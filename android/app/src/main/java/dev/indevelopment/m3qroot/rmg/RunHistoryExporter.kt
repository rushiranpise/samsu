package dev.indevelopment.m3qroot.rmg

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports run history as a single zip archive.
 *
 * Ported from Root-My-Galaxy-Extended (`HistoryLogExporter.kt`) by igorcv88
 * (Apache License 2.0), adapted to SamSU's export route: the archive is written
 * through MediaStore into the public Downloads collection, matching the existing
 * single-log export, so no storage permission or SAF round trip is needed.
 */
object RunHistoryExporter {

    private const val MIME_ZIP = "application/zip"
    private const val INDEX_NAME = "index.txt"

    fun archiveFileName(prefix: String, completedCount: Int): String =
        prefix + "-logs-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
            "-$completedCount.zip"

    /**
     * Writes every completed entry to `Downloads/<name>` and returns that name.
     *
     * Running entries are rejected here as well as by the caller: a run that has
     * not produced its terminal line must never be archived as a finished one.
     */
    @Throws(IOException::class)
    fun export(
        context: Context,
        entries: Collection<RunHistoryEntry>,
        namePrefix: String,
        indexLines: List<String> = emptyList(),
        appendices: Map<String, String> = emptyMap(),
    ): String {
        val snapshot = entries
            .filter { it.result != RunResult.Running }
            .sortedByDescending(RunHistoryEntry::startedAtMillis)
        if (snapshot.isEmpty()) {
            throw IOException("No completed runs to export yet")
        }

        val name = archiveFileName(namePrefix, snapshot.size)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME_ZIP)
        }
        val target = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
        ) ?: throw IOException("MediaStore insert failed")

        try {
            val output = context.contentResolver.openOutputStream(target)
                ?: throw IOException("output stream unavailable")
            output.use { raw ->
                ZipOutputStream(raw).use { zip ->
                    if (indexLines.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry(INDEX_NAME))
                        zip.write(
                            (indexLines.joinToString("\n") + "\n")
                                .toByteArray(Charsets.UTF_8),
                        )
                        zip.closeEntry()
                    }
                    snapshot.forEach { entry ->
                        zip.putNextEntry(ZipEntry(entryFileName(entry, namePrefix)))
                        zip.write(
                            entry.log.ifBlank { "(no output captured)\n" }
                                .toByteArray(Charsets.UTF_8),
                        )
                        zip.closeEntry()
                    }
                    appendices.forEach { (fileName, text) ->
                        zip.putNextEntry(ZipEntry(fileName))
                        zip.write(text.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }
        } catch (error: IOException) {
            // Do not leave a half-written archive visible in Downloads.
            runCatching { context.contentResolver.delete(target, null, null) }
            throw error
        }
        return name
    }

    /** Reads a log file for inclusion as a companion entry, tail-bounded. */
    fun readAppendix(file: File, limitBytes: Int = 64 * 1024): String? {
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            val slice = if (bytes.size <= limitBytes) bytes else bytes.copyOfRange(
                bytes.size - limitBytes, bytes.size,
            )
            String(slice, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun entryFileName(entry: RunHistoryEntry, prefix: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date(entry.startedAtMillis))
        val job = entry.job.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "run" }
        return "$prefix-$stamp-$job-${entry.result.name.lowercase(Locale.US)}-" +
            "${entry.id.take(8)}.log"
    }
}
