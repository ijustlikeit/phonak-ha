"""State of a pair of Phonak hearing aids, as reported by the phone app.

Home Assistant never talks to the aids itself. The Phonak Bridge app on the
phone (which is LE-bonded with both aids, like myPhonak) reads them and POSTs
the values to this integration's webhook. The reply to every POST carries the
commands queued since the previous one, so a change made here reaches the aids
the next time the phone checks in (every ~30 s), and its result comes back
with the check-in after that.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
import logging
from typing import Any
import uuid

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator
from homeassistant.util import dt as dt_util

from .const import (
    ACTION_ATTRIBUTES,
    COMMAND_TIMEOUT,
    DEFAULT_MANUFACTURER,
    DOMAIN,
    PHONE_TIMEOUT,
    SIDES,
)

_LOGGER = logging.getLogger(__name__)

type PhonakConfigEntry = ConfigEntry[PhonakCoordinator]


@dataclass
class StreamingDevice:
    """A phone/PC paired with the hearing aid, with its streaming permission."""

    name: str
    enabled: bool


@dataclass
class AidState:
    """Last values the phone read from one hearing aid."""

    address: str | None = None
    serial: str | None = None
    manufacturer: str | None = None
    model: str | None = None
    firmware: str | None = None
    battery: int | None = None
    program: int | None = None  # program id, see const.PROGRAM_NAMES
    volume: int | None = None
    muted: bool | None = None
    auto_on: bool | None = None
    tap_control: bool | None = None
    streaming: dict[str, StreamingDevice] = field(default_factory=dict)
    updated: datetime | None = None  # last successful read
    error: str | None = None  # why the last read failed, if it did


@dataclass
class Command:
    """A change waiting for the phone to pick it up."""

    action: str
    sides: list[str]
    value: Any = None
    device: str | None = None
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    queued: datetime = field(default_factory=dt_util.utcnow)

    def as_json(self) -> dict[str, Any]:
        data = {"id": self.id, "action": self.action, "sides": self.sides}
        if self.value is not None:
            data["value"] = self.value
        if self.device is not None:
            data["device"] = self.device
        return data

    def replaces(self, other: Command) -> bool:
        """Dragging a slider queues many changes; only the last one matters."""
        return (self.action, self.sides, self.device) == (other.action, other.sides, other.device)


class PhonakCoordinator(DataUpdateCoordinator[dict[str, AidState]]):
    """Holds the reported state per side ("right"/"left") and the command queue."""

    config_entry: PhonakConfigEntry

    def __init__(self, hass: HomeAssistant, entry: PhonakConfigEntry) -> None:
        super().__init__(hass, _LOGGER, config_entry=entry, name=f"{DOMAIN} {entry.title}")
        self.data = {}
        self.last_checkin: datetime | None = None
        self.app_version: str | None = None
        self._queue: list[Command] = []
        self._sent: dict[str, Command] = {}  # handed to the phone, result pending

    @property
    def phone_connected(self) -> bool:
        return self.last_checkin is not None and dt_util.utcnow() - self.last_checkin < PHONE_TIMEOUT

    @property
    def pending(self) -> int:
        return len(self._queue) + len(self._sent)

    @callback
    def queue(self, command: Command, apply: Callable[[AidState], None] | None = None) -> None:
        """Queue a command and show its effect right away (the next report confirms it)."""
        self._queue = [c for c in self._queue if not command.replaces(c)]
        self._queue.append(command)
        if apply is not None:
            for side in command.sides:
                if aid := self.data.get(side):
                    apply(aid)
        self.async_set_updated_data(self.data)

    @callback
    def handle_report(self, report: dict[str, Any]) -> list[dict[str, Any]]:
        """Take a check-in from the phone; return the commands for it to run."""
        now = dt_util.utcnow()
        self.last_checkin = now
        if (version := report.get("app_version")) and version != self.app_version:
            self.app_version = str(version)
            dr.async_get(self.hass).async_get_or_create(
                config_entry_id=self.config_entry.entry_id,
                identifiers={(DOMAIN, f"{self.config_entry.entry_id}_bridge")},
                sw_version=self.app_version,
            )

        for result in report.get("results") or []:
            command = self._sent.pop(str(result.get("id")), None)
            if command is None:
                continue
            if result.get("ok"):
                _LOGGER.debug("Phone ran %s %s", command.action, command.sides)
            else:
                _LOGGER.warning(
                    "Hearing aid change %s %s failed: %s",
                    command.action, command.sides, result.get("error") or "unknown error",
                )

        for side, values in (report.get("aids") or {}).items():
            if side in SIDES and isinstance(values, dict):
                self._update_aid(side, values, now)

        # Results that never came back (phone restarted) and stale commands are dropped.
        for command in [*self._sent.values(), *self._queue]:
            if now - command.queued > COMMAND_TIMEOUT:
                _LOGGER.warning("Dropping hearing aid change %s %s: phone never ran it",
                                command.action, command.sides)
        self._sent = {k: c for k, c in self._sent.items() if now - c.queued <= COMMAND_TIMEOUT}
        commands = [c for c in self._queue if now - c.queued <= COMMAND_TIMEOUT]
        self._queue = []
        self._sent.update((c.id, c) for c in commands)

        self.async_set_updated_data(self.data)
        return [c.as_json() for c in commands]

    def _update_aid(self, side: str, values: dict[str, Any], now: datetime) -> None:
        aid = self.data.setdefault(side, AidState())
        if values.get("error"):
            # Couldn't reach it this time: keep showing the last values.
            aid.error = str(values["error"])
            return
        aid.error = None
        aid.updated = now
        # The phone read these before it got the changes still queued here;
        # keep showing the queued values until a report after the change.
        values = {
            key: value
            for key, value in values.items()
            if not any(
                ACTION_ATTRIBUTES.get(c.action) == key and side in c.sides for c in self._queue
            )
        }
        for key in ("address", "serial", "manufacturer", "model", "firmware"):
            if values.get(key):
                setattr(aid, key, str(values[key]))
        for key in ("battery", "program", "volume"):
            if isinstance(values.get(key), int):
                setattr(aid, key, values[key])
        if aid.battery is not None and not 0 <= aid.battery <= 100:
            aid.battery = None  # app 0.1.1 could mix up replies with myPhonak's (122 %)
        for key in ("muted", "auto_on", "tap_control"):
            if isinstance(values.get(key), bool):
                setattr(aid, key, values[key])
        if isinstance(values.get("streaming"), list):
            aid.streaming = {
                str(d["address"]): StreamingDevice(
                    name=str(d.get("name") or d["address"]), enabled=bool(d.get("enabled"))
                )
                for d in values["streaming"]
                if isinstance(d, dict) and d.get("address")
            }
        self._update_device(side, aid)

    def _update_device(self, side: str, aid: AidState) -> None:
        """Fill in model, serial and firmware once they're known."""
        dr.async_get(self.hass).async_get_or_create(
            config_entry_id=self.config_entry.entry_id,
            identifiers={(DOMAIN, f"{self.config_entry.entry_id}_{side}")},
            manufacturer=aid.manufacturer or DEFAULT_MANUFACTURER,
            model=aid.model,
            serial_number=aid.serial,
            sw_version=aid.firmware,
        )
