package app.phonakbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Recent activity, shown in the app. */
object BridgeLog {
    val lines = MutableStateFlow<List<String>>(emptyList())
    private val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun add(message: String) {
        Log.i("PhonakBridge", message)
        val line = "${time.format(Date())}  $message"
        lines.value = (listOf(line) + lines.value).take(200)
    }
}

/** What the app is set up with. */
data class Settings(
    val webhookUrl: String,
    val right: String?, // Bluetooth address of the aid's LE device
    val left: String?,
    val checkInSeconds: Int,
    val readSeconds: Int,
    val enabled: Boolean,
) {
    val aids get() = listOfNotNull(right?.let { "right" to it }, left?.let { "left" to it })

    fun save(context: Context) {
        prefs(context).edit()
            .putString("webhook_url", webhookUrl)
            .putString("right", right)
            .putString("left", left)
            .putInt("check_in_seconds", checkInSeconds)
            .putInt("read_seconds", readSeconds)
            .putBoolean("enabled", enabled)
            .apply()
    }

    companion object {
        const val DEFAULT_CHECK_IN = 30
        const val DEFAULT_READ = 300

        private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun load(context: Context): Settings = prefs(context).let {
            Settings(
                webhookUrl = it.getString("webhook_url", "") ?: "",
                right = it.getString("right", null),
                left = it.getString("left", null),
                checkInSeconds = it.getInt("check_in_seconds", DEFAULT_CHECK_IN),
                readSeconds = it.getInt("read_seconds", DEFAULT_READ),
                enabled = it.getBoolean("enabled", false),
            )
        }
    }
}

/**
 * The LE link and session to one hearing aid. Like the old HA client, the
 * session stays open while it is used and drops after a short idle time:
 * the aid refuses a new session right after the previous one.
 */
class AidConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    val side: String,
    val address: String,
) {
    private val lock = Mutex()
    private var session: AidSession? = null
    private var idleClose: Job? = null

    @SuppressLint("MissingPermission")
    suspend fun <T> use(block: suspend (AidSession) -> T): T = lock.withLock {
        idleClose?.cancel()
        try {
            val current = session?.takeIf { it.alive } ?: open()
            block(current)
        } catch (e: Exception) {
            close()
            throw if (e is AidException) e else AidException(e.message ?: e.toString(), e)
        } finally {
            idleClose = scope.launch {
                delay(IDLE_CLOSE_MS)
                lock.withLock { close() }
            }
        }
    }

    private suspend fun open(): AidSession {
        close()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) throw AidException("Bluetooth is off")
        val link = GattLink.connect(context, adapter.getRemoteDevice(address))
        val opened = AidSession(link, scope)
        try {
            // The aid only answers on an encrypted link; reading this makes Android
            // encrypt it with the bond myPhonak created.
            if (link.characteristic(Protocol.SECURE_CHAR) == null) {
                // Seen while myPhonak was connecting too; a wrong device has none either.
                throw AidException("Sonova service not found (myPhonak busy, or not a hearing aid's LE device)")
            }
            try {
                link.read(Protocol.SECURE_CHAR)
            } catch (e: AidException) {
                throw AidException("Link not encrypted; is the phone still paired with this aid? (${e.message})", e)
            }
            opened.open()
        } catch (e: Exception) {
            opened.close()
            throw e
        }
        session = opened
        return opened
    }

    fun close() {
        session?.close()
        session = null
    }

    companion object {
        const val IDLE_CLOSE_MS = 15_000L
    }
}

/** Reads the aids and runs Home Assistant's commands on them. */
class Bridge(private val context: Context, private val scope: CoroutineScope) {
    private var connections = emptyMap<String, AidConnection>()

    class CycleResult(val aids: JSONObject, val results: JSONArray)

    private fun connectionsFor(settings: Settings): List<AidConnection> {
        val wanted = settings.aids.toMap()
        if (connections.mapValues { it.value.address } != wanted) {
            connections.values.forEach { it.close() }
            connections = wanted.mapValues { (side, address) -> AidConnection(context, scope, side, address) }
        }
        return connections.values.toList()
    }

    fun close() {
        connections.values.forEach { it.close() }
        connections = emptyMap()
    }

    /** One pass over both aids: commands first, then a fresh read for the report. */
    suspend fun cycle(settings: Settings, commands: List<Command>): CycleResult = coroutineScope {
        val aids = connectionsFor(settings)
        // Right first and the left a moment later, as myPhonak connects them.
        val outcomes = aids.mapIndexed { index, aid ->
            async {
                delay(index * 600L)
                runSide(aid, commands.filter { aid.side in it.sides })
            }
        }.awaitAll()

        val report = JSONObject()
        val errors = mutableMapOf<String, MutableList<String>>() // command id -> errors
        for ((aid, outcome) in aids.zip(outcomes)) {
            report.put(aid.side, outcome.first)
            for ((id, error) in outcome.second) if (error != null) errors.getOrPut(id) { mutableListOf() } += error
        }
        val configured = aids.map { it.side }.toSet()
        val results = JSONArray()
        for (command in commands) {
            val problems = errors[command.id] ?: mutableListOf()
            // Pair-wide commands are fine with one aid set up; not with none.
            if (command.sides.none { it in configured }) problems += "no ${command.sides.joinToString("/")} aid set up in the app"
            results.put(JSONObject().put("id", command.id).put("ok", problems.isEmpty())
                .apply { if (problems.isNotEmpty()) put("error", problems.joinToString("; ")) })
            BridgeLog.add("${command.action} ${command.value ?: ""} ${command.sides}: " +
                if (problems.isEmpty()) "done" else problems.joinToString("; "))
        }
        CycleResult(report, results)
    }

    /** Returns the aid's report and an error (or null) per command id. */
    private suspend fun runSide(aid: AidConnection, commands: List<Command>): Pair<JSONObject, Map<String, String?>> {
        val results = mutableMapOf<String, String?>()
        return try {
            val values = aid.use { session ->
                for (command in commands) {
                    results[command.id] = try {
                        session.run(command)
                        null
                    } catch (e: AidException) {
                        "${aid.side}: ${e.message}"
                    }
                }
                session.readAll()
            }
            BridgeLog.add("${aid.side}: battery ${values.opt("battery")}%, program ${values.opt("program")}, " +
                "volume ${values.opt("volume")}")
            values to results
        } catch (e: AidException) {
            val message = e.message ?: "unknown error"
            BridgeLog.add("${aid.side}: $message")
            // Commands that ran before the failure keep their result.
            for (command in commands) if (command.id !in results) results[command.id] = "${aid.side}: $message"
            JSONObject().put("error", message) to results
        }
    }
}
