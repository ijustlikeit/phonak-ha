"""Decode Sonova (Phonak) traffic from an Android btsnoop_hci.log.

Usage:
    python tools/sonova_decode.py btsnoop_hci.log [--utc-offset HOURS] [--all]

Prints every Sonova message (requests, responses, events) with time,
connection (hearing aid address) and direction. Uses the integration's own
protocol module for framing and CRC, so a decode here means the integration
would understand the same bytes.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import protocol as p  # noqa: E402

# btsnoop timestamps are microseconds since 0000-01-01; datetime starts at year 1.
EPOCH_DELTA_US = 0x00DCDDB30F2F8000

OPCODES = {
    p.OP_IDENTIFY: "identify",
    p.OP_WRITE: "write",
    p.OP_READ: "read",
    p.OP_SUBSCRIBE: "subscribe",
    p.OP_READ_TABLE: "table",
}
NAMES = {
    v: k[6:].lower()
    for k, v in vars(p).items()
    if k.startswith("PARAM_") and isinstance(v, int)
}
NAMES.update({v: k[6:].lower() for k, v in vars(p).items() if k.startswith("TABLE_")})


def read_btsnoop(path: Path):
    """Yield (timestamp_us, sent_by_host, h4_packet)."""
    data = path.read_bytes()
    if data[:8] != b"btsnoop\0":
        raise SystemExit(f"{path} is not a btsnoop file")
    i = 16
    while i + 24 <= len(data):
        _orig, incl, flags, _drops, ts = struct.unpack_from(">IIIIq", data, i)
        i += 24
        yield ts, not flags & 1, data[i : i + incl]
        i += incl


def att_packets(path: Path):
    """Yield (ts, conn_handle, address, sent, att_pdu) with L2CAP reassembly."""
    addresses: dict[int, str] = {}
    partial: dict[tuple[int, bool], bytearray] = {}
    for ts, sent, pkt in read_btsnoop(path):
        if not pkt:
            continue
        kind, body = pkt[0], pkt[1:]
        if kind == 0x04 and len(body) >= 2 and body[0] == 0x3E:
            sub = body[2]
            # LE (enhanced) connection complete: status, handle, role, addr type, addr
            if sub in (0x01, 0x0A) and body[3] == 0:
                handle = struct.unpack_from("<H", body, 4)[0]
                addresses[handle] = ":".join(f"{b:02X}" for b in reversed(body[8:14]))
            continue
        if kind != 0x02 or len(body) < 4:
            continue
        hdr, length = struct.unpack_from("<HH", body)
        handle, pb = hdr & 0x0FFF, (hdr >> 12) & 0x3
        frag = body[4 : 4 + length]
        key = (handle, sent)
        if pb == 0x1:  # continuation
            if key not in partial:
                continue
            partial[key] += frag
        else:
            partial[key] = bytearray(frag)
        buf = partial[key]
        if len(buf) < 4:
            continue
        l2len, cid = struct.unpack_from("<HH", buf)
        if len(buf) - 4 < l2len:
            continue
        del partial[key]
        if cid == 0x0004:
            yield ts, handle, addresses.get(handle, f"conn{handle:#x}"), sent, bytes(buf[4 : 4 + l2len])


def describe(msg: bytes, from_aid: bool, address: bytes) -> str:
    if address == p.ADDR_EVENT:
        changes = p.parse_events([msg])
        if changes:
            return "EVENT " + ", ".join(f"{NAMES.get(k, f'{k:#06x}')}={v.hex(' ')}" for k, v in changes.items())
        return f"EVENT raw {msg.hex(' ')}"
    if len(msg) < 2:
        return f"raw {msg.hex(' ')}"
    op, seq, rest = msg[0], msg[1], msg[2:]
    if from_aid:
        status, payload = (rest[0], rest[1:]) if rest else (None, b"")
        name = OPCODES.get(op - 1, f"op{op - 1:#04x}")
        return f"  <- {name}-reply seq={seq} status={status} {payload.hex(' ')}"
    name = OPCODES.get(op, f"op{op:#04x}")
    if op in (p.OP_READ, p.OP_WRITE, p.OP_READ_TABLE) and len(rest) >= 2:
        param = struct.unpack_from("<H", rest)[0]
        label = NAMES.get(param, f"{param:#06x}")
        return f"-> {name} seq={seq} {label} {rest[2:].hex(' ')}"
    return f"-> {name} seq={seq} {rest.hex(' ')}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("log", type=Path)
    ap.add_argument("--utc-offset", type=float, default=0, help="hours to add to timestamps")
    ap.add_argument("--all", action="store_true", help="also print non-Sonova ATT writes/notifications")
    ap.add_argument("--hide", default="0x01b0", help="comma separated params to hide (keepalive)")
    args = ap.parse_args()
    hide = {int(x, 0) for x in args.hide.split(",") if x}

    decoders: dict[tuple[int, bool], p.FrameDecoder] = defaultdict(p.FrameDecoder)
    pending_hidden: set[tuple[int, int]] = set()
    for ts, handle, addr, sent, pdu in att_packets(args.log):
        op = pdu[0]
        if op in (0x12, 0x52):  # write request / command
            value, from_aid = pdu[3:], False
        elif op in (0x1B, 0x1D):  # notification / indication
            value, from_aid = pdu[3:], True
        else:
            continue
        when = datetime.fromtimestamp((ts - EPOCH_DELTA_US) / 1e6, timezone.utc) + timedelta(hours=args.utc_offset)
        stamp = when.strftime("%H:%M:%S.%f")[:-3]
        att_handle = struct.unpack_from("<H", pdu, 1)[0]
        try:
            frame = decoders[(handle, from_aid)].feed(value)
        except p.ProtocolError:
            if args.all:
                print(f"{stamp} {addr} h{att_handle:#06x} {'<-' if from_aid else '->'} {value.hex(' ')}")
            continue
        if frame is None:
            continue
        frame_addr, messages = frame
        for msg in messages:
            if len(msg) >= 4 and not from_aid and msg[0] == p.OP_READ and struct.unpack_from("<H", msg, 2)[0] in hide:
                pending_hidden.add((handle, msg[1]))
                continue
            if from_aid and len(msg) >= 2 and (handle, msg[1]) in pending_hidden and frame_addr == p.ADDR_REQUEST:
                pending_hidden.discard((handle, msg[1]))
                continue
            print(f"{stamp} {addr} {describe(msg, from_aid, frame_addr)}")


if __name__ == "__main__":
    main()
