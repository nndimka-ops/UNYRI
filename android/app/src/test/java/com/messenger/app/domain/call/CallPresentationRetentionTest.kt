package com.messenger.app.domain.call

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.messenger.app.domain.model.CallMediaKind
import com.messenger.app.ui.call.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CallPresentationRetentionTest {
    @Test fun `activity ViewModel store keeps minimized hidden placement through recreation and phase changes`() = runTest {
        val dispatcher=UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store=ViewModelStore()
        try {
            val signal=FakeCallSignalingClient()
            val engine=FakeWebRtcEngine()
            val controller=CallController(FakeCallApi(),signal,engine,backgroundScope+dispatcher)
            controller.startCall("peer","Peer",CallMediaKind.VIDEO);runCurrent()
            val factory=CallViewModelFactory(controller)
            val before=ViewModelProvider(store,factory)[CallViewModel::class.java]
            runCurrent()
            val p=before.presentation
            val placement=CallMiniWindowPolicy.Placement(CallMiniWindowPolicy.Corner.TOP_LEFT,CallMiniWindowPolicy.Step.LARGE,true,.3f)
            p.expanded.value=false;p.miniHidden.value=true;p.mini.store("call-0",placement);p.mini.hide("call-0")
            val recreated=ViewModelProvider(store,factory)[CallViewModel::class.java]
            assertSame(before,recreated)
            assertFalse(recreated.presentation.expanded.value);assertTrue(recreated.presentation.miniHidden.value)
            assertEquals(placement,recreated.presentation.mini.placement("call-0"))
            signal.emit(com.messenger.app.data.call.CallSignalingEvent.Accepted("call-0"));runCurrent()
            assertFalse(p.expanded.value);assertTrue(p.miniHidden.value)
            controller.hangup();controller.acknowledgeEnded();runCurrent()
            assertNull(p.callId.value);assertFalse(p.miniHidden.value)
            controller.startCall("next","Next",CallMediaKind.VIDEO);runCurrent()
            assertEquals("call-1",p.callId.value);assertTrue(p.expanded.value)
            assertEquals(CallMiniWindowPolicy.Placement(),p.mini.placement("call-1"))
        } finally { store.clear();Dispatchers.resetMain() }
    }
}
