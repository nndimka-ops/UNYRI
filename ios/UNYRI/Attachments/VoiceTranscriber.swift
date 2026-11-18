import Combine
import CryptoKit
import Foundation
import Speech

/// Why a voice message could not be turned into text. Recognition is strictly
/// on-device: there is no network fallback, so a language the phone cannot
/// recognise by itself is reported as unavailable instead of being sent anywhere.
enum VoiceTranscriptionError: Error, Equatable {
    /// Speech Recognition is denied or restricted for this app.
    case notAuthorized
    /// No on-device model for the language (or it is not installed).
    case languageUnavailable
    /// iOS reports speech recognition as switched off system-wide.
    case recognitionDisabled
    case noSpeech
    case failed

    /// English key; shown through `UnyriL10n.string`.
    var messageKey: String {
        switch self {
        case .notAuthorized: return "Speech Recognition is turned off for UNYRI in iOS Settings."
        case .languageUnavailable: return "On-device recognition is not available for this language."
        case .recognitionDisabled: return "Siri & Dictation are turned off on this device."
        case .noSpeech: return "No speech found."
        case .failed: return "Could not turn this message into text."
        }
    }
}

/// The transcript state of ONE voice message. A bubble observes only its own
/// entry, so progress on one message never re-renders the other voice rows.
@MainActor
final class VoiceTranscriptEntry: ObservableObject {
    enum Phase: Equatable {
        case idle
        case working
        case done(String)
        /// `alternatives`: other app languages this phone can recognise on-device
        /// (only filled for `.languageUnavailable`).
        case failed(VoiceTranscriptionError, alternatives: [String])
    }

    let id: String
    @Published fileprivate(set) var phase: Phase = .idle
    /// Whether the transcript (or the error line) is open in the bubble.
    @Published fileprivate(set) var shown = false

    fileprivate init(id: String) { self.id = id }

    var isWorking: Bool { phase == .working }
    /// The transcript the user currently sees in the bubble.
    var visibleText: String? {
        if shown, case .done(let text) = phase { return text }
        return nil
    }

    fileprivate func reset() {
        if phase != .idle { phase = .idle }
        if shown { shown = false }
    }
}

/// Voice message -> text, on this device only.
///
/// Every request sets `requiresOnDeviceRecognition`, and none is started for a
/// recognizer without `supportsOnDeviceRecognition`: the audio never leaves the
/// phone (not to our server, not to Apple), it costs nothing and works offline.
/// One recognition runs at a time; the rest wait in order.
@MainActor
final class VoiceTranscriber {
    static let shared = VoiceTranscriber()

    /// The app languages, in the order they are offered.
    nonisolated static var languages: [String] { ["ru", "uk", "en"] }

    nonisolated static func localeIdentifier(_ language: String) -> String {
        switch language {
        case "ru": return "ru-RU"
        case "uk": return "uk-UA"
        default: return "en-US"
        }
    }

    /// Native names, as in the language picker.
    nonisolated static func languageName(_ language: String) -> String {
        switch language {
        case "ru": return "Русский"
        case "uk": return "Українська"
        default: return "English"
        }
    }

    /// Those of the three app languages this phone can recognise without a network.
    nonisolated static var availableLanguages: [String] {
        get async {
            await withTaskGroup(of: (String, Bool).self, returning: [String].self) { group in
                for language in languages {
                    group.addTask { (language, await supportsOnDevice(language)) }
                }
                var supported = Set<String>()
                for await (language, available) in group where available { supported.insert(language) }
                return languages.filter { supported.contains($0) }
            }
        }
    }

    private var entries: [String: VoiceTranscriptEntry] = [:]
    private var jobs: [String: (token: UUID, task: Task<Void, Never>)] = [:]
    /// Serial gate for the recognizer.
    private var recognizing = false
    private var waiting: [CheckedContinuation<Void, Never>] = []
    /// The disk folder of the signed-in account. Transcripts are kept per account:
    /// two accounts can hold the same attachment, and signing one out must leave
    /// the other's transcripts alone. Nil (no account) keeps nothing on disk.
    private var folder: String?

    private init() {}

    nonisolated static func folderName(owner: String) -> String {
        String(SHA256.hash(data: Data(owner.lowercased().utf8)).map { String(format: "%02x", $0) }.joined().prefix(32))
    }

    /// The account whose services are starting (`storageOrigin|userId`). Another
    /// account's in-memory transcripts and running jobs are dropped.
    static func bind(owner: String?) {
        let transcriber = shared
        let next = owner.map(folderName(owner:))
        guard transcriber.folder != next else { return }
        transcriber.dropMemory()
        transcriber.folder = next
        VoiceTranscriptDisk.removeLegacyFiles()
    }

    /// One account's session ended while another stays: only its transcripts go.
    static func erase(owner: String) {
        let transcriber = shared
        let target = folderName(owner: owner)
        if transcriber.folder == target { transcriber.dropMemory() }
        VoiceTranscriptDisk.removeFolder(target)
    }

    private func dropMemory() {
        for job in jobs.values { job.task.cancel() }
        jobs = [:]
        for entry in entries.values { entry.reset() }
        entries = [:]
    }

    /// The entry of one attachment (a dictionary lookup; safe to call while rendering).
    func entry(for id: String) -> VoiceTranscriptEntry {
        if let entry = entries[id] { return entry }
        if entries.count >= 600 { entries = entries.filter { $0.value.phase != .idle || $0.value.shown } }
        let entry = VoiceTranscriptEntry(id: id)
        entries[id] = entry
        return entry
    }

    /// The transcript currently open in the bubble of this attachment, if any.
    func visibleText(for id: String) -> String? { entries[id]?.visibleText }

    /// The "to text" control: start, cancel while working, hide/show a finished text,
    /// dismiss an error (the next tap tries again).
    func toggle(_ entry: VoiceTranscriptEntry, fileURL: URL?) {
        entries[entry.id] = entry
        switch entry.phase {
        case .working:
            cancel(entry)
        case .done:
            entry.shown.toggle()
        case .failed:
            entry.reset()
        case .idle:
            if let fileURL { transcribe(entry, fileURL: fileURL) }
        }
    }

    /// `language` nil: the app language, served from the cache when this message was
    /// already transcribed. A language the user picked always runs again.
    func transcribe(_ entry: VoiceTranscriptEntry, fileURL: URL, language: String? = nil) {
        let id = entry.id
        entries[id] = entry
        jobs[id]?.task.cancel()
        let token = UUID()
        let chosen = language ?? UnyriL10n.language
        let useCache = language == nil
        let folder = self.folder
        entry.shown = true
        entry.phase = .working
        let task = Task { [weak self] in
            guard let self else { return }
            let outcome = await self.produce(id: id, fileURL: fileURL, language: chosen, useCache: useCache, folder: folder)
            // Cancelled, replaced, forgotten or erased meanwhile: the result is dropped.
            guard self.jobs[id]?.token == token else { return }
            self.jobs[id] = nil
            if outcome.store, case .done(let text) = outcome.phase, let folder, folder == self.folder {
                VoiceTranscriptDisk.write(text, language: chosen, id: id, folder: folder)
            }
            entry.phase = outcome.phase
        }
        jobs[id] = (token, task)
    }

    func cancel(_ entry: VoiceTranscriptEntry) {
        jobs[entry.id]?.task.cancel()
        jobs[entry.id] = nil
        entry.reset()
    }

    /// A deleted message takes its transcript with it (memory and disk).
    static func forget(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        let transcriber = shared
        for id in ids {
            transcriber.jobs[id]?.task.cancel()
            transcriber.jobs[id] = nil
            transcriber.entries.removeValue(forKey: id)?.reset()
        }
        if let folder = transcriber.folder { VoiceTranscriptDisk.remove(ids, folder: folder) }
    }

    /// The last local session ended: no transcript of any account stays in memory or on disk.
    static func eraseAll() {
        shared.dropMemory()
        VoiceTranscriptDisk.removeAll()
    }

    // MARK: - One transcription

    private func produce(id: String, fileURL: URL, language: String, useCache: Bool,
                         folder: String?) async -> (phase: VoiceTranscriptEntry.Phase, store: Bool) {
        if useCache, let folder, let text = await VoiceTranscriptDisk.read(id: id, folder: folder) { return (.done(text), false) }
        // Checked before the permission prompt: the user is not asked for anything
        // when this phone cannot recognise the language by itself.
        guard await Self.supportsOnDevice(language) else { return (await unavailable(language), false) }
        if Task.isCancelled { return (.idle, false) }
        var status = SFSpeechRecognizer.authorizationStatus()
        if status == .notDetermined { status = await Self.requestAuthorization() }
        guard status == .authorized else { return (.failed(.notAuthorized, alternatives: []), false) }
        switch await recognizeInTurn(fileURL: fileURL, language: language) {
        case .success(let text): return (.done(text), true)
        case .failure(.languageUnavailable): return (await unavailable(language), false)
        case .failure(let error): return (.failed(error, alternatives: []), false)
        }
    }

    private func unavailable(_ language: String) async -> VoiceTranscriptEntry.Phase {
        let others = await Self.availableLanguages.filter { $0 != language }
        return .failed(.languageUnavailable, alternatives: others)
    }

    private func recognizeInTurn(fileURL: URL, language: String) async -> Result<String, VoiceTranscriptionError> {
        await acquire()
        defer { release() }
        if Task.isCancelled { return .failure(.failed) }
        // The decrypted cache copy must not be evicted while it is being read.
        let lease = MediaCacheLease(path: fileURL.path)
        let locale = Locale(identifier: Self.localeIdentifier(language))
        let result = await Self.detached { () async -> Result<String, VoiceTranscriptionError> in
            await VoiceRecognitionJob().run(fileURL: fileURL, locale: locale)
        }
        withExtendedLifetime(lease) {}
        return result
    }

    /// Async mutex on the main actor: ownership passes to the oldest waiter.
    private func acquire() async {
        if !recognizing { recognizing = true; return }
        await withCheckedContinuation { waiting.append($0) }
    }

    private func release() {
        if waiting.isEmpty { recognizing = false } else { waiting.removeFirst().resume() }
    }

    // MARK: - Speech framework (off the main thread)

    nonisolated private static func detached<T: Sendable>(_ work: @escaping @Sendable () async -> T) async -> T {
        let task = Task.detached(priority: .userInitiated, operation: work)
        return await withTaskCancellationHandler(operation: { await task.value }, onCancel: { task.cancel() })
    }

    nonisolated private static func supportsOnDevice(_ language: String) async -> Bool {
        await detached { () async -> Bool in
            guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: localeIdentifier(language))) else { return false }
            return await settledOnDeviceSupport(recognizer)
        }
    }

    /// `supportsOnDeviceRecognition` can read false right after init on some iOS
    /// versions and settle a moment later, so a negative answer is re-read briefly.
    nonisolated fileprivate static func settledOnDeviceSupport(_ recognizer: SFSpeechRecognizer) async -> Bool {
        for attempt in 0..<4 {
            if recognizer.supportsOnDeviceRecognition { return true }
            if attempt == 3 || Task.isCancelled { break }
            try? await Task.sleep(nanoseconds: 150_000_000)
        }
        return false
    }

    /// Nonisolated on purpose: the system calls the handler on an arbitrary queue.
    nonisolated private static func requestAuthorization() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in continuation.resume(returning: status) }
        }
    }
}

/// One file recognition. Holds the recognizer and its task until the single final
/// result (or error, or cancellation) arrives on the recognizer's own queue.
private final class VoiceRecognitionJob: @unchecked Sendable {
    typealias Outcome = Result<String, VoiceTranscriptionError>

    private let lock = NSLock()
    private var continuation: CheckedContinuation<Outcome, Never>?
    private var recognizer: SFSpeechRecognizer?
    private var task: SFSpeechRecognitionTask?
    private var cancelled = false

    func run(fileURL: URL, locale: Locale) async -> Outcome {
        guard let recognizer = SFSpeechRecognizer(locale: locale),
              await VoiceTranscriber.settledOnDeviceSupport(recognizer) else { return .failure(.languageUnavailable) }
        if Task.isCancelled { return .failure(.failed) }
        // Result handlers default to the main queue.
        let callbacks = OperationQueue()
        callbacks.maxConcurrentOperationCount = 1
        recognizer.queue = callbacks
        // AAC-LC .m4a is read directly from the file.
        let request = SFSpeechURLRecognitionRequest(url: fileURL)
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = false
        request.addsPunctuation = true
        request.taskHint = .dictation
        return await withTaskCancellationHandler(operation: {
            await withCheckedContinuation { continuation in
                self.begin(recognizer: recognizer, request: request, continuation: continuation)
            }
        }, onCancel: { self.cancel() })
    }

    private func begin(recognizer: SFSpeechRecognizer, request: SFSpeechURLRecognitionRequest,
                       continuation: CheckedContinuation<Outcome, Never>) {
        lock.lock()
        if cancelled {
            lock.unlock()
            continuation.resume(returning: .failure(.failed))
            return
        }
        self.continuation = continuation
        self.recognizer = recognizer
        lock.unlock()
        // The request is on-device only; without on-device support nothing is started.
        guard recognizer.supportsOnDeviceRecognition else {
            finish(.failure(.languageUnavailable))
            return
        }
        let started = recognizer.recognitionTask(with: request) { result, error in
            if let result = result, result.isFinal {
                let text = result.bestTranscription.formattedString.trimmingCharacters(in: .whitespacesAndNewlines)
                self.finish(text.isEmpty ? .failure(.noSpeech) : .success(text))
            } else if let error = error {
                self.finish(.failure(Self.classify(error)))
            }
        }
        lock.lock()
        let stale = cancelled || self.continuation == nil
        if !stale { task = started }
        lock.unlock()
        if stale { started.cancel() }
    }

    private func finish(_ outcome: Outcome) {
        lock.lock()
        let pending = continuation
        continuation = nil
        task = nil
        recognizer = nil
        lock.unlock()
        pending?.resume(returning: outcome)
    }

    private func cancel() {
        lock.lock()
        cancelled = true
        let running = task
        lock.unlock()
        running?.cancel()
        finish(.failure(.failed))
    }

    /// The Speech framework reports these as untyped NSErrors.
    private static func classify(_ error: Error) -> VoiceTranscriptionError {
        let detail = error as NSError
        #if DEBUG
        print("VoiceTranscriber: recognition error", detail.domain, detail.code)
        #endif
        switch (detail.domain, detail.code) {
        case ("kAFAssistantErrorDomain", 1110), ("kAFAssistantErrorDomain", 203): return .noSpeech
        case ("kLSRErrorDomain", 102): return .languageUnavailable   // on-device model not installed
        case ("kLSRErrorDomain", 201): return .recognitionDisabled   // Siri and Dictation are disabled
        default: return .failed
        }
    }
}

/// Transcripts on disk: one small JSON per attachment in
/// Caches/voice-transcripts/<account folder>, next to the decrypted media cache
/// that already lives on the device.
private enum VoiceTranscriptDisk {
    private struct Record: Codable {
        let text: String
        let language: String
    }

    /// Every read, write and removal goes through this queue, so an erase
    /// requested after a write always runs after it.
    private static let queue = DispatchQueue(label: "app.unyri.voice-transcripts", qos: .utility)

    private static var directory: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("voice-transcripts", isDirectory: true)
    }

    private static func file(_ id: String, folder: String) -> URL {
        let plain = !id.isEmpty && id.utf8.count <= 100 && id.utf8.allSatisfy { byte in
            (byte >= 48 && byte <= 57) || (byte >= 65 && byte <= 90) || (byte >= 97 && byte <= 122) || byte == 45 || byte == 95
        }
        let name = plain ? id : SHA256.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(folder, isDirectory: true).appendingPathComponent(name + ".json")
    }

    static func read(id: String, folder: String) async -> String? {
        await withCheckedContinuation { continuation in
            queue.async {
                let record = (try? Data(contentsOf: file(id, folder: folder))).flatMap { try? JSONDecoder().decode(Record.self, from: $0) }
                continuation.resume(returning: record.flatMap { $0.text.isEmpty ? nil : $0.text })
            }
        }
    }

    static func write(_ text: String, language: String, id: String, folder: String) {
        queue.async {
            let protection = FileProtectionType.completeUntilFirstUserAuthentication
            let target = file(id, folder: folder)
            try? FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true,
                                                     attributes: [.protectionKey: protection])
            guard let data = try? JSONEncoder().encode(Record(text: text, language: language)) else { return }
            try? data.write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    static func remove(_ ids: [String], folder: String) {
        queue.async { for id in ids { try? FileManager.default.removeItem(at: file(id, folder: folder)) } }
    }

    static func removeFolder(_ folder: String) {
        queue.sync { try? FileManager.default.removeItem(at: directory.appendingPathComponent(folder, isDirectory: true)) }
    }

    /// Files of versions that kept one flat folder for whichever account was
    /// signed in: their owner is unknown, so they are not read (a re-transcribe
    /// recreates them in the right account's folder).
    static func removeLegacyFiles() {
        queue.async {
            let manager = FileManager.default
            guard let files = try? manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else { return }
            for file in files where file.pathExtension == "json" { try? manager.removeItem(at: file) }
        }
    }

    static func removeAll() {
        queue.sync { try? FileManager.default.removeItem(at: directory) }
    }
}
