import Photos
import PhotosUI
import SwiftUI

/// Photos owns asset decoding. Listen only while visible; refresh an affected asset
/// when Photos reports a change, without polling or downloading iCloud originals.
@MainActor private final class PhotosAttachmentThumbnailSource: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    private var asset: PHAsset?
    private var request: PHImageRequestID?
    private var observing = false
    private var ticket = UUID()
    private var refreshTask: Task<Void, Never>?
    func follow(_ asset: PHAsset, active: Bool) {
        guard active else { stop(); return }
        guard self.asset?.localIdentifier != asset.localIdentifier || !observing else { return }
        stop(); self.asset = asset
        PHPhotoLibrary.shared().register(self); observing = true
        // PHAsset is a snapshot. An edit while hidden produced no observer event,
        // so refetch it and request a current final thumbnail on every resume.
        let expected = ticket, id = asset.localIdentifier
        refreshTask = Task { @MainActor [weak self] in
            let current = await Task.detached(priority: .utility) {
                PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject
            }.value
            guard let self, !Task.isCancelled, self.observing, self.ticket == expected else { return }
            self.refreshTask = nil
            guard let current else {
                UnyriAttachmentImages.invalidate(id: "photos:" + id); self.stop(); return
            }
            self.asset = current; self.load()
        }
    }
    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor [weak self] in
            guard let self, self.observing, let asset = self.asset,
                  let details = changeInstance.changeDetails(for: asset) else { return }
            guard let after = details.objectAfterChanges else {
                UnyriAttachmentImages.invalidate(id: "photos:" + asset.localIdentifier)
                self.stop(); return
            }
            self.asset = after
            self.load()
        }
    }
    private func load() {
        guard let asset else { return }
        refreshTask?.cancel(); refreshTask = nil
        if let request { PHImageManager.default().cancelImageRequest(request) }
        ticket = UUID(); let expected = ticket
        // A fresh edit can have exactly the same dimensions and quality as the old
        // crop. Retire that version before the monotonic quality selector runs.
        UnyriAttachmentImages.invalidate(id: "photos:" + asset.localIdentifier)
        let options = PHImageRequestOptions()
        options.deliveryMode = .opportunistic; options.resizeMode = .exact
        options.isNetworkAccessAllowed = false
        request = PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 256, height: 256),
            contentMode: .aspectFill, options: options) { [weak self] image, info in
                let degraded = info?[PHImageResultIsDegradedKey] as? Bool == true
                let cancelled = info?[PHImageCancelledKey] as? Bool == true
                Task { @MainActor in
                    guard let self, self.ticket == expected, !cancelled, let image else { return }
                    UnyriAttachmentImages.publish(image, id: "photos:" + asset.localIdentifier,
                        origin: degraded ? .inline : .localImage)
                }
            }
    }
    func stop() {
        ticket = UUID()
        refreshTask?.cancel(); refreshTask = nil
        if let request { PHImageManager.default().cancelImageRequest(request) }; request = nil
        if observing { PHPhotoLibrary.shared().unregisterChangeObserver(self) }; observing = false; asset = nil
    }
}

struct UnyriPhotosAttachmentThumbnail: View {
    let asset: PHAsset
    @StateObject private var source = PhotosAttachmentThumbnailSource()
    @State private var visible = false
    @Environment(\.scenePhase) private var scene
    var body: some View {
        UnyriAttachmentImage(request: AttachmentImageRequest(id: "photos:" + asset.localIdentifier,
            url: nil, mime: asset.mediaType == .video ? "video/mp4" : "image/*", repositoryAttachment: false), workAllowed: false) { image in
            Color.secondary.opacity(0.15).overlay {
                GeometryReader { geometry in
                    if let image { Image(uiImage: image).resizable().scaledToFill()
                        .frame(width: geometry.size.width, height: geometry.size.height).clipped() }
                }
            }.clipped()
        }
        .modifier(UnyriMediaVisibility { visible = $0 })
        .onAppear { source.follow(asset, active: visible && scene == .active) }
        .onChange(of: visible) { source.follow(asset, active: $0 && scene == .active) }
        .onChange(of: scene) { source.follow(asset, active: visible && $0 == .active) }
        .onChange(of: asset.localIdentifier) { _ in source.follow(asset, active: visible && scene == .active) }
        .onDisappear { source.stop() }
    }
}

struct UnyriPickerAttachmentThumbnail: View {
    let item: PhotosPickerItem
    let video: Bool
    @State private var asset: PHAsset?
    var body: some View {
        Group {
            if let asset { UnyriPhotosAttachmentThumbnail(asset: asset) }
            else { Image(systemName: video ? "video" : "photo").foregroundStyle(.secondary) }
        }
        .task(id: item.itemIdentifier) {
            let access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            guard let id = item.itemIdentifier, access == .authorized || access == .limited else { asset = nil; return }
            let found = await Task.detached(priority: .utility) { PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject }.value
            if !Task.isCancelled { asset = found }
        }
    }
}
