import SwiftUI

struct DocumentCardView: View {
    let doc: FilingDocument
    let onOpen: () -> Void
    let onShare: () -> Void
    let onEdit: () -> Void
    let onTogglePin: () -> Void
    let onToggleTemporary: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .center, spacing: 10) {
                    Text(doc.fileTypeIcon)
                        .font(.system(size: 22))

                    Text(doc.name)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(CleanFilingTheme.textDark)
                        .lineLimit(1)

                    Spacer(minLength: 6)

                    HStack(spacing: 5) {
                        if !doc.date.isEmpty {
                            Text(doc.date)
                                .font(.system(size: 11, weight: .medium))
                                .foregroundColor(CleanFilingTheme.textSubtle)
                        }
                        if doc.isSynced || doc.driveFileId != nil {
                            Text("☁️")
                                .font(.system(size: 13))
                        }
                    }
                }

                if !doc.labelList.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(doc.labelList, id: \.self) { label in
                                Text("🏷️ \(label)")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(CleanFilingTheme.purpleDark)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(CleanFilingTheme.pillLavender.opacity(0.65))
                                    .clipShape(Capsule())
                            }
                        }
                    }
                }

                // Centered Action Row (matching Android card actions)
                HStack(spacing: 12) {
                    Spacer()

                    actionIcon(systemName: "square.and.arrow.up", tint: CleanFilingTheme.purpleDark, action: onShare)
                    actionIcon(systemName: "pencil", tint: CleanFilingTheme.textSubtle, action: onEdit)
                    actionIcon(
                        systemName: doc.isPinned ? "pin.fill" : "pin",
                        tint: doc.isPinned ? CleanFilingTheme.redPin : CleanFilingTheme.textSubtle,
                        action: onTogglePin
                    )
                    actionIcon(
                        systemName: "hourglass.bottomhalf.filled",
                        tint: doc.isTemporary ? CleanFilingTheme.amberGold : CleanFilingTheme.textSubtle.opacity(0.4),
                        action: onToggleTemporary
                    )
                    actionIcon(systemName: "trash", tint: CleanFilingTheme.roseRed, action: onDelete)

                    Spacer()
                }
                .padding(.top, 2)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(CleanFilingTheme.cardWhite)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(CleanFilingTheme.borderColor, lineWidth: 1)
            )
            .shadow(color: Color.black.opacity(0.05), radius: 5, x: 0, y: 2)
        }
        .buttonStyle(.plain)
    }

    private func actionIcon(systemName: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(tint)
                .frame(width: 34, height: 34)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
