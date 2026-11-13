# Shared Cabinet setup

Local Only needs no account. Personal Google Drive backup uses its own Google sign-in in Android settings. Shared Cabinet uses the Android app and an owner-managed Apps Script deployment.

## Configure a test cabinet

1. In one Google Cloud project, create a Web OAuth client and an Android OAuth client for `com.cleanvault.app` and the signing certificate SHA-1 used by the APK. If the consent screen is in testing, register the owner and partner as test users.
2. Paste the repository's [Code.gs](Code.gs) into the owner's Apps Script project and enable the V8 runtime. In **Project Settings → Script properties**, set `GOOGLE_WEB_CLIENT_ID` to the Web client ID. Keep the cabinet Drive folder and ledger private.
3. Deploy as a web app that executes as the owner. Select **Anyone** for platform access so the app can reach the endpoint. The script checks the Google ID token and cabinet membership on each data request. Copy the exact `/exec` URL.
4. Build Android with `-PcleanVaultGoogleClientId=<web-client-id>` and `-PcleanVaultCabinetUrls=<exec-url>`. The second property accepts comma-separated exact deployment URLs. Only allowlist deployments controlled by the application operator. The app sends Google ID tokens to these deployments.

## Invite and join on Android

1. The Apps Script owner opens **Settings → Shared File Cabinet**, pastes the configured link, and signs in with the Google account that deployed the script. The first verified owner sign-in initializes membership.
2. Enter the partner's Gmail or Google Workspace email and tap **Invite**. Tap **Share cabinet link** to send it through an app of your choice.
3. The partner opens the same settings, pastes the link, and signs in with the invited account.
4. Use **Load members** to see who joined. **Remove** blocks future server requests. Files a member already downloaded or copied remain on their device.

## Upgrade an existing cabinet

Update the existing deployment to a new version, so the app can use the same URL. Archive any older public deployments. In the Apps Script editor, run `privatizeCabinetFiles` as the owner to remove public link sharing on old cabinet files. Review the folder, ledger, and legacy files for separately granted or inherited Drive access. If there are multiple same-name folders or ledgers, set `CABINET_FOLDER_ID` and `CABINET_SPREADSHEET_ID` explicitly before signing in.

The static browser client uses the legacy API. Use Android with the authenticated deployment.
