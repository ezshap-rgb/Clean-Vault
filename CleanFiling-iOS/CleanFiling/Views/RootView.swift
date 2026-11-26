import SwiftUI

struct RootView: View {
    @EnvironmentObject private var lang: LanguageManager
    @EnvironmentObject private var auth: GoogleAuthManager
    @EnvironmentObject private var repo: FilingRepository

    @State private var currentScreen: AppScreen = .home
    @State private var showSettings = false
    @State private var showTrash = false

    @State private var docToRename: FilingDocument?
    @State private var docToDelete: FilingDocument?
    @State private var noteDocToView: FilingDocument?
    @State private var isEditingNote = false

    @State private var quickLookItem: QuickLookItem?
    @State private var shareItem: ShareSheetItem?

    private var s: AppStrings { lang.strings }

    var body: some View {
        ZStack {
            CleanFilingTheme.clayAppBg
                .ignoresSafeArea()

            Group {
                switch currentScreen {
                case .home:
                    HomeView(
                        onOpenFileIn: { currentScreen = .fileIn },
                        onOpenEssentials: { currentScreen = .essentials },
                        onOpenTemporary: { currentScreen = .temporary },
                        onOpenCabinet: { currentScreen = .cabinet },
                        onOpenSettings: { showSettings = true }
                    )
                case .fileIn:
                    FileIntakeView(
                        onBack: { currentScreen = .home },
                        onFiled: { currentScreen = .cabinet }
                    )
                case .essentials:
                    EssentialsView(
                        onBack: { currentScreen = .home },
                        onOpenDoc: { handleOpenDoc($0) },
                        onShareDoc: { handleShareDoc($0) },
                        onEditDoc: { handleEditDoc($0) },
                        onDeleteDoc: { docToDelete = $0 }
                    )
                case .temporary:
                    TemporaryView(
                        onBack: { currentScreen = .home },
                        onOpenDoc: { handleOpenDoc($0) },
                        onShareDoc: { handleShareDoc($0) },
                        onEditDoc: { handleEditDoc($0) },
                        onDeleteDoc: { docToDelete = $0 }
                    )
                case .cabinet:
                    CabinetView(
                        onBack: { currentScreen = .home },
                        onOpenTrash: { showTrash = true },
                        onOpenDoc: { handleOpenDoc($0) },
                        onShareDoc: { handleShareDoc($0) },
                        onEditDoc: { handleEditDoc($0) },
                        onDeleteDoc: { docToDelete = $0 }
                    )
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            // Floating Toast Banner
            if let toast = repo.toastMessage {
                VStack {
                    Spacer()
                    Text(toast)
                        .font(.system(size: 13.5, weight: .bold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 11)
                        .background(CleanFilingTheme.textDark.opacity(0.92))
                        .clipShape(Capsule())
                        .shadow(color: Color.black.opacity(0.2), radius: 8, x: 0, y: 4)
                        .padding(.bottom, 26)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .animation(.easeInOut(duration: 0.2), value: repo.toastMessage)
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsSheetView()
                .environment(\.layoutDirection, lang.layoutDirection)
        }
        .sheet(isPresented: $showTrash) {
            TrashSheetView()
                .environment(\.layoutDirection, lang.layoutDirection)
        }
        .sheet(item: $docToRename) { doc in
            RenameSheetView(doc: doc)
                .environment(\.layoutDirection, lang.layoutDirection)
        }
        .sheet(item: $noteDocToView) { doc in
            NoteViewerSheetView(doc: doc, startInEditMode: isEditingNote)
                .environment(\.layoutDirection, lang.layoutDirection)
        }
        .sheet(item: $quickLookItem) { item in
            QuickLookPreview(item: item)
                .ignoresSafeArea()
        }
        .sheet(item: $shareItem) { item in
            ShareSheetView(activityItems: [item.url])
        }
        .alert(
            s.deleteConfirmTitle,
            isPresented: Binding(
                get: { docToDelete != nil },
                set: { if !$0 { docToDelete = nil } }
            ),
            presenting: docToDelete
        ) { doc in
            Button(s.moveToTrash, role: .destructive) {
                Task {
                    let moved = await repo.moveToTrash(docId: doc.id)
                    if moved, repo.hasCloudConnection {
                        await repo.syncWithCloud(silent: true)
                    }
                }
            }
            Button(s.cancel, role: .cancel) {}
        } message: { doc in
            Text(s.deleteConfirmMessage(doc.name))
        }
    }

    private func handleOpenDoc(_ doc: FilingDocument) {
        if doc.isTextNote {
            isEditingNote = false
            noteDocToView = doc
            return
        }
        let url = repo.fileURL(for: doc)
        if FileManager.default.fileExists(atPath: url.path) {
            quickLookItem = QuickLookItem(url: url, title: doc.name)
        } else if let webLink = doc.driveFileUrl, let remoteURL = URL(string: webLink) {
            UIApplication.shared.open(remoteURL)
        } else {
            repo.showToast(s.syncIncomplete)
        }
    }

    private func handleShareDoc(_ doc: FilingDocument) {
        let url = repo.fileURL(for: doc)
        if FileManager.default.fileExists(atPath: url.path) {
            shareItem = ShareSheetItem(url: url)
        } else {
            repo.showToast(s.syncIncomplete)
        }
    }

    private func handleEditDoc(_ doc: FilingDocument) {
        if doc.isTextNote {
            isEditingNote = true
            noteDocToView = doc
        } else {
            docToRename = doc
        }
    }
}
