package com.inkpaper.epd.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.inkpaper.epd.protocol.PanelProtocol
import com.inkpaper.epd.protocol.PanelStatusException
import com.inkpaper.epd.protocol.ProtocolEvent
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

sealed interface SessionEvent {
    data class Scanning(val secondsLeft: Int) : SessionEvent
    data class TagFound(val uidHex: String) : SessionEvent
    data class Stage(val phase: String, val title: String) : SessionEvent
    data class Progress(val done: Int, val total: Int) : SessionEvent
    data class RefreshSuccess(val message: String) : SessionEvent
    data class SessionError(val headline: String, val detail: String) : SessionEvent
    data class Cancelled(val message: String) : SessionEvent
    data class LogLine(val line: String) : SessionEvent
    data class Finished(val wasSuccessful: Boolean) : SessionEvent
}

/**
 * Foreground NFC reader session managing Q-Poll setup, IsoDep transport, watchdog, and cleanup.
 * Always defaults to official D4 power check (`0x00`).
 */
class NfcFlashSession(
    private val activity: Activity,
    private val packedFrame: ByteArray,
    private val officialCheck: Boolean = true,
    private val onEvent: (SessionEvent) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val cancelled = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    @Volatile
    private var readerActive = false

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var currentIsoDep: IsoDep? = null

    @Volatile
    private var lastSignalMs = SystemClock.elapsedRealtime()

    @Volatile
    private var lastPhase = ""

    @Volatile
    private var scanDeadlineMs = 0L

    @Volatile
    private var awaitingTag = false

    @Volatile
    private var sessionSucceeded = false

    private val scanTicker = object : Runnable {
        override fun run() {
            if (finished.get() || !awaitingTag) return
            val left = (((scanDeadlineMs - SystemClock.elapsedRealtime()) + 999) / 1000).toInt().coerceAtLeast(0)
            onEvent(SessionEvent.Scanning(left))
            main.postDelayed(this, 250)
        }
    }

    private val scanTimeoutRunnable = Runnable {
        if (finished.get() || !awaitingTag) return@Runnable
        awaitingTag = false
        onEvent(
            SessionEvent.SessionError(
                headline = "未感应到墨水屏",
                detail = "${SCAN_SECONDS} 秒内未发现屏幕。请将手机背面 NFC 区域紧贴屏幕线圈中心，摘掉厚手机壳后重试。",
            )
        )
        finishSession()
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (finished.get() || awaitingTag) return
            val quietMs = SystemClock.elapsedRealtime() - lastSignalMs
            val limitMs = when (lastPhase) {
                "uploading", "checking", "authenticating" -> 12_000L
                else -> 40_000L
            }
            if (quietMs > limitMs) {
                onEvent(
                    SessionEvent.SessionError(
                        headline = "通信超时中断",
                        detail = "屏幕 ${quietMs / 1000} 秒未回应，请保持手机贴稳不移动后再试。",
                    )
                )
                runCatching { currentIsoDep?.close() }
                finishSession()
                return
            }
            main.postDelayed(this, 2000)
        }
    }

    fun start() {
        val nfc = adapter
        if (nfc == null) {
            onEvent(SessionEvent.SessionError("设备不支持 NFC", "未检测到 NFC 硬件。"))
            finishSession()
            return
        }
        if (!nfc.isEnabled) {
            onEvent(SessionEvent.SessionError("NFC 未开启", "请先在系统设置或控制中心打开 NFC。"))
            finishSession()
            return
        }

        Thread({
            val tier = PrivilegeManager.prepareForSession(activity) { msg ->
                onEvent(SessionEvent.LogLine(msg))
            }
            if (cancelled.get()) {
                onEvent(SessionEvent.Cancelled("已停止"))
                finishSession()
                return@Thread
            }
            if (!tier.readyToFlash) {
                val (head, desc) = when (tier) {
                    PrivilegeTier.SHIZUKU_PENDING -> "需要 Shizuku 授权" to "请在弹窗或状态栏中允许本应用使用 Shizuku 后再刷图。"
                    else -> "缺少寻卡权限" to "Android 16+ 需先启用 Root 授权或开启 Shizuku（无线调试）服务。"
                }
                onEvent(SessionEvent.SessionError(head, desc))
                finishSession()
                return@Thread
            }
            main.post { beginDiscovery(nfc) }
        }, "nfc-prepare").start()
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        awaitingTag = false
        onEvent(SessionEvent.LogLine("用户请求停止当前会话"))
        if (workerThread == null) {
            onEvent(SessionEvent.Cancelled("已取消寻卡"))
            finishSession()
        }
    }

    private fun beginDiscovery(nfc: NfcAdapter) {
        if (finished.get() || cancelled.get()) return
        readerActive = true
        lastSignalMs = SystemClock.elapsedRealtime()

        if (DEAD_SCREEN_AFTER_REFRESH) {
            // K90 / Android 16+: enable QTag BEFORE enableReaderMode so controller uses Q-Poll from frame 0.
            val qtagCode = sendQtag(nfc, 0x01)
            onEvent(SessionEvent.LogLine("QTag(1) 返回=$qtagCode"))
            if (qtagCode != 0) {
                onEvent(
                    SessionEvent.SessionError(
                        headline = "Q-Poll 开启失败 ($qtagCode)",
                        detail = "系统拒绝发起私有寻卡。若刚安装或更新过，请确认 Root / Shizuku 授权状态。",
                    )
                )
                finishSession()
                return
            }
            runCatching {
                nfc.enableReaderMode(
                    activity,
                    { tag -> main.post { onTagDiscovered(tag) } },
                    READER_FLAGS,
                    readerOptions(),
                )
                if (ShizukuChannel.available()) {
                    val resumed = ShizukuChannel.resumePolling()
                    onEvent(SessionEvent.LogLine("Shizuku resumePolling=$resumed"))
                }
            }.onFailure { err ->
                onEvent(SessionEvent.SessionError("启动前台读卡失败", err.message ?: err.toString()))
                finishSession()
                return
            }
        } else {
            // Android <= 15 (12T Pro path): disable+enable to clear lingering connected guard, then QTag & narrow.
            runCatching {
                nfc.disableReaderMode(activity)
                nfc.enableReaderMode(
                    activity,
                    { tag -> main.post { onTagDiscovered(tag) } },
                    READER_FLAGS,
                    readerOptions(),
                )
            }.onFailure { err ->
                onEvent(SessionEvent.SessionError("启动前台读卡失败", err.message ?: err.toString()))
                finishSession()
                return
            }
            val qtagCode = sendQtag(nfc, 0x01)
            onEvent(SessionEvent.LogLine("QTag(1) 返回=$qtagCode"))
            narrowDiscoveryToQPoll()
        }

        awaitingTag = true
        scanDeadlineMs = SystemClock.elapsedRealtime() + SCAN_SECONDS * 1000L
        main.post(scanTicker)
        main.postDelayed(scanTimeoutRunnable, SCAN_SECONDS * 1000L)
    }

    private fun onTagDiscovered(tag: Tag) {
        if (finished.get() || workerThread != null || !awaitingTag) return
        awaitingTag = false
        main.removeCallbacks(scanTicker)
        main.removeCallbacks(scanTimeoutRunnable)

        val uidHex = PanelProtocol.toHex(tag.id)
        onEvent(SessionEvent.TagFound(uidHex))
        onEvent(SessionEvent.LogLine("发现屏幕标签 UID=$uidHex"))
        touchSignal("connecting")

        val thread = Thread({
            try {
                executeWithRetry(tag)
            } catch (_: InterruptedException) {
                onEvent(SessionEvent.Cancelled("已停止传输，请查看屏幕状态"))
            } catch (e: PanelStatusException) {
                val (headline, advice) = explainPanelError(e)
                onEvent(SessionEvent.SessionError(headline, advice))
                onEvent(SessionEvent.LogLine("协议中断: ${e.message}"))
            } catch (e: IOException) {
                onEvent(
                    SessionEvent.SessionError(
                        headline = "连接中途断开",
                        detail = "屏幕移开或贴合松动（${e.message ?: "IO 异常"}），请贴稳重刷。",
                    )
                )
                onEvent(SessionEvent.LogLine("IO 错误: ${e.message}"))
            } catch (e: Throwable) {
                onEvent(SessionEvent.SessionError("刷屏失败", e.message ?: e.javaClass.simpleName))
                onEvent(SessionEvent.LogLine("异常: ${e.stackTraceToString().lineSequence().take(4).joinToString(" | ")}"))
            } finally {
                finishSession()
            }
        }, "epd-flash")
        workerThread = thread
        thread.start()
        main.postDelayed(watchdogRunnable, 3000)
    }

    private fun executeWithRetry(initialTag: Tag) {
        val targetUid = initialTag.id.copyOf()
        for (attempt in 1..3) {
            try {
                runSingleIsoSession(initialTag)
                return
            } catch (e: BeforeApduException) {
                if (attempt >= 3 || cancelled.get()) throw e.cause ?: e
                onEvent(SessionEvent.LogLine("尚未发送指令即连接失败，重连尝试 ${attempt + 1}/3"))
                Thread.sleep(200)
                if (cancelled.get()) throw InterruptedException("已停止")
                if (!initialTag.id.contentEquals(targetUid)) {
                    throw IOException("标签 UID 变更，已终止")
                }
            }
        }
    }

    private fun runSingleIsoSession(tag: Tag) {
        val deadline = SystemClock.elapsedRealtime() + 240_000L
        val control = object : PanelProtocol.Control {
            override fun now(): Long = SystemClock.elapsedRealtime()
            override fun sleep(ms: Long) = Thread.sleep(ms)
            override fun check() {
                if (cancelled.get()) throw InterruptedException("用户已停止")
                if (now() > deadline) throw IllegalStateException("会话超过总时长上限")
            }

            override fun emit(event: ProtocolEvent) {
                when (event) {
                    is ProtocolEvent.Stage -> {
                        touchSignal(event.phase)
                        onEvent(SessionEvent.Stage(event.phase, event.title))
                        onEvent(SessionEvent.LogLine("阶段: ${event.title}"))
                    }
                    is ProtocolEvent.Identity -> {
                        touchSignal("identity")
                        onEvent(SessionEvent.LogLine("屏幕信息: ${event.description}"))
                    }
                    is ProtocolEvent.Progress -> {
                        touchSignal("uploading")
                        onEvent(SessionEvent.Progress(event.done, event.total))
                    }
                    is ProtocolEvent.HoldTick -> {
                        touchSignal("refreshing")
                        onEvent(SessionEvent.Stage("refreshing", event.message))
                    }
                    is ProtocolEvent.RefreshDone -> {
                        sessionSucceeded = true
                        touchSignal("done")
                        onEvent(SessionEvent.RefreshSuccess(event.message))
                        onEvent(SessionEvent.LogLine("完成: ${event.message}"))
                    }
                    is ProtocolEvent.Log -> {
                        touchSignal(lastPhase)
                        onEvent(SessionEvent.LogLine("${event.kind}: ${event.message}"))
                    }
                }
            }
        }

        control.check()
        val iso = IsoDep.get(tag) ?: throw IllegalArgumentException("此标签不支持 ISO-DEP")
        currentIsoDep = iso
        try {
            val started = SystemClock.elapsedRealtime()
            control.emit(ProtocolEvent.Stage("connecting", "建立 NFC 连接"))
            try {
                iso.connect()
            } catch (e: IOException) {
                throw BeforeApduException(e)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            onEvent(SessionEvent.LogLine("ISO-DEP 已连接 (${elapsed}ms, max=${iso.maxTransceiveLength})"))
            require(iso.maxTransceiveLength >= 257) { "设备单包 APDU 上限不足 257 字节" }

            val protocol = PanelProtocol(
                transport = { cmd, timeoutMs ->
                    control.check()
                    val remaining = (deadline - control.now()).coerceAtLeast(1L).toInt()
                    iso.timeout = minOf(timeoutMs, remaining)
                    iso.transceive(cmd)
                },
                control = control,
            )
            protocol.write(packedFrame, officialCheck = officialCheck)
        } finally {
            currentIsoDep = null
            runCatching { iso.close() }
        }
    }

    private fun finishSession() {
        if (!finished.compareAndSet(false, true)) return
        awaitingTag = false
        main.post {
            main.removeCallbacks(scanTicker)
            main.removeCallbacks(scanTimeoutRunnable)
            main.removeCallbacks(watchdogRunnable)
            if (readerActive) {
                runCatching { adapter?.disableReaderMode(activity) }
                if (DEAD_SCREEN_AFTER_REFRESH) {
                    adapter?.let { sendQtag(it, 0x00) }
                    onEvent(SessionEvent.LogLine("已关闭 Q-Poll 磁场，等待屏幕充电复位"))
                } else {
                    // Android <= 15: restore normal foreground reader routing & drop QTag until next start.
                    adapter?.let { sendQtag(it, 0x00) }
                }
                readerActive = false
            }
            Thread({ RootHook.releaseLease() }, "lease-release").start()
            onEvent(SessionEvent.Finished(sessionSucceeded))
        }
    }

    private fun touchSignal(phase: String) {
        lastSignalMs = SystemClock.elapsedRealtime()
        if (phase.isNotEmpty()) lastPhase = phase
    }

    private fun readerOptions(): Bundle = Bundle().apply {
        putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_DELAY_MS)
    }

    private fun sendQtag(nfc: NfcAdapter, action: Int): Int {
        val self = sendQtagAsSelf(nfc, action)
        if (self == 0) return 0
        if (ShizukuChannel.available()) {
            val shell = ShizukuChannel.sendQtag(action)
            if (shell != ShizukuChannel.UNAVAILABLE) return shell
        }
        return self
    }

    private fun sendQtagAsSelf(nfc: NfcAdapter, action: Int): Int {
        // 1) Android 16+ public NfcAdapter.sendVendorNciMessage (unlocked by WRITE_SECURE_SETTINGS)
        runCatching {
            val m = NfcAdapter::class.java.getMethod(
                "sendVendorNciMessage",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
            )
            val code = m.invoke(nfc, 1, 15, 112, byteArrayOf(0x31, action.toByte()))
            if (code is Int && code == 0) return 0
        }

        // 2) Hidden INfcAdapter reflection fallback (Android <= 15)
        return runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "nfc") as? IBinder
                ?: return@runCatching -1
            val service = Class.forName("android.nfc.INfcAdapter\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return@runCatching -1
            val target: Method = service.javaClass.methods.firstOrNull {
                it.name == "sendVendorNciMessage" && it.parameterTypes.size == 4
            } ?: return@runCatching -1
            target.isAccessible = true
            val code = target.invoke(service, 1, 15, 112, byteArrayOf(0x31, action.toByte()))
            (code as? Int) ?: -1
        }.getOrDefault(-1)
    }

    private fun narrowDiscoveryToQPoll() {
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "nfc") as? IBinder ?: return
            val service = Class.forName("android.nfc.INfcAdapter\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return
            val upd = service.javaClass.methods.firstOrNull { it.name == "updateDiscoveryTechnology" } ?: return
            val token = android.os.Binder()
            when (upd.parameterCount) {
                2 -> upd.invoke(service, token, 0x71)
                3 -> upd.invoke(service, token, 0x71, 0)
                else -> upd.invoke(service, *arrayOf<Any?>(token, 0x71, 0, null).copyOfRange(0, upd.parameterCount))
            }
        }
    }

    private fun explainPanelError(e: PanelStatusException): Pair<String, String> = when (e.statusWord) {
        0x68CA, 0x6502, 0x698A -> "贴合供电不足 (${"%04X".format(e.statusWord)})" to
            "图片已传完但墨水屏升压自检未通过。请取下较厚手机壳，将背面 NFC 天线紧贴屏幕线圈中心后再试。"
        0x68CB -> "刷新超时 (68CB)" to "屏幕刷新未在时限内完成，请贴紧后重试。"
        else -> "屏幕返回错误 (${"%04X".format(e.statusWord)})" to e.statusMeaning
    }

    private class BeforeApduException(cause: IOException) : IOException(cause)

    companion object {
        private val DEAD_SCREEN_AFTER_REFRESH = Build.VERSION.SDK_INT >= 36
        private const val PRESENCE_DELAY_MS = 90_000
        private const val SCAN_SECONDS = 30
        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        fun teardown(activity: Activity) {
            runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
            runCatching {
                val sm = Class.forName("android.os.ServiceManager")
                val binder = sm.getMethod("getService", String::class.java).invoke(null, "nfc") as? IBinder ?: return
                val service = Class.forName("android.nfc.INfcAdapter\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder) ?: return
                val m = service.javaClass.methods.firstOrNull {
                    it.name == "sendVendorNciMessage" && it.parameterTypes.size == 4
                }
                m?.invoke(service, 1, 15, 112, byteArrayOf(0x31, 0x00))
            }
            Thread({ RootHook.releaseLease() }, "lease-teardown").start()
        }
    }
}
