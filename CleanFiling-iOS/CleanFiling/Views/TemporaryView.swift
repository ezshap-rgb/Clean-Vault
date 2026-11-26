import SwiftUI

struct TemporaryView: View {
    @EnvironmentObject private var lang: LanguageManager
    @EnvironmentObject private var repo: FilingRepository

    let onBack: () -> Void
    let onOpenDoc: (FilingDocument) -> Void
    let onShareDoc: (FilingDocument) -> Void
    let onEditDoc: (FilingDocument) -> Void
    let onDeleteDoc: (FilingDocument) -> Void

    private var s: AppStrings { lang.strings }
    private var tempDocs: [FilingDocument] {
        repo.documents.filter { $0.isTemporary }
    }

    var body: some View {
        VStack(spacing: 12) {
            // Header
            HStack {
                HStack(spacing: 14) {
                    Button(action: onBack) {
                        Image(systemName: lang.isHebrew ? "chevron.right" : "chevron.left")
                            .font(.system(size: 18, weight: .black))
                            .foregroundColor(CleanFilingTheme.textDark)
                            .frame(width: 42, height: 42)
                            .background(CleanFilingTheme.cardWhite)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .shadow(color: Color.black.opacity(0.08), radius: 4, x: 0, y: 2)
                    }
                    .buttonStyle(.plain)

                    Text(s.temporaryHeader)
                        .font(.system(size: 22, weight: .black))
                        .foregroundColor(CleanFilingTheme.textDark)
                }

                Spacer()

                Text(s.temporaryCount(tempDocs.count))
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(CleanFilingTheme.textSubtle)
            }

            if tempDocs.isEmpty {
                Spacer()
                VStack(spacing: 10) {
                    Text("⏳")
                        .font(.system(size: 48))
                    Text(s.noTempTitle)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(CleanFilingTheme.textDark)
                    Text(s.noTempSubtitle)
                        .font(.system(size: 12.5))
                        .foregroundColor(CleanFilingTheme.textSubtle)
                        .multilineTextAlignment(.center)
                }
                Spacer()
            } else {
                ScrollView(showsIndicators: false) {
                    LazyVStack(spacing: 10) {
                        ForEach(tempDocs) { doc in
                            DocumentCardView(
                                doc: doc,
                                onOpen: { onOpenDoc(doc) },
                                onShare: { onShareDoc(doc) },
                                onEdit: { onEditDoc(doc) },
                                onTogglePin: {
                                    repo.togglePin(docId: doc.id)
                                    if repo.hasCloudConnection {
                                        Task { await repo.syncWithCloud(silent: true) }
                                    }
                                },
                                onToggleTemporary: {
                                    repo.toggleTemporary(docId: doc.id)
                                    if repo.hasCloudConnection {
                                        Task { await repo.syncWithCloud(silent: true) }
                                    }
                                },
                                onDelete: { onDeleteDoc(doc) }
                            )
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
        }
    }
}
