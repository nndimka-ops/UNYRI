import UIKit
import UniformTypeIdentifiers
final class LegacyShareViewController: UIViewController {
    var shareContext: NSExtensionContext?
    private var context: NSExtensionContext? { shareContext ?? extensionContext }
    private func text(_ en: String, _ ru: String, _ uk: String) -> String {
        let saved = UserDefaults(suiteName: UnyriShareStore.group)?.string(forKey: "coco.language")
        switch UnyriShareStore.language(saved: saved, preferred: Locale.preferredLanguages.first ?? "en") {
        case "uk": return uk
        case "ru": return ru
        default: return en
        }
    }
    /// Why a share could not be saved, with the numbers the person needs to act on it.
    private enum Failure: Error {
        case inboxFull
        case tooLarge(name: String, bytes: Int?, limit: Int)
        case batchTooLarge(limit: Int)
        case noSpace(needed: Int, free: Int)
        case unsupported
    }
    private func megabytes(_ bytes: Int) -> String {
        let value = Double(bytes) / 1_048_576
        return value < 10 ? String(format: "%.1f", value) : String(Int(value.rounded(.up)))
    }
    private func message(_ failure: Failure) -> String {
        switch failure {
        case .inboxFull:
            return text("UNYRI already holds files waiting to be sent. Open the app, send or cancel them, then share again.",
                        "В UNYRI уже есть файлы, которые ждут отправки. Откройте приложение, отправьте или отмените их и поделитесь снова.",
                        "В UNYRI вже є файли, що чекають на надсилання. Відкрийте застосунок, надішліть або скасуйте їх і поділіться знову.")
        case .tooLarge(let name, let bytes, let limit):
            let video = UnyriShareStore.isVideo(name)
            let actual = bytes.map { String(format: text("%@ MB", "%@ МБ", "%@ МБ"), megabytes($0)) }
                ?? String(format: text("more than %@ MB", "больше %@ МБ", "понад %@ МБ"), megabytes(limit))
            return video
                ? String(format: text("Video “%1$@” is %2$@. Videos up to %3$@ MB can be shared; UNYRI compresses them before sending.",
                       "Видео «%1$@» — %2$@. Можно поделиться видео до %3$@ МБ: UNYRI сожмёт его перед отправкой.",
                       "Відео «%1$@» — %2$@. Можна поділитися відео до %3$@ МБ: UNYRI стисне його перед надсиланням."), name, actual, megabytes(limit))
                : String(format: text("File “%1$@” is %2$@. Files up to %3$@ MB can be shared (videos up to %4$@ MB).",
                       "Файл «%1$@» — %2$@. Можно поделиться файлом до %3$@ МБ (видео до %4$@ МБ).",
                       "Файл «%1$@» — %2$@. Можна поділитися файлом до %3$@ МБ (відео до %4$@ МБ)."), name, actual, megabytes(limit), megabytes(UnyriShareStore.maxVideoBytes))
        case .batchTooLarge(let limit):
            return String(format: text("Together these files are larger than %@ MB. Share fewer at a time.",
                        "Вместе эти файлы больше %@ МБ. Поделитесь меньшим количеством за раз.",
                        "Разом ці файли перевищують %@ МБ. Поділіться меншою кількістю за раз."), megabytes(limit))
        case .noSpace(let needed, let free):
            return String(format: text("Not enough space on this iPhone: needs %1$@ MB, %2$@ MB free.",
                        "Не хватает места на iPhone: нужно %1$@ МБ, свободно %2$@ МБ.",
                        "Недостатньо місця на iPhone: потрібно %1$@ МБ, вільно %2$@ МБ."), megabytes(needed), megabytes(free))
        case .unsupported:
            return text("This item cannot be shared to UNYRI. Share it as a file.", "Этим нельзя поделиться в UNYRI. Поделитесь как файлом.", "Цим не можна поділитися в UNYRI. Поділіться як файлом.")
        }
    }
    private func message(for error: Error) -> String {
        if let failure = error as? Failure { return message(failure) }
        let ns = error as NSError
        if ns.domain == NSCocoaErrorDomain, ns.code == CocoaError.fileWriteOutOfSpace.rawValue || ns.code == NSFileWriteVolumeReadOnlyError {
            return text("Not enough space on this iPhone. Free some storage and try again.", "Не хватает места на iPhone. Освободите место и попробуйте снова.", "Недостатньо місця на iPhone. Звільніть місце та спробуйте знову.")
        }
        return text("Could not read this file. Try again or share it from another app.", "Не удалось прочитать файл. Попробуйте снова или поделитесь им из другого приложения.", "Не вдалося прочитати файл. Спробуйте знову або поділіться ним з іншого застосунку.")
    }
    private static func freeBytes(at url: URL) -> Int? {
        guard let value = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage else { return nil }
        return Int(clamping: value)
    }
    private let status = UILabel()
    private let save = UIButton(type: .system)
    private var task: Task<Void, Never>?
    private var progress: Progress?
    private let extensionIO = UnyriShareStore.InboxIO(label: "unyri.share.extension.io")
    private var preparationCancellation: UnyriShareStore.CopyCancellation?
    private var stagingRequest: UnyriShareStore.StagingRequest?
    private var cancelled = false
    private var committed = false
    private var providers: [NSItemProvider] {
        (context?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
    }
    private var countError: String { text("Choose between 1 and 10 files.", "Выберите от 1 до 10 файлов.", "Виберіть від 1 до 10 файлів.") }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(white: 32 / 255, alpha: 1)
        view.tintColor = UIColor(white: 222 / 255, alpha: 1)
        let title = UILabel(); title.text = "UNYRI"; title.font = .preferredFont(forTextStyle: .largeTitle)
        title.adjustsFontForContentSizeCategory = true; title.textColor = UIColor(white: 241 / 255, alpha: 1)
        status.text = ShareStrings.text("Open UNYRI once after updating to choose a chat here.", language: UnyriShareStore.language(saved: UserDefaults(suiteName: UnyriShareStore.group)?.string(forKey: "coco.language"), preferred: Locale.preferredLanguages.first ?? "en"))
        status.numberOfLines = 0
        status.font = .preferredFont(forTextStyle: .body); status.adjustsFontForContentSizeCategory = true
        status.textColor = UIColor(white: 241 / 255, alpha: 1)
        save.setTitle(text("Save in UNYRI", "Сохранить в UNYRI", "Зберегти в UNYRI"), for: .normal); save.addTarget(self, action: #selector(saveFiles), for: .touchUpInside)
        let cancel = UIButton(type: .system); cancel.setTitle(text("Close", "Закрыть", "Закрити"), for: .normal); cancel.addTarget(self, action: #selector(close), for: .touchUpInside)
        for button in [save, cancel] { button.titleLabel?.font = .preferredFont(forTextStyle: .headline); button.titleLabel?.adjustsFontForContentSizeCategory = true; button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true }
        let stack = UIStackView(arrangedSubviews: [title, status, save, cancel]); stack.axis = .vertical; stack.spacing = 24
        let scroll = UIScrollView(); scroll.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(scroll)
        stack.translatesAutoresizingMaskIntoConstraints = false; scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor), scroll.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor), scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 24), stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -24),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 24), stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -48)
        ])
        if !(1...10).contains(providers.count) { status.text = countError; save.isEnabled = false }
    }
    @objc private func close() {
        cancelled = true; preparationCancellation?.cancel(); stagingRequest?.cancellation.cancel(); progress?.cancel(); task?.cancel()
        if committed { context?.completeRequest(returningItems: nil) }
        else {
            do {
                // Unlink our staging tree synchronously before iOS may tear down
                // the process. This does not wait for a provider continuation.
                try stagingRequest?.cancelAndRemove()
                stagingRequest = nil
                context?.cancelRequest(withError: CocoaError(.userCancelled))
            } catch {
                status.text = text("Could not remove shared files. Tap Close to try again.", "Не удалось удалить общие файлы. Нажмите «Закрыть», чтобы повторить.", "Не вдалося видалити спільні файли. Натисніть «Закрити», щоб повторити.")
                save.isEnabled = false
            }
        }
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if !committed {
            cancelled = true; preparationCancellation?.cancel(); stagingRequest?.cancellation.cancel(); progress?.cancel(); task?.cancel()
            try? stagingRequest?.cancelAndRemove()
        }
    }
    @objc private func saveFiles() {
        guard task == nil else { return }
        let providers = self.providers
        guard (1...10).contains(providers.count) else { status.text = countError; return }
        guard let binding = UnyriShareStore.activeBinding() else {
            status.text = text("Open UNYRI and sign in to save files to your account.", "Откройте UNYRI и войдите, чтобы сохранить файлы в свой аккаунт.", "Відкрийте UNYRI та увійдіть, щоб зберегти файли у свій обліковий запис.")
            return
        }
        save.isEnabled = false; status.text = text("Saving files…", "Сохраняем файлы…", "Зберігаємо файли…")
        let preparationCancellation = UnyriShareStore.CopyCancellation()
        self.preparationCancellation = preparationCancellation
        task = Task { @MainActor in
            do {
                try Task.checkCancellation()
                guard !cancelled else { throw CancellationError() }
                let preparation = try await extensionIO.prepareExtensionShare(cancellation: preparationCancellation)
                try Task.checkCancellation()
                guard !cancelled else { throw CancellationError() }
                guard preparation.readyBatches < UnyriShareStore.maxReadyBatches else { throw Failure.inboxFull }
                // Recovery created no per-request files. Create and adopt this
                // bounded staging tree only now, before the next suspension.
                let request = try preparation.makeRequest(binding: binding)
                stagingRequest = request
                self.preparationCancellation = nil
                let cancellation = request.cancellation
                var entries: [UnyriShareStore.Entry] = []; var total = 0
                for provider in providers {
                    try Task.checkCancellation()
                    guard !cancelled else { throw CancellationError() }
                    let type = provider.registeredTypeIdentifiers.first { identifier in
                        guard let type = UTType(identifier) else { return false }
                        return type.conforms(to: .data) && type != .fileURL && type != .url
                    }
                    guard type != nil || provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) else { throw Failure.unsupported }
                    let entryID = UUID().uuidString.lowercased()
                    let itemFolder = try request.itemFolder(id: entryID)
                    let batchRemaining = UnyriShareStore.maxBatchBytes - total
                    let loaded: (String, Int) = try await withCheckedThrowingContinuation { continuation in
                        let complete: (URL?, Error?) -> Void = { url, error in
                            do {
                                try cancellation.check()
                                guard let url else { throw error ?? CocoaError(.fileReadUnknown) }
                                // The provider's temporary URL is valid only inside this callback.
                                let name = UnyriShareStore.safeName(url.lastPathComponent)
                                let scoped = url.startAccessingSecurityScopedResource()
                                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                                let limit = UnyriShareStore.limit(for: name)
                                // Declared sizes give a precise answer before any copying;
                                // the bounded copy still catches a provider that lies.
                                let declared = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? nil
                                if let declared {
                                    guard declared <= limit else { throw Failure.tooLarge(name: name, bytes: declared, limit: limit) }
                                    guard declared <= batchRemaining else { throw Failure.batchTooLarge(limit: UnyriShareStore.maxBatchBytes) }
                                    if let free = Self.freeBytes(at: itemFolder), declared + 50 * 1024 * 1024 > free {
                                        throw Failure.noSpace(needed: declared, free: free)
                                    }
                                }
                                let copyLimit = min(limit, batchRemaining)
                                var coordinationError: NSError?
                                var copied: Result<Int, Error> = .failure(CocoaError(.fileReadUnknown))
                                NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readable in
                                    copied = Result { try UnyriShareStore.copy(readable, to: itemFolder.appendingPathComponent(name), limit: copyLimit, cancellation: cancellation) }
                                }
                                if let coordinationError { throw coordinationError }
                                do { continuation.resume(returning: (name, try copied.get())) }
                                catch is UnyriShareStore.LimitExceeded {
                                    throw copyLimit < limit ? Failure.batchTooLarge(limit: UnyriShareStore.maxBatchBytes)
                                                            : Failure.tooLarge(name: name, bytes: nil, limit: limit)
                                }
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
                try request.commit(entries: entries)
                stagingRequest = nil; committed = true
                status.text = text("Saved. Open UNYRI to choose a chat and send the files.", "Сохранено. Откройте UNYRI, чтобы выбрать чат и отправить файлы.", "Збережено. Відкрийте UNYRI, щоб вибрати чат і надіслати файли.")
                save.isHidden = true
            } catch {
                if !cancelled { status.text = message(for: error); save.isEnabled = true }
            }
            if let request = stagingRequest {
                do { try request.cancelAndRemove(); stagingRequest = nil }
                catch { save.isEnabled = false }
            }
            self.preparationCancellation = nil; progress = nil; task = nil
        }
    }
}
