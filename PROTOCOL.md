# Sonova protocol notes (Phonak Audéo Sphere i90)

Reverse-engineered from myPhonak's Bluetooth traffic (Android HCI snoop logs)
on a pair of Audéo Sphere i90. Framing, CRC and opcodes are in
`tools/protocol.py` (that file is the reference; the phone app's `Protocol.kt` is a port).

## Connections (2026-10-09 capture + Phonak's own description)
- Each aid is three Bluetooth "devices" on a phone: the classic (BR/EDR) "R-Phonak
  hearing aid" for audio/calls (only the right aid here), and one LE device per
  aid (`LE_R-Phonak`, `LE_L-Phonak`) used only by myPhonak. The LE links sleep
  ("Not connected") until the app is opened. Multipoint: up to 8 paired, 2 active
  classic connections.
- myPhonak opens both LE links when it starts: right first, left ~0.5 s later
  (two `LE Enhanced Connection Complete`, peer type 2), then runs both sessions
  in parallel and sends pair-wide changes to each aid.
- The phone reaches the aids through their resolvable private addresses
  (rotating), resolved to the aids' public identity addresses (`80:28:3C:…`) with
  the IRKs from the LE bond (both are in its resolving and accept lists). Classic
  link to the right aid at the same public address.
- While the aid is connectable to new devices (e.g. after a restart) it also
  advertises from its public address: `02 01 08 04 ff 82 02 00 02 0a 00` (flags
  0x08, Sonova company id 642 with data `00`, TX power 0), name "R-Phonak hearing
  aid" in the scan response.
- The advertisements to already-bonded devices (private address) contain only
  flags (`02 01 04` / `02 01 08`): no name, no manufacturer data. Without the IRK (no LE bond) a scanner sees only rotating
  random addresses, which is why HA never saw `80:28:3C:...` advertise and why a
  name/manufacturer matcher can't discover them.

## Phone bonds (Samsung S26)
- No separate `LE_R-`/`LE_L-` entries on this phone: the aids are bonded once each,
  "R-Phonak hearing aid" (dual: classic for audio + LE keys) and
  "L-Phonak hearing aid" (LE only). Any app can open GATT to them
  with `TRANSPORT_LE`; Android encrypts with the existing bond.
- myPhonak keeps both LE links and its Sonova session open in the background.
  A second app's session open on the same link then fails (no `b3 01`, or identify
  unanswered). With myPhonak force-stopped the bridge app read both aids fine
  (firmware 1.1.11.0, model 067-1602).

## Session
- Proprietary GATT service `d099cd20-...`; no standard battery/volume services.
- Open: notify on response + link chars, write `00 00 03 04 01 00` to the request
  char, `ff ff` to the open char, wait for the link notification, then identify
  (`00 00`, seq 0) + handshake write 0x005d.
- The aid drops the session after ~3 s without a request (poll 0x01b0).
- Quick reconnects are refused with `00 01 01 03`.

## Parameters
| param | meaning |
|---|---|
| 0x00ae | battery % (u8) |
| 0x002b | active program id (u8) |
| 0x0033 | volume offset, **s8**, per ear, 0 = middle of slider |
| 0x0045 | muted (u8) |
| 0x006f | change counter (u16), bumped after every settings session |
| 0x00b1 | write `01 <s8 level> 01` to set volume on that ear only |
| 0x00b6 | write `01 00` mute / `01 01` unmute, sent to each ear |
| 0x0092 | auto-on, written inside a settings session (commit arg 0), to both ears |

## Programs (ids are per fitting; names live in the app; these are from one fitting)
| id | name | how myPhonak switches it (sent to both ears) |
|---|---|---|
| 0x00 | Automatic | write 0x00a6 (empty), write 0x00b0 `00 00` |
| 0x01 | Speech in loud noise | write 0x00b0 `00 01` |
| 0x23 | Restaurant | settings session (commit `00 <0x006f u32>`), 0x0116 `00 01 id ff*13`, 0x00b0 `00 id`, close |
| 0x2b | Music | as Restaurant |
| 0x1f | TV | as Restaurant |
| 0x20 | Calm situation backyard | as Restaurant |
| 0x07 | Bluetooth streaming & Mic | entered by the aid while the phone streams |

Volume values seen: -3, -2, -1, 0, +2 (one slider step up = +2).

## Capturing
Phone: Developer options → Enable Bluetooth HCI snoop log, toggle Bluetooth,
use myPhonak, then `adb bugreport x.zip`; the log is `FS/data/log/bt/btsnoop_hci.log`.
Decode with `py -3.14 tools/sonova_decode.py <log>` (timestamps are phone local time).
