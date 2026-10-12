package app.phonakbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Runs in the foreground: checks in with Home Assistant every few seconds,
 * reads the aids every few minutes (or when asked), and runs the changes
 * Home Assistant hands back in its replies.
 */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Starting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (wakeLock == null) {
            // Without it the check-ins stop while the screen is off.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhonakBridge:loop")
                .apply { acquire() }
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.release()
        wakeLock = null
        BridgeLog.add("Stopped")
        super.onDestroy()
    }

    private suspend fun run() {
        val bridge = Bridge(this, scope)
        BridgeLog.add("Started")
        var lastRead = 0L
        var commands = emptyList<Command>()
        var unsentAids: JSONObject? = null
        val unsentResults = JSONArray()
        try {
            while (scope.isActive) {
                val settings = Settings.load(this)
                val now = SystemClock.elapsedRealtime()
                if (commands.isNotEmpty() || lastRead == 0L || now - lastRead >= settings.readSeconds * 1000L) {
                    val result = bridge.cycle(settings, commands)
                    unsentAids = result.aids
                    for (i in 0 until result.results.length()) unsentResults.put(result.results.get(i))
                    lastRead = SystemClock.elapsedRealtime()
                    commands = emptyList()
                }

                val report = JSONObject().put("app_version", BuildConfig.VERSION_NAME)
                unsentAids?.let { report.put("aids", it) }
                if (unsentResults.length() > 0) report.put("results", unsentResults)
                try {
                    val reply = post(settings.webhookUrl, report)
                    unsentAids = null
                    while (unsentResults.length() > 0) unsentResults.remove(0)
                    val list = reply.optJSONArray("commands") ?: JSONArray()
                    commands = (0 until list.length()).map { Command.fromJson(list.getJSONObject(it)) }
                    if (commands.isNotEmpty()) BridgeLog.add("Home Assistant sent ${commands.joinToString { it.action }}")
                    updateNotification("Checked in ${java.text.DateFormat.getTimeInstance().format(java.util.Date())}")
                } catch (e: IOException) {
                    BridgeLog.add("Home Assistant: ${e.message}")
                    updateNotification("Can't reach Home Assistant")
                }
                // Run the changes right away; otherwise wait for the next check-in.
                if (commands.isEmpty()) delay(settings.checkInSeconds * 1000L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BridgeLog.add("Crashed: $e")
            throw e
        } finally {
            bridge.close()
        }
    }

    private suspend fun post(url: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (url.isBlank()) throw IOException("no webhook URL set")
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw IOException("bad webhook URL ($e)")
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            // Home Assistant answers 200 with no body for a webhook id it doesn't know.
            if (text.isBlank()) throw IOException("webhook not found; copy the URL from the integration again")
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw IOException("unexpected reply: ${text.take(80)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bridge running", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("Phonak Bridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
