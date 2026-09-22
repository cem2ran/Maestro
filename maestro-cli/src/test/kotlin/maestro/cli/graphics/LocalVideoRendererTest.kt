package maestro.cli.graphics

import com.google.common.truth.Truth.assertThat
import maestro.cli.runner.resultview.AnsiResultView
import org.jetbrains.skia.Image
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Rational
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.util.Base64

class LocalVideoRendererTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @EnabledIf("ffmpegAvailable")
    fun `local renderer writes a non-empty mp4`() {
        val ffmpeg = Ffmpeg.findBinary() ?: return
        val source = tempDir.resolve("source.mp4").toFile()
        val output = tempDir.resolve("out.mp4").toFile()
        val generate = ProcessBuilder(
            ffmpeg, "-y", "-f", "lavfi",
            "-i", "testsrc=size=160x320:rate=5",
            "-t", "1",
            "-pix_fmt", "yuv420p",
            source.absolutePath,
        ).inheritIO().start()
        assertThat(generate.waitFor()).isEqualTo(0)

        LocalVideoRenderer(
            frameRenderer = SkiaFrameRenderer(),
            outputFile = output,
            outputFPS = 5,
            outputWidthPx = 640,
            outputHeightPx = 360,
        ).render(
            source,
            listOf(
                AnsiResultView.Frame(
                    timestamp = 0,
                    content = Base64.getEncoder().encodeToString(" ║  > Flow: sample\n ║     ✅  Launch app\n".toByteArray()),
                )
            )
        )

        val probed = Ffmpeg.probe(ffmpeg, output)
        assertThat(probed.width).isEqualTo(640)
        assertThat(probed.height).isEqualTo(360)
        assertThat(probed.durationSeconds).isWithin(0.25).of(1.0)
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    fun `unchanged frames are reused until the transcript changes`() {
        val ffmpeg = Ffmpeg.findBinary() ?: return
        val source = tempDir.resolve("static.mp4").toFile()
        val output = tempDir.resolve("reused.mp4").toFile()
        val generate = ProcessBuilder(
            ffmpeg, "-y", "-f", "lavfi",
            "-i", "color=c=blue:s=80x160:r=5",
            "-t", "1",
            "-pix_fmt", "yuv420p",
            source.absolutePath,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        assertThat(generate.waitFor()).isEqualTo(0)

        val counting = CountingFrameRenderer()
        LocalVideoRenderer(
            frameRenderer = counting,
            outputFile = output,
            outputFPS = 5,
            outputWidthPx = 320,
            outputHeightPx = 180,
        ).render(
            source,
            twoTranscripts(),
        )

        assertThat(counting.draws).isEqualTo(2)
        val probed = Ffmpeg.probe(ffmpeg, output)
        assertThat(probed.width).isEqualTo(320)
        assertThat(probed.height).isEqualTo(180)
    }

    @Test
    fun `jcodec reuses unchanged frames until the transcript changes`() {
        val source = tempDir.resolve("jcodec-static.mp4").toFile()
        val output = tempDir.resolve("jcodec-reused.mp4").toFile()
        writeSolidSource(source, frameCount = 3, fps = 2)

        val counting = CountingFrameRenderer()
        LocalVideoRenderer(
            frameRenderer = counting,
            outputFile = output,
            outputFPS = 5,
            outputWidthPx = 320,
            outputHeightPx = 180,
            encodePath = LocalEncodePath.JCODEC,
        ).render(
            source,
            twoTranscripts(),
        )

        assertThat(counting.draws).isEqualTo(2)
        assertThat(output.length()).isGreaterThan(0L)
    }

    @Test
    fun `jcodec composites a static transcript once`() {
        val source = tempDir.resolve("jcodec-once.mp4").toFile()
        val output = tempDir.resolve("jcodec-once-out.mp4").toFile()
        writeSolidSource(source, frameCount = 3, fps = 2)

        val counting = CountingFrameRenderer()
        LocalVideoRenderer(
            frameRenderer = counting,
            outputFile = output,
            outputFPS = 5,
            outputWidthPx = 320,
            outputHeightPx = 180,
            encodePath = LocalEncodePath.JCODEC,
        ).render(
            source,
            listOf(
                AnsiResultView.Frame(
                    timestamp = 0,
                    content = Base64.getEncoder().encodeToString("first\n".toByteArray()),
                )
            ),
        )

        assertThat(counting.draws).isEqualTo(1)
        assertThat(output.length()).isGreaterThan(0L)
    }

    private fun twoTranscripts(): List<AnsiResultView.Frame> {
        return listOf(
            AnsiResultView.Frame(
                timestamp = 0,
                content = Base64.getEncoder().encodeToString("first\n".toByteArray()),
            ),
            AnsiResultView.Frame(
                timestamp = 400,
                content = Base64.getEncoder().encodeToString("second\n".toByteArray()),
            ),
        )
    }

    private fun writeSolidSource(file: File, frameCount: Int, fps: Int) {
        val width = 80
        val height = 160
        NIOUtils.writableFileChannel(file.absolutePath).use { out ->
            AWTSequenceEncoder(out, Rational.R(fps, 1)).use { encoder ->
                val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
                val graphics = image.graphics
                graphics.color = Color.BLUE
                graphics.fillRect(0, 0, width, height)
                repeat(frameCount) {
                    encoder.encodeImage(image)
                }
                graphics.dispose()
            }
        }
    }

    private class CountingFrameRenderer : FrameRenderer {
        private val delegate = SkiaFrameRenderer()
        var draws = 0
            private set

        override fun renderBgra(
            outputWidthPx: Int,
            outputHeightPx: Int,
            screen: Image,
            text: String,
            destBgra: ByteArray,
        ) {
            draws += 1
            delegate.renderBgra(outputWidthPx, outputHeightPx, screen, text, destBgra)
        }
    }

    companion object {
        @JvmStatic
        fun ffmpegAvailable(): Boolean = Ffmpeg.findBinary() != null
    }
}
