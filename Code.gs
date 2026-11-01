/**
 * Clean Vault - Google Apps Script Backend
 * --------------------------------------------------------------------------
 * Handles secure storage of receipt & document photos, PDFs, and files
 * in Google Drive and structured records in Google Sheets.
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
  "Pinned"
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

    // 2. Action: Save Receipt / Document (supports JPEG, PNG, PDF, DOC, etc.)
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
      const row = [
        new Date().toISOString(),
        meta.date || new Date().toISOString().slice(0, 10),
        meta.vendor || "Unknown",
        meta.totalAmount !== undefined && meta.totalAmount !== null ? Number(meta.totalAmount) : 0,
        meta.currency || "USD",
        meta.category || "General",
        meta.itemsSummary || "",
        meta.notes || "",
        meta.rawOcr || "",
        fileUrl,
        fileId,
        meta.pinned ? "true" : "false"
      ];

      sheet.appendRow(row);
      const lastRow = sheet.getLastRow();

      return createJsonResponse({
        status: "success",
        fileUrl: fileUrl,
        fileId: fileId,
        rowId: lastRow
      });
    }

    // 3. Action: Toggle Pin Status
    if (action === "toggle_pin") {
      const rowId = parseInt(payload.rowId, 10);
      if (!rowId || rowId < 2) return createJsonResponse({ status: "error", message: "Invalid rowId" }, 400);

      const sheet = getOrCreateSheet(SHEET_NAME);
      if (rowId <= sheet.getLastRow()) {
        const currentVal = String(sheet.getRange(rowId, 12).getValue()).toLowerCase() === "true";
        const newVal = !currentVal;
        sheet.getRange(rowId, 12).setValue(newVal ? "true" : "false");
        return createJsonResponse({ status: "success", pinned: newVal, rowId: rowId });
      }
      return createJsonResponse({ status: "error", message: "Row not found" }, 404);
    }

    // 4. Action: Delete Receipt
    if (action === "delete_receipt") {
      const rowId = parseInt(payload.rowId, 10);
      if (!rowId || rowId < 2) return createJsonResponse({ status: "error", message: "Invalid rowId" }, 400);

      const sheet = getOrCreateSheet(SHEET_NAME);
      if (rowId <= sheet.getLastRow()) {
        const fileId = sheet.getRange(rowId, 11).getValue();
        if (fileId && payload.deleteDriveFile) {
          try { DriveApp.getFileById(fileId).setTrashed(true); } catch (e) {}
        }
        sheet.deleteRow(rowId);
        return createJsonResponse({ status: "success" });
      }
      return createJsonResponse({ status: "error", message: "Row not found" }, 404);
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
        record.pinned = String(record.pinned).toLowerCase() === "true";
        return record;
      });

      records.reverse();
      return createJsonResponse({ status: "success", records: records, count: records.length });
    }

    return createJsonResponse({ status: "error", message: "Unknown action" }, 400);
  } catch (err) {
    return createJsonResponse({ status: "error", message: err.toString() }, 500);
  }
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
    sheet.getRange(2, 4, 1000, 1).setNumberFormat("#,##0.00");
  } else if (sheet.getLastColumn() < HEADERS.length) {
    sheet.getRange(1, HEADERS.length).setValue("Pinned").setFontWeight("bold");
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
