"""Sensors: battery and last successful read of each hearing aid."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
    SensorStateClass,
)
from homeassistant.const import PERCENTAGE, EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .const import SIDES
from .coordinator import AidState, PhonakConfigEntry, PhonakCoordinator
from .entity import PhonakEntity


@dataclass(frozen=True, kw_only=True)
class PhonakSensorDescription(SensorEntityDescription):
    value: Callable[[AidState], int | datetime | None]


DESCRIPTIONS: tuple[PhonakSensorDescription, ...] = (
    PhonakSensorDescription(
        key="battery",
        device_class=SensorDeviceClass.BATTERY,
        native_unit_of_measurement=PERCENTAGE,
        state_class=SensorStateClass.MEASUREMENT,
        value=lambda d: d.battery,
    ),
    PhonakSensorDescription(
        key="last_update",
        device_class=SensorDeviceClass.TIMESTAMP,
        entity_category=EntityCategory.DIAGNOSTIC,
        value=lambda d: d.updated,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhonakConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    coordinator = entry.runtime_data
    async_add_entities(
        PhonakSensor(coordinator, side, desc) for side in SIDES for desc in DESCRIPTIONS
    )


class PhonakSensor(PhonakEntity, SensorEntity):
    """A read-only value from the hearing aid."""

    entity_description: PhonakSensorDescription

    def __init__(
        self, coordinator: PhonakCoordinator, side: str, description: PhonakSensorDescription
    ) -> None:
        super().__init__(coordinator, side, description.key)
        self.entity_description = description

    @property
    def native_value(self) -> int | datetime | None:
        data = self.aid
        return self.entity_description.value(data) if data else None

    @property
    def extra_state_attributes(self) -> dict[str, str] | None:
        # Why the phone couldn't read the aid last time (values are from earlier).
        data = self.aid
        if self.entity_description.key == "last_update" and data and data.error:
            return {"last_error": data.error}
        return None
