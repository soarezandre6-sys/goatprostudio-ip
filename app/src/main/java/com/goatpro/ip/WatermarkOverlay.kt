package com.goatpro.ip

/**
 * Lightweight GOAT Cam watermark rendered directly into the NV21 luminance plane.
 * This keeps the normal MJPEG/H.264 pipeline zero-copy after CameraX conversion.
 * The direct front 4K path is a Pro-only Surface path and intentionally bypasses it.
 */
object WatermarkOverlay {
    private const val TEXT = "GOAT CAM FREE"

    private val glyphs: Map<Char, IntArray> = mapOf(
        'A' to intArrayOf(0b01110,0b10001,0b10001,0b11111,0b10001,0b10001,0b10001),
        'C' to intArrayOf(0b01111,0b10000,0b10000,0b10000,0b10000,0b10000,0b01111),
        'E' to intArrayOf(0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b11111),
        'F' to intArrayOf(0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b10000),
        'G' to intArrayOf(0b01111,0b10000,0b10000,0b10111,0b10001,0b10001,0b01111),
        'M' to intArrayOf(0b10001,0b11011,0b10101,0b10101,0b10001,0b10001,0b10001),
        'O' to intArrayOf(0b01110,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110),
        'R' to intArrayOf(0b11110,0b10001,0b10001,0b11110,0b10100,0b10010,0b10001),
        'T' to intArrayOf(0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b00100),
        ' ' to intArrayOf(0,0,0,0,0,0,0)
    )

    fun apply(frame: ImageUtils.Nv21Frame, enabled: Boolean): ImageUtils.Nv21Frame {
        if (!enabled || frame.width < 320 || frame.height < 180) return frame

        val scale = (frame.width / 640).coerceIn(2, 6)
        val glyphWidth = 5 * scale
        val gap = scale
        val textWidth = TEXT.length * (glyphWidth + gap) - gap
        val textHeight = 7 * scale
        val pad = 3 * scale
        val margin = 8 * scale

        val boxWidth = textWidth + pad * 2
        val boxHeight = textHeight + pad * 2
        val left = (frame.width - boxWidth - margin).coerceAtLeast(0)
        val top = (frame.height - boxHeight - margin).coerceAtLeast(0)

        // Dark translucent-looking plate using only Y. Chroma is kept untouched.
        for (y in top until (top + boxHeight).coerceAtMost(frame.height)) {
            val row = y * frame.width
            for (x in left until (left + boxWidth).coerceAtMost(frame.width)) {
                val i = row + x
                val current = frame.bytes[i].toInt() and 0xff
                frame.bytes[i] = minOf(current, 70).toByte()
            }
        }

        var cursorX = left + pad
        val originY = top + pad
        for (ch in TEXT) {
            val rows = glyphs[ch] ?: glyphs[' ']!!
            drawGlyph(frame, rows, cursorX + scale, originY + scale, scale, 20) // shadow
            drawGlyph(frame, rows, cursorX, originY, scale, 235)
            cursorX += glyphWidth + gap
        }
        return frame
    }

    private fun drawGlyph(
        frame: ImageUtils.Nv21Frame,
        rows: IntArray,
        x0: Int,
        y0: Int,
        scale: Int,
        yValue: Int
    ) {
        for (gy in rows.indices) {
            val bits = rows[gy]
            for (gx in 0 until 5) {
                if ((bits and (1 shl (4 - gx))) == 0) continue
                val px0 = x0 + gx * scale
                val py0 = y0 + gy * scale
                for (sy in 0 until scale) {
                    val py = py0 + sy
                    if (py !in 0 until frame.height) continue
                    val row = py * frame.width
                    for (sx in 0 until scale) {
                        val px = px0 + sx
                        if (px !in 0 until frame.width) continue
                        frame.bytes[row + px] = yValue.toByte()
                    }
                }
            }
        }
    }
}
