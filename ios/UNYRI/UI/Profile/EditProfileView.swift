import SwiftUI
import UIKit
import AVFoundation

struct EditProfileView: View {
    @EnvironmentObject private var users: UsersRepository
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let initial: UserProfile
    let onSaved: (UserProfile) -> Void

    @State private var identityDirty = false
    @State private var identityApplying = false
    @State private var displayName: String
    @State private var bio: String
    @State private var avatarJPEG: Data?
    @State private var currentAvatarURL: URL?
    @State private var avatarPicker: AvatarPickerSource?
    @State private var cameraClaim: NonCallCaptureOwnership.Claim?
    @State private var removeAvatarRequested = false
    @State private var isSaving = false
    @State private var attemptedSave = false
    @State private var confirmDiscard = false
    @State private var errorMessage: String?
    /// Localized server rejection of the name, shown under the field; cleared on typing.
    @State private var serverNameError: String?
    @FocusState private var focusedField: Field?
    private enum Field: Hashable { case name, username, bio }

    init(initial: UserProfile, onSaved: @escaping (UserProfile) -> Void) {
        self.initial = initial
        self.onSaved = onSaved
        _displayName = State(initialValue: initial.displayName)
        _bio = State(initialValue: initial.bio ?? "")
    }

    private var hasChanges: Bool {
        identityDirty || displayName != initial.displayName || bio != (initial.bio ?? "")
            || avatarJPEG != nil || removeAvatarRequested
    }
    /// What the server will store (DisplayNamePolicy mirrors its sanitising).
    private var sanitizedName: String { DisplayNamePolicy.sanitize(displayName) }
    private var nameEdited: Bool { displayName != initial.displayName }
    private var submittedName: String { nameEdited ? sanitizedName : initial.displayName }
    // Legacy names remain visible and are never silently clipped. Saving any
    // profile changes still requires the current iOS length limit.
    private var nameError: String? {
        DisplayNamePolicy.profileError(for: displayName, original: initial.displayName)
    }
    private var previewName: String { sanitizedName.isEmpty ? initial.displayName : sanitizedName }
    private var accent: Color { colorStyle.decorative(Color(hex: 0x7BC9EC)) }
    private var canRemovePhoto: Bool {
        (initial.hasAvatar || initial.avatarUrl != nil || avatarJPEG != nil) && !removeAvatarRequested
    }

    var body: some View {
        UnyriSettingsPage("Profile") {
            profileHeader
            UnyriSettingsGroup("Your profile") {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 8) {
                        fieldHeading("Display name", count: submittedName.unicodeScalars.count, maximum: DisplayNamePolicy.maximumScalars)
                        TextField("Display name", text: $displayName)
                            .textContentType(.name).textInputAutocapitalization(.words)
                            .submitLabel(.next).focused($focusedField, equals: .name)
                            .onSubmit { focusedField = .username }
                            .modifier(ProfileFieldSurface())
                            .accessibilityIdentifier("edit-profile-name")
                            .onChange(of: displayName) { _ in serverNameError = nil }
                        nameFeedback
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        fieldHeading("About you", count: bio.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars.count, maximum: 160)
                        TextField("Bio", text: $bio, axis: .vertical)
                            .lineLimit(3...5).focused($focusedField, equals: .bio)
                            .modifier(ProfileFieldSurface())
                            .accessibilityIdentifier("edit-profile-bio")
                        fieldFeedback(Validation.bioError(bio), edited: bio != (initial.bio ?? ""),
                                      help: "A few words about yourself. You can leave this empty.")
                    }
                }.padding(16)
            }
            PublicIdentityEditor(dirty: $identityDirty, applying: $identityApplying)
            UnyriSettingsNote("Apply identity changes in their section, then save profile changes with Save.")
            if let errorMessage {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "exclamationmark.circle").foregroundColor(UnyriColors.danger).accessibilityHidden(true)
                    Text(errorMessage).font(.footnote).foregroundColor(UnyriColors.danger)
                        .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                }.padding(14).background(UnyriColors.danger.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                    .accessibilityIdentifier("edit-profile-error")
            }
        }
        .unyriBlocksRootDrawer()
        .scrollDismissesKeyboard(.interactively)
        .disabled(isSaving)
        // Preserve native clean-form back/swipe. Dirty/saving forms get a guarded
        // leading button without a global navigation-gesture delegate override.
        .navigationBarBackButtonHidden(hasChanges || isSaving || identityApplying)
        .interactiveDismissDisabled(hasChanges || isSaving || identityApplying)
        .toolbar {
            if hasChanges || isSaving || identityApplying {
                UnyriBackToolbarItem(action: requestDismiss, enabled: !isSaving && !identityApplying, identifier: "edit-profile-back")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: save) {
                    Group {
                        if isSaving { ProgressView().tint(UnyriColors.primaryText) }
                        else { Text("Save").font(.body.weight(.semibold)) }
                    }.frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }.foregroundColor(accent).disabled(isSaving || identityApplying || identityDirty)
                    .accessibilityLabel("Save").accessibilityIdentifier("edit-profile-save")
            }
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { focusedField = nil }
            }
        }
        .alert("Discard profile changes?", isPresented: $confirmDiscard) {
            Button("Keep editing", role: .cancel) {}
            Button("Discard changes", role: .destructive) { focusedField = nil; dismiss() }
        } message: {
            Text("Unsaved changes will be discarded. Anything already saved to your profile will remain.")
        }
        .task {
            let generation = auth.sessionGeneration
            guard auth.currentUser?.id == initial.id else { return }
            let url = await users.avatarURL(id: initial.id, avatarUrl: initial.avatarUrl, avatarVersion: initial.avatarVersion)
            guard generation == auth.sessionGeneration, auth.currentUser?.id == initial.id, !Task.isCancelled else { return }
            currentAvatarURL = url
        }
        .sheet(item: $avatarPicker, onDismiss: {
            if let claim = cameraClaim { NonCallCaptureOwnership.stop(claim); cameraClaim = nil }
        }) { source in
            AvatarPhotoPicker(source: source, captureClaim: cameraClaim) { image in
                // A cancelled picker leaves both the text and previous photo draft intact.
                if let image {
                    if let jpeg = AvatarPhotoPicker.normalizedJPEG(image) {
                        avatarJPEG = jpeg
                        removeAvatarRequested = false
                    } else { errorMessage = UnyriL10n.string("Could not prepare the selected photo.") }
                }
                avatarPicker = nil
            }
            .ignoresSafeArea()
        }
        .onDisappear { if let claim = cameraClaim { NonCallCaptureOwnership.stop(claim) } }
    }

    private var profileHeader: some View {
        UnyriSettingsGroup {
            VStack(spacing: 16) {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(spacing: 12) { profileAvatar; profileIdentity }
                } else {
                    HStack(spacing: 16) { profileAvatar; profileIdentity }
                }
                Menu {
                    Button { UnyriHaptics.selection(); avatarPicker = .library } label: { Label("Gallery", systemImage: "photo") }
                        .accessibilityIdentifier("edit-profile-gallery")
                    Button { UnyriHaptics.selection(); openCamera() } label: { Label("Camera", systemImage: "camera") }
                        .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))
                        .accessibilityIdentifier("edit-profile-camera")
                    if canRemovePhoto {
                        Button(role: .destructive) { UnyriHaptics.selection(); removeAvatarRequested = true; avatarJPEG = nil } label: {
                            Label("Remove avatar", systemImage: "trash")
                        }.accessibilityIdentifier("edit-profile-remove-avatar")
                    }
                    if avatarJPEG != nil || removeAvatarRequested {
                        Button { UnyriHaptics.selection(); avatarJPEG = nil; removeAvatarRequested = false } label: {
                            Label("Undo photo change", systemImage: "arrow.uturn.backward")
                        }.accessibilityIdentifier("edit-profile-undo-avatar")
                    }
                } label: {
                    Label("Change photo", systemImage: "camera")
                        .font(.subheadline.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
                        .foregroundColor(accent)
                        .background(accent.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
                }.accessibilityLabel("Change photo").accessibilityIdentifier("edit-profile-photo-menu")
            }.padding(16)
        }
    }

    private var profileIdentity: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(previewName).font(.title3.weight(.semibold)).foregroundColor(UnyriColors.primaryText)
                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
            if let identity = users.ownIdentity {
                Text(PublicIdentityPolicy.formattedNumber(identity.unyriNumber))
                    .font(.subheadline.monospacedDigit()).foregroundColor(UnyriColors.secondaryText)
            }
            Text("Profile preview").font(.caption).foregroundColor(UnyriColors.veryMutedText)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }

    private var profileAvatar: some View {
        Group {
            if let avatarJPEG, let image = UIImage(data: avatarJPEG) {
                Image(uiImage: image).resizable().scaledToFill().frame(width: 88, height: 88).clipShape(Circle())
            } else {
                UserAvatar(url: removeAvatarRequested ? nil : currentAvatarURL, name: previewName, size: 88)
            }
        }.frame(width: 88, height: 88)
            .overlay(Circle().stroke(UnyriColors.outlineSecondary, lineWidth: 1))
            .accessibilityElement(children: .ignore).accessibilityLabel("Profile photo")
    }

    private func fieldHeading(_ title: String, count: Int, maximum: Int) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(LocalizedStringKey(title)).font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.primaryText)
            Spacer(minLength: 4)
            Text("\(count)/\(maximum)").font(.caption).monospacedDigit()
                .accessibilityIdentifier(title == "Display name" ? "edit-profile-name-count" : title == "Username" ? "edit-profile-username-count" : "edit-profile-bio-count")
                .foregroundColor(count > maximum ? UnyriColors.danger : UnyriColors.secondaryText)
                .accessibilityLabel(Text("\(count)/\(maximum)"))
        }
    }

    /// The name's line: a server or local error, else a note that hidden
    /// characters will be dropped, else the usual help. One short cross-fade.
    private var nameFeedback: some View {
        let error = serverNameError ?? nameError.map { UnyriL10n.string($0) }
        let help = nameEdited && DisplayNamePolicy.removesHiddenCharacters(displayName)
            ? "Hidden characters will be removed." : "The name people see in your chats."
        return ZStack(alignment: .leading) {
            if let error {
                Text(verbatim: error).font(.footnote).foregroundColor(UnyriColors.danger)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
                    .accessibilityIdentifier("edit-profile-name-error")
            } else {
                Text(LocalizedStringKey(help)).font(.footnote).foregroundColor(UnyriColors.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
                    .id(help)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: error ?? help)
    }

    @ViewBuilder private func fieldFeedback(_ error: String?, edited: Bool, help: String) -> some View {
        if let error, edited || attemptedSave {
            Text(verbatim: UnyriL10n.string(error)).font(.footnote).foregroundColor(UnyriColors.danger)
                .fixedSize(horizontal: false, vertical: true)
        } else {
            Text(LocalizedStringKey(help)).font(.footnote).foregroundColor(UnyriColors.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private struct ProfileFieldSurface: ViewModifier {
        func body(content: Content) -> some View {
            content.padding(.horizontal, 14).padding(.vertical, 12).frame(minHeight: 50)
                .background(UnyriColors.background.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(UnyriColors.outlineSubtle, lineWidth: 1))
        }
    }

    private func requestDismiss() {
        guard !isSaving, !identityApplying else { return }
        UnyriHaptics.selection()
        if hasChanges { focusedField = nil; confirmDiscard = true }
        else { focusedField = nil; dismiss() }
    }

    private func openCamera() {
        let generation = auth.sessionGeneration
        guard auth.currentUser?.id == initial.id else { return }
        Task { @MainActor in
            do {
                guard let claim = try await NonCallCaptureOwnership.prepare(.camera, generation: generation, stop: { claim in
                    if cameraClaim == claim { avatarPicker = nil; cameraClaim = nil }
                    NonCallCaptureOwnership.release(claim)
                }) else { errorMessage = UnyriL10n.string("Finish the call before taking a photo."); return }
                cameraClaim = claim
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                guard generation == auth.sessionGeneration, auth.currentUser?.id == initial.id, !Task.isCancelled, NonCallCaptureOwnership.isCurrent(claim) else {
                    NonCallCaptureOwnership.release(claim); return
                }
                guard granted, UIImagePickerController.isSourceTypeAvailable(.camera) else {
                    NonCallCaptureOwnership.release(claim); cameraClaim = nil
                    errorMessage = UnyriL10n.string("Camera access is required to take a photo."); return
                }
                avatarPicker = .camera
            } catch is CancellationError { }
            catch { if generation == auth.sessionGeneration { errorMessage = UnyriL10n.string("Finish the call before taking a photo.") } }
        }
    }

    private func save() {
        guard !isSaving, !identityApplying, !identityDirty, auth.currentUser?.id == initial.id else { return }
        attemptedSave = true
        let generation = auth.sessionGeneration
        // Preserve an untouched name exactly, after enforcing the iOS save limit.
        let nameToSend = submittedName
        let trimmedBio = bio.trimmingCharacters(in: .whitespacesAndNewlines)

        if let error = nameError {
            // Already shown under the field; say it and go there.
            focusedField = .name
            UIAccessibility.post(notification: .announcement, argument: UnyriL10n.string(error))
            return
        }
        if let error = Validation.bioError(bio) { errorMessage = UnyriL10n.string(error); return }

        focusedField = nil
        UnyriHaptics.selection()
        isSaving = true
        errorMessage = nil
        Task { @MainActor in
            defer { isSaving = false }
            do {
                var updated = try await users.updateProfile(
                    username: initial.username,
                    displayName: nameToSend,
                    bio: trimmedBio == (initial.bio ?? "") ? .absent : (trimmedBio.isEmpty ? .clear : .set(trimmedBio)),
                    avatarUrl: .absent
                )
                guard generation == auth.sessionGeneration, auth.currentUser?.id == initial.id, !Task.isCancelled else { return }
                if let avatarJPEG { updated = try await users.uploadAvatar(jpeg: avatarJPEG) }
                else if removeAvatarRequested { updated = try await users.removeAvatar() }
                guard generation == auth.sessionGeneration, auth.currentUser?.id == initial.id, !Task.isCancelled else { return }
                onSaved(updated)
                dismiss()
            } catch is CancellationError { }
            catch APIError.server(let code, let message) where ["display_name_reserved", "display_name_link", "display_name_leading_at"].contains(code) {
                // A name error belongs under the name field, not in the page banner.
                guard generation == auth.sessionGeneration, auth.currentUser?.id == initial.id else { return }
                let text = APIError.localizedServerMessage(code: code, message: message)
                serverNameError = text
                UIAccessibility.post(notification: .announcement, argument: text)
            }
            catch {
                guard generation == auth.sessionGeneration, auth.currentUser?.id == initial.id else { return }
                errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription
            }
        }
    }
}

enum AvatarPickerSource: String, Identifiable {
    case library, camera
    var id: String { rawValue }
}

struct AvatarPhotoPicker: UIViewControllerRepresentable {
    let source: AvatarPickerSource
    var captureClaim: NonCallCaptureOwnership.Claim? = nil
    let onSelected: (UIImage?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onSelected, claim: source == .camera ? captureClaim : nil) }
    func makeUIViewController(context: Context) -> UIViewController {
        let picker = UIImagePickerController()
        if source == .camera {
            guard let lease = context.coordinator.lease,
                  lease.attach(picker, interrupted: { context.coordinator.completion(nil) }) else {
                DispatchQueue.main.async { onSelected(nil) }; return UIViewController()
            }
        }
        picker.sourceType = source == .camera ? .camera : .photoLibrary
        picker.mediaTypes = ["public.image"]
        picker.allowsEditing = true
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ picker: UIViewController, context: Context) {}
    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) { coordinator.lease?.close() }

    @MainActor final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let completion: (UIImage?) -> Void
        let lease: NativeCameraPickerLease?
        init(_ completion: @escaping (UIImage?) -> Void, claim: NonCallCaptureOwnership.Claim?) {
            self.completion = completion; lease = claim.map(NativeCameraPickerLease.init)
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { completion(nil) }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            guard lease?.isCurrent != false else { return }
            completion((info[.editedImage] ?? info[.originalImage]) as? UIImage)
        }
    }

    static func normalizedJPEG(_ image: UIImage) -> Data? {
        guard image.size.width > 0, image.size.height > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let side: CGFloat = 512
        let scale = max(side / image.size.width, side / image.size.height)
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: side, height: side))
            image.draw(in: CGRect(x: (side - size.width) / 2, y: (side - size.height) / 2, width: size.width, height: size.height))
        }.jpegData(compressionQuality: 0.9)
    }
}
