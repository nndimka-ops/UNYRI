import SwiftUI
import UIKit

/// An inert accessibility frame sourced from the actual window, not guessed status
/// bar heights. The walkthrough compares the panel against this window safe area.
struct UnyriReactionSafeAreaProbe: UIViewRepresentable {
    func makeUIView(context: Context) -> Probe { Probe() }
    func updateUIView(_ view: Probe, context: Context) { view.setNeedsLayout() }
    final class Probe: UIView {
        private let safe = UIView()
        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            safe.isAccessibilityElement = ProcessInfo.processInfo.environment["UNYRI_QA_REACTION_PICKER"] == "1"
            safe.accessibilityIdentifier = "reaction-safe-area"
            safe.accessibilityLabel = "Safe area"
            addSubview(safe)
        }
        required init?(coder: NSCoder) { nil }
        override func layoutSubviews() {
            super.layoutSubviews()
            guard let window else { return }
            safe.frame = convert(window.safeAreaLayoutGuide.layoutFrame, from: window)
        }
    }
}

struct UnyriReactionPickerPanel: View {
    let store: UnyriReactionStore?
    let cells: NSMapTable<NSString, UIView>
    let choose: (String) -> Void
    let close: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var catalog: UnyriReactionCatalog?
    @State private var results: [UnyriReactionEmoji] = []
    @State private var query = ""
    @State private var searching = false
    @State private var history: UnyriReactionHistory
    @State private var selectedCategory = "people"
    @FocusState private var searchFocused: Bool
    private let columns = [GridItem(.adaptive(minimum: 44), spacing: 0)]

    init(store: UnyriReactionStore?, cells: NSMapTable<NSString, UIView>, choose: @escaping (String) -> Void,
         close: @escaping () -> Void) {
        self.store = store; self.cells = cells; self.choose = choose; self.close = close
        _history = State(initialValue: store?.history ?? UnyriReactionHistory())
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(Color.secondary.opacity(0.55)).frame(width: 34, height: 4)
                .padding(.top, 8).padding(.bottom, 6).accessibilityHidden(true)
                .frame(maxWidth: .infinity).contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 12).onEnded { if $0.translation.height > 32 { close() } })
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Find emoji", text: $query).textInputAutocapitalization(.never)
                    .autocorrectionDisabled().submitLabel(.search).focused($searchFocused)
                    .onSubmit { searchFocused = false }.accessibilityIdentifier("reaction-search")
            }.padding(.horizontal, 12).frame(minHeight: 44)
                .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal, 12)
            if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !history.recent.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Recent").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 8)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 0) {
                            ForEach(history.recent, id: \.self) { emoji in
                                emojiCell(emoji, identifier: "reaction-recent-\(emoji)", exact: true).frame(width: 44)
                            }
                        }
                    }.frame(height: 44).accessibilityIdentifier("reaction-recents")
                }.padding(.horizontal, 8).padding(.top, 4)
            }
            if let catalog {
                ScrollViewReader { reader in
                    VStack(spacing: 0) {
                        // Outside the scrolling grid, so it remains pinned while every
                        // category heading below has a stable scroll-to target.
                        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 0) {
                                    ForEach(catalog.categories) { category in
                                        Button {
                                            UnyriHaptics.selection(); selectedCategory = category.id
                                            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                                                reader.scrollTo(category.id, anchor: .top)
                                            }
                                        } label: {
                                            Image(systemName: category.symbol).font(.system(size: 19))
                                                .frame(width: 44, height: 44)
                                                .background(selectedCategory == category.id ? Color.white.opacity(0.12) : Color.clear, in: Circle())
                                        }.buttonStyle(.plain)
                                            .accessibilityLabel(category.localizedTitle(language: UnyriL10n.language))
                                            .accessibilityIdentifier("reaction-category-\(category.id)")
                                    }
                                }
                            }.frame(height: 44).padding(.horizontal, 8)
                        }
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 0) {
                                Color.clear.frame(height: 0).id("reaction-results-top")
                                if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                    ForEach(catalog.categories) { category in
                                        Text(category.localizedTitle(language: UnyriL10n.language))
                                            .font(.caption).foregroundStyle(.secondary).padding(.horizontal, 8)
                                            .padding(.top, 4).id(category.id)
                                        LazyVGrid(columns: columns, spacing: 0) {
                                            ForEach(catalog.entriesByCategory[category.id] ?? []) { entry in
                                                emojiCell(entry.emoji, identifier: "reaction-grid-\(entry.emoji)", name: entry.localizedName(language: UnyriL10n.language))
                                            }
                                        }
                                    }
                                } else if results.isEmpty && !searching {
                                    Text("No results").foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 44)
                                        .accessibilityIdentifier("reaction-no-results")
                                } else {
                                    LazyVGrid(columns: columns, spacing: 0) {
                                        ForEach(results) { entry in
                                            emojiCell(entry.emoji, identifier: "reaction-grid-\(entry.emoji)", name: entry.localizedName(language: UnyriL10n.language),
                                                exact: UnyriReactionSearch.baseKey(query.trimmingCharacters(in: .whitespacesAndNewlines)) == UnyriReactionSearch.baseKey(entry.emoji))
                                        }
                                    }
                                }
                            }.padding(.horizontal, 8).padding(.bottom, 8)
                        }.accessibilityIdentifier("reaction-grid")
                            .mask {
                                VStack(spacing: 0) {
                                    Rectangle()
                                    LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom).frame(height: 8)
                                }
                            }
                    }
                    .onChange(of: query) { _ in reader.scrollTo("reaction-results-top", anchor: .top) }
                    .onChange(of: results.count) { _ in
                        if !query.isEmpty { reader.scrollTo("reaction-results-top", anchor: .top) }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityElement(children: .contain).accessibilityIdentifier("reaction-panel")
            .simultaneousGesture(DragGesture(minimumDistance: 28).onEnded { value in
                // A dismissal starts at the grabber/search/recents area. Scrolling the
                // category grid does not accidentally dismiss the picker.
                if value.startLocation.y < 112 && value.translation.height > 60 { close() }
            })
            .task { catalog = await UnyriReactionCatalog.load() }
            .task(id: query) {
                let text = query
                searching = true; results = []
                let loaded = await UnyriReactionCatalog.load()
                let worker = Task.detached(priority: .userInitiated) { loaded.search(text) }
                let found = await withTaskCancellationHandler(operation: { await worker.value }, onCancel: { worker.cancel() })
                guard !Task.isCancelled, text == query else { return }
                results = found; searching = false
            }
    }

    private func emojiCell(_ emoji: String, identifier: String, name: String? = nil, exact: Bool = false) -> some View {
        UnyriReactionEmojiButton(emoji: emoji,
            name: name ?? catalog?.entries.first(where: { $0.emoji == UnyriReactionTones.base(of: emoji) })?.localizedName(language: UnyriL10n.language) ?? emoji,
            preferred: exact ? emoji : history.tone(for: emoji),
            cells: cells, identifier: identifier, choose: choose, rememberTone: { variant in
                store?.setTone(variant, for: emoji)
                history.setTone(variant, for: emoji)
            })
    }
}

/// One gesture decision prevents a tone long-press also sending the base on release.
struct UnyriReactionEmojiButton: View {
    let emoji: String
    let name: String
    let preferred: String
    let cells: NSMapTable<NSString, UIView>
    let identifier: String
    let choose: (String) -> Void
    let rememberTone: (String) -> Void
    @State private var showingTones = false
    private var variants: [String] { UnyriReactionTones.variants(of: emoji) }
    private func send(_ value: String, originKey: String) {
        if let view = cells.object(forKey: originKey as NSString) { cells.setObject(view, forKey: value as NSString) }
        choose(value)
    }
    var body: some View {
        Text(preferred).font(.system(size: 30)).frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle()).background {
                UnyriMenuReactionCell(cells: cells, emoji: preferred)
                UnyriMenuReactionCell(cells: cells, emoji: identifier)
            }
            .gesture(LongPressGesture(minimumDuration: 0.4).exclusively(before: TapGesture()).onEnded { value in
                switch value {
                case .first:
                    if !variants.isEmpty { UnyriHaptics.selection(); showingTones = true }
                case .second: send(preferred, originKey: identifier)
                }
            })
            .accessibilityElement().accessibilityLabel(name).accessibilityValue(preferred)
            .accessibilityIdentifier(identifier).accessibilityAddTraits(.isButton)
            .accessibilityAction { send(preferred, originKey: identifier) }
            .modifier(UnyriReactionToneAccessibility(available: !variants.isEmpty) { showingTones = true })
            .popover(isPresented: $showingTones, attachmentAnchor: .rect(.bounds), arrowEdge: .bottom) {
                HStack(spacing: 0) {
                    ForEach(variants, id: \.self) { variant in
                        Button {
                            rememberTone(variant); showingTones = false; send(variant, originKey: identifier + "-tone-" + variant)
                        } label: {
                            Text(variant).font(.system(size: 30)).frame(width: 44, height: 48)
                                .background(UnyriMenuReactionCell(cells: cells, emoji: identifier + "-tone-" + variant))
                        }.buttonStyle(.plain).accessibilityLabel(variant)
                            .accessibilityIdentifier("reaction-tone-\(variant)")
                    }
                }.padding(6).modifier(UnyriCompactPopover())
            }
    }
}

private struct UnyriReactionToneAccessibility: ViewModifier {
    let available: Bool
    let action: () -> Void
    @ViewBuilder func body(content: Content) -> some View {
        if available { content.accessibilityAction(named: Text("Skin tone"), action) }
        else { content }
    }
}

/// The tone popover stays a popover on iPhone where iOS allows it (16.4+); earlier releases use the system default.
private struct UnyriCompactPopover: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 16.4, *) { content.presentationCompactAdaptation(.popover) } else { content }
    }
}
