import Foundation

var failures = 0
func check(_ value: Bool, _ name: String) {
    if !value { print("FAIL: \(name)"); failures += 1 }
}
func accepts(_ content: MessageContent, _ operation: String = "delete", _ chat: String = "chat",
             _ client: String = "event", _ target: String? = "target") -> Bool {
    MessageContentCodec.authenticates(content, chatId: chat, clientMessageId: client,
        operation: operation, targetMessageId: target)
}
do {
    let data = try MessageContentCodec.authenticate(MessageContentCodec.encodeDelete(), chatId: "chat",
        clientMessageId: "event", operation: "delete", targetMessageId: "target", targetClientMessageId: "original-client")
    let content = MessageContentCodec.decode(data)
    check(accepts(content), "bound deletion is accepted")
    check(!accepts(content, "edit"), "operation substitution is rejected")
    check(!accepts(content, "delete", "other-chat"), "chat substitution is rejected")
    check(!accepts(content, "delete", "chat", "replayed-client"), "client substitution is rejected")
    check(!accepts(content, "delete", "chat", "event", "other-target"), "target substitution is rejected")
    let text = MessageContentCodec.decode(MessageContentCodec.encodeText("legacy"))
    check(accepts(text, "content", "chat", "event", nil), "legacy text remains readable")
    check(!accepts(text, "edit"), "legacy text cannot become an edit")
    check(!accepts(MessageContentCodec.decode(MessageContentCodec.encodeDelete())), "unbound deletion is rejected")
    // Kotlin omits default version=1; this is the shared wire representation.
    let kotlin = Data(#"{"kind":"delete","authentication":{"chat_id":"chat","client_message_id":"event","operation":"delete","target_message_id":"target","target_client_message_id":"original-client"}}"#.utf8)
    check(accepts(MessageContentCodec.decode(kotlin)), "Kotlin default-version payload is accepted")
    let nullVersion = Data(#"{"kind":"delete","authentication":{"version":null,"chat_id":"chat","client_message_id":"event","operation":"delete","target_message_id":"target","target_client_message_id":"original-client"}}"#.utf8)
    check(!accepts(MessageContentCodec.decode(nullVersion)), "explicit null version cannot authorize deletion")
    for raw in [#"{"kind":"text","authentication":null}"#, #"{"kind":"text","authentication":{"version":"bad"}}"#] {
        check(!accepts(MessageContentCodec.decode(Data(raw.utf8)), "content", "chat", "event", nil),
              "malformed authentication cannot fall back to legacy text")
    }
    var future = content
    future.authentication?.version = 2
    check(!accepts(future), "unknown authentication version fails closed")
} catch { print("FAIL: \(error)"); failures += 1 }
if failures > 0 { exit(1) }
print("Message authentication contracts passed")
