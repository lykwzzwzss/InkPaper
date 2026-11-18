package com.inkpaper.epd.nfc

import android.content.Context
import android.nfc.NfcAdapter
import android.os.Build

/**
 * Automatic highest-privilege selector: Root > Shizuku Shell > System/Native API.
 * Removes manual auth toggles from the user interface while keeping K90/12T compatibility intact.
 */
enum class PrivilegeTier(
    val shortBadge: String,
    val title: String,
    val detail: String,
    val readyToFlash: Boolean,
) {
    ROOT(
        shortBadge = "Root 已就绪",
        title = "已启用 Root 最高权限",
        detail = "自动授予 NFC 权限并接管 HAL 兼容层，无需额外操作。",
        readyToFlash = true,
    ),
    SHIZUKU_READY(
        shortBadge = "Shizuku 就绪",
        title = "已连接 Shizuku Shell 通道",
        detail = "通过无线调试 / Shell 身份发送 Q-Poll 寻卡指令（免 Root）。",
        readyToFlash = true,
    ),
    SHIZUKU_PENDING(
        shortBadge = "Shizuku 待授权",
        title = "检测到 Shizuku 服务未授权",
        detail = "点击下方「授权 Shizuku」即可自动连接（若已Root请在管理器允许）。",
        readyToFlash = false,
    ),
    NATIVE_PUBLIC(
        shortBadge = "原生已就绪",
        title = "已具备系统 NFC 权限",
        detail = "直接通过公开 NFC 接口发起 Q-Poll 寻卡，无需 Root 或 Shizuku。",
        readyToFlash = true,
    ),
    NATIVE_LEGACY(
        shortBadge = "标准直连",
        title = "系统支持免权限直连",
        detail = "Android 15 及以下可直接调用 NFC 通道。",
        readyToFlash = true,
    ),
    NEEDS_ACCESS(
        shortBadge = "需授权",
        title = "未检测到 Root 或 Shizuku",
        detail = "Android 16+ 发送 Q-Poll 寻卡需要 Root 授权或启动 Shizuku（无线调试）。",
        readyToFlash = false,
    ),
}

data class PrivilegeSnapshot(
    val tier: PrivilegeTier,
    val nfcPresent: Boolean,
    val nfcEnabled: Boolean,
    val hasRoot: Boolean,
    val hasSecureSettings: Boolean,
    val shizukuAlive: Boolean,
    val shizukuGranted: Boolean,
    val chipSummary: String,
)

object PrivilegeManager {
    fun inspect(context: Context): PrivilegeSnapshot {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        val nfcPresent = adapter != null
        val nfcEnabled = adapter?.isEnabled == true
        val hasRoot = RootHook.hasRoot()
        val hasSecure = RootHook.hasSecureSettings(context)
        val shizukuAlive = ShizukuChannel.isBinderAlive()
        val shizukuGranted = ShizukuChannel.hasPermission()

        val tier = when {
            hasRoot -> PrivilegeTier.ROOT
            shizukuAlive && shizukuGranted -> PrivilegeTier.SHIZUKU_READY
            shizukuAlive && !shizukuGranted -> PrivilegeTier.SHIZUKU_PENDING
            hasSecure -> PrivilegeTier.NATIVE_PUBLIC
            Build.VERSION.SDK_INT < 36 -> PrivilegeTier.NATIVE_LEGACY
            else -> PrivilegeTier.NEEDS_ACCESS
        }

        return PrivilegeSnapshot(
            tier = tier,
            nfcPresent = nfcPresent,
            nfcEnabled = nfcEnabled,
            hasRoot = hasRoot,
            hasSecureSettings = hasSecure,
            shizukuAlive = shizukuAlive,
            shizukuGranted = shizukuGranted,
            chipSummary = ChipInfo.summary(),
        )
    }

    /**
     * Automatically prepares the highest available privilege before starting NFC discovery.
     * Re-probes Root at call time in case the user just approved it in KernelSU / Magisk.
     */
    fun prepareForSession(context: Context, log: (String) -> Unit): PrivilegeTier {
        val hasRoot = RootHook.hasRoot()
        if (hasRoot) {
            if (!RootHook.hasSecureSettings(context)) {
                val res = RootHook.grantSecureSettings(context)
                log("Root 自动授予 WRITE_SECURE_SETTINGS：$res")
            }
            RootHook.ensureReady(context, log)
            return PrivilegeTier.ROOT
        }

        if (ShizukuChannel.hasPermission()) {
            val connected = ShizukuChannel.ensureConnected(context)
            if (connected) {
                log("权限通道：Shizuku Shell 已连接")
                return PrivilegeTier.SHIZUKU_READY
            }
        }

        if (RootHook.hasSecureSettings(context)) {
            log("权限通道：公开 NFC 接口（已授予权限）")
            return PrivilegeTier.NATIVE_PUBLIC
        }

        if (Build.VERSION.SDK_INT < 36) {
            log("权限通道：标准直连（Android ${Build.VERSION.RELEASE}）")
            return PrivilegeTier.NATIVE_LEGACY
        }

        return if (ShizukuChannel.isBinderAlive()) PrivilegeTier.SHIZUKU_PENDING else PrivilegeTier.NEEDS_ACCESS
    }
}
