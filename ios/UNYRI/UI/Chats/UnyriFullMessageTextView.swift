import SwiftUI
import UIKit

enum UnyriMessageTextPresentation {
    static func display(_ raw: String, inline: Bool = false) -> String {
        let result = MessageTextPolicy.presentationResult(raw, inline: inline)
        switch result.kind {
        case .text: return result.text
        case .empty, .invalid: return UnyriL10n.string(result.text)
        }
    }
}

/// Explicit, read-only full text. TextKit 2 lays out the visible region; the
/// ordinary bubble receives only a bounded presentation. No markup/autolinks.
struct UnyriFullMessageTextView: View {
    let text: String
    @Environment(\.dismiss) private var dismiss
    private var canDisplay: Bool { (try? MessageTextPolicy.received(text)) != nil }

    var body: some View {
        NavigationStack {
            Group {
                if canDisplay { UnyriFullTextNativeView(text: text) }
                else {
                    Text(UnyriL10n.string("This message is too large or contains unsupported characters for the full text viewer. You can still copy the original text."))
                        .padding()
                }
            }
            .navigationTitle(UnyriL10n.string("Full text"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(UnyriL10n.string("Close")) { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(UnyriL10n.string("Copy")) { UIPasteboard.general.string = text }
                }
            }
        }
    }
}

private struct UnyriFullTextNativeView: UIViewRepresentable {
    let text: String
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView(usingTextLayoutManager: true)
        view.isEditable = false
        view.isSelectable = true
        view.isScrollEnabled = true
        view.dataDetectorTypes = []
        view.font = .preferredFont(forTextStyle: .body)
        view.adjustsFontForContentSizeCategory = true
        view.backgroundColor = .systemBackground
        view.textColor = .label
        view.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
        view.text = text
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        if view.text != text { view.text = text }
    }
}
