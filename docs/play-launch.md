# Google Play launch — manual steps (Jiri) + what's scripted

Status: the app itself is MVP-complete with brand theming, notifications and
the live map. Everything below the fold is what only you can do; the rest the
agent can automate once the account exists.

## Manual steps for Jiri (~30–45 min total, mostly waiting)

1. **Create the Play Console developer account** — https://play.google.com/console/signup
   - Personal account, one-time **$25** fee.
   - Use the same Google identity you want to own the app long-term.
   - Identity verification (ID document) is usually required and can take a
     day or two — start it early.
2. **Two-step verification** must be on for the Google account (Play requires it).
3. Once the account exists, create the app entry: *Create app* →
   name **Lístkomat**, default language **Czech (cs)**, App (not game), Free.
4. **Closed testing requirement** (new personal accounts): before production,
   you must run a closed test with **~12 testers opted in for 14 continuous
   days**, then apply for production access. Your friends are the testers —
   collect their Google-account emails and add them to the closed-track
   email list. (Verify the exact tester count in the console; Google tweaks it.)
5. **App signing**: accept Play App Signing (default). We'll upload an AAB
   signed with an upload key the agent can generate locally.
6. **Map tiles — nothing to set up.** The live map is MapLibre Native
   (open source, BSD-2) rendering OpenFreeMap vector tiles: no API key, no
   account. The Google Maps SDK was rejected on 2026-09-30 because it sends
   Google the IP address, device metadata, an identifier and crash traces,
   which would end "no data collected" on the Data safety form (android#22).
   The style URLs at the top of `ui/TransitMap.kt` are the single switch
   if the tiles ever move to self-hosting.

## Release automation (LIVE since 2026-09-23)

`scripts/play_release.py` talks to the Google Play Developer API with the
service account key at `~/.config/listkomat-play/service-account.json`
(local-only secret; per-app permissions: testing + production releases,
tester lists, store presence). Commands: `status`, `release <track>`,
`promote <from> <to>`, `listing <lang> [--dry-run]`, `screenshots <lang> [--dry-run]`. Release notes come from `play/release-notes/<track>.txt` (cs-CZ) and `<track>.en-US.txt` (en-US, sent when present; 500 chars max each)
— edit the files, never the console UI (house rule, same as iOS fastlane
metadata).

## What the agent can do once the account exists

- Generate the upload keystore + signing config (kept out of git).
- Build the release **AAB** (`./gradlew bundleRelease`).
- Prepare the store listing texts (cs + en) from the App Store copy in
  `listkomat-ios/fastlane/metadata/`, adapted to Play limits.
- Data-safety form answers: no data collected, no third-party SDKs sending
  data (OSM tile fetches are content delivery; declare no collection).
- Screenshots: re-shoot from a release build (the dev-verification set in
  `docs/screenshots/` shows the flow but is debug-build material).
- Content rating questionnaire drafts, privacy policy URL
  (listkomat-web already hosts one for iOS — reuse).

## Play policy notes already baked into the app

- SMS: `smsto:` intent only — no `SEND_SMS`/`READ_SMS` permissions anywhere,
  so no SMS/Call-Log policy declaration is needed.
- Location: coarse, foreground-only, with an in-app primer; no background use.
- No exact alarms (`setAndAllowWhileIdle` only) — no policy exposure there.

## Store screenshots (recipe, 2026-11-19)

Taken on the `listkomat` AVD (Pixel 7, 1080×2400) with a signed
`assembleRelease` APK, saved to `play/screenshots/<lang>/NN-name.png`, uploaded
with `play_release.py screenshots <lang>`. Play accepted 1080×2400.

- `adb root`, then the system language: `setprop persist.sys.locale cs-CZ`
  (or en-US) + `settings put system system_locales …` + `setprop ctl.restart
  zygote` (killing systemui alone leaves the shade in the old language). Set the
  app language after the restart: `cmd locale set-app-locales cz.flipcom.listkomat --locales …`.
  English shots: `settings put system time_12_24 12` so the clock matches "4:29 PM".
- Status bar: SystemUI demo mode (`settings put global sysui_demo_allowed 1`,
  then `am broadcast -a com.android.systemui.demo -e command …`: clock = the
  real time so "Valid until" matches, battery 100, wifi full, mobile hidden,
  notifications hidden). Re-apply after every restart.
- Location: `adb emu geo fix 14.4213 50.0875` (Prague). The emulator's US SIM
  shows the foreign-SIM notice; dismiss it with its ×.
- Active ticket: tap a ticket → Messages opens → Back twice → "Ano, odesláno" →
  "Potvrdit nyní". Notification shot: snooze the system notifications first
  (`cmd notification snooze --for 3600000 '<key>'` from `cmd notification list`),
  then `cmd statusbar expand-notifications`.
- Set: countdown, live map with a tapped vehicle card, expanded notification,
  dark map + dark list (Tmavý + Černá), Růžová theme, theme sheet.
