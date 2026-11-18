package com.inkpaper.epd.nfc

import java.io.File

object ChipInfo {
    private val FW_CANDIDATES = listOf(
        "/odm/etc/libnfc-nxp.conf",
        "/vendor/etc/libnfc-nxp.conf",
        "/vendor/etc/libnfc-nxp_RF.conf",
        "/system/etc/libnfc-nxp.conf",
        "/etc/libnfc-nxp.conf",
    )

    fun summary(): String {
        for (path in FW_CANDIDATES) {
            val file = File(path)
            if (!file.isFile || !file.canRead()) continue
            val found = runCatching {
                file.useLines { lines ->
                    lines.map { it.trim() }
                        .filter { !it.startsWith("#") && it.startsWith("NXP_FW_NAME") }
                        .map { it.substringAfter('=', "").trim().trim('"') }
                        .firstOrNull { it.isNotEmpty() }
                }
            }.getOrNull()
            if (found != null) return "$found (${file.name})"
        }
        return "通用 NFC 控制器"
    }
}
