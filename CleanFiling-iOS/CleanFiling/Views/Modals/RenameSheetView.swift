import SwiftUI

struct RenameSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var lang: LanguageManager
    @EnvironmentObject private var repo: FilingRepository

    let doc: FilingDocument

    @State private var nameText = ""
    @State private var labelsText = ""

    private var s: AppStrings { lang.strings }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(s.docNameLabel)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(CleanFilingTheme.textSubtle)
                    TextField(s.docNamePlaceholder, text: $nameText)
                        .font(.system(size: 15, weight: .bold))
                        .padding(12)
                        .background(CleanFilingTheme.surfaceRaised)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(s.labelsLabel)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(CleanFilingTheme.textSubtle)
                    TextField(s.labelsPlaceholder, text: $labelsText)
                        .font(.system(size: 14, weight: .semibold))
                        .padding(12)
                        .background(CleanFilingTheme.surfaceRaised)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                Spacer()
            }
            .padding(18)
            .navigationTitle(s.renameTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(s.cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(s.save) {
                        repo.renameDocument(docId: doc.id, newName: nameText, newNotes: labelsText)
                        if repo.hasCloudConnection {
                            Task { await repo.syncWithCloud(silent: true) }
                        }
                        dismiss()
                    }
                    .fontWeight(.bold)
                }
            }
            .onAppear {
                nameText = doc.name
                labelsText = doc.notes
            }
        }
    }
}
