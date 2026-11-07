"""Run the production sequential chat-list runner without accounts or network."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
view = (root / "ios/UNYRI/UI/Chats/ChatsListView.swift").read_text(encoding="utf-8")
production = view[view.index("struct ChatListBatchResult {"):]
tests = r'''
enum FixtureFailure: Error { case diskFull }
@main struct Run {
    @MainActor static var count = 0
    @MainActor static func check(_ value: Bool, _ message: String) {
        precondition(value, message); count += 1
    }
    @MainActor static func main() async {
        var calls: [String] = []
        let empty = await ChatListBatchRunner.run(ids: [], isCurrent: { true }) { calls.append($0) }
        check(empty.completed.isEmpty && empty.failed.isEmpty && calls.isEmpty, "Empty selection is inert")

        var inFlight = false
        let success = await ChatListBatchRunner.run(ids: ["a", "b", "a", "c"], isCurrent: { true }) { id in
            check(!inFlight, "Operations must never run concurrently")
            inFlight = true; calls.append(id)
            await Task.yield(); inFlight = false
        }
        check(calls == ["a", "b", "c"], "Each selected chat runs exactly once, in order")
        check(success.completed == ["a", "b", "c"] && success.failed.isEmpty, "Durable successes release the selection")

        calls = []
        let partial = await ChatListBatchRunner.run(ids: ["a", "b", "c"], isCurrent: { true }) { id in
            calls.append(id)
            if id == "b" { throw FixtureFailure.diskFull }
        }
        check(calls == ["a", "b", "c"], "One failed chat must not prevent other selected chats")
        check(partial.completed == ["a", "c"] && partial.failed == ["b"], "Only failed chat remains selected")

        calls = []
        let individualCancellation = await ChatListBatchRunner.run(ids: ["a", "b"], isCurrent: { true }) { id in
            calls.append(id)
            if id == "a" { throw CancellationError() }
        }
        check(calls == ["a", "b"] && individualCancellation.failed == ["a"], "A missing/deleted item does not cancel a still-valid account batch")

        calls = []
        let stale = await ChatListBatchRunner.run(ids: ["a", "b"], isCurrent: { false }) { calls.append($0) }
        check(calls.isEmpty && stale.failed == ["a", "b"], "A stale account cannot start an operation")

        calls = []
        var current = true
        let switched = await ChatListBatchRunner.run(ids: ["a", "b", "c"], isCurrent: { current }) { id in
            calls.append(id); current = false
        }
        check(calls == ["a"] && switched.completed.isEmpty, "Account change during await suppresses result and subsequent operations")
        check(switched.failed == ["a", "b", "c"], "No old-account completion is published")

        let cancelled = await Task { @MainActor in
            await ChatListBatchRunner.run(ids: ["a", "b"], isCurrent: { true }) { _ in
                withUnsafeCurrentTask { $0?.cancel() }
            }
        }.value
        check(cancelled.completed.isEmpty && cancelled.failed == ["a", "b"], "Cancelled task stops before a second operation")
        var requests = ChatListDeleteRequestIDs()
        var counter = 0
        func nextID() -> String { counter += 1; return "request-" + String(counter) }
        let mine = requests.requests(for: ["a", "b"], forEveryone: false, makeID: nextID)
        let both = requests.requests(for: ["a", "b"], forEveryone: true, makeID: nextID)
        check(mine["a"] != mine["b"], "Each chat has an independent bounded-clear request")
        check(mine["a"] != both["a"] && mine["b"] != both["b"], "Changing scope never reuses a request ID")
        let retry = requests.requests(for: ["b", "c"], forEveryone: false, makeID: nextID)
        check(retry["b"] == mine["b"] && retry["c"] != nil && retry["a"] == nil, "Retry retains failed IDs, allocates only new targets and excludes deselected targets")
        requests.complete(["a"])
        let afterPartial = requests.requests(for: ["b"], forEveryone: false, makeID: nextID)
        let bothAfterPartial = requests.requests(for: ["b"], forEveryone: true, makeID: nextID)
        check(afterPartial["b"] == mine["b"] && bothAfterPartial["b"] == both["b"], "Successful IDs are removed without losing either scope of failed targets")
        let newIntent = requests.requests(for: ["a"], forEveryone: false, makeID: nextID)
        check(newIntent["a"] != mine["a"], "A later deletion of a successfully cleared chat gets a new bound")
        requests.reset()
        let newSession = requests.requests(for: ["b"], forEveryone: false, makeID: nextID)
        check(newSession["b"] != mine["b"], "Explicit selection cancel or owner change resets retained IDs")
        print("PASS: \(count) production chat-list batch contracts; no live writes")
    }
}
'''
with tempfile.TemporaryDirectory(prefix="coco-chat-list-contracts-") as directory:
    folder = Path(directory)
    source = folder / "Runner.swift"
    executable = folder / "runner.exe"
    source.write_text("import Foundation\n" + production + "\n" + tests, encoding="utf-8")
    subprocess.run(["swiftc", "-parse-as-library", str(source), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
