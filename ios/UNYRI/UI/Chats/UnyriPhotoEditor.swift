import SwiftUI
import PencilKit
import UIKit

/// Photo editor opened straight from the viewer: the picture with the full PencilKit
/// palette (pen, marker, pencil, eraser, lasso, ruler, colours) plus text labels and
/// emoji stickers, undo/redo, and one clear Send. No intermediate preview screen.
struct UnyriPhotoEditor: View {
    let image: UIImage
    let send: (UIImage) -> Void
    let save: (UIImage) -> Void
    let share: (UIImage) -> Void
    let cancel: () -> Void

    @StateObject private var model = UnyriPhotoEditorModel()
    @State private var labels: [UnyriEditorLabel] = []
    @State private var editingLabel: UnyriEditorLabel?
    @State private var confirmDiscard = false
    @State private var showEmoji = false
    @State private var canvasSize: CGSize = .zero

    private static let stickerEmoji = ["😂", "😍", "🔥", "👍", "❤️", "😎", "🤔", "😮", "😢", "🎉", "💯", "⭐️", "✅", "❌", "👀", "🙏", "💪", "😡", "🥳", "🤝", "📍", "➡️", "⬆️", "💬"]

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            GeometryReader { geometry in
                let rect = Self.fit(image.size, in: geometry.size)
                ZStack(alignment: .topLeading) {
                    Image(uiImage: image).resizable().interpolation(.high)
                        .frame(width: rect.width, height: rect.height)
                    UnyriCanvasRepresentable(model: model)
                        .frame(width: rect.width, height: rect.height)
                    ForEach($labels) { $label in
                        UnyriEditorLabelView(label: $label, area: rect.size, onEdit: {
                            editingLabel = label
                        })
                    }
                }
                .frame(width: rect.width, height: rect.height)
                .clipped()
                .position(x: rect.midX, y: rect.midY)
                .onAppear { canvasSize = rect.size }
                .onChange(of: rect.size) { size in
                    // Strokes are stored in canvas points: keep them on the photo
                    // if the fitted rect still changes (rotation).
                    model.rescaleDrawing(from: canvasSize, to: size)
                    canvasSize = size
                }
            }
            .padding(.top, 64)
            .padding(.bottom, model.toolPickerHeight)
            .ignoresSafeArea(.keyboard)

            VStack {
                topBar
                Spacer()
            }
            if let editing = editingLabel {
                UnyriEditorTextEntry(label: editing) { result in
                    if let result {
                        if result.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            labels.removeAll { $0.id == result.id }
                        } else if let index = labels.firstIndex(where: { $0.id == result.id }) {
                            labels[index] = result
                        } else {
                            labels.append(result)
                        }
                    }
                    editingLabel = nil
                    model.showTools()
                }
            }
            if showEmoji {
                emojiPanel
            }
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(true)
        .confirmationDialog("Discard changes?", isPresented: $confirmDiscard, titleVisibility: .visible) {
            Button("Discard", role: .destructive, action: cancel)
            Button("Keep editing", role: .cancel) {}
        }
        .onAppear { model.showTools() }
    }

    private var hasChanges: Bool { !model.isEmpty || !labels.isEmpty }

    private var topBar: some View {
        HStack(spacing: 10) {
            circle("xmark", label: "Close") { if hasChanges { confirmDiscard = true } else { cancel() } }
            HStack(spacing: 2) {
                tool("arrow.uturn.backward", label: "Undo", enabled: model.canUndo) { model.undo() }
                tool("arrow.uturn.forward", label: "Redo", enabled: model.canRedo) { model.redo() }
                tool("textformat", label: "Add text", enabled: true) {
                    model.hideTools()
                    editingLabel = UnyriEditorLabel(text: "", color: .white, center: CGPoint(x: 0.5, y: 0.4), scale: 1)
                }
                tool("face.smiling", label: "Add sticker", enabled: true) { model.hideTools(); showEmoji = true }
                tool("trash", label: "Clear all", enabled: hasChanges) { model.clear(); labels.removeAll() }
            }
            .padding(.horizontal, 4)
            .modifier(UnyriAttachGlass(shape: Capsule()))
            Spacer(minLength: 0)
            Menu {
                Button { save(render()) } label: { Label("Save to Photos", systemImage: "square.and.arrow.down") }
                Button { share(render()) } label: { Label("Share…", systemImage: "square.and.arrow.up") }
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 17, weight: .semibold)).frame(width: 44, height: 44)
            }
            .modifier(UnyriAttachGlass(shape: Circle()))
            .accessibilityLabel("More")
            Button { UnyriHaptics.press(); send(render()) } label: {
                Image(systemName: "arrow.up").font(.system(size: 18, weight: .bold)).foregroundStyle(.black)
                    .frame(width: 44, height: 44).background(Circle().fill(Color.white))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Send")
            .accessibilityIdentifier("photo-editor-send")
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 12).padding(.top, 8)
    }

    private var emojiPanel: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.35).ignoresSafeArea().onTapGesture { showEmoji = false; model.showTools() }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 6), spacing: 10) {
                ForEach(Self.stickerEmoji, id: \.self) { emoji in
                    Button {
                        UnyriHaptics.selection()
                        labels.append(UnyriEditorLabel(text: emoji, color: .white, center: CGPoint(x: 0.5, y: 0.45), scale: 1.6, isEmoji: true))
                        showEmoji = false
                        model.showTools()
                    } label: { Text(emoji).font(.system(size: 34)).frame(maxWidth: .infinity, minHeight: 48) }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Color(white: 0.12)))
            .padding(12)
        }
    }

    private func circle(_ symbol: String, label: String, action: @escaping () -> Void) -> some View {
        Button { UnyriHaptics.press(); action() } label: {
            Image(systemName: symbol).font(.system(size: 16, weight: .semibold)).frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .modifier(UnyriAttachGlass(shape: Circle()))
        .accessibilityLabel(Text(UnyriL10n.string(label)))
    }

    private func tool(_ symbol: String, label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button { UnyriHaptics.selection(); action() } label: {
            Image(systemName: symbol).font(.system(size: 16, weight: .semibold)).frame(width: 40, height: 44)
        }
        .buttonStyle(.plain)
        .disabled(!enabled).opacity(enabled ? 1 : 0.35)
        .accessibilityLabel(Text(UnyriL10n.string(label)))
    }

    static func fit(_ size: CGSize, in area: CGSize) -> CGRect {
        guard size.width > 0, size.height > 0, area.width > 0, area.height > 0 else { return .zero }
        let scale = min(area.width / size.width, area.height / size.height)
        let width = size.width * scale, height = size.height * scale
        return CGRect(x: (area.width - width) / 2, y: (area.height - height) / 2, width: width, height: height)
    }

    /// Flattens the photo, the drawing and the labels at the photo's resolution (≤ 2560 px).
    private func render() -> UIImage {
        let longest = max(image.size.width, image.size.height)
        let factor = min(1, 2560 / max(1, longest))
        let output = CGSize(width: (image.size.width * factor).rounded(), height: (image.size.height * factor).rounded())
        let view = canvasSize.width > 0 ? canvasSize : output
        let ratio = output.width / view.width
        let format = UIGraphicsImageRendererFormat.default(); format.scale = 1; format.opaque = true
        return UIGraphicsImageRenderer(size: output, format: format).image { context in
            image.draw(in: CGRect(origin: .zero, size: output))
            let drawing = model.drawing.image(from: CGRect(origin: .zero, size: view), scale: ratio)
            drawing.draw(in: CGRect(origin: .zero, size: output))
            for label in labels {
                let font = UIFont.systemFont(ofSize: UnyriEditorLabel.baseSize * label.scale * ratio, weight: label.isEmoji ? .regular : .bold)
                let shadow = NSShadow(); shadow.shadowColor = UIColor.black.withAlphaComponent(0.45); shadow.shadowBlurRadius = 4 * ratio
                let text = NSAttributedString(string: label.text, attributes: [.font: font, .foregroundColor: UIColor(label.color), .shadow: shadow])
                let size = text.boundingRect(with: CGSize(width: output.width * 0.9, height: .greatestFiniteMagnitude),
                                             options: [.usesLineFragmentOrigin], context: nil).size
                let origin = CGPoint(x: label.center.x * output.width - size.width / 2, y: label.center.y * output.height - size.height / 2)
                text.draw(with: CGRect(origin: origin, size: size), options: [.usesLineFragmentOrigin], context: nil)
            }
            _ = context
        }
    }
}

/// Owns the canvas and its tool picker; publishes undo state for the top bar.
@MainActor final class UnyriPhotoEditorModel: NSObject, ObservableObject, PKCanvasViewDelegate {
    let canvas = PKCanvasView()
    private let picker = PKToolPicker()
    private let pickerObserver = UnyriToolPickerObserver()
    @Published private(set) var canUndo = false
    @Published private(set) var canRedo = false
    @Published private(set) var isEmpty = true
    @Published private(set) var toolPickerHeight: CGFloat = 96
    override init() {
        super.init()
        canvas.backgroundColor = .clear
        canvas.isOpaque = false
        canvas.drawingPolicy = .anyInput
        // The canvas is exactly the photo's rect: no scrolling, zooming or
        // automatic insets that could move strokes off the picture.
        canvas.isScrollEnabled = false
        canvas.minimumZoomScale = 1
        canvas.maximumZoomScale = 1
        canvas.contentInsetAdjustmentBehavior = .never
        canvas.tool = PKInkingTool(.marker, color: .systemRed, width: 12)
        canvas.delegate = self
        picker.addObserver(canvas)
        pickerObserver.model = self
        picker.addObserver(pickerObserver)
        picker.selectedTool = canvas.tool
    }
    var drawing: PKDrawing { canvas.drawing }
    func showTools() {
        picker.setVisible(true, forFirstResponder: canvas)
        DispatchQueue.main.async { self.canvas.becomeFirstResponder(); self.measureToolPicker() }
    }
    func hideTools() { picker.setVisible(false, forFirstResponder: canvas); canvas.resignFirstResponder() }
    func undo() { canvas.undoManager?.undo(); refresh() }
    func redo() { canvas.undoManager?.redo(); refresh() }
    func clear() { canvas.drawing = PKDrawing(); refresh() }
    nonisolated func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
        Task { @MainActor in self.refresh() }
    }
    private func refresh() {
        canUndo = canvas.undoManager?.canUndo ?? false
        canRedo = canvas.undoManager?.canRedo ?? false
        isEmpty = canvas.drawing.strokes.isEmpty
        measureToolPicker()
    }
    /// Room kept free for the docked tool picker. It is measured when the picker
    /// appears (observer), before the first stroke. Once strokes exist it stays
    /// fixed, so the photo and the canvas never resize under a drawing; it was
    /// first measured at the end of the first stroke, which shifted that stroke.
    func measureToolPicker() {
        guard canvas.drawing.strokes.isEmpty, let window = canvas.window else { return }
        let frame = picker.frameObscured(in: window)
        guard !frame.isNull, frame.height > 0, frame.height < 400 else { return }
        let height = (frame.height + 8).rounded()
        if abs(height - toolPickerHeight) >= 1 { toolPickerHeight = height }
    }
    /// Safety net when the fitted photo rect changes anyway with strokes on the
    /// canvas (rotation): scale the strokes with the photo, which keeps them in
    /// place on screen and in the exported image.
    func rescaleDrawing(from old: CGSize, to new: CGSize) {
        guard old.width > 0, old.height > 0, new.width > 0, new.height > 0, old != new,
              !canvas.drawing.strokes.isEmpty else { return }
        canvas.drawing = canvas.drawing.transformed(using: CGAffineTransform(scaleX: new.width / old.width, y: new.height / old.height))
        // Recorded undo steps refer to the old stroke coordinates.
        canvas.undoManager?.removeAllActions()
        refresh()
    }
}

/// Reports tool picker frame changes before the first stroke. A separate object
/// so the picker (owned by the model) never holds the model itself.
private final class UnyriToolPickerObserver: NSObject, PKToolPickerObserver {
    weak var model: UnyriPhotoEditorModel?
    func toolPickerFramesObscuredDidChange(_ toolPicker: PKToolPicker) { notifyModel() }
    func toolPickerVisibilityDidChange(_ toolPicker: PKToolPicker) { notifyModel() }
    private func notifyModel() {
        let target = model
        Task { @MainActor in target?.measureToolPicker() }
    }
}

private struct UnyriCanvasRepresentable: UIViewRepresentable {
    let model: UnyriPhotoEditorModel
    func makeUIView(context: Context) -> PKCanvasView { model.canvas }
    func updateUIView(_ view: PKCanvasView, context: Context) {}
}

struct UnyriEditorLabel: Identifiable, Equatable {
    static let baseSize: CGFloat = 30
    let id = UUID()
    var text: String
    var color: Color
    /// Centre in the picture, 0…1 on both axes.
    var center: CGPoint
    var scale: CGFloat
    var isEmoji = false
}

/// A draggable, pinch-to-resize text or emoji; tap to edit text.
private struct UnyriEditorLabelView: View {
    @Binding var label: UnyriEditorLabel
    let area: CGSize
    let onEdit: () -> Void
    @State private var dragStart: CGPoint?
    @State private var scaleStart: CGFloat?
    var body: some View {
        Text(label.text)
            .font(.system(size: UnyriEditorLabel.baseSize * label.scale, weight: label.isEmoji ? .regular : .bold))
            .foregroundStyle(label.color)
            .multilineTextAlignment(.center)
            .shadow(color: .black.opacity(0.45), radius: 4)
            .frame(maxWidth: area.width * 0.9)
            .fixedSize()
            .position(x: label.center.x * area.width, y: label.center.y * area.height)
            .gesture(DragGesture(minimumDistance: 1).onChanged { value in
                let start = dragStart ?? label.center
                dragStart = start
                label.center = CGPoint(x: min(1, max(0, start.x + value.translation.width / max(1, area.width))),
                                       y: min(1, max(0, start.y + value.translation.height / max(1, area.height))))
            }.onEnded { _ in dragStart = nil })
            .simultaneousGesture(MagnificationGesture().onChanged { value in
                let start = scaleStart ?? label.scale
                scaleStart = start
                label.scale = min(6, max(0.5, start * value))
            }.onEnded { _ in scaleStart = nil })
            .onTapGesture { if !label.isEmoji { onEdit() } }
            .accessibilityLabel(Text(label.text))
    }
}

/// Full-screen text entry with colour choice, like Telegram's media editor.
private struct UnyriEditorTextEntry: View {
    @State var label: UnyriEditorLabel
    let done: (UnyriEditorLabel?) -> Void
    @FocusState private var focused: Bool
    private let colors: [Color] = [.white, .black, Color(hex: 0xFF453A), Color(hex: 0xFF9F0A), Color(hex: 0xFFD60A),
                                   Color(hex: 0x32D74B), Color(hex: 0x64D2FF), Color(hex: 0x0A84FF), Color(hex: 0xBF5AF2)]
    init(label: UnyriEditorLabel, done: @escaping (UnyriEditorLabel?) -> Void) {
        _label = State(initialValue: label); self.done = done
    }
    var body: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture { done(label) }
            VStack(spacing: 18) {
                HStack {
                    Button("Cancel") { done(nil) }.foregroundStyle(.white.opacity(0.8))
                    Spacer()
                    Button("Done") { done(label) }.font(.body.weight(.semibold)).foregroundStyle(.white)
                }.padding(.horizontal, 20).padding(.top, 12)
                Spacer()
                TextField("", text: $label.text, axis: .vertical)
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(label.color)
                    .multilineTextAlignment(.center)
                    .focused($focused)
                    .padding(.horizontal, 24)
                    .shadow(color: .black.opacity(0.4), radius: 3)
                Spacer()
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(Array(colors.enumerated()), id: \.offset) { _, color in
                            Button { UnyriHaptics.selection(); label.color = color } label: {
                                Circle().fill(color).frame(width: 30, height: 30)
                                    .overlay(Circle().stroke(Color.white, lineWidth: label.color == color ? 3 : 1))
                            }.buttonStyle(.plain)
                        }
                    }.padding(.horizontal, 20)
                }.padding(.bottom, 12)
            }
        }
        .onAppear { focused = true }
    }
}
