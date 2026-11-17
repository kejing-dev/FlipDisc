package io.embodit.flipdisc.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.random.Random

/**
 * Canvas textures (Xiangqi BoardTextures pattern). The felt face: green baize with a soft nap and the 8 x 8 grid in a
 * slightly darker green (palette.board3d gridLine). The bitmap's top edge is the board's far edge (rank 1); the plane is
 * laid flat with pitch -90°, which maps bitmap-up to -Z. The pattern is symmetric (no text), so its orientation cannot
 * read mirrored (D10).
 */
object BoardTextures {
    fun felt(size: Int = 2048): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0x3A, 0x8A, 0x5C))
        // felt nap: many tiny lighter / darker flecks, deterministic
        val rnd = Random(8)
        val fleck = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(26_000) {
            val x = rnd.nextFloat() * size; val y = rnd.nextFloat() * size
            val light = rnd.nextBoolean()
            fleck.color = if (light) Color.argb(14 + rnd.nextInt(14), 255, 255, 255) else Color.argb(16 + rnd.nextInt(16), 0x12, 0x3A, 0x22)
            c.drawCircle(x, y, 0.8f + rnd.nextFloat() * 1.6f, fleck)
        }
        // the grid: 9 lines each way, 3 mm wide on a 64 cm felt
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2A, 0x66, 0x45); strokeWidth = size * (0.003f / 0.64f) }
        val cell = size / 8f
        for (i in 0..8) {
            val p = (i * cell).coerceIn(line.strokeWidth / 2, size - line.strokeWidth / 2)
            c.drawLine(p, 0f, p, size.toFloat(), line)
            c.drawLine(0f, p, size.toFloat(), p, line)
        }
        return bmp
    }

    /** Walnut: warm brown with long straight grain (frame and troughs). */
    fun walnut(base: Int, size: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(base)
        val rnd = Random(5)
        val grain = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(140) {
            val y = rnd.nextFloat() * size
            grain.color = if (rnd.nextFloat() < 0.6f) Color.argb(20 + rnd.nextInt(30), 0x24, 0x14, 0x08) else Color.argb(14 + rnd.nextInt(16), 0xC8, 0x9A, 0x6A)
            grain.strokeWidth = 0.6f + rnd.nextFloat() * 3f
            val wob = rnd.nextFloat() * 10f - 5f
            c.drawLine(0f, y, size.toFloat(), y + wob, grain)
        }
        return bmp
    }
}
