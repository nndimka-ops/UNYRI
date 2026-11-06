/// Presentation only. Key verification, revision checks and upload admission
/// remain in the history coordinator; this never authorizes a history operation.
enum HistorySetupStep: String {
    case prepareKey, confirmKey, enterExistingKey, unfinishedUpload
    case allowUploads, createBackup, ready

    static func next(keySaved: Bool, hasDraft: Bool, hasBackup: Bool,
                     hasUnfinishedUpload: Bool, uploadsAllowed: Bool) -> Self {
        if hasDraft { return .confirmKey }
        if !keySaved {
            if hasBackup { return .enterExistingKey }
            if hasUnfinishedUpload { return .unfinishedUpload }
            return .prepareKey
        }
        if !uploadsAllowed { return .allowUploads }
        return hasBackup ? .ready : .createBackup
    }

    var title: String {
        switch self {
        case .prepareKey: return "1 of 3 · Save your recovery key"
        case .confirmKey: return "1 of 3 · Confirm your saved key"
        case .enterExistingKey: return "Enter the key for your existing backup"
        case .unfinishedUpload: return "An upload was interrupted"
        case .allowUploads: return "2 of 3 · Allow encrypted backups"
        case .createBackup: return "3 of 3 · Create your first backup"
        case .ready: return "Your backup is ready"
        }
    }

    var detail: String {
        switch self {
        case .prepareKey: return "Start here. Save the recovery key outside UNYRI. Then you can create a copy of your messages and files."
        case .confirmKey: return "Copy or write down the entire key below, then enter its last 8 characters. Preparing a key does not upload your history."
        case .enterExistingKey: return "This server already has a backup. Enter its saved recovery key below. A new key cannot open the old copy."
        case .unfinishedUpload: return "The earlier upload is not a confirmed backup. Refresh its status or remove the unfinished server copy below. Messages on this phone stay in place."
        case .allowUploads: return "Your key is saved. Allow encrypted uploads to create or update a backup. An existing copy can still be restored while uploads are off."
        case .createBackup: return "The key is saved and uploads are allowed. Tap Create encrypted backup. Keep UNYRI open until it finishes."
        case .ready: return "Your encrypted copy is stored on the server. Keep your recovery key outside UNYRI; signing in alone does not restore history."
        }
    }
}
