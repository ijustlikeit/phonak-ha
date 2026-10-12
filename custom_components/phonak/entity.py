"""Base entities for Phonak hearing aids."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DEFAULT_MANUFACTURER, DOMAIN, SIDE_NAMES, SIDES
from .coordinator import AidState, Command, PhonakCoordinator


class PhonakEntity(CoordinatorEntity[PhonakCoordinator]):
    """An entity belonging to one hearing aid (its own device) of the pair."""

    _attr_has_entity_name = True

    def __init__(self, coordinator: PhonakCoordinator, side: str, key: str) -> None:
        super().__init__(coordinator)
        self.side = side
        entry_id = coordinator.config_entry.entry_id
        self._attr_translation_key = key
        self._attr_unique_id = f"{entry_id}_{side}_{key}"
        # Model, serial and firmware are added by the coordinator from the reports.
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, f"{entry_id}_{side}")},
            name=SIDE_NAMES[side],
            manufacturer=DEFAULT_MANUFACTURER,
        )

    @property
    def aid(self) -> AidState | None:
        """Last values the phone read from this entity's aid."""
        return self.coordinator.data.get(self.side)

    @property
    def available(self) -> bool:
        # The phone often can't reach the aids (out of range, in the charger),
        # so keep showing the last values instead of flapping to unavailable.
        return self.aid is not None and self.aid.updated is not None

    def send(
        self,
        action: str,
        value: Any = None,
        *,
        both: bool = False,
        device: str | None = None,
        apply: Callable[[AidState], None] | None = None,
    ) -> None:
        """Queue a change for the phone. Pair-wide ones go to each aid, like myPhonak."""
        # The phone skips an aid it doesn't know and reports that as the result.
        sides = list(SIDES) if both else [self.side]
        self.coordinator.queue(Command(action, sides, value, device), apply)


class PhonakBridgeEntity(CoordinatorEntity[PhonakCoordinator]):
    """An entity of the phone app that relays to the aids."""

    _attr_has_entity_name = True

    def __init__(self, coordinator: PhonakCoordinator, key: str) -> None:
        super().__init__(coordinator)
        entry_id = coordinator.config_entry.entry_id
        self._attr_translation_key = key
        self._attr_unique_id = f"{entry_id}_bridge_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, f"{entry_id}_bridge")},
            name="Phonak Bridge",
            manufacturer="Phonak Bridge",
            sw_version=coordinator.app_version,
        )
