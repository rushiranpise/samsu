package dev.indevelopment.m3qroot.rmg

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Integrity record for the exploit payload and KernelSU daemon actually used.
 *
 * Ported from Root-My-Galaxy-Extended's known-good payload cache
 * (`KnownGoodPayloadStore.kt`, `SupportManifest.kt`) by igorcv88
 * (Apache License 2.0), adapted to SamSU's feed: BuSung's `targets-v3.json`
 * publishes only `url` + `size`, so there is no upstream hash to check against.
 * Hashes are therefore pinned on first verified use (trust on first use) and
 * enforced on every later run, which still catches truncated files, a different
 * binary served at the same URL, and payloads staged by an older app version.
 *
 * A record covers three things at once: the payload hash, the KernelSU daemon
 * hash, and the device identity the run was verified on.
 */

enum class IntegrityVerdict {
    /** Nothing pinned yet; this is the artifact that would become the baseline. */
    Untracked,

    /** Hashes and device identity match the recorded verified run. */
    Verified,

    /** A hash changed without a device change: treat the artifact as suspect. */
    Corrupt,

    /** The recorded run happened on different firmware/kernel than this boot. */
    ForeignBuild,
}

data class IntegrityResult(
    val verdict: IntegrityVerdict,
    val detail: String,
    val exploitSha256: String?,
    val ksudSha256: String?,
) {
    /** Named `isTrusted` so the generated getter reads naturally from Java. */
    val isTrusted: Boolean
        get() = verdict == IntegrityVerdict.Verified || verdict == IntegrityVerdict.Untracked
}

data class PinnedPayload(
    val payloadId: String,
    val exploitSha256: String?,
    val ksudSha256: String?,
    val deviceDigest: String,
    val deviceLabel: String,
    val verifiedAtMillis: Long,
)

class PayloadIntegrityStore(private val context: Context) {
    private val prefs =
        context.getSharedPreferences("payload_integrity", Context.MODE_PRIVATE)

    fun identity(): DeviceIdentity = DeviceIdentity.current()

    fun pinned(payloadId: String): PinnedPayload? {
        val raw = prefs.getString(payloadId, null) ?: return null
        return runCatching {
            val value = JSONObject(raw)
            PinnedPayload(
                payloadId = payloadId,
                exploitSha256 = value.optString("exploitSha256").ifEmpty { null },
                ksudSha256 = value.optString("ksudSha256").ifEmpty { null },
                deviceDigest = value.optString("deviceDigest"),
                deviceLabel = value.optString("deviceLabel"),
                verifiedAtMillis = value.optLong("verifiedAtMillis", 0L),
            )
        }.getOrNull()
    }

    /**
     * Compares the artifacts on disk with the pinned record, if any.
     * A null [exploit] or [ksud] simply takes part out of the comparison.
     */
    fun verify(payloadId: String, exploit: File?, ksud: File?): IntegrityResult {
        val exploitHash = exploit?.takeIf(File::isFile)?.let(::sha256)
        val ksudHash = ksud?.takeIf(File::isFile)?.let(::sha256)
        val pin = pinned(payloadId)
            ?: return IntegrityResult(
                IntegrityVerdict.Untracked,
                "no verified run recorded for $payloadId yet",
                exploitHash,
                ksudHash,
            )

        if (pin.deviceDigest.isNotEmpty() && pin.deviceDigest != identity().digest) {
            return IntegrityResult(
                IntegrityVerdict.ForeignBuild,
                "verified on ${pin.deviceLabel.ifEmpty { "another build" }}, " +
                    "this boot is ${identity().label()}",
                exploitHash,
                ksudHash,
            )
        }

        val exploitChanged = pin.exploitSha256 != null && exploitHash != null &&
            pin.exploitSha256 != exploitHash
        val ksudChanged = pin.ksudSha256 != null && ksudHash != null &&
            pin.ksudSha256 != ksudHash
        if (exploitChanged || ksudChanged) {
            val what = when {
                exploitChanged && ksudChanged -> "payload and KernelSU daemon changed"
                exploitChanged -> "payload changed"
                else -> "KernelSU daemon changed"
            }
            return IntegrityResult(
                IntegrityVerdict.Corrupt,
                "$what since the verified run",
                exploitHash,
                ksudHash,
            )
        }

        return IntegrityResult(
            IntegrityVerdict.Verified,
            "matches the run verified on ${pin.deviceLabel}",
            exploitHash,
            ksudHash,
        )
    }

    /** Records the artifacts of a run that reached verified KernelSU root. */
    @Synchronized
    fun pin(payloadId: String, exploit: File?, ksud: File?): PinnedPayload? {
        // Without the payload there is nothing meaningful to bind.
        val exploitHash = exploit?.takeIf(File::isFile)?.let(::sha256) ?: return null
        val ksudHash = ksud?.takeIf(File::isFile)?.let(::sha256)
        val snapshot = identity()
        val record = JSONObject()
            .put("exploitSha256", exploitHash)
            .put("ksudSha256", ksudHash ?: JSONObject.NULL)
            .put("deviceDigest", snapshot.digest)
            .put("deviceLabel", snapshot.label())
            .put("verifiedAtMillis", System.currentTimeMillis())
        prefs.edit().putString(payloadId, record.toString()).commit()
        return pinned(payloadId)
    }

    fun forget(payloadId: String) {
        prefs.edit().remove(payloadId).commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }.getOrNull()
}
