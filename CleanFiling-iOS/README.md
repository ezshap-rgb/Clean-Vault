# Clean Filing — iOS App (`CleanFiling-iOS`)

This folder contains a completely separate, native **SwiftUI** iPhone/iPad app for **Clean Filing** (`com.cleanfiling.app`), designed to share the exact same Google Drive `"Clean Filing"` cabinet with the Android and Desktop Web apps.

None of your existing Android or Web files are modified or depended upon here.

---

## Key Native iPhone Features
- **Apple VisionKit Document Scanner** (`VNDocumentCameraViewController`): Built-in auto-edge detection, perspective cropping, and multi-page scanning exported to **PDF** (`PDFKit`) or **JPG**.
- **Google Sign-In & Direct Drive Sync**: Shares the `"Clean Filing"` folder and metadata contract (`docId`, `notes`, `isPinned`, `vaultName`) seamlessly with Android and Desktop Web, including **Partner Sharing** and **Cabinet Source / Owner** binding.
- **Bilingual Hebrew / English Toggle (`🇮🇱` / `🇺🇸`)**: Instant Right-to-Left (RTL) layout switching and Unicode Hebrew filename preservation (`\u0590-\u05FF`).
- **Quick Notes, Rename/Tag Editing, Essentials Pinning, Local Trash, QuickLook Preview & Native Share Sheet**.

---

## Step-by-Step: Free Build & Install on iPhone (From Windows, \$0)

### 1. Create a Google iOS OAuth Client ID
1. Open [Google Cloud Console → Credentials](https://console.cloud.google.com/apis/credentials) in your **existing** Google Cloud project (the same one used by Android & Web so both apps have access to the same `drive.file` items).
2. Click **+ CREATE CREDENTIALS → OAuth client ID**.
3. Choose Application type: **iOS**.
4. Enter **Bundle ID**:
   ```text
   com.cleanfiling.app
   ```
5. Click **Create** and copy the generated Client ID (e.g., `162565165888-xxxxxxx.apps.googleusercontent.com`).
6. Ensure your iPhone user's Gmail is listed under **Audience → Test users** if the OAuth consent screen is in Testing mode.

### 2. Configure the Client ID in GitHub Secrets (or `Config/GoogleAuth.xcconfig`)
**Recommended (GitHub Actions secret):**
1. On GitHub, open your repository → **Settings → Secrets and variables → Actions → New repository secret**.
2. Name: `IOS_GOOGLE_CLIENT_ID`
3. Value: paste your full iOS Client ID ending in `.apps.googleusercontent.com`.

*(Alternative if building locally on a Mac: edit `CleanFiling-iOS/Config/GoogleAuth.xcconfig` directly with your Client ID and reversed Client ID.)*

### 3. Build `CleanFiling-unsigned.ipa` for Free on GitHub Actions
1. Push your changes to GitHub (`CleanFiling-iOS/` and `.github/workflows/ios-build.yml`).
2. Go to the **Actions** tab in your GitHub repository → **Build CleanFiling iOS (Unsigned IPA)**.
3. Once complete (~3–5 minutes), download the **`CleanFiling-unsigned-ipa`** artifact at the bottom of the run summary and unzip it to get `CleanFiling-unsigned.ipa`.

### 4. Install onto Your iPhone Using Sideloadly (Free, Windows)
1. Download and install **Sideloadly** on Windows (free).
2. On your iPhone, turn on **Developer Mode** (*Settings → Privacy & Security → Developer Mode*).
3. Plug your iPhone into your PC via USB and trust the computer.
4. Drag `CleanFiling-unsigned.ipa` into Sideloadly, enter your free Apple ID email, and click **Start**.
5. On your iPhone: go to *Settings → General → VPN & Device Management* and **Trust** your Apple ID certificate.
6. Launch **Clean Filing** on your iPhone!

> **Note about Free Apple IDs:** Apps signed with a free Apple ID remain active for 7 days before needing a 15-second re-sign via Sideloadly. When you're ready for 90-day TestFlight installs and Share Sheet extensions, you can enroll in the Apple Developer Program (\$99/year) without rewriting any Swift code.
