package dev.indevelopment.m3qroot.rmg

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.security.MessageDigest

/**
 * Identity of the running device and kernel.
 *
 * Ported from Root-My-Galaxy-Extended (`DeviceSnapshot.kt`) by igorcv88
 * (Apache License 2.0). The snapshot is what binds a verified payload to the
 * exact firmware/kernel it was proven on, so a payload verified before a
 * firmware update is never silently reused after one.
 */
data class DeviceIdentity(
    val manufacturer: String,
    val model: String,
    val device: String,
    val kernelRelease: String,
    val kernelVersionInfo: String,
    val machine: String,
    val buildId: String,
    val fingerprint: String,
    val androidRelease: String,
    val sdk: Int,
    val abi: String,
    val pageSize: Long,
) {
    val kernelVersion: String
        get() = kernelRelease.takeWhile { it.isDigit() || it == '.' }

    /**
     * Stable digest of every field above. `uname.version` is included because it
     * carries the kernel build stamp, which distinguishes two builds that share
     * a release string.
     */
    val digest: String
        get() = sha256Hex(
            listOf(
                manufacturer, model, device, kernelRelease, kernelVersionInfo,
                machine, buildId, fingerprint, androidRelease, sdk.toString(),
                abi, pageSize.toString(),
            ).joinToString("|"),
        )

    fun label(): String = buildString {
        append(model.ifEmpty { "unknown model" })
        if (buildId.isNotEmpty()) append(" ").append(buildId)
        if (kernelRelease.isNotEmpty()) append(" kernel ").append(kernelRelease)
    }

    companion object {
        fun current(): DeviceIdentity {
            val uname = runCatching { Os.uname() }.getOrNull()
            return DeviceIdentity(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                kernelRelease = uname?.release.orEmpty(),
                kernelVersionInfo = uname?.version.orEmpty(),
                machine = uname?.machine.orEmpty(),
                buildId = Build.DISPLAY.orEmpty(),
                fingerprint = Build.FINGERPRINT.orEmpty(),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                pageSize = runCatching {
                    Os.sysconf(OsConstants._SC_PAGESIZE)
                }.getOrDefault(0L),
            )
        }
    }
}

internal fun sha256Hex(text: String): String =
    sha256Hex(text.toByteArray(Charsets.UTF_8))

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
