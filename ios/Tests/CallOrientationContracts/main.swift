import Foundation

// From repository root on macOS:
// See README.md for the complete swiftc command (including CallModels' archive/API dependencies).
// /tmp/call-orientation-contracts
func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    precondition(condition(), message)
}
func near(_ actual: CGFloat, _ expected: CGFloat, _ message: String) {
    check(abs(actual - expected) < 0.00001, "\(message): \(actual) != \(expected)")
}
func sample(_ state: inout CallOrientationHysteresis, angle: Double, at time: Double, z: Double = 0) {
    let radians = angle * .pi / 180
    state.sample(x: -sin(radians), y: -cos(radians), z: z, at: time)
}

let orientations: [CallOrientation] = [.portrait, .landscapeLeft, .portraitUpsideDown, .landscapeRight]
for (index, orientation) in orientations.enumerated() {
    check(CallOrientationPolicy.frameRotation(orientation, front: false) == [90, 0, 270, 180][index], "Back camera table")
    check(CallOrientationPolicy.frameRotation(orientation, front: true) == [90, 180, 270, 0][index], "Front camera table")
    for front in [false, true] {
        let rotation = CallOrientationPolicy.frameRotation(orientation, front: front)
        check((rotation == 90 || rotation == 270) == !orientation.landscape, "Mirror axis follows replacement rotation")
    }
}

var boundary = CallOrientationHysteresis()
sample(&boundary, angle: 60, at: 10)
sample(&boundary, angle: 60, at: 10.25)
check(boundary.orientation == .landscapeLeft, "Exact 60 degree and 0.25 second thresholds")

var h = CallOrientationHysteresis()
sample(&h, angle: 59.9, at: 0)
sample(&h, angle: 59.9, at: 1)
check(h.orientation == .portrait, "Stay inside the 60 degree hysteresis")
sample(&h, angle: 61, at: 2)
sample(&h, angle: 61, at: 2.249)
check(h.orientation == .portrait, "Candidate must last 0.25 seconds")
sample(&h, angle: 61, at: 2.25)
check(h.orientation == .landscapeLeft && h.stableSince == 2.25, "Commit at 0.25 seconds")
sample(&h, angle: 31, at: 3)
sample(&h, angle: 31, at: 4)
check(h.orientation == .landscapeLeft, "Reverse hysteresis measured from current axis")
sample(&h, angle: 0, at: 5)
sample(&h, angle: 0, at: 5.25, z: 0.86)
check(h.orientation == .landscapeLeft, "Face up cancels a pending turn")
sample(&h, angle: 0, at: 6, z: -0.86)
check(h.orientation == .landscapeLeft, "Face down keeps the last orientation")
sample(&h, angle: 0, at: 7, z: 0.85)
sample(&h, angle: 0, at: 7.25, z: 0.85)
check(h.orientation == .portrait, "0.85g boundary is inclusive")
sample(&h, angle: 270, at: 8)
sample(&h, angle: 270, at: 8.25)
check(h.orientation == .landscapeRight, "Wrap around the 360 degree seam")
sample(&h, angle: 180, at: 9)
sample(&h, angle: 180, at: 9.25)
check(h.orientation == .portraitUpsideDown, "Capture supports upside down even though phone UI does not")
sample(&h, angle: 90, at: 10)
sample(&h, angle: 180, at: 10.2)
sample(&h, angle: 90, at: 10.3)
sample(&h, angle: 90, at: 10.5)
check(h.orientation == .portraitUpsideDown, "Interrupted candidates restart their dwell")
h.sample(x: .nan, y: 0, z: 0, at: 12)
check(h.orientation == .portraitUpsideDown, "Invalid motion is ignored")

check(CallOrientationPolicy.tag(interface: .portrait, physical: .landscapeLeft, disagreeingSince: 10,
    now: 10.599) == .portrait, "A portrait screen keeps the tag for 0.6 seconds of disagreement")
check(CallOrientationPolicy.tag(interface: .portrait, physical: .landscapeLeft, disagreeingSince: 10,
    now: 10.6) == .landscapeLeft, "Exact 0.6 seconds of disagreement: the phone wins under rotation lock")
check(CallOrientationPolicy.tag(interface: .portrait, physical: .landscapeLeft, disagreeingSince: nil,
    now: 99) == .portrait, "No recorded disagreement keeps the screen's orientation")
for interface in [CallOrientation.landscapeLeft, .landscapeRight] {
    for physical in [CallOrientation.portrait, .portraitUpsideDown] {
        check(CallOrientationPolicy.tag(interface: interface, physical: physical, disagreeingSince: 10,
            now: 10.1) == interface, "An allowed screen turn gets its 0.6 second grace")
        check(CallOrientationPolicy.tag(interface: interface, physical: physical, disagreeingSince: 10,
            now: 10.6) == physical, "Portrait and upside-down phones win beneath a stable landscape UI")
    }
}
for interface in orientations {
    for physical in orientations {
        check(CallOrientationPolicy.tag(interface: interface, physical: physical, disagreeingSince: nil,
            now: 20, allowsLandscape: false) == physical, "Portrait-only mask: committed phone wins at once")
    }
}
check(CallOrientationPolicy.tag(interface: .portrait, physical: .portrait, disagreeingSince: nil,
    now: 20.02) == .portrait, "Screen and phone agree: no wait at all")
check(CallOrientationPolicy.tag(interface: .portrait, physical: .portraitUpsideDown, disagreeingSince: 30,
    now: 30.6) == .portraitUpsideDown, "Upside down under a portrait screen after the exact grace period")
let deltas = [[0, 90, 180, -90], [-90, 0, 90, 180], [180, -90, 0, 90], [90, 180, -90, 0]]
for (screenIndex, screen) in orientations.enumerated() {
    for (tagIndex, tag) in orientations.enumerated() {
        let delta = CallOrientationPolicy.selfViewDelta(interface: screen, tag: tag)
        check(delta == deltas[screenIndex][tagIndex], "Self view delta table")
        check(CallOrientationPolicy.swapsAspect(delta: delta) == (screen.landscape != tag.landscape), "Renderer bounds swap only on quarter turns")
    }
}
let portrait = CGSize(width: 393, height: 852), landscape = CGSize(width: 852, height: 393)
check(CallOrientationPolicy.remoteFills(frame: portrait, stage: portrait), "Portrait fills portrait")
check(CallOrientationPolicy.remoteFills(frame: landscape, stage: landscape), "Landscape fills landscape")
check(!CallOrientationPolicy.remoteFills(frame: landscape, stage: portrait), "Landscape fits portrait")
check(!CallOrientationPolicy.remoteFills(frame: portrait, stage: landscape), "Portrait fits landscape")
check(CallOrientationPolicy.remoteFills(frame: .zero, stage: portrait), "No geometry preserves initial fill")
var framing = CallRemoteVideoFraming()
framing.updateRemote(landscape, rotation: 0)
check(!framing.fills(stage: portrait), "Framing starts automatically")
framing.toggle(stage: portrait)
check(framing.fills(stage: portrait) && framing.overrideFill == true, "Double tap toggles auto fit to fill")
framing.updateRemote(CGSize(width: 1280, height: 720), rotation: 0)
check(framing.overrideFill == true, "Resolution change retains user choice")
framing.updateRemote(CGSize(width: 1280, height: 720), rotation: 180)
check(framing.overrideFill == nil && !framing.fills(stage: portrait), "Peer's 180 degree turn resets auto")
framing.toggle(stage: portrait)
framing.updateScreen(.landscapeLeft)
check(framing.overrideFill == nil, "Screen turn resets auto")
framing.toggle(stage: landscape)
check(!framing.fills(stage: landscape), "Double tap toggles auto fill to fit")
framing.updateScreen(.landscapeRight)
check(framing.overrideFill == nil, "Opposite landscape screen direction resets auto")
framing.toggle(stage: landscape)
framing.updateStage(landscape)
check(framing.overrideFill == nil, "Measured stage orientation resets auto too")
let store = CallOrientationStore()
check(store.read() == .portrait, "Safe initial frame tag")
store.write(.landscapeRight)
check(store.read() == .landscapeRight, "Capture queue tag relay")

// Real safe-area-reduced stage proposals, plus full-screen proposals retained
// from round 2. In particular the 6.1-inch free height is shorter than SE's.
let phoneLayouts: [(CGSize, CGFloat, CGFloat)] = [
    (CGSize(width: 667, height: 375), 105, 6),
    (CGSize(width: 734, height: 372), 104.16, 6),
    (CGSize(width: 814, height: 409), 114.52, 12),
    (CGSize(width: 852, height: 393), 110.04, 6),
    (CGSize(width: 932, height: 430), 120.4, 12)
]
for (size, avatar, gap) in phoneLayouts {
    let p = CallLayoutPolicy.landscape(availableWidth: size.width, availableHeight: size.height)
    check(CallLayoutPolicy.isLandscape(availableWidth: size.width, availableHeight: size.height), "Measured landscape")
    check(p == CallLayoutPolicy.landscape(availableWidth: size.width, availableHeight: size.height), "Scalar layout remains Equatable")
    near(p.avatar, avatar, "Landscape avatar")
    near(p.control, 64, "Portrait-sized controls")
    near(p.controlSpacing, gap, "Short-side spacing")
    near(p.identityGap, 24, "Horizontal identity spacing")
    near(p.controlsHeight, 104, "Bottom controls reservation")
    check(p.freeX == 24 && p.freeY == 64 && p.freeWidth == size.width - 48 && p.freeHeight == size.height - 176, "Free area")
    near(p.previewWidth, size.height * 0.30, "Wide short-side preview")
    near(p.previewHeight, p.previewWidth / (4.0 / 3.0), "Landscape preview aspect")
    near(p.previewX, size.width - 12 - p.previewWidth, "Top right default X")
    check(p.previewY == 64, "Top right default Y")
    let identityWidth = p.avatar + p.identityGap + p.identityTextWidth
    let identityRight = (size.width + identityWidth) / 2
    check(identityRight + 12 <= p.previewX + 0.00001, "Identity clears wide self-view, including SE")
    check((size.width - identityWidth) / 2 >= p.freeX, "Identity stays within the leading inset")
    check(p.identityTextWidth >= 250 && p.avatar <= p.freeHeight, "Usable identity dimensions")
    // Native text sizing must still be checked at AX-XL on the simulator.
    check(2 * 40 + 8 + 52 <= p.freeHeight, "Two-line name and path slot fit the identity height")
    let controlsTop = size.height - p.controlsHeight
    check(p.freeY + p.freeHeight + 8 <= controlsTop, "Identity clears controls")
    check(p.previewX >= 12 && p.previewY >= 56, "Tile clears top bar and leading edge")
    check(p.previewX + p.previewWidth <= size.width - 12 + 0.00001, "Tile clears trailing edge")
    check(p.previewY + p.previewHeight + 12 <= controlsTop, "Tile clears controls")
    check(p.control >= 44 && p.control * 4 + p.controlSpacing * 3 < size.width - 48, "Four reachable controls")
    check(CallLayoutPolicy.controlRows(availableWidth: size.width, controlCount: 4) == 1, "One video control row")
    let preview = CallLayoutPolicy.previewSize(availableWidth: size.width, availableHeight: size.height)
    near(preview.width, p.previewWidth, "Outer preview follows interface width")
    near(preview.height, p.previewHeight, "Outer preview follows interface height")
    check(CallLayoutPolicy.failureTopInset(availableWidth: size.width, availableHeight: size.height) == 56, "Failed screen clears top bar")
    check(CallLayoutPolicy.encryptionColumns(availableWidth: size.width, availableHeight: size.height) == 2, "Encryption landscape columns")
}
near(CallLayoutPolicy.landscape(availableWidth: 667, availableHeight: 375).identityTextWidth, 265, "SE identity width")
near(CallLayoutPolicy.landscape(availableWidth: 734, availableHeight: 372).freeHeight, 196, "6.1-inch safe-area free height")
// In every interface/tag pairing the INNER renderer has the camera picture's
// aspect. The OUTER tile keeps the interface's aspect, even under rotation lock.
for screen in orientations {
    let stage = screen.landscape ? landscape : portrait
    let preview = CallLayoutPolicy.previewSize(availableWidth: stage.width, availableHeight: stage.height)
    for tag in orientations {
        let delta = CallOrientationPolicy.selfViewDelta(interface: screen, tag: tag)
        let swap = CallOrientationPolicy.swapsAspect(delta: delta)
        let innerWidth = swap ? preview.height : preview.width
        let innerHeight = swap ? preview.width : preview.height
        near(innerWidth / innerHeight, tag.landscape ? 4.0 / 3.0 : 3.0 / 4.0, "Renderer matches camera picture without cropping")
        check((preview.width > preview.height) == screen.landscape, "Outer tile follows interface only")
    }
}
check(!CallLayoutPolicy.isLandscape(availableWidth: portrait.width, availableHeight: portrait.height), "Portrait selection unchanged")
near(CallLayoutPolicy.avatarSize(availableHeight: 667, isVideo: false), 136, "Portrait avatar regression")
near(CallLayoutPolicy.controlSize(availableWidth: 375, controlCount: 4), 64, "Portrait controls regression")
check(CallLayoutPolicy.encryptionColumns(availableWidth: portrait.width, availableHeight: portrait.height) == 1, "Portrait encryption stays one column")
check(CallLayoutPolicy.failureTopInset(availableWidth: portrait.width, availableHeight: portrait.height) == 0, "Portrait failure inset unchanged")
print("CallOrientationContracts passed")
