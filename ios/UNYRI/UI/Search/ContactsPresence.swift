import SwiftUI
import Combine
import UIKit

@MainActor
final class ContactPresenceCell: ObservableObject {
    /// Lease refreshes update private evidence without publishing unchanged UI.
    @Published private(set) var value: ContactPresenceDisplay
    private var evidence: ContactPresenceValue
    private var hasLiveEvidence = false
    init(lastSeen: String) {
        evidence = ContactPresenceValue(serverLastSeen: lastSeen)
        value = evidence.display(at: ProcessInfo.processInfo.systemUptime)
    }
    var presentation: ContactPresenceDisplay { evidence.display(at: ProcessInfo.processInfo.systemUptime) }
    var nextOnlineExpiry: TimeInterval? { evidence.nextOnlineExpiry }

    func apply(_ observation: PresenceObservation?) {
        let now = ProcessInfo.processInfo.systemUptime
        if let observation {
            hasLiveEvidence = true
            let lastDate: Date? = observation.item.lastSeenAt == nil ? nil :
                (observation.item.online ? observation.observedAt : observation.item.lastSeenAt.flatMap(PresencePolicy.timestamp))
            let last = lastDate.map(Self.lastSeen) ?? UnyriL10n.string("Status hidden")
            evidence.apply(liveOnline: observation.item.online,
                           label: observation.item.online ? UnyriL10n.string("online") : last, lastSeen: last,
                           expiresAtUptime: observation.expiresAtUptime, uptime: now)
        } else { evidence.apply(liveOnline: nil, uptime: now) }
        publish()
    }
    func seed(lastSeen raw: String?) {
        guard !hasLiveEvidence, let date = raw.flatMap(PresencePolicy.timestamp) else { return }
        evidence = ContactPresenceValue(serverLastSeen: Self.lastSeen(date))
        publish()
    }
    func hidden() {
        hasLiveEvidence = true
        let text = UnyriL10n.string("Status hidden")
        evidence.apply(liveOnline: false, label: text, lastSeen: text)
        evidence.invalidate()
        publish()
    }
    private func publish() {
        let next = presentation
        if value != next { value = next }
    }
    static func lastSeen(_ date: Date) -> String {
        let label = PresencePolicy.lastSeenLabel(date, now: Date(), locale: Locale(identifier: UnyriL10n.language),
                                                calendar: .autoupdatingCurrent)
        return String(format: UnyriL10n.string(label.key), label.value)
    }
}

/// One presence observer distributes only changed presentation to the owning row.
/// Section classification is a separate deduplicated stream of known contact IDs.
@MainActor
final class ContactsPresenceHub: ObservableObject {
    let classificationChanges = CurrentValueSubject<Set<String>, Never>([])
    private let realtime: RealtimeClient
    private let owner = "contacts:" + UUID().uuidString
    private var cells: [String: ContactPresenceCell] = [:]
    private var visible: [UUID: String] = [:]
    private var observations = Set<AnyCancellable>()
    private var subscriptionTask: Task<Void, Never>?
    private var expiryTask: Task<Void, Never>?
    private var classificationTask: Task<Void, Never>?
    private var classifications = ContactClassifications()
    private var contactIDs = Set<String>()
    private var enabled = false
    private var requestedIDs = Set<String>()
    private var ready = Set<String>()
    private var probes: [UUID: WeakContactsProbe] = [:]
    private weak var scrollView: UIScrollView?
    private var scrollObservation: NSKeyValueObservation?
    private var pendingAnchor: (revision: Int, anchor: ContactScrollAnchor)?
    private var restorationQueued = false
    var blocked = Set<String>() {
        didSet {
            for id in blocked {
                cells[id]?.hidden()
                classifications.apply(id: id, online: false)
            }
            ready.subtract(blocked)
            publishClassifications()
            updateSubscription()
            refresh()
        }
    }

    init(realtime: RealtimeClient) {
        self.realtime = realtime
        realtime.$presence.combineLatest(realtime.$state.removeDuplicates())
            .sink { [weak self] _ in
                // @Published arrives before assignment; read the committed snapshot.
                Task { @MainActor [weak self] in self?.refresh() }
            }.store(in: &observations)
        realtime.presenceHiddenIDs.sink { [weak self] ids in
            guard let self, self.enabled else { return }
            let current = Set(self.visible.values).intersection(self.requestedIDs).subtracting(self.blocked)
            self.ready.formUnion(current)
            for id in current {
                if ids.contains(id) {
                    self.cells[id]?.hidden()
                    self.classifications.apply(id: id, online: false)
                } else if let observation = self.realtime.presenceObservation(for: id) {
                    self.cells[id]?.apply(observation)
                    self.classifications.apply(id: id, online: observation.item.online)
                }
            }
            self.scheduleExpiry()
            self.publishClassifications()
        }.store(in: &observations)
    }

    func setContacts(_ ids: Set<String>) {
        guard contactIDs != ids else { return }
        contactIDs = ids
        publishClassifications()
    }

    func cell(for user: PublicUser) -> ContactPresenceCell {
        if let cell = cells[user.id] { return cell }
        // Directory online hints cannot create a confirmed lease.
        let last = user.lastSeenAt.flatMap(PresencePolicy.timestamp).map(ContactPresenceCell.lastSeen) ?? ""
        let cell = ContactPresenceCell(lastSeen: last)
        cells[user.id] = cell
        return cell
    }

    func visibility(id: String, token: UUID, visible isVisible: Bool) {
        if isVisible {
            guard visible[token] != id else { return }
            visible[token] = id
        } else {
            guard visible.removeValue(forKey: token) != nil else { return }
            if !visible.values.contains(id) { ready.remove(id); cells[id]?.apply(nil) }
        }
        updateSubscription()
        // A temporary handoff preserves only this cell's still-fresh confirmed lease.
        if isVisible, !ready.contains(id) { cells[id]?.apply(nil) }
        scheduleExpiry()
    }

    func setEnabled(_ enabled: Bool) {
        self.enabled = enabled
        if !enabled {
            ready.removeAll(); requestedIDs.removeAll()
            classificationTask?.cancel(); classificationTask = nil
            for cell in cells.values { cell.apply(nil) }
        }
        updateSubscription()
        refresh()
        scheduleExpiry()
    }

    private func updateSubscription() {
        subscriptionTask?.cancel()
        guard enabled else { realtime.unsubscribePresence(owner: owner); return }
        let ids = Set(visible.values).subtracting(blocked).sorted()
        subscriptionTask = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return }
            guard let self, self.enabled, !Task.isCancelled else { return }
            self.requestedIDs = Set(ids)
            self.realtime.subscribePresence(owner: self.owner, userIDs: ids, priority: 20)
        }
    }

    private func refresh() {
        guard enabled else { return }
        for id in Set(visible.values) {
            cells[id]?.apply(blocked.contains(id) || !ready.contains(id) ? nil : realtime.presenceObservation(for: id))
        }
        scheduleExpiry()
    }

    /// One bounded deadline task for retained online cells, including offscreen ones.
    /// No per-row timers and no task at all when every cell is offline/unknown.
    private func scheduleExpiry() {
        expiryTask?.cancel(); expiryTask = nil
        guard let deadline = cells.values.compactMap(\.nextOnlineExpiry).min() else { return }
        let delay = max(0, deadline - ProcessInfo.processInfo.systemUptime)
        expiryTask = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64((delay * 1_000_000_000).rounded(.up))) } catch { return }
            guard let self, !Task.isCancelled else { return }
            let now = ProcessInfo.processInfo.systemUptime
            for cell in self.cells.values where cell.nextOnlineExpiry.map({ $0 <= now }) == true { cell.apply(nil) }
            self.scheduleExpiry()
        }
    }

    private var scrolling: Bool {
        scrollView.map { $0.isTracking || $0.isDragging || $0.isDecelerating } ?? false
    }
    private func publishClassifications() {
        let next = classifications.onlineIDs(in: contactIDs)
        guard next != classificationChanges.value else { classificationTask?.cancel(); classificationTask = nil; return }
        // Do not interrupt a gesture/deceleration to regroup. Flush the newest known
        // classification once scrolling settles, with a saved row/pixel anchor.
        if scrolling {
            classificationTask?.cancel()
            classificationTask = Task { [weak self] in
                do { try await Task.sleep(nanoseconds: 150_000_000) } catch { return }
                guard let self, self.enabled, !Task.isCancelled else { return }
                self.publishClassifications()
            }
        } else {
            classificationTask?.cancel(); classificationTask = nil
            classificationChanges.send(next)
        }
    }

    func registerViewport(_ probe: ContactsViewportProbe, scroll: UIScrollView) {
        probes[probe.token] = WeakContactsProbe(probe)
        if scrollView !== scroll {
            scrollObservation = nil
            scrollView = scroll
            scrollObservation = scroll.observe(\.contentOffset, options: [.new]) { [weak self] _, _ in
                Task { @MainActor [weak self] in self?.refreshViewport() }
            }
        }
        if probe.tracksVisibility { refreshViewport() }
        if pendingAnchor?.anchor.id == probe.id { requestAnchorRestoration() }
    }
    func unregisterViewport(_ token: UUID, id: String) {
        probes[token] = nil
        visibility(id: id, token: token, visible: false)
        if probes.isEmpty { scrollObservation = nil; scrollView = nil }
    }
    func refreshViewport() {
        guard let scroll = scrollView else { return }
        for (token, weakProbe) in probes {
            guard let probe = weakProbe.view else { continue }
            guard probe.tracksVisibility else { continue }
            let intersection = probe.convert(probe.bounds, to: scroll).intersection(scroll.bounds)
            visibility(id: probe.id, token: token,
                       visible: probe.window != nil && !intersection.isNull && intersection.width > 0 && intersection.height > 0)
        }
    }

    func captureScrollAnchor(revision: Int, excluding changed: Set<String>) {
        pendingAnchor = nil
        guard let scroll = scrollView else { return }
        let visibleProbes = probes.values.compactMap(\.view).filter {
            $0.window != nil && $0.bounds.height > 0 && $0.convert($0.bounds, to: scroll).intersects(scroll.bounds)
        }.sorted { $0.convert($0.bounds, to: scroll).minY < $1.convert($1.bounds, to: scroll).minY }
        guard let probe = visibleProbes.first(where: { !changed.contains($0.id) }) ?? visibleProbes.first else { return }
        let offset = probe.convert(probe.bounds, to: scroll).minY - scroll.contentOffset.y
        pendingAnchor = (revision, ContactScrollAnchor(id: probe.id, viewportOffset: Double(offset)))
    }
    func clearScrollAnchor() { pendingAnchor = nil }
    func anchorNeedingMaterialization(revision: Int) -> ContactScrollAnchor? {
        guard let pendingAnchor, pendingAnchor.revision == revision else { return nil }
        let materialized = probes.values.contains { $0.view?.id == pendingAnchor.anchor.id && ($0.view?.bounds.height ?? 0) > 0 }
        return materialized ? nil : pendingAnchor.anchor
    }
    func requestAnchorRestoration() {
        guard pendingAnchor != nil, !restorationQueued else { return }
        restorationQueued = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.restorationQueued = false
            self.restoreScrollAnchor()
        }
    }
    private func restoreScrollAnchor() {
        guard let pendingAnchor, let scroll = scrollView else { return }
        scroll.superview?.layoutIfNeeded()
        guard let probe = probes.values.compactMap(\.view).first(where: { $0.id == pendingAnchor.anchor.id && $0.bounds.height > 0 }) else { return }
        let rowMinY = probe.convert(probe.bounds, to: scroll).minY
        let minimum = -scroll.adjustedContentInset.top
        let maximum = max(minimum, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
        let offset = ContactsScrollPolicy.restoredOffset(rowMinY: Double(rowMinY),
            viewportOffset: pendingAnchor.anchor.viewportOffset, minimum: Double(minimum), maximum: Double(maximum))
        self.pendingAnchor = nil
        if abs(scroll.contentOffset.y - CGFloat(offset)) > 0.5 {
            scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: CGFloat(offset)), animated: false)
        }
    }
}

/// Runs after SwiftUI applies the new flat list, with no scroll-offset State at the screen root.
struct ContactsProjectionBridge: UIViewRepresentable {
    let revision: Int
    let hub: ContactsPresenceHub
    func makeUIView(context: Context) -> UIView {
        let view = UIView(); view.isUserInteractionEnabled = false; view.isAccessibilityElement = false; return view
    }
    func updateUIView(_ view: UIView, context: Context) { hub.requestAnchorRestoration() }
}

/// Actual viewport visibility; probes also retain a pixel anchor during classification changes.
struct ContactsRowVisibility: ViewModifier {
    let id: String
    let hub: ContactsPresenceHub
    @State private var token = UUID()
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.background(ContactsViewportTracker(id: id, token: token, hub: hub, tracksVisibility: false).allowsHitTesting(false))
                .onScrollVisibilityChange(threshold: 0.01) { hub.visibility(id: id, token: token, visible: $0) }
                .onDisappear { hub.unregisterViewport(token, id: id) }
        } else {
            content.background(ContactsViewportTracker(id: id, token: token, hub: hub, tracksVisibility: true).allowsHitTesting(false))
                .onDisappear { hub.unregisterViewport(token, id: id) }
        }
    }
}

@MainActor
private final class WeakContactsProbe {
    weak var view: ContactsViewportProbe?
    init(_ view: ContactsViewportProbe) { self.view = view }
}

private struct ContactsViewportTracker: UIViewRepresentable {
    let id: String
    let token: UUID
    let hub: ContactsPresenceHub
    let tracksVisibility: Bool
    func makeUIView(context: Context) -> ContactsViewportProbe {
        ContactsViewportProbe(id: id, token: token, hub: hub, tracksVisibility: tracksVisibility)
    }
    func updateUIView(_ view: ContactsViewportProbe, context: Context) { view.attach() }
    static func dismantleUIView(_ view: ContactsViewportProbe, coordinator: ()) { view.hub?.unregisterViewport(view.token, id: view.id) }
}

/// Noninteractive background. One shared scroll observer, never a scroll observer per row.
final class ContactsViewportProbe: UIView {
    let id: String
    let token: UUID
    let tracksVisibility: Bool
    weak var hub: ContactsPresenceHub?
    init(id: String, token: UUID, hub: ContactsPresenceHub, tracksVisibility: Bool) {
        self.id = id; self.token = token; self.hub = hub; self.tracksVisibility = tracksVisibility
        super.init(frame: .zero)
        isUserInteractionEnabled = false; isAccessibilityElement = false
    }
    required init?(coder: NSCoder) { nil }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { hub?.unregisterViewport(token, id: id) } else { attach() }
    }
    override func layoutSubviews() { super.layoutSubviews(); attach() }
    func attach() {
        guard window != nil else { return }
        var ancestor = superview
        while let view = ancestor {
            if let scroll = view as? UIScrollView { hub?.registerViewport(self, scroll: scroll); return }
            ancestor = view.superview
        }
    }
}
