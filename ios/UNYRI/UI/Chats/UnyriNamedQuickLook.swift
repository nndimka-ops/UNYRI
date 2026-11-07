import SwiftUI
import UIKit
import QuickLook
import Darwin

extension View {
    /// Original cache identity is kept; a named temporary link also names native exports.
    func unyriQuickLookPreview(_ selection: Binding<URL?>, title: String? = nil) -> some View {
        let item = Binding<UnyriNamedQuickLookItem?>(
            get: { selection.wrappedValue.map { UnyriNamedQuickLookItem(url: $0, title: title) } },
            set: { selection.wrappedValue = $0?.url })
        return fullScreenCover(item: item) { value in
            UnyriNamedQuickLookPresentation(item: value, close: {
                guard selection.wrappedValue == value.url else { return }
                selection.wrappedValue = nil
            })
        }
    }
}

// Link creation performs metadata work only, never a full-file read or copy.
private final class UnyriNamedQuickLookLease: @unchecked Sendable {
    let url: URL
    private let directory: URL?
    private init(url: URL, directory: URL?) { self.url = url; self.directory = directory }

    static func prepare(url: URL, title: String?) throws -> UnyriNamedQuickLookLease {
        try Task.checkCancellation()
        guard let title, !title.isEmpty else { return UnyriNamedQuickLookLease(url: url, directory: nil) }
        guard url.isFileURL else { throw CocoaError(.fileReadUnsupportedScheme) }
        let name = DirectFileOffer.safeFilename(title)
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true else { throw CocoaError(.fileReadUnsupportedScheme) }
        if url.lastPathComponent == name { return UnyriNamedQuickLookLease(url: url, directory: nil) }
        let manager = FileManager()
        let directory = manager.temporaryDirectory.appendingPathComponent("unyri-named-preview-" + UUID().uuidString, isDirectory: true)
        let destination = directory.appendingPathComponent(name, isDirectory: false)
        try manager.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        do {
            try Task.checkCancellation()
            try manager.linkItem(at: url, to: destination)
            try Task.checkCancellation()
            return UnyriNamedQuickLookLease(url: destination, directory: directory)
        } catch {
            cleanup(url: destination, directory: directory)
            throw error
        }
    }
    private static func cleanup(url: URL, directory: URL) {
        // Only our exact linked entry and its now-empty private directory are removed.
        url.path.withCString { _ = Darwin.unlink($0) }
        directory.path.withCString { _ = Darwin.rmdir($0) }
    }
    deinit {
        guard let directory else { return }
        let url = url
        DispatchQueue.global(qos: .utility).async { Self.cleanup(url: url, directory: directory) }
    }
}

@MainActor
private struct UnyriNamedQuickLookPresentation: View {
    let item: UnyriNamedQuickLookItem
    let close: () -> Void
    @State private var lease: UnyriNamedQuickLookLease?
    @State private var failed = false
    var body: some View {
        Group {
            if item.title == nil {
                UnyriNamedQuickLookController(item: item, close: close)
            } else if let lease {
                UnyriNamedQuickLookController(item: UnyriNamedQuickLookItem(url: item.url, title: item.title, lease: lease), close: close)
            } else {
                NavigationStack {
                    Group {
                        if failed { Text("Could not open this file. Check that it is available on this device, then try again.") }
                        else { ProgressView() }
                    }
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close", action: close) } }
                }.tint(UnyriColors.primaryText)
            }
        }
        .task(id: item.request) {
            guard item.title != nil else { return }
            lease = nil; failed = false
            let request = item.request
            let work = Task.detached(priority: .utility) { try UnyriNamedQuickLookLease.prepare(url: request.url, title: request.title) }
            await withTaskCancellationHandler {
                do {
                    let prepared = try await work.value
                    try Task.checkCancellation()
                    lease = prepared
                } catch is CancellationError { /* The unadmitted lease releases only its own temporary entry. */ }
                catch { if !Task.isCancelled { failed = true } }
            } onCancel: { work.cancel() }
        }
    }
}

private struct UnyriNamedQuickLookRequest: Hashable {
    let url: URL
    let title: String?
}

private final class UnyriNamedQuickLookItem: NSObject, QLPreviewItem, Identifiable {
    let url: URL
    let title: String?
    var id: URL { url }
    private let lease: UnyriNamedQuickLookLease?
    var request: UnyriNamedQuickLookRequest { UnyriNamedQuickLookRequest(url: url, title: title) }
    var previewItemURL: URL? { lease?.url ?? url }
    var previewItemTitle: String? { title }
    init(url: URL, title: String?, lease: UnyriNamedQuickLookLease? = nil) {
        self.url = url
        self.title = title?.isEmpty == false ? title : nil
        self.lease = lease
    }
}

private struct UnyriNamedQuickLookController: UIViewControllerRepresentable {
    let item: UnyriNamedQuickLookItem
    let close: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(item, close: close) }
    func makeUIViewController(context: Context) -> UINavigationController {
        let preview = QLPreviewController()
        preview.dataSource = context.coordinator
        preview.delegate = context.coordinator
        let coordinator = context.coordinator
        preview.navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .close,
            primaryAction: UIAction { [weak coordinator] _ in coordinator?.close() })
        let navigation = UINavigationController(rootViewController: preview)
        navigation.navigationBar.tintColor = UIColor(UnyriColors.primaryText)
        navigation.toolbar.tintColor = UIColor(UnyriColors.primaryText)
        return navigation
    }
    func updateUIViewController(_ controller: UINavigationController, context: Context) {
        context.coordinator.close = close
        controller.navigationBar.tintColor = UIColor(UnyriColors.primaryText)
        controller.toolbar.tintColor = UIColor(UnyriColors.primaryText)
        guard context.coordinator.item.url != item.url || context.coordinator.item.title != item.title || context.coordinator.item.previewItemURL != item.previewItemURL else { return }
        context.coordinator.item = item
        (controller.viewControllers.first as? QLPreviewController)?.reloadData()
    }
    final class Coordinator: NSObject, QLPreviewControllerDataSource, QLPreviewControllerDelegate {
        var item: UnyriNamedQuickLookItem
        var close: () -> Void
        init(_ item: UnyriNamedQuickLookItem, close: @escaping () -> Void) {
            self.item = item; self.close = close
        }
        func previewControllerDidDismiss(_ controller: QLPreviewController) { close() }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { item }
    }
}

