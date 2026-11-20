package org.unyri.admin.core

import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeNoException
import com.sun.jna.platform.win32.Win32Exception
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class WindowsStoreTest {
    @Test fun dpapiRoundTripDoesNotWritePlaintextAndDeleteRemovesIt() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory=Files.createTempDirectory(Path.of("build"),"dpapi-")
        try {
            val path=directory.resolve("session.bin"); val store=WindowsSessionStore(path)
            val id="00000000-0000-0000-0000-000000000000"
            val session=Session("https://test.invalid","synthetic-access",PaperCrypto.b64(ByteArray(32)),id,"synthetic",id,"owner",id,
                Instant.now().plusSeconds(3600).toString(),Instant.now().toString(),true)
            try { store.save(session) } catch (e: AdminFailure) {
                val cause = e.cause
                if (cause is Win32Exception && cause.errorCode == 2) {
                    assertFalse(Files.exists(path), "Unavailable DPAPI must not write a session")
                    assumeNoException("Windows execution profile has no usable DPAPI master-key context (code 2); interactive-user round trip remains required", cause)
                }
                throw e
            }
            assertFalse(Files.readAllBytes(path).toString(Charsets.ISO_8859_1).contains(session.access))
            assertEquals(session,store.load(session.origin))
            store.delete(session.origin); assertFalse(Files.exists(path))
            assertFailsWith<AdminFailure> { store.save(session.copy(optedIn=false)) }
        } finally { Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
