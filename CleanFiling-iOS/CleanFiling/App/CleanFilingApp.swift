import GoogleSignIn
import SwiftUI

@main
struct CleanFilingApp: App {
    @StateObject private var languageManager = LanguageManager()
    @StateObject private var authManager: GoogleAuthManager
    @StateObject private var repository: FilingRepository

    init() {
        let auth = GoogleAuthManager()
        let repo = FilingRepository(authManager: auth)
        _authManager = StateObject(wrappedValue: auth)
        _repository = StateObject(wrappedValue: repo)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(languageManager)
                .environmentObject(authManager)
                .environmentObject(repository)
                .environment(\.layoutDirection, languageManager.layoutDirection)
                .preferredColorScheme(.light)
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
                .task {
                    await authManager.restoreSession()
                    if let email = authManager.signedInEmail,
                       repository.googleEmail.isEmpty,
                       repository.activeCloudMode == .googleDrive {
                        repository.setGoogleAccount(email: email)
                    }
                    if repository.hasCloudConnection {
                        await repository.syncWithCloud(silent: true)
                    }
                }
        }
    }
}
