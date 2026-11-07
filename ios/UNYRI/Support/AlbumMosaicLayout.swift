import Foundation

struct UnyriAlbumTile: Equatable {
    let index: Int
    let x: Double
    let y: Double
    let width: Double
    let height: Double
}

/// Presentation geometry shared in behaviour with Android's AlbumMosaicLayout.
/// Bounded to ten already-probed aspect ratios: no file I/O or image decoding.
struct UnyriAlbumMosaic: Equatable {
    let width: Double
    let height: Double
    let tiles: [UnyriAlbumTile]

    static func make(ratios: [Double], width: Double, gap: Double = 4) -> Self {
        precondition((1...10).contains(ratios.count) && width.isFinite && width >= 80 && gap.isFinite && gap >= 0)
        let safe = ratios.map { $0.isFinite && $0 > 0 ? min(2.1, max(0.6, $0)) : 1 }
        if safe.count == 1 {
            let height = min(width * 1.2, max(width * 0.5, width / safe[0]))
            return Self(width: width, height: height, tiles: [.init(index: 0, x: 0, y: 0, width: width, height: height)])
        }
        if safe.count == 2 {
            let height = width * 153 / 274
            let split = (width - gap) * min(0.65, max(0.35, safe[0] / safe.reduce(0, +)))
            return Self(width: width, height: height, tiles: [
                .init(index: 0, x: 0, y: 0, width: split, height: height),
                .init(index: 1, x: split + gap, y: 0, width: width - split - gap, height: height)])
        }
        if safe.count == 3 && safe[0] < 0.8 {
            let height = width * 1.05, left = (width - gap) * 0.51, right = width - left - gap, half = (height - gap) / 2
            return Self(width: width, height: height, tiles: [
                .init(index: 0, x: 0, y: 0, width: left, height: height),
                .init(index: 1, x: left + gap, y: 0, width: right, height: half),
                .init(index: 2, x: left + gap, y: half + gap, width: right, height: half)])
        }
        var candidates: [[Int]] = []
        func partitions(_ remaining: Int, _ rows: [Int]) {
            if remaining == 0 { if (2...4).contains(rows.count) { candidates.append(rows) }; return }
            guard rows.count < 4 else { return }
            for count in 1...min(3, remaining) { partitions(remaining - count, rows + [count]) }
        }
        partitions(safe.count, [])
        func heights(_ rows: [Int]) -> [Double] {
            var offset = 0
            return rows.map { count in
                let height = (width - gap * Double(count - 1)) / safe[offset..<(offset + count)].reduce(0, +)
                offset += count
                return height
            }
        }
        let target = width * (safe.count == 3 ? 1.05 : safe.count == 4 ? 1 : safe.count <= 6 ? 1.08 : 1.3)
        func score(_ rows: [Int]) -> Double {
            let values = heights(rows)
            return abs(values.reduce(0, +) + gap * Double(rows.count - 1) - target)
                + values.reduce(0) { $0 + max(0, width * 0.24 - $1) } * 4
                + Double(rows.filter { $0 == 1 }.count) * width * 0.07
                + ((values.max() ?? 0) - (values.min() ?? 0)) * 0.12
        }
        // Explicit comparison retains Android's first-candidate tie breaking.
        let rows = candidates.dropFirst().reduce(candidates[0]) { score($1) < score($0) ? $1 : $0 }
        let values = heights(rows), total = values.reduce(0, +) + gap * Double(rows.count - 1)
        let scale = min(1, width * 1.5 / total)
        var tiles: [UnyriAlbumTile] = [], index = 0, y = 0.0
        for (row, count) in rows.enumerated() {
            let height = values[row] * scale, sum = safe[index..<(index + count)].reduce(0, +)
            var x = 0.0
            for column in 0..<count {
                let tileWidth = column == count - 1 ? width - x : (width - gap * Double(count - 1)) * safe[index] / sum
                tiles.append(.init(index: index, x: x, y: y, width: tileWidth, height: height))
                x += tileWidth + gap; index += 1
            }
            y += height + gap
        }
        return Self(width: width, height: y - gap, tiles: tiles)
    }
}

enum UnyriTimelineAnchor {
    /// ScrollViewReader owns row IDs, not the IDs of nested album members.
    static func rowID(target: String, rows: [(id: String, members: [String])]) -> String? {
        rows.first { $0.id == target || $0.members.contains(target) }?.id
    }
}
