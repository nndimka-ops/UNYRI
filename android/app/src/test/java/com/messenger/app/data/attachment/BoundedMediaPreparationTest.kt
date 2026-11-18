package com.messenger.app.data.attachment

import java.io.ByteArrayOutputStream
import kotlin.test.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedMediaPreparationTest {
    @Test fun unknownSmallProviderCommitsViaExistingBoundedImporter() = runTest {
        val saved=mutableListOf<String>();var normal=0;var bounded=0
        val coordinator=MediaPreparationCoordinator(backgroundScope,{7},{_,source->normal++;source},
            {_,_,source,_->saved+=source},{_,_,_->},{_,_->},stageWithLimit={_,source,limit->
                bounded++;val bytes=ByteArrayOutputStream()
                copyBoundedFile(ByteArray(42).inputStream(),bytes,boundedDraftSourceLimit(500,limit))
                source
            })
        assertTrue(coordinator.start("chat",listOf("unknown-small"),expectedEpoch=7,sourceLimitBytes=100))
        runCurrent()
        assertEquals(listOf("unknown-small"),saved);assertEquals(0,normal);assertEquals(1,bounded)
        assertFalse(coordinator.state.value.getValue("chat").failed)
    }
    @Test fun actualOversizeOrGrowthShowsLimitAndRetainsAlreadyCommittedMixedFiles() = runTest {
      for (sources in listOf(listOf("ready","grew-after-metadata"), listOf("grew-after-metadata","ready"))) {
        val saved=mutableListOf<String>();val discarded=mutableListOf<String>();var cleanups=0
        lateinit var coordinator: MediaPreparationCoordinator
        coordinator=MediaPreparationCoordinator(backgroundScope,{7},{_,source->source},
            {_,_,source,_->
                if (sources.first()=="grew-after-metadata") assertEquals(MediaPreparationCoordinator.Failure.SOURCE_LIMIT,coordinator.state.value.getValue("chat").failure)
                saved+=source
            },{_,_,_->},{_,source->discarded+=source},stageWithLimit={_,source,limit->
                val bytes=ByteArrayOutputStream()
                copyBoundedFile(ByteArray(if (source == "ready") 42 else 101).inputStream(),bytes,boundedDraftSourceLimit(500,limit))
                source
            })
        assertTrue(coordinator.start("chat",sources,expectedEpoch=7,sourceLimitBytes=100,cleanup={cleanups++}))
        runCurrent()
        assertEquals(listOf("ready"),saved)
        val state=coordinator.state.value.getValue("chat")
        assertFalse(state.active);assertTrue(state.failed)
        assertEquals(MediaPreparationCoordinator.Failure.SOURCE_LIMIT,state.failure)
        assertEquals(1,cleanups)
        assertEquals(listOf("ready"),discarded)
      }
    }
    @Test fun boundedModeStillStopsOnFatalIoOrCancellationWithoutImportingLaterItems() = runTest {
      for (error in listOf(java.io.IOException("provider failed"), kotlinx.coroutines.CancellationException("account changed"))) {
        val saved=mutableListOf<String>();var cleanups=0
        val coordinator=MediaPreparationCoordinator(backgroundScope,{7},{_,source->source},
            {_,_,source,_->saved+=source},{_,_,_->},{_,_->},stageWithLimit={_,_,_->throw error})
        coordinator.start("chat",listOf("failed","ready"),sourceLimitBytes=100,cleanup={cleanups++});runCurrent()
        assertTrue(saved.isEmpty());assertEquals(1,cleanups)
        assertNull(coordinator.state.value.getValue("chat").failure)
        assertEquals(error is java.io.IOException,coordinator.state.value.getValue("chat").failed)
      }
    }
    @Test fun ordinaryImportUsesUnchangedStageWithoutOptionalCap() = runTest {
        var normal=0;var bounded=0
        val coordinator=MediaPreparationCoordinator(backgroundScope,{7},{_,source->normal++;source},
            {_,_,_,_->},{_,_,_->},{_,_->},stageWithLimit={_,source,_->bounded++;source})
        coordinator.start("chat",listOf("ordinary-video"));runCurrent()
        assertEquals(1,normal);assertEquals(0,bounded)
    }
}
