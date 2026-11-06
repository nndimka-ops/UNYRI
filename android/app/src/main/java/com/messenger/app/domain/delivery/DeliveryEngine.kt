package com.messenger.app.domain.delivery

/** One configured route for this first stage. Route selection can later live here;
 * introducing speculative fallback/parallel sends now would change delivery semantics. */
class DeliveryEngine(private val transport: MessageTransport) {
    suspend fun attempt(message: PreparedMessage, context: DeliveryContext): DeliveryAcceptance =
        transport.send(message, context)
}
