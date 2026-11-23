"""Media player for the pair of hearing aids: volume, mute and program as source.

It doesn't play anything. It exposes the controls the other entities already
have in the shape media cards, voice assistants and HomeKit/Google bridges
understand, and sends the same commands through the phone app.
"""

from __future__ import annotations

from homeassistant.components.media_player import (
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
)
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import (
    ACTION_MUTE,
    ACTION_PROGRAM,
    ACTION_VOLUME,
    DEFAULT_MANUFACTURER,
    DOMAIN,
    MAX_VOLUME,
    MIN_VOLUME,
    SIDES,
)
from .coordinator import AidState, Command, PhonakConfigEntry, PhonakCoordinator
from .select import BY_NAME, SELECTABLE, program_name

STEPS = MAX_VOLUME - MIN_VOLUME


def level_to_volume(level: int) -> float:
    """Aid volume offset (MIN..MAX) -> media player volume (0..1)."""
    return (max(MIN_VOLUME, min(MAX_VOLUME, level)) - MIN_VOLUME) / STEPS


def volume_to_level(volume: float) -> int:
    """Media player volume (0..1) -> aid volume offset, never outside MIN..MAX."""
    return max(MIN_VOLUME, min(MAX_VOLUME, MIN_VOLUME + round(volume * STEPS)))


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhonakConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    async_add_entities([PhonakMediaPlayer(entry.runtime_data)])


class PhonakMediaPlayer(CoordinatorEntity[PhonakCoordinator], MediaPlayerEntity):
    """Both hearing aids as one player. Volume and mute go to both ears."""

    _attr_has_entity_name = True
    _attr_name = None  # the device name: "Phonak hearing aids"
    _attr_device_class = MediaPlayerDeviceClass.SPEAKER
    _attr_supported_features = (
        MediaPlayerEntityFeature.VOLUME_SET
        | MediaPlayerEntityFeature.VOLUME_STEP
        | MediaPlayerEntityFeature.VOLUME_MUTE
        | MediaPlayerEntityFeature.SELECT_SOURCE
    )
    _attr_volume_step = 1 / STEPS

    def __init__(self, coordinator: PhonakCoordinator) -> None:
        super().__init__(coordinator)
        entry_id = coordinator.config_entry.entry_id
        self._attr_unique_id = f"{entry_id}_pair_media_player"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, f"{entry_id}_pair")},
            name="Phonak hearing aids",
            manufacturer=DEFAULT_MANUFACTURER,
        )

    def _aids(self) -> list[AidState]:
        """Aids with values, right first (the right aid is the one read first)."""
        data = self.coordinator.data
        return [data[side] for side in SIDES if side in data and data[side].updated]

    @property
    def available(self) -> bool:
        return bool(self._aids())

    @property
    def state(self) -> MediaPlayerState | None:
        return MediaPlayerState.ON if self._aids() else None

    @property
    def volume_level(self) -> float | None:
        levels = [aid.volume for aid in self._aids() if aid.volume is not None]
        if not levels:
            return None
        # The ears can differ (set separately); show the louder one.
        return level_to_volume(max(levels))

    @property
    def is_volume_muted(self) -> bool | None:
        muted = [aid.muted for aid in self._aids() if aid.muted is not None]
        return any(muted) if muted else None

    @property
    def source(self) -> str | None:
        for aid in self._aids():
            if aid.program is not None:
                return program_name(aid.program)
        return None

    @property
    def source_list(self) -> list[str]:
        sources = list(SELECTABLE.values())
        # Show programs we can't select (streaming, unknown ids) while they're active.
        if (current := self.source) is not None and current not in sources:
            sources.append(current)
        return sources

    def _send_volume(self, level: int) -> None:
        def update(aid: AidState) -> None:
            aid.volume = level

        # Volume is per ear on the aid; the player sets both to the same level.
        for side in SIDES:
            self.coordinator.queue(Command(ACTION_VOLUME, [side], level), update)

    async def async_set_volume_level(self, volume: float) -> None:
        self._send_volume(volume_to_level(volume))

    async def async_volume_up(self) -> None:
        if (volume := self.volume_level) is not None:
            self._send_volume(min(MAX_VOLUME, volume_to_level(volume) + 1))

    async def async_volume_down(self) -> None:
        if (volume := self.volume_level) is not None:
            self._send_volume(max(MIN_VOLUME, volume_to_level(volume) - 1))

    async def async_mute_volume(self, mute: bool) -> None:
        def update(aid: AidState) -> None:
            aid.muted = mute

        self.coordinator.queue(Command(ACTION_MUTE, list(SIDES), mute), update)

    async def async_select_source(self, source: str) -> None:
        if (program := BY_NAME.get(source)) is None:
            raise HomeAssistantError(f"{source} can't be selected; the hearing aid enters it itself")

        def update(aid: AidState) -> None:
            aid.program = program

        self.coordinator.queue(Command(ACTION_PROGRAM, list(SIDES), program), update)
