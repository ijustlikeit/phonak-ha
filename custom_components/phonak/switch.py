"""Switches: mute, auto-on, tap control and per-device Bluetooth streaming."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from homeassistant.components.switch import SwitchEntity, SwitchEntityDescription
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .const import (
    ACTION_ATTRIBUTES,
    ACTION_AUTO_ON,
    ACTION_MUTE,
    ACTION_STREAMING,
    ACTION_TAP_CONTROL,
    SIDES,
)
from .coordinator import AidState, PhonakConfigEntry, PhonakCoordinator
from .entity import PhonakEntity


@dataclass(frozen=True, kw_only=True)
class PhonakSwitchDescription(SwitchEntityDescription):
    """The key is the command action; ACTION_ATTRIBUTES gives the AidState field."""

    # myPhonak changes these on both aids at once, so the switch does too.
    both: bool = False


DESCRIPTIONS: tuple[PhonakSwitchDescription, ...] = (
    PhonakSwitchDescription(key=ACTION_MUTE, both=True),
    PhonakSwitchDescription(key=ACTION_AUTO_ON, entity_category=EntityCategory.CONFIG, both=True),
    PhonakSwitchDescription(key=ACTION_TAP_CONTROL, entity_category=EntityCategory.CONFIG),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhonakConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    coordinator = entry.runtime_data
    async_add_entities(
        PhonakSwitch(coordinator, side, desc) for side in SIDES for desc in DESCRIPTIONS
    )

    # Paired devices are only known once the phone has read the aids.
    known: set[tuple[str, str]] = set()

    @callback
    def add_streaming_switches() -> None:
        new = [
            (side, device)
            for side, aid in coordinator.data.items()
            for device in aid.streaming
            if (side, device) not in known
        ]
        known.update(new)
        if new:
            async_add_entities(
                PhonakStreamingSwitch(coordinator, side, device) for side, device in new
            )

    add_streaming_switches()
    entry.async_on_unload(coordinator.async_add_listener(add_streaming_switches))


class PhonakSwitch(PhonakEntity, SwitchEntity):
    """A boolean hearing aid setting."""

    entity_description: PhonakSwitchDescription

    def __init__(
        self, coordinator: PhonakCoordinator, side: str, description: PhonakSwitchDescription
    ) -> None:
        super().__init__(coordinator, side, description.key)
        self.entity_description = description
        self._attribute = ACTION_ATTRIBUTES[description.key]

    @property
    def is_on(self) -> bool | None:
        data = self.aid
        return getattr(data, self._attribute) if data else None

    def _turn(self, on: bool) -> None:
        def update(data: AidState) -> None:
            setattr(data, self._attribute, on)

        self.send(self.entity_description.key, on, both=self.entity_description.both, apply=update)

    async def async_turn_on(self, **kwargs: Any) -> None:
        self._turn(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        self._turn(False)


class PhonakStreamingSwitch(PhonakEntity, SwitchEntity):
    """Whether one paired phone/PC may stream audio to the hearing aid."""

    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PhonakCoordinator, side: str, device: str) -> None:
        super().__init__(coordinator, side, "streaming")
        self._device = device
        self._attr_unique_id = f"{coordinator.config_entry.entry_id}_{side}_streaming_{device}"
        self._attr_translation_placeholders = {
            "device": coordinator.data[side].streaming[device].name
        }

    @property
    def available(self) -> bool:
        data = self.aid
        return super().available and data is not None and self._device in data.streaming

    @property
    def is_on(self) -> bool | None:
        data = self.aid
        device = data.streaming.get(self._device) if data else None
        return device.enabled if device else None

    def _turn(self, on: bool) -> None:
        def update(data: AidState) -> None:
            if device := data.streaming.get(self._device):
                device.enabled = on

        self.send(ACTION_STREAMING, on, device=self._device, apply=update)

    async def async_turn_on(self, **kwargs: Any) -> None:
        self._turn(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        self._turn(False)
