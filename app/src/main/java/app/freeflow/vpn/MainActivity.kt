package app.freeflow.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class Engine(val name: String, val detail: String, val state: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FreeFlowApp() }
    }
}

@Composable
private fun FreeFlowApp() {
    val engines = listOf(
        Engine("URnetwork", "شبکه مستقل؛ اتصال از طریق SDK رسمی", "در انتظار پیاده‌سازی"),
        Engine("Lantern", "کلاینت متن‌باز Lantern", "در انتظار بررسی فنی"),
        Engine("Geph", "کلاینت متن‌باز Geph", "در انتظار بررسی فنی"),
        Engine("Tor", "شبکه داوطلبانه Tor", "در انتظار بررسی فنی"),
        Engine("Psiphon", "سرویس Psiphon", "در انتظار بررسی فنی"),
        Engine("Cloudflare WARP", "سرویس رسمی Cloudflare", "در انتظار بررسی فنی"),
        Engine("Proton VPN", "پلن رایگان Proton", "نیازمند بررسی روش رسمی اتصال"),
        Engine("Windscribe", "پلن رایگان Windscribe", "نیازمند بررسی روش رسمی اتصال")
    )
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("FreeFlowVPN", style = MaterialTheme.typography.headlineMedium)
                Text("چند موتور اتصال، یک رابط واحد", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text("وضعیت اتصال", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("هنوز موتوری متصل نشده", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("این نسخه فقط پوسته رابط کاربری است؛ اتصال VPN فعال نیست.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("موتورها", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(engines) { engine ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(engine.name, style = MaterialTheme.typography.titleMedium)
                                    Text(engine.detail, style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(4.dp))
                                    Text(engine.state, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                OutlinedButton(onClick = { }) { Text("جزئیات") }
                            }
                        }
                    }
                }
            }
        }
    }
}
