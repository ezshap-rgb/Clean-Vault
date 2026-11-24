import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var lang: LanguageManager
    @EnvironmentObject private var repo: FilingRepository

    let onOpenFileIn: () -> Void
    let onOpenEssentials: () -> Void
    let onOpenCabinet: () -> Void
    let onOpenSettings: () -> Void

    @State private var syncSpin = false

    private var s: AppStrings { lang.strings }

    var body: some View {
        VStack(spacing: 12) {
            // App Header Card
            HStack(alignment: .center, spacing: 10) {
                HStack(spacing: 12) {
                    Image("AppLogo")
                        .resizable()
                        .scaledToFill()
                        .frame(width: 42, height: 42)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
                        )

                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(s.appTitle)
                                .font(.system(size: 17.5, weight: .bold))
                                .foregroundColor(CleanFilingTheme.textDark)

                            Text(s.studioBadge)
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(CleanFilingTheme.purpleDark)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(CleanFilingTheme.purpleAccent.opacity(0.15))
                                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                                        .stroke(CleanFilingTheme.purpleAccent.opacity(0.5), lineWidth: 0.8)
                                )
                        }

                        Text(s.brandSubtitle)
                            .font(.system(size: 11.5, weight: .medium))
                            .foregroundColor(CleanFilingTheme.textSubtle)
                    }
                }

                Spacer()

                HStack(spacing: 4) {
                    // Language Toggle
                    Button(action: { lang.toggle() }) {
                        Text(lang.isHebrew ? "🇺🇸" : "🇮🇱")
                            .font(.system(size: 17))
                            .frame(width: 34, height: 34)
                    }
                    .buttonStyle(.plain)

                    // Sync Button
                    Button(action: {
                        if !repo.hasCloudConnection {
                            repo.showToast(s.connectDrivePrompt)
                            onOpenSettings()
                        } else {
                            Task { await repo.syncWithCloud(silent: false) }
                        }
                    }) {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(
                                repo.isSyncing
                                    ? CleanFilingTheme.amberGold
                                    : (repo.hasCloudConnection ? CleanFilingTheme.emeraldGreen : CleanFilingTheme.textSubtle)
                            )
                            .rotationEffect(.degrees(repo.isSyncing && syncSpin ? 360 : 0))
                            .animation(
                                repo.isSyncing
                                    ? .linear(duration: 1).repeatForever(autoreverses: false)
                                    : .default,
                                value: syncSpin
                            )
                            .frame(width: 34, height: 34)
                    }
                    .buttonStyle(.plain)
                    .onChange(of: repo.isSyncing) { syncing in
                        syncSpin = syncing
                    }

                    // Settings Button
                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape.fill")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(CleanFilingTheme.textSubtle)
                            .frame(width: 34, height: 34)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(CleanFilingTheme.cardWhite)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
            )
            .shadow(color: Color.black.opacity(0.05), radius: 6, x: 0, y: 2)

            // 3D Clay Cabinet Casing
            GeometryReader { geo in
                let totalHeight = geo.size.height
                let spacing: CGFloat = 12
                let innerPadding: CGFloat = 16
                let available = max(300, totalHeight - innerPadding * 2 - spacing * 2)
                let unit = available / (1.25 + 0.95 + 1.25)

                VStack(spacing: spacing) {
                    // Drawer 1: IN (Scan & File)
                    cabinetDrawer(
                        title: s.drawerInTitle,
                        subtitle: s.drawerInSub,
                        handleWidth: 110,
                        handleHeight: 14,
                        titleSize: 16,
                        letterSpacing: 2.0,
                        action: onOpenFileIn
                    )
                    .frame(height: unit * 1.25)

                    // Drawer 2: ESSENTIALS (Pinned)
                    cabinetDrawer(
                        title: s.drawerEssentialsTitle,
                        subtitle: s.drawerEssentialsSub(repo.documents.filter { $0.isPinned }.count),
                        handleWidth: 80,
                        handleHeight: 12,
                        titleSize: 13.5,
                        letterSpacing: 1.2,
                        action: onOpenEssentials
                    )
                    .frame(height: unit * 0.95)

                    // Drawer 3: FILES (Cabinet)
                    cabinetDrawer(
                        title: s.drawerCabinetTitle,
                        subtitle: s.drawerCabinetSub(repo.documents.count),
                        handleWidth: 110,
                        handleHeight: 14,
                        titleSize: 16,
                        letterSpacing: 2.0,
                        action: onOpenCabinet
                    )
                    .frame(height: unit * 1.25)
                }
                .padding(innerPadding)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(CleanFilingTheme.clayCabinetGradient)
                .clipShape(RoundedRectangle(cornerRadius: 38, style: .continuous))
                .shadow(color: CleanFilingTheme.purpleDeep.opacity(0.28), radius: 14, x: 0, y: 8)
            }
            .padding(.bottom, 4)
        }
    }

    private func cabinetDrawer(
        title: String,
        subtitle: String,
        handleWidth: CGFloat,
        handleHeight: CGFloat,
        titleSize: CGFloat,
        letterSpacing: CGFloat,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            ZStack {
                RoundedRectangle(cornerRadius: 32, style: .continuous)
                    .fill(CleanFilingTheme.claySlotBg)

                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .fill(CleanFilingTheme.clayDrawerGradient)
                    .padding(4)
                    .overlay(
                        RoundedRectangle(cornerRadius: 28, style: .continuous)
                            .stroke(Color.white.opacity(0.25), lineWidth: 1)
                            .padding(4)
                    )

                VStack(spacing: 6) {
                    NeonHandleView(width: handleWidth, height: handleHeight)

                    Text(title)
                        .font(.system(size: titleSize, weight: .black))
                        .foregroundColor(.white)
                        .tracking(lang.isHebrew ? 0 : letterSpacing)

                    Text(subtitle)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(CleanFilingTheme.pillLavender)
                }
            }
        }
        .buttonStyle(.plain)
    }
}
