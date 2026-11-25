package app.freeflow.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local diagnostic ring buffer. Never records JWTs, recovery phrases, packet contents,
 * IP addresses, Wi-Fi names, or visited domains.
 */
object VpnDiagnostics {
    private const val PREFS = "freeflow_diagnostics"
    private const val KEY_EVENTS = "events"
    private const val MAX_CHARS = 18_000
    private val lock = Any()

    fun log(context: Context, stage: String, detail: String = "") {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val safe = redactSecrets(detail).take(700)
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getString(KEY_EVENTS, "").orEmpty()
            val next = (old + "\n" + "$stamp | $stage | $safe").takeLast(MAX_CHARS)
            prefs.edit().putString(KEY_EVENTS, next).apply()
        }
    }

    private fun redactSecrets(value: String): String {
        // Handle both key=value logs and JSON-like key: "value" fragments.
        val secretPattern = Regex(
            """(?i)(["']?(?:by_jwt|jwt|token|seedphrase|recovery_phrase|recovery|password|authorization)["']?\s*[:=]\s*["']?)[^"'\s,;}]+"""
        )
        return secretPattern.replace(value) { match ->
            match.groupValues[1] + "[REDACTED]"
        }
    }

    fun logError(context: Context, stage: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        log(context, stage, "${error.javaClass.name}: ${error.message ?: "(no message)"}\n$sw")
    }

    fun buildReport(context: Context): String {
        val app = context.applicationContext
        val vpnPermission = VpnService.prepare(app) == null
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("Cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        }.ifEmpty { listOf("unknown") }.joinToString(", ")
        val statePrefs = app.getSharedPreferences("vpn_state", Context.MODE_PRIVATE)
        val events = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, "").orEmpty()
        return buildString {
            appendLine("FreeFlowVPN diagnostic report")
            appendLine("Report time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date()))
            appendLine("App version: 0.3.0")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("VPN permission granted: $vpnPermission")
            appendLine("Active network transport: $transports")
            appendLine("Internet capability: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true}")
            appendLine("Validated network: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}")
            appendLine("Current app state: ${statePrefs.getString("state", "unknown")}")
            appendLine("Last app error: ${redactSecrets(statePrefs.getString("error", "").orEmpty()).ifBlank { "(none)" }}")
            appendLine()
            appendLine("Recent diagnostic events (newest at bottom):")
            appendLine(events.ifBlank { "(no events recorded)" })
            appendLine()
            appendLine("Privacy: no account JWT, recovery phrase, packet data, IP address, Wi-Fi name, or browsing history.")
        }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_EVENTS).apply()
    }
}
