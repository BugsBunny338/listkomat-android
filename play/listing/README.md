# Play store listing

Adapted from the iOS App Store copy (`listkomat-ios/fastlane/metadata/`) to
Play limits: title ≤30, short description ≤80, full description ≤4000 chars.
iOS-only features (Live Activity / Dynamic Island) are replaced by their
Android counterparts. Upload manually to Play Console → Store presence →
Main store listing, or via the Play Developer API once configured.

Data-safety form answers (Console → App content → Data safety):
- Does the app collect or share user data? **No.**
  (Location is used on-device only and never transmitted; no analytics,
  no third-party SDKs sending data. OSM tile fetches are content delivery.)
- Privacy policy URL: https://bugsbunny338.github.io/listkomat-web/privacy
