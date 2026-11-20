import UIKit
import SwiftUI
import Combine

/// A thin host. No app services or networking are linked into this target.
@MainActor
final class ShareViewController: UIViewController {
    private var model: ShareComposerModel?
    private var observer: AnyCancellable?
    private var legacy: LegacyShareViewController?
    override func viewDidLoad() {
        PeerImagePolicy.install()
        super.viewDidLoad()
        overrideUserInterfaceStyle = .dark
        view.backgroundColor = UIColor(white: 0.125, alpha: 1)
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        let model = ShareComposerModel(providers: providers) { [weak self] complete in
            if complete { self?.extensionContext?.completeRequest(returningItems: nil) }
            else { self?.extensionContext?.cancelRequest(withError: CocoaError(.userCancelled)) }
        }
        self.model = model
        embed(UIHostingController(rootView: ShareComposerView(model: model)))
        observer = model.$state.receive(on: DispatchQueue.main).sink { [weak self] state in
            guard state == .legacy, let self, self.legacy == nil else { return }
            let legacy = LegacyShareViewController(); self.legacy = legacy
            // A child does not own an NSExtensionContext. Forward it explicitly.
            legacy.shareContext = self.extensionContext
            self.children.forEach { $0.willMove(toParent: nil); $0.view.removeFromSuperview(); $0.removeFromParent() }
            self.embed(legacy)
        }
    }
    private func embed(_ child: UIViewController) {
        addChild(child); child.view.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(child.view)
        NSLayoutConstraint.activate([child.view.leadingAnchor.constraint(equalTo: view.leadingAnchor), child.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            child.view.topAnchor.constraint(equalTo: view.topAnchor), child.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)])
        child.didMove(toParent: self)
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if legacy == nil { model?.disappeared() }
    }
}
