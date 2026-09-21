package maestro.cli.graphics

import maestro.cli.runner.resultview.AnsiResultView
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Rational
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64

/**
 * Manual timing harness for `record --local` compositing.
 *
 * Run with:
 *   BENCH_RECORD_RENDER=1 ./gradlew :<cli-module>:test --tests '*LocalVideoRendererBench*'
 *
 * Optional: BENCH_WIDTH / BENCH_HEIGHT / BENCH_FPS override the output (defaults 1920x1080 @ 25).
 */
class LocalVideoRendererBench {

    @Test
    @Tag("bench")
    @EnabledIfEnvironmentVariable(named = "BENCH_RECORD_RENDER", matches = "1")
    fun benchLocalRender() {
        val durationSeconds = System.getenv("BENCH_DURATION_SECONDS")?.toInt() ?: 8
        val outputFps = System.getenv("BENCH_FPS")?.toInt() ?: 25
        val outputWidth = System.getenv("BENCH_WIDTH")?.toInt() ?: 1920
        val outputHeight = System.getenv("BENCH_HEIGHT")?.toInt() ?: 1080

        val source = File.createTempFile("record-render-src", ".mp4")
        val output = File.createTempFile("record-render-out", ".mp4")
        source.deleteOnExit()
        output.deleteOnExit()

        writeSourceVideo(source, durationSeconds = durationSeconds)

        val textFrames = listOf(
            AnsiResultView.Frame(
                timestamp = 0,
                content = Base64.getEncoder().encodeToString(
                    "Running on iPhone\n\n ║\n ║  > Flow: launchApp\n ║\n ║     ⏳  Launch app\n".toByteArray()
                ),
            ),
            AnsiResultView.Frame(
                timestamp = 2000,
                content = Base64.getEncoder().encodeToString(
                    "Running on iPhone\n\n ║\n ║  > Flow: launchApp\n ║\n ║     ✅  Launch app\n ║     ⏳  Assert visible\n".toByteArray()
                ),
            ),
        )

        val startedAt = System.nanoTime()
        LocalVideoRenderer(
            frameRenderer = SkiaFrameRenderer(),
            outputFile = output,
            outputFPS = outputFps,
            outputWidthPx = outputWidth,
            outputHeightPx = outputHeight,
        ).render(source, textFrames)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        println("BENCH_RECORD_RENDER ms=$elapsedMs output=${output.absolutePath} bytes=${output.length()} ${outputWidth}x${outputHeight}@${outputFps} sourceSeconds=$durationSeconds")
        check(output.length() > 0L) { "renderer wrote an empty file" }
    }

    private fun writeSourceVideo(file: File, durationSeconds: Int) {
        val width = 400
        val height = 800
        val fps = 2
        NIOUtils.writableFileChannel(file.absolutePath).use { out ->
            AWTSequenceEncoder(out, Rational.R(fps, 1)).use { encoder ->
                val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
                val graphics = image.graphics
                repeat(durationSeconds * fps) { frameIndex ->
                    graphics.color = Color((frameIndex * 37) % 255, 90, 180)
                    graphics.fillRect(0, 0, width, height)
                    graphics.color = Color.WHITE
                    graphics.drawString("frame $frameIndex", 24, 48)
                    encoder.encodeImage(image)
                }
                graphics.dispose()
            }
        }
    }
}
