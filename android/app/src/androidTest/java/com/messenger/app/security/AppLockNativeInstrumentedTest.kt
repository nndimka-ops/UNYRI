package com.messenger.app.security

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.domain.model.AuthSessionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only on the task-owned emulator with an explicitly supplied emulator PIN. */
@RunWith(AndroidJUnit4::class)
class AppLockNativeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } } }
    private var diagnosticController: AppLockController? = null
    private var diagnosticActivity: MainActivity? = null
    private fun await(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; Thread.sleep(100) }
        val app=ApplicationProvider.getApplicationContext<MessengerApp>()
        val keyguard=app.getSystemService(android.app.KeyguardManager::class.java)
        val root=instrumentation.uiAutomation.rootInActiveWindow
        instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","Native failed-await: package=${root?.packageName} class=${root?.className} keyguardLocked=${keyguard.isKeyguardLocked} deviceLocked=${keyguard.isDeviceLocked} controller=${diagnosticController?.state?.value} ownerCurrent=${diagnosticController?.canReadCurrentOwner()} lifecycle=${diagnosticActivity?.lifecycle?.currentState} focused=${diagnosticActivity?.hasWindowFocus()}") })
        assertTrue("Native lock state did not settle", condition())
    }
    private fun awaitAppSurface() {
        await {
            val activity=diagnosticActivity
            val keyguard=activity?.getSystemService(android.app.KeyguardManager::class.java)
            val title=activity?.getString(com.messenger.app.R.string.app_lock_auth_title)
            activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true && activity.hasWindowFocus() &&
                keyguard?.isKeyguardLocked==false && keyguard.isDeviceLocked==false &&
                (title==null || instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title).isNullOrEmpty())
        }
    }
    private fun enterSystemCredential(pin: String) {
        val title=checkNotNull(diagnosticActivity).getString(com.messenger.app.R.string.app_lock_auth_title)
        repeat(80) {
            val root=instrumentation.uiAutomation.rootInActiveWindow
            val nativePackages=setOf("com.android.systemui","com.android.settings")
            if(root?.packageName?.toString() !in nativePackages || root?.findAccessibilityNodeInfosByText(title).isNullOrEmpty()) { Thread.sleep(100); return@repeat }
            val usePin=root?.findAccessibilityNodeInfosByText("Use PIN")?.firstOrNull { it.isClickable }
            if(usePin!=null) { usePin.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); Thread.sleep(300); return@repeat }
            val nodes=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if(node==null) return
                if(node.isEditable && node.packageName?.toString() in nativePackages && node.viewIdResourceName?.substringAfterLast('/') in setOf("lockPassword","password_entry")) nodes.add(node)
                for(i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(root)
            val field=nodes.firstOrNull()
            if(field!=null) {
                field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                field.refresh()
                if(!field.isFocused) { Thread.sleep(100); return@repeat }
                val filled=field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,pin) })
                assertTrue("Native credential field rejected text action",filled)
                field.refresh()
                assertEquals("Native credential character count",pin.length,field.text?.length)
                val submit=field.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                if(!submit) shell("input keyevent 66")
                await { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title).isNullOrEmpty() }
                return
            }
            Thread.sleep(100)
        }
        fail("Current COCO native credential field not found")
    }
    private fun captureComponent(scenario: ActivityScenario<MainActivity>, name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        scenario.onActivity { activity -> activity.setContent { com.messenger.app.ui.theme.CocoTheme { content() } } }
        instrumentation.waitForIdleSync(); Thread.sleep(800)
        val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val app=ApplicationProvider.getApplicationContext<MessengerApp>()
        val file=java.io.File(app.getExternalFilesDir(null),"settings-native-$name.building.png")
        file.outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
        image.recycle()
        check(file.length()>8)
        // Keep proof outside account-purge directories before finally deletes the QA account.
        check(name in setOf("privacy","downloads","cache"))
        val destination="/data/local/tmp/coco-settings-proof-$name.png"
        // executeShellCommand tokenizes directly; these fixed paths have no spaces.
        shell("cp ${file.absolutePath} $destination")
        val descriptor=instrumentation.uiAutomation.executeShellCommand("wc -c $destination")
        val exported=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }.trim().substringBefore(' ').toLongOrNull()
        assertEquals("Exported PNG byte count",file.length(),exported)
    }
    @Test fun nativeCredentialAndRegisteredRepositoryReadGateOnOwnedMainActivity(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        check(args.getString("ownedEmulator") == "history_audit_20261101" && android.os.Build.HARDWARE in setOf("ranchu","goldfish")) { "Task-owned emulator only" }
        val pin=checkNotNull(args.getString("devicePin")); check(pin.matches(Regex("[0-9]{4,8}")))
        val app=ApplicationProvider.getApplicationContext<MessengerApp>()
        val lockPrefs=app.getSharedPreferences("native-app-lock",android.content.Context.MODE_PRIVATE)
        val oldEnabled=lockPrefs.getBoolean("device|enabled",false); val oldTimeout=lockPrefs.getLong("device|timeout",0)
        lockPrefs.edit().putBoolean("device|enabled",false).putLong("device|timeout",0).commit()
        app.authRepository.logout()
        com.messenger.app.data.api.ServerEndpoint.save(app, com.messenger.app.BuildConfig.PUBLIC_DEFAULT_API_ORIGIN)
        val username="settingsqa" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val password="Aa9!" + java.util.UUID.randomUUID()
        app.authRepository.register(username,password,"Android Settings QA").getOrThrow()
        val owner=(app.authRepository.authState.value as AuthSessionState.LoggedIn).user.id
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            app.authRepository.startWithoutRestoredHistory().getOrThrow()
            val chat=app.chatRepository.getOrCreateSavedChat().getOrThrow()
            scenario=ActivityScenario.launch(MainActivity::class.java)
            instrumentation.waitForIdleSync()
            lateinit var controller: AppLockController
            scenario.onActivity { controller=it.appLockController; diagnosticController=controller; diagnosticActivity=it }
            awaitAppSurface()
            scenario.onActivity { controller.setEnabled(true) }
            assertFalse(controller.state.value.enabled)
            enterSystemCredential(pin); await { controller.state.value.enabled && !controller.state.value.authenticating }; awaitAppSurface()
            scenario.onActivity { assertTrue(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0) }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(controller.state.value.locked)
            val daoField=app.messageRepository.javaClass.getDeclaredField("dao").apply { isAccessible=true }
            val dao=daoField.get(app.messageRepository) as com.messenger.app.data.local.message.MessageDao
            val readBefore=app.chatRepository.localReadWatermarks()[chat.chatId] ?: 0L
            val sequence=readBefore+1
            // Synthetic decrypted local arrival, never a claim of network/E2E delivery.
            app.authRepository.withSessionWrite(app.authRepository.currentSessionEpoch()) {
                dao.upsert(com.messenger.app.data.local.message.MessageEntity(java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),chat.chatId,java.util.UUID.randomUUID().toString(),"Native lock read visibility fixture",sequence,java.time.Instant.now().toString(),"delivered"))
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(controller.state.value.locked)
            val foregroundSequence=sequence+1
            app.authRepository.withSessionWrite(app.authRepository.currentSessionEpoch()) {
                dao.upsert(com.messenger.app.data.local.message.MessageEntity(java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),chat.chatId,java.util.UUID.randomUUID().toString(),"Arrival behind foreground lock shield",foregroundSequence,java.time.Instant.now().toString(),"delivered"))
            }
            // Exercise the real repository and Room under its registered live
            // Activity guard, independent of navigation/Compose timing.
            app.messageRepository.markRead(chat.chatId,foregroundSequence).getOrThrow()
            assertEquals(readBefore,app.chatRepository.localReadWatermarks()[chat.chatId] ?: 0L)
            scenario.onActivity { controller.unlock() }; enterSystemCredential(pin); await { !controller.state.value.locked && !controller.state.value.authenticating }; awaitAppSurface()
            await { app.messageReadVisibility.allowsViewedCursor() }
            app.messageRepository.markRead(chat.chatId,foregroundSequence).getOrThrow()
            assertTrue((app.chatRepository.localReadWatermarks()[chat.chatId] ?: 0L) >= foregroundSequence)
            instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","Native next timeout auth: ${controller.state.value}; activePackage=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}") })
            scenario.onActivity { controller.setTimeout(60_000) }; enterSystemCredential(pin); await { controller.state.value.timeoutMs == 60_000L && !controller.state.value.authenticating }; awaitAppSurface()
            scenario.recreate(); scenario.onActivity { controller=it.appLockController; diagnosticController=controller; diagnosticActivity=it }
            assertTrue(controller.state.value.locked)
            scenario.onActivity { controller.unlock() }; enterSystemCredential(pin); await { !controller.state.value.locked && !controller.state.value.authenticating }; awaitAppSurface()
            scenario.onActivity { controller.setEnabled(false) }
            Thread.sleep(600)
            // Genuine user cancellation by leaving the app. Do not claim the
            // OS PIN keyboard Back/Cancel interaction is covered by this case.
            shell("input keyevent 3")
            await { !controller.state.value.authenticating }
            assertTrue(controller.state.value.enabled)
            // Foreground the stopped task through the real launcher path;
            // ActivityScenario.moveToState alone cannot undo a genuine Home.
            shell("am start -W -n com.messenger.app/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10020000")
            awaitAppSurface()
            scenario.onActivity { controller=it.appLockController; diagnosticController=controller; diagnosticActivity=it; assertTrue(controller.state.value.enabled); assertFalse(controller.state.value.authenticating) }
            scenario.onActivity { controller.unlock() }; enterSystemCredential(pin); await { !controller.state.value.locked && !controller.state.value.authenticating }; awaitAppSurface()
            Thread.sleep(300)
            scenario.onActivity { controller.setEnabled(false) }; enterSystemCredential(pin); await { !controller.state.value.enabled && !controller.state.value.authenticating }; awaitAppSurface()
            scenario.onActivity { assertEquals(0,it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        } finally {
            scenario?.close()
            val current=app.authRepository.authState.value as? AuthSessionState.LoggedIn
            check(current?.user?.id == owner && current.user.username == username) { "QA owner changed; deletion refused" }
            app.authRepository.deleteAccount(password).getOrThrow()
            lockPrefs.edit().putBoolean("device|enabled",oldEnabled).putLong("device|timeout",oldTimeout).commit()
        }
    }
    @Test fun ownedPrivacyWireAndProductionSettingsComponentCaptures(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        check(args.getString("ownedEmulator")=="history_audit_20261101" && android.os.Build.HARDWARE in setOf("ranchu","goldfish")) { "Task-owned emulator only" }
        val app=ApplicationProvider.getApplicationContext<MessengerApp>()
        val lockPrefs=app.getSharedPreferences("native-app-lock",android.content.Context.MODE_PRIVATE)
        val oldEnabled=lockPrefs.getBoolean("device|enabled",false); val oldTimeout=lockPrefs.getLong("device|timeout",0)
        lockPrefs.edit().putBoolean("device|enabled",false).putLong("device|timeout",0).commit()
        app.authRepository.logout()
        com.messenger.app.data.api.ServerEndpoint.save(app,com.messenger.app.BuildConfig.PUBLIC_DEFAULT_API_ORIGIN)
        val username="settingswire"+java.util.UUID.randomUUID().toString().replace("-","").take(12)
        val password="Aa9!"+java.util.UUID.randomUUID()
        app.authRepository.register(username,password,"Android Settings UI QA").getOrThrow()
        val owner=(app.authRepository.authState.value as AuthSessionState.LoggedIn).user.id
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            app.authRepository.startWithoutRestoredHistory().getOrThrow()
            scenario=ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { diagnosticController=it.appLockController; diagnosticActivity=it }
            awaitAppSurface()
            scenario.onActivity { assertEquals(0,it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            val originalPrivacy=app.privacyRepository.refresh().getOrThrow()
            val changed=app.privacyRepository.update { it.copy(onlineVisibility="nobody",lastSeenVisibility="nobody",sendReadReceipts=false) }.getOrThrow()
            assertEquals("nobody",changed.onlineVisibility); assertFalse(changed.sendReadReceipts)
            val roundTrip=app.privacyRepository.refresh().getOrThrow()
            assertEquals(changed.revision,roundTrip.revision); assertFalse(roundTrip.sendReadReceipts)
            app.privacyRepository.update { originalPrivacy.copy(revision=it.revision) }.getOrThrow()
            captureComponent(scenario,"privacy") {
                com.messenger.app.ui.settings.SettingsScaffold("Privacy and security",{}) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { AppLockSettings(); com.messenger.app.ui.settings.PrivacySettingsContent() }
                }
            }
            captureComponent(scenario,"downloads") {
                com.messenger.app.ui.settings.SettingsScaffold("Automatic downloads",{}) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { com.messenger.app.ui.settings.MediaDownloadSettings() }
                }
            }
            val storage=com.messenger.app.ui.settings.StorageViewModel(app.mediaCacheManager)
            captureComponent(scenario,"cache") { com.messenger.app.ui.settings.StorageScreen(storage,{}) }
        } finally {
            scenario?.close()
            val current=app.authRepository.authState.value as? AuthSessionState.LoggedIn
            check(current?.user?.id == owner && current.user.username == username) { "QA owner changed; deletion refused" }
            app.authRepository.deleteAccount(password).getOrThrow()
            lockPrefs.edit().putBoolean("device|enabled",oldEnabled).putLong("device|timeout",oldTimeout).commit()
        }
    }

}
