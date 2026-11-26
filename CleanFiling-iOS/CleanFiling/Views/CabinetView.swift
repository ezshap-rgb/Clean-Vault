import SwiftUI

struct CabinetView: View {
    @EnvironmentObject private var lang: LanguageManager
    @EnvironmentObject private var repo: FilingRepository

    let onBack: () -> Void
    let onOpenTrash: () -> Void
    let onOpenDoc: (FilingDocument) -> Void
    let onShareDoc: (FilingDocument) -> Void
    let onEditDoc: (FilingDocument) -> Void
    let onDeleteDoc: (FilingDocument) -> Void

    @State private var searchQuery = ""
    @State private var filterPinnedOnly = false
    @State private var filterTempOnly = false

    private var s: AppStrings { lang.strings }

    private var filteredDocs: [FilingDocument] {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        return repo.documents.filter { doc in
            let matchesSearch = q.isEmpty ||
                doc.name.localizedCaseInsensitiveContains(q) ||
                doc.notes.localizedCaseInsensitiveContains(q)
            let matchesPinned = !filterPinnedOnly || doc.isPinned
            let matchesTemp = !filterTempOnly || doc.isTemporary
            return matchesSearch && matchesPinned && matchesTemp
        }
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

                    Text(s.cabinetHeader)
                        .font(.system(size: 22, weight: .black))
                        .foregroundColor(CleanFilingTheme.textDark)
                }

                Spacer()

                HStack(spacing: 6) {
                    Text(s.filesCount(repo.documents.count))
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(CleanFilingTheme.textSubtle)

                    Button(action: onOpenTrash) {
                        Image(systemName: "trash.fill")
                            .font(.system(size: 16))
                            .foregroundColor(CleanFilingTheme.textSubtle)
                            .frame(width: 34, height: 34)
                    }
                    .buttonStyle(.plain)
                }
            }

            // Cloud Sync Status Row
            if repo.hasCloudConnection {
                let pending = repo.documents.filter { !$0.isSynced }.count
                HStack {
                    Text(syncStatusText(pending: pending))
                        .font(.system(size: 12))
                        .foregroundColor(CleanFilingTheme.textSubtle)

                    Spacer()

                    if !repo.isSyncing && (repo.cloudSyncStatus.needsRetry || pending > 0) {
                        Button(action: {
                            Task { await repo.syncWithCloud(silent: false) }
                        }) {
                            Text(repo.cloudSyncStatus.needsRetry ? s.retry : s.syncNow)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(CleanFilingTheme.purpleDark)
                        }
                    }
                }
            }

            // Search Bar & Filter Pills
            VStack(spacing: 8) {
                HStack(spacing: 8) {
                    Text("🔍")
                        .font(.system(size: 14))

                    TextField(s.searchPlaceholder, text: $searchQuery)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(CleanFilingTheme.textDark)

                    if !searchQuery.isEmpty {
                        Button(action: { searchQuery = "" }) {
                            Text("✕")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(CleanFilingTheme.textSubtle)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(CleanFilingTheme.cardWhite)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .shadow(color: Color.black.opacity(0.04), radius: 4, x: 0, y: 2)

                HStack(spacing: 8) {
                    Button(action: {
                        filterPinnedOnly.toggle()
                        if filterPinnedOnly { filterTempOnly = false }
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "pin.fill")
                                .font(.system(size: 13))
                                .foregroundColor(filterPinnedOnly ? CleanFilingTheme.redPin : CleanFilingTheme.textSubtle.opacity(0.5))
                            Text(s.pinnedFilterPill)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(filterPinnedOnly ? CleanFilingTheme.redPin : CleanFilingTheme.textSubtle)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(filterPinnedOnly ? CleanFilingTheme.softRedBg : CleanFilingTheme.cardWhite)
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: Color.black.opacity(0.04), radius: 4, x: 0, y: 2)
                    }
                    .buttonStyle(.plain)

                    Button(action: {
                        filterTempOnly.toggle()
                        if filterTempOnly { filterPinnedOnly = false }
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "hourglass.bottomhalf.filled")
                                .font(.system(size: 13))
                                .foregroundColor(filterTempOnly ? CleanFilingTheme.amberGold : CleanFilingTheme.textSubtle.opacity(0.5))
                            Text(s.temporaryFilterPill)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(filterTempOnly ? CleanFilingTheme.amberGold : CleanFilingTheme.textSubtle)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(filterTempOnly ? CleanFilingTheme.softAmberBg : CleanFilingTheme.cardWhite)
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: Color.black.opacity(0.04), radius: 4, x: 0, y: 2)
                    }
                    .buttonStyle(.plain)

                    Spacer()
                }
            }

            // Document List
            if filteredDocs.isEmpty {
                Spacer()
                Text(s.noDocumentsFound)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(CleanFilingTheme.textSubtle)
                Spacer()
            } else {
                ScrollView(showsIndicators: false) {
                    LazyVStack(spacing: 10) {
                        ForEach(filteredDocs) { doc in
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

    private func syncStatusText(pending: Int) -> String {
        if repo.isSyncing { return s.syncingNow }
        if repo.cloudSyncStatus.needsRetry { return s.syncIncomplete }
        if pending > 0 { return s.pendingCount(pending) }
        if repo.cloudSyncStatus.lastSuccess > 0 {
            let date = Date(timeIntervalSince1970: Double(repo.cloudSyncStatus.lastSuccess) / 1000.0)
            let formatted = DateFormatter.localizedString(from: date, dateStyle: .short, timeStyle: .short)
            return s.lastSynced(formatted)
        }
        return s.readyToSync
    }
}
