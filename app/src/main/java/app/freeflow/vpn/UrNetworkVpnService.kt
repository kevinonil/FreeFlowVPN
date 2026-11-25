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
import android.system.Os
import android.system.OsConstants
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
    @Volatile private var startInProgress = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        VpnDiagnostics.log(this, "service_created", "Android " + Build.VERSION.SDK_INT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                if (startInProgress || getSharedPreferences(PREFS, MODE_PRIVATE).getString(STATE_KEY, "disconnected") == "connected") {
                    VpnDiagnostics.log(this, "start_ignored", "Connection attempt already active")
                    return START_NOT_STICKY
                }
                stopping = false
                startInProgress = true
                startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی اتصال…"))
                setState("connecting")
                VpnDiagnostics.log(this, "start_requested")
                Thread({ startTunnel() }, "FreeFlow-URnetwork").start()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        var stage = "load_account"
        try {
            val jwt = SecureStore(this).get("ur_jwt")
                ?: throw IllegalStateException("ابتدا حساب URnetwork را ایجاد کنید.")
            stage = "check_network"
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val hasInternet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val validated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            VpnDiagnostics.log(this, stage, "internet=" + hasInternet + " validated=" + validated)
            if (!hasInternet) throw IllegalStateException("اتصال اینترنت گوشی در دسترس نیست.")
            stage = "sdk_manager"
            val prefs = getSharedPreferences("installation", MODE_PRIVATE)
            val instanceId = prefs.getString("ur_instance_id", null) ?: Sdk.newId().toString().also {
                prefs.edit().putString("ur_instance_id", it).apply()
            }

            val networkManager = Sdk.newNetworkSpaceManager(filesDir.absolutePath)
            manager = networkManager
            VpnDiagnostics.log(this, stage, "NetworkSpaceManager created")
            val key = Sdk.newNetworkSpaceKey("ur.network", "main")
            val space = networkManager.updateNetworkSpace(key) {
                it.migrationHostName = "bringyour.com"
            }
            space.api.setByJwt(jwt)
            VpnDiagnostics.log(this, "network_space", "Production space configured")
            stage = "create_device"

            val localDevice = Sdk.newDeviceLocalWithMemoryTarget(
                space,
                jwt,
                "FreeFlowVPN Android",
                Build.MODEL ?: "Android",
                "0.3.0",
                Sdk.parseId(instanceId),
                false,
                null,
                96L * 1024L * 1024L
            )
            device = localDevice
            VpnDiagnostics.log(this, stage, "DeviceLocal created")
            stage = "configure_device"

            // Fail closed: if the SDK tunnel goes away, do not silently route traffic locally.
            localDevice.setRouteLocal(false)
            localDevice.setConnectLocation(
                ConnectLocation().apply {
                    connectLocationId = ConnectLocationId().apply { bestAvailable = true }
                }
            )

            stage = "establish_tun"
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
            builder.addDisallowedApplication(packageName)

            val tun = builder.establish() ?: throw IllegalStateException("Android نتوانست رابط VPN (TUN) را ایجاد کند.")
            VpnDiagnostics.log(this, stage, "TUN established; MTU=1440; IPv4+IPv6 routes")
            stage = "configure_tun_fd"
            // URnetwork SDK documentation requires a non-blocking descriptor.
            val fdFlags = Os.fcntlInt(tun.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(tun.fileDescriptor, OsConstants.F_SETFL, fdFlags or OsConstants.O_NONBLOCK)
            VpnDiagnostics.log(this, stage, "TUN descriptor set to O_NONBLOCK")
            stage = "start_io_loop"
            val detachedFd = tun.detachFd()
            VpnDiagnostics.log(this, stage, "TUN fd detached and transferred to SDK")
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
            startInProgress = false
            VpnDiagnostics.log(this, "io_loop_started", "SDK IoLoop created successfully")
            if (!stopping) {
                setState("connected", "تونل سیستم ساخته شد؛ مسیر اینترنت هنوز با ترافیک واقعی تأیید نشده است.")
                mainHandler.post { updateNotification("تونل فعال • URnetwork") }
            } else {
                stopTunnel()
            }
        } catch (error: Throwable) {
            VpnDiagnostics.logError(this, "failure_at_" + stage, error)
            val reason = error.message ?: error.javaClass.name
            stopTunnel()
            startInProgress = false
            // Cleanup resets the normal state, so persist the failure after cleanup.
            setState("error", reason)
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopTunnel(updateState: Boolean = true) {
        stopping = true
        startInProgress = false
        try { closeIoLoop?.invoke() } catch (_: Exception) { }
        closeIoLoop = null
        try { device?.close() } catch (_: Exception) { }
        device = null
        try { manager?.close() } catch (_: Exception) { }
        manager = null
        if (updateState) setState("disconnected")
    }

    override fun onRevoke() {
        stopTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel(updateState = false)
        super.onDestroy()
    }

    private fun setState(state: String, error: String = "") {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(STATE_KEY, state)
            .putString(ERROR_KEY, error)
            .apply()
        VpnDiagnostics.log(this, "state", state + " " + error.take(250))
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
