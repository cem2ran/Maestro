package maestro.cli.graphics

import maestro.cli.CliError
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.view.ProgressBar
import maestro.cli.view.render
import okio.ByteString.Companion.decodeBase64
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

interface FrameRenderer {
    fun renderBgra(
        outputWidthPx: Int,
        outputHeightPx: Int,
        screen: Image,
        text: String,
        destBgra: ByteArray,
    )
}

class LocalVideoRenderer(
    private val frameRenderer: FrameRenderer,
    private val outputFile: File,
    private val outputFPS: Int,
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
) : VideoRenderer {

    override fun render(
        screenRecording: File,
        textFrames: List<AnsiResultView.Frame>,
    ) {
        val ffmpeg = Ffmpeg.requireBinary()
        val probe = Ffmpeg.probe(ffmpeg, screenRecording)
        val codecArgs = Ffmpeg.encoderCodecArgs(ffmpeg)
        val outputFrameCount = max(1, (probe.durationSeconds * outputFPS).roundToInt())
        val screenFrameSize = probe.width * probe.height * 4
        val outputFrameSize = outputWidthPx * outputHeightPx * 4
        val textKeyframes = decodeTextFrames(textFrames)

        System.err.println()
        System.err.println("@|bold Rendering video - This may take some time...|@".render())
        System.err.println()
        System.err.println(outputFile.absolutePath)
        System.err.println("encoder: ${codecArgs.getOrElse(1) { "unknown" }}  ${outputWidthPx}x${outputHeightPx}@$outputFPS")

        val startedAt = System.nanoTime()
        val progress = ProgressBar(50)

        val decoder = startFfmpeg(
            listOf(
                ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
                "-noautorotate",
                "-i", screenRecording.absolutePath,
                "-an",
                "-vf", "fps=$outputFPS",
                "-f", "rawvideo",
                "-pix_fmt", "bgra",
                "pipe:1",
            )
        )
        val encoder = try {
            startFfmpeg(
                listOf(
                    ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "rawvideo",
                    "-pix_fmt", "bgra",
                    "-s:v", "${outputWidthPx}x${outputHeightPx}",
                    "-r", outputFPS.toString(),
                    "-i", "pipe:0",
                    "-an",
                ) + codecArgs + listOf(
                    "-movflags", "+faststart",
                    outputFile.absolutePath,
                ),
                discardStdout = true,
            )
        } catch (error: Exception) {
            decoder.destroyQuietly()
            throw error
        }

        try {
            try {
                decoder.inputStream.buffered(1 shl 20).use { rawIn ->
                    encoder.outputStream.buffered(1 shl 20).use { rawOut ->
                        val decodeBuf = ByteArray(screenFrameSize)
                        val prevScreen = ByteArray(screenFrameSize)
                        val outputBuf = ByteArray(outputFrameSize)
                        val prevOutput = ByteArray(outputFrameSize)
                        var havePrev = false
                        var prevText: String? = null
                        var frameIndex = 0
                        val screenInfo = ImageInfo(
                            probe.width,
                            probe.height,
                            ColorType.BGRA_8888,
                            ColorAlphaType.UNPREMUL,
                        )

                        while (rawIn.readExact(decodeBuf)) {
                            val timestampSeconds = frameIndex.toDouble() / outputFPS
                            val text = textAt(textKeyframes, timestampSeconds)
                            val screenUnchanged = havePrev && decodeBuf.contentEquals(prevScreen) && text == prevText
                            if (screenUnchanged) {
                                rawOut.write(prevOutput)
                            } else {
                                Image.makeRaster(screenInfo, decodeBuf, probe.width * 4).use { screen ->
                                    frameRenderer.renderBgra(
                                        outputWidthPx,
                                        outputHeightPx,
                                        screen,
                                        text,
                                        outputBuf,
                                    )
                                }
                                rawOut.write(outputBuf)
                                decodeBuf.copyInto(prevScreen)
                                outputBuf.copyInto(prevOutput)
                                prevText = text
                                havePrev = true
                            }
                            progress.set(((frameIndex + 1).toFloat() / outputFrameCount).coerceIn(0f, 1f))
                            frameIndex++
                        }
                        if (frameIndex == 0) {
                            throw CliError("ffmpeg produced no video frames from ${screenRecording.absolutePath}")
                        }
                    }
                }
            } catch (error: IOException) {
                throw encoder.failureAfterPipeError("ffmpeg pipe", error)
            }
            encoder.waitForSuccess("ffmpeg encode")
            decoder.waitForSuccess("ffmpeg decode")
        } finally {
            encoder.destroyQuietly()
            decoder.destroyQuietly()
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        System.err.println()
        System.err.println()
        System.err.println(
            "Rendering complete in ${elapsedMs}ms! If you're sharing on Twitter be sure to tag us \uD83D\uDE04 @|bold @mobile__dev|@".render()
        )
    }

    private fun decodeTextFrames(textFrames: List<AnsiResultView.Frame>): List<TextKeyframe> {
        return textFrames.map { frame ->
            TextKeyframe(
                timestampSeconds = frame.timestamp / 1000.0,
                text = frame.content.decodeBase64()?.string(Charsets.UTF_8)?.stripAnsiCodes().orEmpty(),
            )
        }
    }

    private fun textAt(frames: List<TextKeyframe>, timestampSeconds: Double): String {
        if (frames.isEmpty()) return ""
        return frames.lastOrNull { it.timestampSeconds <= timestampSeconds }?.text ?: frames.first().text
    }

    private fun String.stripAnsiCodes(): String {
        return replace("\\u001B\\[[;\\d]*[mH]".toRegex(), "")
    }

    private data class TextKeyframe(
        val timestampSeconds: Double,
        val text: String,
    )
}
