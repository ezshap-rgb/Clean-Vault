import Foundation

struct DriveRemoteFile: Decodable {
    struct Owner: Decodable {
        let emailAddress: String?
    }

    let id: String
    let name: String?
    let mimeType: String?
    let description: String?
    let properties: [String: String]?
    let webViewLink: String?
    let createdTime: String?
    let modifiedTime: String?
    let owners: [Owner]?
}

enum GoogleDriveError: LocalizedError {
    case invalidURL
    case httpError(Int, String)
    case folderNotFound(String)
    case decodingError(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Invalid Google Drive request URL."
        case .httpError(let code, let message):
            return message.isEmpty ? "Google Drive HTTP \(code)" : message
        case .folderNotFound(let msg):
            return msg
        case .decodingError(let msg):
            return "Failed to parse Google Drive response: \(msg)"
        }
    }
}

final class GoogleDriveService {
    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 120
        self.session = URLSession(configuration: config)
    }

    // MARK: - Folder Discovery & Creation

    func findOrCreateAppFolder(token: String, targetOwnerEmail: String? = nil) async throws -> String {
        let query = "name = 'Clean Filing' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        components.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "fields", value: "files(id,name,owners)"),
            URLQueryItem(name: "supportsAllDrives", value: "true"),
            URLQueryItem(name: "includeItemsFromAllDrives", value: "true")
        ]

        guard let url = components.url else { throw GoogleDriveError.invalidURL }

        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await session.data(for: req)
        try ensureSuccess(data: data, response: response)

        struct FolderListResponse: Decodable {
            let files: [DriveRemoteFile]?
        }
        let decoded = try JSONDecoder().decode(FolderListResponse.self, from: data)
        let matchingFolders = decoded.files ?? []

        if !matchingFolders.isEmpty {
            if let ownerFilter = targetOwnerEmail?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
               !ownerFilter.isEmpty {
                for folder in matchingFolders {
                    let owners = folder.owners ?? []
                    if owners.contains(where: { ($0.emailAddress ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == ownerFilter }) {
                        return folder.id
                    }
                }
                throw GoogleDriveError.folderNotFound(
                    "Cabinet owned by '\(targetOwnerEmail ?? "")' not found. Please ensure they granted your email access."
                )
            }
            return matchingFolders[0].id
        }

        if let ownerFilter = targetOwnerEmail?.trimmingCharacters(in: .whitespacesAndNewlines), !ownerFilter.isEmpty {
            throw GoogleDriveError.folderNotFound(
                "No Clean Filing cabinet found from '\(ownerFilter)'. Please ensure they granted access."
            )
        }

        // Create "Clean Filing" folder
        guard let createURL = URL(string: "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true") else {
            throw GoogleDriveError.invalidURL
        }
        var createReq = URLRequest(url: createURL)
        createReq.httpMethod = "POST"
        createReq.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        createReq.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")

        let payload: [String: Any] = [
            "name": "Clean Filing",
            "mimeType": "application/vnd.google-apps.folder",
            "description": "Clean Filing Document Storage"
        ]
        createReq.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (createData, createRes) = try await session.data(for: createReq)
        try ensureSuccess(data: createData, response: createRes)

        struct IdResponse: Decodable { let id: String }
        return try JSONDecoder().decode(IdResponse.self, from: createData).id
    }

    // MARK: - List Files

    func listDocuments(token: String, folderId: String) async throws -> [DriveRemoteFile] {
        let query = "'\(folderId)' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'"
        var allFiles: [DriveRemoteFile] = []
        var pageToken: String?

        struct FileListResponse: Decodable {
            let nextPageToken: String?
            let files: [DriveRemoteFile]?
        }

        repeat {
            var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
            var items = [
                URLQueryItem(name: "q", value: query),
                URLQueryItem(name: "fields", value: "nextPageToken,files(id,name,mimeType,description,properties,webViewLink,createdTime,modifiedTime)"),
                URLQueryItem(name: "pageSize", value: "200"),
                URLQueryItem(name: "supportsAllDrives", value: "true"),
                URLQueryItem(name: "includeItemsFromAllDrives", value: "true")
            ]
            if let pageToken, !pageToken.isEmpty {
                items.append(URLQueryItem(name: "pageToken", value: pageToken))
            }
            components.queryItems = items

            guard let url = components.url else { throw GoogleDriveError.invalidURL }
            var req = URLRequest(url: url)
            req.httpMethod = "GET"
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

            let (data, response) = try await session.data(for: req)
            try ensureSuccess(data: data, response: response)

            let decoded = try JSONDecoder().decode(FileListResponse.self, from: data)
            if let files = decoded.files {
                allFiles.append(contentsOf: files)
            }
            pageToken = decoded.nextPageToken
        } while pageToken != nil && !(pageToken?.isEmpty ?? true)

        return allFiles
    }

    // MARK: - Upload Document

    func uploadDocument(
        token: String,
        folderId: String,
        fileURL: URL,
        docName: String,
        notes: String,
        mimeType: String,
        docId: String,
        isPinned: Bool,
        isTemporary: Bool = false
    ) async throws -> (id: String, webViewLink: String) {
        guard let url = URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,webViewLink&supportsAllDrives=true") else {
            throw GoogleDriveError.invalidURL
        }

        let boundary = "CleanFilingBoundary-\(UUID().uuidString)"
        let resolvedName = docName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? fileURL.lastPathComponent
            : docName.trimmingCharacters(in: .whitespacesAndNewlines)

        // Note: "vaultName" property key is retained strictly for wire-level interoperability with Android and Desktop Web Drive metadata.
        let metadata: [String: Any] = [
            "name": resolvedName,
            "parents": [folderId],
            "description": notes,
            "properties": [
                "docId": docId,
                "vaultName": resolvedName,
                "notes": notes,
                "isPinned": isPinned ? "true" : "false",
                "isTemporary": isTemporary ? "true" : "false",
                "client": "CleanFiling"
            ]
        ]

        let metadataData = try JSONSerialization.data(withJSONObject: metadata)
        let fileData = try Data(contentsOf: fileURL)

        var body = Data()
        body.appendString("--\(boundary)\r\n")
        body.appendString("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        body.append(metadataData)
        body.appendString("\r\n--\(boundary)\r\n")
        body.appendString("Content-Type: \(mimeType)\r\n\r\n")
        body.append(fileData)
        body.appendString("\r\n--\(boundary)--\r\n")

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.httpBody = body

        let (data, response) = try await session.data(for: req)
        try ensureSuccess(data: data, response: response)

        struct UploadResponse: Decodable {
            let id: String
            let webViewLink: String?
        }
        let decoded = try JSONDecoder().decode(UploadResponse.self, from: data)
        let link = decoded.webViewLink ?? "https://drive.google.com/file/d/\(decoded.id)/view"
        return (decoded.id, link)
    }

    // MARK: - Update Content & Properties

    func updateFileContent(token: String, fileId: String, fileURL: URL, mimeType: String) async throws {
        guard let url = URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(fileId)?uploadType=media&supportsAllDrives=true") else {
            throw GoogleDriveError.invalidURL
        }
        let fileData = try Data(contentsOf: fileURL)

        var req = URLRequest(url: url)
        req.httpMethod = "PATCH"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        req.httpBody = fileData

        let (data, response) = try await session.data(for: req)
        try ensureSuccess(data: data, response: response)
    }

    func updateFileProperties(token: String, fileId: String, name: String, properties: [String: String]) async throws {
        guard let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileId)?fields=id,properties&supportsAllDrives=true") else {
            throw GoogleDriveError.invalidURL
        }

        var payload: [String: Any] = [
            "name": name,
            "properties": properties
        ]
        if let notes = properties["notes"] {
            payload["description"] = notes
        }

        var req = URLRequest(url: url)
        req.httpMethod = "PATCH"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, response) = try await session.data(for: req)
        try ensureSuccess(data: data, response: response)
    }

    // MARK: - Download, Delete & Share

    func downloadFile(token: String, fileId: String, destURL: URL) async throws {
        guard let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileId)?alt=media&supportsAllDrives=true") else {
            throw GoogleDriveError.invalidURL
        }

        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await session.data(for: req)
        try ensureSuccess(data: data, response: response)
        try data.write(to: destURL, options: .atomic)
    }

    func deleteFile(token: String, fileId: String) async throws {
        guard let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileId)?supportsAllDrives=true") else {
            throw GoogleDriveError.invalidURL
        }

        var req = URLRequest(url: url)
        req.httpMethod = "DELETE"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await session.data(for: req)
        if let http = response as? HTTPURLResponse, http.statusCode == 404 {
            return
        }
        try ensureSuccess(data: data, response: response)
    }

    func shareFolder(token: String, folderId: String, email: String) async throws {
        guard let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(folderId)/permissions?sendNotificationEmail=false&supportsAllDrives=true") else {
            throw GoogleDriveError.invalidURL
        }

        let payload: [String: Any] = [
            "role": "writer",
            "type": "user",
            "emailAddress": email.trimmingCharacters(in: .whitespacesAndNewlines)
        ]

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, response) = try await session.data(for: req)
        try ensureSuccess(data: data, response: response)
    }

    // MARK: - Helpers

    private func ensureSuccess(data: Data, response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { return }
        guard (200...299).contains(http.statusCode) else {
            let apiMsg = extractGoogleErrorMessage(from: data) ?? String(data: data, encoding: .utf8) ?? ""
            throw GoogleDriveError.httpError(http.statusCode, apiMsg)
        }
    }

    private func extractGoogleErrorMessage(from data: Data) -> String? {
        guard
            let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let error = obj["error"] as? [String: Any],
            let message = error["message"] as? String
        else { return nil }
        return message
    }
}

private extension Data {
    mutating func appendString(_ string: String) {
        if let data = string.data(using: .utf8) {
            append(data)
        }
    }
}
