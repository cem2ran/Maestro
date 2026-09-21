package maestro.cli.graphics

import maestro.cli.CliError
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class VideoProbe(
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
)

internal object Ffmpeg {

    fun requireBinary(): String {
        return findBinary()
            ?: throw CliError(
                "ffmpeg is required for `maestro record --local`. " +
                    "Install ffmpeg, add it to PATH, or set MAESTRO_FFMPEG to the binary."
            )
    }

    fun findBinary(): String? {
        val override = System.getenv("MAESTRO_FFMPEG")?.trim().orEmpty()
        if (override.isNotEmpty()) {
            val file = File(override)
            if (file.canExecute()) return file.absolutePath
            throw CliError("MAESTRO_FFMPEG is set but is not an executable file: $override")
        }
        return findOnPath(if (isWindows()) "ffmpeg.exe" else "ffmpeg")
    }

    fun probe(ffmpeg: String, file: File): VideoProbe {
        val ffprobe = siblingBinary(ffmpeg, "ffprobe")
        if (ffprobe != null) {
            val output = runAndCapture(
                listOf(
                    ffprobe,
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1",
                    file.absolutePath,
                )
            )
            val values = parseKeyValues(output)
            val width = values["width"]?.toIntOrNull()
            val height = values["height"]?.toIntOrNull()
            val duration = values["duration"]?.toDoubleOrNull()
            if (width != null && height != null && duration != null && width > 0 && height > 0 && duration > 0) {
                return VideoProbe(width, height, duration)
            }
        }

        val output = runAndCapture(
            listOf(ffmpeg, "-hide_banner", "-i", file.absolutePath),
            allowNonZeroExit = true,
        )
        return parseFfmpegIdentify(output)
            ?: throw CliError("Could not read video size/duration from ${file.absolutePath}")
    }

    fun encoderCodecArgs(ffmpeg: String): List<String> {
        return if (!isWindows() && hasEncoder(ffmpeg, "h264_videotoolbox")) {
            listOf("-c:v", "h264_videotoolbox", "-b:v", "8M", "-pix_fmt", "yuv420p")
        } else {
            listOf("-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-pix_fmt", "yuv420p")
        }
    }

    private fun hasEncoder(ffmpeg: String, name: String): Boolean {
        val output = runAndCapture(listOf(ffmpeg, "-hide_banner", "-encoders"), allowNonZeroExit = true)
        return output.lineSequence().any { line ->
            line.contains(name) && !line.trimStart().startsWith("ffmpeg")
        }
    }

    private fun siblingBinary(ffmpeg: String, name: String): String? {
        val file = File(File(ffmpeg).parentFile, if (isWindows()) "$name.exe" else name)
        return file.absolutePath.takeIf { file.canExecute() }
    }

    private fun findOnPath(executable: String): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparator)
            .asSequence()
            .map { File(it, executable) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    private fun parseKeyValues(output: String): Map<String, String> {
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx) to line.substring(idx + 1)
            }
    }

    private fun parseFfmpegIdentify(output: String): VideoProbe? {
        val durationMatch = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""").find(output)
        val sizeMatch = Regex("""(\d{2,5})x(\d{2,5})""").find(output)
        if (durationMatch == null || sizeMatch == null) return null
        val hours = durationMatch.groupValues[1].toInt()
        val minutes = durationMatch.groupValues[2].toInt()
        val seconds = durationMatch.groupValues[3].toDouble()
        val duration = hours * 3600 + minutes * 60 + seconds
        val width = sizeMatch.groupValues[1].toInt()
        val height = sizeMatch.groupValues[2].toInt()
        if (width <= 0 || height <= 0 || duration <= 0) return null
        return VideoProbe(width, height, duration)
    }

    private fun runAndCapture(command: List<String>, allowNonZeroExit: Boolean = false): String {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val reader = thread(isDaemon = true, name = "ffmpeg-capture") {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    stream.lineSequence().forEach { line ->
                        synchronized(output) {
                            output.appendLine(line)
                        }
                    }
                }
            } catch (_: IOException) {
                // The process was destroyed, or the pipe closed.
            }
        }
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1_000)
            throw CliError("Command timed out after 60 seconds: ${command.joinToString(" ")}\n${outputText(output)}")
        }
        reader.join(5_000)
        val code = process.exitValue()
        val text = outputText(output)
        if (code != 0 && !allowNonZeroExit) {
            throw CliError("Command failed (exit $code): ${command.joinToString(" ")}\n$text")
        }
        return text
    }

    private fun outputText(output: StringBuilder): String {
        return synchronized(output) { output.toString() }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}

internal class FfmpegProcess(
    private val process: Process,
    private val stderr: StringBuilder,
    private val stderrThread: Thread,
) {
    val inputStream: InputStream = process.inputStream
    val outputStream = process.outputStream

    fun waitForSuccess(context: String) {
        val finished = process.waitFor(15, TimeUnit.MINUTES)
        val log = drainStderr()
        if (!finished) {
            process.destroyForcibly()
            throw CliError("$context timed out after 15 minutes\n$log")
        }
        val code = process.exitValue()
        if (code != 0) {
            throw CliError("$context failed (exit $code)\n$log")
        }
    }

    fun failureAfterPipeError(context: String, error: IOException): CliError {
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        val log = drainStderr()
        val status = if (finished) "exit ${process.exitValue()}" else "did not exit"
        return CliError("$context failed ($status)\n$log\n${error.message}")
    }

    fun destroyQuietly() {
        process.destroyForcibly()
    }

    private fun drainStderr(): String {
        stderrThread.join(5_000)
        return synchronized(stderr) { stderr.toString() }
    }
}

internal fun startFfmpeg(
    command: List<String>,
    discardStdout: Boolean = false,
): FfmpegProcess {
    val builder = ProcessBuilder(command)
    if (discardStdout) {
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    }
    val process = builder.start()
    val stderr = StringBuilder()
    val stderrThread = thread(isDaemon = true, name = "ffmpeg-stderr") {
        process.errorStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                synchronized(stderr) {
                    stderr.appendLine(line)
                }
            }
        }
    }
    return FfmpegProcess(process, stderr, stderrThread)
}

internal fun InputStream.readExact(buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) {
            if (offset == 0) return false
            throw IOException("Truncated raw video frame ($offset/${buffer.size} bytes)")
        }
        offset += read
    }
    return true
}
