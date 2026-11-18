package com.messenger.app.ui.media

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhotoEditorStateTest {
    private fun stroke(vararg points: EditorPoint) = EditorStroke(points.toList(), -1, .005f)
    private val line = stroke(EditorPoint(.1f, .1f), EditorPoint(.9f, .9f))

    @Test fun undoRedoIncludesTextMoveAndClearAsWholeTransactions() {
        val drawn = PhotoEditorState().stroke(line)
        val text = EditorLabel(text = "UNYRI")
        val labelled = drawn.label(text)
        val moved = labelled.label(text.transformed(.2f, .1f, 2f))
        val cleared = moved.clear()
        assertFalse(cleared.document.changed)
        assertEquals(moved.document, cleared.undo().document)
        assertEquals(labelled.document, moved.undo().document)
        assertEquals(drawn.document, labelled.undo().document)
        assertEquals(moved.document, moved.undo().undo().redo().redo().document)
    }
    @Test fun newEditAfterUndoDropsOnlyRedoBranch() {
        val first = PhotoEditorState().stroke(line)
        val second = first.label(EditorLabel(text = "old"))
        val branched = second.undo().label(EditorLabel(text = "new"))
        assertFalse(branched.canRedo)
        assertEquals(listOf("new"), branched.document.labels.map { it.text })
        assertEquals(first.document, branched.undo().document)
    }
    @Test fun cancelledOrEmptyTextCreatesNoHistoryAndBlankExistingTextDeletesIt() {
        val empty = PhotoEditorState()
        assertSame(empty, empty.label(EditorLabel(text = " \n")))
        val label = EditorLabel(text = "hello")
        val text = empty.label(label)
        val deleted = text.label(label.copy(text = ""))
        assertTrue(deleted.document.labels.isEmpty())
        assertEquals(text.document, deleted.undo().document)
        // A draft is separate from the document: cancelling cannot affect it.
        label.copy(text = "cancelled")
        assertEquals("hello", text.document.labels.single().text)
    }
    @Test fun undoClearRestoresVectorsWithoutMovingThem() {
        val state = PhotoEditorState().stroke(line).label(EditorLabel(text = "label"))
        assertEquals(state.document, state.clear().undo().document)
        assertFalse(state.clear().undo().redo().document.changed)
    }
    @Test fun eraseHitsMiddleOfSparseSegmentAndIsUndoable() {
        val state = PhotoEditorState().stroke(line)
        assertFalse(state.eraseAt(EditorPoint(.5f, .5f)).document.changed)
        assertEquals(state.document, state.eraseAt(EditorPoint(.5f, .5f)).undo().document)
        assertSame(state, state.eraseAt(EditorPoint(.1f, .9f)))
    }
    @Test fun eraseOnlyRemovesTopmostStrokeAndHandlesDot() {
        val state = PhotoEditorState().stroke(line).stroke(stroke(EditorPoint(.5f, .5f)))
        assertEquals(listOf(line), state.eraseAt(EditorPoint(.5f, .5f)).document.strokes)
    }
    @Test fun letterboxAndRotationMapSameSourcePoint() {
        val portrait = editorFit(1200, 800, 402f, 650f)
        val landscape = editorFit(1200, 800, 800f, 300f)
        val point = EditorPoint(.35f, .7f)
        for (rect in listOf(portrait, landscape)) {
            val mapped = rect.point(rect.left + point.x * rect.width, rect.top + point.y * rect.height)
            assertEquals(point.x, mapped.x, .00001f)
            assertEquals(point.y, mapped.y, .00001f)
            assertEquals(1.5f, rect.width / rect.height, .00001f)
        }
        assertFalse(portrait.contains(10f, 1f))
    }
    @Test fun transformClampsLabelToPhotoAndScaleRange() {
        val label = EditorLabel(text = "label", center = EditorPoint(.95f, .05f))
        val transformed = label.transformed(1f, -1f, 100f)
        assertEquals(EditorPoint(1f, 0f), transformed.center)
        assertEquals(6f, transformed.scale)
        assertEquals(.5f, transformed.transformed(0f, 0f, .001f).scale)
        assertEquals(label.id, transformed.id)
    }
    @Test fun emptyGesturesAndNoOpClearDoNotEnableUndo() {
        val state = PhotoEditorState()
        assertSame(state, state.stroke(stroke()))
        assertSame(state, state.clear())
        assertSame(state, state.undo())
        assertSame(state, state.redo())
    }
    @Test fun gestureCopiesItsPointsAndBoundsThem() {
        val points = mutableListOf(EditorPoint(-.1f, 2f))
        val state = PhotoEditorState().stroke(EditorStroke(points, -1, .01f))
        points.clear()
        assertEquals(listOf(EditorPoint(0f, 1f)), state.document.strokes.single().points)
    }
    @Test fun boundedHistoryDoesNotRemoveDrawing() {
        var state = PhotoEditorState()
        repeat(100) { state = state.stroke(line) }
        assertEquals(100, state.document.strokes.size)
        repeat(64) { state = state.undo() }
        assertFalse(state.canUndo)
        assertEquals(36, state.document.strokes.size)
    }

    @Test fun drawingAdmissionStopsBeforeAnOverLimitGestureAndUndoRestoresCapacity() {
        var state = PhotoEditorState()
        repeat(128) { state = state.stroke(line) }
        for (tool in listOf(EditorTool.Pen, EditorTool.Marker, EditorTool.Pencil)) assertFalse(state.canBeginStroke(tool))
        assertTrue(state.canBeginStroke(EditorTool.Eraser))
        assertSame(state, state.stroke(line))
        val undone = state.undo()
        assertTrue(undone.canBeginStroke(EditorTool.Pen))
        assertFalse(undone.redo().canBeginStroke(EditorTool.Pen))
    }

    @Test fun eraseAndClearRestoreDrawingWithoutEvictingOtherStrokes() {
        var state = PhotoEditorState()
        repeat(128) { state = state.stroke(line) }
        val erased = state.eraseAt(EditorPoint(.5f, .5f))
        assertTrue(erased.canDraw)
        assertEquals(state.document.strokes.dropLast(1), erased.document.strokes)
        assertFalse(erased.undo().canDraw)
        assertTrue(state.clear().canDraw)
    }

    @Test fun fullLabelCapacityStillAllowsEditingMovingAndDeletingExistingLabels() {
        var state = PhotoEditorState()
        repeat(64) { state = state.label(EditorLabel(text = "Label $it")) }
        assertFalse(state.canAddLabel)
        val draft = EditorLabel(text = "Do not lose this draft")
        assertFalse(state.canSaveLabel(draft))
        assertSame(state, state.label(draft))
        val first = state.document.labels.first()
        val edited = first.copy(text = "Edited").transformed(.1f, 0f, 1.2f)
        assertTrue(state.canSaveLabel(edited))
        state = state.label(edited)
        assertEquals(edited, state.document.labels.single { it.id == first.id })
        assertFalse(state.canAddLabel)
        assertTrue(state.label(edited.copy(text = "")).canAddLabel)
        assertTrue(state.clear().canAddLabel)
    }

    @Test fun sessionRejectsFullCapacityWithoutConsumingDraftOrChangingHistory() {
        val session = PhotoEditorSession()
        repeat(64) { session.trySaveLabel(EditorLabel(text = "Label $it")) }
        val before = session.state
        val draft = EditorLabel(text = "Keep me")
        assertFalse(session.trySaveLabel(draft))
        assertSame(before, session.state)
        assertEquals("Keep me", draft.text)
        session.state = before.undo()
        assertTrue(session.trySaveLabel(draft))
        assertEquals(draft, session.state.document.labels.single { it.id == draft.id })
    }
}

class PhotoEditorEraserTest {
    private val line = EditorStroke(listOf(EditorPoint(.1f, .1f), EditorPoint(.9f, .9f)), -1, .005f)

    @Test fun batchPreservesTopmostPerSampleAndDoesNotModifySnapshot() {
        val document = EditorDocument(strokes = listOf(line, line), labels = listOf(EditorLabel(text = "kept")))
        val once = eraseEditorGesture(document, listOf(EditorPoint(.5f, .5f)), 1f)
        assertEquals(listOf(line), once.strokes)
        val twice = eraseEditorGesture(document, listOf(EditorPoint(.5f, .5f), EditorPoint(.5f, .5f)), 1f)
        assertTrue(twice.strokes.isEmpty())
        assertSame(document.labels, twice.labels)
        assertEquals(2, document.strokes.size)
    }

    @Test fun boundsRejectMaximumSizeMissesWithoutScanningSegmentsForEverySample() {
        val points = List(4096) { EditorPoint(it / 4095f, .1f) }
        val document = EditorDocument(strokes = List(128) { EditorStroke(points, -1, .005f) })
        var checkpoints = 0
        val result = eraseEditorGesture(document, List(4096) { EditorPoint(.5f, .9f) }, 1f) { checkpoints++ }
        assertSame(document, result)
        // Bounding + admission work is linear in stored points and samples*strokes,
        // rather than billions of segment checks for a gesture outside the bounds.
        assertTrue(checkpoints < 6000)
    }

    @Test fun cancellationIsCheckedInsideLongCandidateStrokeScans() {
        val points = List(4096) { if (it % 2 == 0) EditorPoint(.1f, .1f) else EditorPoint(.9f, .9f) }
        val document = EditorDocument(strokes = List(128) { EditorStroke(points, -1, .005f) })
        var checkpoints = 0
        try {
            eraseEditorGesture(document, List(4096) { EditorPoint(.1f, .9f) }, 1f) {
                if (++checkpoints == 600) throw CancellationException("User cancelled")
            }
            fail("Expected cooperative cancellation")
        } catch (_: CancellationException) { }
        assertEquals(128, document.strokes.size)
    }

    @Test fun physicalAspectAndDotsUseSameHitGeometryAsSinglePointEraser() {
        val dot = EditorStroke(listOf(EditorPoint(.5f, .5f)), -1, .005f)
        val document = EditorDocument(strokes = listOf(dot))
        assertTrue(eraseEditorGesture(document, listOf(EditorPoint(.5f, .52f)), 1f).strokes.isEmpty())
        assertSame(document, eraseEditorGesture(document, listOf(EditorPoint(.5f, .52f)), 2f))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoEditorSessionTest {
    private val line = EditorStroke(listOf(EditorPoint(.1f, .1f), EditorPoint(.9f, .9f)), -1, .005f)

    @Test fun exportIdentitySurvivesUnchangedStateButChangesWithEditsIncludingWorkerEdits() = runTest {
        val session = PhotoEditorSession(StandardTestDispatcher(testScheduler), this)
        val initial = session.exportToken
        session.state = session.state.clear()
        assertEquals(initial, session.exportToken)
        session.state = session.state.stroke(line)
        val drawn = session.exportToken
        assertNotEquals(initial, drawn)
        session.state = session.state.copy()
        assertEquals(drawn, session.exportToken)
        session.erase(listOf(EditorPoint(.5f, .5f)), 1f)!!.join()
        assertNotEquals(drawn, session.exportToken)
        val erased = session.exportToken
        session.state = session.state.undo()
        assertNotEquals(erased, session.exportToken)
    }

    @Test fun workerAppliesWholeGestureAsOneUndoStepAndOwnsSampleSnapshot() = runTest {
        val session = PhotoEditorSession(StandardTestDispatcher(testScheduler), this)
        val before = PhotoEditorState().stroke(line).stroke(line)
        session.state = before
        val points = mutableListOf(EditorPoint(.5f, .5f), EditorPoint(.5f, .5f))
        val job = session.erase(points, 1f)!!
        points.clear()
        assertTrue(session.erasing)
        assertSame(before, session.state)
        job.join()
        assertFalse(session.erasing)
        assertTrue(session.state.document.strokes.isEmpty())
        assertEquals(before.past.size + 1, session.state.past.size)
        assertEquals(before.document, session.state.undo().document)
    }

    @Test fun editingOrCancelBeforeWorkerDispatchNeverAppliesAnObsoleteResult() = runTest {
        val session = PhotoEditorSession(StandardTestDispatcher(testScheduler), this)
        val before = PhotoEditorState().stroke(line)
        session.state = before
        val first = session.erase(listOf(EditorPoint(.5f, .5f)), 1f)!!
        val edited = before.label(EditorLabel(text = "new edit"))
        session.state = edited
        first.join()
        assertSame(edited, session.state)
        assertFalse(session.erasing)
        val second = session.erase(listOf(EditorPoint(.5f, .5f)), 1f)!!
        session.cancelErasing(); second.join()
        assertSame(edited, session.state)
    }

    @Test fun boundedGestureRetainsTheReleaseLocation() = runTest {
        val session = PhotoEditorSession(StandardTestDispatcher(testScheduler), this)
        session.state = PhotoEditorState().stroke(line)
        val samples = List(4096) { EditorPoint(0f, 1f) } + EditorPoint(.5f, .5f)
        session.erase(samples, 1f)!!.join()
        assertTrue(session.state.document.strokes.isEmpty())
    }

    @Test fun realWorkerKeepsCallerResponsiveAndCancellationPreservesDrawing() = runTest {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val started = CountDownLatch(1)
            var workerThread = -1L
            val callerThread = Thread.currentThread().id
            val points = List(4096) { if (it % 2 == 0) EditorPoint(.1f, .1f) else EditorPoint(.9f, .9f) }
            val before = PhotoEditorState(EditorDocument(strokes = List(128) { EditorStroke(points, -1, .005f) }))
            val session = PhotoEditorSession(worker, this) { doc, samples, aspect, check ->
                workerThread = Thread.currentThread().id
                eraseEditorGesture(doc, samples, aspect) { check(); started.countDown() }
            }
            session.state = before
            val job = session.erase(List(4096) { EditorPoint(.1f, .9f) }, 1f)!!
            runCurrent()
            withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            assertNotEquals(callerThread, workerThread)
            assertEquals("UI heartbeat", async { "UI heartbeat" }.await())
            session.cancelErasing(); job.join()
            assertFalse(session.erasing)
            assertSame(before, session.state)
        }
    }

    @Test fun workerFailureRetainsDrawingAndCanBeDismissed() = runTest {
        val session = PhotoEditorSession(StandardTestDispatcher(testScheduler), this) { _, _, _, _ ->
            throw IllegalStateException("Worker failed")
        }
        val before = PhotoEditorState().stroke(line)
        session.state = before
        session.erase(listOf(EditorPoint(.5f, .5f)), 1f)!!.join()
        assertSame(before, session.state)
        assertFalse(session.erasing)
        assertTrue(session.eraseFailed)
        session.cancelErasing()
        assertFalse(session.eraseFailed)
    }
}
