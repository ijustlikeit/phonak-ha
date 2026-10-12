"""Hearing program selector for Phonak hearing aids."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .const import ACTION_PROGRAM, PROGRAM_NAMES, PROGRAM_STREAMING, SIDES
from .coordinator import AidState, PhonakConfigEntry
from .entity import PhonakEntity

# Programs that can be picked; the streaming program is entered by the aid itself.
SELECTABLE = {pid: name for pid, name in PROGRAM_NAMES.items() if pid != PROGRAM_STREAMING}
BY_NAME = {name: pid for pid, name in SELECTABLE.items()}


def program_name(program: int) -> str:
    return PROGRAM_NAMES.get(program, f"Program {program:#04x}")


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhonakConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    coordinator = entry.runtime_data
    async_add_entities(PhonakProgram(coordinator, side, "program") for side in SIDES)


class PhonakProgram(PhonakEntity, SelectEntity):
    """Active hearing program. Changing it switches both aids, like myPhonak."""

    @property
    def current_option(self) -> str | None:
        data = self.aid
        return program_name(data.program) if data and data.program is not None else None

    @property
    def options(self) -> list[str]:
        options = list(SELECTABLE.values())
        # Show programs we can't select (streaming, unknown ids) while they're active.
        if (current := self.current_option) is not None and current not in options:
            options.append(current)
        return options

    async def async_select_option(self, option: str) -> None:
        if (program := BY_NAME.get(option)) is None:
            raise HomeAssistantError(f"{option} can't be selected; the hearing aid enters it itself")

        def update(data: AidState) -> None:
            data.program = program

        self.send(ACTION_PROGRAM, program, both=True, apply=update)
