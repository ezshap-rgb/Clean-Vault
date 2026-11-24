import Foundation
import GoogleSignIn
import UIKit

enum GoogleAuthError: LocalizedError {
    case missingClientId
    case noRootViewController
    case notSignedIn
    case missingEmail
    case scopeDenied

    var errorDescription: String? {
        switch self {
        case .missingClientId:
            return "Google iOS OAuth Client ID is not configured yet. Set IOS_GOOGLE_CLIENT_ID in GitHub Secrets or Config/GoogleAuth.xcconfig."
        case .noRootViewController:
            return "Unable to find an active window to present Google Sign-In."
        case .notSignedIn:
            return "Not signed into Google Drive. Please tap Sign in with Google in Settings."
        case .missingEmail:
            return "Google account email was not returned."
        case .scopeDenied:
            return "Google Drive file permission was not granted."
        }
    }
}

@MainActor
final class GoogleAuthManager: ObservableObject {
    static let driveScope = "https://www.googleapis.com/auth/drive.file"

    @Published var signedInEmail: String?

    var clientId: String {
        (Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    var isConfigured: Bool {
        !clientId.isEmpty &&
        !clientId.contains("REPLACE_WITH_IOS_CLIENT_ID") &&
        clientId.hasSuffix(".apps.googleusercontent.com")
    }

    func restoreSession() async {
        guard isConfigured else {
            signedInEmail = nil
            return
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientId)
        do {
            let user = try await GIDSignIn.sharedInstance.restorePreviousSignIn()
            signedInEmail = user.profile?.email
        } catch {
            signedInEmail = GIDSignIn.sharedInstance.currentUser?.profile?.email
        }
    }

    func signIn() async throws -> String {
        guard isConfigured else { throw GoogleAuthError.missingClientId }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientId)

        guard let presenter = Self.topViewController() else {
            throw GoogleAuthError.noRootViewController
        }

        let result = try await GIDSignIn.sharedInstance.signIn(
            withPresenting: presenter,
            hint: nil,
            additionalScopes: [Self.driveScope]
        )

        var user = result.user
        let granted = user.grantedScopes ?? []
        if !granted.contains(Self.driveScope) {
            let scopeResult = try await user.addScopes([Self.driveScope], presenting: presenter)
            user = scopeResult.user
            guard (user.grantedScopes ?? []).contains(Self.driveScope) else {
                throw GoogleAuthError.scopeDenied
            }
        }

        guard let email = user.profile?.email, !email.isEmpty else {
            throw GoogleAuthError.missingEmail
        }

        signedInEmail = email
        return email
    }

    func freshAccessToken() async throws -> String {
        guard isConfigured else { throw GoogleAuthError.missingClientId }
        if GIDSignIn.sharedInstance.currentUser == nil {
            await restoreSession()
        }
        guard let user = GIDSignIn.sharedInstance.currentUser else {
            throw GoogleAuthError.notSignedIn
        }
        let refreshed = try await user.refreshTokensIfNeeded()
        return refreshed.accessToken.tokenString
    }

    func signOut() {
        GIDSignIn.sharedInstance.signOut()
        signedInEmail = nil
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }

        let keyWindow = scenes
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
            ?? scenes.flatMap { $0.windows }.first

        var top = keyWindow?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}
