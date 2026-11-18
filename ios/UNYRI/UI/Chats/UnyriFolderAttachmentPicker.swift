import Foundation

struct UnyriPickedDocument: Identifiable {
    let source: AttachmentFileCopy.DocumentSource
    var id: String { source.url.standardizedFileURL.absoluteString }
    var name: String { source.url.lastPathComponent }
}

/// Native Files picks one file at a time; the app owns the ordered ten-item bound.
struct UnyriFileSelection {
    enum Admission { case added, duplicate, full, invalid }
    let capacity: Int
    private(set) var files: [UnyriPickedDocument] = []
    init(capacity: Int) { self.capacity = min(10, max(0, capacity)) }
    var canAdd: Bool { files.count < capacity }
    @discardableResult mutating func append(_ urls: [URL],
        makeSource: (URL) -> AttachmentFileCopy.DocumentSource = { AttachmentFileCopy.DocumentSource($0) }) -> Admission {
        guard urls.count == 1, let url = urls.first, url.isFileURL, !url.hasDirectoryPath else { return .invalid }
        let id = url.standardizedFileURL.absoluteString
        if files.contains(where: { $0.id == id }) { return .duplicate }
        guard canAdd else { return .full }
        // Called synchronously by the picker delegate, before UIKit releases its URLs.
        files.append(UnyriPickedDocument(source: makeSource(url)))
        return .added
    }
    mutating func remove(_ id: String) { files.removeAll { $0.id == id } }
}

/// UIKit delegate completion can arrive after the system sheet has disappeared.
/// Only the current request's explicit callback owns its result; dismissal alone is not cancellation.
struct UnyriFolderDialogLifecycle {
    enum Request { case initial, change }
    enum Outcome { case pending, picked, cancelled }
    private(set) var request = Request.initial
    private(set) var outcome = Outcome.pending
    private(set) var requestID = UUID()
    private(set) var isFinished = false
    mutating func begin(initial: Bool) -> UUID? {
        guard !isFinished else { return nil }
        request = initial ? .initial : .change
        outcome = .pending; requestID = UUID()
        return requestID
    }
    mutating func picked(_ id: UUID) -> Bool {
        guard !isFinished, requestID == id, outcome == .pending else { return false }
        outcome = .picked; return true
    }
    mutating func cancelled(_ id: UUID) -> Bool {
        guard !isFinished, requestID == id, outcome == .pending else { return false }
        outcome = .cancelled; return true
    }
    @discardableResult mutating func finish() -> Bool {
        guard !isFinished else { return false }
        isFinished = true; return true
    }
}

#if !UNYRI_FOLDER_SELECTION_CONTRACT
import SwiftUI
import UIKit
import UniformTypeIdentifiers

@MainActor
final class UnyriFolderAttachmentModel: ObservableObject {
    @Published private(set) var selection: UnyriFileSelection
    @Published private(set) var failed = false
    @Published private(set) var duplicate = false
    private(set) var fileDialog = UnyriFolderDialogLifecycle()
    init(capacity: Int) { selection = UnyriFileSelection(capacity: capacity) }
    func beginFileRequest(initial: Bool) -> UUID? {
        guard selection.canAdd else { return nil }
        failed = false; duplicate = false
        return fileDialog.begin(initial: initial)
    }
    @discardableResult func pickFiles(_ urls: [URL], requestID: UUID) -> Bool {
        guard fileDialog.picked(requestID) else { return false }
        let result = selection.append(urls)
        failed = result == .invalid || result == .full
        duplicate = result == .duplicate
        return true
    }
    func cancelFileRequest(_ requestID: UUID) -> Bool? {
        guard fileDialog.cancelled(requestID) else { return nil }
        guard fileDialog.request == .initial else { return false }
        guard finish() else { return nil }
        return true
    }
    @discardableResult func finish() -> Bool { fileDialog.finish() }
    func remove(_ id: String) { selection.remove(id) }
    func documents() -> [AttachmentFileCopy.DocumentSource] { selection.files.map(\.source) }
}

struct UnyriFolderAttachmentPicker: View {
    @StateObject private var model: UnyriFolderAttachmentModel
    let add: ([AttachmentFileCopy.DocumentSource]) -> Void
    let close: () -> Void
    private struct FileRequest: Identifiable { let id: UUID }
    @State private var fileRequest: FileRequest?
    @State private var askedForFile = false
    init(capacity: Int, add: @escaping ([AttachmentFileCopy.DocumentSource]) -> Void, close: @escaping () -> Void) {
        _model = StateObject(wrappedValue: UnyriFolderAttachmentModel(capacity: capacity))
        self.add = add; self.close = close
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Text("Selected files")
                        Spacer()
                        Text("\(model.selection.files.count)/\(model.selection.capacity)")
                            .monospacedDigit().accessibilityIdentifier("folder-selection-count")
                    }
                    Button("Add more") {
                        if let id = model.beginFileRequest(initial: false) { fileRequest = FileRequest(id: id) }
                    }.disabled(!model.selection.canAdd)
                        .accessibilityIdentifier("files-add-more")
                    if model.failed {
                        Text("Could not prepare the file.")
                            .font(.footnote).foregroundColor(UnyriColors.secondaryText)
                            .accessibilityIdentifier("file-load-error")
                    }
                    if model.duplicate {
                        Text(UnyriL10n.string("This file is already selected."))
                            .font(.footnote).foregroundColor(UnyriColors.secondaryText)
                            .accessibilityIdentifier("file-duplicate-notice")
                    }
                }
                if !model.selection.files.isEmpty {
                    Section("Selected") {
                        ForEach(Array(model.selection.files.enumerated()), id: \.element.id) { index, file in
                            Button { model.remove(file.id) } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "doc").frame(width: 26).accessibilityHidden(true)
                                    Text(file.name).lineLimit(2).foregroundColor(UnyriColors.primaryText)
                                    Spacer(minLength: 8)
                                    Text("\(index + 1)").font(.subheadline.monospacedDigit().weight(.semibold))
                                    Image(systemName: "checkmark.circle.fill").font(.system(size: 22)).accessibilityHidden(true)
                                }.frame(minHeight: 44).contentShape(Rectangle())
                            }.buttonStyle(.plain).foregroundColor(UnyriColors.primaryText)
                                .accessibilityLabel(Text(file.name))
                                .accessibilityValue(Text("\(index + 1)"))
                                .accessibilityAddTraits(.isSelected)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped).unyriScreenBackground().tint(UnyriColors.primaryText)
            .navigationTitle("Files").navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("folder-attachment-picker")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { guard model.finish() else { return }; close() }
                        .frame(minWidth: 44, minHeight: 44).accessibilityIdentifier("folder-cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add (\(model.selection.files.count))") {
                        let documents = model.documents()
                        guard !documents.isEmpty, model.finish() else { return }
                        add(documents)
                    }.disabled(model.selection.files.isEmpty)
                        .frame(minWidth: 44, minHeight: 44).accessibilityIdentifier("folder-add")
                }
            }
        }
        .sheet(item: $fileRequest) { request in
            UnyriSystemFilePicker(picked: { urls in
                guard model.pickFiles(urls, requestID: request.id) else { return }
                fileRequest = nil
            }, cancelled: {
                guard let closeInitial = model.cancelFileRequest(request.id) else { return }
                fileRequest = nil
                if closeInitial { close() }
            })
        }
        .onAppear {
            if !askedForFile {
                askedForFile = true
                if let id = model.beginFileRequest(initial: true) { fileRequest = FileRequest(id: id) }
            }
        }
    }
}

/// Files this phone sent recently, for the attach sheet's Files tab. Local copies
/// in Caches (bounded, cancellable copies of files up to 100 MiB); the OS may
/// purge them, and logout erases them with the rest of the account's files.
enum UnyriRecentFiles {
    struct Source: Sendable {
        let url: URL
        var keepAlive: AttachmentFileCopy.DocumentSource? = nil
    }
    struct Entry: Codable, Identifiable, Equatable {
        let id: String
        let name: String
        let bytes: Int64
        let date: Date
        let file: String
    }
    static let limit = 40
    static let maxBytes: Int64 = 100 * 1024 * 1024
    private static let queue = DispatchQueue(label: "unyri.recent-files")
    // Index readers must not wait behind a large file copy on the main thread.
    static let copyQueue = DispatchQueue(label: "unyri.recent-files.copy", qos: .utility)
    /// One list per account (UnyriAccountSlot): files sent from one account are
    /// never offered in the other. The primary account keeps the historical folder.
    static func folder(for slot: UnyriAccountSlot) -> URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(slot.isPrimary ? "unyri-recent-files" : "unyri-recent-files-" + slot.id, isDirectory: true)
    }
    private static var directory: URL { folder(for: UnyriActiveAccountSlot.current) }
    static func url(for entry: Entry) -> URL { directory.appendingPathComponent(entry.file) }

    static func load() -> [Entry] {
        queue.sync { readIndex().filter { FileManager.default.fileExists(atPath: url(for: $0).path) } }
    }
    /// Called only inside the awaited copy scope, with a captured account folder.
    /// Source grants and durable reference ownership span this entire worker.
    static func record(_ sources: [Source], into owner: URL, cancellation: ChatComposerRecentFileCopy.Cancellation) throws {
        precondition(!Thread.isMainThread, "Recent-file copying must not run on the main thread")
        func fromRecent(_ source: Source) -> Bool {
            source.url.standardizedFileURL.resolvingSymlinksInPath().path
                .hasPrefix(owner.standardizedFileURL.resolvingSymlinksInPath().path + "/")
        }
        // Bump existing picks before admitting fresh copies which could prune them.
        let ordered = sources.filter(fromRecent) + sources.filter { !fromRecent($0) }
        for source in ordered {
            defer { withExtendedLifetime(source.keepAlive) {} }
            try cancellation.check()
            do { try copyIn(source.url, into: owner, cancellation: cancellation) }
            catch is CancellationError { throw CancellationError() }
            catch { /* Recent files are a best-effort cache; keep sending on IO failure. */ }
        }
    }
    /// True for a file inside this account's Recent files folder.
    static func contains(_ url: URL) -> Bool {
        url.standardizedFileURL.resolvingSymlinksInPath().path
            .hasPrefix(directory.standardizedFileURL.resolvingSymlinksInPath().path + "/")
    }
    static func remove(_ id: String) {
        queue.sync {
            var entries = readIndex()
            for entry in entries where entry.id == id { try? FileManager.default.removeItem(at: url(for: entry).deletingLastPathComponent()) }
            entries.removeAll { $0.id == id }
            writeIndex(entries)
        }
    }
    static func eraseAll() { queue.sync { try? FileManager.default.removeItem(at: directory) } }

    private static func copyIn(_ source: URL, into directory: URL, cancellation: ChatComposerRecentFileCopy.Cancellation) throws {
        let manager = FileManager.default
        // Sending a file picked from this list: move its entry to the top and leave the
        // file where it is. Copying it and deleting the "old" copy deleted the very file
        // the message was still reading (a direct send failed with "no such file").
        let sourcePath = source.standardizedFileURL.resolvingSymlinksInPath().path
        let rootPath = directory.standardizedFileURL.resolvingSymlinksInPath().path
        if sourcePath.hasPrefix(rootPath + "/") {
            try queue.sync {
                try cancellation.check()
                var entries = readIndex(in: directory)
                guard let index = entries.firstIndex(where: {
                    directory.appendingPathComponent($0.file).standardizedFileURL.resolvingSymlinksInPath().path == sourcePath
                }) else { return }
                let entry = entries.remove(at: index)
                entries.insert(Entry(id: entry.id, name: entry.name, bytes: entry.bytes, date: Date(), file: entry.file), at: 0)
                writeIndex(entries, in: directory)
            }
            return
        }
        guard let size = (try? manager.attributesOfItem(atPath: source.path)[.size] as? NSNumber)?.int64Value,
              size > 0, size <= maxBytes else { return }
        let name = source.lastPathComponent
        let id = UUID().uuidString.lowercased()
        let folder = directory.appendingPathComponent(id, isDirectory: true)
        do {
            try manager.createDirectory(at: folder, withIntermediateDirectories: true)
            let reader = AttachmentFileCopy.DocumentRead()
            try cancellation.withReaderCancellation({ reader.cancel() }) {
                try reader.copy(from: source, to: folder.appendingPathComponent(name), maxBytes: maxBytes,
                    allocation: { _, _ in try cancellation.check() })
            }
            try queue.sync {
                try cancellation.check()
                var entries = readIndex(in: directory)
                // A recently used entry may still be the source of a queued send.
                let inUse = Date().addingTimeInterval(-120)
                for old in entries where old.name == name && old.bytes == size && old.date < inUse {
                    try? manager.removeItem(at: directory.appendingPathComponent(old.file).deletingLastPathComponent())
                }
                entries.removeAll { $0.name == name && $0.bytes == size && $0.date < inUse }
                entries.insert(Entry(id: id, name: name, bytes: size, date: Date(), file: id + "/" + name), at: 0)
                let kept = entries.enumerated().filter { $0.offset < limit || $0.element.date >= inUse }.map(\.element)
                for old in entries where !kept.contains(old) { try? manager.removeItem(at: directory.appendingPathComponent(old.file).deletingLastPathComponent()) }
                writeIndex(kept, in: directory)
            }
        } catch { try? manager.removeItem(at: folder); throw error }
    }
    private static func readIndex(in folder: URL? = nil) -> [Entry] {
        guard let data = try? Data(contentsOf: (folder ?? directory).appendingPathComponent("index.json")) else { return [] }
        return (try? JSONDecoder().decode([Entry].self, from: data)) ?? []
    }
    private static func writeIndex(_ entries: [Entry], in folder: URL? = nil) {
        let target = folder ?? directory
        try? FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(entries) { try? data.write(to: target.appendingPathComponent("index.json"), options: .atomic) }
    }
}

/// System Files with multi-select; security-scoped grants are taken synchronously
/// inside the delegate callback (before UIKit releases the URLs).
struct UnyriMultiFilePicker: UIViewControllerRepresentable {
    let limit: Int
    let picked: ([AttachmentFileCopy.DocumentSource]) -> Void
    let cancelled: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(limit: limit, picked: picked, cancelled: cancelled) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: false)
        picker.allowsMultipleSelection = limit > 1; picker.shouldShowFileExtensions = true
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let limit: Int
        let picked: ([AttachmentFileCopy.DocumentSource]) -> Void
        let cancelled: () -> Void
        init(limit: Int, picked: @escaping ([AttachmentFileCopy.DocumentSource]) -> Void, cancelled: @escaping () -> Void) {
            self.limit = limit; self.picked = picked; self.cancelled = cancelled
        }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            var seen = Set<String>()
            let files = urls.filter { $0.isFileURL && !$0.hasDirectoryPath && seen.insert($0.standardizedFileURL.absoluteString).inserted }
            picked(files.prefix(max(1, limit)).map { AttachmentFileCopy.DocumentSource($0) })
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { cancelled() }
    }
}

#if canImport(VisionKit)
import VisionKit

/// VisionKit document camera → one multi-page PDF in the temporary directory.
struct UnyriDocumentScanner: UIViewControllerRepresentable {
    static var isAvailable: Bool { VNDocumentCameraViewController.isSupported }
    let finished: (URL?) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(finished: finished) }
    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {}
    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let finished: (URL?) -> Void
        init(finished: @escaping (URL?) -> Void) { self.finished = finished }
        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            guard scan.pageCount > 0 else { finished(nil); return }
            let formatter = DateFormatter(); formatter.dateFormat = "yyyy-MM-dd HH.mm"
            let name = UnyriL10n.string("Scan") + " " + formatter.string(from: Date()) + ".pdf"
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            let file = url.appendingPathComponent(name)
            let first = scan.imageOfPage(at: 0)
            let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: first.size))
            do {
                try renderer.writePDF(to: file) { context in
                    for index in 0..<scan.pageCount {
                        let page = scan.imageOfPage(at: index)
                        context.beginPage(withBounds: CGRect(origin: .zero, size: page.size), pageInfo: [:])
                        page.draw(in: CGRect(origin: .zero, size: page.size))
                    }
                }
                finished(file)
            } catch { finished(nil) }
        }
        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) { finished(nil) }
        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) { finished(nil) }
    }
}
#endif

private struct UnyriSystemFilePicker: UIViewControllerRepresentable {
    let picked: ([URL]) -> Void
    let cancelled: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(picked: picked, cancelled: cancelled) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: false)
        picker.allowsMultipleSelection = false; picker.shouldShowFileExtensions = true
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let picked: ([URL]) -> Void
        let cancelled: () -> Void
        init(picked: @escaping ([URL]) -> Void, cancelled: @escaping () -> Void) {
            self.picked = picked; self.cancelled = cancelled
        }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            picked(urls)
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { cancelled() }
    }
}
#endif
