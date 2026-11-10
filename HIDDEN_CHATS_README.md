# Local Hidden Chats

This branch adds a device-local hidden-chat area to the Android client.

## How to use

1. Open the main Chats screen.
2. Tap the title at the top five times within about 1.5 seconds.
3. On first use, create a password with at least 8 characters, including English letters and digits, then confirm it.
4. Unlock the area and choose **Hide existing chats**.
5. Select one or more chats. Their messages remain in Telegram's normal local database and are not deleted.
6. Tap a hidden chat's name to open it, or choose **Unhide** to return it to the normal list.

The vault locks when its fragment is closed or when the app leaves the foreground. Once the hidden area is opened, Android's secure-window flag remains enabled for the rest of that app session so that opening a hidden chat does not expose it in screenshots or recent-app previews. This also disables screenshots of ordinary screens until the app process is restarted.

## Local protection

- The list of dialog IDs is encrypted locally with AES-256-GCM.
- The password-derived key uses PBKDF2-HMAC-SHA256.
- The normal chat list can filter hidden chats while the vault is locked by using non-reversible HMAC tokens protected by Android Keystore.
- Vault data is stored separately for each Telegram account.
- New notifications from hidden chats are suppressed, and existing notification entries are cleared when a chat is hidden.
- Ordinary dialog search results are filtered before returning hidden peers/messages.

This is local visibility protection, not a second Telegram account or server-side archive. It does not encrypt Telegram's underlying message database independently of Telegram's own storage protection.

## Build for parallel installation

The repository's `APP_PACKAGE` is `org.telegram.messenger`; the Debug build adds the `.beta` application ID suffix, so it installs alongside the regular Telegram package.

1. Clone the repository with its submodules:
   ```bash
   git clone --recursive https://github.com/masihropite/Telegram-private.git
   cd Telegram-private
   git checkout feature/local-hidden-chats
   ```
2. Open the project in Android Studio and install the Android SDK 36 and NDK 27.2.12479018 requested by the Gradle files. Let Android Studio create/update `local.properties` for your SDK path.
3. Configure your own Telegram API ID and API hash locally in the project's BuildVars configuration. Do not publish or send these credentials.
4. Build the **afatDebug** variant (the plain `assembleDebug` task is filtered out by this repository's Gradle configuration):
   ```bash
   ./gradlew :TMessagesProj_App:assembleAfatDebug
   ```
   On Windows use:
   ```bat
   gradlew.bat :TMessagesProj_App:assembleAfatDebug
   ```
5. The APK should be generated at:
   ```
   TMessagesProj_App/build/outputs/apk/afat/debug/app.apk
   ```
   This Debug build uses the application ID `org.telegram.messenger.beta`, so it can be installed alongside the official package `org.telegram.messenger`.

## Verification status

The source changes are committed to this branch, but this environment has not compiled or run the Android application. The Gradle task and APK path above were checked against this repository's build configuration. Test the resulting APK on Android 12 before relying on the feature. In particular, test first-time password creation, wrong-password rejection, hide/unhide, search, notification suppression, app-background locking, and reinstall/backup behavior.


## TelegramZ automated APK build

The GitHub Actions workflow `.github/workflows/build-telegramz.yml` builds the `:TMessagesProj_App:assembleAfatDebug` variant on pushes to `feature/local-hidden-chats` and uploads the resulting `TelegramZ.apk` as a workflow artifact.
