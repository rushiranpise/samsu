/*
 * New file for SamSU: a small Java-friendly surface over the ported ADB stack.
 * The ported files keep Root-My-Galaxy-Extended's `internal` visibility, so
 * this is the only type the activity needs to talk to.
 */
package dev.indevelopment.m3qroot.rmg

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.runBlocking

/** Result of a live local-ADB connection test, for the activity to display. */
data class AdbTestOutcome(
    val ok: Boolean,
    val summary: String,
    val detail: String,
)

/**
 * The in-app Wireless ADB transport.
 *
 * This is what lets SamSU reach a `u:r:shell:s0` shell without a PC and without
 * Shizuku: pair once against the device's own adbd, then open a session over
 * loopback. Nothing here touches the exploit path — it only provides the
 * transport the exploit and KernelSU handoff can run over.
 */
object WirelessAdbFacade {

    /** Starts the one-time pairing flow, which prompts for the 6-digit code. */
    fun startPairing(context: Context, forceRepair: Boolean) {
        context.startActivity(AdbPairingSetupActivity.pairingIntent(context, forceRepair))
    }

    /** Opens Developer options, where Wireless Debugging and the code live. */
    fun openDeveloperSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun isPaired(context: Context): Boolean = AdbPrefs.isPaired(context)

    fun hasStoredKey(context: Context): Boolean = AdbCredentialStore.hasStoredKey(context)

    fun fingerprint(context: Context): String? = AdbCredentialStore.fingerprint(context)

    fun isWirelessDebuggingEnabled(context: Context): Boolean =
        AdbPairing.isWirelessAdbEnabled(context)

    /**
     * True once the one-time grant is in place. Without it Wireless Debugging
     * can only be turned on by hand, which the pairing flow asks the user to do.
     */
    fun hasWriteSecureSettings(context: Context): Boolean =
        AdbPairing.hasWriteSecureSettings(context)

    /** Status without touching the network or the ADB daemon. */
    fun statusSummary(context: Context): String =
        summary(WirelessAdbDiagnostics.passiveSnapshot(context))

    fun statusDetail(context: Context): String =
        WirelessAdbDiagnostics.passiveSnapshot(context).detail

    /**
     * Turns Wireless Debugging on for the length of one real TLS shell round
     * trip, then turns it back off. Blocking: call from a worker thread.
     */
    fun testConnection(context: Context): AdbTestOutcome {
        val snapshot = runBlocking { WirelessAdbDiagnostics.testConnection(context) }
        return AdbTestOutcome(
            ok = snapshot.authState == WirelessAdbAuthState.Valid,
            summary = summary(snapshot),
            detail = snapshot.detail,
        )
    }

    /** Drops the local ADB identity; a new pairing is required afterwards. */
    fun forgetCredential(context: Context): Boolean =
        AdbCredentialStore.forgetLocalCredential(context)

    private fun summary(snapshot: WirelessAdbDiagnosticSnapshot): String {
        val label = when (snapshot.authState) {
            WirelessAdbAuthState.Valid -> "verified"
            WirelessAdbAuthState.SavedUnverified -> "paired, unverified"
            WirelessAdbAuthState.NoCredential -> "not paired"
            WirelessAdbAuthState.PairingRejected -> "pairing rejected"
            WirelessAdbAuthState.MdnsUnavailable -> "pairing service not found"
            WirelessAdbAuthState.PermissionRequired -> "permission required"
            WirelessAdbAuthState.ConnectionFailed -> "connection failed"
        }
        val key = if (snapshot.keyPresent) "key present" else "no key"
        val debugging = if (snapshot.wirelessDebuggingEnabled) "on" else "off"
        val port = snapshot.connectPort?.takeIf { it > 0 }?.let { " port $it" } ?: ""
        return "$label ($key, wireless debugging $debugging$port)"
    }
}
