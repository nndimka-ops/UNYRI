import Combine
import Foundation

@MainActor
protocol BlockedAccountsService: AnyObject {
    var blockedAccountScope: String? { get }
    var blockedAccountOwnerID: String { get }
    func blockedAccounts(query: String, cursor: String?) async throws -> BlockedAccountsPage
    func directory(query: String, limit: Int, offset: Int64) async throws -> SearchResponse
    func setAccountBlocked(userID: String, blocked: Bool) async throws
}

extension UsersRepository: BlockedAccountsService {}

/// Screen-owned, memory-only state. No history/chat request is part of this flow.
@MainActor
final class BlockedAccountsModel: ObservableObject {
    enum Mode { case blocked, people }
    let mode: Mode
    @Published var query = ""
    @Published private(set) var users: [PublicUser] = []
    @Published private(set) var loading = false
    @Published private(set) var hasMore = false
    @Published private(set) var loadFailed = false
    @Published private(set) var mutationFailed = false
    @Published private(set) var changing: Set<String> = []
    private let service: BlockedAccountsService
    private let scope: String?
    private var request = UUID()
    private var loadedQuery: String?
    private var cursor: String?
    private var offset: Int64 = 0
    private var excludedIDs: Set<String>

    init(service: BlockedAccountsService, mode: Mode = .blocked, excludedIDs: Set<String> = []) {
        self.service = service
        self.mode = mode
        self.scope = service.blockedAccountScope
        self.excludedIDs = excludedIDs
    }

    var identityIsCurrent: Bool { scope != nil && service.blockedAccountScope == scope }

    func load(reset: Bool = true, debounce: Bool = false) async {
        guard identityIsCurrent else { clear(); return }
        let search = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard reset || (!loading && hasMore && loadedQuery == search) else { return }
        let ticket = UUID()
        request = ticket
        if reset && loadedQuery != search { users = []; hasMore = false }
        loading = true
        loadFailed = false
        defer { if request == ticket { loading = false } }
        do {
            if debounce { try await Task.sleep(nanoseconds: 250_000_000) }
            try Task.checkCancellation()
            guard identityIsCurrent else { clear(); return }
            guard request == ticket else { return }
            let pageUsers: [PublicUser]
            let nextCursor: String?
            let nextOffset: Int64?
            switch mode {
            case .blocked:
                let page = try await service.blockedAccounts(query: search, cursor: reset ? nil : cursor)
                pageUsers = page.items.map(\.user)
                nextCursor = page.nextCursor
                nextOffset = nil
                // A broken/unsupported cursor must not create an endless load loop.
                if let nextCursor, UUID(uuidString: nextCursor) == nil || (!reset && nextCursor == cursor) {
                    throw BlockedAccountsLoadError.invalidPage
                }
            case .people:
                let page = try await service.directory(query: search, limit: 30, offset: reset ? 0 : offset)
                pageUsers = page.items
                nextCursor = nil
                nextOffset = page.nextOffset
                if let nextOffset, nextOffset <= (reset ? 0 : offset) { throw BlockedAccountsLoadError.invalidPage }
            }
            try Task.checkCancellation()
            guard identityIsCurrent else { clear(); return }
            guard request == ticket,
                  query.trimmingCharacters(in: .whitespacesAndNewlines) == search else { return }
            var ids = Set<String>()
            users = ((reset ? [] : users) + pageUsers).filter {
                $0.id != service.blockedAccountOwnerID && !excludedIDs.contains($0.id) && ids.insert($0.id).inserted
            }
            loadedQuery = search
            cursor = nextCursor
            offset = nextOffset ?? 0
            hasMore = nextCursor != nil || nextOffset != nil
        } catch is CancellationError {
            if !identityIsCurrent { clear() }
        } catch {
            guard identityIsCurrent, request == ticket else { return }
            loadFailed = true
        }
    }

    @discardableResult
    func change(_ user: PublicUser) async -> Bool {
        guard identityIsCurrent, !changing.contains(user.id), user.id != service.blockedAccountOwnerID,
              users.contains(where: { $0.id == user.id }) else { return false }
        changing.insert(user.id)
        mutationFailed = false
        // A page started before this mutation cannot reinsert the removed row.
        request = UUID()
        loading = false
        defer { changing.remove(user.id) }
        do {
            try await service.setAccountBlocked(userID: user.id, blocked: mode == .people)
            guard identityIsCurrent else { clear(); return false }
            // Foreground/revision refresh can start while this write awaits.
            // Invalidate that page too, before it can restore a stale row.
            request = UUID()
            loading = false
            users.removeAll { $0.id == user.id }
            if mode == .people { excludedIDs.insert(user.id) }
            return true
        } catch {
            guard identityIsCurrent else { clear(); return false }
            mutationFailed = true
            return false
        }
    }

    private func clear() {
        request = UUID()
        users = []
        excludedIDs = []
        cursor = nil
        offset = 0
        hasMore = false
        loading = false
        loadFailed = false
        mutationFailed = false
        changing = []
    }
}

private enum BlockedAccountsLoadError: Error { case invalidPage }
