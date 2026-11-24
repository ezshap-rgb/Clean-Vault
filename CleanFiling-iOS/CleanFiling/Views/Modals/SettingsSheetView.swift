import SwiftUI

struct SettingsSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var lang: LanguageManager
    @EnvironmentObject private var auth: GoogleAuthManager
    @EnvironmentObject private var repo: FilingRepository

    @State private var partnerEmailInput = ""
    @State private var ownerEmailInput = ""
    @State private var isSharing = false
    @State private var shareInviteURL: ShareSheetItem?

    private var s: AppStrings { lang.strings }
    private var isLocalActive: Bool { repo.activeCloudMode == .localOnly }
    private var isDriveConnected: Bool { !repo.googleEmail.isEmpty }
    private var isDriveActive: Bool { repo.activeCloudMode == .googleDrive && isDriveConnected }

    var body: some View {
        NavigationStack {
            ScrollView(showsIndicators: false) {
                VStack(spacing: 16) {
                    // 1. App Language Card
                    HStack(spacing: 10) {
                        Text("🌐").font(.system(size: 18))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.appLanguageTitle)
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(CleanFilingTheme.textDark)
                            Text(s.appLanguageSubtitle)
                                .font(.system(size: 11.5))
                                .foregroundColor(CleanFilingTheme.textSubtle)
                        }
                        Spacer()
                        Button(action: { lang.toggle() }) {
                            Text(s.switchLanguageBtn)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(CleanFilingTheme.purpleAccent)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(14)
                    .background(CleanFilingTheme.surfaceRaised)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
                    )

                    // 2. Local Only Card
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            HStack(spacing: 8) {
                                Text("📱").font(.system(size: 18))
                                Text(s.localOnlyTitle)
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundColor(CleanFilingTheme.textDark)
                            }
                            Spacer()
                            if isLocalActive {
                                activeBadge
                            }
                        }

                        Text(s.localOnlyDesc)
                            .font(.system(size: 11.5))
                            .foregroundColor(CleanFilingTheme.textSubtle)

                        if !isLocalActive {
                            Button(action: {
                                repo.setActiveCloudMode(.localOnly)
                            }) {
                                Text(s.switchToLocalOnly)
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(CleanFilingTheme.textDark)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 8)
                                    .background(CleanFilingTheme.cardWhite)
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                                            .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
                                    )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(14)
                    .background(isLocalActive ? CleanFilingTheme.softGreenBg : CleanFilingTheme.surfaceRaised)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(
                                isLocalActive ? CleanFilingTheme.emeraldGreen.opacity(0.6) : CleanFilingTheme.borderColor,
                                lineWidth: 1
                            )
                    )

                    // 3. Google Drive Cabinet Card
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            HStack(spacing: 8) {
                                Text("☁️").font(.system(size: 18))
                                Text(s.googleDriveTitle)
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundColor(CleanFilingTheme.textDark)
                            }
                            Spacer()
                            if isDriveActive {
                                activeBadge
                            }
                        }

                        Text(s.googleDriveDesc)
                            .font(.system(size: 11.5))
                            .foregroundColor(CleanFilingTheme.textSubtle)

                        if !auth.isConfigured {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("⚠️ \(s.clientNotConfiguredTitle)")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(CleanFilingTheme.amberGold)
                                Text(s.clientNotConfiguredDesc)
                                    .font(.system(size: 11))
                                    .foregroundColor(CleanFilingTheme.textSubtle)
                            }
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(CleanFilingTheme.amberGold.opacity(0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }

                        if isDriveConnected {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(s.accountLabel(repo.googleEmail))
                                    .font(.system(size: 12.5, weight: .bold))
                                    .foregroundColor(CleanFilingTheme.emeraldGreen)
                                Text(s.folderLabel)
                                    .font(.system(size: 11.5))
                                    .foregroundColor(CleanFilingTheme.textSubtle)
                            }

                            HStack(spacing: 8) {
                                Button(action: {
                                    repo.setActiveCloudMode(.googleDrive)
                                    Task {
                                        let res = await repo.syncWithCloud(silent: false)
                                        if case .success(let summary) = res {
                                            repo.showToast("Synced: \(summary.total) docs ☁️")
                                        }
                                    }
                                }) {
                                    HStack(spacing: 6) {
                                        Image(systemName: "arrow.triangle.2.circlepath")
                                        Text(s.syncPrivateDrive)
                                    }
                                    .font(.system(size: 12.5, weight: .bold))
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 40)
                                    .background(CleanFilingTheme.purpleDark)
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                                .buttonStyle(.plain)

                                Button(action: {
                                    auth.signOut()
                                    repo.setGoogleAccount(email: nil)
                                }) {
                                    Text(s.disconnect)
                                        .font(.system(size: 12.5, weight: .bold))
                                        .foregroundColor(CleanFilingTheme.roseRed)
                                        .padding(.horizontal, 14)
                                        .frame(height: 40)
                                        .background(CleanFilingTheme.cardWhite)
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                                .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
                                        )
                                }
                                .buttonStyle(.plain)
                            }

                            // Partner Sharing Sub-card
                            VStack(alignment: .leading, spacing: 8) {
                                Text("🤝 \(s.sharePartnerTitle)")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(CleanFilingTheme.textDark)

                                Text(s.sharePartnerDesc)
                                    .font(.system(size: 11))
                                    .foregroundColor(CleanFilingTheme.textSubtle)

                                TextField(s.partnerEmailPlaceholder, text: $partnerEmailInput)
                                    .keyboardType(.emailAddress)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                    .font(.system(size: 13))
                                    .padding(10)
                                    .background(CleanFilingTheme.surfaceRaised)
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                                HStack(spacing: 8) {
                                    Button(action: grantPartnerAccess) {
                                        Text(isSharing ? "..." : s.grantAccess)
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundColor(.white)
                                            .frame(maxWidth: .infinity)
                                            .frame(height: 36)
                                            .background(partnerEmailInput.trimmingCharacters(in: .whitespaces).isEmpty ? CleanFilingTheme.textFaint : CleanFilingTheme.emeraldGreen)
                                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                    }
                                    .buttonStyle(.plain)
                                    .disabled(isSharing || partnerEmailInput.trimmingCharacters(in: .whitespaces).isEmpty)

                                    Button(action: {
                                        if let url = URL(string: "https://ezshap-rgb.github.io/Clean-Vault/") {
                                            shareInviteURL = ShareSheetItem(url: url)
                                        }
                                    }) {
                                        Text(s.shareLink)
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundColor(CleanFilingTheme.purpleDark)
                                            .padding(.horizontal, 12)
                                            .frame(height: 36)
                                            .background(CleanFilingTheme.cardWhite)
                                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                            .overlay(
                                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                                    .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
                                            )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(12)
                            .background(CleanFilingTheme.cardWhite)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
                            )

                            // Cabinet Source / Owner Sub-card
                            VStack(alignment: .leading, spacing: 8) {
                                Text("📂 \(s.ownerSectionTitle)")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(CleanFilingTheme.textDark)

                                Text(s.ownerSectionDesc)
                                    .font(.system(size: 11))
                                    .foregroundColor(CleanFilingTheme.textSubtle)

                                TextField(s.ownerEmailPlaceholder, text: $ownerEmailInput)
                                    .keyboardType(.emailAddress)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                    .font(.system(size: 13))
                                    .padding(10)
                                    .background(CleanFilingTheme.surfaceRaised)
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                                HStack(spacing: 8) {
                                    Button(action: {
                                        repo.setCabinetOwnerEmail(ownerEmailInput)
                                        Task { await repo.syncWithCloud(silent: false) }
                                    }) {
                                        Text(s.saveOwnerBtn)
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundColor(.white)
                                            .frame(maxWidth: .infinity)
                                            .frame(height: 36)
                                            .background(CleanFilingTheme.purpleAccent)
                                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                    }
                                    .buttonStyle(.plain)

                                    if !repo.cabinetOwnerEmail.isEmpty {
                                        Button(action: {
                                            ownerEmailInput = ""
                                            repo.setCabinetOwnerEmail("")
                                            Task { await repo.syncWithCloud(silent: false) }
                                        }) {
                                            Text(s.resetToMine)
                                                .font(.system(size: 12, weight: .bold))
                                                .foregroundColor(CleanFilingTheme.roseRed)
                                                .padding(.horizontal, 12)
                                                .frame(height: 36)
                                                .background(CleanFilingTheme.cardWhite)
                                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                                .overlay(
                                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                                        .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
                                                )
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }

                                if !repo.cabinetOwnerEmail.isEmpty {
                                    Text(s.boundToOwner(repo.cabinetOwnerEmail))
                                        .font(.system(size: 11.5, weight: .bold))
                                        .foregroundColor(CleanFilingTheme.emeraldGreen)
                                }
                            }
                            .padding(12)
                            .background(CleanFilingTheme.cardWhite)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
                            )
                        } else {
                            Button(action: signInToGoogle) {
                                HStack(spacing: 8) {
                                    Image(systemName: "person.crop.circle.fill")
                                    Text(s.signInWithGoogle)
                                }
                                .font(.system(size: 13.5, weight: .bold))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 44)
                                .background(CleanFilingTheme.purpleDark)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(14)
                    .background(isDriveActive ? CleanFilingTheme.softPurpleBg : CleanFilingTheme.surfaceRaised)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(
                                isDriveActive ? CleanFilingTheme.purpleAccent.opacity(0.6) : CleanFilingTheme.borderColor,
                                lineWidth: 1
                            )
                    )
                }
                .padding(16)
            }
            .background(CleanFilingTheme.cardWhite)
            .navigationTitle("⚙️ \(s.settingsTitle)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(s.done) { dismiss() }
                }
            }
            .onAppear {
                ownerEmailInput = repo.cabinetOwnerEmail
            }
            .sheet(item: $shareInviteURL) { item in
                ShareSheetView(activityItems: [item.url])
            }
        }
    }

    private var activeBadge: some View {
        Text(s.activeBadge)
            .font(.system(size: 9.5, weight: .black))
            .foregroundColor(CleanFilingTheme.purpleDark)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(CleanFilingTheme.purpleAccent.opacity(0.15))
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private func signInToGoogle() {
        Task {
            do {
                let email = try await auth.signIn()
                repo.setGoogleAccount(email: email)
                repo.showToast("Signed in: \(email)")
                await repo.syncWithCloud(silent: false)
            } catch {
                repo.showToast(error.localizedDescription)
            }
        }
    }

    private func grantPartnerAccess() {
        isSharing = true
        Task {
            let res = await repo.shareGoogleDriveCabinet(partnerEmail: partnerEmailInput)
            isSharing = false
            switch res {
            case .success:
                repo.showToast("Shared with \(partnerEmailInput) 🤝")
                partnerEmailInput = ""
            case .failure(let error):
                repo.showToast(error.localizedDescription)
            }
        }
    }
}
