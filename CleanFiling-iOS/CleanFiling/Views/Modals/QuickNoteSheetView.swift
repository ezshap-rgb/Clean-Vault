import SwiftUI

struct QuickNoteSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var lang: LanguageManager
    @EnvironmentObject private var repo: FilingRepository

    let onAttached: (URL, String) -> Void

    @State private var noteTitle = ""
    @State private var noteBody = ""

    private var s: AppStrings { lang.strings }

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(s.noteTitleLabel)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(CleanFilingTheme.textSubtle)

                    TextField(s.noteTitlePlaceholder, text: $noteTitle)
                        .font(.system(size: 15, weight: .semibold))
                        .padding(12)
                        .background(CleanFilingTheme.surfaceRaised)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(s.noteContentLabel)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(CleanFilingTheme.textSubtle)

                    TextEditor(text: $noteBody)
                        .font(.system(size: 14))
                        .padding(8)
                        .frame(minHeight: 180)
                        .scrollContentBackground(.hidden)
                        .background(CleanFilingTheme.surfaceRaised)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                Spacer()

                HStack(spacing: 10) {
                    Button(s.cancel) {
                        dismiss()
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .background(CleanFilingTheme.surfaceRaised)
                    .foregroundColor(CleanFilingTheme.textDark)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                    Button(action: attachNote) {
                        Text(s.attachNote)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.black)
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                            .background(CleanFilingTheme.amberGold)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
            }
            .padding(18)
            .navigationTitle(s.quickNoteTitle)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func attachNote() {
        if noteTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            noteBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            repo.showToast(s.noteEmptyToast)
            return
        }
        do {
            let result = try repo.createTempNoteFile(title: noteTitle, body: noteBody)
            onAttached(result.url, result.suggestedName)
            repo.showToast(s.noteAttachedToast)
            dismiss()
        } catch {
            repo.showToast(error.localizedDescription)
        }
    }
}
