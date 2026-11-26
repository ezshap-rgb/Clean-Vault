import Foundation

enum AppScreen: Hashable {
    case home
    case fileIn
    case essentials
    case temporary
    case cabinet
}

enum CloudSyncMode: String, Codable {
    case localOnly = "LOCAL_ONLY"
    case googleDrive = "GOOGLE_DRIVE"
}

struct FilingDocument: Identifiable, Codable, Equatable {
    var id: String
    var name: String
    var notes: String
    var fileName: String
    var mimeType: String
    var date: String
    var isPinned: Bool
    var isTemporary: Bool
    var timestamp: Int64
    var driveFileId: String?
    var driveFileUrl: String?
    var isSynced: Bool
    var lastModified: Int64
    var contentPending: Bool

    init(
        id: String = UUID().uuidString,
        name: String,
        notes: String = "",
        fileName: String,
        mimeType: String,
        date: String? = nil,
        isPinned: Bool = false,
        isTemporary: Bool = false,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        driveFileId: String? = nil,
        driveFileUrl: String? = nil,
        isSynced: Bool = false,
        lastModified: Int64? = nil,
        contentPending: Bool = false
    ) {
        self.id = id
        self.name = name
        self.notes = notes
        self.fileName = fileName
        self.mimeType = mimeType
        self.date = date ?? FilingDocument.defaultDateFormatter.string(from: Date())
        self.isPinned = isPinned
        self.isTemporary = isTemporary
        self.timestamp = timestamp
        self.driveFileId = driveFileId
        self.driveFileUrl = driveFileUrl
        self.isSynced = isSynced
        self.lastModified = lastModified ?? timestamp
        self.contentPending = contentPending
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        notes = try container.decodeIfPresent(String.self, forKey: .notes) ?? ""
        fileName = try container.decode(String.self, forKey: .fileName)
        mimeType = try container.decodeIfPresent(String.self, forKey: .mimeType) ?? "image/jpeg"
        date = try container.decodeIfPresent(String.self, forKey: .date) ?? ""
        isPinned = try container.decodeIfPresent(Bool.self, forKey: .isPinned) ?? false
        isTemporary = try container.decodeIfPresent(Bool.self, forKey: .isTemporary) ?? false
        timestamp = try container.decodeIfPresent(Int64.self, forKey: .timestamp) ?? 0
        driveFileId = try container.decodeIfPresent(String.self, forKey: .driveFileId)
        driveFileUrl = try container.decodeIfPresent(String.self, forKey: .driveFileUrl)
        isSynced = try container.decodeIfPresent(Bool.self, forKey: .isSynced) ?? false
        lastModified = try container.decodeIfPresent(Int64.self, forKey: .lastModified) ?? timestamp
        contentPending = try container.decodeIfPresent(Bool.self, forKey: .contentPending) ?? false
    }

    var fileTypeIcon: String {
        let lowerName = fileName.lowercased()
        let lowerMime = mimeType.lowercased()
        if lowerName.hasSuffix(".txt") || lowerMime.hasPrefix("text/") {
            return "📝"
        }
        if lowerName.hasSuffix(".pdf") || lowerMime.contains("pdf") {
            return "📄"
        }
        return "🖼️"
    }

    var isTextNote: Bool {
        fileName.lowercased().hasSuffix(".txt") || mimeType.lowercased().hasPrefix("text/")
    }

    var isPDF: Bool {
        fileName.lowercased().hasSuffix(".pdf") || mimeType.lowercased().contains("pdf")
    }

    var isImage: Bool {
        mimeType.lowercased().hasPrefix("image/")
    }

    var labelList: [String] {
        notes
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private static let defaultDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()
}

struct TrashedFilingDocument: Identifiable, Codable, Equatable {
    var id: String { document.id }
    var document: FilingDocument
    var trashedAt: Int64
}

struct CloudSyncStatus: Codable, Equatable {
    var lastSuccess: Int64
    var needsRetry: Bool

    static let empty = CloudSyncStatus(lastSuccess: 0, needsRetry: false)
}

struct SyncSummary {
    let downloaded: Int
    let uploaded: Int
    let total: Int
    let failed: Int
}
