import Foundation
import UserNotifications

/// Runs for every message alert, even when UNYRI was closed, and tells the server
/// that this device received the message, so the sender sees the second tick right
/// away (like WhatsApp/Telegram) instead of only after the app is opened.
///
/// It holds no account credentials: the push carries a short-lived token bound to
/// this device and this message. The notification itself is shown unchanged.
final class NotificationService: UNNotificationServiceExtension {
    /// Shared by every notification this extension process handles: a session per
    /// notification was never invalidated, so each one stayed alive until the process died.
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 8
        return URLSession(configuration: configuration)
    }()

    private let lock = NSLock()
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var content: UNNotificationContent?
    private var deliveryTask: URLSessionDataTask?

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        PeerImagePolicy.install()
        lock.lock(); self.contentHandler = contentHandler; content = request.content; lock.unlock()
        guard let report = DeliveryReport(userInfo: request.content.userInfo) else { finish(); return }
        var post = URLRequest(url: report.url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
        post.httpMethod = "POST"
        post.setValue("application/json", forHTTPHeaderField: "Content-Type")
        post.httpBody = report.body
        let task = Self.session.dataTask(with: post)
        task.delegate = DeliveryResponseDiscarder { [weak self] in self?.finish() }
        lock.lock()
        let active = self.contentHandler != nil
        if active { deliveryTask = task }
        lock.unlock()
        if active { task.resume() } else { task.cancel() }
    }

    override func serviceExtensionTimeWillExpire() { finish() }

    private func finish() {
        lock.lock()
        let handler = contentHandler, shown = content, task = deliveryTask
        contentHandler = nil
        deliveryTask = nil
        lock.unlock()
        task?.cancel()
        if let handler, let shown { handler(shown) }
    }
}

/// The extension never needs acknowledgement bytes. Cancel before any body is
/// delivered, for success and error statuses alike, then show the notification.
private final class DeliveryResponseDiscarder: NSObject, URLSessionDataDelegate {
    private let finished: () -> Void
    init(finished: @escaping () -> Void) { self.finished = finished }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        completionHandler(.cancel)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) { finished() }
}

struct DeliveryReport {
    let url: URL
    let body: Data

    init?(userInfo: [AnyHashable: Any]) {
        guard userInfo["event"] as? String == "new_message",
              let delivery = userInfo["delivery"] as? [String: Any],
              let token = delivery["token"] as? String, (16...128).contains(token.count),
              let expires = (delivery["expires_at"] as? NSNumber)?.int64Value,
              let device = (userInfo["device_id"] as? String).flatMap(UUID.init(uuidString:)),
              let message = (userInfo["message_id"] as? String).flatMap(UUID.init(uuidString:)) else { return nil }
        let origin = (delivery["origin"] as? String).flatMap(Self.httpsOrigin)
            ?? (Bundle.main.object(forInfoDictionaryKey: "UNYRI_API_BASE_URL") as? String).flatMap(Self.origin)
        guard let base = origin else { return nil }
        url = base.appendingPathComponent("push").appendingPathComponent("delivered")
        guard let encoded = try? JSONSerialization.data(withJSONObject: [
            "device_id": device.uuidString.lowercased(), "message_id": message.uuidString.lowercased(),
            "expires_at": expires, "token": token,
        ]) else { return nil }
        body = encoded
    }

    private static func httpsOrigin(_ raw: String) -> URL? {
        guard let url = origin(raw), url.scheme == "https" else { return nil }
        return url
    }
    private static func origin(_ raw: String) -> URL? {
        guard let parts = URLComponents(string: raw.trimmingCharacters(in: .whitespaces)),
              let scheme = parts.scheme?.lowercased(), ["https", "http"].contains(scheme),
              let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil else { return nil }
        var origin = URLComponents(); origin.scheme = scheme; origin.host = host; origin.port = parts.port
        return origin.url
    }
}
