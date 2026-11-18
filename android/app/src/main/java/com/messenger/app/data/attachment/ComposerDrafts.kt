package com.messenger.app.data.attachment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** One journal snapshot. Edits always apply to the latest stored value, never a UI copy. */
data class ComposerDrafts(val photos: List<String> = emptyList(), val files: List<String> = emptyList(), val direct: Set<String> = emptySet()) {
    val all: List<String> get() = (photos + files).distinct()
    fun edit(addPhotos: List<String> = emptyList(), addFiles: List<String> = emptyList(), remove: Set<String> = emptySet(), addDirect: Set<String> = emptySet()): ComposerDrafts {
        val transport = (direct + addDirect) - remove
        val photoSources = (photos + addPhotos).distinct()
        val nextPhotos = photoSources.filterNot { it in remove || it in transport }
        val nextFiles = (files + addFiles + photoSources.filter { it in transport }).distinct().filterNot { it in remove || it in nextPhotos }
        return ComposerDrafts(nextPhotos, nextFiles, transport.intersect((nextPhotos + nextFiles).toSet()))
    }
    companion object {
        const val DIRECT_KEY = "b05-direct-attachment-intents-v1"
        fun decode(photos: String?, files: String?, legacyFile: String?, direct: String? = null): ComposerDrafts {
            fun list(raw: String?) = raw?.let { Json.parseToJsonElement(it).jsonArray.map { entry -> entry.jsonPrimitive.content } }.orEmpty()
            return ComposerDrafts().edit(list(photos), list(files) + listOfNotNull(legacyFile), addDirect = list(direct).toSet())
        }
        fun encode(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()
    }
}

/** Rendering, readiness and Send consume exactly this projection, even before Room reloads. */
data class ComposerTraySnapshot(val ordered: List<String>, val photos: List<String>, val files: List<String>, val importing: Set<String>, val unavailable: Set<String> = emptySet()) {
    val ready: Boolean get() = importing.isEmpty()
    val sendablePhotos: List<String> get() = photos.filterNot { it in unavailable }
    val sendableFiles: List<String> get() = files.filterNot { it in unavailable }
    companion object {
        fun create(restored: ComposerDrafts, items: List<MediaPreparationCoordinator.ImportItem>, forgotten: Set<String>, unavailable: Set<String> = emptySet()): ComposerTraySnapshot {
            val restoredPhotos = restored.photos.filterNot { it in forgotten }
            val restoredFiles = restored.files.filterNot { it in forgotten }
            val staged = items.mapNotNull { it.staged }.toSet()
            val order = ((restoredPhotos + restoredFiles).filterNot { it in staged } + items.map { it.staged ?: it.source }).distinct()
            val photoSet = restoredPhotos.toSet() + items.filterNot { it.asFile }.map { it.staged ?: it.source }
            return ComposerTraySnapshot(order, order.filter { it in photoSet }, order.filterNot { it in photoSet },
                items.filter { it.staged == null && it.source !in unavailable }.map { it.source }.toSet(), unavailable)
        }
    }
}
