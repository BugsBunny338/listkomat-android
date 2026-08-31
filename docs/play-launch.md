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
6. **Google Maps API key** (recommended — this is what makes the live map
   pretty): in Google Cloud Console create a project, enable *Maps SDK for
   Android*, create an API key restricted to the app's package name +
   signing-cert SHA-1. The free monthly credit comfortably covers a personal
   app. Until then the map uses OSM tiles (functional but visually loud —
   Carto's clean tiles turned out to watermark without a key, tried
   2026-08-31). The swap is contained to `ui/LiveMapScreen.kt`.

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
