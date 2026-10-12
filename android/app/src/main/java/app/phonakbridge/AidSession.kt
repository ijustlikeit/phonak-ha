package app.phonakbridge

import android.os.SystemClock
import android.util.Log
import app.phonakbridge.Protocol as P
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AidSession"
private const val RESPONSE_TIMEOUT = 5_000L
// The aid ends the session after ~3 s without a request (myPhonak polls every 2 s).
private const val KEEPALIVE_INTERVAL = 1_500L
private const val BUSY = "myPhonak is using the hearing aids; close it to let the bridge in"

/** A command from Home Assistant, as handed out in the webhook reply. */
data class Command(
    val id: String,
    val action: String,
    val sides: List<String>,
    val value: Any?,
    val device: String?,
) {
    companion object {
        fun fromJson(json: JSONObject) = Command(
            id = json.getString("id"),
            action = json.getString("action"),
            sides = json.optJSONArray("sides")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: emptyList(),
            value = json.opt("value"),
            device = json.optString("device").ifEmpty { null },
        )
    }
}

/**
 * One open Sonova protocol session on a hearing aid: framing, sequence
 * numbers, reply matching, keepalive, and the reads and writes Home
 * Assistant asks for.
 */
class AidSession(private val link: GattLink, private val scope: CoroutineScope) {
    private var seq = -1 // identify must use seq 0 or the aid refuses everything
    private val decoder = P.FrameDecoder()
    // seq -> (expected reply opcode, reply)
    private val pending = ConcurrentHashMap<Int, Pair<Int, CompletableDeferred<ByteArray>>>()
    // Requests we gave up on: their late replies are not a sign of another app.
    private val abandoned: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    @Volatile private var contended: String? = null
    private val writeLock = Mutex()
    private var maxValue = 20
    @Volatile private var lastRequest = 0L
    private var keepalive: Job? = null
    @Volatile var alive = false
        private set
    lateinit var identity: P.Identity
        private set

    /**
     * Android hands every app on the link the aid's notifications. When myPhonak
     * runs a session at the same time, its replies arrive here too and their
     * sequence numbers collide with ours (2026-10-10: battery read as 122 %).
     * So any sign of a second session ends ours instead of trusting the replies.
     */
    private fun contention(reason: String) {
        if (contended == null) Log.i(TAG, "Another session on ${link.device.address}: $reason")
        contended = contended ?: reason
        alive = false
        val error = AidException(BUSY)
        pending.values.forEach { it.second.completeExceptionally(error) }
        pending.clear()
    }

    private fun onResponse(data: ByteArray) {
        if (data.size >= 3 && data.copyOfRange(0, 3).contentEquals(P.OPEN_REFUSED)) {
            // `00 01 01 03` refuses our open; `00 01 01 00` answers someone else's.
            contention("session open answer ${data.toHex()}")
            return
        }
        val frame = try {
            synchronized(decoder) { decoder.feed(data) }
        } catch (e: P.ProtocolException) {
            Log.d(TAG, "Ignoring notification: ${e.message}")
            return
        } ?: return
        val (address, messages) = frame
        if (address.contentEquals(P.ADDR_EVENT)) return // "parameter changed" events
        for (msg in messages) {
            if (msg.size < 3) continue
            val seq = P.u8(msg, 1)
            val waiting = pending[seq]
            when {
                waiting != null && P.u8(msg, 0) == waiting.first -> {
                    pending.remove(seq)
                    waiting.second.complete(msg)
                }
                waiting == null && abandoned.remove(seq) -> Unit // late reply to a timed-out request
                else -> contention("unexpected reply ${msg.copyOfRange(0, minOf(msg.size, 4)).toHex()}")
            }
        }
    }

    suspend fun open() {
        maxValue = maxOf(20, link.mtu - 3)
        val linkReady = CompletableDeferred<Unit>()
        link.notifyHandlers[P.RESPONSE_CHAR] = ::onResponse
        link.notifyHandlers[P.LINK_CHAR] = { linkReady.complete(Unit) }
        link.enableNotify(P.RESPONSE_CHAR)
        link.enableNotify(P.LINK_CHAR)
        link.write(P.REQUEST_CHAR, P.OPEN_REQUEST)
        link.write(P.OPEN_CHAR, P.OPEN_SESSION)
        // Like myPhonak, wait for the link-status notification before the first
        // frame; requests sent earlier go unanswered.
        // myPhonak keeps its own session open on the same link while it runs in the
        // background; the aid then ignores ours (seen on the S26, 2026-10-10).
        val ready = withTimeoutOrNull(RESPONSE_TIMEOUT) { linkReady.await() }
        if (contended != null) throw AidException(BUSY)
        ready ?: throw AidException("Hearing aid did not accept the session; is myPhonak running?")
        // Same opening as myPhonak: identify plus its 0x005d handshake write.
        val (identify, _) = try {
            request(listOf(
                P.OP_IDENTIFY to ByteArray(0),
                P.OP_WRITE to P.writePayload(P.PARAM_HANDSHAKE, P.HANDSHAKE_VALUE),
            ))
        } catch (e: AidException) {
            throw if (contended != null) e else AidException("${e.message}; is myPhonak running?", e)
        }
        if (identify.first != P.STATUS_OK) {
            throw AidException("Hearing aid refused the session (status ${identify.first})")
        }
        identity = P.parseIdentity(identify.second)
        alive = true
        keepalive = scope.launch { keepAlive() }
    }

    fun close() {
        alive = false
        keepalive?.cancel()
        link.close()
    }

    private suspend fun keepAlive() {
        while (currentCoroutineContext().isActive && alive) {
            val idle = SystemClock.elapsedRealtime() - lastRequest
            if (idle < KEEPALIVE_INTERVAL) {
                delay(KEEPALIVE_INTERVAL - idle)
                continue
            }
            try {
                request(listOf(P.OP_READ to P.readPayload(P.PARAM_KEEPALIVE)))
            } catch (e: Exception) {
                Log.d(TAG, "Keepalive failed: ${e.message}")
                alive = false
            }
        }
    }

    /** Send requests in one frame; return (status, payload) for each. */
    suspend fun request(requests: List<Pair<Int, ByteArray>>): List<Pair<Int, ByteArray>> {
        if (!link.isConnected) throw AidException("Hearing aid disconnected")
        if (contended != null) throw AidException(BUSY)
        val waits = mutableListOf<Pair<Int, CompletableDeferred<ByteArray>>>()
        writeLock.withLock {
            val messages = requests.map { (opcode, payload) ->
                seq = (seq + 1) and 0xFF
                val reply = CompletableDeferred<ByteArray>()
                pending[seq] = (opcode + 1) to reply
                abandoned.remove(seq)
                waits += seq to reply
                byteArrayOf(opcode.toByte(), seq.toByte()) + payload
            }
            lastRequest = SystemClock.elapsedRealtime()
            for (chunk in P.packFrames(messages, maxValue)) link.write(P.REQUEST_CHAR, chunk)
        }
        val replies = withTimeoutOrNull(RESPONSE_TIMEOUT) { waits.map { it.second }.awaitAll() }
        if (replies == null) {
            waits.forEach { (seq, _) -> if (pending.remove(seq) != null) abandoned += seq }
            throw AidException("Hearing aid did not answer")
        }
        return replies.map { P.u8(it, 2) to it.copyOfRange(3, it.size) }
    }

    // --- Reads -------------------------------------------------------------------

    /** Everything Home Assistant shows, in the shape its webhook expects. */
    suspend fun readAll(): JSONObject {
        val aid = JSONObject()
            .put("address", link.device.address)
            .put("serial", identity.serial)
            .put("side", if (identity.side == P.SIDE_RIGHT) "right" else "left")
        for ((key, uuid) in listOf("manufacturer" to P.DIS_MANUFACTURER, "model" to P.DIS_MODEL,
                "firmware" to P.DIS_FIRMWARE)) {
            if (link.characteristic(uuid) == null) continue
            try {
                aid.put(key, String(link.read(uuid), Charsets.UTF_8).trim('\u0000', ' '))
            } catch (e: AidException) {
                Log.d(TAG, "Reading $key failed: ${e.message}")
            }
        }

        val params = listOf(P.PARAM_BATTERY, P.PARAM_PROGRAM, P.PARAM_VOLUME, P.PARAM_MUTED,
            P.PARAM_AUTO_ON, P.PARAM_TAP_CONTROL)
        val replies = request(
            params.map { P.OP_READ to P.readPayload(it) } + listOf(
                P.OP_READ_TABLE to P.tablePayload(P.TABLE_PAIRED_NAMES, 0, 4),
                P.OP_READ_TABLE to P.tablePayload(P.TABLE_PAIRED_NAMES, 4, 4),
                P.OP_READ_TABLE to P.tablePayload(P.TABLE_STREAMING, 0, 8),
            )
        )
        val values = params.zip(replies)
            .filter { (_, r) -> r.first == P.STATUS_OK && r.second.isNotEmpty() }
            .associate { (param, r) -> param to P.u8(r.second, 0) }
        values[P.PARAM_BATTERY]?.takeIf { it <= 100 }?.let { aid.put("battery", it) }
        values[P.PARAM_PROGRAM]?.let { aid.put("program", it) }
        values[P.PARAM_VOLUME]?.let { aid.put("volume", P.signed(it)) }
        values[P.PARAM_MUTED]?.let { aid.put("muted", it != 0) }
        values[P.PARAM_AUTO_ON]?.let { aid.put("auto_on", it != 0) }
        values[P.PARAM_TAP_CONTROL]?.let { aid.put("tap_control", it != 0) }

        val (names1, names2, streaming) = replies.subList(params.size, replies.size)
        val names = mutableMapOf<String, String>()
        for ((status, payload) in listOf(names1, names2)) {
            if (status == P.STATUS_OK) names += P.parsePairedNames(payload)
        }
        if (streaming.first == P.STATUS_OK) {
            val devices = JSONArray()
            for ((address, enabled) in P.parseStreaming(streaming.second)) {
                devices.put(JSONObject().put("address", address)
                    .put("name", names[address] ?: address).put("enabled", enabled))
            }
            aid.put("streaming", devices)
        }
        return aid
    }

    // --- Writes ------------------------------------------------------------------

    /** Carry out one Home Assistant command on this aid. */
    suspend fun run(command: Command) {
        when (command.action) {
            "refresh" -> Unit // the read after the commands does it
            "volume" -> {
                // Offset for this ear: 0 = fitted default, negative = quieter.
                val level = (command.value as? Number)?.toInt() ?: throw AidException("No volume given")
                write(listOf(P.writePayload(P.PARAM_SET_VOLUME, byteArrayOf(1, level.toByte(), 1))))
            }
            "program" -> {
                val program = (command.value as? Number)?.toInt() ?: throw AidException("No program given")
                val (counterReply) = request(listOf(P.OP_READ to P.readPayload(P.PARAM_CHANGE_COUNTER)))
                val counter = if (counterReply.first == P.STATUS_OK && counterReply.second.size >= 2)
                    P.le16(counterReply.second, 0) else 0
                for (batch in P.programWrites(program, counter)) write(batch)
            }
            "mute" -> {
                val audible = if (flag(command)) 0 else 1
                write(listOf(P.writePayload(P.PARAM_SET_AUDIBLE, byteArrayOf(1, audible.toByte()))))
            }
            "auto_on" -> write(P.settingsWrites(P.PARAM_AUTO_ON, byteArrayOf(bit(command))))
            // Tap control is per ear: 1 = left, 2 = right.
            "tap_control" -> write(P.settingsWrites(P.PARAM_TAP_CONTROL,
                byteArrayOf(bit(command), (identity.side + 1).toByte())))
            "streaming" -> {
                val device = command.device ?: throw AidException("No device given")
                write(P.settingsWrites(P.PARAM_STREAMING,
                    P.parseAddress(device) + byteArrayOf(0, 1, bit(command))))
            }
            else -> throw AidException("Unknown action ${command.action} (update the app?)")
        }
    }

    private fun flag(command: Command) = command.value as? Boolean ?: throw AidException("No on/off given")

    private fun bit(command: Command): Byte = if (flag(command)) 1 else 0

    private suspend fun write(payloads: List<ByteArray>) {
        for ((status, _) in request(payloads.map { P.OP_WRITE to it })) {
            if (status != P.STATUS_OK) throw AidException("Hearing aid rejected the change (status $status)")
        }
    }
}
