package com.messenger.app.ui.devices

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.messenger.app.R
import com.messenger.app.domain.model.DeviceInfo
import com.messenger.app.domain.model.SecurityEventInfo
import com.messenger.app.domain.repository.DevicesRepository
import com.messenger.app.ui.theme.CocoTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Isolated repository: the operator's real devices are never revoked by this test. */
class DeviceConfirmationInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun otherDevicesAreNotRevokedBeforeExplicitConfirmationAndCancelDoesNothing() {
        val repository=DeviceFixture()
        show(repository)
        val label=compose.activity.getString(R.string.devices_revoke_all)
        val body=compose.activity.getString(R.string.devices_revoke_others_confirmation)
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(body).assertIsDisplayed()
        compose.runOnIdle {assertEquals(0,repository.revokes.get())}
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.onNodeWithText(body).assertDoesNotExist()
        compose.runOnIdle {assertEquals(0,repository.revokes.get())}
        compose.onNodeWithText(label).performClick()
        compose.onAllNodes(hasText(label) and hasClickAction()).onLast().performClick()
        compose.waitUntil(5000){repository.revokes.get()==1}
        compose.onNodeWithText(body).assertDoesNotExist()
        compose.onNodeWithText(label).assertIsNotEnabled()
        compose.runOnIdle {assertEquals(1,repository.revokes.get())}
    }

    @Test fun confirmationAndCancelRemainReachableWithLargeSystemText() {
        val repository=DeviceFixture()
        show(repository,1.8f)
        val label=compose.activity.getString(R.string.devices_revoke_all)
        compose.onNodeWithText(label).performClick()
        compose.onAllNodes(hasText(label) and hasClickAction()).onLast().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals(0,repository.revokes.get())}
    }

    @Test fun individualConfirmationStaysBoundToTheSameDeviceWhenTheListReorders() {
        val repository=DeviceFixture()
        lateinit var model:DevicesViewModel
        show(repository,onModel={model=it})
        val label=compose.activity.getString(R.string.revoke)
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle {repository.prependAnotherDevice();model.refresh()}
        compose.onNodeWithText(compose.activity.getString(R.string.devices_revoke_text,"Another test phone")).assertIsDisplayed()
        compose.onAllNodes(hasText(label) and hasClickAction()).onLast().performClick()
        compose.waitUntil(5000){repository.revokedIds.isNotEmpty()}
        compose.runOnIdle {assertEquals(listOf("other"),repository.revokedIds.toList())}
    }

    private fun show(repository:DeviceFixture,fontScale:Float=1f,onModel:(DevicesViewModel)->Unit={}) {
        compose.setContent {
            val store=remember {ViewModelStore()}
            val model=remember {DevicesViewModel(repository).also {store.put("device-confirmation",it)}}
            DisposableEffect(store) {onModel(model);onDispose {store.clear()}}
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale)) {
                CocoTheme {Box(Modifier.width(320.dp).fillMaxHeight()) {DevicesScreen(model,{})}}
            }
        }
    }

    private class DeviceFixture:DevicesRepository {
        val revokes=AtomicInteger()
        val revokedIds=java.util.Collections.synchronizedList(mutableListOf<String>())
        private var devices=listOf(
            DeviceInfo("current","This phone","android",null,null,null,true),
            DeviceInfo("other","Another test phone","ios",null,null,null,false))
        override fun cachedDevices()=devices
        override suspend fun listDevices()=Result.success(devices)
        override suspend fun renameDevice(deviceId:String,displayName:String)=Result.success(Unit)
        override suspend fun revokeDevice(deviceId:String):Result<Unit> {
            revokedIds.add(deviceId);devices=devices.filterNot {it.id==deviceId};return Result.success(Unit)
        }
        fun prependAnotherDevice() {
            devices=listOf(DeviceInfo("inserted","Newly connected test phone","android",null,null,null,false))+devices
        }
        override suspend fun revokeOtherDevices():Result<Int> {
            revokes.incrementAndGet();devices=devices.filter {it.isCurrent};return Result.success(1)
        }
        override suspend fun securityEvents(limit:Int,cursor:Long?):Result<Pair<List<SecurityEventInfo>,Long?>> =
            Result.success(emptyList<SecurityEventInfo>() to null)
    }
}
