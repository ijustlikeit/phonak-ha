package app.phonakbridge

import java.util.UUID

/**
 * Sonova message protocol used by Phonak Audéo Sphere hearing aids.
 *
 * Port of tools/protocol.py (the reference; see PROTOCOL.md for the evidence).
 * Every request is one message `opcode, seq, payload`; the hearing aid answers
 * with `opcode + 1, seq, status, payload` (status 0 = ok). Messages travel in
 * CRC-protected frames over the proprietary GATT service.
 */
object Protocol {
    // --- GATT ---------------------------------------------------------------------
    val SONOVA_SERVICE: UUID = UUID.fromString("d099cd20-b2ea-11e5-bf01-0002a5d5c51b")
    val REQUEST_CHAR: UUID = UUID.fromString("2016ec20-b2eb-11e5-a307-0002a5d5c51b")
    val RESPONSE_CHAR: UUID = UUID.fromString("388706a0-b2eb-11e5-a790-0002a5d5c51b")
    val LINK_CHAR: UUID = UUID.fromString("43802b40-b2eb-11e5-b6c5-0002a5d5c51b")
    val OPEN_CHAR: UUID = UUID.fromString("2c1b1820-b2eb-11e5-8f66-0002a5d5c51b")
    val SECURE_CHAR: UUID = UUID.fromString("48facb60-c0d9-11e5-b34c-0002a5d5c51b")

    val OPEN_REQUEST = hex("000003040100") // unframed, to REQUEST_CHAR
    val OPEN_SESSION = hex("ffff") // to OPEN_CHAR
    val OPEN_REFUSED = hex("000101") // unframed reply on RESPONSE_CHAR (seen: 00 01 01 03)

    val DIS_MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val DIS_MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val DIS_FIRMWARE: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    // --- Framing ------------------------------------------------------------------
    const val FRAME_MARKER = 0x0A
    val ADDR_REQUEST = hex("0900") // requests and their responses
    val ADDR_EVENT = hex("0908") // unsolicited "parameter changed" events
    const val MAX_FRAGMENT = 145 // largest fragment the hearing aid itself sends

    // --- Opcodes ------------------------------------------------------------------
    const val OP_IDENTIFY = 0x00
    const val OP_WRITE = 0x4E
    const val OP_READ = 0x58
    const val OP_READ_TABLE = 0x62
    const val STATUS_OK = 0x00

    // --- Parameters ---------------------------------------------------------------
    const val PARAM_BATTERY = 0x00AE // u8 percent
    const val PARAM_PROGRAM = 0x002B // u8 active program id
    const val PARAM_VOLUME = 0x0033 // s8 volume offset per ear, 0 = fitted default
    const val PARAM_MUTED = 0x0045 // u8 1 = muted
    const val PARAM_HANDSHAKE = 0x005D // written by myPhonak at every session start
    const val PARAM_SET_PROGRAM = 0x00B0 // write: 00, program id
    const val PARAM_SET_VOLUME = 0x00B1 // write: 01, s8 level, 01
    const val PARAM_PROGRAM_SLOT = 0x0116 // write before an app program: 00 01 id ff*13
    const val PARAM_AUTOMATIC = 0x00A6 // empty write before switching back to Automatic
    const val PARAM_CHANGE_COUNTER = 0x006F // u16, bumped by the aid after every settings change
    const val PARAM_SET_AUDIBLE = 0x00B6 // write: 01, 1 = unmuted / 0 = muted
    const val PARAM_AUTO_ON = 0x0092 // settings: u8 0/1
    const val PARAM_TAP_CONTROL = 0x00FC // settings: u8 0/1, side (1 = left, 2 = right)
    const val PARAM_STREAMING = 0x01D4 // settings: address[6], 00 01, u8 0/1
    const val PARAM_SETTINGS_SESSION = 0x0021
    const val PARAM_SETTINGS_STAGE = 0x0023
    const val PARAM_SETTINGS_COMMIT = 0x0025
    const val PARAM_KEEPALIVE = 0x01B0 // what myPhonak polls every 2 s

    const val TABLE_PAIRED_NAMES = 0x01BE // 59-byte rows: type, addr, 00 01, addr, name[43], flag
    const val TABLE_STREAMING = 0x01D5 // 9-byte rows: addr, 00 01, enabled

    const val PROGRAM_AUTOMATIC = 0x00
    val FITTED_PROGRAMS = setOf(0x00, 0x01) // Automatic, Speech in loud noise

    val HANDSHAKE_VALUE = hex("844e14ee500028000706")
    const val SIDE_LEFT = 0
    const val SIDE_RIGHT = 1

    class ProtocolException(message: String) : Exception(message)

    /**
     * CRC-32 (poly 0x04C11DB7, init and xorout 0xFFFFFFFF, MSB-first) fed as
     * zero-padded little-endian 32-bit words, like an STM32 hardware CRC unit.
     */
    fun sonovaCrc(body: ByteArray): Int {
        val padded = body.copyOf(body.size + (-body.size).mod(4))
        var crc = -1
        for (i in padded.indices step 4) {
            crc = crc xor le32(padded, i)
            repeat(32) {
                crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return crc.inv()
    }

    /** Pack messages into one frame, split into GATT writes of <= maxValue bytes. */
    fun buildFrames(messages: List<ByteArray>, maxValue: Int): List<ByteArray> {
        var body = ADDR_REQUEST
        for (m in messages) body += byteArrayOf(m.size.toByte()) + m
        var payload = body + u32(sonovaCrc(body))
        val chunkMax = minOf(maxValue, MAX_FRAGMENT) - 3 // len, marker, frag
        if (payload.size <= chunkMax) return listOf(fragment(0, payload))
        // First fragment carries the total length (body + CRC) before the body.
        payload = u16(payload.size) + payload
        val chunks = payload.indices.step(chunkMax).map {
            payload.copyOfRange(it, minOf(it + chunkMax, payload.size))
        }
        return chunks.mapIndexed { index, chunk ->
            fragment((index shl 1) or (if (index < chunks.size - 1) 1 else 0), chunk)
        }
    }

    /**
     * Like buildFrames, but spreads messages over several frames so each frame
     * fits in one write where possible (myPhonak never fragmented its requests).
     */
    fun packFrames(messages: List<ByteArray>, maxValue: Int): List<ByteArray> {
        val max = minOf(maxValue, MAX_FRAGMENT)
        val overhead = 3 + ADDR_REQUEST.size + 4 // len, marker, frag + address + CRC
        val writes = mutableListOf<ByteArray>()
        var group = mutableListOf<ByteArray>()
        var size = overhead
        for (msg in messages) {
            if (group.isNotEmpty() && size + 1 + msg.size > max) {
                writes += buildFrames(group, max)
                group = mutableListOf()
                size = overhead
            }
            group += msg
            size += 1 + msg.size
        }
        if (group.isNotEmpty()) writes += buildFrames(group, max)
        return writes
    }

    private fun fragment(frag: Int, chunk: ByteArray) =
        byteArrayOf((chunk.size + 2).toByte(), FRAME_MARKER.toByte(), frag.toByte()) + chunk

    /** Reassembles notifications into (address, messages) frames. */
    class FrameDecoder {
        private var partial: ByteArray? = null

        fun feed(data: ByteArray): Pair<ByteArray, List<ByteArray>>? {
            if (data.size < 3 || data[1].toInt() != FRAME_MARKER || u8(data, 0) != data.size - 1) {
                throw ProtocolException("Not a frame: ${data.toHex()}")
            }
            val frag = u8(data, 2)
            var chunk = data.copyOfRange(3, data.size)
            if (frag shr 1 == 0) {
                partial = ByteArray(0)
                if (frag and 1 != 0) chunk = chunk.copyOfRange(minOf(2, chunk.size), chunk.size)
            } else if (partial == null) {
                return null // middle of a frame we didn't see start
            }
            partial = partial!! + chunk
            if (frag and 1 != 0) return null
            val payload = partial!!
            partial = null
            if (payload.size < 6) throw ProtocolException("Short frame: ${payload.toHex()}")
            val body = payload.copyOfRange(0, payload.size - 4)
            if (sonovaCrc(body) != le32(payload, payload.size - 4)) {
                throw ProtocolException("Bad CRC: ${payload.toHex()}")
            }
            return body.copyOfRange(0, 2) to splitMessages(body.copyOfRange(2, body.size))
        }
    }

    fun splitMessages(data: ByteArray): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        var i = 0
        while (i < data.size) {
            val n = u8(data, i)
            messages += data.copyOfRange(i + 1, minOf(i + 1 + n, data.size))
            i += 1 + n
        }
        return messages
    }

    // --- Message payloads (after opcode and seq) -----------------------------------

    fun readPayload(param: Int) = u16(param)

    fun writePayload(param: Int, value: ByteArray) = u16(param) + value

    fun tablePayload(table: Int, offset: Int, count: Int) =
        u16(table) + u16(offset) + byteArrayOf(count.toByte())

    /**
     * Payloads for a persistent setting change, wrapped exactly like myPhonak does.
     * `counter` goes into the opening commit: 0 for settings, the aid's change
     * counter (0x006f) for program switches. `extra` is written just before the setting.
     */
    fun settingsWrites(
        param: Int, value: ByteArray, counter: Int = 0, extra: List<ByteArray> = emptyList(),
    ): List<ByteArray> = listOf(
        writePayload(PARAM_SETTINGS_SESSION, hex("0101")),
        writePayload(PARAM_SETTINGS_STAGE, hex("00")),
        writePayload(PARAM_SETTINGS_COMMIT, hex("00") + u32(counter)),
    ) + extra + listOf(
        writePayload(param, value),
        writePayload(PARAM_SETTINGS_COMMIT, hex("0200000000")),
        writePayload(PARAM_SETTINGS_STAGE, hex("01")),
        writePayload(PARAM_SETTINGS_SESSION, hex("0001")),
    )

    /** Payloads to switch program, in the batches myPhonak sends to each aid. */
    fun programWrites(program: Int, counter: Int): List<List<ByteArray>> {
        if (program == PROGRAM_AUTOMATIC) {
            return listOf(listOf(writePayload(PARAM_AUTOMATIC, ByteArray(0)),
                writePayload(PARAM_SET_PROGRAM, ByteArray(2))))
        }
        if (program in FITTED_PROGRAMS) {
            return listOf(listOf(writePayload(PARAM_SET_PROGRAM, byteArrayOf(0, program.toByte()))))
        }
        // Programs created in the app are first put in a slot, inside a settings session.
        val slot = writePayload(PARAM_PROGRAM_SLOT,
            byteArrayOf(0, 1, program.toByte()) + ByteArray(13) { 0xFF.toByte() })
        val writes = settingsWrites(PARAM_SET_PROGRAM, byteArrayOf(0, program.toByte()),
            counter and 0xFFFF, listOf(slot))
        return listOf(writes.subList(0, 3), writes.subList(3, 5), writes.subList(5, writes.size))
    }

    fun signed(value: Int) = if (value > 127) value - 256 else value

    // --- Decoders -----------------------------------------------------------------

    data class Identity(val serial: String, val side: Int)

    /**
     * Decode the identify response payload (after status): a length byte and the
     * NUL-terminated serial; the side is the byte just before the `10 02` that
     * follows the last model-number block.
     */
    fun parseIdentity(payload: ByteArray): Identity {
        val rest = payload.copyOfRange(minOf(1, payload.size), payload.size)
        val end = rest.indexOf(0).let { if (it < 0) rest.size else it }
        val serial = String(rest, 0, end, Charsets.US_ASCII)
        var marker = -1
        for (i in 0 until payload.size - 1) {
            if (payload[i].toInt() == 0x10 && payload[i + 1].toInt() == 0x02) { marker = i; break }
        }
        val side = if (marker > 0) u8(payload, marker - 1) else SIDE_LEFT
        return Identity(serial, if (side == SIDE_RIGHT) SIDE_RIGHT else SIDE_LEFT)
    }

    /** Paired-device table rows -> {address: name}. */
    fun parsePairedNames(payload: ByteArray): Map<String, String> {
        val names = linkedMapOf<String, String>()
        var i = 0
        while (i + 59 <= payload.size) {
            val addr = payload.copyOfRange(i + 1, i + 7)
            if (addr.any { it.toInt() != 0 }) {
                val raw = payload.copyOfRange(i + 15, i + 58)
                val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
                names[formatAddress(addr)] = String(raw, 0, end, Charsets.UTF_8)
            }
            i += 59
        }
        return names
    }

    /** Streaming table rows -> {address: enabled}. */
    fun parseStreaming(payload: ByteArray): Map<String, Boolean> {
        val result = linkedMapOf<String, Boolean>()
        var i = 0
        while (i + 9 <= payload.size) {
            val addr = payload.copyOfRange(i, i + 6)
            if (addr.any { it.toInt() != 0 }) result[formatAddress(addr)] = payload[i + 8].toInt() != 0
            i += 9
        }
        return result
    }

    /** Addresses are stored least-significant byte first. */
    fun formatAddress(raw: ByteArray) =
        raw.reversed().joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    fun parseAddress(text: String) = hex(text.replace(":", "")).reversedArray()

    // --- Bytes --------------------------------------------------------------------

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)

    fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    fun u32(v: Int) = u16(v) + u16(v shr 16)
}

fun ByteArray.toHex() = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
