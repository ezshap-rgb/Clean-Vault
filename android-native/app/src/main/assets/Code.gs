/**
 * Clean Vault - Google Apps Script Backend (Sync & Shared Cloud Vault)
 * --------------------------------------------------------------------------
 * Shared cloud backend enabling multiple devices (e.g. you and your wife)
 * to sync to the exact same Google Drive folder and ledger.
 */

const FOLDER_NAME = "Clean_Vault_Files";
const SHEET_NAME = "Clean_Vault_Ledger";

function verifyCabinetIdentity_(token) {
  const audience = PropertiesService.getScriptProperties().getProperty("GOOGLE_WEB_CLIENT_ID");
  if (!audience || typeof token !== "string" || token.length > 8192) return null;
  const parts = token.split(".");
  if (parts.length !== 3 || parts.some(part => !/^[A-Za-z0-9_-]+$/.test(part))) return null;
  const decode = part => JSON.parse(Utilities.newBlob(Utilities.base64DecodeWebSafe(part)).getDataAsString());
  const header = decode(parts[0]);
  const claims = decode(parts[1]);
  if (header.alg !== "RS256" || claims.aud !== audience ||
      !["accounts.google.com", "https://accounts.google.com"].includes(claims.iss) ||
      !Number.isFinite(claims.exp) || claims.exp <= Date.now() / 1000 ||
      claims.email_verified !== true || typeof claims.sub !== "string" || !claims.sub ||
      typeof claims.email !== "string" || !claims.email) return null;

  const cache = CacheService.getScriptCache();
  const cached = cache.get("google_signing_keys");
  let keys = cached ? JSON.parse(cached) : [];
  let key = keys.find(entry => entry.kid === header.kid);
  if (!cached) {
    const response = UrlFetchApp.fetch("https://www.googleapis.com/oauth2/v3/certs");
    keys = JSON.parse(response.getContentText()).keys;
    const headers = response.getAllHeaders();
    const maxAge = /max-age=(\d+)/i.exec(String(headers["Cache-Control"] || headers["cache-control"] || ""));
    if (maxAge && Number(maxAge[1]) > 0) {
      cache.put("google_signing_keys", JSON.stringify(keys), Math.min(Number(maxAge[1]), 3600));
    }
    key = keys.find(entry => entry.kid === header.kid);
  }
  if (!key || key.kty !== "RSA" || key.alg !== "RS256") return null;
  if (!verifyRsaSignature_(parts, key)) return null;
  return claims;
}

function verifyRsaSignature_(parts, key) {
  // Verify the fixed RS256 DigestInfo encoding using Google's RSA public key.
  const hex = bytes => bytes.map(byte => (byte & 255).toString(16).padStart(2, "0")).join("");
  const modulus = hex(Utilities.base64DecodeWebSafe(key.n));
  const signature = hex(Utilities.base64DecodeWebSafe(parts[2]));
  if (signature.length !== modulus.length) return false;
  const n = BigInt("0x" + modulus);
  let value = BigInt("0x" + signature);
  let exponent = BigInt("0x" + hex(Utilities.base64DecodeWebSafe(key.e)));
  if (value >= n) return false;
  let decoded = 1n;
  while (exponent > 0n) {
    if (exponent & 1n) decoded = decoded * value % n;
    value = value * value % n;
    exponent >>= 1n;
  }
  const digest = hex(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, parts[0] + "." + parts[1]));
  const digestInfo = "3031300d060960864801650304020105000420" + digest;
  const padding = (modulus.length - 6 - digestInfo.length) / 2;
  return padding >= 8 &&
    decoded.toString(16).padStart(modulus.length, "0") === "0001" + "ff".repeat(padding) + "00" + digestInfo;
}

function cabinetAccess_(identity, action, payload) {
  const properties = PropertiesService.getScriptProperties();
  const email = identity.email.toLowerCase();
  const authoritativeEmail = email.endsWith("@gmail.com") || Boolean(identity.hd);
  const state = JSON.parse(properties.getProperty("CABINET_MEMBERSHIP") || '{"owner":"","members":[]}');
  if (!state.owner && action === "join" && authoritativeEmail &&
      email === Session.getEffectiveUser().getEmail().toLowerCase()) {
    state.owner = identity.sub;
    properties.setProperty("CABINET_MEMBERSHIP", JSON.stringify(state));
  }
  const owner = state.owner === identity.sub;
  const member = state.members.find(entry => entry.sub === identity.sub);
  if (!owner && !member && action === "join" && authoritativeEmail) {
    const invite = state.members.find(entry => entry.email === email && !entry.sub);
    if (invite) {
      invite.sub = identity.sub;
      properties.setProperty("CABINET_MEMBERSHIP", JSON.stringify(state));
      return { status: "success", role: "member" };
    }
  }
  if (!owner && !member) return { status: "error", message: "This Google account has not been invited." };
  if (action === "join") return { status: "success", role: owner ? "owner" : "member" };
  if (!["invite", "members", "remove_member"].includes(action)) return null;
  if (!owner) return { status: "error", message: "Only the cabinet owner can manage members." };
  const address = String(payload.email || "").trim().toLowerCase();
  if (action === "invite") {
    if (address.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address) || address === email) {
      return { status: "error", message: "Enter your partner's Google account email." };
    }
    if (!state.members.some(entry => entry.email === address)) {
      state.members.push({ email: address, sub: "" });
    }
  } else if (action === "remove_member") {
    state.members = state.members.filter(entry => entry.email !== address);
  }
  if (action !== "members") properties.setProperty("CABINET_MEMBERSHIP", JSON.stringify(state));
  return { status: "success", members: state.members.map(entry => ({ email: entry.email, joined: Boolean(entry.sub) })) };
}

function getCabinetFile_(fileId) {
  const file = DriveApp.getFileById(fileId);
  const folder = getOrCreateFolder(FOLDER_NAME);
  const parents = file.getParents();
  while (parents.hasNext()) {
    if (parents.next().getId() === folder.getId()) return file;
  }
  throw new Error("File is outside this cabinet");
}

// Run from the Apps Script editor when upgrading an existing cabinet.
function privatizeCabinetFiles() {
  const files = getOrCreateFolder(FOLDER_NAME).getFiles();
  while (files.hasNext()) {
    const file = files.next();
    if (file.getSharingAccess() !== DriveApp.Access.PRIVATE) {
      file.setSharing(DriveApp.Access.PRIVATE, DriveApp.Permission.VIEW);
    }
  }
}

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
  let lock;
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
    const identity = verifyCabinetIdentity_(payload.idToken);
    if (!identity) return createJsonResponse({ status: "error", message: "Google sign-in required" });
    lock = LockService.getScriptLock();
    lock.waitLock(10000);
    const access = cabinetAccess_(identity, action, payload);
    if (access) return createJsonResponse(access);

    if (action === "get_records" || action === "download_file") {
      return readCabinet_(action, payload);
    }

    // 1. Action: Ping / Test
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

    // 2. Action: Save Receipt / Document
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
        
        fileId = file.getId();
      }

      const meta = payload.metadata || {};
      const docId = meta.docId || Utilities.getUuid();
      const row = [
        new Date().toISOString(),
        cabinetText_(meta.date || new Date().toISOString().slice(0, 10)),
        cabinetText_(meta.vendor || "Unknown"),
        meta.totalAmount !== undefined && meta.totalAmount !== null ? Number(meta.totalAmount) : 0,
        cabinetText_(meta.currency || "USD"),
        cabinetText_(meta.category || "General"),
        cabinetText_(meta.itemsSummary || ""),
        cabinetText_(meta.notes || ""),
        cabinetText_(meta.rawOcr || ""),
        fileUrl,
        fileId,
        meta.pinned ? "true" : "false",
        cabinetText_(docId)
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
        let pinnedCol = getColumnIndexByName(sheet, "Pinned");
        if (pinnedCol <= 0) {
          pinnedCol = sheet.getLastColumn() + 1;
          sheet.getRange(1, pinnedCol).setValue("Pinned").setFontWeight("bold");
        }
        const currentVal = String(sheet.getRange(targetRow, pinnedCol).getValue()).toLowerCase() === "true";
        const newVal = payload.pinned !== undefined ? (payload.pinned === true || payload.pinned === "true" || payload.pinned === 1) : !currentVal;
        sheet.getRange(targetRow, pinnedCol).setValue(newVal ? "true" : "false");
        return createJsonResponse({ status: "success", pinned: newVal, rowId: targetRow, col: pinnedCol });
      }
      return createJsonResponse({ status: "error", message: "Row not found for toggle_pin" }, 404);
    }

    // 4. Action: Delete Receipt
    if (action === "delete_receipt") {
      const sheet = getOrCreateSheet(SHEET_NAME);
      const targetRow = findRowByPayload(sheet, payload);

      if (targetRow > 1) {
        const rows = sheet.getDataRange().getValues();
        const docId = String(rows[targetRow - 1][12] || "");
        const matchingRows = rows.slice(1).map((row, index) => ({ row: row, number: index + 2 }))
          .filter(entry => entry.number === targetRow || (docId && String(entry.row[12]) === docId));
        const files = [...new Set(matchingRows.map(entry => entry.row[10]).filter(Boolean))].map(getCabinetFile_);
        files.forEach(file => file.setTrashed(true));
        matchingRows.reverse().forEach(entry => sheet.deleteRow(entry.number));
        return createJsonResponse({ status: "success", deletedRow: targetRow });
      }
      return createJsonResponse({ status: "error", message: "Row not found for delete_receipt" }, 404);
    }

    // 5. Action: Update / Rename Receipt
    if (action === "update_receipt" || action === "rename_receipt") {
      const sheet = getOrCreateSheet(SHEET_NAME);
      const targetRow = findRowByPayload(sheet, payload);

      if (targetRow > 1) {
        if (payload.name !== undefined && String(payload.name).trim() !== "") {
          sheet.getRange(targetRow, 3).setValue(cabinetText_(payload.name));
        }
        if (payload.labels !== undefined) {
          sheet.getRange(targetRow, 8).setValue(cabinetText_(payload.labels));
        } else if (payload.notes !== undefined) {
          sheet.getRange(targetRow, 8).setValue(cabinetText_(payload.notes));
        }
        return createJsonResponse({ status: "success", rowId: targetRow });
      }
      return createJsonResponse({ status: "error", message: "Row not found for update_receipt" }, 404);
    }

    return createJsonResponse({ status: "error", message: "Unrecognized action" }, 400);
  } catch (err) {
    return createJsonResponse({ status: "error", message: "Cabinet request failed. Check the deployment and try again." });
  } finally {
    if (lock && lock.hasLock()) {
      try { SpreadsheetApp.flush(); } finally { lock.releaseLock(); }
    }
  }
}

function doGet() {
  return createJsonResponse({ status: "error", message: "Open this cabinet from Clean Filing and sign in with Google." });
}

function cabinetText_(value) {
  const text = String(value);
  return /^\s*=/.test(text) ? "'" + text : text;
}

function readCabinet_(action, payload) {
  try {
    if (action === "get_records") {
      const sheet = getOrCreateSheet(SHEET_NAME);
      const data = sheet.getDataRange().getValues();

      if (data.length <= 1) {
        return createJsonResponse({ status: "success", records: [] });
      }

      const headers = data[0];
      const rows = data.slice(1);
      const lowerHeaders = headers.map(h => String(h).trim().toLowerCase());
      const pinnedIdx = lowerHeaders.indexOf("pinned");

      const records = rows.map((row, index) => {
        let record = { rowId: index + 2 };
        if (!row[12]) {
          row[12] = Utilities.getUuid();
          sheet.getRange(index + 2, 13).setValue(row[12]);
        }
        headers.forEach((header, colIdx) => {
          record[toCamelCase(header)] = row[colIdx];
        });

        // Ensure normalized access regardless of camelCase variations
        record.timestamp = row[0] || record.timestamp || "";
        record.date = row[1] || record.date || "";
        record.vendor = row[2] || record.vendor || "";
        record.name = row[2] || record.name || record.vendor || "";
        record.notes = row[7] || record.notes || "";
        record.labels = row[7] || record.labels || record.notes || "";
        delete record.driveFileURL;
        record.driveFileUrl = "";
        record.fileUrl = "";
        record.fileId = row[10] || record.fileId || record.fileID || "";
        
        // Dynamically evaluate pinned status from dedicated column or camelCase field
        const rawPinVal = pinnedIdx >= 0 ? row[pinnedIdx] : (row[11] || record.pinned);
        record.pinned = (rawPinVal === true || rawPinVal === 1 || String(rawPinVal).toLowerCase() === "true");
        record.docId = String(row[12] || record.docId || record.docID || ("row_" + (index + 2)));

        return record;
      });

      // Traverse newest first and deduplicate by fileId / docId to eliminate duplicate rows
      records.reverse();
      const seenFileIds = Object.create(null);
      const seenDocIds = Object.create(null);
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

    // Download a file directly as base64 for native synchronization
    if (action === "download_file") {
      const fileId = payload.fileId;
      if (!fileId) return createJsonResponse({ status: "error", message: "Missing fileId" }, 400);

      const sheet = getOrCreateSheet(SHEET_NAME);
      if (findRowByPayload(sheet, { fileId: fileId }) < 2) {
        return createJsonResponse({ status: "error", message: "File is outside this cabinet" });
      }
      const file = getCabinetFile_(fileId);
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
    return createJsonResponse({ status: "error", message: "Could not read cabinet data" });
  }
}

function findRowByPayload(sheet, payload) {
  if (!payload) return -1;
  const data = sheet.getDataRange().getValues();
  if (data.length <= 1) return -1;

  const docId = payload.docId ? String(payload.docId).trim() : "";
  const fileId = payload.fileId ? String(payload.fileId).trim() : "";
  if (fileId) {
    for (let r = data.length - 1; r >= 1; r--) {
      if (String(data[r][10]).trim() === fileId) return r + 1;
    }
  } else if (docId && !docId.startsWith("row_")) {
    for (let r = data.length - 1; r >= 1; r--) {
      if (String(data[r][12]).trim() === docId) return r + 1;
    }
  }

  return -1;
}

function getColumnIndexByName(sheet, colName) {
  if (!sheet || sheet.getLastRow() === 0) return -1;
  const headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
  const target = colName.trim().toLowerCase();
  for (let i = 0; i < headers.length; i++) {
    if (String(headers[i]).trim().toLowerCase() === target) {
      return i + 1; // 1-based index
    }
  }
  return -1;
}

function getOrCreateFolder(folderName) {
  const properties = PropertiesService.getScriptProperties();
  const folderId = properties.getProperty("CABINET_FOLDER_ID");
  if (folderId) return DriveApp.getFolderById(folderId);
  const folders = DriveApp.getFoldersByName(folderName);
  const folder = folders.hasNext() ? folders.next() : DriveApp.createFolder(folderName);
  if (folders.hasNext()) throw new Error("Set CABINET_FOLDER_ID to the intended cabinet folder");
  properties.setProperty("CABINET_FOLDER_ID", folder.getId());
  return folder;
}

function getOrCreateSheet(sheetName) {
  const properties = PropertiesService.getScriptProperties();
  const spreadsheetId = properties.getProperty("CABINET_SPREADSHEET_ID");
  let spreadsheet;
  if (spreadsheetId) {
    spreadsheet = SpreadsheetApp.openById(spreadsheetId);
  } else {
    const files = DriveApp.getFilesByName(sheetName);
    spreadsheet = files.hasNext() ? SpreadsheetApp.open(files.next()) : SpreadsheetApp.create(sheetName);
    if (files.hasNext()) throw new Error("Set CABINET_SPREADSHEET_ID to the intended ledger");
    properties.setProperty("CABINET_SPREADSHEET_ID", spreadsheet.getId());
  }
  const sheetId = properties.getProperty("CABINET_SHEET_ID");
  const sheet = sheetId ? spreadsheet.getSheets().find(tab => String(tab.getSheetId()) === sheetId)
    : spreadsheet.getActiveSheet();
  if (!sheet) throw new Error("Cabinet ledger tab is missing");
  if (!sheetId) properties.setProperty("CABINET_SHEET_ID", String(sheet.getSheetId()));

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
