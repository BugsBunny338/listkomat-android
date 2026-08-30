# MVP verification screenshots — 2026-08-30

First end-to-end run of the MVP on an emulator (Pixel 7 AVD, API 35
google_apis, en-US locale, debug build, first commit 4b07aeb). Captured via
adb while driving the full purchase loop; kept as reference material for the
future Play listing (final listing screenshots should be re-shot from a
release build with curated device frames).

| File | Shows |
|---|---|
| 01-city-list.png | City list; cold-boot stale-prices hint (refresh raced the emulator's network) |
| 03-tickets.png | Brno ticket list |
| 04-confirm.png | Price-confirm dialog (premium SMS BRNO → 90206, 29 CZK) |
| 05-sms-app.png | `smsto:` handoff — Messages prefilled, user presses send |
| 08-pending.png | Pending banner: "Waiting for confirmation…" + Confirm now |
| 09-countdown.png | Live countdown after Confirm now (1:14:57, valid-until time) |
| 10-after-kill.png | Same ticket still counting after `am force-stop` — persistence |
