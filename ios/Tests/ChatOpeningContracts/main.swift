var gate = ChatOpeningGate()
let first = gate.begin()!
precondition(gate.begin() == nil, "Duplicate lookup must be rejected")
precondition(gate.finish(first), "Fetched chat must release the navigation guard")
precondition(!gate.isPending, "Successful lookup must allow opening the chat")
precondition(!gate.finish(first), "A result must only be consumed once")

let cancelled = gate.begin()!
gate.cancel()
let replacement = gate.begin()!
precondition(!gate.finish(cancelled), "Late result must not complete a newer lookup")
precondition(gate.isPending, "Late result must not clear the current guard")
precondition(gate.finish(replacement))

let failed = gate.begin()!
precondition(gate.finish(failed))
precondition(gate.begin() != nil, "Failed lookup must allow retry")
gate.cancel()
precondition(!gate.isPending, "Leaving the screen must immediately release the guard")
print("Chat opening contracts passed")
