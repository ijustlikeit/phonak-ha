"""The Phonak hearing aids integration (fed by the Phonak Bridge phone app)."""

from __future__ import annotations

from datetime import timedelta
from http import HTTPStatus
import logging

from aiohttp import web

from homeassistant.components import webhook
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.event import async_track_time_interval

from .const import CONF_WEBHOOK_ID, DOMAIN
from .coordinator import PhonakConfigEntry, PhonakCoordinator

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [
    Platform.BINARY_SENSOR,
    Platform.BUTTON,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.SENSOR,
    Platform.SWITCH,
]


async def async_setup_entry(hass: HomeAssistant, entry: PhonakConfigEntry) -> bool:
    """Set up the hearing aids and the webhook the phone app reports to."""
    coordinator = PhonakCoordinator(hass, entry)
    entry.runtime_data = coordinator

    async def handle_webhook(
        hass: HomeAssistant, webhook_id: str, request: web.Request
    ) -> web.Response:
        try:
            report = await request.json()
        except ValueError:
            return web.Response(status=HTTPStatus.BAD_REQUEST, text="Invalid JSON")
        if not isinstance(report, dict):
            return web.Response(status=HTTPStatus.BAD_REQUEST, text="Expected an object")
        _LOGGER.debug("Phone report: %s", report)
        commands = coordinator.handle_report(report)
        if commands:
            _LOGGER.debug("Commands for the phone: %s", commands)
        return web.json_response({"commands": commands})

    webhook.async_register(
        hass,
        DOMAIN,
        entry.title,
        entry.data[CONF_WEBHOOK_ID],
        handle_webhook,
        allowed_methods=["POST"],
        local_only=False,  # the phone reports from wherever you are
    )
    entry.async_on_unload(lambda: webhook.async_unregister(hass, entry.data[CONF_WEBHOOK_ID]))

    @callback
    def refresh_phone_state(_now: object) -> None:
        # "Phone app connected" depends on time since the last check-in.
        coordinator.async_update_listeners()

    entry.async_on_unload(
        async_track_time_interval(hass, refresh_phone_state, timedelta(seconds=30))
    )

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: PhonakConfigEntry) -> bool:
    """Unload a config entry."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def async_migrate_entry(hass: HomeAssistant, entry: PhonakConfigEntry) -> bool:
    """Entries before 0.4 connected over Bluetooth; the phone app replaced that."""
    if entry.version < 3:
        _LOGGER.error(
            "Phonak entry '%s' is from a Bluetooth version of this integration. "
            "Delete it and add Phonak Hearing Aids again to get a webhook for the phone app",
            entry.title,
        )
        return False
    return True
