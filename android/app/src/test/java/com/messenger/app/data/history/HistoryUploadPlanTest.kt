package com.messenger.app.data.history
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HistoryUploadPlanTest {
    private val attachment="22222222-2222-4222-8222-222222222222"
    @Test fun `restart reuses ID but changed ciphertext has a new reservation`() {
        val directory=Files.createTempDirectory("history-plan").toFile()
        try {
            val first=HistoryUploadPlan(directory).blob(attachment,100,"a".repeat(64))
            assertEquals(first,HistoryUploadPlan(directory).blob(attachment,100,"a".repeat(64)))
            assertNotEquals(first,HistoryUploadPlan(directory).blob(attachment,100,"b".repeat(64)))
            HistoryUploadPlan(directory).retain(listOf(HistoryMediaEntry(attachment,first,100,"a".repeat(64))))
            assertEquals(1,directory.listFiles()!!.size)
            assertEquals(first,HistoryUploadPlan(directory).blob(attachment,100,"a".repeat(64)))
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
