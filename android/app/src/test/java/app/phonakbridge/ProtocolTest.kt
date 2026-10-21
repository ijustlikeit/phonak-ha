package app.phonakbridge

import app.phonakbridge.Protocol as P
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values come from tools/protocol.py and the 2026-10-09 myPhonak capture. */
class ProtocolTest {
    private fun hexes(frames: List<ByteArray>) = frames.map { it.toHex().replace(" ", "") }

    private fun reads(n: Int) = (0 until n).map { byteArrayOf(P.OP_READ.toByte(), it.toByte()) + P.readPayload(0xAE) }

    @Test
    fun crc() {
        assertEquals(0x00b1f40f, P.sonovaCrc(P.hex("0900020000")))
    }

    @Test
    fun singleFrame() {
        assertEquals(listOf("0b0a0009000200000ff4b100"), hexes(P.buildFrames(listOf(P.hex("0000")), 100)))
    }

    @Test
    fun fragmentedFrame() {
        val frames = P.buildFrames(reads(40), 60)
        assertEquals(4, frames.size)
        assertEquals("3b0a01ce000900045800ae00045801ae00045802ae00045803ae00045804ae00045805ae00045806ae00045807ae00045808ae00045809ae0004580a", hexes(frames)[0])
        assertEquals("270a0621ae00045822ae00045823ae00045824ae00045825ae00045826ae00045827ae000ea507a0", hexes(frames)[3])
        // and back
        val decoder = P.FrameDecoder()
        val decoded = frames.map { decoder.feed(it) }
        assertEquals(listOf(null, null, null), decoded.take(3))
        val (address, messages) = decoded[3]!!
        assertEquals("09 00", address.toHex())
        assertEquals(reads(40).map { it.toHex() }, messages.map { it.toHex() })
    }

    @Test
    fun packFrames() {
        assertEquals(
            listOf(
                "3a0a000900045800ae00045801ae00045802ae00045803ae00045804ae00045805ae00045806ae00045807ae00045808ae00045809ae006960533c",
                "120a00090004580aae0004580bae00a6989554",
            ),
            hexes(P.packFrames(reads(12), 60)),
        )
    }

    @Test
    fun programWrites() {
        // Restaurant at 16:09:42, change counter 1.
        assertEquals(
            listOf(
                listOf("21 00 01 01", "23 00 00", "25 00 00 01 00 00 00"),
                listOf("16 01 00 01 23" + " ff".repeat(13), "b0 00 00 23"),
                listOf("25 00 02 00 00 00 00", "23 00 01", "21 00 00 01"),
            ),
            P.programWrites(0x23, 1).map { batch -> batch.map { it.toHex() } },
        )
        assertEquals(listOf(listOf("b0 00 00 01")), P.programWrites(0x01, 9).map { b -> b.map { it.toHex() } })
        assertEquals(listOf(listOf("a6 00", "b0 00 00 00")), P.programWrites(0x00, 9).map { b -> b.map { it.toHex() } })
    }

    @Test
    fun settingsAndVolume() {
        assertEquals(
            listOf("25 00 00 00 00 00 00", "92 00 00"),
            P.settingsWrites(P.PARAM_AUTO_ON, byteArrayOf(0)).subList(2, 4).map { it.toHex() },
        )
        assertEquals(-3, P.signed(0xFD))
        assertEquals("b1 00 01 fd 01", P.writePayload(P.PARAM_SET_VOLUME, byteArrayOf(1, -3, 1)).toHex())
    }

    @Test
    fun volumeIsClampedToTheVerifiedRange() {
        assertEquals("b1 00 01 fd 01", P.volumePayload(-3).toHex())
        assertEquals("b1 00 01 02 01", P.volumePayload(2).toHex())
        assertEquals("b1 00 01 02 01", P.volumePayload(5).toHex()) // never louder than +2
        assertEquals("b1 00 01 fd 01", P.volumePayload(-128).toHex())
        assertEquals("b1 00 01 02 01", P.volumePayload(127).toHex())
    }

    @Test
    fun addresses() {
        assertEquals("80:28:3C:12:34:56", P.formatAddress(P.hex("5634123c2880")))
        assertEquals("56 34 12 3c 28 80", P.parseAddress("80:28:3C:12:34:56").toHex())
        val row = P.hex("5634123c2880") + P.hex("0001") + byteArrayOf(1)
        assertEquals(mapOf("80:28:3C:12:34:56" to true), P.parseStreaming(row + ByteArray(9)))
    }
}
