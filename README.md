# Lístkomat for Android

Native Kotlin/Compose companion to [listkomat-ios](https://github.com/BugsBunny338/listkomat-ios) —
premium-SMS transit tickets for Czech cities. Built because friends asked for it
([listkomat-ios#5](https://github.com/BugsBunny338/listkomat-ios/issues/5)).

## How it works

Same product decisions as iOS:

- **SMS handoff, not automation.** Buying launches the user's SMS app via an
  `smsto:` intent with the ticket code prefilled — the user presses send
  themselves. No SMS permission, no Play policy exposure. Don't "upgrade" this
  to `SmsManager`.
- **Catalog-driven.** Cities/tickets come from
  [listkomat-catalog](https://github.com/BugsBunny338/listkomat-catalog)'s
  `tickets.json`: remote (freshest) → disk cache (last good) → bundled asset
  (offline fallback). Ticket fixes need no app release. The schema is a
  two-client contract with iOS — additive changes only.
- **Validity is anchored to confirmation, not send.** The operator's
  confirmation SMS arrives ~2 min after send, so the countdown starts pending
  ("waiting for confirmation") and the user can re-anchor with *Confirm now* —
  the same `TicketTimeline` math as iOS, 120 s buffer.
- **No analytics, no third-party SDKs.**

Czech-first UI (`values/`), English localization (`values-en/`); catalog
`i18n.en` overrides apply at render time.

## Build

```sh
# toolchain (macOS): brew install openjdk@17 android-commandlinetools
# then: sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew test            # JVM unit tests (timeline math, catalog contract)
```

`local.properties` (gitignored) must point `sdk.dir` at the Android SDK.

## Status / roadmap

MVP: city picker → ticket list → SMS handoff → validity countdown. See
[listkomat-ios#5](https://github.com/BugsBunny338/listkomat-ios/issues/5) for
the phasing (next: expiry notification, foreign-SIM notice, live vehicle map,
Play listing). The premium-SMS purchase itself can't be exercised on an
emulator (no SIM) — that step needs a real device with a Czech SIM.
