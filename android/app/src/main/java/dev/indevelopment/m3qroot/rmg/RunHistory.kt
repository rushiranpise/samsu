package dev.indevelopment.m3qroot.rmg

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Per-job run history for SamSU.
 *
 * Ported from Root-My-Galaxy-Extended (`InstallHistory.kt`) by igorcv88, itself
 * based on BuSung-dev/Root-My-Galaxy (Apache License 2.0). Adapted to SamSU:
 * entries describe any hold-to-run job rather than only an install, each entry
 * is written atomically, and a run interrupted by process death is closed as
 * failed on the next start instead of staying "Running" forever.
 */

enum class RunResult {
    Running,
    Succeeded,
    Failed,
}

data class RunHistoryEntry(
    val id: String,
    val job: String,
    val startedAtMillis: Long,
    val completedAtMillis: Long?,
    val result: RunResult,
    val log: String,
    val payloadId: String? = null,
    val usedShizuku: Boolean = false,
)

class RunHistoryStore(private val context: Context) {
    private val directory = File(context.filesDir, "run-history").apply { mkdirs() }

    fun load(): List<RunHistoryEntry> = directory
        .listFiles { file -> file.extension == "json" }
        .orEmpty()
        .mapNotNull(::decodeOrQuarantine)
        .sortedByDescending(RunHistoryEntry::startedAtMillis)

    /**
     * Any entry still marked running belongs to a process that died mid-job.
     * Close it so the history never advertises a run that cannot finish.
     *
     * @return how many entries were actually closed by this call.
     */
    fun closeInterruptedRuns(): Int {
        var closed = 0
        load().forEach { entry ->
            if (entry.result == RunResult.Running) {
                save(
                    entry.copy(
                        completedAtMillis = System.currentTimeMillis(),
                        result = RunResult.Failed,
                        log = closeLog(
                            entry.log,
                            "==== interrupted (app process ended) ====",
                        ),
                    ),
                )
                closed++
            }
        }
        return closed
    }

    fun begin(
        job: String,
        payloadId: String? = null,
        usedShizuku: Boolean = false,
    ): RunHistoryEntry = RunHistoryEntry(
        id = UUID.randomUUID().toString(),
        job = job,
        startedAtMillis = System.currentTimeMillis(),
        completedAtMillis = null,
        result = RunResult.Running,
        log = "",
        payloadId = payloadId,
        usedShizuku = usedShizuku,
    )

    /** Writes the terminal state. No-op for an entry that already finished. */
    fun finish(
        entry: RunHistoryEntry,
        result: RunResult,
        log: String,
    ): RunHistoryEntry {
        val completed = entry.copy(
            completedAtMillis = System.currentTimeMillis(),
            result = result,
            log = trimLog(log),
        )
        save(completed)
        return completed
    }

    fun save(entry: RunHistoryEntry) {
        val target = File(directory, "${entry.id}.json")
        val atomicFile = AtomicFile(target)
        val output = atomicFile.startWrite()
        try {
            output.write(encode(entry).toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    fun delete(id: String) {
        File(directory, "$id.json").delete()
    }

    fun deleteAll() {
        directory.listFiles()?.forEach(File::delete)
    }

    private fun encode(entry: RunHistoryEntry) = JSONObject()
        .put("id", entry.id)
        .put("job", entry.job)
        .put("startedAtMillis", entry.startedAtMillis)
        .put("completedAtMillis", entry.completedAtMillis ?: JSONObject.NULL)
        .put("result", entry.result.name)
        .put("log", entry.log)
        .put("payloadId", entry.payloadId ?: JSONObject.NULL)
        .put("usedShizuku", entry.usedShizuku)

    private fun decodeOrQuarantine(file: File): RunHistoryEntry? = try {
        decode(AtomicFile(file).openRead().use { it.readBytes() })
    } catch (_: Throwable) {
        val quarantined = File(directory, "${file.name}.corrupt")
        quarantined.delete()
        file.renameTo(quarantined)
        null
    }

    private fun decode(bytes: ByteArray): RunHistoryEntry {
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        return RunHistoryEntry(
            id = value.getString("id"),
            job = value.optString("job").takeIf(String::isNotBlank) ?: "root",
            startedAtMillis = value.getLong("startedAtMillis"),
            completedAtMillis = if (value.isNull("completedAtMillis")) {
                null
            } else {
                value.getLong("completedAtMillis")
            },
            result = RunResult.valueOf(value.getString("result")),
            log = value.getString("log"),
            payloadId = if (value.isNull("payloadId")) {
                null
            } else {
                value.getString("payloadId").takeIf(String::isNotBlank)
            },
            usedShizuku = value.optBoolean("usedShizuku", false),
        )
    }

    private fun trimLog(log: String): String {
        if (log.length <= MAX_LOG_CHARS) return log
        return "[Earlier lines trimmed]\n" + log.takeLast(MAX_LOG_CHARS)
    }

    private fun closeLog(log: String, line: String): String {
        val header = if (log.isBlank()) "" else "==== run ====\n"
        return trimLog(header + log.trimEnd('\n') + "\n" + line + "\n")
    }

    private companion object {
        /** Bound on a single entry, so a chatty run cannot fill app storage. */
        const val MAX_LOG_CHARS = 128 * 1024
    }
}
