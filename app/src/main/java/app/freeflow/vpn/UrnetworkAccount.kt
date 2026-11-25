package app.freeflow.vpn

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AccountCreation(val recoveryPhrase: String)

/** Uses URnetwork's documented public instant-account flow; no shared app credential is embedded. */
object UrnetworkAccount {
    fun create(context: Context): AccountCreation {
        val payload = JSONObject()
            .put("terms", true)
            .put("product_updates", false)
            .put("result_errors", true)

        val connection = (URL("https://api.bringyour.com/auth/network-create").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(body) } catch (_: Exception) {
                throw IOException("URnetwork پاسخ قابل‌خواندن نداد (HTTP $code).")
            }
            val error = json.optJSONObject("error")?.optString("message").orEmpty()
            val network = json.optJSONObject("network")
            val jwt = network?.optString("by_jwt").orEmpty()
            if (code !in 200..299 || jwt.isBlank()) {
                throw IOException(error.ifBlank { "ساخت حساب ناموفق بود (HTTP $code)." })
            }
            val phrase = json.optString("seedphrase").orEmpty()
            if (phrase.isBlank()) {
                throw IOException("حساب ساخته شد اما عبارت بازیابی در پاسخ نبود؛ برای جلوگیری از قفل‌شدن حساب، اتصال را شروع نکردیم.")
            }
            SecureStore(context).apply {
                put("ur_jwt", jwt)
                put("ur_recovery_phrase", phrase)
            }
            return AccountCreation(phrase)
        } finally {
            connection.disconnect()
        }
    }
}
