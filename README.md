# Clean Vault

Clean Vault is an Android document cabinet for scans, receipts, files, and notes. File and find documents offline, or connect Personal Google Drive or Shared Cabinet for sync.

- [Android APK](CleanFiling.apk)
- [Shared Cabinet setup and upgrade guide](CLEAN_VAULT_SETUP_GUIDE.md)
- [Apps Script backend](Code.gs)

## Build Android

From `android-native`, run `gradlew.bat :app:assembleDebug` with JDK 21 and Android SDK 35. The [Shared Cabinet guide](CLEAN_VAULT_SETUP_GUIDE.md) covers the OAuth client and deployment URLs required for authenticated cabinet builds.

The static browser client uses the legacy Shared Cabinet API. Use the Android app for authenticated cabinets.
