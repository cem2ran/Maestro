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
import org.jetbrains.skiko.toImage
import org.jcodec.api.PictureWithMetadata
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Rational
import org.jcodec.scale.AWTUtil
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

internal enum class LocalEncodePath {
    AUTO,
    FFMPEG,
    JCODEC,
}

class LocalVideoRenderer internal constructor(
    private val frameRenderer: FrameRenderer,
    private val outputFile: File,
    private val outputFPS: Int,
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
    private val encodePath: LocalEncodePath,
) : VideoRenderer {

    constructor(
        frameRenderer: FrameRenderer,
        outputFile: File,
        outputFPS: Int,
        outputWidthPx: Int,
        outputHeightPx: Int,
    ) : this(
        frameRenderer,
        outputFile,
        outputFPS,
        outputWidthPx,
        outputHeightPx,
        LocalEncodePath.AUTO,
    )

    override fun render(
        screenRecording: File,
        textFrames: List<AnsiResultView.Frame>,
    ) {
        val textKeyframes = decodeTextFrames(textFrames)
        val ffmpeg = when (encodePath) {
            LocalEncodePath.AUTO -> Ffmpeg.findBinary()
            LocalEncodePath.FFMPEG -> Ffmpeg.requireBinary()
            LocalEncodePath.JCODEC -> null
        }
        if (ffmpeg != null) {
            renderWithFfmpeg(ffmpeg, screenRecording, textKeyframes)
        } else {
            renderWithJcodec(screenRecording, textKeyframes)
        }
    }

    private fun renderWithFfmpeg(
        ffmpeg: String,
        screenRecording: File,
        textKeyframes: List<TextKeyframe>,
    ) {
        val probe = Ffmpeg.probe(ffmpeg, screenRecording)
        val codecArgs = Ffmpeg.encoderCodecArgs(ffmpeg)
        val outputFrameCount = max(1, (probe.durationSeconds * outputFPS).roundToInt())
        val screenFrameSize = probe.width * probe.height * 4
        val outputFrameSize = outputWidthPx * outputHeightPx * 4

        renderTimed(codecArgs.getOrElse(1) { "unknown" }) { progress ->
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

                            fun writeComposite(screenBgra: ByteArray, text: String) {
                                val screenUnchanged = havePrev && screenBgra.contentEquals(prevScreen) && text == prevText
                                if (screenUnchanged) {
                                    rawOut.write(prevOutput)
                                } else {
                                    Image.makeRaster(screenInfo, screenBgra, probe.width * 4).use { screen ->
                                        frameRenderer.renderBgra(
                                            outputWidthPx,
                                            outputHeightPx,
                                            screen,
                                            text,
                                            outputBuf,
                                        )
                                    }
                                    rawOut.write(outputBuf)
                                    screenBgra.copyInto(prevScreen)
                                    outputBuf.copyInto(prevOutput)
                                    prevText = text
                                    havePrev = true
                                }
                            }

                            while (rawIn.readExact(decodeBuf)) {
                                writeComposite(decodeBuf, textAt(textKeyframes, frameIndex.toDouble() / outputFPS))
                                progress.set(((frameIndex + 1).toFloat() / outputFrameCount).coerceIn(0f, 1f))
                                frameIndex++
                            }
                            if (frameIndex == 0) {
                                throw CliError("ffmpeg produced no video frames from ${screenRecording.absolutePath}")
                            }
                            // iOS screen recordings are VFR: last PTS can land
                            // well before container duration. JCodec holds the
                            // last picture through totalDuration; pad so both
                            // encoders emit the same length.
                            while (frameIndex < outputFrameCount) {
                                writeComposite(prevScreen, textAt(textKeyframes, frameIndex.toDouble() / outputFPS))
                                progress.set(((frameIndex + 1).toFloat() / outputFrameCount).coerceIn(0f, 1f))
                                frameIndex++
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
        }
    }

    private fun renderWithJcodec(
        screenRecording: File,
        textKeyframes: List<TextKeyframe>,
    ) {
        val outputFrameSize = outputWidthPx * outputHeightPx * 4
        val destBgra = ByteArray(outputFrameSize)
        val compositeBgra = ByteArray(outputFrameSize)
        val compositeImage = wrapBgraBufferedImage(outputWidthPx, outputHeightPx, compositeBgra)

        renderTimed("jcodec") { progress ->
            NIOUtils.writableFileChannel(outputFile.absolutePath).use { out ->
                AWTSequenceEncoder(out, Rational.R(outputFPS, 1)).use { encoder ->
                    useFrameGrab(screenRecording) { grab ->
                        val outputDurationSeconds = grab.videoTrack.meta.totalDuration
                        val outputFrameCount = (outputDurationSeconds * outputFPS).toInt()
                        var curFrame: PictureWithMetadata = grab.nativeFrameWithMetadata
                            ?: throw CliError("JCodec produced no video frames from ${screenRecording.absolutePath}")
                        var nextFrame: PictureWithMetadata? = grab.nativeFrameWithMetadata
                        var prevPlanes: Array<ByteArray>? = null
                        var prevText: String? = null
                        var screenImage: Image? = null
                        var haveComposite = false

                        try {
                            (0..outputFrameCount).forEach { frameIndex ->
                                val currentTimestampSeconds = frameIndex.toDouble() / outputFPS

                                // !! Due to smart cast limitation: https://youtrack.jetbrains.com/issue/KT-7186
                                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                                while (nextFrame != null && nextFrame!!.timestamp <= currentTimestampSeconds) {
                                    curFrame = nextFrame!!
                                    nextFrame = grab.nativeFrameWithMetadata
                                }

                                val picture = curFrame.picture
                                val text = textAt(textKeyframes, currentTimestampSeconds)
                                val pictureUnchanged = prevPlanes != null && picture.data.contentDeepEquals(prevPlanes)
                                if (pictureUnchanged && text == prevText && haveComposite) {
                                    encoder.encodeImage(compositeImage)
                                } else {
                                    if (!pictureUnchanged || screenImage == null) {
                                        val nextScreen = AWTUtil.toBufferedImage(picture).toImage()
                                        screenImage?.close()
                                        screenImage = nextScreen
                                        prevPlanes = copyPlanes(picture.data)
                                    }
                                    val screen = checkNotNull(screenImage) { "missing cached screen image" }
                                    frameRenderer.renderBgra(
                                        outputWidthPx,
                                        outputHeightPx,
                                        screen,
                                        text,
                                        destBgra,
                                    )
                                    destBgra.copyInto(compositeBgra)
                                    encoder.encodeImage(compositeImage)
                                    prevText = text
                                    haveComposite = true
                                }

                                if (outputFrameCount > 0) {
                                    progress.set((frameIndex / outputFrameCount.toFloat()).coerceIn(0f, 1f))
                                }
                            }
                        } finally {
                            screenImage?.close()
                        }
                    }
                }
            }
        }
    }

    private fun renderTimed(encoderLabel: String, block: (ProgressBar) -> Unit) {
        System.err.println()
        System.err.println("@|bold Rendering video - This may take some time...|@".render())
        System.err.println()
        System.err.println(outputFile.absolutePath)
        System.err.println("encoder: $encoderLabel  ${outputWidthPx}x${outputHeightPx}@$outputFPS")

        val startedAt = System.nanoTime()
        block(ProgressBar(50))
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

private fun copyPlanes(data: Array<ByteArray>): Array<ByteArray> {
    return Array(data.size) { index -> data[index].copyOf() }
}
