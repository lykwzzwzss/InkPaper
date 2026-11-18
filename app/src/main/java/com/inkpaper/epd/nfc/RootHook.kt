package com.inkpaper.epd.nfc

import android.content.Context
import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Root HAL compatibility layer (`libqpollhook.so` + `qpoll-inject`) with 6-minute lease file.
 * Automatically patches outgoing NCI discovery poll type to `0x71` only during active sessions.
 */
object RootHook {
    private const val DIR = "/data/vendor/nfc/qpolllab"
    private const val LIB_NAME = "libqpollhook.so"
    private const val INJECT_NAME = "qpoll-inject"
    private const val LEASE = "$DIR/enable"
    private const val ASSET_DIR = "native/arm64-v8a/"

    private val SU_BINARIES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/debug_ramdisk/su",
        "/sbin/su",
        "su",
    )

    @Volatile
    private var deployedLibPath = "$DIR/$LIB_NAME"

    @Volatile
    private var deployedInjectPath = "$DIR/$INJECT_NAME"

    fun hasRoot(): Boolean = rootProbe().contains("uid=0")

    fun rootProbe(): String = exec("id").trim()

    fun hasSecureSettings(context: Context): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED

    fun grantSecureSettings(context: Context): String {
        if (hasSecureSettings(context)) return "已授予"
        val probe = rootProbe()
        if (!probe.contains("uid=0")) return "无 Root 权限（${firstLine(probe)}）"
        val out = exec("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS 2>&1")
        return if (hasSecureSettings(context)) "授权成功" else "授权失败：${firstLine(out)}"
    }

    /**
     * Stages hashed native hook binaries, injects into NFC HAL if not yet mapped, and activates lease.
     */
    fun ensureReady(context: Context, log: (String) -> Unit): Boolean {
        val probe = rootProbe()
        if (!probe.contains("uid=0")) {
            log("未检测到 Root 权限（${firstLine(probe)}）")
            return false
        }
        if (!deploy(context, log)) return false

        var mapped: String? = null
        for (candidate in listOf(deployedLibPath, "$DIR/$LIB_NAME")) {
            if (exec("$deployedInjectPath $candidate --status").contains("stable=")) {
                mapped = candidate
                break
            }
        }
        if (mapped == null) {
            val injected = exec("$deployedInjectPath $deployedLibPath")
            if (!injected.contains("HAL adapter verified")) {
                log("HAL 兼容层未注入（${firstLine(injected)}）")
                return false
            }
        } else {
            deployedLibPath = mapped
        }

        if (!renewLease()) {
            log("HAL 兼容层租约启用失败，请重启 NFC 后再试")
            return false
        }
        log(if (mapped != null) "HAL 兼容层已就绪（复用现有映射）" else "HAL 兼容层已注入并激活")
        return true
    }

    fun renewLease(): Boolean {
        val out = exec("touch $LEASE ; chown root:root $LEASE ; chmod 0644 $LEASE ; test -f $LEASE && echo LEASE_OK")
        return out.contains("LEASE_OK")
    }

    fun releaseLease() {
        exec("rm -f $LEASE")
    }

    private fun deploy(context: Context, log: (String) -> Unit): Boolean {
        val staging = File(context.cacheDir, "hook")
        if (!staging.isDirectory && !staging.mkdirs()) {
            log("无法创建兼容层缓存目录")
            return false
        }
        val libName = stageAsset(context, LIB_NAME, staging)
        val injectName = stageAsset(context, INJECT_NAME, staging)
        if (libName == null || injectName == null) {
            log("提取兼容层二进制文件失败")
            return false
        }

        val libPath = "$DIR/$libName"
        val injectPath = "$DIR/$injectName"
        deployedLibPath = libPath
        deployedInjectPath = injectPath

        val libTmp = File(staging, libName).absolutePath
        val injectTmp = File(staging, injectName).absolutePath

        val cmd = buildString {
            append("mkdir -p ").append(DIR)
            append(" ; chcon u:object_r:vendor_file:s0 ").append(DIR).append(" 2>/dev/null")
            append(" ; if [ ! -f ").append(libPath).append(" ] ; then cp ").append(libTmp).append(' ').append(libPath)
            append(" && chown root:root ").append(libPath).append(" && chmod 0755 ").append(libPath)
            append(" && chcon u:object_r:vendor_file:s0 ").append(libPath).append(" 2>/dev/null ; fi")
            append(" ; if [ ! -f ").append(injectPath).append(" ] ; then cp ").append(injectTmp).append(' ').append(injectPath)
            append(" && chown root:root ").append(injectPath).append(" && chmod 0755 ").append(injectPath)
            append(" && chcon u:object_r:vendor_file:s0 ").append(injectPath).append(" 2>/dev/null ; fi")
            append(" ; ls ").append(libPath).append(' ').append(injectPath)
        }
        val out = exec(cmd)
        if (out.contains("denied") || out.contains("No such file")) {
            log("部署 HAL 兼容层被拒绝：${firstLine(out)}")
            return false
        }
        return true
    }

    private fun stageAsset(context: Context, assetName: String, staging: File): String? = runCatching {
        context.assets.open(ASSET_DIR + assetName).use { input ->
            val data = input.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }
            val hashedName = "$hash-$assetName"
            val target = File(staging, hashedName)
            if (!target.exists() || target.length() != data.size.toLong()) {
                FileOutputStream(target).use { it.write(data) }
            }
            hashedName
        }
    }.getOrNull()

    private fun exec(command: String): String {
        var ranAny = false
        for (binary in SU_BINARIES) {
            val out = execOnce(binary, command) ?: continue
            ranAny = true
            if (out.isNotBlank()) return out
        }
        return if (ranAny) "" else "No such file or directory"
    }

    private fun execOnce(binary: String, command: String): String? {
        var process: Process? = null
        return try {
            process = ProcessBuilder(binary, "-c", command).redirectErrorStream(true).start()
            val bytes = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            process.inputStream.use { stdout ->
                while (true) {
                    val n = stdout.read(buf)
                    if (n <= 0) break
                    bytes.write(buf, 0, n)
                }
            }
            process.waitFor()
            bytes.toString("UTF-8")
        } catch (_: Throwable) {
            null
        } finally {
            process?.destroy()
        }
    }

    private fun firstLine(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "无输出"
        val nl = trimmed.indexOf('\n')
        return if (nl < 0) trimmed else trimmed.substring(0, nl)
    }
}
