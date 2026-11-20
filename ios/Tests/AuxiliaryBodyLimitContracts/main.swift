import Foundation

enum APIError: Error { case network(String), decoding(String) }

// An in-memory URL loading protocol. Its reserved .invalid URL is never resolved.
final class MemoryResponse: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Length": "2"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("{}".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@main struct AuxiliaryBodyLimitContracts {
    static let url = URL(string: "https://auxiliary.invalid/contracts")!

    static func main() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MemoryResponse.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        // Exercise the production async adapter, not a reimplementation of it.
        let (body, http) = try await session.unyriBoundedData(for: URLRequest(url: url), maximumBytes: 2)
        precondition(body == Data("{}".utf8) && http.statusCode == 200)
        do {
            _ = try await session.unyriBoundedData(for: URLRequest(url: url), maximumBytes: 1)
            preconditionFailure("Oversized successful JSON must never reach the decoder")
        } catch is HTTPBodyLimitExceeded { }
        let cancelled = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await session.unyriBoundedData(for: URLRequest(url: url), maximumBytes: 2)
        }
        do { _ = try await cancelled.value; preconditionFailure("Cancelled read resumed") }
        catch is CancellationError { }

        // Deliver callbacks directly to test exact boundaries, dishonest/absent
        // lengths and split chunks without transport timing or external servers.
        for maximum in [16 * 1024, 64 * 1024, 8 * 1024 * 1024] {
            for status in [200, 401, 429, 503] {
                for declared in [nil, "1", String(maximum + 1)] as [String?] {
                    try await overflow(session: session, maximum: maximum, status: status, declared: declared)
                }
            }
            let collector = DirectJSONCollector(maximum: maximum)
            let task = session.dataTask(with: url) // Suspended throughout: no request.
            collector.attach(task)
            let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Length": String(maximum)])!
            collector.urlSession(session, dataTask: task, didReceive: response) { precondition($0 == .allow) }
            let bytes = Data(repeating: 32, count: maximum)
            collector.urlSession(session, dataTask: task, didReceive: bytes)
            collector.urlSession(session, task: task, didCompleteWithError: nil)
            let (result, _) = try await collector.result()
            precondition(result == bytes, "An exact-limit response must remain valid")
            task.cancel()
        }
        for status in [200, 204, 302, 401, 503] {
            let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
            let task = session.dataTask(with: url)
            DiscardedHTTPBody().urlSession(session, dataTask: task, didReceive: response) {
                precondition($0 == .cancel, "Diagnostics must cancel even unknown-length/error bodies at headers")
            }
            var completed = false
            let delivery = DeliveryResponseDiscarder { completed = true }
            delivery.urlSession(session, dataTask: task, didReceive: response) { precondition($0 == .cancel) }
            delivery.urlSession(session, task: task, didCompleteWithError: URLError(.cancelled))
            precondition(completed, "Discarding an acknowledgement must still finish notification delivery")
            task.cancel()
        }
        let finish = NotificationFinishHarness()
        var shown: [String] = []
        finish.content = "unchanged notification"
        finish.contentHandler = { shown.append($0) }
        finish.deliveryTask = session.dataTask(with: url)
        let deliveryTask = finish.deliveryTask!
        finish.finish()
        finish.finish() // Expiration racing completion remains one-shot.
        precondition(shown == ["unchanged notification"] && finish.deliveryTask == nil)
        precondition(deliveryTask.state == .canceling || deliveryTask.state == .completed)
        print("Auxiliary body limits, cancellation, discard and notification completion contracts passed")
    }

    static func overflow(session: URLSession, maximum: Int, status: Int, declared: String?) async throws {
        let collector = DirectJSONCollector(maximum: maximum,
            overflowError: HTTPBodyLimitExceeded(maximumBytes: maximum), preserveHTTPErrorStatus: true)
        let task = session.dataTask(with: url)
        collector.attach(task)
        defer { task.cancel() }
        let headers = declared.map { ["Content-Length": $0] }
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        var allowed = false
        collector.urlSession(session, dataTask: task, didReceive: response) { allowed = $0 == .allow }
        if declared == String(maximum + 1) {
            precondition(!allowed, "Known oversized bodies must cancel before any bytes")
        } else {
            precondition(allowed)
            let half = Data(repeating: 32, count: maximum / 2)
            collector.urlSession(session, dataTask: task, didReceive: half)
            collector.urlSession(session, dataTask: task, didReceive: half)
            collector.urlSession(session, dataTask: task, didReceive: Data([32]))
            // Late callbacks cannot grow the buffer or overwrite the limit error.
            collector.urlSession(session, dataTask: task, didReceive: half)
        }
        precondition(task.state == .canceling || task.state == .completed, "Overflow must cancel the underlying task")
        collector.urlSession(session, task: task, didCompleteWithError: URLError(.cancelled))
        do {
            let (prefix, http) = try await collector.result()
            precondition(status != 200 && http.statusCode == status && prefix.count <= maximum,
                         "Only bounded HTTP error status may survive overflow")
        } catch let error as HTTPBodyLimitExceeded {
            precondition(status == 200 && error.maximumBytes == maximum)
        }
    }
}
