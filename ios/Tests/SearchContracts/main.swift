import Foundation

@main struct SearchContracts {
    static func main() async throws {
        precondition(SearchPresentation.query("  @Alice \n") == "Alice")
        precondition(SearchPresentation.query(String(repeating: "x", count: 300)).count == 256)
        precondition(!SearchPresentation.canSearchDirectory("ab", connected: true))
        precondition(!SearchPresentation.canSearchDirectory("abc", connected: false))
        precondition(SearchPresentation.canSearchDirectory(" @abc ", connected: true))
        precondition(SearchPresentation.sections(localCount: 5, messageCount: 10, query: " ").isEmpty,
                     "Empty input must not section or search cached history")
        precondition(SearchPresentation.sections(localCount: 1, messageCount: 2, query: "hi") == [.people, .messages])
        precondition(SearchPresentation.sections(localCount: 1, messageCount: 2, query: "hello") == [.people, .global, .messages])
        precondition(SearchPresentation.sections(localCount: 0, messageCount: 0, query: "abc") == [.global],
                     "Directory notice/loading/offline belongs below the same field from three characters")

        let candidates = [
            SearchCandidate(id: "substring", title: "Hello Alice", username: "other"),
            SearchCandidate(id: "prefix", title: "Alice Cooper", username: "ac"),
            SearchCandidate(id: "exact-b", title: "ALICE", username: "b"),
            SearchCandidate(id: "exact-a", title: "Other", username: "alice"),
            SearchCandidate(id: "accent", title: "Álice", username: "a"),
            SearchCandidate(id: "absent", title: "Bob", username: "bob")
        ]
        let expected = ["accent", "exact-a", "exact-b", "prefix", "substring"]
        precondition(SearchPresentation.ranked(candidates, query: "@alice") == expected)
        precondition(SearchPresentation.ranked(Array(candidates.reversed()), query: "alice") == expected,
                     "Repository order and response arrival must not reorder ranking ties")
        precondition(SearchPresentation.ranked(candidates, query: " ").isEmpty)
        let photo = SearchPresentation.categories(mediaType: "image", mimeType: "image/jpeg", text: "https://example.invalid")
        let video = SearchPresentation.categories(mediaType: "file", mimeType: "video/mp4", text: "")
        let voice = SearchPresentation.categories(mediaType: "voice", mimeType: "audio/ogg", text: "")
        let file = SearchPresentation.categories(mediaType: "file", mimeType: "application/pdf", text: "")
        precondition(photo == [.media, .links] && video == [.media] && voice == [.voice] && file == [.files])
        precondition(SearchPresentation.tabs(categories: [], hasPeople: false).isEmpty)
        precondition(SearchPresentation.tabs(categories: [[]], hasPeople: false) == [.chats])
        precondition(SearchPresentation.tabs(categories: [photo, file, voice], hasPeople: true) == [.chats, .media, .links, .files, .voice])
        precondition(SearchPresentation.tabs(categories: [voice], hasPeople: false) == [.chats, .voice],
                     "No empty media/links/files tabs")
        let long = String(repeating: "👨‍👩‍👧‍👦 ", count: 100) + "needle" + String(repeating: "x", count: 200)
        precondition(SearchPresentation.snippet(long, query: "needle").contains("needle"))

        // Exercise the same cancellable debounce used by the view. Rapid edits
        // cancel before any local read or directory request may begin.
        precondition(SearchPresentation.debounceNanoseconds == 300_000_000)
        var edits: [Task<Bool, Never>] = []
        for _ in 0..<12 {
            edits.last?.cancel()
            edits.append(Task {
                do { try await SearchPresentation.debounce(); return true }
                catch { return false }
            })
        }
        var accepted = 0
        for edit in edits { if await edit.value { accepted += 1 } }
        precondition(accepted == 1, "One settled query, no heavy work per keystroke")
        let abandoned = Task {
            do { try await SearchPresentation.debounce(); return true }
            catch { return false }
        }
        abandoned.cancel()
        let abandonedPublished = await abandoned.value
        precondition(!abandonedPublished, "Closing search cancels work before it starts")
        print("Search sectioning, ranking, categories, snippet and debounce contracts passed")
    }
}
