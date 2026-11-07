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
    private(set) var fileDialog = UnyriFolderDialogLifecycle()
    init(capacity: Int) { selection = UnyriFileSelection(capacity: capacity) }
    func beginFileRequest(initial: Bool) -> UUID? {
        guard selection.canAdd else { return nil }
        failed = false
        return fileDialog.begin(initial: initial)
    }
    @discardableResult func pickFiles(_ urls: [URL], requestID: UUID) -> Bool {
        guard fileDialog.picked(requestID) else { return false }
        let result = selection.append(urls)
        failed = result == .invalid || result == .full
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
