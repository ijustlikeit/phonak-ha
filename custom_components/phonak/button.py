"""Button asking the phone app to read the hearing aids now."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .const import ACTION_REFRESH, SIDES
from .coordinator import Command, PhonakConfigEntry
from .entity import PhonakBridgeEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhonakConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    async_add_entities([PhonakRefresh(entry.runtime_data, "refresh")])


class PhonakRefresh(PhonakBridgeEntity, ButtonEntity):
    """The phone reads both aids at its next check-in instead of on its schedule."""

    async def async_press(self) -> None:
        self.coordinator.queue(Command(ACTION_REFRESH, list(SIDES)))
