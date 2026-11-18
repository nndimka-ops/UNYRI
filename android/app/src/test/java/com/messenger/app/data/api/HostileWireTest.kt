package com.messenger.app.data.api

import com.messenger.app.data.message.OutboxFailureClass
import com.messenger.app.data.message.OutboxPolicy
import kotlin.test.*

class HostileWireTest {
    @Test fun `wire integer boundaries reject rather than truncate or wrap`() {
        listOf(0, -1, 128, Int.MAX_VALUE, Int.MIN_VALUE).forEach { assertFailsWith<HostileWireException> { HostileWire.device(it) } }
        assertEquals(127, HostileWire.device(127)); assertEquals(0, HostileWire.id(0)); assertEquals(Int.MAX_VALUE, HostileWire.id(Int.MAX_VALUE))
        assertFailsWith<HostileWireException> { HostileWire.id(-1) }
        assertFailsWith<HostileWireException> { HostileWire.ice(17) }
        assertFailsWith<HostileWireException> { HostileWire.dimension(0) }
        assertFailsWith<HostileWireException> { HostileWire.duration(Long.MAX_VALUE) }
        assertFailsWith<HostileWireException> { HostileWire.size(-1) }
        assertFailsWith<HostileWireException> { MultipartPartDto(Int.MAX_VALUE, 1, "unused", false) }
        assertEquals(OutboxFailureClass.PERMANENT, OutboxPolicy.classify(HostileWireException()))
        assertEquals(OutboxFailureClass.PERMANENT, OutboxPolicy.classify(kotlinx.serialization.SerializationException("overflow")))
    }
}
