"""Run the pure chat-list and receipt motion policies without UI, accounts or network."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
view = (root / "ios/UNYRI/UI/Chats/ChatsListView.swift").read_text(encoding="utf-8").replace("\r\n", "\n")
policies = view[view.index("// MARK: - Chat list motion policies"):view.index("// MARK: - End chat list motion policies")]

# Swipe tray glass (source checks). Inside a GlassEffectContainer only the content of the
# view `.glassEffect` is applied to stays above the shared glass; a glass on an empty
# shape behind the icon covers it (the call top bar on device, 12 Nov).
tray = view[view.index("// MARK: - Swipe actions"):view.index("private final class ChatSwipeSampler")]
source_checks = 0
def source_check(value, message):
    global source_checks
    assert value, message
    source_checks += 1
glasses = [m.start() for m in re.finditer(r"\.glassEffect\(", tray)]
source_check(glasses, "The swipe tray has Liquid Glass")
source_check("GlassEffectContainer(" in tray, "The tray keeps one glass pass, so nearby capsules merge like liquid")
for at in glasses:
    receiver = re.search(r"([A-Za-z_][A-Za-z0-9_]*|\))\s*$", tray[:at])
    source_check(receiver and receiver.group(1) == "content",
                 "Every tray glass is applied to a modifier's content, never to a shape: " + tray[max(0, at - 60):at + 14].strip())
source_check(not re.search(r"(Color\.clear|\.fill\(\s*\.clear\s*\))\s*(\.\w+\([^()]*\)\s*)*\.(glassEffect|modifier\(ChatSwipeCapsuleGlass)", tray),
             "No tray glass sits on a clear fill")
source_check(re.search(r"struct ChatSwipeCapsuleGlass: ViewModifier", tray), "The capsule glass is a modifier on the slot, not a sibling view")
uses = [m.start() for m in re.finditer(r"\.modifier\(ChatSwipeCapsuleGlass\(", tray)]
source_check(uses, "The slot applies the capsule glass")
for at in uses:
    framed = tray[tray.rindex("ZStack {", 0, at):at]
    source_check("Image(systemName: symbol)" in framed and ".frame(width: width, height: diameter)" in framed,
                 "The capsule glass is applied to the icon's own frame")
    source_check(not re.search(r"Color\.clear|\.fill\(|Capsule\(\)|Circle\(\)", framed), "No empty shape shares the icon's frame under the glass")
    source_check(".scaleEffect(max(0.35, progress)" in framed, "The pulse scale stays on the icon, so the capsule never shrinks with it")
print(f"PASS: {source_checks} swipe tray glass source checks", flush=True)
chat = (root / "ios/UNYRI/UI/Chats/ChatScreen.swift").read_text(encoding="utf-8").replace("\r\n", "\n")
ink = chat[chat.index("enum UnyriReceiptInkPolicy {"):chat.index("private enum UnyriReceiptInk {")]
stubs = r'''
enum MessageStatus: String {
    case pending, sent, failed, delivered, read
    case decryptFailed = "decrypt_failed", identityChanged = "identity_changed"
}
enum UnyriReceiptInk { static let mountGate: TimeInterval = 0.8 }
'''
tests = r'''
@main struct Run {
    static var count = 0
    static func check(_ value: Bool, _ message: String) {
        precondition(value, message); count += 1
    }
    static func row(_ id: String, _ time: Double, unread: Int = 0, muted: Bool = false) -> ChatListLiftPolicy.Row {
        ChatListLiftPolicy.Row(id: id, activity: Date(timeIntervalSince1970: time), unread: unread, muted: muted)
    }
    static func near(_ a: CGFloat, _ b: CGFloat) -> Bool { abs(a - b) < 0.001 }
    static func main() {
        typealias D = ChatListLiftPolicy.Decision
        let yes: (String) -> Bool = { _ in true }

        // Open style
        check(ChatOpenTransitionPolicy.style(viaRow: false, iOS18: true, reduceMotion: false, lowPower: false) == .instant, "Routes, search and Saved keep the instant push")
        check(ChatOpenTransitionPolicy.style(viaRow: true, iOS18: true, reduceMotion: false, lowPower: false, zoom: true) == .zoom, "With zoom on, a row tap zooms on iOS 18+")
        check(ChatOpenTransitionPolicy.style(viaRow: true, iOS18: true, reduceMotion: false, lowPower: false, zoom: false) == .settle, "With the kill switch off, a row tap settles on iOS 18+")
        check(!ChatOpenTransitionPolicy.zoomEnabled, "Zoom stays off until device QA covers its system dismissal gestures")
        check(ChatOpenTransitionPolicy.style(viaRow: true, iOS18: false, reduceMotion: false, lowPower: false, zoom: true) == .settle, "A row tap settles before iOS 18")
        check(ChatOpenTransitionPolicy.style(viaRow: true, iOS18: true, reduceMotion: true, lowPower: false) == .fade, "Reduce Motion fades")
        check(ChatOpenTransitionPolicy.style(viaRow: true, iOS18: true, reduceMotion: false, lowPower: true) == .fade, "Low Power fades")

        // New message in the list
        let old = [row("a", 30), row("b", 20), row("c", 10)]
        check(ChatListLiftPolicy.decide(old: old, new: [row("c", 40, unread: 1), row("a", 30), row("b", 20)], live: true, sweepAllowed: yes)
              == D(animateOrder: true, lifted: "c", swept: "c"), "One incoming message moves, lifts and sweeps its row")
        check(ChatListLiftPolicy.decide(old: old, new: [row("c", 40, unread: 1), row("a", 30), row("b", 20)], live: false, sweepAllowed: yes)
              == D(), "Nothing plays when the list is not live")
        check(ChatListLiftPolicy.decide(old: [], new: old, live: true, sweepAllowed: yes) == D(), "A cold list never animates")
        check(ChatListLiftPolicy.decide(old: old, new: [row("a", 40, unread: 1), row("b", 20), row("c", 10)], live: true, sweepAllowed: yes)
              == D(animateOrder: false, lifted: nil, swept: "a"), "A new message in the top row sweeps without a move")
        check(ChatListLiftPolicy.decide(old: old, new: [row("c", 40, unread: 1, muted: true), row("a", 30), row("b", 20)], live: true, sweepAllowed: yes)
              == D(animateOrder: true, lifted: "c", swept: nil), "A muted chat moves but never sweeps")
        check(ChatListLiftPolicy.decide(old: old, new: [row("c", 40), row("a", 30), row("b", 20)], live: true, sweepAllowed: yes)
              == D(animateOrder: true, lifted: "c", swept: nil), "An own message lifts without a sweep")
        check(ChatListLiftPolicy.decide(old: old, new: [row("c", 40, unread: 1), row("a", 30), row("b", 20)], live: true, sweepAllowed: { _ in false })
              == D(animateOrder: true, lifted: "c", swept: nil), "The sweep cooldown is honoured")
        check(ChatListLiftPolicy.decide(old: old, new: [row("c", 50, unread: 1), row("b", 40, unread: 1), row("a", 30)], live: true, sweepAllowed: yes)
              == D(animateOrder: true, lifted: "c", swept: nil), "A bumped row that keeps its place does not count as moved; two fresh rows never sweep")
        let four = old + [row("d", 5)]
        check(ChatListLiftPolicy.decide(old: four, new: [row("d", 50, unread: 1), row("c", 40, unread: 1), row("a", 30), row("b", 20)], live: true, sweepAllowed: yes)
              == D(animateOrder: true, lifted: nil, swept: nil), "Two moved rows animate the order without a lift or sweep")
        check(ChatListLiftPolicy.decide(old: old, new: [row("n", 50, unread: 1), row("a", 30), row("b", 20), row("c", 10)], live: true, sweepAllowed: yes)
              == D(animateOrder: true, lifted: "n", swept: "n"), "A new chat at the top counts as one live event")
        let eight = (0..<8).map { row("r\($0)", Double(100 - $0)) }
        let fourMoved = [row("r7", 200), row("r6", 199), row("r5", 198), row("r4", 197)] + eight.prefix(4)
        check(ChatListLiftPolicy.decide(old: eight, new: Array(fourMoved), live: true, sweepAllowed: yes) == D(), "Four moved rows are a catch-up")
        let fourNew = [row("n1", 300), row("n2", 299), row("n3", 298), row("n4", 297)] + old
        check(ChatListLiftPolicy.decide(old: old, new: fourNew, live: true, sweepAllowed: yes) == D(), "More than three new rows are a catch-up")

        // Swipe geometry
        check(ChatSwipePolicy.openWidth(count: 3) == 212 && ChatSwipePolicy.openWidth(count: 2) == 144, "Open width is count x 68 + 8")
        check(ChatSwipePolicy.displayed(raw: -10, open: 212, armed: false) == 0, "A drag past closed shows nothing")
        check(ChatSwipePolicy.displayed(raw: 100, open: 212, armed: false) == 100, "Up to the open width the tray follows the finger")
        check(near(ChatSwipePolicy.displayed(raw: 312, open: 212, armed: false), 267), "Past the open width the drag is elastic")
        check(near(ChatSwipePolicy.displayed(raw: 312, open: 212, armed: true), 281), "Arming adds the magnetic pull")
        check(!ChatSwipePolicy.armed(raw: 299, open: 212, wasArmed: false) && ChatSwipePolicy.armed(raw: 300, open: 212, wasArmed: false), "Arms at open + 88")
        check(ChatSwipePolicy.armed(raw: 276, open: 212, wasArmed: true) && !ChatSwipePolicy.armed(raw: 275, open: 212, wasArmed: true), "Disarms below open + 64")
        check(ChatSwipePolicy.release(raw: 300, velocity: -1000, open: 212, armed: true) == .commit, "Releasing armed commits")
        check(ChatSwipePolicy.release(raw: 20, velocity: 400, open: 212, armed: false) == .open, "A flick opens")
        check(ChatSwipePolicy.release(raw: 10, velocity: 400, open: 212, armed: false) == .close, "A flick needs some travel")
        check(ChatSwipePolicy.release(raw: 200, velocity: -400, open: 212, armed: false) == .close, "A flick back closes")
        check(ChatSwipePolicy.release(raw: 106, velocity: 0, open: 212, armed: false) == .open
              && ChatSwipePolicy.release(raw: 105, velocity: 0, open: 212, armed: false) == .close, "Half the open width decides a slow release")

        // Receipt ink
        check(UnyriReceiptInkPolicy.animates(from: .pending, to: .sent, mountedFor: 0, appActive: true, calm: false), "A pending message is always live")
        check(!UnyriReceiptInkPolicy.animates(from: .sent, to: .delivered, mountedFor: 0.5, appActive: true, calm: false)
              && UnyriReceiptInkPolicy.animates(from: .sent, to: .delivered, mountedFor: 0.8, appActive: true, calm: false), "Others need 0.8 s on screen")
        check(UnyriReceiptInkPolicy.animates(from: .sent, to: .delivered, mountedFor: 0.3, appActive: true, calm: false, watchedSending: true)
              && !UnyriReceiptInkPolicy.animates(from: .sent, to: .delivered, mountedFor: 0.3, appActive: true, calm: true, watchedSending: true),
              "A send watched from the clock stays live through a quick delivered, except in calm mode")
        check(!UnyriReceiptInkPolicy.animates(from: .read, to: .delivered, mountedFor: 5, appActive: true, calm: false), "Only forward changes animate")
        check(!UnyriReceiptInkPolicy.animates(from: .pending, to: .read, mountedFor: 5, appActive: false, calm: false), "Never in the background")
        check(!UnyriReceiptInkPolicy.animates(from: .pending, to: .read, mountedFor: 5, appActive: true, calm: true), "Never in calm mode")
        check(!UnyriReceiptInkPolicy.animates(from: .pending, to: .failed, mountedFor: 5, appActive: true, calm: false), "A failure is not ink")
        check(UnyriReceiptInkPolicy.drawsSeparately(.pending) && UnyriReceiptInkPolicy.drawsSeparately(.read)
              && !UnyriReceiptInkPolicy.drawsSeparately(.failed), "Only ink statuses are drawn separately")
        check(UnyriReceiptInkPolicy.batchAnimates(count: 1) && UnyriReceiptInkPolicy.batchAnimates(count: 3), "Up to three receipt changes in one batch ink")
        check(!UnyriReceiptInkPolicy.batchAnimates(count: 4), "A burst of receipt changes is a catch-up and lands still")
        print("PASS: \(count) chat-list and receipt motion contracts")
    }
}
'''
with tempfile.TemporaryDirectory(prefix="coco-chat-motion-contracts-") as directory:
    folder = Path(directory)
    source = folder / "Runner.swift"
    executable = folder / "runner.exe"
    source.write_text("import Foundation\n" + policies + "\n" + stubs + "\n" + ink + "\n" + tests, encoding="utf-8")
    subprocess.run(["swiftc", "-parse-as-library", str(source), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
