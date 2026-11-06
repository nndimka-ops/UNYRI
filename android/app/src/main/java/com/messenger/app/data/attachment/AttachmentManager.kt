package com.messenger.app.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import com.messenger.app.data.api.AttachmentsApi
import com.messenger.app.data.local.attachment.AttachmentDao
import com.messenger.app.data.local.attachment.AttachmentEntity
import com.messenger.app.domain.repository.AuthRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/** Upload/download UX stages (Этап H п.16) - deliberately coarser than the
 *  Room `uploadState` column, just enough for a progress label in the UI. */
enum class AttachmentStage { PREPARING, ENCRYPTING, UPLOADING, SENDING, SENT, FAILED }

/** Narrow seam so the media gallery ViewModel can be unit-tested with a fake
 *  downloader (M.2 BUG 4 regression test). */
interface AttachmentDownloader {
    suspend fun download(attachmentId: String): File
}

/** Throttles a 0..100 progress callback so a 100MB file (1600+ 64KB
 *  chunks) doesn't flood Room/UI with an update per chunk (Этап H.2 п.10)
 *  - only fires on an actual percentage-point change. */
private class ProgressThrottle(private val onProgress: (Int) -> Unit) {
    private var lastPercent = -1
    fun update(processed: Long, total: Long) {
        if (total <= 0) return
        val percent = ((processed * 100) / total).toInt().coerceIn(0, 100)
        if (percent != lastPercent) {
            lastPercent = percent
            onProgress(percent)
        }
    }
}

/**
 * The ONE place every attachment (photo/file/voice) is prepared, encrypted,
 * uploaded, downloaded, verified, and decrypted - never spread across
 * ViewModels (Этап H п.15). Local plaintext caches live under
 * `context.cacheDir/attachments` (app-private, never the public
 * Gallery/Downloads - Этап H п.19) until the user explicitly saves/shares.
 */
class AttachmentManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val attachmentsApi: AttachmentsApi,
    private val authRepository: AuthRepository,
    private val dao: AttachmentDao,
    private val isChatDeleted: suspend (String) -> Boolean = { false },
) : AttachmentDownloader {
    // newBuilder shares the original dispatcher and connection pool: no duplicate worker queue.
    private val automaticDownloadHttpClient = httpClient.newBuilder().addInterceptor(automaticDownloadPolicyInterceptor).build()
    var onCacheWritten: () -> Unit = {}
    private val downloads = AttachmentDownloadGate()
    private val chatWork = ChatMediaWork()
    suspend fun cancelChats(epoch: Int, ids: List<String>) = chatWork.close(epoch, ids)
    private suspend fun <T> inChat(chatId: String, block: suspend () -> T): T =
        chatWork.run(authRepository.currentSessionEpoch(), chatId, { !isChatDeleted(chatId) }, block)

    suspend fun discardDeletedChatCache(rows: List<AttachmentEntity>) {
        val epoch = authRepository.currentSessionEpoch()
        cacheMutation(epoch) {
            val retained = dao.allWithLocalPath().mapNotNull { it.localPath }.map { File(it).canonicalPath }.toSet()
            for (row in rows) {
                File(cacheDir(), "upload-${row.attachmentId}.enc").delete()
                row.localPath?.let { path ->
                    val file = File(path).canonicalFile
                    if (isOwnedMediaFile(file) && file.path !in retained) file.delete()
                }
            }
            Unit
        }
    }
    suspend fun discardDraft(path: String, references: Set<String>) = withContext(Dispatchers.IO) {
        if (discardUnreferencedDraft(File(context.noBackupFilesDir, "media-drafts"), File(path), references)) {
            previewFile(Uri.fromFile(File(path))).delete()
        }
    }
    private fun previewFile(uri: Uri): File {
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "attachments/previews/$id")
    }
    suspend fun discardPendingUpload(clientMessageId: String) {
        val epoch = authRepository.currentSessionEpoch()
        val row = dao.findByClientMessageId(clientMessageId) ?: return
        cacheMutation(epoch) {
            File(cacheDir(), "upload-${row.attachmentId}.enc").delete()
            dao.updateUploadState(row.attachmentId, "cancelled", 0)
            dao.clearLocalCache(row.attachmentId)
            row.localPath?.let { path ->
                val file = File(path).canonicalFile
                if (isOwnedMediaFile(file) && dao.allWithLocalPath().none { it.localPath == path }) file.delete()
            }
            Unit
        }
    }
    suspend fun resumeUpload(clientMessageId: String): Boolean = withContext(Dispatchers.IO) {
        val row = dao.findByClientMessageId(clientMessageId) ?: return@withContext false
        inChat(row.chatId) {
        if (row.uploadState == "uploaded") return@inChat true
        val file = File(cacheDir(), "upload-${row.attachmentId}.enc")
        if (!file.exists()) return@inChat false
        try { uploadMultipart(row.attachmentId, file, authRepository.currentSessionEpoch()) }
        catch (error: com.messenger.app.data.api.AuthApiException.Api) {
            if (error.code != "not_found") throw error
            file.delete() // Expired server intent: regenerate from the durable draft.
            return@inChat false
        } catch (error: AttachmentIntegrityException) {
            file.delete()
            return@inChat false
        }
        file.delete()
        true
        }
    }
    suspend fun stageDraft(uri: Uri, maxSourceBytes: Long? = null): Uri {
        require(maxSourceBytes == null || maxSourceBytes > 0) { "Source limit must be positive" }
        val epoch = authRepository.currentSessionEpoch()
        return transferPreparedResource(Dispatchers.IO, create = {
            authRepository.withWorkspaceWrite(epoch) {
                val directory = File(context.noBackupFilesDir, "media-drafts/${UUID.randomUUID()}").apply { mkdirs() }
                val name = runCatching { queryNameAndSize(uri).first }.getOrNull()?.let { File(it).name } ?: uri.lastPathSegment ?: "attachment"
                val safeName = File(name).name.take(180).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
                val target = File(directory, safeName)
                try { copyUriToFile(uri, target, boundedDraftSourceLimit(if (isVideo(uri)) MAX_VIDEO_SOURCE_BYTES else MAX_FILE_ENCRYPTED_BYTES, maxSourceBytes)); Uri.fromFile(target) }
                catch (error: Exception) { directory.deleteRecursively(); throw error }
            } ?: throw kotlinx.coroutines.CancellationException("Account changed")
        }, discard = { unpublished ->
            authRepository.withWorkspaceWrite(epoch) {
                // This result never reached a caller and cannot have a DB owner.
                // On an account change, the account purge owns cleanup instead.
                unpublished.path?.let { path -> File(path).let { file -> file.delete(); file.parentFile?.delete() } }
            }
        })
    }
    fun isVideo(uri: Uri): Boolean = context.contentResolver.getType(uri)?.startsWith("video/") == true || uri.lastPathSegment?.substringAfterLast('.')?.lowercase() in setOf("mp4", "mov", "m4v", "webm")

    suspend fun draftPreviewFile(uri: Uri): File = withContext(Dispatchers.IO) {
        val epoch = authRepository.currentSessionEpoch()
        authRepository.withWorkspaceWrite(epoch) {
            val directory = File(context.cacheDir, "attachments/previews").apply { mkdirs() }
            val target = previewFile(uri)
            if (target.isFile) return@withWorkspaceWrite target
            val temporary = File(directory, "${UUID.randomUUID()}.building")
            try {
                copyUriToFile(uri, temporary, if (isVideo(uri)) MAX_VIDEO_SOURCE_BYTES else MAX_FILE_ENCRYPTED_BYTES)
                check(temporary.renameTo(target)) { "Cannot publish preview" }
                target
            } finally { temporary.delete() }
        } ?: throw kotlinx.coroutines.CancellationException("Account changed")
    }

    private fun cacheDir(): File = File(context.cacheDir, "attachments").apply { mkdirs() }
    private fun isOwnedMediaFile(file: File): Boolean = file.parentFile == cacheDir().canonicalFile ||
        (file.name.startsWith("restored-") && file.toPath().startsWith(File(context.filesDir, "history-restoration").canonicalFile.toPath()))

    private suspend fun <T: Any> cacheMutation(epoch: Int, block: suspend () -> T): T =
        authRepository.withMediaWrite(epoch, block)
            ?: throw AttachmentCancelledException("session changed before cache write")

    /** Photo: decode -> downscale (never upscale) -> orientation-correct ->
     *  re-encode as a fresh EXIF-free JPEG (Этап H п.20/21), then the common
     *  encrypt/upload pipeline. */
    suspend fun prepareAndUploadImage(
        uri: Uri,
        chatId: String,
        clientMessageId: String,
        highQuality: Boolean = false,
        onStage: (AttachmentStage) -> Unit = {},
    ): AttachmentMetadataDto = inChat(chatId) {
        val epoch = authRepository.currentSessionEpoch()
        onStage(AttachmentStage.PREPARING)
        val plaintextFile = File(cacheDir(), "${UUID.randomUUID()}.jpg")
        val processed = cacheMutation(epoch) {
            try { ImageProcessor.process(context, uri, plaintextFile, highQuality) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { throw MediaPreparationException(error) }
        }
        if (processed.file.length() > MAX_IMAGE_ENCRYPTED_BYTES) {
            throw AttachmentTooLargeException("Photo exceeds the ${MAX_IMAGE_ENCRYPTED_BYTES / (1024 * 1024)}MB limit after compression.")
        }
        encryptUploadComplete(
            epoch = epoch,
            plaintextFile = processed.file,
            mediaType = AttachmentMetadataDto.MEDIA_TYPE_IMAGE,
            mimeType = processed.mimeType,
            filename = null,
            image = ImageInfoDto(processed.width, processed.height),
            voice = null,
            thumbnailBase64 = processed.thumbnailBase64,
            clientMessageId = clientMessageId,
            chatId = chatId,
            onStage = onStage,
        )
    }

    suspend fun prepareAndUploadVideo(uri: Uri, chatId: String, clientMessageId: String, highQuality: Boolean = false): AttachmentMetadataDto = inChat(chatId) {
        val epoch = authRepository.currentSessionEpoch()
        val target = File(cacheDir(), "${UUID.randomUUID()}.mp4")
        sessionMediaWork(authRepository.authState, { authRepository.currentSessionEpoch() == epoch }) { cacheMutation(epoch) {
            try { VideoProcessor.convert(context, uri, target, highQuality) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { throw MediaPreparationException(error) }
        } }
        try { prepareAndUploadFile(Uri.fromFile(target), chatId, clientMessageId) }
        finally { cacheMutation(epoch) { target.delete() } }
    }

    /** Generic file (Storage Access Framework `ACTION_OPEN_DOCUMENT` result). */
    suspend fun prepareAndUploadFile(
        uri: Uri,
        chatId: String,
        clientMessageId: String,
        onStage: (AttachmentStage) -> Unit = {},
    ): AttachmentMetadataDto = inChat(chatId) {
        val epoch = authRepository.currentSessionEpoch()
        onStage(AttachmentStage.PREPARING)
        val (rawName, declaredSize) = queryNameAndSize(uri)
        if (declaredSize in 0..MAX_FILE_ENCRYPTED_BYTES) {
            // fine - checked again below by the real copied bytes regardless
        } else if (declaredSize > MAX_FILE_ENCRYPTED_BYTES) {
            throw AttachmentTooLargeException("File exceeds the ${MAX_FILE_ENCRYPTED_BYTES / (1024 * 1024)}MB limit.")
        }
        val suggestedMime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(rawName?.substringAfterLast('.', "")?.lowercase())
            ?: "application/octet-stream"
        val mimeType = context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(32); var size = 0
            while (size < header.size) { val n = input.read(header, size, header.size-size); if (n <= 0) break; size += n }
            MediaFileType.detect(header.copyOf(size), suggestedMime)
        } ?: suggestedMime
        val plaintextFile = File(cacheDir(), "${UUID.randomUUID()}.${MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"}")
        cacheMutation(epoch) { copyUriToFile(uri, plaintextFile) }
        if (plaintextFile.length() > MAX_FILE_ENCRYPTED_BYTES) {
            plaintextFile.delete()
            throw AttachmentTooLargeException("File exceeds the ${MAX_FILE_ENCRYPTED_BYTES / (1024 * 1024)}MB limit.")
        }
        val preview = if (mimeType.startsWith("video/")) VideoPreview.read(plaintextFile) else null
        val imageThumbnail = if (mimeType.startsWith("image/")) ImageProcessor.originalThumbnail(plaintextFile) else null
        coroutineContext.ensureActive()
        encryptUploadComplete(
            epoch = epoch,
            plaintextFile = plaintextFile,
            mediaType = AttachmentMetadataDto.MEDIA_TYPE_FILE,
            mimeType = mimeType,
            filename = rawName,
            image = preview?.image,
            voice = null,
            thumbnailBase64 = imageThumbnail ?: preview?.thumbnail,
            clientMessageId = clientMessageId,
            chatId = chatId,
            onStage = onStage,
            video = preview?.durationMs?.let { VideoInfoDto(it) },
        )
    }

    /** Voice: [recordedFile] is already-encoded AAC/M4A (see VoiceRecorder) -
     *  just the common encrypt/upload pipeline on top. */
    suspend fun prepareAndUploadVoice(
        recordedFile: File,
        durationMs: Long,
        chatId: String,
        clientMessageId: String,
        onStage: (AttachmentStage) -> Unit = {},
    ): AttachmentMetadataDto = inChat(chatId) {
        val epoch = authRepository.currentSessionEpoch()
        onStage(AttachmentStage.PREPARING)
        if (recordedFile.length() > MAX_VOICE_ENCRYPTED_BYTES) {
            throw AttachmentTooLargeException("Voice message exceeds the ${MAX_VOICE_ENCRYPTED_BYTES / (1024 * 1024)}MB limit.")
        }
        encryptUploadComplete(
            epoch = epoch,
            plaintextFile = recordedFile,
            mediaType = AttachmentMetadataDto.MEDIA_TYPE_VOICE,
            mimeType = "audio/mp4",
            filename = null,
            image = null,
            voice = VoiceInfoDto(durationMs),
            thumbnailBase64 = null,
            clientMessageId = clientMessageId,
            chatId = chatId,
            onStage = onStage,
        )
    }

    /** Этап I forward: re-encrypts/reuploads the ALREADY-DECRYPTED local
     *  plaintext cache at [localPath] as a brand-new attachment in
     *  [chatId]/[clientMessageId] - never a server-side pointer/reference
     *  to the original encrypted blob (Этап I п.22/24). Chosen specifically
     *  because `attachments.message_id` is a strict one-to-one association
     *  server-side; reusing the same ciphertext object for a second message
     *  would need a schema/authorization-model change, whereas re-running
     *  the exact same encrypt/upload pipeline this method already uses for
     *  every other attachment needs none. Read-only on the source file -
     *  never mutates/deletes it. */
    suspend fun forwardAttachment(
        localPath: String,
        mediaType: String,
        mimeType: String,
        filename: String?,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        thumbnailBase64: String?,
        chatId: String,
        clientMessageId: String,
        onStage: (AttachmentStage) -> Unit = {},
    ): AttachmentMetadataDto = inChat(chatId) {
        val epoch = authRepository.currentSessionEpoch()
        val plaintextFile = File(localPath)
        if (!plaintextFile.exists()) throw IllegalStateException("Cached attachment file is missing.")
        encryptUploadComplete(
            epoch = epoch,
            plaintextFile = plaintextFile,
            mediaType = mediaType,
            mimeType = mimeType,
            filename = filename,
            image = if (width != null && height != null) ImageInfoDto(width, height) else null,
            voice = if (mediaType == "voice") durationMs?.let { VoiceInfoDto(it) } else null,
            video = if (mimeType.startsWith("video/")) durationMs?.let { VideoInfoDto(it) } else null,
            thumbnailBase64 = thumbnailBase64,
            clientMessageId = clientMessageId,
            chatId = chatId,
            onStage = onStage,
        )
    }

    private suspend fun encryptUploadComplete(
        epoch: Int,
        plaintextFile: File,
        mediaType: String,
        mimeType: String,
        filename: String?,
        image: ImageInfoDto?,
        voice: VoiceInfoDto?,
        thumbnailBase64: String?,
        clientMessageId: String,
        chatId: String,
        onStage: (AttachmentStage) -> Unit,
        video: VideoInfoDto? = null,
    ): AttachmentMetadataDto {
        val job = coroutineContext[kotlinx.coroutines.Job]
        fun isCancelledNow() = job?.isActive == false || authRepository.currentSessionEpoch() != epoch
        suspend fun checkNotCancelled() {
            coroutineContext.ensureActive()
            if (authRepository.currentSessionEpoch() != epoch) {
                throw AttachmentCancelledException("session changed mid-upload")
            }
        }

        checkNotCancelled()
        val prior = dao.findByClientMessageId(clientMessageId)
        if (prior != null) {
            if (prior.uploadState == "uploaded") return metadata(prior)
            val savedCiphertext = File(cacheDir(), "upload-${prior.attachmentId}.enc")
            if (savedCiphertext.exists() && savedCiphertext.length() == prior.encryptedSize) {
                onStage(AttachmentStage.UPLOADING)
                uploadMultipart(prior.attachmentId, savedCiphertext, epoch)
                checkNotCancelled()
                savedCiphertext.delete()
                return metadata(prior)
            }
        }
        val maximum = if (mediaType == AttachmentMetadataDto.MEDIA_TYPE_FILE) MAX_FILE_ENCRYPTED_BYTES else MAX_IMAGE_ENCRYPTED_BYTES
        if (plaintextFile.length() > maximum) throw AttachmentTooLargeException("File exceeds the allowed size.")
        requireAttachmentSpace(cacheDir(), plaintextFile.length() + 4096)
        onStage(AttachmentStage.ENCRYPTING)
        // New attachments always use v2 Streaming AEAD (Этап H.2) - v1
        // AES-GCM remains only as a decrypt-only legacy path (see download()).
        val keysetHandle = AttachmentCryptoV2.generateKeysetHandle()
        // Written to a `.building` temp name and only renamed to its final
        // `.enc` name once encryption fully succeeds - never leaves a
        // half-encrypted ciphertext file under its "real" name (Этап H.2 п.8/9).
        val ciphertextBuilding = File(cacheDir(), "${UUID.randomUUID()}.enc.building")
        // Encryption has no Room row to persist progress into yet (the
        // attachmentId is only assigned by the backend's `/attachments`
        // create call below) - encryption is CPU-bound and typically much
        // faster than the network-bound upload/download phases, where
        // progress is actually persisted and observable (see ProgressThrottle
        // usages further down). isCancelled is still honored here.
        val progress = ProgressThrottle { _ -> }
        val sha256 = try { cacheMutation(epoch) {
            AttachmentCryptoV2.encryptFile(
                plaintextFile,
                ciphertextBuilding,
                keysetHandle,
                onBytesProcessed = progress::update,
                isCancelled = ::isCancelledNow,
            )
        }
        } catch (e: Exception) {
            ciphertextBuilding.delete()
            onStage(AttachmentStage.FAILED)
            throw e
        }
        val ciphertextFile = File(cacheDir(), "${UUID.randomUUID()}.enc")
        cacheMutation(epoch) {
        if (!ciphertextBuilding.renameTo(ciphertextFile)) {
            ciphertextBuilding.copyTo(ciphertextFile, overwrite = true)
            ciphertextBuilding.delete()
        }
        }
        val encryptedSize = ciphertextFile.length()
        val plaintextSize = plaintextFile.length()

        val token = authRepository.currentAccessToken() ?: run {
            ciphertextFile.delete()
            onStage(AttachmentStage.FAILED)
            error("Not authenticated.")
        }

        try {
            checkNotCancelled()
            onStage(AttachmentStage.UPLOADING)
            val clientAttachmentId = UUID.randomUUID().toString()
            val intent = attachmentsApi.create(token, clientAttachmentId, encryptedSize)
            val keysetBase64 = b64(AttachmentCryptoV2.serializeKeysetHandle(keysetHandle))

            // P0 hardening: the row CREATION is gated on the session epoch so
            // a delayed old-account upload can never insert an attachment row
            // after the logout Room clear (same mutex as AuthRepository.clearRoom).
            val created = authRepository.withSessionWrite(epoch) {
                dao.upsert(
                    AttachmentEntity(
                        attachmentId = intent.attachmentId,
                        clientMessageId = clientMessageId,
                        chatId = chatId,
                        mediaType = mediaType,
                        mimeType = mimeType,
                        filename = filename,
                        size = plaintextSize,
                        width = image?.width,
                        height = image?.height,
                        durationMs = video?.durationMs ?: voice?.durationMs,
                        thumbnailBase64 = thumbnailBase64,
                        localPath = plaintextFile.absolutePath,
                        downloadState = "downloaded",
                        uploadState = "uploading",
                        objectKey = intent.objectKey,
                        encryptedSize = encryptedSize,
                        encryptionVersion = EncryptionInfoDto.VERSION_V2_STREAMING_AEAD,
                        keyBase64 = null,
                        nonceBase64 = null,
                        keysetBase64 = keysetBase64,
                        sha256Base64 = b64(sha256),
                    ),
                )
                true
            }
            if (created != true) {
                throw AttachmentCancelledException("session changed before attachment persist")
            }

            val resumable = File(cacheDir(), "upload-${intent.attachmentId}.enc")
            cacheMutation(epoch) {
                if (!ciphertextFile.renameTo(resumable)) {
                    ciphertextFile.copyTo(resumable, overwrite = true)
                    ciphertextFile.delete()
                }
            }
            uploadMultipart(intent.attachmentId, resumable, epoch)
            checkNotCancelled()
            resumable.delete()

            onStage(AttachmentStage.SENDING)
            return AttachmentMetadataDto(
                attachmentId = intent.attachmentId,
                mediaType = mediaType,
                objectKey = intent.objectKey,
                size = plaintextSize,
                encryptedSize = encryptedSize,
                mimeType = mimeType,
                encryption = EncryptionInfoDto(
                    version = EncryptionInfoDto.VERSION_V2_STREAMING_AEAD,
                    keyset = keysetBase64,
                    sha256 = b64(sha256),
                ),
                filename = filename,
                image = image,
                voice = voice,
                video = video,
                thumbnail = thumbnailBase64,
            )
        } catch (e: Exception) {
            onStage(AttachmentStage.FAILED)
            throw e
        } finally {
            ciphertextFile.delete()
        }
    }

    private fun metadata(row: AttachmentEntity) = AttachmentMetadataDto(
        attachmentId = row.attachmentId, mediaType = row.mediaType, objectKey = row.objectKey,
        size = row.size, encryptedSize = row.encryptedSize, mimeType = row.mimeType,
        encryption = EncryptionInfoDto(row.encryptionVersion, row.keyBase64, row.nonceBase64, row.keysetBase64, row.sha256Base64),
        filename = row.filename, image = if (row.width != null && row.height != null) ImageInfoDto(row.width, row.height) else null,
        voice = if (row.mediaType == "voice") row.durationMs?.let { VoiceInfoDto(it) } else null, thumbnail = row.thumbnailBase64,
        video = if (row.mimeType.startsWith("video/")) row.durationMs?.let { VideoInfoDto(it) } else null)

    private suspend fun uploadMultipart(id: String, file: File, epoch: Int) = withContext(Dispatchers.IO) {
        suspend fun token(): String {
            coroutineContext.ensureActive()
            if (authRepository.currentSessionEpoch() != epoch) throw AttachmentCancelledException()
            return authRepository.currentAccessToken() ?: error("Not authenticated.")
        }
        val row = dao.findByAttachmentId(id) ?: throw IOException("Unknown attachment")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        if (b64(digest.digest()) != row.sha256Base64) throw AttachmentIntegrityException("Pending attachment failed integrity verification")
        val upload = attachmentsApi.multipart(token(), id)
        if (!upload.complete) {
            if (upload.partSize != 5L * 1024 * 1024 || upload.parts.size > 21 ||
                upload.parts.withIndex().any { (i,p) -> p.number != i+1 || p.size <= 0 || p.size != minOf(upload.partSize, file.length() - i * upload.partSize) } ||
                upload.parts.sumOf { it.size } != file.length()) {
                throw IOException("Invalid multipart upload plan")
            }
            val activeContext = coroutineContext
            var completed = upload.parts.filter { it.complete }.sumOf { it.size }
            for (part in upload.parts.filterNot { it.complete }) {
                token()
                val body = AttachmentUploadBody(file, { activeContext.ensureActive() }, { pct ->
                    val progress = ((completed + part.size * pct / 100) * 100 / file.length()).toInt().coerceIn(0,99)
                    kotlinx.coroutines.runBlocking { authRepository.withSessionWrite(epoch) { dao.updateUploadState(id,"uploading",progress) } }
                }, (part.number - 1) * upload.partSize, part.size)
                val etag = httpClient.newCall(Request.Builder().url(part.uploadUrl).put(body).build()).withAttachmentResponse { response ->
                    if (!response.isSuccessful) throw IOException("Upload failed: HTTP ${response.code}")
                    response.header("ETag") ?: throw IOException("Missing upload receipt")
                }
                attachmentsApi.acknowledgePart(token(), id, part.number, etag)
                completed += part.size
            }
        }
        attachmentsApi.complete(token(), id)
        authRepository.withSessionWrite(epoch) { dao.updateUploadState(id, "uploaded", 100) }
    }

    /** Convenience overload for callers (ViewModels) that only hold the
     *  domain-layer id, never the raw crypto material - that stays entirely
     *  inside this data-layer class and its Room row. */
    suspend fun autoDownload(attachmentId: String): File? {
        val epoch = authRepository.currentSessionEpoch()
        val row = cacheMutation(epoch) { dao.findByAttachmentId(attachmentId) ?: error("Unknown attachment") }
        val preferences = MediaDownloadPreferences(context)
        fun requireInitialPolicy() { if (!preferences.allows(row.mediaType,row.mimeType,row.size)) throw AutomaticDownloadDeferred() }
        return try { performDownload(attachmentId, {}, automatic=true, beforeStart=::requireInitialPolicy, epochAtStart=epoch) }
        catch (_: AutomaticDownloadDeferred) { null }
    }

    suspend fun download(attachmentId: String, onStage: (AttachmentStage) -> Unit = {}): File =
        performDownload(attachmentId,onStage)

    private suspend fun performDownload(attachmentId: String, onStage: (AttachmentStage) -> Unit, automatic: Boolean = false, beforeStart: () -> Unit = {}, epochAtStart: Int = authRepository.currentSessionEpoch()): File {
        val epoch = epochAtStart
        val lease = com.messenger.app.data.local.MediaCacheUse.acquire(attachmentId = attachmentId)
        try { return downloads.run("$epoch:$attachmentId", beforeStart=beforeStart) {
            val entity = cacheMutation(epoch) { dao.findByAttachmentId(attachmentId) ?: error("Unknown attachment") }
            try {
                inChat(entity.chatId) { download(entity,epoch,onStage,automatic=automatic) {
                    // Fresh metadata and network/preferences are re-evaluated after
                    // every queue/auth wait and at the actual HTTP start.
                    if (automatic && !MediaDownloadPreferences(context).allows(entity.mediaType,entity.mimeType,entity.size)) throw AutomaticDownloadDeferred()
                } }
            } catch (deferred: AutomaticDownloadDeferred) {
                // The attachment gate is still held: a queued manual consumer
                // cannot have started its own download state yet.
                cacheMutation(epoch) {
                    if (dao.findByAttachmentId(attachmentId)?.downloadState == "downloading") dao.updateDownloadState(attachmentId,"not_downloaded",0)
                }
                throw deferred
            }
        } } finally { lease.close() }
    }

    /** [AttachmentDownloader] seam (M.2): single-arg form used by the media
     *  gallery ViewModel - delegates to the progress-callback overload. */
    override suspend fun download(attachmentId: String): File = download(attachmentId, {})

    /** Downloads (if not already cached locally), verifies the ciphertext
     *  SHA-256, then decrypts - never returns a partially-verified file
     *  (Этап H п.11/42). Safe to call repeatedly - an already-downloaded
     *  attachment is served straight from the local cache. Dispatches to
     *  the v1 (legacy AES-GCM) or v2 (Tink Streaming AEAD) decrypt path
     *  based on [AttachmentEntity.encryptionVersion] - see Этап H.2 п.4. */
    private suspend fun download(attachment: AttachmentEntity, epoch: Int, onStage: (AttachmentStage) -> Unit, automatic: Boolean = false, beforeRequest: () -> Unit = {}): File = withContext(Dispatchers.IO) {
        if (authRepository.currentSessionEpoch() != epoch) throw AttachmentCancelledException()
        attachment.localPath?.let { cachedPath ->
            val cached = File(cachedPath)
            if (cached.exists()) { cached.setLastModified(System.currentTimeMillis()); return@withContext cached }
        }

        val job = coroutineContext[kotlinx.coroutines.Job]
        fun isCancelled() = job?.isActive == false || authRepository.currentSessionEpoch() != epoch

        val attachmentId = attachment.attachmentId
        if (attachment.encryptedSize < 0 || attachment.encryptedSize > MAX_FILE_ENCRYPTED_BYTES + 4096 ||
            attachment.size < 0 || attachment.size > MAX_FILE_ENCRYPTED_BYTES) {
            throw AttachmentTooLargeException("File exceeds the allowed size.")
        }
        beforeRequest()
        requireAttachmentSpace(cacheDir(), attachment.encryptedSize + attachment.size)
        dao.updateDownloadState(attachmentId, "downloading", 0)
        val token = authRepository.currentAccessToken() ?: run {
            dao.updateDownloadState(attachmentId, "failed", 0)
            error("Not authenticated.")
        }
        if (isCancelled()) throw AttachmentCancelledException()
        beforeRequest()
        val downloadInfo = attachmentsApi.download(token, attachmentId)

        // Downloaded to a `.part` name first, only renamed to its final
        // `.enc` cache name once the transfer completes in full - a
        // network failure/process death mid-download never leaves a
        // partial blob under the name anything else might read (Этап
        // H.2 п.8/9).
        val transferId = UUID.randomUUID()
        val ciphertextPart = File(cacheDir(), "$attachmentId-$transferId.enc.part")
        val ciphertextFile = File(cacheDir(), "$attachmentId-$transferId.enc")
        try {
            val downloadProgress = ProgressThrottle { pct -> kotlinx.coroutines.runBlocking { dao.updateDownloadState(attachmentId, "downloading", pct) } }
            downloadToFile(downloadInfo.downloadUrl, ciphertextPart, downloadProgress::update, ::isCancelled, attachment.encryptedSize,beforeRequest=beforeRequest,automatic=automatic)
            if (!ciphertextPart.renameTo(ciphertextFile)) {
                ciphertextPart.copyTo(ciphertextFile, overwrite = true)
                ciphertextPart.delete()
            }
        } catch (e: Exception) {
            ciphertextPart.delete()
            ciphertextFile.delete()
            dao.updateDownloadState(attachmentId, if (e is AttachmentCancelledException || e is AutomaticDownloadDeferred) "not_downloaded" else "failed", 0)
            throw e
        }

        val plaintextFile = File(cacheDir(), "${attachmentId}_${sanitizeFilename(attachment.filename ?: ("COCO." + (MimeTypeMap.getSingleton().getExtensionFromMimeType(attachment.mimeType) ?: "bin")))}")
        try {
            cacheMutation(epoch) {
            val decryptProgress = ProgressThrottle { pct -> kotlinx.coroutines.runBlocking { dao.updateDownloadState(attachmentId, "downloading", pct) } }
            when (attachment.encryptionVersion) {
                EncryptionInfoDto.VERSION_V2_STREAMING_AEAD -> {
                    val keysetHandle = AttachmentCryptoV2.parseKeysetHandle(
                        Base64.decode(attachment.keysetBase64, Base64.NO_WRAP),
                    )
                    AttachmentCryptoV2.decryptFile(
                        ciphertextFile,
                        plaintextFile,
                        keysetHandle,
                        Base64.decode(attachment.sha256Base64, Base64.NO_WRAP),
                        onBytesProcessed = decryptProgress::update,
                        isCancelled = ::isCancelled,
                    )
                }
                else -> {
                    val keyMaterial = AttachmentKeyMaterial(
                        key = Base64.decode(attachment.keyBase64, Base64.NO_WRAP),
                        nonce = Base64.decode(attachment.nonceBase64, Base64.NO_WRAP),
                    )
                    AttachmentCrypto.decryptFile(
                        ciphertextFile,
                        plaintextFile,
                        keyMaterial,
                        Base64.decode(attachment.sha256Base64, Base64.NO_WRAP),
                        onBytesProcessed = decryptProgress::update,
                        isCancelled = ::isCancelled,
                    )
                }
            }
            }
        } catch (e: AttachmentIntegrityException) {
            dao.updateDownloadState(attachmentId, "corrupt", 0)
            throw e
        } catch (e: AttachmentCancelledException) {
            dao.updateDownloadState(attachmentId, "not_downloaded", 0)
            throw e
        } catch (e: Exception) {
            dao.updateDownloadState(attachmentId, "failed", 0)
            throw e
        } finally {
            ciphertextFile.delete()
            ciphertextPart.delete()
        }

        // P0 hardening: the final durable state write is gated on the session
        // epoch so a delayed old-account download can never commit its
        // "downloaded" row after the logout Room clear.
        cacheMutation(epoch) {
            dao.markDownloaded(attachmentId, "downloaded", plaintextFile.absolutePath)
        }
        onCacheWritten()
        plaintextFile
    }

    private suspend fun downloadToFile(
        url: String,
        destFile: File,
        onBytesProcessed: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        expectedSize: Long,
        beforeRequest: () -> Unit = {},
        automatic: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().apply {
            if (automatic) tag(AutomaticDownloadRequestPolicy::class.java, AutomaticDownloadRequestPolicy(beforeRequest))
        }.build()
        beforeRequest()
        val client = if (automatic) automaticDownloadHttpClient else httpClient
        client.newCall(request).withAttachmentResponse { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty download response body")
            val totalSize = body.contentLength()
            if (totalSize >= 0 && totalSize != expectedSize) throw IOException("Attachment size mismatch")
            var processed = 0L
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) throw AttachmentCancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        processed += read
                        if (processed > expectedSize) throw IOException("Attachment exceeds declared size")
                        output.write(buffer, 0, read)
                        onBytesProcessed(processed, totalSize)
                    }
                }
            }
            if (processed != expectedSize) throw IOException("Incomplete attachment")
        }
    }


    private suspend fun copyUriToFile(uri: Uri, destFile: File, maxBytes: Long = MAX_FILE_ENCRYPTED_BYTES) {
        val activeContext = coroutineContext
        val declared = queryNameAndSize(uri).second
        if (declared > maxBytes) throw AttachmentTooLargeException("File exceeds the allowed size.")
        requireAttachmentSpace(destFile.parentFile ?: cacheDir(), declared.coerceAtLeast(0))
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open $uri")
        try {
            input.use { FileOutputStream(destFile).use { output -> copyBoundedFile(it, output, maxBytes) { activeContext.ensureActive() } } }
        } catch (error: Exception) { destFile.delete(); throw error }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        localFileMetadata(uri.scheme, uri.path)?.let { return it }
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                return name to size
            }
        }
        return null to -1L
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Never a raw, possibly-path-traversing filename (Этап H п.41 - e.g.
     *  "../../../etc/passwd") is used as a local filesystem path component. */
    private fun sanitizeFilename(name: String?): String {
        val cleaned = (name ?: "file").replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return cleaned.ifBlank { "file" }.take(100)
    }

    companion object {
        const val MAX_IMAGE_ENCRYPTED_BYTES = 25L * 1024 * 1024
        private const val MAX_VIDEO_SOURCE_BYTES = 500L * 1024 * 1024
        const val MAX_FILE_ENCRYPTED_BYTES = 100L * 1024 * 1024
        const val MAX_VOICE_ENCRYPTED_BYTES = 25L * 1024 * 1024
        private const val STREAM_BUFFER_SIZE = 64 * 1024
    }
}

class AttachmentTooLargeException(message: String) : Exception(message)

class MediaPreparationException(cause: Throwable) : Exception("Media could not be prepared; retry or send the original file.", cause)
