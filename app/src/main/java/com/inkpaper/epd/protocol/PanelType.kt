package com.inkpaper.epd.protocol

/**
 * Physical JD79665AA 552x768 e-ink panel controller row mappings.
 * Hardware variant cannot be inferred from D1 and must match the physical flex/panel.
 */
enum class PanelType(val displayName: String, val subtitle: String) {
    A1("A1", "标准直排"),
    A0("A0", "对折交错"),
    A0X("A0X", "双半区交换");

    fun pack(codes: ByteArray): ByteArray {
        val linear = PanelProtocol.packCodes(codes, PanelProtocol.WIDTH, PanelProtocol.HEIGHT)
        return when (this) {
            A1 -> linear
            A0 -> PanelProtocol.mapA0Rows(linear)
            A0X -> PanelProtocol.mapA0Rows(swapHalves(linear))
        }
    }

    fun unpack(packed: ByteArray): ByteArray {
        require(packed.size == PanelProtocol.IMAGE_BYTES) { "屏幕数据长度错误" }
        if (this == A1) return packed
        val rows = PanelProtocol.WIDTH
        val stride = PanelProtocol.HEIGHT / 4
        val linear = ByteArray(packed.size)
        for (row in 0 until rows) {
            val source = if (row < rows / 2) 2 * row else 2 * rows - 1 - 2 * row
            System.arraycopy(packed, source * stride, linear, row * stride, stride)
        }
        return if (this == A0X) swapHalves(linear) else linear
    }

    fun preview(packed: ByteArray): IntArray =
        ReinkRaster.decodePreview(unpack(packed), PanelProtocol.WIDTH, PanelProtocol.HEIGHT)

    companion object {
        fun fromName(value: String?): PanelType = when (value) {
            "A0" -> A0
            "A1" -> A1
            "A0X" -> A0X
            else -> A1
        }

        private fun swapHalves(linear: ByteArray): ByteArray {
            val half = linear.size / 2
            val out = ByteArray(linear.size)
            System.arraycopy(linear, half, out, 0, half)
            System.arraycopy(linear, 0, out, half, half)
            return out
        }

        /** Scan 00 sends bottom-up pixels; scan 01 reverses each row into top-down pixel order. */
        fun forScan(packed: ByteArray, topDown: Boolean): ByteArray {
            require(packed.size == PanelProtocol.IMAGE_BYTES) { "屏幕数据长度错误" }
            if (!topDown) return packed.clone()
            val stride = PanelProtocol.HEIGHT / 4
            val out = ByteArray(packed.size)
            for (row in 0 until PanelProtocol.WIDTH) {
                val rowOffset = row * stride
                for (i in 0 until stride) {
                    val b = packed[rowOffset + stride - 1 - i].toInt() and 0xFF
                    out[rowOffset + i] = (
                        ((b and 0x03) shl 6) or
                            ((b and 0x0C) shl 2) or
                            ((b and 0x30) shr 2) or
                            ((b and 0xC0) shr 6)
                        ).toByte()
                }
            }
            return out
        }
    }
}
