"""Scan for BLE advertisements from the hearing aids (prefix 80:28:3C)."""
import asyncio
import sys

from bleak import BleakScanner

SECONDS = float(sys.argv[1]) if len(sys.argv) > 1 else 20


async def main() -> None:
    seen: dict[str, int] = {}

    def cb(dev, adv):
        if dev.address.upper().startswith("80:28:3C"):
            if dev.address not in seen:
                print(f"{dev.address} name={adv.local_name!r} rssi={adv.rssi} mfr={ {k: v.hex() for k, v in adv.manufacturer_data.items()} }")
            seen[dev.address] = seen.get(dev.address, 0) + 1

    async with BleakScanner(cb):
        await asyncio.sleep(SECONDS)
    print("advertisement counts:", seen or "none seen")


asyncio.run(main())
