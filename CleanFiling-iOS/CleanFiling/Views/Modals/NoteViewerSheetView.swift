import SwiftUI

struct NoteViewerSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var lang: LanguageManager
    @EnvironmentObject private var repo: FilingRepository

    let doc: FilingDocument
    let startInEditMode: Bool

    @State private var isEditing = false
    @State private var titleText = ""
    @State private var labelsText = ""
    @State private var bodyText = ""

    private var s: AppStrings { lang.strings }

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                if isEditing {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(s.docNameLabel)
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(CleanFilingTheme.textSubtle)
                        TextField("", text: $titleText)
                            .font(.system(size: 15, weight: .bold))
                            .padding(10)
                            .background(CleanFilingTheme.surfaceRaised)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Text(s.labelsLabel)
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(CleanFilingTheme.textSubtle)
                        TextField("", text: $labelsText)
                            .font(.system(size: 14))
                            .padding(10)
                            .background(CleanFilingTheme.surfaceRaised)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }

                    TextEditor(text: $bodyText)
                        .font(.system(size: 14))
                        .padding(8)
                        .scrollContentBackground(.hidden)
                        .background(CleanFilingTheme.surfaceRaised)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 10) {
                            if !doc.notes.isEmpty {
                                Text("🏷️ \(doc.notes)")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(CleanFilingTheme.purpleDark)
                            }
                            Text(bodyText)
                                .font(.system(size: 15))
                                .foregroundColor(CleanFilingTheme.textDark)
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(14)
                    }
                    .background(CleanFilingTheme.surfaceRaised)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }
            .padding(18)
            .navigationTitle(isEditing ? s.editNote : doc.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(s.close) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isEditing {
                        Button(s.saveChanges) {
                            repo.updateNote(docId: doc.id, name: titleText, notes: labelsText, newContent: bodyText)
                            isEditing = false
                            if repo.hasCloudConnection {
                                Task { await repo.syncWithCloud(silent: true) }
                            }
                        }
                    } else {
                        Button(s.editNote) { isEditing = true }
                    }
                }
            }
            .onAppear {
                isEditing = startInEditMode
                titleText = doc.name
                labelsText = doc.notes
                bodyText = repo.readNoteText(for: doc)
            }
        }
    }
}
