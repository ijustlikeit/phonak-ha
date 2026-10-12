"""Checks protocol.py against what myPhonak sent in the 2026-10-09 capture."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import protocol as p  # noqa: E402


def hexes(batches):
    return [[m.hex(" ") for m in batch] for batch in batches]


def main() -> None:
    # Restaurant at 16:09:42, change counter 1 (read as 01 00 at session start).
    assert hexes(p.program_writes(0x23, 1)) == [
        ["21 00 01 01", "23 00 00", "25 00 00 01 00 00 00"],
        ["16 01 00 01 23" + " ff" * 13, "b0 00 00 23"],
        ["25 00 02 00 00 00 00", "23 00 01", "21 00 00 01"],
    ], hexes(p.program_writes(0x23, 1))
    # Speech in loud noise (16:09:20) and Automatic (16:10:57).
    assert hexes(p.program_writes(0x01, 9)) == [["b0 00 00 01"]]
    assert hexes(p.program_writes(0x00, 9)) == [["a6 00", "b0 00 00 00"]]
    # Auto-on off (16:13:59) still uses commit 0.
    assert [m.hex(" ") for m in p.settings_writes(p.PARAM_AUTO_ON, b"\x00")][2:4] == [
        "25 00 00 00 00 00 00", "92 00 00",
    ]
    # Volume: fd = -3 sent for the left aid, 02 = one step up.
    assert p.signed(0xFD) == -3 and p.signed(0x02) == 2 and p.signed(0xFF) == -1
    assert p.write_payload(p.PARAM_SET_VOLUME, bytes([1, -3 & 0xFF, 1])).hex(" ") == "b1 00 01 fd 01"
    print("OK")


if __name__ == "__main__":
    main()
