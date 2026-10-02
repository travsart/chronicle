package local.oss.chronicle.application

import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import android.util.Log

internal class WarningErrorFileTree(
    private val logFile: File,
    private val isEnabled: () -> Boolean,
) : Timber.Tree() {
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chronicle-log-writer").apply { isDaemon = true }
    }

    override fun isLoggable(
        tag: String?,
        priority: Int,
    ): Boolean = priority >= Log.WARN && isEnabled()

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        if (priority < Log.WARN || !isEnabled()) return

        val entry = buildString {
            append(SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).format(Date()))
            append(" ")
            append(if (priority == Log.ERROR) "ERROR" else "WARN")
            append("/")
            append(tag ?: "Chronicle")
            append(": ")
            append(message)
            if (t != null) {
                val stackTrace = StringWriter().also { t.printStackTrace(PrintWriter(it)) }
                append("\n")
                append(stackTrace)
            }
            append("\n")
        }

        writer.execute {
            try {
                appendEntry(entry)
            } catch (_: Exception) {
                // Logging failures must not trigger another log event.
            }
        }
    }

    private fun appendEntry(entry: String) {
        logFile.parentFile?.mkdirs()
        val bytes = entry.toByteArray(Charsets.UTF_8)
        if (logFile.exists() && logFile.length() + bytes.size > MAX_FILE_SIZE_BYTES) {
            val previousLogFile = File(logFile.parentFile, "$LOG_FILE_NAME.1")
            previousLogFile.delete()
            logFile.renameTo(previousLogFile)
        }
        FileOutputStream(logFile, true).use { it.write(bytes) }
    }

    companion object {
        const val LOG_FILE_NAME = "warning-error-logs.log"
        private const val TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
        private const val MAX_FILE_SIZE_BYTES = 1024 * 1024L
    }
}