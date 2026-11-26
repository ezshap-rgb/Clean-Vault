import Foundation
import SwiftUI

@MainActor
final class FilingRepository: ObservableObject {
    @Published private(set) var documents: [FilingDocument] = []
    @Published private(set) var trashedDocuments: [TrashedFilingDocument] = []
    @Published private(set) var isSyncing: Bool = false
    @Published private(set) var cloudSyncStatus: CloudSyncStatus = .empty
    @Published private(set) var activeCloudMode: CloudSyncMode = .localOnly
    @Published private(set) var googleEmail: String = ""
    @Published private(set) var googleFolderId: String = ""
    @Published private(set) var cabinetOwnerEmail: String = ""
    @Published var toastMessage: String?

    private let driveService = GoogleDriveService()
    private let authManager: GoogleAuthManager
    private let defaults = UserDefaults.standard
    private let docsDir: URL

    private enum Keys {
        static let documentsList = "CLEAN_FILING_DOCUMENTS_LIST"
        static let trashedList = "CLEAN_FILING_TRASHED_LIST"
        static let deletedIds = "CLEAN_FILING_DELETED_IDS"
        static let activeCloudMode = "CLEAN_FILING_CLOUD_MODE"
        static let googleEmail = "CLEAN_FILING_GOOGLE_EMAIL"
        static let googleFolderId = "CLEAN_FILING_FOLDER_ID"
        static let ownerEmail = "CLEAN_FILING_OWNER_EMAIL"
        static let syncPrefix = "CLEAN_FILING_SYNC_"
    }

    var hasCloudConnection: Bool {
        activeCloudMode == .googleDrive && !googleEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    init(authManager: GoogleAuthManager) {
        self.authManager = authManager

        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dir = appSupport.appendingPathComponent("CleanFiling/documents", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.docsDir = dir

        loadState()
    }

    func showToast(_ message: String) {
        toastMessage = message
        Task {
            try? await Task.sleep(nanoseconds: 2_800_000_000)
            if toastMessage == message {
                toastMessage = nil
            }
        }
    }

    func fileURL(for doc: FilingDocument) -> URL {
        docsDir.appendingPathComponent(doc.fileName)
    }

    func fileExists(for doc: FilingDocument) -> Bool {
        !doc.fileName.isEmpty && FileManager.default.fileExists(atPath: fileURL(for: doc).path)
    }

    // MARK: - Persistence & Mode State

    private func loadState() {
        googleEmail = defaults.string(forKey: Keys.googleEmail) ?? ""
        googleFolderId = defaults.string(forKey: Keys.googleFolderId) ?? ""
        cabinetOwnerEmail = defaults.string(forKey: Keys.ownerEmail) ?? ""

        if let rawMode = defaults.string(forKey: Keys.activeCloudMode),
           let parsed = CloudSyncMode(rawValue: rawMode) {
            activeCloudMode = parsed
        } else {
            activeCloudMode = googleEmail.isEmpty ? .localOnly : .googleDrive
        }

        if let data = defaults.data(forKey: Keys.documentsList),
           let decoded = try? JSONDecoder().decode([FilingDocument].self, from: data) {
            documents = sortAndDeduplicate(decoded)
        }

        if let data = defaults.data(forKey: Keys.trashedList),
           let decoded = try? JSONDecoder().decode([TrashedFilingDocument].self, from: data) {
            trashedDocuments = decoded.sorted { $0.trashedAt > $1.trashedAt }
        }

        cloudSyncStatus = loadSyncStatus()
    }

    private func saveDocuments(_ list: [FilingDocument]) {
        let cleaned = sortAndDeduplicate(list)
        documents = cleaned
        if let data = try? JSONEncoder().encode(cleaned) {
            defaults.set(data, forKey: Keys.documentsList)
        }
    }

    private func saveTrash(_ list: [TrashedFilingDocument]) {
        let sorted = list.sorted { $0.trashedAt > $1.trashedAt }
        trashedDocuments = sorted
        if let data = try? JSONEncoder().encode(sorted) {
            defaults.set(data, forKey: Keys.trashedList)
        }
    }

    private func getDeletedIds() -> Set<String> {
        Set(defaults.stringArray(forKey: Keys.deletedIds) ?? [])
    }

    private func saveDeletedIds(_ ids: Set<String>) {
        defaults.set(Array(ids), forKey: Keys.deletedIds)
    }

    private func syncStatusKey() -> String {
        switch activeCloudMode {
        case .googleDrive: return "\(Keys.syncPrefix)drive_\(googleEmail)"
        case .localOnly: return "\(Keys.syncPrefix)local"
        }
    }

    private func loadSyncStatus() -> CloudSyncStatus {
        let key = syncStatusKey()
        let lastSuccess = Int64(defaults.integer(forKey: "\(key)_last_success"))
        let lastSuccess64 = (defaults.object(forKey: "\(key)_last_success") as? Int64) ?? lastSuccess
        let retry = defaults.bool(forKey: "\(key)_retry")
        return CloudSyncStatus(lastSuccess: lastSuccess64, needsRetry: retry)
    }

    private func updateSyncStatus(success: Bool) {
        let key = syncStatusKey()
        defaults.set(!success, forKey: "\(key)_retry")
        if success {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            defaults.set(now, forKey: "\(key)_last_success")
        }
        cloudSyncStatus = loadSyncStatus()
    }

    // MARK: - Account & Settings Actions

    func setActiveCloudMode(_ mode: CloudSyncMode) {
        activeCloudMode = mode
        defaults.set(mode.rawValue, forKey: Keys.activeCloudMode)
        cloudSyncStatus = loadSyncStatus()
    }

    func setGoogleAccount(email: String?, folderId: String? = nil) {
        if let email, !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let cleanEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
            googleEmail = cleanEmail
            defaults.set(cleanEmail, forKey: Keys.googleEmail)
            if let folderId, !folderId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let cleanFolder = folderId.trimmingCharacters(in: .whitespacesAndNewlines)
                googleFolderId = cleanFolder
                defaults.set(cleanFolder, forKey: Keys.googleFolderId)
            }
            setActiveCloudMode(.googleDrive)
        } else {
            googleEmail = ""
            googleFolderId = ""
            defaults.removeObject(forKey: Keys.googleEmail)
            defaults.removeObject(forKey: Keys.googleFolderId)
            setActiveCloudMode(.localOnly)
        }
    }

    func setCabinetOwnerEmail(_ email: String) {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed != cabinetOwnerEmail {
            cabinetOwnerEmail = trimmed
            defaults.set(trimmed, forKey: Keys.ownerEmail)
            googleFolderId = ""
            defaults.removeObject(forKey: Keys.googleFolderId)
        }
    }

    func shareGoogleDriveCabinet(partnerEmail: String) async -> Result<String, Error> {
        let trimmedPartner = partnerEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedPartner.isEmpty else {
            return .failure(NSError(domain: "CleanFiling", code: 1, userInfo: [NSLocalizedDescriptionKey: "Enter partner Gmail address"]))
        }
        do {
            let token = try await authManager.freshAccessToken()
            var folderId = googleFolderId
            if folderId.isEmpty {
                folderId = try await driveService.findOrCreateAppFolder(token: token, targetOwnerEmail: nil)
                googleFolderId = folderId
                defaults.set(folderId, forKey: Keys.googleFolderId)
            }
            try await driveService.shareFolder(token: token, folderId: folderId, email: trimmedPartner)
            return .success(folderId)
        } catch {
            return .failure(error)
        }
    }

    // MARK: - Local Document CRUD

    @discardableResult
    func saveFileToCabinet(
        name: String,
        notes: String,
        isPinned: Bool,
        isTemporary: Bool = false,
        sourceURL: URL,
        mimeType: String
    ) throws -> FilingDocument {
        let ext = sourceURL.pathExtension.isEmpty
            ? (mimeType.contains("pdf") ? "pdf" : (mimeType.contains("text") ? "txt" : "jpg"))
            : sourceURL.pathExtension
        let targetFileName = "Filing_\(UUID().uuidString).\(ext)"
        let targetURL = docsDir.appendingPathComponent(targetFileName)

        let accessing = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if accessing { sourceURL.stopAccessingSecurityScopedResource() }
        }
        if FileManager.default.fileExists(atPath: targetURL.path) {
            try FileManager.default.removeItem(at: targetURL)
        }
        try FileManager.default.copyItem(at: sourceURL, to: targetURL)

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let resolvedName = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? targetURL.deletingPathExtension().lastPathComponent
            : name.trimmingCharacters(in: .whitespacesAndNewlines)

        let doc = FilingDocument(
            name: resolvedName,
            notes: notes.trimmingCharacters(in: .whitespacesAndNewlines),
            fileName: targetFileName,
            mimeType: mimeType,
            isPinned: isPinned,
            isTemporary: isTemporary,
            timestamp: now,
            isSynced: false,
            lastModified: now
        )

        var list = documents
        list.insert(doc, at: 0)
        saveDocuments(list)
        return doc
    }

    func createTempNoteFile(title: String, body: String) throws -> (url: URL, suggestedName: String) {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd_HHmm"
        let stamp = formatter.string(from: Date())

        let cleanTitle = FilenameSanitizer.sanitizeNoteTitle(title)
        let fileName = cleanTitle.isEmpty ? "Note_\(stamp).txt" : "\(cleanTitle).txt"
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedBody = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let fullText = trimmedTitle.isEmpty ? trimmedBody : "\(trimmedTitle)\n\n\(trimmedBody)"

        try fullText.write(to: tempURL, atomically: true, encoding: .utf8)
        let suggestedName = cleanTitle.isEmpty ? "Note \(stamp)" : cleanTitle
        return (tempURL, suggestedName)
    }

    func togglePin(docId: String) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let updated = documents.map { doc -> FilingDocument in
            guard doc.id == docId else { return doc }
            var copy = doc
            copy.isPinned.toggle()
            copy.isSynced = false
            copy.lastModified = now
            return copy
        }
        saveDocuments(updated)
    }

    func toggleTemporary(docId: String) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let updated = documents.map { doc -> FilingDocument in
            guard doc.id == docId else { return doc }
            var copy = doc
            copy.isTemporary.toggle()
            copy.isSynced = false
            copy.lastModified = now
            return copy
        }
        saveDocuments(updated)
    }

    func renameDocument(docId: String, newName: String, newNotes: String) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let cleanName = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanNotes = newNotes.trimmingCharacters(in: .whitespacesAndNewlines)
        let updated = documents.map { doc -> FilingDocument in
            guard doc.id == docId else { return doc }
            var copy = doc
            if !cleanName.isEmpty { copy.name = cleanName }
            copy.notes = cleanNotes
            copy.isSynced = false
            copy.lastModified = now
            return copy
        }
        saveDocuments(updated)
    }

    func readNoteText(for doc: FilingDocument) -> String {
        let url = fileURL(for: doc)
        return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
    }

    func updateNote(docId: String, name: String, notes: String, newContent: String) {
        guard let index = documents.firstIndex(where: { $0.id == docId }) else { return }
        var target = documents[index]
        let url = fileURL(for: target)
        try? newContent.write(to: url, atomically: true, encoding: .utf8)

        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanName.isEmpty { target.name = cleanName }
        target.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        target.isSynced = false
        target.contentPending = true
        target.lastModified = Int64(Date().timeIntervalSince1970 * 1000)

        var copy = documents
        copy[index] = target
        saveDocuments(copy)
    }

    // MARK: - Trash Operations

    func moveToTrash(docId: String) async -> Bool {
        guard let current = documents.first(where: { $0.id == docId }) else { return false }

        // Ensure recoverable local file exists before removing remote copy on next sync
        if !fileExists(for: current), let fileId = current.driveFileId, hasCloudConnection {
            if let token = try? await authManager.freshAccessToken() {
                let targetName = current.fileName.isEmpty ? "Filing_\(UUID().uuidString).bin" : current.fileName
                let destURL = docsDir.appendingPathComponent(targetName)
                try? await driveService.downloadFile(token: token, fileId: fileId, destURL: destURL)
            }
        }

        var deleted = getDeletedIds()
        if !(current.id.hasPrefix("row_") && current.driveFileId != nil) {
            deleted.insert(current.id)
        }
        if let driveId = current.driveFileId, !driveId.isEmpty {
            deleted.insert(driveId)
        }
        saveDeletedIds(deleted)

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var trash = trashedDocuments
        trash.insert(TrashedFilingDocument(document: current, trashedAt: now), at: 0)
        saveTrash(trash)
        saveDocuments(documents.filter { $0.id != docId })
        return true
    }

    func restoreFromTrash(docId: String) {
        guard let item = trashedDocuments.first(where: { $0.document.id == docId }) else { return }
        var deleted = getDeletedIds()
        deleted.remove(item.document.id)
        if let driveId = item.document.driveFileId {
            deleted.remove(driveId)
        }
        saveDeletedIds(deleted)

        var restored = item.document
        if restored.id.hasPrefix("row_") {
            restored.id = UUID().uuidString
        }
        restored.isSynced = false
        restored.lastModified = Int64(Date().timeIntervalSince1970 * 1000)

        saveTrash(trashedDocuments.filter { $0.document.id != docId })
        var docs = documents
        docs.insert(restored, at: 0)
        saveDocuments(docs)
    }

    func permanentlyDeleteFromTrash(docId: String) {
        if let item = trashedDocuments.first(where: { $0.document.id == docId }) {
            let url = fileURL(for: item.document)
            try? FileManager.default.removeItem(at: url)
        }
        saveTrash(trashedDocuments.filter { $0.document.id != docId })
    }

    func emptyTrash() {
        for item in trashedDocuments {
            let url = fileURL(for: item.document)
            try? FileManager.default.removeItem(at: url)
        }
        saveTrash([])
    }

    // MARK: - Google Drive Sync Engine

    @discardableResult
    func syncWithCloud(silent: Bool = false) async -> Result<SyncSummary, Error> {
        guard hasCloudConnection else {
            return .failure(NSError(domain: "CleanFiling", code: 0, userInfo: [NSLocalizedDescriptionKey: "Cloud sync is disabled (Local Only mode)."]))
        }
        guard !isSyncing else {
            return .success(SyncSummary(downloaded: 0, uploaded: 0, total: documents.count, failed: 0))
        }

        isSyncing = true
        defer { isSyncing = false }

        do {
            let token = try await authManager.freshAccessToken()

            // 1. Find or create "Clean Filing" folder (supporting linked owner cabinet)
            let owner = cabinetOwnerEmail.trimmingCharacters(in: .whitespacesAndNewlines)
            let folderId = try await driveService.findOrCreateAppFolder(
                token: token,
                targetOwnerEmail: owner.isEmpty ? nil : owner
            )
            googleFolderId = folderId
            defaults.set(folderId, forKey: Keys.googleFolderId)

            // 2. Fetch remote files and process deletions
            let remoteFiles = try await driveService.listDocuments(token: token, folderId: folderId)
            let deletedIds = getDeletedIds()

            var remoteMap: [String: DriveRemoteFile] = [:]
            for rf in remoteFiles {
                let props = rf.properties ?? [:]
                let propDocId = props["docId"] ?? ""
                let effectiveKey = propDocId.isEmpty ? rf.id : propDocId

                if deletedIds.contains(rf.id) || (!propDocId.isEmpty && deletedIds.contains(propDocId)) {
                    try await driveService.deleteFile(token: token, fileId: rf.id)
                    continue
                }
                remoteMap[effectiveKey] = rf
            }

            var localList = documents
            var addedCount = 0
            var uploadedCount = 0
            var failedIds = Set<String>()

            // 3. Download missing remote files & reconcile edits
            for (key, rf) in remoteMap {
                let fId = rf.id
                let rName = rf.name ?? "Document"
                let rDesc = rf.description ?? ""
                let rMime = rf.mimeType ?? "application/pdf"
                let rUrl = rf.webViewLink ?? "https://drive.google.com/file/d/\(fId)/view"
                let props = rf.properties ?? [:]
                let propDocId = (props["docId"]?.isEmpty == false) ? props["docId"]! : key
                let propNotes = props["notes"] ?? rDesc
                let propPinned = (props["isPinned"] ?? "false").lowercased() == "true"
                let propTemp = (props["isTemporary"] ?? "false").lowercased() == "true"
                let remoteTimestamp = parseTimestamp(rf.createdTime, fallback: rf.modifiedTime)

                if let existingIndex = localList.firstIndex(where: {
                    $0.driveFileId == fId || $0.id == propDocId || ($0.driveFileUrl != nil && $0.driveFileUrl == rUrl)
                }) {
                    var existing = localList[existingIndex]

                    if !fileExists(for: existing) {
                        let ext = extensionForMime(rMime)
                        let fallbackFile = existing.fileName.isEmpty ? "doc.\(ext)" : existing.fileName
                        let targetName = "Filing_\(Int64(Date().timeIntervalSince1970 * 1000))_\(fallbackFile)"
                        let targetURL = docsDir.appendingPathComponent(targetName)
                        do {
                            try await driveService.downloadFile(token: token, fileId: fId, destURL: targetURL)
                            existing.fileName = targetName
                        } catch {
                            try? FileManager.default.removeItem(at: targetURL)
                            failedIds.insert(existing.id)
                        }
                    }

                    let hasUnsyncedLocalEdits = !existing.isSynced
                    let finalPinned = hasUnsyncedLocalEdits ? existing.isPinned : propPinned
                    let finalTemp = hasUnsyncedLocalEdits ? existing.isTemporary : propTemp
                    // Note: "vaultName" is read/written strictly for cross-platform interoperability with Android & Web.
                    let propVaultName = (props["vaultName"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    let remoteName: String = {
                        if !propVaultName.isEmpty { return propVaultName }
                        if rName == existing.fileName { return existing.name }
                        return stripExtension(rName)
                    }()

                    let finalName = hasUnsyncedLocalEdits ? existing.name : remoteName
                    let finalNotes = hasUnsyncedLocalEdits ? existing.notes : propNotes

                    var metadataSynced = true
                    if hasUnsyncedLocalEdits && !(finalName == remoteName && finalNotes == propNotes && finalPinned == propPinned && finalTemp == propTemp) {
                        do {
                            try await driveService.updateFileProperties(
                                token: token,
                                fileId: fId,
                                name: finalName,
                                properties: [
                                    "vaultName": finalName,
                                    "notes": finalNotes,
                                    "isPinned": finalPinned ? "true" : "false",
                                    "isTemporary": finalTemp ? "true" : "false"
                                ]
                            )
                        } catch {
                            metadataSynced = false
                        }
                    }

                    var contentSynced = true
                    if existing.contentPending {
                        let localURL = fileURL(for: existing)
                        if FileManager.default.fileExists(atPath: localURL.path) {
                            do {
                                try await driveService.updateFileContent(
                                    token: token,
                                    fileId: fId,
                                    fileURL: localURL,
                                    mimeType: existing.mimeType
                                )
                            } catch {
                                contentSynced = false
                            }
                        } else {
                            contentSynced = false
                        }
                    }

                    if !metadataSynced || !contentSynced {
                        failedIds.insert(existing.id)
                    }

                    existing.name = finalName
                    existing.notes = finalNotes
                    existing.isPinned = finalPinned
                    existing.isTemporary = finalTemp
                    existing.driveFileId = fId
                    existing.driveFileUrl = rUrl
                    existing.isSynced = metadataSynced && contentSynced
                    existing.contentPending = !contentSynced
                    if remoteTimestamp > 0 { existing.timestamp = remoteTimestamp }
                    localList[existingIndex] = existing
                } else {
                    let ext = extensionForMime(rMime)
                    let safeRemote = FilenameSanitizer.sanitizeNoteTitle(stripExtension(rName))
                    let safeFileBase = safeRemote.isEmpty ? "doc" : safeRemote
                    let targetName = "Filing_\(Int64(Date().timeIntervalSince1970 * 1000))_\(safeFileBase).\(ext)"
                    let targetURL = docsDir.appendingPathComponent(targetName)

                    var finalFileName = targetName
                    var syncedOk = true
                    do {
                        try await driveService.downloadFile(token: token, fileId: fId, destURL: targetURL)
                    } catch {
                        try? FileManager.default.removeItem(at: targetURL)
                        finalFileName = ""
                        syncedOk = false
                        failedIds.insert(propDocId)
                    }

                    let propVaultName = (props["vaultName"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    let displayName = propVaultName.isEmpty ? stripExtension(rName) : propVaultName

                    let newDoc = FilingDocument(
                        id: propDocId,
                        name: displayName,
                        notes: propNotes,
                        fileName: finalFileName,
                        mimeType: rMime,
                        isPinned: propPinned,
                        isTemporary: propTemp,
                        timestamp: remoteTimestamp,
                        driveFileId: fId,
                        driveFileUrl: rUrl,
                        isSynced: syncedOk,
                        lastModified: remoteTimestamp
                    )
                    localList.append(newDoc)
                    addedCount += 1
                }
            }

            // 4. Upload local-only documents not yet in Google Drive
            var finalDocs: [FilingDocument] = []
            for var doc in localList {
                if deletedIds.contains(doc.id) || (doc.driveFileId != nil && deletedIds.contains(doc.driveFileId!)) {
                    try? FileManager.default.removeItem(at: fileURL(for: doc))
                    continue
                }

                let existsInDrive = remoteMap.values.contains { rf in
                    let propDocId = rf.properties?["docId"]
                    return doc.driveFileId == rf.id || (propDocId != nil && propDocId == doc.id)
                }

                if !existsInDrive {
                    let localURL = fileURL(for: doc)
                    if FileManager.default.fileExists(atPath: localURL.path) {
                        do {
                            let (fId, link) = try await driveService.uploadDocument(
                                token: token,
                                folderId: folderId,
                                fileURL: localURL,
                                docName: doc.name,
                                notes: doc.notes,
                                mimeType: doc.mimeType,
                                docId: doc.id,
                                isPinned: doc.isPinned,
                                isTemporary: doc.isTemporary
                            )
                            doc.driveFileId = fId
                            doc.driveFileUrl = link
                            doc.isSynced = true
                            doc.contentPending = false
                            uploadedCount += 1
                            finalDocs.append(doc)
                        } catch {
                            failedIds.insert(doc.id)
                            doc.isSynced = false
                            finalDocs.append(doc)
                        }
                    } else {
                        failedIds.insert(doc.id)
                        doc.isSynced = false
                        finalDocs.append(doc)
                    }
                } else {
                    finalDocs.append(doc)
                }
            }

            saveDocuments(finalDocs)
            let summary = SyncSummary(downloaded: addedCount, uploaded: uploadedCount, total: finalDocs.count, failed: failedIds.count)
            updateSyncStatus(success: summary.failed == 0)
            return .success(summary)
        } catch {
            updateSyncStatus(success: false)
            if !silent {
                showToast(error.localizedDescription)
            }
            return .failure(error)
        }
    }

    // MARK: - Helpers

    private func sortAndDeduplicate(_ raw: [FilingDocument]) -> [FilingDocument] {
        let deleted = getDeletedIds()
        let valid = raw.filter { doc in
            !deleted.contains(doc.id) && (doc.driveFileId == nil || !deleted.contains(doc.driveFileId!))
        }

        var map: [String: FilingDocument] = [:]
        var orderedKeys: [String] = []

        for doc in valid {
            let key: String
            if let driveId = doc.driveFileId, !driveId.isEmpty {
                key = "file_\(driveId)"
            } else if !doc.fileName.isEmpty {
                key = "path_\(doc.fileName)"
            } else {
                key = "id_\(doc.id)"
            }

            if let existing = map[key] {
                let winner = doc.lastModified >= existing.lastModified ? doc : existing
                let loser = doc.lastModified >= existing.lastModified ? existing : doc
                var merged = winner
                if winner.isSynced != loser.isSynced {
                    merged.isPinned = !winner.isSynced ? winner.isPinned : loser.isPinned
                    merged.isTemporary = !winner.isSynced ? winner.isTemporary : loser.isTemporary
                }
                merged.driveFileId = winner.driveFileId ?? loser.driveFileId
                merged.driveFileUrl = winner.driveFileUrl ?? loser.driveFileUrl
                if !fileExists(for: winner) && !loser.fileName.isEmpty {
                    merged.fileName = loser.fileName
                }
                if winner.timestamp <= 0 {
                    merged.timestamp = loser.timestamp
                }
                map[key] = merged
            } else {
                map[key] = doc
                orderedKeys.append(key)
            }
        }

        return orderedKeys.compactMap { map[$0] }.sorted { lhs, rhs in
            let lt = lhs.timestamp > 0 ? lhs.timestamp : lhs.lastModified
            let rt = rhs.timestamp > 0 ? rhs.timestamp : rhs.lastModified
            if lt != rt { return lt > rt }
            if lhs.date != rhs.date { return lhs.date > rhs.date }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    private func parseTimestamp(_ raw: String?, fallback: String?) -> Int64 {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let isoNoFrac = ISO8601DateFormatter()
        isoNoFrac.formatOptions = [.withInternetDateTime]

        for candidate in [raw, fallback].compactMap({ $0?.trimmingCharacters(in: .whitespacesAndNewlines) }) where !candidate.isEmpty {
            if let num = Int64(candidate), num > 1_000_000_000 {
                return num
            }
            if let date = iso.date(from: candidate) ?? isoNoFrac.date(from: candidate) {
                return Int64(date.timeIntervalSince1970 * 1000)
            }
        }
        return Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func extensionForMime(_ mime: String) -> String {
        let lower = mime.lowercased()
        if lower.contains("pdf") { return "pdf" }
        if lower.contains("text") { return "txt" }
        if lower.contains("png") { return "png" }
        return "jpg"
    }

    private func stripExtension(_ name: String) -> String {
        let url = URL(fileURLWithPath: name)
        let base = url.deletingPathExtension().lastPathComponent
        return base.isEmpty ? name : base
    }
}
