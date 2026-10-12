"""Volume control for Phonak hearing aids, one slider per ear."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .const import ACTION_VOLUME, MAX_VOLUME, MIN_VOLUME, SIDES
from .coordinator import AidState, PhonakConfigEntry
from .entity import PhonakEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhonakConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    coordinator = entry.runtime_data
    async_add_entities(PhonakVolume(coordinator, side, "volume") for side in SIDES)


class PhonakVolume(PhonakEntity, NumberEntity):
    """Volume relative to the fitted default. Each ear is set separately."""

    _attr_native_min_value = MIN_VOLUME
    _attr_native_max_value = MAX_VOLUME
    _attr_native_step = 1
    _attr_mode = NumberMode.SLIDER

    @property
    def native_value(self) -> int | None:
        data = self.aid
        return data.volume if data else None

    async def async_set_native_value(self, value: float) -> None:
        level = max(MIN_VOLUME, min(MAX_VOLUME, round(value)))

        def update(data: AidState) -> None:
            data.volume = level

        self.send(ACTION_VOLUME, level, apply=update)
