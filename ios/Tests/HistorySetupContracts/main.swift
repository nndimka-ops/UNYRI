import Foundation

@main struct HistorySetupContracts {
    static func main() {
        func step(_ key: Bool, _ draft: Bool, _ backup: Bool, _ staged: Bool, _ uploads: Bool) -> HistorySetupStep {
            HistorySetupStep.next(keySaved: key, hasDraft: draft, hasBackup: backup,
                                  hasUnfinishedUpload: staged, uploadsAllowed: uploads)
        }
        // The actual first-copy journey, including a cancelled/expired draft.
        precondition(step(false, false, false, false, false) == .prepareKey)
        precondition(step(false, true, false, false, false) == .confirmKey)
        precondition(step(false, false, false, false, false) == .prepareKey)
        precondition(step(true, false, false, false, false) == .allowUploads)
        precondition(step(true, false, false, false, true) == .createBackup)
        precondition(step(true, false, true, false, true) == .ready)
        // Existing history must not be offered a newly generated replacement key.
        for uploads in [false, true] {
            precondition(step(false, false, true, false, uploads) == .enterExistingKey)
            precondition(step(false, false, true, true, uploads) == .enterExistingKey)
            precondition(step(false, false, false, true, uploads) == .unfinishedUpload)
        }
        precondition(step(true, false, true, false, false) == .allowUploads)
        precondition(step(true, false, true, true, true) == .ready)
        precondition(step(true, false, false, true, true) == .createBackup)
        print("PASS: 15 history setup transitions; presentation does not authorize crypto operations")
    }
}
