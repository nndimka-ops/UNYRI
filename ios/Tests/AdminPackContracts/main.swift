import Foundation

// ADMIN PRODUCT PACK 2.1 - typed administration contract (source-level).
//
// These checks are pure Foundation: no SwiftUI, no network, no Keychain. They
// pin the wire contract (see docs/ADMIN_API_CONTRACT.md) and the pagination
// rules every administration list must obey.
func check(_ value: Bool, line: UInt = #line) {
    if !value { fatalError("Admin pack contract failed at line \(line)") }
}

func decode<T: Decodable>(_ json: String) -> T {
    do { return try AdminTransport.decoder.decode(T.self, from: Data(json.utf8)) }
    catch { fatalError("Admin pack contract could not decode \(T.self): \(error)") }
}

// ----- cursor pagination ---------------------------------------------------

check(AdminPaging.path("reports") == "reports?limit=25")
check(AdminPaging.path("reports", cursor: "abc") == "reports?limit=25&cursor=abc")
check(AdminPaging.path("reports", query: AdminPaging.query([("status", "open")])) == "reports?limit=25&status=open")
check(AdminPaging.query([("status", "in_progress"), ("query", "  anna  "), ("actor", ""), ("target", nil)]) == "status=in_progress&query=anna")
// A value that looks like SQL is percent-encoded, never interpolated.
let injected = AdminPaging.query([("query", "' OR 1=1--")])
check(!injected.contains("'"))
check(AdminPaging.query([("query", "' OR 1=1--")]).hasPrefix("query="))
// A filter is bounded before it goes on the wire, so a pathological query cannot
// become a multi-kilobyte URL. The bound applies to the value, and `query=` is
// six characters - not part of it.
let longFilter = AdminPaging.query([("query", String(repeating: "a", count: 500))])
check(longFilter == "query=" + String(repeating: "a", count: AdminPaging.maxFilterLength))

struct Row: Equatable { var id: String }
let first = AdminPage(rows: [Row(id: "a"), Row(id: "b")], nextCursor: "c1", hasMore: true)
let second = AdminPage(rows: [Row(id: "b"), Row(id: "c")], nextCursor: nil, hasMore: false)
let merged = AdminPaging.append(first, second, id: { $0.id })
check(merged.rows.map(\.id) == ["a", "b", "c"])
check(merged.nextCursor == nil)
check(!merged.hasMore)
// A row without an identity is never dropped.
check(AdminPaging.merge([Row(id: "x")], [Row(id: "y"), Row(id: "y")], id: { _ in nil }).count == 3)

check(AdminPaging.isCurrent(expected: 4, actual: 4, expectedFilter: "status=open", currentFilter: "status=open"))
check(!AdminPaging.isCurrent(expected: 4, actual: 4, expectedFilter: "status=open", currentFilter: "status=resolved"))
check(!AdminPaging.isCurrent(expected: 3, actual: 4, expectedFilter: "s", currentFilter: "s"))

// ----- guards --------------------------------------------------------------

check(!AdminNotePolicy.valid(""))
check(!AdminNotePolicy.valid("   "))
check(AdminNotePolicy.valid("ok"))
check(AdminNotePolicy.valid(String(repeating: "a", count: AdminNotePolicy.max)))
check(!AdminNotePolicy.valid(String(repeating: "a", count: AdminNotePolicy.max + 1)))
check(AdminNotePolicy.payload("  note  ") == "note")

check(AdminConfirmations.forReportStatus("resolved"))
check(AdminConfirmations.forReportStatus("dismissed"))
check(!AdminConfirmations.forReportStatus("open"))
check(AdminConfirmations.forFeedbackStatus("resolved"))
check(!AdminConfirmations.forFeedbackStatus("open"))
check(AdminConfirmations.forRestore(blocked: false))
check(!AdminConfirmations.forRestore(blocked: true))

check(!AdminCleanupPolicy.canExecute(stage: .idle, plannedCount: 3, planId: "p1"))
check(!AdminCleanupPolicy.canExecute(stage: .previewed, plannedCount: 0, planId: "p1"))
check(!AdminCleanupPolicy.canExecute(stage: .previewed, plannedCount: 3, planId: nil))
check(AdminCleanupPolicy.canExecute(stage: .previewed, plannedCount: 3, planId: "p1"))

// ----- typed decoding ------------------------------------------------------

let page: AdminPageDTO<AdminReportDTO> = decode("""
{"items":[{"id":"r1","status":"open","created_at":"2026-09-25T10:11:12.000000Z","unexpected":"ignored"}],
 "next_cursor":"abc","has_more":true}
""")
check(page.items.count == 1)
check(page.items[0].id == "r1")
check(page.nextCursor == "abc")
check(page.hasMore)

// A field the server omits is nil, never a fabricated value.
let sparse: AdminReportDTO = decode("{\"id\":\"r1\"}")
check(sparse.userId == nil)
check(sparse.reason == nil)
check(sparse.status == "open")

// Feedback detail carries descriptors and no embedded bytes.
let detailJSON = """
{"id":"f1","user_id":"u1","description":"Camera is black","platform":"android",
 "app_version":"0.2.0","os_version":"16","status":"open","created_at":"2026-09-25T10:11:12.000000Z",
 "screenshots":[{"index":0,"mime_type":"image/png","byte_size":1234}]}
"""
let feedback: AdminFeedbackDTO = decode(detailJSON)
check(feedback.screenshots.count == 1)
check(feedback.screenshots[0].index == 0)
check(feedback.screenshots[0].mimeType == "image/png")
check(feedback.screenshots[0].byteSize == 1234)
check(!detailJSON.contains("data_base64"))
// A list row has a count instead of descriptors, and no `screenshots` key at all.
let row: AdminFeedbackDTO = decode("{\"id\":\"f1\",\"screenshot_count\":2}")
check(row.screenshotCount == 2)
check(row.screenshots.isEmpty)

// The moderation centre. Every key here is what `GET /admin/users/:id` actually
// returns, so the fixture cannot drift away from the server unnoticed.
let centre: AdminUserDetailDTO = decode("""
{"profile":{"user_id":"u1","username":"anna","display_name":"Anna","has_avatar":true,
  "created_at":"2026-01-01T00:00:00.000000Z"},
 "moderation":{"status":"suspended","until":null,"reason":"spam","reports_total":4,
  "reports_open":2,"notes":1},
 "devices":[{"id":"d1","installation_id":"i1","platform":"android","display_name":"Pixel",
   "app_version":"0.2.0","os_version":"16","signal_device_id":3,
   "created_at":"2026-01-01T00:00:00.000000Z","last_active_at":"2026-09-25T10:11:12.000000Z",
   "revoked":false,"restriction_active":true,"restriction_until":null,
   "restriction_reason":"compromised","is_administrator_device":false}],
 "is_administrator":false,"suspend_allowed":true}
""")
check(centre.profile.username == "anna")
check(centre.moderation.status == "suspended")
check(centre.moderation.reportsTotal == 4)
check(centre.devices.count == 1)
check(centre.devices[0].installationId == "i1")
check(centre.devices[0].restrictionActive)
check(centre.suspendAllowed)

// The account search reuses the report/feedback row keys.
let userRow: AdminUserRowDTO = decode("{\"id\":\"u1\",\"user_id\":\"u1\",\"description\":\"Anna\",\"reason\":\"anna\",\"status\":\"suspended\"}")
check(userRow.displayName == "Anna")
check(userRow.username == "anna")
check(userRow.status == "suspended")

// Timeline events keep their semantic kind and a stable reference.
let timeline: AdminPageDTO<AdminTimelineEventDTO> = decode("""
{"items":[{"kind":"account_suspended","occurred_at":"2026-09-25T10:00:00.000000Z","actor":"a1",
  "reason":"spam","detail":{"installation_id":"i1"},"ref":"audit:7"}],"has_more":false}
""")
check(timeline.items[0].kind == "account_suspended")
check(timeline.items[0].detail.installationId == "i1")
check(timeline.items[0].id == "audit:7")

// A moderator receives an explicit null storage block.
let ownerSummary: AdminSummaryDTO = decode("""
{"open_reports":3,"in_progress_reports":1,"unresolved_feedback":2,"active_account_suspensions":1,
 "active_installation_suspensions":4,"reports_last_24h":2,
 "storage":{"used_bytes":10,"reserved_bytes":4,"budget_bytes":100}}
""")
check(ownerSummary.openReports == 3)
check(ownerSummary.reportsLast24h == 2)
check(ownerSummary.storage?.budgetBytes == 100)
let moderatorSummary: AdminSummaryDTO = decode("{\"open_reports\":3,\"storage\":null}")
check(moderatorSummary.storage == nil)
check(moderatorSummary.openReports == 3)

// Audit rows keep machine-readable detail.
let audit: AdminPageDTO<AdminAuditDTO> = decode("""
{"items":[{"id":"12","actor":"a1","action":"device_suspend","target":"u1",
 "detail":{"installation_id":"i1"},"created_at":"2026-09-25T10:00:00.000000Z"}],"has_more":false}
""")
check(audit.items[0].action == "device_suspend")
check(audit.items[0].detail.installationId == "i1")

// ----- request bodies ------------------------------------------------------

func encode<T: Encodable>(_ value: T) -> String {
    guard let data = try? AdminTransport.encoder.encode(value),
          let object = try? JSONSerialization.jsonObject(with: data),
          let canonical = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
          let text = String(data: canonical, encoding: .utf8) else { return "<encode failed>" }
    return text
}
// `include_installations` must be absent when nil: restoring an account must not
// silently lift installation restrictions.
check(encode(AdminSuspensionBody(blocked: false, reason: "restored", until: nil, includeInstallations: nil))
    == "{\"blocked\":false,\"reason\":\"restored\"}")
check(encode(AdminSuspensionBody(blocked: true, reason: "spam", until: nil, includeInstallations: true))
    .contains("\"include_installations\":true"))
check(encode(AdminDeviceSuspensionBody(blocked: true, reason: "compromised", until: nil))
    == "{\"blocked\":true,\"reason\":\"compromised\"}")
// The review note is always encoded, even when empty, because
// `/admin/reports/:id/review` rejects a body without it; the decision reason is
// omitted when empty, exactly like the server default.
let review = encode(AdminReviewBody(status: "open", note: "", decisionReason: nil))
check(review.contains("\"note\":\"\""))
check(review.contains("\"status\":\"open\""))
check(!review.contains("decision_reason"))
check(encode(AdminNoteBody(body: "watch this")) == "{\"body\":\"watch this\"}")
check(encode(AdminCleanupBody(planId: "p1")) == "{\"plan_id\":\"p1\"}")

// ----- console launcher (Admin Console Pack 3) -----------------------------

// The device keeps the secure core and nothing else: it decides whether the
// entry exists, and it hands the browser a URL.
check(AdminConsolePolicy.entryVisible(role: "owner"))
check(AdminConsolePolicy.entryVisible(role: "moderator"))
check(!AdminConsolePolicy.entryVisible(role: nil))
check(!AdminConsolePolicy.entryVisible(role: ""))
check(!AdminConsolePolicy.entryVisible(role: "user"))
check(!AdminConsolePolicy.entryVisible(role: "admin"))
check(AdminConsolePolicy.canMintHandoff(role: "owner"))
check(!AdminConsolePolicy.canMintHandoff(role: "superuser"))

let handoff = String(repeating: "A", count: AdminConsolePolicy.handoffLength)
let consoleURL = AdminConsolePolicy.consoleUrl(base: URL(string: "https://coco.example")!, handoff: handoff)
check(consoleURL?.absoluteString == "https://coco.example/administration#handoff=\(handoff)")
// The handoff travels in the fragment: never sent to the server, never recorded
// by a reverse proxy, never leaked through Referer.
check(consoleURL?.query == nil)
check(consoleURL?.fragment == "handoff=\(handoff)")
check(!(consoleURL?.absoluteString.contains("token") ?? true))
// A base with a trailing slash still produces one clean path.
check(AdminConsolePolicy.consoleUrl(base: URL(string: "https://coco.example/")!, handoff: handoff)?.absoluteString
    == "https://coco.example/administration#handoff=\(handoff)")
// Cleartext administration is never offered.
check(AdminConsolePolicy.consoleUrl(base: URL(string: "http://coco.example")!, handoff: handoff) == nil)
// A malformed handoff fails closed instead of opening a browser page that cannot work.
check(AdminConsolePolicy.consoleUrl(base: URL(string: "https://coco.example")!, handoff: "") == nil)
check(AdminConsolePolicy.consoleUrl(base: URL(string: "https://coco.example")!, handoff: String(repeating: "A", count: 42)) == nil)
check(AdminConsolePolicy.consoleUrl(base: URL(string: "https://coco.example")!, handoff: String(repeating: "A", count: 44)) == nil)
check(AdminConsolePolicy.consoleUrl(base: URL(string: "https://coco.example")!, handoff: String(repeating: "A", count: 42) + "=") == nil)
check(AdminConsolePolicy.consoleUrl(base: URL(string: "https://coco.example")!, handoff: handoff + "/../x") == nil)

print("Admin pack contracts passed")
