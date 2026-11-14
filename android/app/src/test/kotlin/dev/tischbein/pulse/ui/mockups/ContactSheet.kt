package dev.tischbein.pulse.ui.mockups

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Post-processing of the mockup PNGs with plain Java2D (no extra tools needed). */
object ContactSheet {
    /** Full-page shots are rendered on a tall canvas: cut the empty background below the content. */
    fun trimTallShots(
        dir: File,
        names: List<String>,
    ) {
        for (name in names) {
            val f = File(dir, "$name.png")
            if (!f.exists()) continue
            val img = ImageIO.read(f)
            val bg = img.getRGB(2, img.height - 2)
            // Content ends at the last row that is not plain background.
            var last = img.height - 1
            outer@ while (last > 0) {
                for (x in 0 until img.width step 3) if (img.getRGB(x, last) != bg) break@outer
                last--
            }
            // Keep a bottom margin like the side margins (20 dp at 2.625 px/dp).
            val h = (last + 53).coerceAtMost(img.height)
            ImageIO.write(img.getSubimage(0, 0, img.width, h), "png", f)
        }
    }

    /** Puts [names] side by side in rows of 6 at quarter size, captioned. */
    fun build(
        dir: File,
        names: List<String>,
        out: File,
        perRow: Int = 6,
        scale: Double = 0.25,
    ) {
        val images = names.map { it to ImageIO.read(File(dir, "$it.png")) }
        val w = (images.first().second.width * scale).toInt()
        val h = (images.first().second.height * scale).toInt()
        val gap = 24
        val caption = 36
        val rows = (images.size + perRow - 1) / perRow
        val sheet = BufferedImage(perRow * (w + gap) + gap, rows * (h + caption + gap) + gap, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x2A, 0x2D, 0x34)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        images.forEachIndexed { i, (name, img) ->
            val x = gap + (i % perRow) * (w + gap)
            val y = gap + (i / perRow) * (h + caption + gap)
            g.drawImage(img, x, y, w, h, null)
            g.color = Color(0xC8, 0xCD, 0xD5)
            g.drawString(name, x, y + h + 24)
        }
        g.dispose()
        ImageIO.write(sheet, "png", out)
    }
}
