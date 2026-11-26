import SwiftUI

extension Color {
    init(hex: UInt, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}

enum CleanFilingTheme {
    // 3D Claymorphic Palette (matches Android cabinet design)
    static let clayAppBg = Color(hex: 0xF4F2FA)
    static let clayPageBg = Color(hex: 0xEDE9F6)

    static let clayCasingTop = Color(hex: 0xA855F7)
    static let clayCasingMid = Color(hex: 0x7C3AED)
    static let clayCasingBottom = Color(hex: 0x6D28D9)

    static let clayDrawerTop = Color(hex: 0xB57BFB)
    static let clayDrawerMid = Color(hex: 0x8B5CF6)
    static let clayDrawerBottom = Color(hex: 0x7C3AED)

    static let claySlotBg = Color(hex: 0x4C1D95)

    static let clayScanTop = Color(hex: 0x8B5CF6)
    static let clayScanBottom = Color(hex: 0x6D28D9)

    static let neonAmberTop = Color(hex: 0xFFF3BF)
    static let neonAmberMid = Color(hex: 0xFFB703)
    static let neonAmberBottom = Color(hex: 0xFB8500)

    static let textDark = Color(hex: 0x1E1B4B)
    static let textSubtle = Color(hex: 0x64748B)
    static let textFaint = Color(hex: 0x94A3B8)
    static let textLavender = Color(hex: 0xDDD6FE)

    static let cardWhite = Color.white
    static let pillLavender = Color(hex: 0xDDD6FE)
    static let purpleDark = Color(hex: 0x6D28D9)
    static let purpleAccent = Color(hex: 0x8B5CF6)
    static let purpleDeep = Color(hex: 0x4C1D95)

    static let surfaceRaised = Color(hex: 0xEDE9F6)
    static let surfaceHover = Color(hex: 0xE2DCF0)
    static let borderColor = Color(hex: 0xE2DCF0)
    static let borderHighlight = Color(hex: 0xDDD6FE)

    static let amberGold = Color(hex: 0xFB8500)
    static let emeraldGreen = Color(hex: 0x10B981)
    static let roseRed = Color(hex: 0xEF4444)
    static let redPin = Color(hex: 0xEF4444)
    static let softRedBg = Color(hex: 0xFFEBEE)
    static let softAmberBg = Color(hex: 0xFFF8E1)
    static let softGreenBg = Color(hex: 0xF0FDF4)
    static let softPurpleBg = Color(hex: 0xF5F3FF)

    // Gradients
    static let clayCabinetGradient = LinearGradient(
        colors: [clayCasingTop, clayCasingMid, clayCasingBottom],
        startPoint: .top,
        endPoint: .bottom
    )

    static let clayDrawerGradient = LinearGradient(
        colors: [clayDrawerTop, clayDrawerMid, clayDrawerBottom],
        startPoint: .top,
        endPoint: .bottom
    )

    static let clayScanCardGradient = LinearGradient(
        colors: [clayScanTop, clayScanBottom],
        startPoint: .top,
        endPoint: .bottom
    )

    static let neonAmberHandle = LinearGradient(
        colors: [neonAmberTop, neonAmberMid, neonAmberBottom],
        startPoint: .top,
        endPoint: .bottom
    )
}

struct NeonHandleView: View {
    var width: CGFloat = 110
    var height: CGFloat = 14

    var body: some View {
        Capsule()
            .fill(CleanFilingTheme.neonAmberHandle)
            .frame(width: width, height: height)
            .overlay(
                Capsule()
                    .stroke(Color.white.opacity(0.6), lineWidth: 0.8)
            )
            .shadow(color: CleanFilingTheme.amberGold.opacity(0.55), radius: 6, x: 0, y: 2)
    }
}
