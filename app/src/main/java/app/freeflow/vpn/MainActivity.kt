package app.freeflow.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FreeFlowScreen() }
    }
}

private data class EngineRow(val name: String, val detail: String, val enabled: Boolean)

@Composable
private fun FreeFlowScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { SecureStore(context) }
    var hasAccount by remember { mutableStateOf(!vault.get("ur_jwt").isNullOrBlank()) }
    var recovery by remember { mutableStateOf(vault.get("ur_recovery_phrase")) }
    var terms by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("disconnected") }
    var error by remember { mutableStateOf("") }

    fun startVpn() {
        val intent = Intent(context, UrNetworkVpnService::class.java).setAction(UrNetworkVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpn()
        else message = "مجوز VPN داده نشد."
    }

    LaunchedEffect(Unit) {
        while (true) {
            val p = context.getSharedPreferences("vpn_state", 0)
            state = p.getString("state", "disconnected") ?: "disconnected"
            error = p.getString("error", "") ?: ""
            delay(800)
        }
    }

    val engines = listOf(
        EngineRow("URnetwork", "SDK رسمی اندروید؛ اتصال در این نسخه", true),
        EngineRow("Lantern", "هنوز به پروژه متصل نشده", false),
        EngineRow("Geph", "هنوز به پروژه متصل نشده", false),
        EngineRow("Tor", "هنوز به پروژه متصل نشده", false),
        EngineRow("Psiphon", "هنوز به پروژه متصل نشده", false),
        EngineRow("Cloudflare WARP", "ادغام رسمی شخص ثالث تأیید نشده", false),
        EngineRow("Proton VPN", "ادغام رسمی شخص ثالث تأیید نشده", false),
        EngineRow("Windscribe", "ادغام رسمی شخص ثالث تأیید نشده", false)
    )

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 18.dp)
            ) {
                item {
                    Text("FreeFlowVPN", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("یک رابط واحد برای موتورهای اتصال مستقل")
                }
                item {
                    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text("وضعیت اتصال", style = MaterialTheme.typography.titleMedium)
                            Text(when (state) {
                                "connecting" -> "در حال اتصال…"
                                "connected" -> "متصل به URnetwork"
                                "error" -> "خطا در اتصال"
                                else -> "قطع"
                            }, style = MaterialTheme.typography.headlineSmall)
                            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(10.dp))
                            if (state == "connected" || state == "connecting") {
                                Button(onClick = {
                                    context.startService(Intent(context, UrNetworkVpnService::class.java).setAction(UrNetworkVpnService.ACTION_STOP))
                                }, modifier = Modifier.fillMaxWidth()) { Text("قطع اتصال") }
                            } else {
                                Button(
                                    enabled = hasAccount && !busy,
                                    onClick = {
                                        val prepare = VpnService.prepare(context)
                                        if (prepare == null) startVpn() else permission.launch(prepare)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("اتصال") }
                            }
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text("حساب URnetwork", style = MaterialTheme.typography.titleLarge)
                            Text(if (hasAccount) "حساب شخصی روی این دستگاه ذخیره شده است." else "برای اتصال، حساب شخصی رایگان بساز.")
                            if (!hasAccount) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = terms, onCheckedChange = { terms = it })
                                    Text("شرایط و قوانین URnetwork را می‌پذیرم.")
                                }
                                Button(
                                    enabled = terms && !busy,
                                    onClick = {
                                        busy = true
                                        message = ""
                                        scope.launch {
                                            try {
                                                val result = withContext(Dispatchers.IO) { UrnetworkAccount.create(context) }
                                                hasAccount = true
                                                recovery = result.recoveryPhrase
                                                message = "حساب ساخته شد؛ عبارت بازیابی را ذخیره کن."
                                            } catch (e: Exception) {
                                                message = e.message ?: "ساخت حساب ناموفق بود."
                                            } finally { busy = false }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (busy) "در حال ساخت حساب…" else "ساخت حساب رایگان") }
                            } else {
                                OutlinedButton(onClick = {
                                    context.startService(Intent(context, UrNetworkVpnService::class.java).setAction(UrNetworkVpnService.ACTION_STOP))
                                    vault.remove("ur_jwt")
                                    vault.remove("ur_recovery_phrase")
                                    hasAccount = false
                                    recovery = null
                                    message = "اطلاعات حساب از این دستگاه پاک شد."
                                }, modifier = Modifier.fillMaxWidth()) { Text("خروج و پاک‌کردن حساب") }
                            }
                            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item { Text("موتورهای اتصال", style = MaterialTheme.typography.titleLarge) }
                items(engines) { engine ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor =
                            if (engine.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(engine.name, style = MaterialTheme.typography.titleMedium)
                                Text(engine.detail, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(if (engine.enabled) "آماده" else "غیرفعال")
                        }
                    }
                }
                item {
                    Text("FreeFlowVPN سرور اختصاصی یا حساب مشترک ندارد؛ سهمیه و محدودیت‌های هر سرویس متعلق به همان ارائه‌دهنده است.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (!recovery.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("عبارت بازیابی حساب") },
            text = { Column {
                Text("این عبارت فقط یک‌بار نمایش داده می‌شود. آن را در جای امن و آفلاین نگه دار و برای کسی نفرست.")
                Spacer(Modifier.height(12.dp))
                Text(recovery.orEmpty(), fontWeight = FontWeight.Bold)
            } },
            confirmButton = {
                TextButton(onClick = {
                    vault.remove("ur_recovery_phrase")
                    recovery = null
                }) { Text("ذخیره کردم") }
            }
        )
    }
}
