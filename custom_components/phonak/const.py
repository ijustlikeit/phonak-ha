"""Constants for the Phonak integration."""

from datetime import timedelta

DOMAIN = "phonak"

CONF_WEBHOOK_ID = "webhook_id"

SIDE_RIGHT = "right"
SIDE_LEFT = "left"
SIDES = (SIDE_RIGHT, SIDE_LEFT)
SIDE_NAMES = {SIDE_RIGHT: "Right hearing aid", SIDE_LEFT: "Left hearing aid"}

# The phone app reports this often when it has nothing to do; it is "connected"
# while its last check-in is younger than this.
PHONE_TIMEOUT = timedelta(minutes=3)

# Commands the phone hasn't picked up by then are dropped (the aids or the
# phone are out of reach, and a stale volume change would only surprise).
COMMAND_TIMEOUT = timedelta(minutes=10)

DEFAULT_MANUFACTURER = "Sonova (Phonak)"

# Program ids seen on the user's aids (2026-10-09 capture). The names live in
# the myPhonak app; the aid only knows the ids.
PROGRAM_STREAMING = 0x07  # entered by the aid itself while audio is streamed
PROGRAM_NAMES = {
    0x00: "Automatic",
    0x01: "Speech in loud noise",
    0x23: "Restaurant",
    0x2B: "Music",
    0x1F: "TV",
    0x20: "Calm situation backyard",
    PROGRAM_STREAMING: "Bluetooth streaming & Mic",
}

# 0 is the fitted default (the middle of the myPhonak slider). myPhonak sent
# -3..+2; the full range of the aid hasn't been probed.
MIN_VOLUME = -5
MAX_VOLUME = 5

# Commands for the phone app: "action" -> the AidState attribute it changes.
ACTION_VOLUME = "volume"  # value: s8 level, this ear only
ACTION_PROGRAM = "program"  # value: program id
ACTION_MUTE = "mute"  # value: bool
ACTION_AUTO_ON = "auto_on"  # value: bool
ACTION_TAP_CONTROL = "tap_control"  # value: bool, this ear only
ACTION_STREAMING = "streaming"  # device: paired address, value: bool
ACTION_REFRESH = "refresh"  # read the aids now
ACTION_ATTRIBUTES = {
    ACTION_VOLUME: "volume",
    ACTION_PROGRAM: "program",
    ACTION_MUTE: "muted",
    ACTION_AUTO_ON: "auto_on",
    ACTION_TAP_CONTROL: "tap_control",
}
