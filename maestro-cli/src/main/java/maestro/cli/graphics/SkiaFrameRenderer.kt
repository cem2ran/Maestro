package maestro.cli.graphics

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.toImage
import java.io.IOException
import javax.imageio.ImageIO

class SkiaFrameRenderer : FrameRenderer {

    private val backgroundImage = ImageIO.read(SkiaFrameRenderer::class.java.getResource("/record-background.jpg")!!).toImage()

    private val shadowColor = Color.makeARGB(100, 0, 0, 0)

    private val scenePadding = 40f
    private val sceneGap = 40f

    private val headerBgColor = Color.makeARGB(50, 255, 255, 255)
    private val headerHeight = 60f
    private val headerFont = Font(SkiaFonts.SANS_SERIF_TYPEFACE, 22f)
    private val headerTextColor = Color.makeARGB(200, 0, 0, 0)
    private val headerText = "Record your own using ${'$'} maestro record YourFlow.yaml"

    private val headerButtonColor = Color.makeARGB(50, 255, 255, 255)
    private val headerButtonSize = 20f
    private val headerButtonGap = 10f
    private val headerButtonMx = 20f

    private val footerBgColor = Color.makeARGB(50, 255, 255, 255)
    private val footerHeight = 60f
    private val footerFont = Font(SkiaFonts.SANS_SERIF_TYPEFACE, 22f)
    private val footerTextColor = Color.makeARGB(200, 0, 0, 0)
    private val footerText = "maestro.mobile.dev"

    private val terminalBgColor = Color.makeARGB(220, 0, 0, 0)
    private val terminalContentPadding = 40f

    private val textClipper = SkiaTextClipper()

    private var surface: Surface? = null
    private var bitmap: Bitmap? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    override fun renderBgra(
        outputWidthPx: Int,
        outputHeightPx: Int,
        screen: Image,
        text: String,
        destBgra: ByteArray,
    ) {
        val surface = obtainSurface(outputWidthPx, outputHeightPx)
        drawScene(surface.canvas, outputWidthPx.toFloat(), outputHeightPx.toFloat(), screen, text)
        val bitmap = obtainBitmap(outputWidthPx, outputHeightPx)
        if (!surface.readPixels(bitmap, 0, 0)) {
            throw IOException("Failed to read composited Skia frame")
        }
        val pixels = bitmap.readPixels(bitmap.imageInfo, outputWidthPx * 4, 0, 0)
            ?: throw IOException("Failed to copy composited Skia pixels")
        if (pixels.size != destBgra.size) {
            throw IOException("Unexpected frame size ${pixels.size}, expected ${destBgra.size}")
        }
        pixels.copyInto(destBgra)
    }

    private fun obtainSurface(width: Int, height: Int): Surface {
        val existing = surface
        if (existing != null && surfaceWidth == width && surfaceHeight == height) {
            return existing
        }
        existing?.close()
        bitmap?.close()
        bitmap = null
        val created = Surface.makeRasterN32Premul(width, height)
        surface = created
        surfaceWidth = width
        surfaceHeight = height
        return created
    }

    private fun obtainBitmap(width: Int, height: Int): Bitmap {
        val existing = bitmap
        if (existing != null) return existing
        val created = Bitmap()
        created.allocPixels(ImageInfo.makeN32Premul(width, height))
        bitmap = created
        return created
    }

    private fun drawScene(canvas: Canvas, outputWidthPx: Float, outputHeightPx: Float, screen: Image, text: String) {
        val fullScreenRect = Rect(0f, 0f, outputWidthPx, outputHeightPx)
        canvas.drawImageRect(backgroundImage, fullScreenRect)

        val paddedScreenRect = fullScreenRect.inflate(-scenePadding)

        drawContent(canvas, paddedScreenRect, screen, text)
    }

    private fun drawContent(canvas: Canvas, containerRect: Rect, screen: Image, text: String) {
        val imageRect = drawDevice(canvas, containerRect, screen)
        drawTerminal(canvas, containerRect, imageRect, text)
    }

    private fun drawDevice(canvas: Canvas, containerRect: Rect, screen: Image): Rect {
        val cornerRadius = 20f
        val deviceImageScale = containerRect.height / screen.height.toFloat()
        var deviceImageRect = Rect(0f, 0f, screen.width.toFloat(), screen.height.toFloat()).scale(deviceImageScale)
        deviceImageRect = deviceImageRect.offset(containerRect.right - deviceImageRect.right, containerRect.top)
        val deviceImageRectRounded = deviceImageRect.toRRect(cornerRadius)
        canvas.save()
        canvas.clipRRect(deviceImageRectRounded, true)
        canvas.drawImageRect(screen, deviceImageRect)
        canvas.restore()
        canvas.drawRectShadow(deviceImageRectRounded, 0f, 0f, 20f, 0.5f, shadowColor)
        return deviceImageRect
    }

    private fun drawTerminal(canvas: Canvas, containerRect: Rect, imageRect: Rect, text: String) {
        val terminalRect = Rect(containerRect.left, containerRect.top, imageRect.left - sceneGap, containerRect.bottom)
        val terminalRectRounded = terminalRect.toRRect(20f)
        canvas.drawRectShadow(terminalRectRounded, 0f, 0f, 20f, 0.5f, shadowColor)

        val headerRect = drawHeader(canvas, terminalRect)
        val footerRect = drawFooter(canvas, terminalRect)
        drawTerminalContent(canvas, terminalRect, headerRect, footerRect, text)
    }

    private fun drawFooter(canvas: Canvas, terminalRect: Rect): Rect {
        val footerRect = Rect.makeXYWH(terminalRect.left, terminalRect.bottom - footerHeight, terminalRect.width, footerHeight)
        val headerRectRounded = footerRect.toRRect(0f, 0f, 20f, 20f)
        canvas.drawRRect(headerRectRounded, Paint().apply { color = footerBgColor })

        drawFooterText(canvas, footerRect)

        return footerRect
    }

    private fun drawFooterText(canvas: Canvas, footerRect: Rect) {
        val paint = Paint().apply {
            color = footerTextColor
        }
        val textRect = footerFont.measureText(footerText, paint)
        val x = footerRect.left + (footerRect.width - textRect.width) / 2
        val y = footerRect.top + footerRect.height / 2 + textRect.height / 3
        canvas.drawString(footerText, x, y, footerFont, paint)
    }

    private fun drawHeader(canvas: Canvas, terminalRect: Rect): Rect {
        val headerRect = Rect.makeXYWH(terminalRect.left, terminalRect.top, terminalRect.width, headerHeight)
        val headerRectRounded = headerRect.toRRect(20f, 20f, 0f, 0f)
        canvas.drawRRect(headerRectRounded, Paint().apply { color = headerBgColor })

        drawHeaderButtons(canvas, headerRect)
        drawHeaderText(canvas, headerRect)

        return headerRect
    }

    private fun drawHeaderButtons(canvas: Canvas, headerRect: Rect) {
        var centerX = headerRect.left + headerButtonMx + headerButtonSize / 2
        val centerY = headerRect.top + headerRect.height / 2

        repeat(3) {
            canvas.drawCircle(centerX, centerY, headerButtonSize / 2, Paint().apply { color = headerButtonColor })
            centerX += headerButtonSize + headerButtonGap
        }
    }

    private fun drawHeaderText(canvas: Canvas, headerRect: Rect) {
        val paint = Paint().apply {
            color = headerTextColor
        }
        val textRect = headerFont.measureText(headerText, paint)
        val x = headerRect.left + (headerRect.width - textRect.width) / 2
        val y = headerRect.top + headerRect.height / 2 + textRect.height / 3
        canvas.drawString(headerText, x, y, headerFont, paint)
    }

    private fun drawTerminalContent(canvas: Canvas, terminalRect: Rect, headerRect: Rect, footerRect: Rect, string: String) {
        val contentRect = Rect.makeLTRB(terminalRect.left, headerRect.bottom, terminalRect.right, footerRect.top)
        canvas.drawRect(contentRect, Paint().apply { color = terminalBgColor })

        val paddedContentRect = Rect.makeLTRB(
            l = contentRect.left + terminalContentPadding,
            t = contentRect.top + terminalContentPadding,
            r = contentRect.right - terminalContentPadding,
            b = contentRect.bottom - terminalContentPadding / 4f,
        )

        val focusedLineIndex = getFocusedLineIndex(string)
        val focusedLinePadding = 5
        textClipper.renderClippedText(canvas, paddedContentRect, string, focusedLineIndex + focusedLinePadding)
    }

    private fun getFocusedLineIndex(text: String): Int {
        val lines = text.lines()
        val indexOfFirstPendingLine = lines.indexOfFirst { it.contains("\uD83D\uDD32") }
        if (indexOfFirstPendingLine != -1) return indexOfFirstPendingLine
        val indexOfLastCheck = lines.indexOfLast { it.contains("✅") }
        if (indexOfLastCheck != -1) return indexOfLastCheck
        return 0
    }
}
