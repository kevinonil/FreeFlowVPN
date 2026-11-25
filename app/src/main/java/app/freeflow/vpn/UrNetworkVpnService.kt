package app.freeflow.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.bringyour.sdk.ConnectLocation
import com.bringyour.sdk.ConnectLocationId
import com.bringyour.sdk.DeviceLocal
import com.bringyour.sdk.NetworkSpaceManager
import com.bringyour.sdk.Sdk
import java.io.File

/**
 * Full-device VPN tunnel backed by URnetwork's official Android SDK.
 * The SDK owns packet processing after the TUN descriptor is detached.
 */
class UrNetworkVpnService : VpnService() {
    companion object {
        const val ACTION_START = "app.freeflow.vpn.START"
        const val ACTION_STOP = "app.freeflow.vpn.STOP"
        private const val CHANNEL_ID = "freeflow_vpn"
        private const val NOTIFICATION_ID = 4101
        private const val PREFS = "vpn_state"
        private const val STATE_KEY = "state"
        private const val ERROR_KEY = "error"
    }

    private var manager: NetworkSpaceManager? = null
    private var device: DeviceLocal? = null
    private var closeIoLoop: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                stopping = false
                startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی اتصال…"))
                setState("connecting")
                Thread({ startTunnel() }, "FreeFlow-URnetwork").start()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        try {
            val jwt = SecureStore(this).get("ur_jwt")
                ?: throw IllegalStateException("ابتدا حساب URnetwork را ایجاد کنید.")
            val prefs = getSharedPreferences("installation", MODE_PRIVATE)
            val instanceId = prefs.getString("ur_instance_id", null) ?: Sdk.newId().toString().also {
                prefs.edit().putString("ur_instance_id", it).apply()
            }

            val networkManager = Sdk.newNetworkSpaceManager(filesDir.absolutePath)
            manager = networkManager
            val key = Sdk.newNetworkSpaceKey("ur.network", "main")
            val space = networkManager.updateNetworkSpace(key) {
                it.migrationHostName = "bringyour.com"
            }
            space.api.setByJwt(jwt)

            val localDevice = Sdk.newDeviceLocalWithMemoryTarget(
                space,
                jwt,
                "FreeFlowVPN Android",
                Build.MODEL ?: "Android",
                "0.2.0",
                Sdk.parseId(instanceId),
                false,
                null,
                96L * 1024L * 1024L
            )
            device = localDevice

            // Fail closed: if the SDK tunnel goes away, do not silently route traffic locally.
            localDevice.setRouteLocal(false)
            localDevice.setConnectLocation(
                ConnectLocation().apply {
                    connectLocationId = ConnectLocationId().apply { bestAvailable = true }
                }
            )

            val builder = Builder()
                .setSession("FreeFlowVPN • URnetwork")
                .setMtu(1440)
                .addAddress("10.64.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addAddress("fd00:6466:6c6f:77::2", 128)
                .addRoute("::", 0)
                .addDnsServer("2606:4700:4700::1111")

            // Keep the app's own control-plane traffic outside its TUN to prevent a routing loop.
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) { }

            val tun = builder.establish() ?: throw IllegalStateException("Android اجازه ساخت تونل VPN را نداد.")
            val detachedFd = tun.detachFd()
            val loop = Sdk.newIoLoop(localDevice, detachedFd) {
                mainHandler.post {
                    if (!stopping) {
                        // Preserve the terminal reason for the UI instead of clearing it during cleanup.
                        stopTunnel()
                        setState("error", "تونل توسط موتور شبکه بسته شد؛ اتصال پیش از تکمیل متوقف شد.")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            closeIoLoop = { loop.close() }
            if (!stopping) {
                setState("connected")
                mainHandler.post { updateNotification("متصل به URnetwork") }
            } else {
                stopTunnel()
            }
        } catch (error: Exception) {
            val reason = error.message ?: error.javaClass.simpleName.ifBlank { "خطای ناشناخته در اتصال." }
            stopTunnel()
            // Cleanup resets the normal state, so persist the failure after cleanup.
            setState("error", reason)
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopTunnel() {
        stopping = true
        try { closeIoLoop?.invoke() } catch (_: Exception) { }
        closeIoLoop = null
        try { device?.close() } catch (_: Exception) { }
        device = null
        try { manager?.close() } catch (_: Exception) { }
        manager = null
        setState("disconnected")
    }

    override fun onRevoke() {
        stopTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun setState(state: String, error: String = "") {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(STATE_KEY, state)
            .putString(ERROR_KEY, error)
            .apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FreeFlowVPN connection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("FreeFlowVPN")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("FreeFlowVPN")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }
}
