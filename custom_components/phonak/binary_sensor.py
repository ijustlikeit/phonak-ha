"""Whether the phone app is checking in."""

from __future__ import annotations

from typing import Any

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .coordinator import PhonakConfigEntry
from .entity import PhonakBridgeEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhonakConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    async_add_entities([PhonakPhoneConnected(entry.runtime_data, "phone_connected")])


class PhonakPhoneConnected(PhonakBridgeEntity, BinarySensorEntity):
    """On while the Phonak Bridge app has checked in recently."""

    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    @property
    def is_on(self) -> bool:
        return self.coordinator.phone_connected

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {
            "last_checkin": self.coordinator.last_checkin,
            "app_version": self.coordinator.app_version,
            "pending_changes": self.coordinator.pending,
        }
