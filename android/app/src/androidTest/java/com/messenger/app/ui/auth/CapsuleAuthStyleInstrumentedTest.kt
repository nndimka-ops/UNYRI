package com.messenger.app.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual auth screens, fake submissions only. The operator's signed-in account is untouched. */
@OptIn(ExperimentalTestApi::class)
class CapsuleAuthStyleInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var hideKeyboard:(()->Unit)?=null

    private class FakeAuth(delegate:AuthRepository):AuthRepository by delegate {
        override val authState:StateFlow<AuthSessionState> = MutableStateFlow(AuthSessionState.LoggedOut)
        override val cacheCleanupFailed:StateFlow<Boolean> = MutableStateFlow(false)
        var login:Pair<String,String>?=null
        var registration:Triple<String,String,String>?=null
        override suspend fun login(username:String,password:String):Result<Unit> {
            login=username to password;return Result.success(Unit)
        }
        override suspend fun register(username:String,password:String,displayName:String):Result<Unit> {
            registration=Triple(username,password,displayName);return Result.success(Unit)
        }
        override suspend fun logout() { error("An isolated auth UI test must not log out") }
        override suspend fun currentAccessToken():String?=null
    }

    @Composable private fun Host(fontScale:Float=1f,content:@Composable ()->Unit) {
        CocoTheme(mode=ThemeMode.DARK) {
            val density=LocalDensity.current
            val focus=LocalFocusManager.current
            val keyboard=LocalSoftwareKeyboardController.current
            SideEffect {hideKeyboard={focus.clearFocus(force=true);keyboard?.hide()}}
            CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale)) {
                Box(Modifier.width(320.dp).fillMaxHeight().background(LocalCocoColors.current.background).testTag("auth-fixture")) {
                    content()
                }
            }
        }
    }

    private fun button(resource:Int)=compose.onNode(hasText(compose.activity.getString(resource)) and hasClickAction())
    private fun input(index:Int,text:String) {
        compose.onAllNodes(hasSetTextAction())[index].performScrollTo().performTextReplacement(text)
    }
    private fun closeKeyboard() {compose.runOnIdle{hideKeyboard?.invoke()};compose.waitForIdle()}
    private fun capture(name:String) {
        compose.waitForIdle()
        val folder=File(compose.activity.getExternalFilesDir(null),"capsules-uiqa").apply{mkdirs()}
        File(folder,name).outputStream().use {output->
            compose.onNodeWithTag("auth-fixture").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
        }
    }

    @Test fun loginServerSelectionValidationSubmitAndRegistrationRemainUsableAt320dp() {
        val app=ApplicationProvider.getApplicationContext<MessengerApp>()
        val owner=app.authRepository.authState.value;val epoch=app.authRepository.currentSessionEpoch()
        val fake=FakeAuth(app.authRepository);val model=AuthViewModel(fake);val store=ViewModelStore().apply{put("auth",model)}
        var serverSelections=0;var registrations=0
        var registering by mutableStateOf(false)
        try {
            compose.setContent {Host {
                if(registering) RegisterScreen(model,{registering=false},ThemeMode.DARK,{},null,{},"qa.example.invalid:8443",{serverSelections++})
                else LoginScreen(model,{registrations++;registering=true},ThemeMode.DARK,{},null,{},"qa.example.invalid:8443",{serverSelections++})
            }}
            button(R.string.auth_flow_login).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("qa.example.invalid:8443").performScrollTo().performTouchInput{click()}
            compose.runOnIdle{assertEquals(1,serverSelections);assertNull(fake.login)}
            input(0,"@capsule_ui_test");input(1,"test-password-only")
            closeKeyboard()
            button(R.string.auth_flow_login).performScrollTo().assertIsEnabled().performTouchInput{click()}
            compose.runOnIdle {assertEquals("capsule_ui_test" to "test-password-only",fake.login)}
            compose.onNodeWithText("qa.example.invalid:8443").performScrollTo()
            capture("capsule-login-320.png")
            button(R.string.auth_create_account).performScrollTo().performTouchInput{click()}
            compose.runOnIdle{assertEquals(1,registrations)}
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(4)
            assertEquals(owner,app.authRepository.authState.value);assertEquals(epoch,app.authRepository.currentSessionEpoch())
        }finally{compose.runOnIdle{store.clear()}}
    }

    @Test fun registrationValidationAndSubmitRemainUsableAt320dpWithLargeText() {
        val app=ApplicationProvider.getApplicationContext<MessengerApp>()
        val owner=app.authRepository.authState.value;val epoch=app.authRepository.currentSessionEpoch()
        val fake=FakeAuth(app.authRepository);val model=AuthViewModel(fake);val store=ViewModelStore().apply{put("auth",model)}
        var serverSelections=0
        try {
            compose.setContent {Host(fontScale=1.3f) {
                RegisterScreen(model,{},ThemeMode.DARK,{},null,{},"qa.example.invalid:8443",{serverSelections++})
            }}
            button(R.string.auth_create_account).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("qa.example.invalid:8443").performScrollTo().performTouchInput{click()}
            input(0,"Capsule QA");input(1,"@capsule_ui_test")
            input(2,"test-password-only");input(3,"does-not-match")
            closeKeyboard()
            button(R.string.auth_create_account).performScrollTo().assertIsNotEnabled()
            input(3,"test-password-only");closeKeyboard()
            // Matching passwords alone must not skip the explicit agreement.
            button(R.string.auth_create_account).performScrollTo().assertIsNotEnabled()
            compose.onNode(isToggleable()).assertIsOff()
            // The explanatory label is an actual part of the consent target,
            // not an inert caption beside a tiny checkbox.
            compose.onNodeWithText(compose.activity.getString(R.string.alpha_accept),useUnmergedTree=true)
                .performScrollTo().performTouchInput{click()}
            compose.onNode(isToggleable()).assertIsOn()
            button(R.string.auth_create_account).performScrollTo().assertIsEnabled().performTouchInput{click()}
            compose.runOnIdle {
                assertEquals(1,serverSelections)
                assertEquals(Triple("capsule_ui_test","test-password-only","Capsule QA"),fake.registration)
            }
            capture("capsule-register-320-large-bottom.png")
            compose.onNodeWithText("qa.example.invalid:8443").performScrollTo()
            capture("capsule-register-320-large.png")
            assertEquals(owner,app.authRepository.authState.value);assertEquals(epoch,app.authRepository.currentSessionEpoch())
        }finally{compose.runOnIdle{store.clear()}}
    }
}
