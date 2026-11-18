package com.inkpaper.epd.protocol

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * Protocol and factory scan packing adapted from kur0neko/huawei-epd-pn532.
 * Required Notice: Copyright (c) 2026 kur0neko
 * PolyForm Noncommercial 1.0.0; see bundled assets/licenses/kur0neko-LICENSE.txt.
 */
class PanelStatusException(
    val statusWord: Int,
    val stepLabel: String,
    val statusMeaning: String,
) : IllegalStateException(
    "$stepLabel：SW=${"%04X".format(statusWord)}（$statusMeaning）"
)

sealed interface ProtocolEvent {
    data class Stage(val phase: String, val title: String) : ProtocolEvent
    data class Identity(val uid: String, val topDown: Boolean, val description: String) : ProtocolEvent
    data class Progress(val done: Int, val total: Int) : ProtocolEvent
    data class HoldTick(val secondsLeft: Int, val message: String) : ProtocolEvent
    data class RefreshDone(val officialCheck: Boolean, val message: String) : ProtocolEvent
    data class Log(val kind: String, val message: String) : ProtocolEvent
}

class PanelProtocol(
    private val transport: Transport,
    private val control: Control,
) {
    fun interface Transport {
        fun exchange(command: ByteArray, timeoutMs: Int): ByteArray
    }

    interface Control {
        fun now(): Long
        fun sleep(ms: Long)
        fun check()
        fun emit(event: ProtocolEvent)
    }

    data class Info(val fields: Map<Int, ByteArray>) {
        val uid: String = fields[0xC1]?.let(::toHex) ?: "未提供"
        val topDown: Boolean get() = fields[0xA1]?.get(0)?.toInt() == 1
        val description: String get() = "UID=$uid · 552×768 四色 · ${if (topDown) "扫描01" else "扫描00"}"
    }

    fun identify(): Info {
        control.emit(ProtocolEvent.Stage("checking", "读取并校验屏幕规格"))
        pause(1000)
        val info = parseInfo(exchange(D1, "D1", 5000, requireOk = true))
        control.emit(ProtocolEvent.Identity(info.uid, info.topDown, info.description))
        return info
    }

    /**
     * Writes a packed 552x768 image and triggers refresh.
     * Official check (`officialCheck = true`, D4 byte 4 = `0x00`) waits until panel refresh finishes.
     */
    fun write(packedFrame: ByteArray, officialCheck: Boolean = true): Boolean {
        require(packedFrame.size == IMAGE_BYTES) { "图像必须为 $IMAGE_BYTES 字节" }
        val refreshCmd = d4(POWER_MS, officialCheck)
        control.emit(
            ProtocolEvent.Log(
                "TIMING",
                "上电等待=$POWER_MS ms · 查电=${if (officialCheck) "官方模式(0x00)" else "快速模式(0x80)"}"
            )
        )

        val initial = identify()
        val wireImage = PanelType.forScan(packedFrame, initial.topDown)
        val wireSha = toHex(MessageDigest.getInstance("SHA-256").digest(wireImage))
        control.emit(ProtocolEvent.Log("SCAN", "扫描=${if (initial.topDown) "01" else "00"} · SHA=${wireSha.take(12)}"))

        control.emit(ProtocolEvent.Stage("authenticating", "验证屏幕写入权限"))
        val access = exchange(D8, "D8 权限查询", 5000, requireOk = false)
        if (status(access) == 0x6985) {
            exchange(PIN, "PIN 验证", 5000, requireOk = true)
            exchange(D8, "D8 复检", 5000, requireOk = true)
        } else {
            requireOk(access, "D8 权限查询")
        }

        val rechecked = parseInfo(exchange(D1, "D1 写入前复核", 5000, requireOk = true))
        require(initial.topDown == rechecked.topDown) { "屏幕配置在会话中发生变化，已终止传图" }

        control.emit(ProtocolEvent.Stage("uploading", "正在传输图像，请保持贴合"))
        val blockCount = (wireImage.size + 1999) / 2000
        val blocks = Array(blockCount) { blockIdx ->
            val start = blockIdx * 2000
            val end = min(start + 2000, wireImage.size)
            compressedLzo(wireImage.copyOfRange(start, end))
        }
        val totalPackets = blocks.sumOf { (it.size + 249) / 250 }
        control.emit(ProtocolEvent.Log("PAYLOAD", "共 $totalPackets 包（LZO1X 压缩）"))
        control.emit(ProtocolEvent.Progress(0, totalPackets))

        var sentPackets = 0
        for (blockIdx in blocks.indices) {
            val blockData = blocks[blockIdx]
            var subIdx = 0
            while (subIdx * 250 < blockData.size) {
                exchange(d3(blockData, blockIdx, subIdx), "D3 $blockIdx/$subIdx", 5000, requireOk = true)
                sentPackets++
                if (sentPackets % 6 == 0 || sentPackets == totalPackets) {
                    control.emit(ProtocolEvent.Progress(sentPackets, totalPackets))
                }
                subIdx++
            }
        }

        control.emit(
            ProtocolEvent.Stage(
                "refreshing",
                if (officialCheck) "正在刷新屏幕（官方查电中，请稳住别动）" else "正在启动屏幕刷新"
            )
        )
        control.check()

        var finalStatus = 0
        for (attempt in 1..3) {
            val response = exchange(refreshCmd, "D4 刷新", 30_000, requireOk = false)
            finalStatus = status(response)
            if (finalStatus == 0x9000 || finalStatus == 0x68CA) break
            if (finalStatus == 0x6986 && attempt < 3) {
                control.emit(ProtocolEvent.Log("BUSY", "屏幕忙 (6986)，重试启动刷新 (${attempt + 1}/3)"))
                pause(1000)
                continue
            }
            break
        }

        if (finalStatus == 0x9000) {
            if (officialCheck) {
                control.emit(
                    ProtocolEvent.RefreshDone(
                        officialCheck = true,
                        message = "官方查电确认刷新完成，请看屏幕效果"
                    )
                )
                return true
            }
            holdFieldAfterFastD4()
            control.emit(
                ProtocolEvent.RefreshDone(
                    officialCheck = false,
                    message = "供能 $HOLD_SECONDS 秒完成，请看屏幕效果"
                )
            )
            return true
        }

        val detailMeaning = meaning(finalStatus)
        control.emit(
            ProtocolEvent.Log(
                "REFRESH_UNCONFIRMED",
                "D4 未通过：SW=${"%04X".format(finalStatus)}（$detailMeaning）"
            )
        )
        throw PanelStatusException(finalStatus, "D4 刷新", detailMeaning)
    }

    private fun holdFieldAfterFastD4() {
        val deadline = control.now() + HOLD_SECONDS * 1000L
        var nextTick = control.now() + 1000L
        while (control.now() < deadline) {
            control.sleep(DEFAULT_POLL_MS)
            if (control.now() >= nextTick) {
                val left = max(0, ((deadline - control.now() + 999) / 1000).toInt())
                if (left > 0) {
                    control.emit(ProtocolEvent.HoldTick(left, "正在刷新并持续供能 · 还剩 $left 秒"))
                }
                nextTick += 1000L
            }
            pause(DEFAULT_POLL_MS)
        }
    }

    private fun exchange(cmd: ByteArray, label: String, timeoutMs: Int, requireOk: Boolean): ByteArray {
        control.check()
        val response = transport.exchange(cmd, timeoutMs)
        val sw = status(response)
        val ins = cmd[1].toInt() and 0xFF
        if (ins != 0xD3 && ins != 0xDE) {
            control.emit(ProtocolEvent.Log("APDU", "$label → SW=${"%04X".format(sw)}"))
        }
        if (requireOk) requireOk(response, label)
        return response
    }

    private fun pause(ms: Long) {
        val until = control.now() + ms
        while (control.now() < until) {
            control.check()
            control.sleep(min(100L, until - control.now()))
        }
        control.check()
    }

    companion object {
        const val WIDTH = 552
        const val HEIGHT = 768
        const val IMAGE_BYTES = 105_984
        const val POWER_MS = 500
        const val HOLD_SECONDS = 20
        const val DEFAULT_POLL_MS = 500L

        val D1 = hex("00D1000000")
        val D8 = hex("F0D8000005000000000E")
        private val PIN = hex("00200001055265699919")
        private val GEOMETRY = hex("F0C72006000228")
        private val SCAN = hex("00120030FFFFFF")

        private val EXPECTED_SCAN_ALT = hex("01120030FFFFFF")
        private val EXPECTED_APP_ID = hex("201EA001")
        private val EXPECTED_APP_ID_ALT = hex("00000001")

        fun hex(s: String): ByteArray {
            require((s.length and 1) == 0) { "Odd hex length" }
            return ByteArray(s.length / 2) { i ->
                s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        fun toHex(data: ByteArray): String = buildString(data.size * 2) {
            for (b in data) append("%02X".format(b.toInt() and 0xFF))
        }

        fun status(response: ByteArray): Int {
            require(response.size >= 2) { "回包缺少状态字" }
            val hi = response[response.size - 2].toInt() and 0xFF
            val lo = response[response.size - 1].toInt() and 0xFF
            return (hi shl 8) or lo
        }

        fun meaning(sw: Int): String = when (sw) {
            0x6502, 0x68CA, 0x698A -> "供电或贴合耦合不足"
            0x68CB -> "屏幕刷新超时"
            0x6992 -> "数据解压失败"
            0x6993 -> "解压后数据长度错误"
            0x6995 -> "图像分包序号错误"
            0x6982 -> "安全状态不满足"
            0x6985 -> "访问条件不满足"
            0x6986, 0x68C6 -> "屏幕正在刷新中"
            else -> "设备返回错误"
        }

        fun requireOk(response: ByteArray, label: String): ByteArray {
            val sw = status(response)
            if (sw != 0x9000) throw PanelStatusException(sw, label, meaning(sw))
            return response.copyOf(response.size - 2)
        }

        fun parseInfo(response: ByteArray): Info {
            val data = requireOk(response, "D1 身份读取")
            val fields = LinkedHashMap<Int, ByteArray>()
            var i = 0
            while (i < data.size) {
                require(i + 2 <= data.size) { "D1 TLV 头截断" }
                val tag = data[i++].toInt() and 0xFF
                val len = data[i++].toInt() and 0xFF
                require(!fields.containsKey(tag) && i + len <= data.size) { "D1 TLV 重复或截断" }
                fields[tag] = data.copyOfRange(i, i + len)
                i += len
            }

            val requiredLens = mapOf(0xA0 to 7, 0xA1 to 7, 0xB1 to 1, 0xB2 to 1, 0xC0 to 4, 0xD1 to 7)
            val errors = StringBuilder()
            for ((tag, expectedLen) in requiredLens) {
                val actual = fields[tag]
                if (actual == null || actual.size != expectedLen) {
                    errors.append("\n${"%02X".format(tag)} 长度不符: 期望=$expectedLen")
                }
            }

            val checks = listOf(
                Triple(0xA0, GEOMETRY, null),
                Triple(0xA1, SCAN, EXPECTED_SCAN_ALT),
                Triple(0xC0, EXPECTED_APP_ID, EXPECTED_APP_ID_ALT),
                Triple(0xB1, hex("02"), null),
                Triple(0xD1, hex("01"), null),
            )
            for ((tag, expected, alt) in checks) {
                val actual = fields[tag] ?: continue
                if (actual.isEmpty()) continue
                val ok = if (tag == 0xD1) actual[0].toInt() == 1
                else actual.contentEquals(expected) || (alt != null && actual.contentEquals(alt))
                if (!ok) {
                    errors.append("\n${"%02X".format(tag)} 配置不匹配: 实际=${toHex(actual)}")
                }
            }
            require(errors.isEmpty()) { "屏幕出厂配置与已验证规格不匹配:$errors" }
            return Info(fields)
        }

        fun packCodes(codes: ByteArray, width: Int, height: Int): ByteArray {
            require(width > 0 && height > 0 && height % 4 == 0 && codes.size == width * height) {
                "像素尺寸错误"
            }
            val packed = ByteArray(width * height / 4)
            var out = 0
            for (x in width - 1 downTo 0) {
                var y = height - 1
                while (y >= 0) {
                    var value = 0
                    for (n in 0 until 4) {
                        val code = codes[(y - n) * width + x].toInt() and 0xFF
                        require(code <= 3) { "颜色码错误: $code" }
                        value = value or (code shl (6 - 2 * n))
                    }
                    packed[out++] = value.toByte()
                    y -= 4
                }
            }
            return packed
        }

        fun mapA0Rows(packed: ByteArray): ByteArray {
            require(packed.size == IMAGE_BYTES) { "图像必须为 $IMAGE_BYTES 字节" }
            val out = ByteArray(packed.size)
            val stride = HEIGHT / 4
            for (row in 0 until WIDTH) {
                val target = if (row < WIDTH / 2) 2 * row else 2 * WIDTH - 1 - 2 * row
                System.arraycopy(packed, row * stride, out, target * stride, stride)
            }
            return out
        }

        fun literalLzo(raw: ByteArray): ByteArray {
            require(raw.size in 1..2000) { "LZO 块尺寸错误" }
            val out = ByteArrayOutputStream(raw.size + 16)
            if (raw.size <= 238) {
                out.write(17 + raw.size)
            } else {
                out.write(0)
                var rest = raw.size - 18
                while (rest > 255) {
                    out.write(0)
                    rest -= 255
                }
                out.write(rest)
            }
            out.write(raw)
            out.write(0x11)
            out.write(0)
            out.write(0)
            return out.toByteArray()
        }

        fun compressedLzo(raw: ByteArray): ByteArray {
            require(raw.size in 1..2000) { "LZO 块尺寸错误" }
            val writer = LzoWriter(raw.size + raw.size / 8 + 80)
            val head = IntArray(1 shl 14) { -1 }
            val prev = IntArray(raw.size) { -1 }
            var literalStart = 0
            var pos = 0
            var lastMatch = -1

            while (pos + 2 < raw.size) {
                var bestPos = -1
                var bestLen = 0
                if (pos > 0) {
                    val key = hash(raw, pos)
                    var candidate = head[key]
                    var visited = 0
                    while (candidate >= 0 && pos - candidate <= 0x4000 && visited++ < 64) {
                        val maxLen = raw.size - pos
                        var len = 0
                        while (len < maxLen && raw[candidate + len] == raw[pos + len]) len++
                        if (len > bestLen || (len == bestLen && len >= 3 && pos - candidate < pos - bestPos)) {
                            bestLen = len
                            bestPos = candidate
                        }
                        if (bestLen == maxLen) break
                        candidate = prev[candidate]
                    }
                }
                val current = pos
                val key = hash(raw, current)
                prev[current] = head[key]
                head[key] = current

                if (bestLen >= 3) {
                    writer.literal(raw, literalStart, current - literalStart, lastMatch)
                    lastMatch = writer.match(bestLen, current - bestPos)
                    val endInsert = min(current + bestLen, raw.size - 2)
                    for (ins in (current + 1) until endInsert) {
                        val k = hash(raw, ins)
                        prev[ins] = head[k]
                        head[k] = ins
                    }
                    pos = current + bestLen
                    literalStart = pos
                } else {
                    pos++
                }
            }

            writer.literal(raw, literalStart, raw.size - literalStart, lastMatch)
            writer.write(0x11)
            writer.write(0)
            writer.write(0)
            val literal = literalLzo(raw)
            return if (writer.size < literal.size) writer.toByteArray() else literal
        }

        private fun hash(data: ByteArray, pos: Int): Int {
            val v = ((data[pos].toInt() and 0xFF) shl 16) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                (data[pos + 2].toInt() and 0xFF)
            return ((v * 0x9E3779B1.toInt()) ushr 18) and ((1 shl 14) - 1)
        }

        fun d3(compressed: ByteArray, block: Int, sub: Int): ByteArray {
            require(block in 0..255 && sub in 0..255 && sub * 250 < compressed.size) { "D3 编号错误" }
            val offset = sub * 250
            val count = min(250, compressed.size - offset)
            val packet = ByteArray(count + 7)
            packet[0] = 0xF0.toByte()
            packet[1] = 0xD3.toByte()
            packet[2] = 0
            packet[3] = if (offset + count == compressed.size) 1 else 0
            packet[4] = (count + 2).toByte()
            packet[5] = block.toByte()
            packet[6] = sub.toByte()
            System.arraycopy(compressed, offset, packet, 7, count)
            return packet
        }

        fun d4(powerMs: Int = POWER_MS, officialCheck: Boolean = true): ByteArray {
            require(powerMs in 100..12700 && powerMs % 100 == 0) { "上电等待参数错误" }
            return byteArrayOf(
                0xF0.toByte(),
                0xD4.toByte(),
                (0x80 or (powerMs / 100)).toByte(),
                (if (officialCheck) 0x00 else 0x80).toByte(),
                0,
            )
        }
    }

    private class LzoWriter(capacity: Int) {
        private val buffer = ByteArray(capacity)
        var size: Int = 0
            private set

        fun write(value: Int) {
            buffer[size++] = value.toByte()
        }

        fun append(raw: ByteArray, offset: Int, length: Int) {
            System.arraycopy(raw, offset, buffer, size, length)
            size += length
        }

        fun literal(raw: ByteArray, offset: Int, length: Int, lastMatch: Int) {
            if (length <= 0) return
            when {
                length <= 3 && lastMatch >= 0 -> buffer[lastMatch] = (buffer[lastMatch].toInt() or length).toByte()
                size == 0 && length <= 238 -> write(17 + length)
                length <= 18 -> write(length - 3)
                else -> {
                    write(0)
                    zeroLength(length - 18)
                }
            }
            append(raw, offset, length)
        }

        fun match(length: Int, offset: Int): Int {
            require(offset >= 1) { "LZO 偏移错误" }
            var dist = offset - 1
            if (length <= 8 && dist < 0x0800) {
                write(((length - 1) shl 5) or ((dist and 7) shl 2))
                write(dist ushr 3)
            } else {
                if (length <= 33) write(0x20 or (length - 2))
                else {
                    write(0x20)
                    zeroLength(length - 33)
                }
                write(dist shl 2)
                write(dist ushr 6)
            }
            return size - 2
        }

        private fun zeroLength(len: Int) {
            var rest = len
            while (rest > 255) {
                write(0)
                rest -= 255
            }
            write(rest)
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}
