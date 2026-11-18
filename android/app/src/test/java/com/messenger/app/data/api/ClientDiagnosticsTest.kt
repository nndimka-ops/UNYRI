package com.messenger.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientDiagnosticsTest {
    @Test fun identifiersAndQueriesAreNeverReturnedAsOperation() {
        assertEquals("messages", ClientDiagnostics.operation("/chats/private-id/messages"))
        assertEquals("receipts", ClientDiagnostics.operation("/chats/private-id/read"))
        assertEquals("attachments", ClientDiagnostics.operation("/attachments/private-file/download"))
        assertEquals("calls", ClientDiagnostics.operation("/calls/private-call/hangup"))
    }
    @Test fun authAndDiagnosticsAreExcluded() {
        for (path in listOf("/auth/login", "/auth/refresh", "/diagnostics", "/users/me", "/unknown")) {
            assertNull(ClientDiagnostics.operation(path))
        }
    }
}
