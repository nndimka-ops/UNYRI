import UIKit
import UniformTypeIdentifiers
final class ShareViewController: UIViewController {
    private func text(_ en: String, _ ru: String) -> String {
        let saved = UserDefaults(suiteName: UnyriShareStore.group)?.string(forKey: "coco.language")
        return (saved ?? Locale.preferredLanguages.first ?? "en").hasPrefix("ru") ? ru : en
    }
    private let status = UILabel()
    private let save = UIButton(type: .system)
    private var task: Task<Void, Never>?
    private var progress: Progress?
    private var cancelled = false
    private var committed = false
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let title = UILabel(); title.text = "UNYRI"; title.font = .boldSystemFont(ofSize: 28)
        status.text = text("Save files in UNYRI? Then open the app, choose a chat and confirm sending. Until confirmation the files stay on this device.", "Сохранить файлы в UNYRI? Затем откройте приложение, выберите чат и подтвердите отправку. До подтверждения файлы остаются только на этом устройстве.")
        status.numberOfLines = 0
        save.setTitle(text("Save in UNYRI", "Сохранить в UNYRI"), for: .normal); save.addTarget(self, action: #selector(saveFiles), for: .touchUpInside)
        let cancel = UIButton(type: .system); cancel.setTitle(text("Close", "Закрыть"), for: .normal); cancel.addTarget(self, action: #selector(close), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [title, status, save, cancel]); stack.axis = .vertical; stack.spacing = 24
        stack.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24), stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24), stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor)])
    }
    @objc private func close() {
        cancelled = true; progress?.cancel(); task?.cancel()
        if committed { extensionContext?.completeRequest(returningItems: nil) }
        else { extensionContext?.cancelRequest(withError: CocoaError(.userCancelled)) }
    }
    @objc private func saveFiles() {
        guard task == nil else { return }
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        guard (1...10).contains(providers.count) else { status.text = text("Choose between 1 and 10 files.", "Выберите от 1 до 10 файлов."); return }
        save.isEnabled = false; status.text = text("Saving files…", "Сохраняем файлы…")
        task = Task { @MainActor in
            var staging: URL?
            do {
                let root = try UnyriShareStore.root()
                // Bound inbox growth; never remove files belonging to pending sends.
                let contents = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.creationDateKey])
                for abandoned in contents where abandoned.pathExtension == "staging" {
                    if let created = try abandoned.resourceValues(forKeys: [.creationDateKey]).creationDate, Date().timeIntervalSince(created) > 86400 {
                        try? FileManager.default.removeItem(at: abandoned)
                    }
                }
                let ready = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil).filter { $0.pathExtension == "ready" }
                guard ready.count < 4 else { throw CocoaError(.fileWriteOutOfSpace) }
                let id = UUID().uuidString.lowercased()
                let directory = root.appendingPathComponent(id + ".staging", isDirectory: true); staging = directory
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
                var entries: [UnyriShareStore.Entry] = []; var total = 0
                for provider in providers {
                    try Task.checkCancellation()
                    guard !cancelled else { throw CancellationError() }
                    let type = provider.registeredTypeIdentifiers.first { identifier in
                        guard let type = UTType(identifier) else { return false }
                        return type.conforms(to: .data) && type != .fileURL && type != .url
                    }
                    guard type != nil || provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) else { throw CocoaError(.fileReadUnknown) }
                    let entryID = UUID().uuidString.lowercased()
                    let itemFolder = directory.appendingPathComponent(entryID, isDirectory: true)
                    try FileManager.default.createDirectory(at: itemFolder, withIntermediateDirectories: true)
                    let remaining = min(UnyriShareStore.maxFileBytes, UnyriShareStore.maxBatchBytes - total)
                    let loaded: (String, Int) = try await withCheckedThrowingContinuation { continuation in
                        let complete: (URL?, Error?) -> Void = { url, error in
                            do {
                                guard let url else { throw error ?? CocoaError(.fileReadUnknown) }
                                // The provider's temporary URL is valid only inside this callback.
                                let name = UnyriShareStore.safeName(url.lastPathComponent)
                                let scoped = url.startAccessingSecurityScopedResource()
                                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                                var coordinationError: NSError?
                                var copied: Result<Int, Error> = .failure(CocoaError(.fileReadUnknown))
                                NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readable in
                                    copied = Result { try UnyriShareStore.copy(readable, to: itemFolder.appendingPathComponent(name), limit: remaining) }
                                }
                                if let coordinationError { throw coordinationError }
                                continuation.resume(returning: (name, try copied.get()))
                            } catch { continuation.resume(throwing: error) }
                        }
                        if let type {
                            progress = provider.loadFileRepresentation(forTypeIdentifier: type, completionHandler: complete)
                        } else {
                            progress = nil
                            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { value, error in complete(value as? URL, error) }
                        }
                    }
                    entries.append(.init(id: entryID, name: loaded.0)); total += loaded.1
                }
                try Task.checkCancellation()
                guard !cancelled else { throw CancellationError() }
                try JSONEncoder().encode(UnyriShareStore.Batch(entries: entries)).write(to: directory.appendingPathComponent("manifest.json"), options: [.atomic, .completeFileProtection])
                try FileManager.default.moveItem(at: directory, to: root.appendingPathComponent(id + ".ready", isDirectory: true))
                staging = nil; committed = true
                status.text = text("Saved. Open UNYRI to choose a chat and send the files.", "Сохранено. Откройте UNYRI, чтобы выбрать чат и отправить файлы.")
                save.isHidden = true
            } catch {
                if !cancelled { status.text = text("Could not save. Limits: 10 files, 100 MB per file and 250 MB total. Open UNYRI if its inbox is full and check free storage.", "Не удалось сохранить. Максимум: 10 файлов, 100 МБ на файл и 250 МБ всего. Если входящие заполнены — откройте UNYRI. Проверьте свободное место."); save.isEnabled = true }
            }
            if let staging { try? FileManager.default.removeItem(at: staging) }
            progress = nil; task = nil
        }
    }
}
