/**
 * Clean Vault - Google Apps Script Backend
 * --------------------------------------------------------------------------
 * Handles secure storage of receipt photos in Google Drive and structured
 * ledger records in Google Sheets.
 *
 * Deployment Instructions:
 * 1. Go to https://script.google.com and click "New project"
 * 2. Name the project "Clean Vault Backend"
 * 3. Replace all code in Code.gs with this file
 * 4. Click "Deploy" > "New deployment"
 * 5. Select type: "Web app" (gear icon)
 * 6. Set Description: "Production v1"
 * 7. Set Execute as: "Me (<your_email>)"
 * 8. Set Who has access: "Anyone" (Required so your mobile PWA can send data)
 * 9. Click "Deploy", authorize Google permissions, and copy the Web App URL (/exec)
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
  "File ID"
];

/**
 * Handle incoming POST requests from the Clean Vault PWA
 */
function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return createJsonResponse({ status: "error", message: "Missing POST payload" }, 400);
    }

    let payload;
    try {
      payload = JSON.parse(e.postData.contents);
    } catch (parseErr) {
      return createJsonResponse({ status: "error", message: "Invalid JSON payload: " + parseErr.message }, 400);
    }

    const action = payload.action || "save_receipt";

    // 1. Action: Test Connection
    if (action === "test_connection" || action === "ping") {
      const folder = getOrCreateFolder(FOLDER_NAME);
      const sheet = getOrCreateSheet(SHEET_NAME);
      return createJsonResponse({
        status: "success",
        message: "Clean Vault backend connection verified!",
        folderName: FOLDER_NAME,
        folderUrl: folder.getUrl(),
        sheetName: SHEET_NAME,
        sheetUrl: sheet.getParent().getUrl(),
        timestamp: new Date().toISOString()
      });
    }

    // 2. Action: Save Receipt
    if (action === "save_receipt") {
      const folder = getOrCreateFolder(FOLDER_NAME);
      const sheet = getOrCreateSheet(SHEET_NAME);

      let fileUrl = "";
      let fileId = "";

      // Decode Base64 Image if provided
      if (payload.imageBase64) {
        const rawBase64 = payload.imageBase64.replace(/^data:image\/\w+;base64,/, "");
        const decodedBytes = Utilities.base64Decode(rawBase64);
        const meta = payload.metadata || {};
        const safeVendor = (meta.vendor || "Doc").replace(/[^a-zA-Z0-9_-]/g, "_");
        const safeDate = meta.date || new Date().toISOString().slice(0, 10);
        const fileName = payload.fileName || `Vault_${safeDate}_${safeVendor}_${Date.now()}.jpg`;

        const blob = Utilities.newBlob(decodedBytes, "image/jpeg", fileName);
        const file = folder.createFile(blob);
        
        // Make readable with link so user can open directly from mobile app
        try {
          file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
        } catch (shareErr) {
          // If domain restrictions prevent public link, falls back to private domain link
        }

        fileUrl = file.getUrl();
        fileId = file.getId();
      }

      // Append row to Google Sheets
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
        fileId
      ];

      sheet.appendRow(row);
      const lastRow = sheet.getLastRow();

      return createJsonResponse({
        status: "success",
        message: "Receipt stored in Clean Vault",
        fileUrl: fileUrl,
        fileId: fileId,
        rowId: lastRow
      });
    }

    // 3. Action: Delete Receipt
    if (action === "delete_receipt") {
      const rowId = parseInt(payload.rowId, 10);
      if (!rowId || rowId < 2) {
        return createJsonResponse({ status: "error", message: "Invalid rowId" }, 400);
      }

      const sheet = getOrCreateSheet(SHEET_NAME);
      if (rowId <= sheet.getLastRow()) {
        const fileId = sheet.getRange(rowId, 11).getValue();
        if (fileId && payload.deleteDriveFile) {
          try {
            DriveApp.getFileById(fileId).setTrashed(true);
          } catch (fileErr) {
            console.warn("Could not delete Drive file: " + fileErr);
          }
        }
        sheet.deleteRow(rowId);
        return createJsonResponse({ status: "success", message: "Receipt removed from ledger" });
      } else {
        return createJsonResponse({ status: "error", message: "Row not found" }, 404);
      }
    }

    return createJsonResponse({ status: "error", message: "Unrecognized action: " + action }, 400);

  } catch (err) {
    return createJsonResponse({ status: "error", message: err.toString() }, 500);
  }
}

/**
 * Handle incoming GET requests (Sync & Ledger queries)
 */
function doGet(e) {
  try {
    const action = (e && e.parameter && e.parameter.action) || "get_records";

    if (action === "ping" || action === "test") {
      return createJsonResponse({
        status: "success",
        message: "Clean Vault Backend Online",
        timestamp: new Date().toISOString()
      });
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
        return record;
      });

      // Return reverse chronological by default (newest first)
      records.reverse();

      return createJsonResponse({ status: "success", records: records, count: records.length });
    }

    return createJsonResponse({ status: "error", message: "Unknown action: " + action }, 400);

  } catch (err) {
    return createJsonResponse({ status: "error", message: err.toString() }, 500);
  }
}

/**
 * Helper to retrieve or automatically initialize the target Google Drive Folder
 */
function getOrCreateFolder(folderName) {
  const folders = DriveApp.getFoldersByName(folderName);
  if (folders.hasNext()) {
    return folders.next();
  }
  return DriveApp.createFolder(folderName);
}

/**
 * Helper to retrieve or automatically initialize the target Google Sheet Ledger
 */
function getOrCreateSheet(sheetName) {
  const files = DriveApp.getFilesByName(sheetName);
  let spreadsheet;

  if (files.hasNext()) {
    spreadsheet = SpreadsheetApp.open(files.next());
  } else {
    spreadsheet = SpreadsheetApp.create(sheetName);
  }

  let sheet = spreadsheet.getActiveSheet();
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(HEADERS);
    const headerRange = sheet.getRange(1, 1, 1, HEADERS.length);
    headerRange.setFontWeight("bold");
    headerRange.setBackground("#151C33");
    headerRange.setFontColor("#FFFFFF");
    sheet.setFrozenRows(1);

    // Format Amount column as standard currency
    sheet.getRange(2, 4, 1000, 1).setNumberFormat("#,##0.00");
  }
  return sheet;
}

/**
 * Convert header string into camelCase property name
 */
function toCamelCase(str) {
  return str
    .replace(/\s(.)/g, function($1) { return $1.toUpperCase(); })
    .replace(/\s/g, '')
    .replace(/^(.)/, function($1) { return $1.toLowerCase(); });
}

/**
 * Helper to construct JSON response with correct headers
 */
function createJsonResponse(data, statusCode) {
  const output = ContentService.createTextOutput(JSON.stringify(data));
  output.setMimeType(ContentService.MimeType.JSON);
  return output;
}

