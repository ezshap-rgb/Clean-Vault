import SwiftUI

struct TrashSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var lang: LanguageManager
    @EnvironmentObject private var repo: FilingRepository

    private var s: AppStrings { lang.strings }

    var body: some View {
        NavigationStack {
            Group {
                if repo.trashedDocuments.isEmpty {
                    VStack(spacing: 10) {
                        Text("🗑️").font(.system(size: 46))
                        Text(s.trashEmpty)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(CleanFilingTheme.textSubtle)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(repo.trashedDocuments) { item in
                            HStack(spacing: 12) {
                                Text(item.document.fileTypeIcon)
                                    .font(.system(size: 22))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.document.name)
                                        .font(.system(size: 14.5, weight: .bold))
                                        .foregroundColor(CleanFilingTheme.textDark)
                                    Text(item.document.date)
                                        .font(.system(size: 11.5))
                                        .foregroundColor(CleanFilingTheme.textSubtle)
                                }
                                Spacer()
                                Button(s.restore) {
                                    repo.restoreFromTrash(docId: item.document.id)
                                    if repo.hasCloudConnection {
                                        Task { await repo.syncWithCloud(silent: true) }
                                    }
                                }
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(CleanFilingTheme.purpleDark)
                                .buttonStyle(.borderless)

                                Button(s.deleteForever) {
                                    repo.permanentlyDeleteFromTrash(docId: item.document.id)
                                }
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(CleanFilingTheme.roseRed)
                                .buttonStyle(.borderless)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle(s.trashTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(s.close) { dismiss() }
                }
                if !repo.trashedDocuments.isEmpty {
                    ToolbarItem(placement: .destructiveAction) {
                        Button(s.emptyTrash) {
                            repo.emptyTrash()
                        }
                        .foregroundColor(CleanFilingTheme.roseRed)
                    }
                }
            }
        }
    }
}
