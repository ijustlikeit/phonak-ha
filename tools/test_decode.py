"""Self-test for sonova_decode: builds a tiny btsnoop file and decodes it."""

from pathlib import Path
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import protocol as p  # noqa: E402

TS = 0x00DCDDB30F2F8000 + 1_760_000_000_000_000


def record(h4: bytes, received: bool) -> bytes:
    return struct.pack(">IIIIq", len(h4), len(h4), int(received), 0, TS) + h4


def acl(handle: int, att: bytes, pb: int = 0x2) -> bytes:
    l2 = struct.pack("<HH", len(att), 4) + att
    return b"\x02" + struct.pack("<HH", handle | pb << 12, len(l2)) + l2


def build_reply(msg: bytes, addr: bytes = p.ADDR_REQUEST) -> bytes:
    body = addr + bytes([len(msg)]) + msg
    payload = body + struct.pack("<I", p.sonova_crc(body))
    return bytes([len(payload) + 2, p.FRAME_MARKER, 0]) + payload


def main() -> None:
    conn = bytes([0x3E, 19, 0x01, 0x00]) + struct.pack("<H", 0x40) + b"\x00\x00" + bytes.fromhex("aabbccddee80") + bytes(7)
    req = p.build_frames([bytes([p.OP_READ, 5]) + p.read_payload(p.PARAM_BATTERY)], 244)[0]
    # Long request forces multi-fragment frames.
    long = p.build_frames([bytes([p.OP_WRITE, 6]) + p.write_payload(p.PARAM_SET_PROGRAM, bytes(range(60)))] * 3, 60)
    out = b"btsnoop\0" + struct.pack(">II", 1, 1002)
    out += record(b"\x04" + conn, True)
    out += record(acl(0x40, b"\x52\x10\x00" + req), False)
    for frag in long:
        out += record(acl(0x40, b"\x52\x10\x00" + frag), False)
    out += record(acl(0x40, b"\x1b\x12\x00" + build_reply(bytes([p.OP_READ + 1, 5, 0, 87]))), True)
    out += record(acl(0x40, b"\x1b\x12\x00" + build_reply(b"\x01\x2b\x00\x03", p.ADDR_EVENT)), True)
    with tempfile.TemporaryDirectory() as tmp:
        log = Path(tmp) / "t.log"
        log.write_bytes(out)
        text = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("sonova_decode.py")), str(log)],
            capture_output=True, text=True, check=True,
        ).stdout
    print(text)
    assert "80:EE:DD:CC:BB:AA -> read seq=5 battery" in text, text
    assert text.count("write seq=6 set_program") == 3, text
    assert "read-reply seq=5 status=0 57" in text, text
    assert "EVENT program=03" in text, text
    print("OK")


if __name__ == "__main__":
    main()
