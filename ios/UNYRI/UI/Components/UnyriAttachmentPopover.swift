import SwiftUI
import UIKit

/// A native popover anchored to the plus button, including on compact iPhones.
/// The owner still performs every gallery, file-import and camera operation.
struct UnyriAttachmentPopover: UIViewRepresentable {
    @Binding var isPresented: Bool
    let cameraDisabled: Bool
    @Binding var directSelected: Bool
    let directScope: String?
    let directIssue: String?
    let directInfo: () -> Void
    let camera: () -> Void
    let photos: () -> Void
    let files: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale
    @Environment(\.layoutDirection) private var layoutDirection

    func makeCoordinator() -> Coordinator { Coordinator(owner: self) }

    func makeUIView(context: Context) -> UIView {
        let anchor = UIView()
        anchor.backgroundColor = .clear
        anchor.isUserInteractionEnabled = false
        anchor.isAccessibilityElement = false
        anchor.accessibilityElementsHidden = true
        return anchor
    }

    func updateUIView(_ anchor: UIView, context: Context) {
        context.coordinator.update(owner: self, anchor: anchor)
    }

    static func dismantleUIView(_ anchor: UIView, coordinator: Coordinator) {
        coordinator.invalidate()
    }

    @MainActor
    final class Coordinator: NSObject, UIPopoverPresentationControllerDelegate {
        var owner: UnyriAttachmentPopover
        private weak var anchor: UIView?
        private weak var ownedPresentation: UIPresentationController?
        private var controller: UIHostingController<AnyView>?
        private var pendingAction: Action?
        private var dismissing = false
        private var active = true
        private var generation: UInt64 = 0
        private var rejectedRequest = false
        private var configured: Configuration?

        enum Action { case camera, photos, files, directInfo }

        private struct Configuration: Equatable {
            let maximumWidth: CGFloat
            let availableHeight: CGFloat
            let colorScheme: ColorScheme
            let dynamicTypeSize: DynamicTypeSize
            let cameraDisabled: Bool
            let directSelected: Bool
            let directScope: String?
            let directIssue: String?
            let localeIdentifier: String
            let layoutDirection: LayoutDirection
        }

        init(owner: UnyriAttachmentPopover) { self.owner = owner }

        func update(owner next: UnyriAttachmentPopover, anchor: UIView) {
            let callBecameActive = !owner.cameraDisabled && next.cameraDisabled
            let accountChanged = owner.directScope != next.directScope
            owner = next
            self.anchor = anchor
            guard active else { return }
            if owner.scenePhase != .active || callBecameActive || accountChanged {
                cancelPresentation()
                return
            }
            guard owner.isPresented else {
                rejectedRequest = false
                dismissOwned()
                return
            }
            guard !rejectedRequest else { return }
            if let controller {
                guard !dismissing else { return }
                guard controller.presentedViewController == nil else { cancelPresentation(); return }
                configure(controller, anchor: anchor)
                return
            }
            guard let window = anchor.window, !anchor.bounds.isEmpty,
                  let presenter = presentingController(from: anchor),
                  presenter.presentedViewController == nil else { rejectPresentationRequest(); return }

            let host = UIHostingController(rootView: content(heightLimit: nil))
            host.modalPresentationStyle = .popover
            host.view.backgroundColor = .clear
            guard let popover = host.popoverPresentationController else { return }
            popover.delegate = self
            popover.sourceView = anchor
            popover.sourceRect = anchor.bounds
            // A downward arrow puts the whole popover above the plus button.
            popover.permittedArrowDirections = .down
            generation &+= 1
            ownedPresentation = popover
            controller = host
            configure(host, anchor: anchor, window: window)
            presenter.present(host, animated: true)
        }

        private func content(heightLimit: CGFloat?, measuringIntrinsicWidth: Bool = false) -> AnyView {
            let options = UnyriAttachmentPopoverOptions(cameraDisabled: owner.cameraDisabled,
                heightLimit: heightLimit, directSelected: owner.$directSelected,
                directIssue: owner.directIssue, directInfo: { [weak self] in self?.choose(.directInfo) },
                camera: { [weak self] in self?.choose(.camera) },
                photos: { [weak self] in self?.choose(.photos) },
                files: { [weak self] in self?.choose(.files) })
                .environment(\.colorScheme, owner.colorScheme)
                .environment(\.dynamicTypeSize, owner.dynamicTypeSize)
                .environment(\.locale, owner.locale)
                .environment(\.layoutDirection, owner.layoutDirection)
            if measuringIntrinsicWidth {
                return AnyView(options.fixedSize(horizontal: true, vertical: true))
            }
            return AnyView(options)
        }

        private func configure(_ host: UIHostingController<AnyView>, anchor: UIView, window: UIWindow? = nil) {
            guard let window = window ?? anchor.window else { return }
            let maximumWidth = max(44, window.bounds.width - 32)
            let source = anchor.convert(anchor.bounds, to: window)
            let available = max(44, source.minY - window.safeAreaInsets.top - 12)
            let next = Configuration(maximumWidth: maximumWidth, availableHeight: available,
                colorScheme: owner.colorScheme, dynamicTypeSize: owner.dynamicTypeSize,
                cameraDisabled: owner.cameraDisabled, directSelected: owner.directSelected, directScope: owner.directScope, directIssue: owner.directIssue, localeIdentifier: owner.locale.identifier,
                layoutDirection: owner.layoutDirection)
            let popover = host.popoverPresentationController
            if popover?.sourceView !== anchor { popover?.sourceView = anchor }
            if popover?.sourceRect != anchor.bounds { popover?.sourceRect = anchor.bounds }
            // A draft keystroke must not rebuild rows, remeasure or reset scroll.
            guard configured != next else { return }
            configured = next
            host.overrideUserInterfaceStyle = owner.colorScheme == .dark ? .dark : .light
            host.view.semanticContentAttribute = owner.layoutDirection == .rightToLeft ? .forceRightToLeft : .forceLeftToRight
            // Measure the actual localized labels at their native Dynamic Type
            // size. Only the measurement ignores the horizontal proposal.
            host.rootView = content(heightLimit: nil, measuringIntrinsicWidth: true)
            let ideal = host.sizeThatFits(in: CGSize(width: maximumWidth, height: .greatestFiniteMagnitude))
            let width = min(maximumWidth, max(min(160, maximumWidth), ceil(ideal.width)))
            host.rootView = content(heightLimit: nil)
            let natural = host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
            let height = min(natural.height, available)
            if natural.height > available { host.rootView = content(heightLimit: available) }
            host.preferredContentSize = CGSize(width: width, height: height)
        }

        private func choose(_ action: Action) {
            guard active, owner.isPresented, owner.scenePhase == .active, !dismissing,
                  let controller, controller.presentedViewController == nil else { return }
            if case .camera = action, owner.cameraDisabled { return }
            pendingAction = action
            owner.isPresented = false
            dismissOwned()
        }

        private func dismissOwned(animated: Bool = true) {
            guard let host = controller, !dismissing else { return }
            // If another feature is presenting above us, never dismiss its VC.
            guard host.presentedViewController == nil else { return }
            guard let presenter = host.presentingViewController,
                  presenter.presentedViewController === host else {
                pendingAction = nil
                controller = nil
                ownedPresentation = nil
                configured = nil
                return
            }
            dismissing = true
            let selected = pendingAction
            let selectedGeneration = generation
            pendingAction = nil
            // Present the next system picker only after this transition ends.
            host.dismiss(animated: animated) { [weak self, weak host, weak presenter] in
                guard let self, self.controller === host else { return }
                self.controller = nil
                self.ownedPresentation = nil
                self.dismissing = false
                self.configured = nil
                guard self.active, self.owner.scenePhase == .active,
                      self.generation == selectedGeneration, let window = self.anchor?.window,
                      let presenter, presenter.viewIfLoaded?.window === window,
                      presenter.presentedViewController == nil, let selected else { return }
                switch selected {
                case .camera: self.owner.camera()
                case .photos: self.owner.photos()
                case .files: self.owner.files()
                case .directInfo: self.owner.directInfo()
                }
            }
        }

        private func cancelPresentation() {
            guard owner.isPresented || controller != nil || pendingAction != nil else { return }
            if !rejectedRequest { generation &+= 1 }
            pendingAction = nil
            rejectPresentationRequest()
            // Release the presentation immediately for the incoming-call cover.
            dismissOwned(animated: false)
        }

        private func rejectPresentationRequest() {
            guard !rejectedRequest else { return }
            rejectedRequest = true
            let rejectedGeneration = generation
            // updateUIView must not mutate SwiftUI state during its render pass.
            Task { @MainActor [weak self] in
                guard let self, self.active, self.generation == rejectedGeneration,
                      self.rejectedRequest else { return }
                if self.owner.isPresented { self.owner.isPresented = false }
            }
        }

        func invalidate() {
            active = false
            generation &+= 1
            pendingAction = nil
            let owned = controller
            controller = nil
            ownedPresentation = nil
            configured = nil
            // Never dismiss a modal owned by another screen or feature.
            if let owned, owned.presentedViewController == nil,
               owned.presentingViewController?.presentedViewController === owned {
                owned.dismiss(animated: false)
            }
        }

        func popoverPresentationControllerDidDismissPopover(_ popoverPresentationController: UIPopoverPresentationController) {
            didDismissOwned(popoverPresentationController)
        }

        func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
            didDismissOwned(presentationController)
        }

        private func didDismissOwned(_ presentationController: UIPresentationController) {
            // Programmatic selection is completed exclusively by dismissOwned.
            // UIKit may clear host.presentationController before this callback.
            guard !dismissing, ownedPresentation === presentationController else { return }
            generation &+= 1
            pendingAction = nil
            controller = nil
            ownedPresentation = nil
            configured = nil
            dismissing = false
            rejectedRequest = false
            if owner.isPresented { owner.isPresented = false }
        }

        func adaptivePresentationStyle(for controller: UIPresentationController) -> UIModalPresentationStyle { .none }
        func adaptivePresentationStyle(for controller: UIPresentationController, traitCollection: UITraitCollection) -> UIModalPresentationStyle { .none }

        private func presentingController(from view: UIView) -> UIViewController? {
            var responder: UIResponder? = view
            while let current = responder {
                if let controller = current as? UIViewController { return controller }
                responder = current.next
            }
            return nil
        }
    }
}

private struct UnyriAttachmentPopoverOptions: View {
    let cameraDisabled: Bool
    let heightLimit: CGFloat?
    @Binding var directSelected: Bool
    let directIssue: String?
    let directInfo: () -> Void
    let camera: () -> Void
    let photos: () -> Void
    let files: () -> Void

    @ViewBuilder var body: some View {
        if let heightLimit {
            ScrollView { rows }.frame(height: heightLimit)
        } else { rows.fixedSize(horizontal: false, vertical: true) }
    }

    private var rows: some View {
        VStack(spacing: 0) {
            Button(action: camera) { row("Camera", icon: "camera") }
                .disabled(cameraDisabled).opacity(cameraDisabled ? 0.4 : 1)
                .accessibilityIdentifier("chat-attach-camera")
            Divider()
            Button(action: photos) { row("Photos and videos", icon: "photo.on.rectangle") }
                .accessibilityIdentifier("chat-attach-photos")
            Divider()
            Button(action: files) { row("Files", icon: "doc") }
                .accessibilityIdentifier("chat-attach-files")
            Divider()
            HStack(spacing: 0) {
                Button { directSelected.toggle() } label: {
                    Label("Send directly", systemImage: directSelected ? "checkmark.square.fill" : "square")
                        .symbolRenderingMode(.monochrome).font(.body).foregroundStyle(.primary)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                // A saved ON choice can always be turned OFF, including in Saved Messages.
                .disabled(directIssue != nil && !directSelected)
                .accessibilityValue(Text(LocalizedStringKey(directSelected ? "On" : "Off")))
                .accessibilityIdentifier("chat-attach-direct")
                Button(action: directInfo) { Image(systemName: "info.circle").symbolRenderingMode(.monochrome).foregroundStyle(.primary).font(.body).frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .accessibilityLabel("About direct transfer").accessibilityIdentifier("chat-attach-direct-info")
            }.padding(.leading, 16).padding(.trailing, 4).padding(.vertical, 4)
            if directIssue != nil {
                Text(LocalizedStringKey(directSelected ? "Turn off Send directly to send normally." : "Direct transfer is unavailable here."))
                    .font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: 220, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 8)
            }
        }
        .buttonStyle(.plain).padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-attachment-popover")
    }

    private func row(_ title: LocalizedStringKey, icon: String) -> some View {
        Label(title, systemImage: icon).symbolRenderingMode(.monochrome)
            .font(.body).foregroundStyle(.primary)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 4)
            .contentShape(Rectangle())
    }
}
