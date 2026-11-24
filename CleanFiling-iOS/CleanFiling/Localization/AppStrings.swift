import Foundation
import SwiftUI

final class LanguageManager: ObservableObject {
    private static let storageKey = "CLEAN_FILING_IS_HEBREW"

    @Published var isHebrew: Bool {
        didSet {
            UserDefaults.standard.set(isHebrew, forKey: Self.storageKey)
        }
    }

    init() {
        self.isHebrew = UserDefaults.standard.bool(forKey: Self.storageKey)
    }

    var layoutDirection: LayoutDirection {
        isHebrew ? .rightToLeft : .leftToRight
    }

    var strings: AppStrings {
        AppStrings(isHebrew: isHebrew)
    }

    func toggle() {
        isHebrew.toggle()
    }
}

struct AppStrings {
    let isHebrew: Bool

    // Brand & Home
    var appTitle: String { "Clean Filing" }
    var studioBadge: String { "STUDIO" }
    var brandSubtitle: String { isHebrew ? "מערכת תיוק מסמכים" : "Document filing system" }

    var drawerInTitle: String { isHebrew ? "קליטה" : "IN" }
    var drawerInSub: String { isHebrew ? "סריקה ותיוק" : "Scan & File" }
    var drawerEssentialsTitle: String { isHebrew ? "📌 חשובים" : "📌 ESSENTIALS" }
    func drawerEssentialsSub(_ count: Int) -> String {
        isHebrew ? "\(count) קבצים נעוצים" : "\(count) pinned files"
    }
    var drawerCabinetTitle: String { isHebrew ? "ארכיון" : "FILES" }
    func drawerCabinetSub(_ count: Int) -> String {
        isHebrew ? "\(count) מסמכים" : "\(count) documents"
    }

    // File Intake
    var fileHeader: String { isHebrew ? "תיוק מסמך" : "File" }
    var scanDocument: String { isHebrew ? "סריקת מסמך" : "Scan Document" }
    var scanSubtitle: String { isHebrew ? "זיהוי גבולות אוטומטי (PDF / JPG)" : "Auto-crop & multi-page scan" }
    var scannerUnavailable: String {
        isHebrew ? "סורק המסמכים אינו נתמך במכשיר זה — השתמש במצלמה." : "Document scanner is unavailable on this device — use Camera instead."
    }
    var quickPhotoHint: String { isHebrew ? "צילום" : "Photo" }
    var pickFileHint: String { isHebrew ? "קובץ" : "Files" }
    var pickGalleryHint: String { isHebrew ? "גלריה" : "Photos" }
    var quickNoteHint: String { isHebrew ? "פתק" : "Note" }
    var docNameLabel: String { isHebrew ? "שם המסמך" : "Document Name" }
    var docNamePlaceholder: String { isHebrew ? "לדוגמה: ביטוח בריאות, קבלה..." : "e.g. Health Insurance, Receipt..." }
    var labelsLabel: String { isHebrew ? "תגיות / תוויות" : "Labels / Tags" }
    var labelsPlaceholder: String { isHebrew ? "לדוגמה: מיסים, רפואי, דחוף..." : "e.g. taxes, medical, urgent..." }
    var pinToEssentials: String { isHebrew ? "נעץ לחשובים" : "Pin to Essentials" }
    var fileToCabinet: String { isHebrew ? "תייק לארכיון 🗄️" : "File to Cabinet 🗄️" }
    var cancelRetake: String { isHebrew ? "ביטול / צילום מחדש" : "Cancel / Retake" }
    var filedSuccessToast: String { isHebrew ? "תויק לארכיון! 🗄️" : "Filed to Cabinet! 🗄️" }

    // Essentials
    var essentialsHeader: String { isHebrew ? "📌 חשובים" : "📌 Essentials" }
    func pinnedCount(_ count: Int) -> String {
        isHebrew ? "\(count) נעוצים" : "\(count) pinned"
    }
    var noPinnedTitle: String { isHebrew ? "אין מסמכים חשובים עדיין" : "No pinned essentials yet" }
    var noPinnedSubtitle: String {
        isHebrew
            ? "הקש על סיכת הנעץ בכל מסמך בארכיון\nכדי לשמור אותו זמין כאן."
            : "Tap the pin icon on any file in your cabinet\nto keep it handy right here."
    }

    // Cabinet
    var cabinetHeader: String { isHebrew ? "ארכיון מסמכים" : "File Cabinet" }
    func filesCount(_ count: Int) -> String {
        isHebrew ? "\(count) מסמכים" : "\(count) files"
    }
    var searchPlaceholder: String { isHebrew ? "חיפוש מסמכים, תגיות..." : "Search documents, tags..." }
    var pinnedFilterPill: String { isHebrew ? "חשובים" : "Pinned" }
    var noDocumentsFound: String { isHebrew ? "לא נמצאו מסמכים" : "No documents found" }
    var syncingNow: String { isHebrew ? "מסנכרן..." : "Syncing…" }
    var syncIncomplete: String { isHebrew ? "סנכרון לא הושלם" : "Sync incomplete" }
    func pendingCount(_ count: Int) -> String {
        isHebrew ? "\(count) ממתינים לסנכרון" : "\(count) pending"
    }
    var readyToSync: String { isHebrew ? "מוכן לסנכרון" : "Ready to sync" }
    var retry: String { isHebrew ? "נסה שוב" : "Retry" }
    var syncNow: String { isHebrew ? "סנכרן כעת" : "Sync now" }
    func lastSynced(_ formattedDate: String) -> String {
        isHebrew ? "סונכרן לאחרונה \(formattedDate)" : "Last synced \(formattedDate)"
    }

    // Settings
    var settingsTitle: String { isHebrew ? "הגדרות וגיבוי ענן" : "Settings & Cloud Backup" }
    var appLanguageTitle: String { isHebrew ? "שפת ממשק" : "App Language" }
    var appLanguageSubtitle: String { isHebrew ? "עברית (RTL)" : "English (LTR)" }
    var switchLanguageBtn: String { isHebrew ? "Switch to English 🇺🇸" : "עבור לעברית 🇮🇱" }

    var localOnlyTitle: String { isHebrew ? "מקומי בלבד (לא מקוון)" : "Local Only (Offline)" }
    var localOnlyDesc: String {
        isHebrew
            ? "הכל נשמר בפרטיות במכשיר זה. אין צורך בחשבון ענן."
            : "Everything stays private on this iPhone. Zero cloud connection or accounts required."
    }
    var activeBadge: String { isHebrew ? "פעיל" : "ACTIVE" }
    var switchToLocalOnly: String { isHebrew ? "עבור למצב מקומי בלבד" : "Switch to Local Only" }

    var googleDriveTitle: String { isHebrew ? "ארכיון Google Drive" : "Google Drive Cabinet" }
    var googleDriveDesc: String {
        isHebrew
            ? "גיבוי וסנכרון אוטומטי לתיקיית 'Clean Filing' ב-Google Drive האישי שלך, משותף עם Android והמחשב."
            : "One-tap sync to a 'Clean Filing' folder in your personal Google Drive, shared seamlessly with Android & Desktop."
    }
    var clientNotConfiguredTitle: String { isHebrew ? "נדרשת הגדרת iOS Client ID" : "iOS Client ID Needed" }
    var clientNotConfiguredDesc: String {
        isHebrew
            ? "יש להוסיף Google iOS OAuth Client ID בקובץ GoogleAuth.xcconfig או ב-GitHub Secrets."
            : "Add your Google iOS OAuth Client ID in Config/GoogleAuth.xcconfig or IOS_GOOGLE_CLIENT_ID GitHub secret."
    }
    func accountLabel(_ email: String) -> String {
        isHebrew ? "חשבון: \(email)" : "Account: \(email)"
    }
    var folderLabel: String { isHebrew ? "תיקייה: Google Drive / Clean Filing" : "Folder: Google Drive / Clean Filing" }
    var syncPrivateDrive: String { isHebrew ? "סנכרן Drive" : "Sync Drive" }
    var disconnect: String { isHebrew ? "התנתק" : "Disconnect" }
    var signInWithGoogle: String { isHebrew ? "התחבר עם Google" : "Sign in with Google" }

    var sharePartnerTitle: String { isHebrew ? "שיתוף ארכיון עם שותף / בת זוג" : "Share Cabinet with Partner / Wife" }
    var sharePartnerDesc: String {
        isHebrew
            ? "הענק לשותף גישה ישירות באמצעות הזנת כתובת ה-Gmail שלו. הגישה ניתנת בשקט ברקע, ועליהם להזין את כתובתך תחת 'מקור הארכיון' במכשירם."
            : "Grant your partner access silently in the background (no Google emails sent). They just enter your Gmail under 'Cabinet Source / Owner' on their device to sync!"
    }
    var partnerEmailPlaceholder: String { isHebrew ? "Gmail של השותף (spouse@gmail.com)" : "Partner's Gmail (e.g. spouse@gmail.com)" }
    var grantAccess: String { isHebrew ? "הענק גישה 🤝" : "Grant Access 🤝" }
    var shareLink: String { isHebrew ? "שתף קישור" : "Share Link" }

    var ownerSectionTitle: String { isHebrew ? "מקור הארכיון / בעלים" : "Cabinet Source / Owner" }
    var ownerSectionDesc: String {
        isHebrew
            ? "השאר ריק כדי להשתמש בארכיון הפרטי שלך. אם שותף שיתף איתך ארכיון, הזן את כתובת ה-Gmail שלו כאן."
            : "Leave blank to use your own private cabinet. If a partner shared their cabinet with you, enter their Gmail address below to bind directly to theirs."
    }
    var ownerEmailPlaceholder: String { isHebrew ? "Gmail של הבעלים (owner@gmail.com)" : "Owner's Gmail (e.g. owner@gmail.com)" }
    var saveOwnerBtn: String { isHebrew ? "שמור מקור" : "Bind Owner" }
    var resetToMine: String { isHebrew ? "אפס לשלי" : "Reset to Mine" }
    func boundToOwner(_ email: String) -> String {
        isHebrew ? "מקושר אל: \(email)" : "Bound to: \(email)"
    }

    // Quick Note & Viewer
    var quickNoteTitle: String { isHebrew ? "📝 פתק מהיר" : "📝 Quick Note" }
    var noteTitleLabel: String { isHebrew ? "כותרת הפתק" : "Note Title" }
    var noteTitlePlaceholder: String { isHebrew ? "לדוגמה: קוד שער, פוליסה, רשימה..." : "e.g. Gate code, Policy #, List..." }
    var noteContentLabel: String { isHebrew ? "תוכן הפתק" : "Note Content" }
    var noteContentPlaceholder: String { isHebrew ? "הקלד את הפתק שלך כאן..." : "Type your note here..." }
    var attachNote: String { isHebrew ? "צרף פתק" : "Attach Note" }
    var noteEmptyToast: String { isHebrew ? "הפתק ריק" : "Note is empty" }
    var noteAttachedToast: String { isHebrew ? "הפתק צורף! 📝" : "Note attached! 📝" }
    var editNote: String { isHebrew ? "ערוך" : "Edit" }
    var saveChanges: String { isHebrew ? "שמור שינויים" : "Save Changes" }

    // Rename & Trash
    var renameTitle: String { isHebrew ? "שינוי שם ועריכת תגיות" : "Rename & Edit Labels" }
    var deleteConfirmTitle: String { isHebrew ? "להעביר לאשפה?" : "Move to Trash?" }
    func deleteConfirmMessage(_ name: String) -> String {
        isHebrew
            ? "המסמך \"\(name)\" יועבר לאשפה ותוכל לשחזר אותו משם."
            : "\"\(name)\" will be moved to Trash and can be restored from there."
    }
    var moveToTrash: String { isHebrew ? "העבר לאשפה" : "Move to Trash" }
    var trashTitle: String { isHebrew ? "🗑️ אשפה" : "🗑️ Trash" }
    var trashEmpty: String { isHebrew ? "האשפה ריקה" : "Trash is empty" }
    var restore: String { isHebrew ? "שחזר" : "Restore" }
    var deleteForever: String { isHebrew ? "מחק לצמיתות" : "Delete" }
    var emptyTrash: String { isHebrew ? "רוקן אשפה" : "Empty Trash" }

    // Common
    var cancel: String { isHebrew ? "ביטול" : "Cancel" }
    var save: String { isHebrew ? "שמור" : "Save" }
    var close: String { isHebrew ? "סגור" : "Close" }
    var done: String { isHebrew ? "סיום" : "Done" }
    var connectDrivePrompt: String {
        isHebrew ? "חבר את Google Drive בהגדרות ⚙️ כדי לסנכרן" : "Connect Google Drive in Settings ⚙️ to sync"
    }
}

enum FilenameSanitizer {
    private static let noteTitleRegex = try? NSRegularExpression(pattern: "[^a-zA-Z0-9\\u0590-\\u05FF._ -]", options: [])
    private static let safeSlugRegex = try? NSRegularExpression(pattern: "[^a-zA-Z0-9\\u0590-\\u05FF_-]", options: [])

    static func sanitizeNoteTitle(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let regex = noteTitleRegex else { return "" }
        let range = NSRange(trimmed.startIndex..., in: trimmed)
        return regex.stringByReplacingMatches(in: trimmed, options: [], range: range, withTemplate: "_")
    }

    static func sanitizeDownloadName(_ raw: String, fallback: String = "FilingDocument") -> String {
        let base = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let target = base.isEmpty ? fallback : base
        guard let regex = safeSlugRegex else { return fallback }
        let range = NSRange(target.startIndex..., in: target)
        return regex.stringByReplacingMatches(in: target, options: [], range: range, withTemplate: "_")
    }
}
