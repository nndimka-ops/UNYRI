package com.messenger.app.ui.chats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.auth.AccountFileProvider
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.ui.theme.CocoIcons
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RecentFile(
    val id: String, val name: String, val bytes: Long, val sentAt: String,
    val path: String, val uri: String,
)

/** SQL already orders sends across chats; a resend replaces the older source. */
internal fun latestRecentFiles(entries: List<RecentFile>): List<RecentFile> = entries.distinctBy { it.id }

/** Read only, bounded projection of the CURRENT workspace. No second Room owner,
 * downloads, permanent duplicate cache, or polling subscription is introduced. */
internal const val RECENT_FILES_SQL = """
    SELECT a.filename, a.size, a.localPath, m.createdAt, a.sha256Base64
    FROM attachments a JOIN messages m ON m.clientMessageId = a.clientMessageId
    WHERE m.senderId = ? AND m.kind = 'content'
      AND m.status IN ('sent', 'delivered', 'read')
      AND m.isDeletedForEveryone = 0 AND m.hiddenLocally = 0
      AND a.mediaType = 'file' AND a.localPath IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM deleted_chats d WHERE d.chatId = m.chatId)
    ORDER BY m.createdAt DESC, m.sequence DESC, a.attachmentId DESC LIMIT 100
"""

internal suspend fun loadAccountRecentFiles(context: Context, owner: String?, epoch: Int): List<RecentFile> = withContext(Dispatchers.IO) {
    val app = context.applicationContext as? MessengerApp ?: return@withContext emptyList()
    fun current() = owner != null && app.authRepository.currentSessionEpoch() == epoch &&
        (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id == owner
    if (!current()) return@withContext emptyList()
    val database = app.getDatabasePath("messenger.db")
    if (!database.isFile) return@withContext emptyList()
    val files = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery(RECENT_FILES_SQL, arrayOf(owner)).use { cursor ->
            latestRecentFiles(buildList {
                while (cursor.moveToNext()) {
                    val file = File(cursor.getString(2))
                    if (!file.isFile || !file.canRead()) continue
                    val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: file.name
                    val uri = AccountFileProvider.uriForFile(app, "${app.packageName}.fileprovider", file, name)
                    add(RecentFile(cursor.getString(4) + ":" + name, name, cursor.getLong(1), cursor.getString(3), file.path, uri.toString()))
                }
            })
        }
    }
    if (current()) files else emptyList()
}

/** Pure ordered selection policy shared by recent files and the media grid. */
internal fun <T> toggleAttachmentSelection(selection: List<T>, item: T, limit: Int): List<T> = when {
    item in selection -> selection - item
    selection.size < limit.coerceIn(0, 10) -> selection + item
    else -> selection
}

internal fun filePickerResultIsCurrent(requestEpoch: Int, currentEpoch: Int, requestOwner: String?, currentOwner: String?) =
    requestEpoch >= 0 && requestEpoch == currentEpoch && requestOwner != null && requestOwner == currentOwner

@Composable
internal fun RecentFilesTab(
    files: List<RecentFile>, selection: List<String>, loading: Boolean, failed: Boolean,
    onToggle: (String) -> Unit, onChooseFiles: () -> Unit, onMediaAsFiles: () -> Unit, onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = Color(AttachmentPickerStyle.foreground)
    val accent = Color(AttachmentPickerStyle.accent)
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(modifier.testTag("attachment-picker-files"), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp)) {
        item {
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(ink.copy(alpha = .05f))) {
                RecentFileAction(stringResource(R.string.files_choose), stringResource(R.string.files_choose_detail), false, onChooseFiles)
                HorizontalDivider(Modifier.padding(start = 64.dp), thickness = .5.dp, color = ink.copy(alpha = .08f))
                RecentFileAction(stringResource(R.string.files_media_as_files), stringResource(R.string.files_media_as_files_detail), true, onMediaAsFiles)
            }
        }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
        else if (failed) item { TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("files-retry")) { Text(stringResource(R.string.files_load_failed), color = ink) } }
        else if (files.isEmpty()) item { Text(stringResource(R.string.files_recent_empty), Modifier.fillMaxWidth().padding(24.dp), color = ink.copy(alpha = .55f), fontSize = 13.sp) }
        else {
            item { Text(stringResource(R.string.files_recent_title), Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp), color = ink.copy(alpha = .5f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            itemsIndexed(files, key = { _, file -> file.id }) { index, file ->
                val number = selection.indexOf(file.id) + 1
                val state = if (number > 0) stringResource(R.string.files_selected_number, number) else stringResource(R.string.gallery_select)
                val shape = RoundedCornerShape(topStart = if (index == 0) 14.dp else 0.dp, topEnd = if (index == 0) 14.dp else 0.dp,
                    bottomStart = if (index == files.lastIndex) 14.dp else 0.dp, bottomEnd = if (index == files.lastIndex) 14.dp else 0.dp)
                Column(Modifier.clip(shape).background(ink.copy(alpha = .05f))) {
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 64.dp), thickness = .5.dp, color = ink.copy(alpha = .08f))
                    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("files-recent-${file.id}")
                        .clickable(role = Role.Checkbox) { onToggle(file.id) }.semantics(mergeDescendants = true) { selected = number > 0; stateDescription = state }
                        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(38.dp).background(ink.copy(alpha = .10f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Text(file.name.substringAfterLast('.', "FILE").uppercase(Locale.ROOT).take(4), color = ink.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            CompactFileName(file.name, MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), ink)
                            Text(recentFileDetail(file, locale), color = ink.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"), maxLines = 1)
                        }
                        Surface(Modifier.size(24.dp).clearAndSetSemantics {}, shape = CircleShape, color = if (number > 0) accent else Color.Transparent,
                            border = BorderStroke(1.5.dp, if (number > 0) accent else ink.copy(alpha = .45f))) {
                            Box(contentAlignment = Alignment.Center) { if (number > 0) Text(number.toString(), color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentFileAction(title: String, detail: String, media: Boolean, onClick: () -> Unit) {
    val ink = Color(AttachmentPickerStyle.foreground)
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag(if (media) "files-media-as-files" else "files-choose")
        .clickable(role = Role.Button, onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(38.dp).background(ink.copy(alpha = .08f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(if (media) CocoIcons.StackedPhotos else AttachmentUiIcons.folder, null, Modifier.size(22.dp), tint = Color(AttachmentPickerStyle.accent))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = ink.copy(alpha = .55f), fontSize = 12.sp)
        }
        Text("›", color = ink.copy(alpha = .4f), fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun recentFileDetail(file: RecentFile, locale: Locale): String {
    val date = runCatching {
        val sent = Instant.parse(file.sentAt).atZone(ZoneId.systemDefault())
        val today = java.time.LocalDate.now(sent.zone)
        if (sent.toLocalDate() == today) sent.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        else sent.format(DateTimeFormatter.ofPattern("d MMM", locale))
    }.getOrDefault("")
    val unit = when { file.bytes >= 1_000_000 -> R.string.direct_unit_mb; file.bytes >= 1000 -> R.string.direct_unit_kb; else -> R.string.direct_unit_b }
    val divisor = when (unit) { R.string.direct_unit_mb -> 1_000_000.0; R.string.direct_unit_kb -> 1000.0; else -> 1.0 }
    val format = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (file.bytes >= 1_000_000) 1 else 0 }
    // Unit labels come from resources at the call site below.
    return format.format(file.bytes / divisor) + " " + recentUnitLabel(unit) + if (date.isEmpty()) "" else " · $date"
}

@Composable
private fun recentUnitLabel(unit: Int): String = stringResource(unit)

/** Small local outlines; no SF assets or changes to another batch's icon ledger. */
internal object AttachmentUiIcons {
    private fun outline(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()
    val folder by lazy { outline("AttachmentFolder") {
        moveTo(3f, 5f); lineTo(9f, 5f); lineTo(12f, 8f); lineTo(21f, 8f)
        lineTo(21f, 20f); lineTo(3f, 20f); close(); moveTo(3f, 10f); lineTo(21f, 10f)
    } }
    val direct by lazy { outline("AttachmentDirect") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(9f, 2f); lineTo(4f, 7f); lineTo(9f, 12f)
        moveTo(20f, 17f); lineTo(4f, 17f); moveTo(15f, 12f); lineTo(20f, 17f); lineTo(15f, 22f)
    } }
    val info by lazy { outline("AttachmentInfo") {
        moveTo(22f, 12f); arcTo(10f, 10f, 0f, true, true, 2f, 12f); arcTo(10f, 10f, 0f, true, true, 22f, 12f)
        moveTo(10f, 11f); lineTo(12f, 11f); lineTo(12f, 17f); moveTo(10f, 17f); lineTo(14f, 17f)
        moveTo(12f, 7f); lineTo(12f, 7.2f)
    } }
}
