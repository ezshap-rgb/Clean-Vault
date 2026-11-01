# Clean Vault 🗄️⚡
> **Autonomous Mobile Document & Receipt Vault**  
> Native Android app & PWA for fast, offline-first document organizing with zero-lag storage, speech dictation, and optional automatic Google Drive backup and shared multi-device sync.

---

## 📱 Download the Native Android App

- **[Download CleanVault.apk](https://github.com/ezshap-rgb/Clean-Vault/raw/main/CleanVault.apk)**
- **[Step-by-Step Setup & Sharing Guide](CLEAN_VAULT_SETUP_GUIDE.md)**

---

## ⚡ Standalone (Local-Only) vs Cloud Backup

1. **Standalone (Local-Only)**:
   - Clean Vault works **100% out of the box with zero configuration**.
   - All documents, photos, PDFs, and notes are saved to your device's internal storage.
   - Built-in on-device speech dictation for document names and labels.
   - 0ms latency viewing, downloading, searching, pinning, and sharing.
   - No internet connection or accounts required.

2. **Personal Google Drive Backup & Multi-Device Sync (Optional)**:
   - Free personal cloud backup to your own Google Drive (`Clean_Vault_Files`) and Google Sheets (`Clean_Vault_Ledger`).
   - Syncs on app open and close.
   - Allows spouses or family members to share the exact same synchronized cabinet across multiple phones!
   - Follow the **[Setup Guide](CLEAN_VAULT_SETUP_GUIDE.md)** to connect in under 3 minutes.

---

## 🎨 Design & Aesthetic
Clean Vault is crafted to match the exact dark obsidian and glowing electric gradients of the **Clean** app suite (**Clean Audio Trimmer**, **Clean Recorder**):
- **Base obsidian background**: `#070913` with subtle glowing radial ambient lighting.
- **Electric signature gradient**: `linear-gradient(135deg, #5C4FEE 0%, #A816DF 50%, #F50ACE 100%)`.
- **Amber Gold accents**: `#F59E0B` matching the glowing handles on the 3D Vault icon.
- **Typography**: Clean modern sans (`Outfit`) paired with precision figures in `JetBrains Mono`.
- **PWA Mobile-First**: Native viewport fit, iOS safe-area handling, smooth animations, and haptic touch states.

---

## 🚀 Key Features

### 1. Zero-Friction Intake (Snap & Dictate)
- **Direct Camera or Gallery**: Snap with your phone's camera or select existing photos/screenshots.
- **Smart Client Compression**: Automatically optimizes photos to max 1600px @ 80% JPEG quality before uploading, keeping uploads near-instantaneous while preserving razor-sharp OCR clarity.
- **Voice Dictation**: Tap the glowing mic to speak contextual notes (e.g., *"Dinner with client Sarah, tax deductible"*). Uses the Web Speech API with real-time transcription.
- **Multimodal AI Extraction**: Sends image and speech context to Google Gemini (supports `gemini-2.5-flash`, `gemini-2.5-pro`, or `gemini-1.5-flash`). Automatically extracts:
  - Vendor / Merchant Name
  - Date (ISO `YYYY-MM-DD`)
  - Total Amount & Currency (`USD`, `EUR`, `ILS`, `GBP`, `CAD`, `AUD`, etc.)
  - Categorization (`Groceries`, `Dining`, `Business`, `Tech`, `Travel`, `Utilities`, etc.)
  - Line Items & Summary
  - Full Text OCR Transcript
- **Review Card**: Quick editable review with instant one-click save.

### 2. Private Google Drive & Sheets Backend (`Code.gs`)
- **100% Private & Free**: All files are uploaded directly to your own Google Drive in the folder `Clean_Vault_Files`.
- **Structured Google Sheet**: Every receipt is recorded in `Clean_Vault_Ledger` with a direct clickable link to the stored photo.
- **Zero Third-Party Databases or Subscriptions**: Runs on your personal Google account via Google Apps Script.

### 3. Ask Vault AI & Instant Ledger Retrieval
- **Natural Language Assistant**: Tap the mic or type questions like:
  - *"How much did I spend on groceries in March?"*
  - *"Find the receipt for the computer monitor I bought"*
  - *"What was my highest dining bill this year?"*
  Gemini analyzes your entire ledger, calculates the exact mathematical sum, and surfaces the matching receipts with direct links to view the original photos in Drive!
- **Fast Client-Side Filtering**: Category chips and instant search by vendor or note without waiting for network requests.
- **Offline Ledger Cache**: Cached locally in browser `localStorage` for instant lookup anywhere.

---

## 🛠️ Step-by-Step Setup Guide

### Step 1: Deploy the Google Apps Script Backend
1. Go to [script.google.com](https://script.google.com) and click **New project**.
2. Name the project `Clean Vault Backend`.
3. Replace the entire code in `Code.gs` with the provided [Code.gs](file:///c:/Users/ezsha/Github/Clean%20Vault/Code.gs).
4. Click **Deploy** (top right) > **New deployment**.
5. Click the gear icon next to "Select type" and choose **Web app**.
6. Set the deployment fields:
   - **Description**: `Clean Vault v1`
   - **Execute as**: `Me (your_email@gmail.com)`
   - **Who has access**: `Anyone` *(Crucial: allows your mobile PWA to communicate securely with your script)*
7. Click **Deploy**, click **Authorize access** with your Google account, and copy the **Web App URL** (ends in `/exec`).

> *(The script will automatically generate the `Clean_Vault_Files` Drive folder and `Clean_Vault_Ledger` Sheet upon your first save!)*

### Step 2: Get a Gemini API Key
1. Go to [Google AI Studio](https://aistudio.google.com/).
2. Click **Get API key** > **Create API key**.
3. Copy your key.

### Step 3: Run / Host the PWA Client
You can host `Clean Vault` statically anywhere:
- **GitHub Pages**: Push this repo to GitHub, go to **Settings > Pages**, and set the source branch to `main`.
- **Local Testing**: Run `npx serve .` in this folder.

### Step 4: Install to Phone Home Screen
1. Open the hosted URL in Safari (iOS) or Chrome (Android).
2. **iOS**: Tap **Share** (bottom icon) > **Add to Home Screen**.
3. **Android**: Tap **More (⋮)** > **Install app / Add to Home screen**.
4. Open the app from your home screen, go to **Settings**, paste your Gemini API Key and Apps Script URL, and tap **Save Settings** & **Test Connection**!

---

## 📱 Tech Stack
- **Frontend**: Vanilla HTML5, CSS3 Custom Properties (Design Tokens), Web Speech API, Canvas API, PWA Service Worker.
- **AI Engine**: Google Gemini Multimodal REST API (`gemini-2.5-flash`, `gemini-2.5-pro`).
- **Cloud Backend**: Google Apps Script, Google Drive API, Google Sheets API.
- **Branding**: Clean Suite Design System & 3D Drawer Vault Icon.

