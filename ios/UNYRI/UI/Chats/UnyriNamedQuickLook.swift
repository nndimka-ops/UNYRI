import SwiftUI
import UIKit
import QuickLook
import PDFKit
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
    @State private var fallbackOriginal = false
    private var isPDF: Bool {
        item.url.pathExtension.lowercased() == "pdf" || (item.title?.lowercased().hasSuffix(".pdf") ?? false)
    }
    var body: some View {
        Group {
            if isPDF {
                // Our own PDFKit reader: QuickLook nested in a SwiftUI cover + navigation
                // stack jumped, doubled its bars and misbehaved on rotation/dismiss.
                UnyriPDFReader(url: item.url, title: item.title ?? item.url.lastPathComponent,
                               shareURL: lease?.url ?? item.url, close: close)
            } else if item.title == nil {
                UnyriNamedQuickLookController(item: item, close: close)
            } else if let lease {
                UnyriNamedQuickLookController(item: UnyriNamedQuickLookItem(url: item.url, title: item.title, lease: lease), close: close)
            } else if fallbackOriginal {
                UnyriNamedQuickLookController(item: UnyriNamedQuickLookItem(url: item.url, title: nil), close: close)
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
            lease = nil; failed = false; fallbackOriginal = false
            let request = item.request
            let work = Task.detached(priority: .utility) { try UnyriNamedQuickLookLease.prepare(url: request.url, title: request.title) }
            await withTaskCancellationHandler {
                do {
                    let prepared = try await work.value
                    try Task.checkCancellation()
                    lease = prepared
                } catch is CancellationError { /* The unadmitted lease releases only its own temporary entry. */ }
                catch {
                    if !Task.isCancelled {
                        // Fall back to opening the original file URL directly if hard link creation fails
                        fallbackOriginal = true
                    }
                }
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



/// Full-screen PDF reader: continuous vertical pages, pinch zoom, page counter,
/// glass chrome (tap to hide), share with the real file name.
private struct UnyriPDFReader: View {
    let url: URL
    let title: String
    let shareURL: URL
    let close: () -> Void
    @State private var page = 1
    @State private var pages = 0
    @State private var chrome = true
    @State private var failed = false
    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            if failed {
                VStack(spacing: 12) {
                    Image(systemName: "doc.text").font(.system(size: 34))
                    Text("Could not open this file. Check that it is available on this device, then try again.")
                        .multilineTextAlignment(.center).font(.subheadline)
                }.foregroundStyle(.white.opacity(0.8)).padding(32).frame(maxHeight: .infinity)
            } else {
                UnyriPDFKitView(url: url, page: $page, pages: $pages, failed: $failed, onTap: {
                    withAnimation(.easeOut(duration: 0.18)) { chrome.toggle() }
                }).ignoresSafeArea()
            }
            if chrome {
                // Keeps the status bar and the buttons legible over white pages.
                LinearGradient(colors: [Color.black.opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
                    .frame(height: 130).ignoresSafeArea(edges: .top).allowsHitTesting(false)
                    .transition(.opacity)
            }
            if chrome {
                HStack(spacing: 10) {
                    Button(action: close) {
                        Image(systemName: "xmark").font(.system(size: 16, weight: .semibold))
                            .frame(width: 40, height: 40).background(Color.black.opacity(0.62), in: Circle()).overlay(Circle().stroke(Color.white.opacity(0.12), lineWidth: 0.5))
                    }.accessibilityLabel("Close")
                    Text(title).font(.subheadline.weight(.semibold)).lineLimit(1).truncationMode(.middle)
                        .padding(.horizontal, 14).frame(height: 40).background(Color.black.opacity(0.62), in: Capsule()).overlay(Capsule().stroke(Color.white.opacity(0.12), lineWidth: 0.5))
                    Spacer(minLength: 0)
                    ShareLink(item: shareURL) {
                        Image(systemName: "square.and.arrow.up").font(.system(size: 16, weight: .semibold))
                            .frame(width: 40, height: 40).background(Color.black.opacity(0.62), in: Circle()).overlay(Circle().stroke(Color.white.opacity(0.12), lineWidth: 0.5))
                    }.accessibilityLabel("Share file")
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 12).padding(.top, 6)
                .transition(.opacity)
            }
        }
        .overlay(alignment: .bottom) {
            if chrome && pages > 1 {
                Text("\(page) / \(pages)").font(.system(size: 13, weight: .semibold).monospacedDigit())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14).frame(height: 32).background(Color.black.opacity(0.62), in: Capsule()).overlay(Capsule().stroke(Color.white.opacity(0.12), lineWidth: 0.5))
                    .padding(.bottom, 14)
                    .transition(.opacity)
                    .accessibilityLabel(Text("\(page) / \(pages)"))
            }
        }
        .statusBarHidden(!chrome)
        .preferredColorScheme(.dark)
        .accessibilityIdentifier("pdf-reader")
    }
}

private struct UnyriPDFKitView: UIViewRepresentable {
    let url: URL
    @Binding var page: Int
    @Binding var pages: Int
    @Binding var failed: Bool
    let onTap: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.backgroundColor = .black
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.pageShadowsEnabled = false
        view.autoScales = true
        view.displaysPageBreaks = true
        view.pageBreakMargins = UIEdgeInsets(top: 6, left: 0, bottom: 6, right: 0)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped))
        tap.numberOfTapsRequired = 1
        // Let PDFKit's double-tap zoom win over the chrome toggle.
        for recognizer in view.gestureRecognizers ?? [] where (recognizer as? UITapGestureRecognizer)?.numberOfTapsRequired == 2 {
            tap.require(toFail: recognizer)
        }
        view.addGestureRecognizer(tap)
        NotificationCenter.default.addObserver(context.coordinator, selector: #selector(Coordinator.pageChanged(_:)),
                                               name: .PDFViewPageChanged, object: view)
        context.coordinator.load(into: view)
        return view
    }
    func updateUIView(_ view: PDFView, context: Context) { context.coordinator.parent = self }
    static func dismantleUIView(_ view: PDFView, coordinator: Coordinator) {
        NotificationCenter.default.removeObserver(coordinator)
        view.document = nil
    }
    final class Coordinator: NSObject {
        var parent: UnyriPDFKitView
        init(_ parent: UnyriPDFKitView) { self.parent = parent }
        func load(into view: PDFView) {
            let url = parent.url
            // Parse off the main thread; large PDFs used to freeze the first frame.
            DispatchQueue.global(qos: .userInitiated).async {
                let document = PDFDocument(url: url)
                DispatchQueue.main.async {
                    guard let document else { self.parent.failed = true; return }
                    view.document = document
                    view.minScaleFactor = view.scaleFactorForSizeToFit
                    view.maxScaleFactor = max(4, view.scaleFactorForSizeToFit * 6)
                    self.parent.pages = document.pageCount
                    self.parent.page = 1
                }
            }
        }
        @objc func tapped() { parent.onTap() }
        @objc func pageChanged(_ note: Notification) {
            guard let view = note.object as? PDFView, let current = view.currentPage, let document = view.document else { return }
            let index = document.index(for: current) + 1
            if parent.page != index { parent.page = index }
        }
    }
}
