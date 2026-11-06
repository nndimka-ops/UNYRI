import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var auth: AuthRepository

    var body: some View {
        Group {
            switch auth.state {
            case .restoring:
                ProgressView()
            case .loggedOut:
                LoginView()
            case .sessionUnavailable:
                VStack(spacing: 16) {
                    Text("Unlock your iPhone to access the saved session. Your messages have not been removed.")
                        .multilineTextAlignment(.center)
                    Button("Retry") { Task { await auth.bootstrap() } }
                }.padding()
            case .cleanupFailed(let message):
                VStack(spacing: 16) {
                    Text(message).multilineTextAlignment(.center)
                    Button("Retry cleanup") { Task { await auth.retryLocalCleanup() } }
                }.padding()
            case .loggedIn:
                if auth.needsHistoryChoice {
                    HistoryWelcomeView()
                } else {
                AccountContentView(auth: auth)
                    .id(auth.sessionGeneration)
                }
            }
        }
    }
}

private struct HistoryWelcomeView: View {
    @EnvironmentObject private var auth: AuthRepository
    @State private var error: String?
    var body: some View {
        VStack(spacing: 20) {
            Text("History on this phone").font(.title2.bold())
            Text("Previous history is not automatically restored. Use your history recovery key or transfer a prepared encrypted copy by QR from your old phone. You can also start messaging now. Keep your old phone until you have checked the restored history and files.")
                .multilineTextAlignment(.center)
            if let error { Text(error).font(.footnote) }
            Button("Restore or transfer history") {
                do {
                    UserDefaults.standard.set(auth.currentUser?.id, forKey: "openHistoryBackupForAccount")
                    try auth.startWithoutRestoredHistory()
                } catch { self.error = error.localizedDescription }
            }.unyriProminentAction()
            Button("Start without restored history") {
                do { try auth.startWithoutRestoredHistory() }
                catch { self.error = error.localizedDescription }
            }.unyriProminentAction()
            Button("Sign out") { Task { await auth.logout() } }
        }.padding(24)
    }
}
