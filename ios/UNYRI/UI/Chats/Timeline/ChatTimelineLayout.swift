import UIKit
import os

/// Debug traces of the timeline: what moved the offset, estimated and measured row
/// heights (row id suffixes and numbers only, never content). Off unless debug logging is on.
enum ChatTimelineTrace {
    static let log = Logger(subsystem: "com.unyri.timeline", category: "moves")
    static let sizes = Logger(subsystem: "com.unyri.timeline", category: "sizes")
}

/// One column for the inverted chat timeline.
///
/// Index 0 is the newest row and sits at list-space y = 0. The collection view is
/// flipped vertically, so list-space y = 0 is drawn at the visual bottom; every cell
/// is flipped back through its attributes. Rows that size themselves while the reader
/// scrolls into history, and older pages, land at the list-space end, where they never
/// move what is on screen.
///
/// The reader's position is kept by an anchor: while the reader is away from the
/// newest message, a size change or insertion below the anchor row (list-space
/// before it) shifts the content offset by the same amount, so the anchor stays put.
final class ChatTimelineLayout: UICollectionViewLayout {
    /// Measured heights closer than this to the laid-out height are pixel-rounding noise.
    static let measurementTolerance: CGFloat = 1
    /// Supplied by the timeline controller (current snapshot order, newest first).
    var itemID: (Int) -> String? = { _ in nil }
    var indexOfID: (String) -> Int? = { _ in nil }
    var estimatedHeight: (Int) -> CGFloat = { _ in 64 }
    /// The row whose on-screen position must not change, or nil while following the newest row.
    var anchorIndex: () -> Int? = { nil }
    /// True while new rows at the newest end should slide in from below.
    var animatesArrivals = false
    /// Who sent the row at an index, for its arrival motion.
    var arrivalStyle: ((Int) -> ChatTimelineArrival)?

    private var heights: [String: CGFloat] = [:]
    /// Estimates of rows not measured yet. prepare() runs after every self-sizing pass and
    /// used to re-estimate every unmeasured row each time (text split into lines included):
    /// about 300 ms of main-thread work over four chat openings in a profile.
    private var estimates: [String: CGFloat] = [:]
    private var heightsWidth: CGFloat = 0
    private var origins: [CGFloat] = []
    private var sizes: [CGFloat] = []
    private var contentHeight: CGFloat = 0
    private var width: CGFloat = 0
    private var captured: (id: String, origin: CGFloat)?
    private var insertedIndexes = Set<Int>()

    /// Forgets every measured height (a new chat, account or appearance).
    func resetHeights() { heights.removeAll(); estimates.removeAll() }
    /// Rows whose content changed: estimate them again if they are not measured yet.
    func forgetEstimates(_ ids: [String]) { for id in ids { estimates[id] = nil } }
    /// The rows changed (arrivals, history, edits, grouping): estimate them again. Cheap
    /// next to what it saves, since layout passes between two updates reuse them.
    func forgetAllEstimates() { estimates.removeAll(keepingCapacity: true) }
    /// Heights remembered from an earlier visit, for rows not measured yet at this width.
    func seed(_ remembered: [String: CGFloat], width: CGFloat) {
        if abs(width - heightsWidth) > 0.5 { heights.removeAll(); estimates.removeAll(); heightsWidth = width }
        for (id, height) in remembered where heights[id] == nil { heights[id] = height }
    }

    /// Measured height for a row, if it was laid out at the current width.
    func measuredHeight(of id: String) -> CGFloat? { heights[id] }

    /// List-space frame of a row in the last prepared layout.
    func frame(at index: Int) -> CGRect? {
        guard index >= 0, index < origins.count else { return nil }
        return CGRect(x: 0, y: origins[index], width: width, height: sizes[index])
    }

    var itemCount: Int { origins.count }

    override func prepare() {
        super.prepare()
        guard let collectionView else { return }
        let currentWidth = collectionView.bounds.width
        if abs(currentWidth - heightsWidth) > 0.5 {
            heights.removeAll()
            estimates.removeAll()
            heightsWidth = currentWidth
        }
        width = currentWidth
        let count = collectionView.numberOfSections > 0 ? collectionView.numberOfItems(inSection: 0) : 0
        if origins.count != count {
            origins = Array(repeating: 0, count: count)
            sizes = Array(repeating: 0, count: count)
        }
        var y: CGFloat = 0
        for index in 0..<count {
            let height: CGFloat
            if let id = itemID(index) {
                if let measured = heights[id] { height = measured }
                else if let estimate = estimates[id] { height = estimate }
                else { let estimate = max(1, estimatedHeight(index)); estimates[id] = estimate; height = estimate }
            } else { height = max(1, estimatedHeight(index)) }
            origins[index] = y
            sizes[index] = height
            y += height
        }
        contentHeight = y
    }

    override var collectionViewContentSize: CGSize { CGSize(width: width, height: contentHeight) }

    override func layoutAttributesForElements(in rect: CGRect) -> [UICollectionViewLayoutAttributes]? {
        guard !origins.isEmpty else { return [] }
        var low = 0, high = origins.count
        while low < high {
            let mid = (low + high) / 2
            if origins[mid] + sizes[mid] <= rect.minY { low = mid + 1 } else { high = mid }
        }
        var result: [UICollectionViewLayoutAttributes] = []
        var index = low
        while index < origins.count, origins[index] < rect.maxY {
            result.append(attributes(index))
            index += 1
        }
        return result
    }

    override func layoutAttributesForItem(at indexPath: IndexPath) -> UICollectionViewLayoutAttributes? {
        guard indexPath.section == 0, indexPath.item < origins.count else { return nil }
        return attributes(indexPath.item)
    }

    private func attributes(_ index: Int) -> UICollectionViewLayoutAttributes {
        let value = UICollectionViewLayoutAttributes(forCellWith: IndexPath(item: index, section: 0))
        value.frame = CGRect(x: 0, y: origins[index], width: width, height: sizes[index])
        value.transform = CGAffineTransform(scaleX: 1, y: -1)
        return value
    }

    override func shouldInvalidateLayout(forBoundsChange newBounds: CGRect) -> Bool {
        abs(newBounds.width - width) > 0.5
    }

    // MARK: Self-sizing

    override func shouldInvalidateLayout(forPreferredLayoutAttributes preferred: UICollectionViewLayoutAttributes,
                                         withOriginalAttributes original: UICollectionViewLayoutAttributes) -> Bool {
        let index = preferred.indexPath.item
        guard let id = itemID(index), preferred.size.height.isFinite, preferred.size.height > 0 else { return false }
        let measured = preferred.size.height
        let laidOut = original.size.height
        // A hosted row's height flips by a pixel or two with its position on screen (pixel
        // rounding). Taking such a flip moved every row above it, which moved this row and
        // flipped it again: the list trembled under a held finger in long chats (owner's
        // videos, 11 Nov; 56 of 65 rows flipped on the simulator). Below one point, keep the
        // height already laid out; a real change (a reaction, a decoded photo) is larger.
        if abs(measured - laidOut) < Self.measurementTolerance {
            if heights[id] == nil { heights[id] = laidOut } // the estimate is gone for good
            return false
        }
        heights[id] = measured
        return true
    }

    override func invalidationContext(forPreferredLayoutAttributes preferred: UICollectionViewLayoutAttributes,
                                      withOriginalAttributes original: UICollectionViewLayoutAttributes) -> UICollectionViewLayoutInvalidationContext {
        let context = super.invalidationContext(forPreferredLayoutAttributes: preferred, withOriginalAttributes: original)
        let index = preferred.indexPath.item
        guard index < sizes.count else { return context }
        let delta = preferred.size.height - sizes[index]
        context.contentSizeAdjustment = CGSize(width: 0, height: delta)
        // A row nearer the newest end than the anchor grew or shrank: everything older,
        // the anchor included, moved by delta in list space. Follow it.
        if delta != 0, let anchor = anchorIndex(), index < anchor {
            context.contentOffsetAdjustment = CGPoint(x: 0, y: delta)
            ChatTimelineTrace.log.debug("resize below anchor: row \(index, privacy: .public) anchor \(anchor, privacy: .public) delta \(Double(delta), privacy: .public)")
        } else if abs(delta) > 0.5 {
            ChatTimelineTrace.log.debug("resize: row \(index, privacy: .public) anchor \(self.anchorIndex() ?? -1, privacy: .public) delta \(Double(delta), privacy: .public)")
        }
        return context
    }

    // MARK: Updates

    /// Remembers where the anchor row is before a snapshot is applied.
    func captureAnchor() {
        guard let anchor = anchorIndex(), anchor < origins.count, let id = itemID(anchor) else { captured = nil; return }
        captured = (id, origins[anchor])
    }

    /// The offset change that keeps the captured anchor in place after an update (consumed once).
    func consumeAnchorShift() -> CGFloat? {
        guard let captured else { return nil }
        self.captured = nil
        guard let index = indexOfID(captured.id), index < origins.count else { return nil }
        let shift = origins[index] - captured.origin
        return abs(shift) > 0.01 ? shift : nil
    }

    override func targetContentOffset(forProposedContentOffset proposed: CGPoint) -> CGPoint {
        guard let shift = consumeAnchorShift() else { return proposed }
        ChatTimelineTrace.log.debug("target offset shift \(Double(shift), privacy: .public)")
        return CGPoint(x: proposed.x, y: proposed.y + shift)
    }

    override func prepare(forCollectionViewUpdates updateItems: [UICollectionViewUpdateItem]) {
        super.prepare(forCollectionViewUpdates: updateItems)
        insertedIndexes = Set(updateItems.compactMap { $0.updateAction == .insert ? $0.indexPathAfterUpdate?.item : nil })
    }

    override func finalizeCollectionViewUpdates() {
        super.finalizeCollectionViewUpdates()
        insertedIndexes.removeAll()
    }

    override func initialLayoutAttributesForAppearingItem(at itemIndexPath: IndexPath) -> UICollectionViewLayoutAttributes? {
        guard let value = layoutAttributesForItem(at: itemIndexPath)?.copy() as? UICollectionViewLayoutAttributes else { return nil }
        // A new message at the newest end rises from under the composer. Your own grows
        // from its trailing edge as it lifts off the composer; an incoming one settles in
        // from its leading edge. Only position, scale and opacity animate (GPU work, no
        // relayout), and Reduce Motion keeps the plain fade-and-rise.
        if animatesArrivals, insertedIndexes.contains(itemIndexPath.item), itemIndexPath.item < 3 {
            let style = arrivalStyle?(itemIndexPath.item) ?? .other
            value.frame.origin.y -= value.frame.height * (style == .incoming ? 0.6 : 1)
            value.alpha = 0
            if style != .other, !UIAccessibility.isReduceMotionEnabled {
                let scale: CGFloat = style == .outgoing ? 0.9 : 0.94
                // Rows are full width: keep the bubble's own edge where it will land.
                let shift = value.frame.width * (1 - scale) / 2 * (style == .outgoing ? 1 : -1)
                value.transform = CGAffineTransform(translationX: shift, y: 0).scaledBy(x: scale, y: -scale)
            }
        }
        return value
    }
}
