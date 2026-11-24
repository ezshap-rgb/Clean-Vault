import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct FileIntakeView: View {
    @EnvironmentObject private var lang: LanguageManager
    @EnvironmentObject private var repo: FilingRepository

    let onBack: () -> Void
    let onFiled: () -> Void

    @State private var scanAsPdf = true
    @State private var selectedTempURL: URL?
    @State private var selectedMimeType = "image/jpeg"
    @State private var previewImage: UIImage?

    @State private var docNameText = ""
    @State private var labelsText = ""
    @State private var isPinned = false

    @State private var showScanner = false
    @State private var showCamera = false
    @State private var showFileImporter = false
    @State private var showPhotoPicker = false
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var showQuickNoteSheet = false

    private var s: AppStrings { lang.strings }

    var body: some View {
        VStack(spacing: 12) {
            // Sub-screen Header
            HStack(spacing: 14) {
                Button(action: {
                    selectedTempURL = nil
                    previewImage = nil
                    onBack()
                }) {
                    Text("✕")
                        .font(.system(size: 18, weight: .black))
                        .foregroundColor(CleanFilingTheme.textDark)
                        .frame(width: 42, height: 42)
                        .background(CleanFilingTheme.cardWhite)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .shadow(color: Color.black.opacity(0.08), radius: 4, x: 0, y: 2)
                }
                .buttonStyle(.plain)

                Text(s.fileHeader)
                    .font(.system(size: 22, weight: .black))
                    .foregroundColor(CleanFilingTheme.textDark)

                Spacer()
            }
            .padding(.bottom, 4)

            if selectedTempURL == nil {
                // Format Pill Toggle [ PDF | JPG ]
                HStack(spacing: 0) {
                    formatPillButton(title: "PDF", isSelected: scanAsPdf) {
                        scanAsPdf = true
                    }
                    formatPillButton(title: "JPG", isSelected: !scanAsPdf) {
                        scanAsPdf = false
                    }
                }
                .padding(4)
                .frame(width: 170)
                .background(CleanFilingTheme.pillLavender)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))

                // Centered Large Scan Card
                Button(action: {
                    if DocumentScannerView.isSupported {
                        showScanner = true
                    } else if CameraPickerView.isAvailable {
                        repo.showToast(s.scannerUnavailable)
                        showCamera = true
                    } else {
                        repo.showToast(s.scannerUnavailable)
                    }
                }) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 38, style: .continuous)
                            .fill(CleanFilingTheme.clayScanCardGradient)
                            .shadow(color: CleanFilingTheme.purpleDark.opacity(0.28), radius: 12, x: 0, y: 6)

                        VStack(spacing: 14) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 30, style: .continuous)
                                    .fill(Color.white.opacity(0.18))
                                    .frame(width: 96, height: 96)

                                Image(systemName: "doc.viewfinder")
                                    .font(.system(size: 44, weight: .semibold))
                                    .foregroundColor(.white)
                            }

                            Text(s.scanDocument)
                                .font(.system(size: 22, weight: .black))
                                .foregroundColor(.white)

                            Text(s.scanSubtitle)
                                .font(.system(size: 12.5, weight: .semibold))
                                .foregroundColor(CleanFilingTheme.pillLavender)
                        }
                        .padding(20)
                    }
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.vertical, 10)

                // Clay Bottom Action Row: Photo, Files, Photos Library, Quick Note
                HStack(spacing: 14) {
                    clayActionTile(emoji: "📷", label: s.quickPhotoHint) {
                        if CameraPickerView.isAvailable {
                            showCamera = true
                        } else {
                            showPhotoPicker = true
                        }
                    }

                    clayActionTile(emoji: "🖼️", label: s.pickGalleryHint) {
                        showPhotoPicker = true
                    }

                    clayActionTile(emoji: "📁", label: s.pickFileHint) {
                        showFileImporter = true
                    }

                    clayActionTile(emoji: "📝", label: s.quickNoteHint) {
                        showQuickNoteSheet = true
                    }
                }
                .padding(.bottom, 6)
            } else {
                // Selected Document Preview & Filing Form
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 12) {
                        // Viewing Card
                        ZStack {
                            RoundedRectangle(cornerRadius: 24, style: .continuous)
                                .fill(CleanFilingTheme.cardWhite)
                                .shadow(color: Color.black.opacity(0.06), radius: 6, x: 0, y: 2)

                            if let image = previewImage {
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(maxHeight: 240)
                                    .padding(10)
                            } else {
                                VStack(spacing: 10) {
                                    Text(selectedMimeType.contains("pdf") ? "📄" : "📝")
                                        .font(.system(size: 54))
                                    Text(selectedTempURL?.lastPathComponent ?? "")
                                        .font(.system(size: 13.5, weight: .bold))
                                        .foregroundColor(CleanFilingTheme.textDark)
                                        .lineLimit(2)
                                        .multilineTextAlignment(.center)
                                }
                                .padding(24)
                            }
                        }
                        .frame(minHeight: 170)

                        // Document Name Field
                        inputCard(label: s.docNameLabel, placeholder: s.docNamePlaceholder, text: $docNameText)

                        // Labels Field
                        inputCard(label: s.labelsLabel, placeholder: s.labelsPlaceholder, text: $labelsText)

                        // Pin to Essentials Toggle
                        Button(action: { isPinned.toggle() }) {
                            HStack {
                                HStack(spacing: 8) {
                                    Image(systemName: "pin.fill")
                                        .foregroundColor(isPinned ? CleanFilingTheme.redPin : CleanFilingTheme.textSubtle.opacity(0.5))
                                    Text(s.pinToEssentials)
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundColor(isPinned ? CleanFilingTheme.redPin : CleanFilingTheme.textSubtle)
                                }
                                Spacer()
                                Toggle("", isOn: $isPinned)
                                    .labelsHidden()
                                    .tint(CleanFilingTheme.redPin)
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                            .background(isPinned ? CleanFilingTheme.softRedBg : CleanFilingTheme.cardWhite)
                            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                            .shadow(color: Color.black.opacity(0.05), radius: 4, x: 0, y: 2)
                        }
                        .buttonStyle(.plain)

                        // File to Cabinet Button
                        Button(action: fileCurrentSelection) {
                            Text(s.fileToCabinet)
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 52)
                                .background(CleanFilingTheme.purpleDark)
                                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                        }
                        .buttonStyle(.plain)

                        // Cancel / Retake Button
                        Button(action: {
                            selectedTempURL = nil
                            previewImage = nil
                        }) {
                            Text(s.cancelRetake)
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(CleanFilingTheme.textSubtle)
                                .frame(maxWidth: .infinity)
                                .frame(height: 46)
                                .background(CleanFilingTheme.cardWhite)
                                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                                        .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
                                )
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.bottom, 16)
                }
            }
        }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentScannerView(
                scanAsPdf: scanAsPdf,
                onComplete: { url, mime in
                    showScanner = false
                    setSelectedFile(url: url, mimeType: mime)
                },
                onCancel: { showScanner = false },
                onError: { err in
                    showScanner = false
                    repo.showToast(err)
                }
            )
            .ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPickerView(
                onImagePicked: { url in
                    showCamera = false
                    setSelectedFile(url: url, mimeType: "image/jpeg")
                },
                onCancel: { showCamera = false }
            )
            .ignoresSafeArea()
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItem, matching: .images)
        .onChange(of: selectedPhotoItem) { newItem in
            guard let newItem else { return }
            Task {
                if let data = try? await newItem.loadTransferable(type: Data.self) {
                    let tempURL = FileManager.default.temporaryDirectory
                        .appendingPathComponent("Import_\(Int(Date().timeIntervalSince1970)).jpg")
                    try? data.write(to: tempURL, options: .atomic)
                    await MainActor.run {
                        setSelectedFile(url: tempURL, mimeType: "image/jpeg")
                        selectedPhotoItem = nil
                    }
                }
            }
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.pdf, .image, .plainText, .data],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                let mime = mimeType(for: url)
                setSelectedFile(url: url, mimeType: mime, suggestedTitle: url.deletingPathExtension().lastPathComponent)
            case .failure(let error):
                repo.showToast(error.localizedDescription)
            }
        }
        .sheet(isPresented: $showQuickNoteSheet) {
            QuickNoteSheetView { tempURL, suggestedTitle in
                setSelectedFile(url: tempURL, mimeType: "text/plain", suggestedTitle: suggestedTitle)
            }
            .environment(\.layoutDirection, lang.layoutDirection)
        }
    }

    private func formatPillButton(title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .black))
                .foregroundColor(isSelected ? CleanFilingTheme.purpleDark : CleanFilingTheme.claySlotBg)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 7)
                .background(isSelected ? CleanFilingTheme.cardWhite : Color.clear)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func clayActionTile(emoji: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Text(emoji)
                    .font(.system(size: 26))
                Text(label)
                    .font(.system(size: 10.5, weight: .bold))
                    .foregroundColor(CleanFilingTheme.textSubtle)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 74)
            .background(CleanFilingTheme.cardWhite)
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .shadow(color: Color.black.opacity(0.06), radius: 6, x: 0, y: 3)
        }
        .buttonStyle(.plain)
    }

    private func inputCard(label: String, placeholder: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(CleanFilingTheme.textSubtle)
            TextField(placeholder, text: text)
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(CleanFilingTheme.textDark)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(CleanFilingTheme.cardWhite)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: Color.black.opacity(0.04), radius: 4, x: 0, y: 2)
    }

    private func setSelectedFile(url: URL, mimeType: String, suggestedTitle: String? = nil) {
        selectedTempURL = url
        selectedMimeType = mimeType
        if let suggestedTitle, docNameText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            docNameText = suggestedTitle
        }
        if mimeType.hasPrefix("image/") {
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url) {
                previewImage = UIImage(data: data)
            }
        } else {
            previewImage = nil
        }
    }

    private func fileCurrentSelection() {
        guard let url = selectedTempURL else { return }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let fallbackName = "Document \(formatter.string(from: Date()))"
        let finalName = docNameText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? fallbackName
            : docNameText.trimmingCharacters(in: .whitespacesAndNewlines)

        do {
            try repo.saveFileToCabinet(
                name: finalName,
                notes: labelsText,
                isPinned: isPinned,
                sourceURL: url,
                mimeType: selectedMimeType
            )
            selectedTempURL = nil
            previewImage = nil
            docNameText = ""
            labelsText = ""
            isPinned = false
            repo.showToast(s.filedSuccessToast)
            onFiled()
            if repo.hasCloudConnection {
                Task { await repo.syncWithCloud(silent: true) }
            }
        } catch {
            repo.showToast(error.localizedDescription)
        }
    }

    private func mimeType(for url: URL) -> String {
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "pdf": return "application/pdf"
        case "txt": return "text/plain"
        case "png": return "image/png"
        case "heic": return "image/heic"
        default: return "image/jpeg"
        }
    }
}
