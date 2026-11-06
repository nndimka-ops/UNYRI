import AVFoundation
import CoreTransferable
import Foundation
import Photos
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

enum DirectComposerOriginal {
    enum Source { case asset(PHAsset), picker(PhotosPickerItem), file(URL) }
    struct Original {
        let url: URL
        let filename: String
        let asset: AVAsset?
        let temporary: Bool
        var size: Int64 {
            ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.int64Value ?? -1
        }
        func cleanup() async {
            guard temporary else { return }
            let file = url
            await Task.detached(priority: .utility) { try? FileManager.default.removeItem(at: file) }.value
        }
    }
    private struct Imported: Transferable {
        let url: URL
        let filename: String
        static var transferRepresentation: some TransferRepresentation {
            FileRepresentation(importedContentType: .movie) { received in
                let name = DirectFileOffer.safeFilename(received.file.lastPathComponent)
                let file = FileManager.default.temporaryDirectory.appendingPathComponent("coco-direct-original-" + UUID().uuidString.lowercased())
                let operation = Task.detached(priority: .userInitiated) {
                    try AttachmentFileCopy.DocumentRead().copy(from: received.file, to: file, maxBytes: DirectFileOffer.maximumBytes)
                }
                try await withTaskCancellationHandler(operation: { try await operation.value }, onCancel: { operation.cancel() })
                return Imported(url: file, filename: name)
            }
        }
    }

    static func load(_ source: Source) async throws -> Original {
        try Task.checkCancellation()
        switch source {
        case .asset(let asset):
            let original = try await DirectPhotoOriginal.video(asset)
            let resource = PHAssetResource.assetResources(for: asset).first { $0.type == .video }
            return Original(url: original.url, filename: resource?.originalFilename ?? original.url.lastPathComponent,
                asset: original.asset, temporary: false)
        case .picker(let item):
            guard let original = try await item.loadTransferable(type: Imported.self) else { throw CocoaError(.fileReadUnknown) }
            do { try Task.checkCancellation() }
            catch { try? FileManager.default.removeItem(at: original.url); throw error }
            return Original(url: original.url, filename: original.filename, asset: nil, temporary: true)
        case .file(let file):
            return Original(url: file, filename: file.lastPathComponent, asset: nil, temporary: false)
        }
    }
}
