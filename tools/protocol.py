"""Sonova message protocol used by Phonak Audéo Sphere hearing aids.

Pure Python (no Home Assistant imports) so it can be tested on its own.
Reverse engineered from myPhonak traffic; see PROTOCOL.md for the evidence.

Every request is one message ``opcode, seq, payload``; the hearing aid answers
with ``opcode + 1, seq, status, payload`` (status 0 = ok). Messages travel in
CRC-protected frames over the proprietary GATT service.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
import struct

# --- GATT ---------------------------------------------------------------------
SONOVA_SERVICE = "d099cd20-b2ea-11e5-bf01-0002a5d5c51b"
REQUEST_CHAR = "2016ec20-b2eb-11e5-a307-0002a5d5c51b"  # framed requests (write B)
RESPONSE_CHAR = "388706a0-b2eb-11e5-a790-0002a5d5c51b"  # framed replies/events (notify A)
LINK_CHAR = "43802b40-b2eb-11e5-b6c5-0002a5d5c51b"  # 2-byte link status (notify B)
OPEN_CHAR = "2c1b1820-b2eb-11e5-8f66-0002a5d5c51b"  # session open (write A)
SECURE_CHAR = "48facb60-c0d9-11e5-b34c-0002a5d5c51b"  # readable only when encrypted

OPEN_REQUEST = bytes.fromhex("000003040100")  # unframed, to REQUEST_CHAR
OPEN_SESSION = b"\xff\xff"  # to OPEN_CHAR
OPEN_REFUSED = b"\x00\x01\x01"  # unframed reply on RESPONSE_CHAR (seen: 00 01 01 03)

DIS_MANUFACTURER = "00002a29-0000-1000-8000-00805f9b34fb"
DIS_MODEL = "00002a24-0000-1000-8000-00805f9b34fb"
DIS_FIRMWARE = "00002a26-0000-1000-8000-00805f9b34fb"

# --- Framing --------------------------------------------------------------------
FRAME_MARKER = 0x0A
ADDR_REQUEST = b"\x09\x00"  # requests and their responses
ADDR_EVENT = b"\x09\x08"  # unsolicited "parameter changed" events
MAX_FRAGMENT = 145  # largest fragment the hearing aid itself sends

# --- Opcodes --------------------------------------------------------------------
OP_IDENTIFY = 0x00
OP_WRITE = 0x4E
OP_READ = 0x58
OP_SUBSCRIBE = 0x60
OP_READ_TABLE = 0x62
EVENT_CHANGED = 0x01

STATUS_OK = 0x00

# --- Parameters -----------------------------------------------------------------
PARAM_BATTERY = 0x00AE  # u8 percent
PARAM_PROGRAM = 0x002B  # u8 active program index
PARAM_VOLUME = 0x0033  # s8 volume offset per ear, 0 = fitted default
PARAM_MUTED = 0x0045  # u8 1 = muted
PARAM_HANDSHAKE = 0x005D  # written by myPhonak at every session start
PARAM_SET_PROGRAM = 0x00B0  # write: 00, program id
PARAM_SET_VOLUME = 0x00B1  # write: 01, s8 level, 01
PARAM_PROGRAM_SLOT = 0x0116  # write before an app program: 00 01 id ff*13
PARAM_AUTOMATIC = 0x00A6  # empty write before switching back to Automatic
PARAM_CHANGE_COUNTER = 0x006F  # u16, bumped by the aid after every settings change
PARAM_SET_AUDIBLE = 0x00B6  # write: 01, 1 = unmuted / 0 = muted
PARAM_AUTO_ON = 0x0092  # settings: u8 0/1
PARAM_TAP_CONTROL = 0x00FC  # settings: u8 0/1, side (1 = left, 2 = right)
PARAM_STREAMING = 0x01D4  # settings: address[6], 00 01, u8 0/1
PARAM_SETTINGS_SESSION = 0x0021  # wrapper around persistent settings writes
PARAM_SETTINGS_STAGE = 0x0023
PARAM_SETTINGS_COMMIT = 0x0025

TABLE_PAIRED_NAMES = 0x01BE  # 59-byte rows: type, addr, 00 01, addr, name[43], flag
TABLE_STREAMING = 0x01D5  # 9-byte rows: addr, 00 01, enabled

# Program ids seen on the user's aids (2026-10-09 capture). The names live in
# the myPhonak app; the aid only knows the ids.
PROGRAM_AUTOMATIC = 0x00
PROGRAM_STREAMING = 0x07  # entered by the aid itself while audio is streamed
FITTED_PROGRAMS = {0x00: "Automatic", 0x01: "Speech in loud noise"}
APP_PROGRAMS = {0x23: "Restaurant", 0x2B: "Music", 0x1F: "TV", 0x20: "Calm situation backyard"}
PROGRAM_NAMES = {**FITTED_PROGRAMS, **APP_PROGRAMS, PROGRAM_STREAMING: "Bluetooth streaming & Mic"}

HANDSHAKE_VALUE = bytes.fromhex("844e14ee500028000706")
SIDE_LEFT, SIDE_RIGHT = 0, 1


class ProtocolError(Exception):
    """The hearing aid sent something we can't parse, or refused a request."""


def sonova_crc(body: bytes) -> int:
    """CRC-32 (poly 0x04C11DB7, init and xorout 0xFFFFFFFF, MSB-first) fed as
    zero-padded little-endian 32-bit words, like an STM32 hardware CRC unit."""
    body += b"\0" * (-len(body) % 4)
    crc = 0xFFFFFFFF
    for (word,) in struct.iter_unpack("<I", body):
        crc ^= word
        for _ in range(32):
            crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if crc & 0x80000000 else (crc << 1) & 0xFFFFFFFF
    return crc ^ 0xFFFFFFFF


def build_frames(messages: Iterable[bytes], max_value: int) -> list[bytes]:
    """Pack messages into one frame, split into GATT writes of <= max_value bytes."""
    body = ADDR_REQUEST + b"".join(bytes([len(m)]) + m for m in messages)
    payload = body + struct.pack("<I", sonova_crc(body))
    chunk_max = min(max_value, MAX_FRAGMENT) - 3  # len, marker, frag
    if len(payload) <= chunk_max:
        return [_fragment(0, payload)]
    # First fragment carries the total length (body + CRC) before the body.
    payload = struct.pack("<H", len(payload)) + payload
    chunks = [payload[i : i + chunk_max] for i in range(0, len(payload), chunk_max)]
    return [
        _fragment(index << 1 | (index < len(chunks) - 1), chunk)
        for index, chunk in enumerate(chunks)
    ]


def pack_frames(messages: list[bytes], max_value: int) -> list[bytes]:
    """Like build_frames, but spreads messages over several frames so each
    frame fits in one write where possible. myPhonak never fragmented its
    requests (it used a large MTU), so fragmenting is the last resort."""
    max_value = min(max_value, MAX_FRAGMENT)
    overhead = 3 + len(ADDR_REQUEST) + 4  # len, marker, frag + address + CRC
    writes: list[bytes] = []
    group: list[bytes] = []
    size = overhead
    for msg in messages:
        if group and size + 1 + len(msg) > max_value:
            writes += build_frames(group, max_value)
            group, size = [], overhead
        group.append(msg)
        size += 1 + len(msg)
    if group:
        writes += build_frames(group, max_value)
    return writes


def _fragment(frag: int, chunk: bytes) -> bytes:
    return bytes([len(chunk) + 2, FRAME_MARKER, frag]) + chunk


class FrameDecoder:
    """Reassembles notifications into (address, [messages]) frames."""

    def __init__(self) -> None:
        self._partial: bytearray | None = None

    def feed(self, data: bytes) -> tuple[bytes, list[bytes]] | None:
        if len(data) < 3 or data[1] != FRAME_MARKER or data[0] != len(data) - 1:
            raise ProtocolError(f"Not a frame: {data.hex(' ')}")
        frag, chunk = data[2], data[3:]
        if frag >> 1 == 0:
            self._partial = bytearray()
            if frag & 1:
                chunk = chunk[2:]  # skip total length
        elif self._partial is None:
            return None  # middle of a frame we didn't see start
        self._partial += chunk
        if frag & 1:
            return None
        payload, self._partial = bytes(self._partial), None
        body, crc = payload[:-4], payload[-4:]
        if len(crc) != 4 or sonova_crc(body) != struct.unpack("<I", crc)[0]:
            raise ProtocolError(f"Bad CRC: {payload.hex(' ')}")
        return body[:2], split_messages(body[2:])


def split_messages(data: bytes) -> list[bytes]:
    messages, i = [], 0
    while i < len(data):
        n = data[i]
        messages.append(data[i + 1 : i + 1 + n])
        i += 1 + n
    return messages


# --- Message payloads (after opcode and seq) -------------------------------------


def read_payload(param: int) -> bytes:
    return struct.pack("<H", param)


def write_payload(param: int, value: bytes) -> bytes:
    return struct.pack("<H", param) + value


def table_payload(table: int, offset: int, count: int) -> bytes:
    return struct.pack("<HHB", table, offset, count)


def subscribe_payload(params: Iterable[int]) -> bytes:
    params = list(params)
    return b"\x5e\x00\x00\x00" + bytes([len(params)]) + b"".join(struct.pack("<H", p) for p in params)


def settings_writes(
    param: int, value: bytes, counter: int = 0, extra: Iterable[bytes] = ()
) -> list[bytes]:
    """Payloads for a persistent setting change, wrapped exactly like myPhonak does.

    ``counter`` goes into the opening commit: myPhonak sends 0 for settings
    and the aid's change counter (0x006f) for program switches. ``extra``
    payloads are written just before the setting itself.
    """
    return [
        write_payload(PARAM_SETTINGS_SESSION, b"\x01\x01"),
        write_payload(PARAM_SETTINGS_STAGE, b"\x00"),
        write_payload(PARAM_SETTINGS_COMMIT, b"\x00" + struct.pack("<I", counter)),
        *extra,
        write_payload(param, value),
        write_payload(PARAM_SETTINGS_COMMIT, b"\x02\x00\x00\x00\x00"),
        write_payload(PARAM_SETTINGS_STAGE, b"\x01"),
        write_payload(PARAM_SETTINGS_SESSION, b"\x00\x01"),
    ]


def program_writes(program: int, counter: int) -> list[list[bytes]]:
    """Payloads to switch program, in the batches myPhonak sends to each aid
    (it waits for the replies to one batch before sending the next)."""
    if program == PROGRAM_AUTOMATIC:
        return [[write_payload(PARAM_AUTOMATIC, b""), write_payload(PARAM_SET_PROGRAM, bytes(2))]]
    if program in FITTED_PROGRAMS:
        return [[write_payload(PARAM_SET_PROGRAM, bytes([0, program]))]]
    # Programs created in the app are first put in a slot, inside a settings session.
    slot = write_payload(PARAM_PROGRAM_SLOT, bytes([0, 1, program]) + bytes([0xFF]) * 13)
    writes = settings_writes(PARAM_SET_PROGRAM, bytes([0, program]), counter & 0xFFFF, [slot])
    return [writes[:3], writes[3:5], writes[5:]]


def signed(value: int) -> int:
    return value - 256 if value > 127 else value


# --- Decoders -------------------------------------------------------------------


@dataclass(frozen=True)
class Identity:
    serial: str
    side: int  # SIDE_LEFT or SIDE_RIGHT


def parse_identity(payload: bytes) -> Identity:
    """Decode the identify response payload (after status).

    Starts with a length byte and the NUL-terminated serial; the side is the
    byte just before the ``10 02`` that follows the last model-number block.
    """
    serial = payload[1:].split(b"\0", 1)[0].decode("ascii", errors="replace")
    marker = payload.find(b"\x10\x02")
    side = payload[marker - 1] if marker > 0 else SIDE_LEFT
    return Identity(serial=serial, side=SIDE_RIGHT if side == SIDE_RIGHT else SIDE_LEFT)


def parse_events(messages: list[bytes]) -> dict[int, bytes]:
    """Decode 'parameter changed' events. Only fixed-size params we know are kept."""
    sizes = {PARAM_BATTERY: 1, PARAM_PROGRAM: 1, PARAM_VOLUME: 1, PARAM_MUTED: 1,
             0x0037: 1, 0x003D: 1, 0x006F: 2, 0x0070: 2, 0x002D: 16}
    changes: dict[int, bytes] = {}
    for msg in messages:
        if not msg or msg[0] != EVENT_CHANGED:
            continue
        i = 1
        while i + 2 <= len(msg):
            param = struct.unpack_from("<H", msg, i)[0]
            size = sizes.get(param)
            if size is None:
                break  # unknown size: can't find the next parameter
            changes[param] = msg[i + 2 : i + 2 + size]
            i += 2 + size
    return changes


def parse_paired_names(payload: bytes) -> dict[str, str]:
    """Paired-device table rows -> {address: name}. Addresses as AA:BB:..."""
    names: dict[str, str] = {}
    for i in range(0, len(payload) - 58, 59):
        row = payload[i : i + 59]
        addr = row[1:7]
        if any(addr):
            names[format_address(addr)] = row[15:58].split(b"\0", 1)[0].decode("utf-8", errors="replace")
    return names


def parse_streaming(payload: bytes) -> dict[str, bool]:
    """Streaming table rows -> {address: enabled}."""
    result: dict[str, bool] = {}
    for i in range(0, len(payload) - 8, 9):
        row = payload[i : i + 9]
        if any(row[:6]):
            result[format_address(row[:6])] = bool(row[8])
    return result


def format_address(raw: bytes) -> str:
    """Addresses are stored least-significant byte first (a PC paired from
    Windows shows up as its adapter MAC reversed). Devices paired earlier can
    appear under a private address instead."""
    return ":".join(f"{b:02X}" for b in reversed(raw))


def parse_address(text: str) -> bytes:
    return bytes(reversed(bytes.fromhex(text.replace(":", ""))))
