# Phonak for Home Assistant

[![HACS Custom](https://img.shields.io/badge/HACS-Custom-41BDF5.svg)](https://hacs.xyz/docs/faq/custom_repositories/)

Custom integration for Phonak hearing aids. One entry covers
the pair; each aid is its own device with battery, volume (per ear), program
(switches both aids), mute and auto-on (both aids), tap control, and a
Bluetooth-streaming switch per paired phone.

> Not affiliated with or endorsed by Sonova AG or Phonak. Phonak and myPhonak
> are trademarks of Sonova AG. The protocol was worked out from the Bluetooth
> traffic of the user's own hearing aids; use at your own risk.

## How it works
Home Assistant doesn't talk to the aids. The **Phonak Bridge** Android app
(source in `android/`, APK attached to each
[release](https://github.com/ijustlikeit/phonak-ha/releases)) does, from the phone that is
already paired with them:

```
hearing aids ⇄ (Bluetooth LE, Sonova protocol) ⇄ Phonak Bridge on the phone ⇄ (HTTPS webhook) ⇄ Home Assistant
```

- Every 30 s the app POSTs to the integration's webhook. The reply carries the
  changes made in Home Assistant since the last check-in; the app runs them on
  the aids, reads them again and reports straight back. So a change takes up
  to one check-in interval to arrive.
- Every 5 minutes (or when you press **Read hearing aids now**) the app reads
  both aids and sends the values.
- It works wherever the phone is, as long as the phone can reach Home Assistant
  (use your external URL, e.g. Nabu Casa, for outside your home network).

Home Assistant's own Bluetooth isn't used: the aids only advertise rarely while
the phone is connected, and Home Assistant can't resolve their private
addresses reliably.

## Install
1. In HACS, open the menu (⋮) → **Custom repositories**, add
   `https://github.com/ijustlikeit/phonak-ha` as an **Integration**, then download
   **Phonak Hearing Aids** and restart Home Assistant.
   (Manual install: copy `custom_components/phonak` to
   `/config/custom_components/phonak` and restart.)
2. Settings → Devices & services → **Add integration** → **Phonak Hearing Aids**.
   The dialog that follows shows the webhook URL (also under **Configure**
   later). If it shows only `/api/webhook/…`, put your Home Assistant URL in
   front, e.g. `https://xxxx.ui.nabu.casa/api/webhook/…`.
3. Download `PhonakBridge-*.apk` from the
   [latest release](https://github.com/ijustlikeit/phonak-ha/releases/latest) on the phone
   and open it (allow installing from your browser/files app when asked).
   Allow nearby devices (Bluetooth) and notifications.
4. In the app: paste the webhook URL, check the hearing aids picked ("R-Phonak
   hearing aid" / "L-Phonak hearing aid"), tap **Save & start**, then
   **Allow running in the background**. On Samsung (or other Android) phones
   also check Settings → Apps → Phonak Bridge → Battery → **Unrestricted**.

The app keeps a notification while it runs and restarts after a reboot.

## myPhonak
myPhonak keeps its own session with the aids open while it runs, even in the
background, and the aids then ignore the bridge ("myPhonak is using the
hearing aids; close it to let the bridge in"). Close myPhonak when you're done
with it (Recent apps → Close all, or App info → Force stop). To keep it from
staying connected in the background, set its battery use to **Restricted**.
Its links come back as soon as you open it again.

**Note:** Do not open or use the myPhonak app while using this integration;
I've had loss of connection and issues reconnecting.

## Known limitations
- Program names live in the myPhonak app, not in the aids. The integration
  knows the ids of one fitting (Automatic, Speech in loud noise, Restaurant,
  Music, TV, …); programs with other ids show as `Program 0x..` and can't be
  selected yet.
- The volume slider goes from -5 to +5 around the fitted default; myPhonak
  itself uses a smaller range.
- Changes take up to one check-in interval (30 s by default) to reach the aids.

## Tested with
- Phonak Audéo i90 Sphere by Sonova AG
- Samsung Android phone S26
- myPhonak app available on Google Play

My best guess is that it should work with any of the newer Sphere or Lumity lines of hearing aids.
I have no way of testing any other Phonak hearing aids nor any other Android phones with this integration.

## Background
I used this approach (Phonak Bridge on an Android phone) because you can use this integration at all times when you have your phone with you.
If you went the design route of having the integration handle the Bluetooth connections, you would need to be within range of your Home Assistant Bluetooth
controller. This would limit the usefulness severely.

## For developers
- `PROTOCOL.md`: what is known about the Sonova protocol.
- `tools/protocol.py`: reference implementation (framing, CRC, messages);
  `android/.../Protocol.kt` is a port, checked against it by `ProtocolTest`.
- `tools/sonova_decode.py`: decodes myPhonak traffic from an Android HCI snoop log.
- `tools/bridge_test_server.py`: stand-in webhook for testing the app without
  Home Assistant (`adb reverse tcp:8765 tcp:8765`, URL
  `http://127.0.0.1:8765/api/webhook/test`).
- Building the app: JDK 17 and the Android SDK (platform 36), then
  `cd android && ./gradlew assembleRelease testDebugUnitTest`.
