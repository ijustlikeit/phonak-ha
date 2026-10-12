"""Config flow for Phonak hearing aids: creates the webhook the phone app reports to."""

from __future__ import annotations

from typing import Any

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.network import NoURLAvailableError

from .const import CONF_WEBHOOK_ID, DOMAIN


def webhook_url(hass: HomeAssistant, webhook_id: str) -> str:
    try:
        return webhook.async_generate_url(hass, webhook_id)
    except NoURLAvailableError:
        return webhook.async_generate_path(webhook_id) + " (prefix with your Home Assistant URL)"


class PhonakConfigFlow(ConfigFlow, domain=DOMAIN):
    """One entry per pair of hearing aids."""

    VERSION = 3

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        if user_input is None:
            return self.async_show_form(step_id="user")
        webhook_id = webhook.async_generate_id()
        return self.async_create_entry(
            title="Phonak hearing aids",
            data={CONF_WEBHOOK_ID: webhook_id},
            description_placeholders={"webhook_url": webhook_url(self.hass, webhook_id)},
        )

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return PhonakOptionsFlow()


class PhonakOptionsFlow(OptionsFlow):
    """Shows the webhook URL again (there is nothing to change)."""

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data={})
        return self.async_show_form(
            step_id="init",
            description_placeholders={
                "webhook_url": webhook_url(self.hass, self.config_entry.data[CONF_WEBHOOK_ID])
            },
        )
