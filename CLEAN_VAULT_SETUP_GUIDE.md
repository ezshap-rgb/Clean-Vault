# 🗄️ Clean Vault — Personal Cloud Backup & Setup Guide

Welcome to **Clean Vault**! This guide explains how to install the app and (optionally) connect it to your own **Google Drive & Google Sheets** for free, automatic cloud backup.

---

## ⚡ Quick Answer: Does Clean Vault Work Without Any Setup?

**YES, 100%!**

Clean Vault is **local-first**:
- If you simply install the APK and open it, the app works **completely offline with zero setup**.
- You can snap photos of documents/receipts, attach PDFs, dictate document names and labels using voice input, search, view, download, and share files immediately.
- Everything is stored safely on your phone's internal storage.
- Setting up the Google Drive backup is **strictly optional**. It is only needed if you want your files automatically backed up to your personal Google Drive or if you want to share a synchronized file cabinet across multiple phones (e.g. between spouses or family members).

---

## 📥 Step 1: Install Clean Vault on Your Phone

1. Download **`CleanVault.apk`** to your Android phone.
2. Tap the downloaded file to install it.
   - *If prompted, allow your browser or file manager permission to "Install Unknown Apps".*
3. Open **Clean Vault**. It is ready to use immediately in **Local only** mode!

---

## ☁️ Step 2: (Optional) Set Up Your Free Google Drive Backup in 3 Minutes

Follow these steps to connect Clean Vault to your own private Google Drive and Google Sheets. No paid services, subscriptions, or API keys are required!

### 1. Open Google Apps Script
On a computer or tablet, open your browser and go to:
👉 **[script.google.com](https://script.google.com)**  
*(Sign in with your Google account).*

### 2. Create a New Project
1. Click the **"+ New project"** button in the top left.
2. In the top left, click **"Untitled project"** and rename it to:  
   **`Clean Vault Backend`**

### 3. Paste the Backend Script
1. Delete any sample code inside the `Code.gs` editor window.
2. Open the file **`Code.gs`** provided with Clean Vault (or copy the code from the section below), paste it completely into the editor, and click the **Save (💾)** icon.

<details>
<summary>📋 Click to view / copy the complete Code.gs script</summary>

```javascript
/**
 * Clean Vault - Google Apps Script Backend (Sync & Shared Cloud Vault)
 * --------------------------------------------------------------------------
 */

const FOLDER_NAME = "Clean_Vault_Files";
const SHEET_NAME = "Clean_Vault_Ledger";

const HEADERS = [
  "Timestamp", 
  "Date", 
  "Vendor", 
  "Total Amount", 
  "Currency", 
  "Category", 
  "Items Summary", 
  "Notes", 
  "Raw OCR", 
  "Drive File URL", 
  "File ID", 
  "Pinned",
  "Doc ID"
];

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return createJsonResponse({ status: "error", message: "Missing POST payload" }, 400);
    }

    let payload;
    try {
      payload = JSON.parse(e.postData.contents);
    } catch (parseErr) {
      return createJsonResponse({ status: "error", message: "Invalid JSON: " + parseErr.message }, 400);
    }

    const action = payload.action || "save_receipt";

    if (action === "test_connection" || action === "ping") {
      const folder = getOrCreateFolder(FOLDER_NAME);
      const sheet = getOrCreateSheet(SHEET_NAME);
      return createJsonResponse({
        status: "success",
        message: "Clean Vault connected!",
        folderUrl: folder.getUrl(),
        sheetUrl: sheet.getParent().getUrl()
      });
    }

    if (action === "save_receipt") {
      const folder = getOrCreateFolder(FOLDER_NAME);
      const sheet = getOrCreateSheet(SHEET_NAME);

      let fileUrl = "";
      let fileId = "";

      if (payload.fileBase64 || payload.imageBase64) {
        const rawBase64 = (payload.fileBase64 || payload.imageBase64).replace(/^data:[^;]+;base64,/, "");
        const decodedBytes = Utilities.base64Decode(rawBase64);
        const meta = payload.metadata || {};
        const safeVendor = (meta.vendor || "Doc").replace(/[^a-zA-Z0-9_-]/g, "_");
        const safeDate = meta.date || new Date().toISOString().slice(0, 10);
        const mimeType = payload.mimeType || "image/jpeg";
        const ext = mimeType.includes("pdf") ? ".pdf" : (mimeType.includes("png") ? ".png" : (mimeType.includes("word") ? ".docx" : ".jpg"));
        const fileName = payload.fileName || `Vault_${safeDate}_${safeVendor}_${Date.now()}${ext}`;

        const blob = Utilities.newBlob(decodedBytes, mimeType, fileName);
        const file = folder.createFile(blob);
        
        try {
          file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
        } catch (shareErr) {}

        fileUrl = file.getUrl();
        fileId = file.getId();
      }

      const meta = payload.metadata || {};
      const docId = meta.docId || Utilities.getUuid();
      const row = [
        new Date().toISOString(),
        meta.date || new Date().toISOString().slice(0, 10),
        meta.vendor || "Unknown",
        meta.totalAmount !== undefined && meta.totalAmount !== null ? Number(meta.totalAmount) : 0,
        meta.currency || "USD",
        meta.category || "General",
        meta.itemsSummary || "",
        meta.notes || meta.labels || "",
        meta.rawOcr || "",
        fileUrl,
        fileId,
        meta.pinned ? "true" : "false",
        docId
      ];

      sheet.appendRow(row);
      const lastRow = sheet.getLastRow();

      return createJsonResponse({
        status: "success",
        fileUrl: fileUrl,
        fileId: fileId,
        docId: docId,
        rowId: lastRow
      });
    }

    if (action === "toggle_pin") {
      const sheet = getOrCreateSheet(SHEET_NAME);
      const targetRow = findRowByPayload(sheet, payload);

      if (targetRow > 1) {
        const currentVal = String(sheet.getRange(targetRow, 12).getValue()).toLowerCase() === "true";
        const newVal = payload.pinned !== undefined ? (payload.pinned === true || payload.pinned === "true") : !currentVal;
        sheet.getRange(targetRow, 12).setValue(newVal ? "true" : "false");
        return createJsonResponse({ status: "success", pinned: newVal, rowId: targetRow });
      }
      return createJsonResponse({ status: "error", message: "Row not found for toggle_pin" }, 404);
    }

    if (action === "delete_receipt") {
      const sheet = getOrCreateSheet(SHEET_NAME);
      const targetRow = findRowByPayload(sheet, payload);

      if (targetRow > 1) {
        const fileId = sheet.getRange(targetRow, 11).getValue();
        if (fileId) {
          try { DriveApp.getFileById(fileId).setTrashed(true); } catch (e) {}
        }
        sheet.deleteRow(targetRow);
        return createJsonResponse({ status: "success", deletedRow: targetRow });
      }
      return createJsonResponse({ status: "error", message: "Row not found for delete_receipt" }, 404);
    }

    if (action === "update_receipt" || action === "rename_receipt") {
      const sheet = getOrCreateSheet(SHEET_NAME);
      const targetRow = findRowByPayload(sheet, payload);

      if (targetRow > 1) {
        if (payload.name !== undefined && String(payload.name).trim() !== "") {
          sheet.getRange(targetRow, 3).setValue(payload.name);
        }
        if (payload.labels !== undefined) {
          sheet.getRange(targetRow, 8).setValue(payload.labels);
        } else if (payload.notes !== undefined) {
          sheet.getRange(targetRow, 8).setValue(payload.notes);
        }
        if (payload.docId && sheet.getLastColumn() >= 13) {
          sheet.getRange(targetRow, 13).setValue(payload.docId);
        }
        return createJsonResponse({ status: "success", rowId: targetRow });
      }
      return createJsonResponse({ status: "error", message: "Row not found for update_receipt" }, 404);
    }

    return createJsonResponse({ status: "error", message: "Unrecognized action" }, 400);
  } catch (err) {
    return createJsonResponse({ status: "error", message: err.toString() }, 500);
  }
}

function doGet(e) {
  try {
    const action = (e && e.parameter && e.parameter.action) || "get_records";

    if (action === "ping" || action === "test") {
      return createJsonResponse({ status: "success", message: "Online" });
    }

    if (action === "get_records") {
      const sheet = getOrCreateSheet(SHEET_NAME);
      const data = sheet.getDataRange().getValues();

      if (data.length <= 1) {
        return createJsonResponse({ status: "success", records: [] });
      }

      const headers = data[0];
      const rows = data.slice(1);

      const records = rows.map((row, index) => {
        let record = { rowId: index + 2 };
        headers.forEach((header, colIdx) => {
          record[toCamelCase(header)] = row[colIdx];
        });

        record.timestamp = row[0] || record.timestamp || "";
        record.date = row[1] || record.date || "";
        record.vendor = row[2] || record.vendor || "";
        record.name = row[2] || record.name || record.vendor || "";
        record.notes = row[7] || record.notes || "";
        record.labels = row[7] || record.labels || record.notes || "";
        record.driveFileUrl = row[9] || record.driveFileUrl || record.driveFileURL || "";
        record.fileUrl = record.driveFileUrl;
        record.fileId = row[10] || record.fileId || record.fileID || "";
        record.pinned = String(row[11] || record.pinned).toLowerCase() === "true";
        record.docId = String(row[12] || record.docId || record.docID || ("row_" + (index + 2)));

        return record;
      });

      records.reverse();
      const seenFileIds = {};
      const seenDocIds = {};
      const uniqueRecords = [];

      for (let i = 0; i < records.length; i++) {
        const r = records[i];
        const fId = r.fileId ? String(r.fileId).trim() : "";
        const dId = (r.docId && !r.docId.startsWith("row_")) ? String(r.docId).trim() : "";

        if (fId && seenFileIds[fId]) continue;
        if (dId && seenDocIds[dId]) continue;

        if (fId) seenFileIds[fId] = true;
        if (dId) seenDocIds[dId] = true;

        uniqueRecords.push(r);
      }

      return createJsonResponse({ status: "success", records: uniqueRecords, count: uniqueRecords.length });
    }

    if (action === "download_file") {
      const fileId = e.parameter.fileId;
      if (!fileId) return createJsonResponse({ status: "error", message: "Missing fileId" }, 400);

      const file = DriveApp.getFileById(fileId);
      const blob = file.getBlob();
      const base64 = Utilities.base64Encode(blob.getBytes());
      return createJsonResponse({
        status: "success",
        name: file.getName(),
        mimeType: blob.getContentType(),
        base64: base64
      });
    }

    return createJsonResponse({ status: "error", message: "Unknown action" }, 400);
  } catch (err) {
    return createJsonResponse({ status: "error", message: err.toString() }, 500);
  }
}

function findRowByPayload(sheet, payload) {
  if (!payload) return -1;
  const data = sheet.getDataRange().getValues();
  if (data.length <= 1) return -1;

  const docId = payload.docId ? String(payload.docId).trim() : "";
  const fileId = payload.fileId ? String(payload.fileId).trim() : "";
  const rowId = parseInt(payload.rowId, 10);

  if (!isNaN(rowId) && rowId > 1 && rowId <= sheet.getLastRow()) {
    return rowId;
  }

  if (docId && docId.startsWith("row_")) {
    const parsedRow = parseInt(docId.replace("row_", ""), 10);
    if (!isNaN(parsedRow) && parsedRow > 1 && parsedRow <= sheet.getLastRow()) {
      return parsedRow;
    }
  }

  if (docId) {
    for (let r = 1; r < data.length; r++) {
      if (data[r].length > 12 && String(data[r][12]).trim() === docId) {
        return r + 1;
      }
    }
  }

  if (fileId) {
    for (let r = 1; r < data.length; r++) {
      if (data[r].length > 10 && String(data[r][10]).trim() === fileId) {
        return r + 1;
      }
    }
  }

  const fileUrl = (payload.fileUrl || payload.driveFileUrl || "").toString().trim();
  if (fileUrl) {
    for (let r = 1; r < data.length; r++) {
      if (data[r].length > 9 && String(data[r][9]).trim() === fileUrl) {
        return r + 1;
      }
    }
  }

  return -1;
}

function findRowByDocIdOrRowId(sheet, docId, rowId) {
  return findRowByPayload(sheet, { docId: docId, rowId: rowId });
}

function getOrCreateFolder(folderName) {
  const folders = DriveApp.getFoldersByName(folderName);
  if (folders.hasNext()) return folders.next();
  return DriveApp.createFolder(folderName);
}

function getOrCreateSheet(sheetName) {
  const files = DriveApp.getFilesByName(sheetName);
  let spreadsheet = files.hasNext() ? SpreadsheetApp.open(files.next()) : SpreadsheetApp.create(sheetName);
  let sheet = spreadsheet.getActiveSheet();

  if (sheet.getLastRow() === 0) {
    sheet.appendRow(HEADERS);
    const headerRange = sheet.getRange(1, 1, 1, HEADERS.length);
    headerRange.setFontWeight("bold");
    headerRange.setBackground("#151C33");
    headerRange.setFontColor("#FFFFFF");
    sheet.setFrozenRows(1);
  } else if (sheet.getLastColumn() < HEADERS.length) {
    sheet.getRange(1, 12).setValue("Pinned").setFontWeight("bold");
    sheet.getRange(1, 13).setValue("Doc ID").setFontWeight("bold");
  }
  return sheet;
}

function toCamelCase(str) {
  return str
    .replace(/\s(.)/g, function($1) { return $1.toUpperCase(); })
    .replace(/\s/g, '')
    .replace(/^(.)/, function($1) { return $1.toLowerCase(); });
}

function createJsonResponse(data, statusCode) {
  const output = ContentService.createTextOutput(JSON.stringify(data));
  output.setMimeType(ContentService.MimeType.JSON);
  return output;
}
```
</details>

---

### 4. Deploy as a Web App
1. In the top right corner of the Google Apps Script editor, click **Deploy > New deployment**.
2. Click the gear icon ⚙️ next to *"Select type"* and choose **Web app**.
3. Fill out the settings:
   - **Description:** `Clean Vault Sync`
   - **Execute as:** `Me (your email address)`
   - **Who has access:** `Anyone` *(Note: This allows your Android phone to upload to your private Drive without complex OAuth logins. Only someone with your exact private URL can access it).*
4. Click **Deploy**.

---

### 5. Grant Google Permissions (One-Time Step)
When deploying for the first time, Google will ask for permission to let this script create files in your Drive:
1. Click **Authorize access**.
2. Choose your Google account.
3. You will see a screen saying: *"Google hasn't verified this app"*.  
   *(Don't worry, this is normal because you just created the script yourself!)*
4. Click **Advanced** (small text at bottom left).
5. Click **"Go to Clean Vault Backend (unsafe)"**.
6. Click **Allow**.

---

### 6. Copy Your Web App URL
After authorization finishes, you will see a box titled **Web app**:
1. Copy the **Web app URL**.  
   It looks like this:  
   `https://script.google.com/macros/s/AKfycbx.../exec`

---

### 7. Paste into Clean Vault on Your Phone
1. Open the **Clean Vault** app on your phone.
2. In the top bar, tap the **Settings (⚙️)** gear icon.
3. Paste your Web App URL into the field.
4. Tap **Test Link**. You will see a green badge saying: `Connected successfully!`
5. Tap **Save & Sync**.

---

## 🎯 What Happens Next?

Your phone is now connected!
- A folder named **`Clean_Vault_Files`** is created in your Google Drive.
- A spreadsheet named **`Clean_Vault_Ledger`** is created in your Google Drive.
- Any receipt or document you save will be instantly backed up.
- **Sync on Open & Close:** Every time you open Clean Vault, it checks for updates. Every time you leave the app, it backs up your changes.
- **Sharing Across Multiple Devices:** If you and your spouse or siblings want to share the exact same file cabinet, simply install the APK on both phones and paste the **same** Web App URL into both!

Enjoy Clean Vault! 🗄️✨
