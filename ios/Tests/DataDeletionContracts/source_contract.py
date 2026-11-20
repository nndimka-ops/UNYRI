"""Portable wiring checks; executable Swift contracts test real storage on macOS."""
from pathlib import Path
import json
import sys

root = Path(__file__).resolve().parents[3]
def read(path):
    return (root / path).read_text(encoding="utf-8")

auth = read("ios/UNYRI/Auth/AuthRepository.swift")
registry = read("ios/UNYRI/Auth/AccountRegistry.swift")
session = read("ios/UNYRI/Auth/SessionStore.swift")
app = read("ios/UNYRI/UNYRIApp.swift")
messages = read("ios/UNYRI/Data/MessageStore.swift")
for code in ["account_deleted", "account_suspended", "session_revoked", "invalid_refresh_token"]:
    assert code in auth
assert "try await eraseTerminalInactive(record, error: error)" in registry
assert 'terminalInactiveCodes[record.slot]' in registry
assert '"unyri.accounts.terminalCleanup"' in registry
retire = session.split("func retireSessionForMissingWorkspace", 1)[1].split("/// Durable device-scoped", 1)[0]
assert retire.index("enqueueLogout") < retire.index("clearSession()")
assert 'case .sessionRevoked(let reason)' in app
assert 'let code = ["account_deleted", "account_suspended"].contains(reason) ? reason : "session_revoked"' in app
assert 'case .userDeleted(let userId)' in app
assert 'UIScene.willDeactivateNotification' in app
assert 'UIApplication.willResignActiveNotification' in app
wake = app.split('if userInfo["event"] as? String == "session_check"', 1)[1].split('guard userInfo["event"]', 1)[0]
assert 'checkSavedSessions()' in wake and 'finishLocalSession' not in wake and 'clearSession()' not in wake
assert "eraseSecurityAfterSignOut = true" in auth
assert "deleted-security-cleanup.json" in auth
assert "try Self.queueDeletedSecurityCleanup(origin: scope.origin, user: scope.user)" in auth
assert "restore_cleanup:" in messages and "json_remove(content_json,'$.reply_snapshot')" in messages
for file in ["Data/MessageStore.swift", "Data/AttachmentStore.swift", "Models/ChatModels.swift"]:
    text = read("ios/UNYRI/" + file)
    assert "PRAGMA secure_delete=ON;" in text
    assert "SQLITE_CHECKPOINT_TRUNCATE" in text
    assert ".erase-pending" in text
    assert "erasureCheckpoint.request()" in text
    assert "pending?.cancel()" in text
strings = json.loads(read("ios/UNYRI/Localizable.xcstrings"))["strings"]
for key in ["Account deleted", "Account suspended by the server administrator", "Session ended",
            "Delete account permanently", "Enter the account number to confirm", "Reason for deletion"]:
    assert {"en", "ru", "uk"}.issubset(strings[key]["localizations"])
admin = read("ios/UNYRI/UI/Profile/ProfileView.swift")
assert 'request("users/\\(target)/delete"' in admin
assert "deletionConfirmation == deletionIdentity" in admin
assert 'role == "owner"' in admin
direct = read("ios/UNYRI/DirectTransfer/DirectFileTransfers.swift")
assert "disk.eraseRemovedMessage(record)" in direct
workflow = read(".github/workflows/ios-build.yml")
assert "DataDeletionContracts/main.swift" in workflow and "DataDeletionContracts/source_contract.py" in workflow
print("PASS: iOS deletion auth, inactive cleanup, reinstall queue, push verification, privacy, localization and owner-action wiring")
