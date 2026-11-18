package com.messenger.app.push

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

/** JVM policy tests plus boundary wiring checks for Android-only framework sinks. */
class SecurityBatch2PresentationTest {
    private fun source(path: String): String = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()
    @Test fun previewsOffNeverAuthorizesPersonDecoration() {
        for (enabled in listOf(false, true)) for (messages in listOf(false, true)) {
            assertFalse(NotificationSettings(enabled = enabled, messages = messages, previews = false).senderDecorationsEnabled)
        }
        assertTrue(NotificationSettings().senderDecorationsEnabled)
        assertFalse(NotificationSettings(enabled = false).senderDecorationsEnabled)
        val handler = source("java/com/messenger/app/push/MessagePushHandlerImpl.kt")
        val decorationGate = handler.indexOf("scope?.takeIf { settings().senderDecorationsEnabled }")
        assertTrue(decorationGate >= 0)
        assertTrue(decorationGate < handler.indexOf("resolveAvatar(event.chatId)?.person()"))
        assertTrue(handler.contains("!settings().senderDecorationsEnabled ||")) // Recheck after async avatar work.
        assertTrue(handler.contains(".setPublicVersion(genericMessagePublicVersion(context))"))
        val public = source("java/com/messenger/app/push/MessageNotificationPrivacy.kt")
        assertFalse(public.contains("Person")); assertFalse(public.contains("MessagingStyle")); assertFalse(public.contains("avatar"))
    }
    @Test fun sensitiveClipboardCoversBothCopiesAndAppBackground() {
        val helper = source("java/com/messenger/app/data/secure/SensitiveClipboard.kt")
        assertTrue(helper.contains("android.content.extra.IS_SENSITIVE")); assertTrue(helper.contains("putBoolean"))
        assertTrue(source("java/com/messenger/app/ui/chats/MediaViewer.kt").contains("SensitiveClipboard.copyUri(context, uri, currentItem.mime)"))
        assertTrue(source("java/com/messenger/app/ui/chats/ChatScreen.kt").contains("SensitiveClipboard.copyText(context, text)"))
        assertTrue(source("java/com/messenger/app/AppForegroundTracker.kt").contains("SensitiveClipboard.onBackground(app)"))
    }
    @Test fun launcherHasNoAffinityAndKeepsShareAndNotificationRouting() {
        val xml = source("AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val android = "http://schemas.android.com/apk/res/android"
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }.single { it.getAttributeNS(android, "name") == ".MainActivity" }
        assertTrue(main.hasAttributeNS(android, "taskAffinity")); assertEquals("", main.getAttributeNS(android, "taskAffinity"))
        assertEquals("singleTop", main.getAttributeNS(android, "launchMode")); assertEquals("true", main.getAttributeNS(android, "exported"))
        val actions = main.getElementsByTagName("action")
        val names = (0 until actions.length).map { (actions.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
        assertTrue(names.containsAll(listOf("android.intent.action.MAIN", "android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE")))
        val route = source("java/com/messenger/app/push/NotificationIntents.kt")
        assertTrue(route.contains("Intent(context, MainActivity::class.java)"))
        assertTrue(route.contains("Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP"))
        assertTrue(route.contains("NotificationRouteProofPolicy.accepts"))
    }
    @Test fun untrustedImageEntryPointsUseGatedDecoderAndPdfIsExplicitOnly() {
        val paths = listOf("push/NotificationAvatar.kt", "ui/components/AvatarBitmap.kt", "ui/chats/ReplyPreview.kt",
            "ui/chats/MediaViewerPreview.kt", "ui/chats/ConversationMediaPreview.kt", "ui/chats/ChatScreen.kt", "ui/media/MediaGalleryScreen.kt")
        for (path in paths) {
            val text = source("java/com/messenger/app/$path")
            assertFalse(text.contains("BitmapFactory.decode"), path)
            assertTrue(text.contains("PeerImageDecoder.decode"), path)
        }
        val viewer = source("java/com/messenger/app/ui/chats/MediaViewer.kt")
        val signatureGate = viewer.indexOf("PeerImageDecoder.allowed(file)")
        assertTrue(signatureGate >= 0)
        assertTrue(signatureGate < viewer.indexOf("ImageDecoder.decodeDrawable"))
        assertTrue(viewer.indexOf("if (index == activePage)") < viewer.indexOf("PdfReader(file"))
        assertFalse(source("java/com/messenger/app/ui/chats/ConversationMediaPreview.kt").contains("PdfRenderer"))
    }
}
