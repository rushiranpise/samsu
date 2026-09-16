/*
 * Ported from Root-My-Galaxy-Extended (dev.busung.s25uroot) by igorcv88, which
 * derives from BuSung-dev/Root-My-Galaxy. Apache License 2.0.
 */
package dev.indevelopment.m3qroot.rmg

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes Shizuku startup attempts across SamSU processes.
 *
 * The boot service and a manual start can both observe "no Binder" and would
 * otherwise launch two Shizuku servers at the same time. A process-local mutex
 * is not enough, so an advisory file lock backs it.
 */
object ShizukuStartCoordinator {
    private val localMutex = Mutex()

    suspend fun <T> withStartLock(
        context: Context,
        block: suspend () -> T,
    ): T = localMutex.withLock {
        withContext(Dispatchers.IO) {
            val lockFile = File(context.noBackupFilesDir, LOCK_FILE_NAME)
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.use { channel ->
                    val lock = channel.lock()
                    try {
                        block()
                    } finally {
                        runCatching { lock.release() }
                    }
                }
            }
        }
    }

    private const val LOCK_FILE_NAME = "shizuku-start.lock"
}
