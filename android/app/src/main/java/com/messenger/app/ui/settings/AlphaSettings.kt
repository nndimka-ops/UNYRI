package com.messenger.app.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.BuildConfig
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.api.ServerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

@Composable fun AlphaAboutContent(onOpenLegal: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.alpha_test), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.alpha_description)); Text(stringResource(R.string.alpha_warning))
        Text(stringResource(R.string.alpha_server_description))
        for (id in listOf("privacy", "support", "terms")) {
            SettingsSecondaryButton(onClick = { onOpenLegal(id) }) { Text(cocoLegalTitle(id)) }
        }
    }
}
@Composable fun AlphaTermsContent() { CocoLegalContent("terms") }

@Composable fun BugReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val formScroll = rememberScrollState()
    val colors = com.messenger.app.ui.theme.LocalCocoColors.current
    var description by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var review by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var rateLimited by remember { mutableStateOf(false) }
    var ticket by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<Int?>(null) }
    var reportId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true; error = false; rateLimited = false
            val epoch = app.authRepository.currentSessionEpoch()
            val origin = ServerEndpoint.current()
            try {
                val prepared = feedbackImportWorker.run { cancellation -> uris.take(3).map { feedbackImage(context, it, cancellation) } }
                if (epoch == app.authRepository.currentSessionEpoch() && origin == ServerEndpoint.current()) {
                    images = prepared; reportId = UUID.randomUUID().toString(); ticket = null
                }
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                error = true; rateLimited = failure is FeedbackRateLimitException
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(error, ticket) { if (error || ticket != null) { focus.clearFocus(); formScroll.animateScrollTo(0) } }
    val submit: () -> Unit = submit@{
        if (busy || ticket != null || description.trim().length < 5) return@submit
        validation = null
        busy = true; error = false; rateLimited = false
        val origin = ServerEndpoint.current(); val epoch = app.authRepository.currentSessionEpoch()
        val body = JSONObject().put("id", reportId).put("description", description.trim()).put("platform", "android")
            .put("app_version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")").put("os_version", android.os.Build.VERSION.RELEASE)
            .put("screenshots", JSONArray(images.map { JSONObject().put("mime_type","image/jpeg").put("data_base64",Base64.encodeToString(it,Base64.NO_WRAP)) }))
            .apply { app.performanceDiagnostics.snapshot()?.let { put("performance", it) } }.toString()
        scope.launch {
            try {
                val token = app.authRepository.currentAccessToken() ?: throw IllegalStateException("signed out")
                check(epoch == app.authRepository.currentSessionEpoch() && origin == ServerEndpoint.current())
                val id = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url("$origin/feedback").header("Authorization", "Bearer $token").post(body.toRequestBody("application/json".toMediaType())).build()
                    OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).build().newCall(request).execute().use {
                        if (it.code == 429) throw FeedbackRateLimitException()
                        check(it.isSuccessful); JSONObject(it.body?.string().orEmpty()).getString("id")
                    }
                }
                if (epoch == app.authRepository.currentSessionEpoch()) ticket = id
            } catch (failure: Exception) { error = true; rateLimited = failure is FeedbackRateLimitException }
            finally { busy = false }
        }
    }
    SettingsScaffold(stringResource(R.string.feedback_title), { if (!busy) onBack() }) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(formScroll).testTag("feedback-form")
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (ticket != null) {
                    Surface(shape = com.messenger.app.ui.theme.CocoShapes.radiusMedium, color = colors.success.copy(alpha = 0.12f)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.feedback_received), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.feedback_success_hint), style = MaterialTheme.typography.bodyMedium)
                            Text(ticket.orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
                        }
                    }
                } else {
                    if (error) Surface(shape = com.messenger.app.ui.theme.CocoShapes.radiusMedium, color = colors.danger.copy(alpha=.08f),
                        border=BorderStroke(1.dp,colors.danger.copy(alpha=.35f))) {
                        Text(stringResource(if (rateLimited) R.string.feedback_rate_limited else R.string.feedback_failed), Modifier.padding(16.dp), color = colors.danger)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.feedback_description), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.feedback_steps), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it.take(5000); reportId = UUID.randomUUID().toString(); validation = null },
                            placeholder = { Text(stringResource(R.string.feedback_placeholder)) },
                            minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth().testTag("feedback-description"), enabled = !busy,
                            shape = com.messenger.app.ui.theme.CocoShapes.radiusMedium,
                            isError = validation == R.string.feedback_need_description,
                            supportingText = {
                                if (validation == R.string.feedback_need_description) Text(stringResource(R.string.feedback_need_description))
                                else Text("${description.length} / 5000")
                            },
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.feedback_screenshots_section), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text("${images.size} / 3", style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                        }
                        Text(stringResource(R.string.feedback_optional_images), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                        if (images.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            images.forEachIndexed { index, bytes ->
                                val bitmap by produceState<Bitmap?>(null, bytes) {
                                    value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                                }
                                Surface(shape = com.messenger.app.ui.theme.CocoShapes.radiusMedium, color = colors.surfaceVariant, border = BorderStroke(1.dp, colors.outlineSubtle)) {
                                    Column(Modifier.width(112.dp).padding(8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                        bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.feedback_screenshot_number, index + 1), Modifier.height(112.dp).fillMaxWidth()) }
                                        SettingsDialogButton(enabled = !busy, onClick = { images = images.filterIndexed { n, _ -> n != index }; reportId = UUID.randomUUID().toString() }) {
                                            Text(stringResource(R.string.feedback_remove))
                                        }
                                    }
                                }
                            }
                        }
                        SettingsSecondaryButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                            focus.clearFocus()
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(stringResource(R.string.feedback_choose)) }
                    }
                    Surface(shape = com.messenger.app.ui.theme.CocoShapes.radiusMedium, color = colors.surfaceVariant) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.feedback_recipient), style = MaterialTheme.typography.labelLarge)
                            Text(ServerEndpoint.current(), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                            Text(stringResource(R.string.feedback_review_hint), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.outlineSubtle)
            SettingsPrimaryButton(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("feedback-send"),
                enabled = !busy,
                onClick = {
                    focus.clearFocus()
                    if (ticket != null) { onBack() }
                    else if (description.trim().length < 5) {
                        validation = R.string.feedback_need_description
                        scope.launch { formScroll.animateScrollTo(0) }
                    } else { validation = null; review = true }
                },
            ) {
                if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current); Spacer(Modifier.width(8.dp)) }
                Text(stringResource(when { busy -> R.string.feedback_working; ticket != null -> R.string.feedback_done; else -> R.string.feedback_send }))
            }
        }
    }
    if (review) {
        AlertDialog(
            onDismissRequest = { review = false },
            title = { Text(stringResource(R.string.feedback_review_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(ServerEndpoint.current(), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.feedback_disclosure))
                    Text(stringResource(R.string.feedback_image_count, images.size), style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = {
                SettingsDialogButton(enabled = !busy, onClick = { review = false; submit() }, modifier = Modifier.testTag("feedback-confirm-send")) {
                    Text(stringResource(R.string.feedback_agree_send))
                }
            },
            dismissButton = { SettingsDialogButton(onClick = { review = false }, modifier = Modifier.testTag("feedback-review-back")) { Text(stringResource(R.string.back)) } },
        )
    }
}

private fun <T> feedbackInput(context: Context, uri: Uri, cancellation: FeedbackImportWorker.Cancellation, read: (java.io.InputStream) -> T): T {
    cancellation.check()
    val signal = android.os.CancellationSignal()
    val detachSignal = cancellation.register { signal.cancel() }
    try {
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: error("image")
        val detachDescriptor = cancellation.register { descriptor.close() }
        try {
            cancellation.check()
            val stream = descriptor.createInputStream()
            val detachStream = cancellation.register { stream.close() }
            try { return stream.use { cancellation.check(); read(it) } }
            finally { detachStream() }
        } finally { descriptor.close(); detachDescriptor() }
    } finally { detachSignal() }
}

private fun feedbackImage(context: Context, uri: Uri, cancellation: FeedbackImportWorker.Cancellation): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    feedbackInput(context, uri, cancellation) { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 150_000_000)
    var sample = 1
    while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
    val bitmap = feedbackInput(context, uri, cancellation) { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("image")
    val orientation = try {
        feedbackInput(context, uri, cancellation) { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) }
    } catch (failure: Exception) {
        if (failure is CancellationException) { bitmap.recycle(); throw failure }
        1
    }
    val matrix = Matrix().apply { when (orientation) { 2 -> setScale(-1f,1f); 3 -> setRotate(180f); 4 -> setScale(1f,-1f); 5 -> {setRotate(90f);postScale(-1f,1f)}; 6 -> setRotate(90f); 7 -> {setRotate(-90f);postScale(-1f,1f)}; 8 -> setRotate(-90f) } }
    val rotated = Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
    try {
        val output = ByteArrayOutputStream(); check(rotated.compress(Bitmap.CompressFormat.JPEG,75,output))
        return output.toByteArray().also { require(it.size <= 1024 * 1024) }
    } finally { if (rotated !== bitmap) rotated.recycle(); bitmap.recycle() }
}

private class FeedbackRateLimitException : Exception()

// Same reviewed policy text as the hosted pages; no network needed to read it.
data class CocoLegalSection(val title: String, val paragraphs: List<String>)
data class CocoLegalDocument(val title: String, val intro: String, val version: String, val sections: List<CocoLegalSection>)
object CocoLegalDocuments {
    private val ru = mapOf(
"privacy" to CocoLegalDocument("Конфиденциальность UNYRI", "Здесь описано, какие данные обрабатывает UNYRI, зачем они нужны и как ими управлять. Сквозное шифрование содержимого переписки не означает, что сервер не получает никаких данных.", "Обновлено: 4 ноября 2026 года", listOf(
CocoLegalSection("Кто отвечает за данные", listOf("Издатель приложения UNYRI и оператор официального сервера UNYRI Cloud — Dmytro Novopolskyi. Контакт по приложению и конфиденциальности: bbdimka@gmail.com.", "Эта политика предназначена для приложения и официального сервера UNYRI Cloud. В UNYRI можно выбрать другой, в том числе собственный сервер. Его оператор отдельно определяет обработку серверных данных, правила и сроки хранения. До входа проверьте адрес и карточку выбранного сервера. Контакт издателя не заменяет контакт оператора стороннего сервера.")),
CocoLegalSection("Учётная запись и профиль", listOf("Сервер обрабатывает имя пользователя, идентификатор аккаунта, выбранное имя, описание и аватар, а также сведения об устройствах и сеансах, необходимые для входа, синхронизации и защиты аккаунта. Пароль проверяется с использованием защищённого хеша; его открытое значение не хранится в базе аккаунтов.", "Профиль, аватар, названия и описания комнат могут быть доступны другим людям в соответствии с функцией и её настройками. Не указывайте там сведения, которые не хотите раскрывать.")),
CocoLegalSection("Переписка, вложения и звонки", listOf("Содержимое поддерживаемой зашифрованной переписки и её вложений шифруется на устройстве перед отправкой. Устройства участников получают данные для расшифрования. Сервер хранит зашифрованные данные и сведения, необходимые для доставки и синхронизации, включая идентификаторы отправителя и получателя, время, состояние доставки, размер и связь вложения с сообщением.", "Для звонков и комнат сервер обрабатывает сигнализацию, идентификаторы участников, состояние участия и соединения. Сетевому серверу или ретранслятору доступны IP-адреса подключений и технические параметры трафика. При прямом соединении сетевой адрес может быть виден другому участнику. Не все сведения приложения зашифрованы сквозным шифрованием: например, публичный профиль и текст отправленной жалобы доступны оператору сервера.", "Если в вашей сборке доступна прямая передача файлов, сервер может участвовать в согласовании и хранить её состояние, даже когда сам файл передаётся между устройствами. Это не обеспечивает анонимность соединения.")),
CocoLegalSection("Диагностика, обращения и жалобы", listOf("При включённой диагностике на выбранный сервер могут отправляться технические коды операций и ошибок, сведения о приложении, ОС и устройстве, времени выполнения, памяти и работе интерфейса. Состав зависит от версии: некоторые установленные сборки также передают показатели свободного места. Эти сведения могут быть связаны с аккаунтом и устройством для разбора проблемы.", "В «Сообщить о проблеме» вы выбираете описание и вложения отчёта, например снимки экрана или диагностические материалы, которые разрешено приложить. Просмотрите их перед отправкой: на снимках могут быть личные данные. Техническая онлайн-диагностика при включённой настройке работает отдельно от ручного отчёта.", "Жалоба на собеседника передаёт выбранному серверу причину и введённое вами описание в читаемом виде. Вся переписка автоматически к жалобе не прикладывается. Письмо на bbdimka@gmail.com получает издатель через почтовую службу; встроенный отчёт получает оператор выбранного сервера.")),
CocoLegalSection("Зачем данные используются и кому доступны", listOf("Данные нужны для работы аккаунта, доставки и синхронизации, звонков, защиты от злоупотреблений, выполнения ваших настроек и разбора обращений. К серверным данным могут иметь доступ уполномоченные администраторы и поставщики инфраструктуры в объёме своей работы. Официальный сервер использует Scaleway; условия обработки и субподрядчики опубликованы на https://www.scaleway.com/en/contracts/ и https://www.scaleway.com/en/subprocessorlist/. Сторонний сервер может использовать других поставщиков и находиться в другой стране.", "Для уведомлений Android используется Google Firebase Cloud Messaging. Сервис получает идентификатор установки и токен доставки, а также технические сведения уведомления: например, идентификаторы чата, сообщения или звонка. Обычные push-сообщения не содержат открытый текст личной переписки; отдельное административное уведомление может содержать текст администратора. По правилам Firebase идентификатор установки хранится до запроса его удаления через API; после запроса удаление из рабочих и резервных систем может занять до 180 дней. Удаление аккаунта UNYRI сейчас не отправляет такой запрос на удаление Firebase ID. Подробнее: https://firebase.google.com/support/privacy и https://firebase.google.com/docs/projects/manage-installations.", "Письма на bbdimka@gmail.com обрабатываются почтовой службой Google по её условиям: https://policies.google.com/privacy и https://policies.google.com/technologies/retention. Разрешения камеры, микрофона и медиатеки обрабатывает ОС при использовании соответствующих функций. Получатель видит отправленные ему сведения и может сохранить собственную копию. Эта политика не обещает отсутствие сетевых метаданных или невозможность компрометации устройства либо сервера.")),
CocoLegalSection("Хранение и удаление", listOf("Аккаунт, история и активная зашифрованная резервная копия истории хранятся, пока вы их не удалите или не замените по правилам выбранного сервера. На официальном сервере дневные группы технической диагностики истекают примерно в пределах восьми суток, показатели производительности — в пределах суток. Ручные отчёты об ошибках очищаются после 30 суток при успешной работе фоновой задачи, которая проверяет их каждый час.", "У записей административного аудита в базе сейчас нет автоматического срока удаления: они остаются до удаления оператором. Технические журналы контейнеров ограничены размером: до трёх файлов примерно по 10 МБ на контейнер; это не фиксированный срок в днях. Журналы могут содержать идентификаторы аккаунта и устройства, сетевые и технические события. Обычный журнал HTTP-доступа Caddy сейчас выключен; это не отключает остальные журналы системы и провайдеров. У писем поддержки нет установленного приложением автоматического срока: они хранятся в почтовом ящике до удаления.", "Автоматическое резервное копирование инфраструктуры UNYRI в проверенной конфигурации сервера не настроено. Это отличается от зашифрованной резервной копии истории, которую включает пользователь, и не исключает технические записи или копии у инфраструктурных поставщиков. Сроки обработки у поставщиков определяются их условиями. Не следует считать удаление аккаунта мгновенным физическим стиранием каждой копии у всех поставщиков.", "Для удаления аккаунта откройте настройки аккаунта → «Удалить аккаунт», подтвердите пароль и действие. Доступ отзывается, аккаунт и связанная серверная история удаляются. Объекты вложений и аватаров ставятся в очередь очистки не ранее чем через 24 часа и после окончания действующих разрешений доступа; при ошибках очередь повторяет попытки. Медиа резервной копии очищаются отдельной очередью после окончания разрешений доступа. Прямые переписки удаляются у собеседников после синхронизации. Снимки экрана, экспорт и другие независимые копии у людей удалить таким способом нельзя. Удаление приложения само по себе не удаляет аккаунт.")),
CocoLegalSection("Ваш выбор и обращения", listOf("В настройках можно управлять доступными разрешениями, диагностикой, показом онлайна и отметок о прочтении, блокировками, кэшем и удалением аккаунта. Доступность отдельных настроек зависит от версии. Отключение диагностики прекращает последующую отправку по этой настройке, но не стирает ранее отправленные данные автоматически.", "Для вопросов о доступе, исправлении, переносимости или удалении данных и отзыва разрешения напишите на bbdimka@gmail.com; для стороннего сервера также обратитесь к его оператору. Возможности и обязательные сроки ответа зависят от применимого закона. Не отправляйте пароль, коды подтверждения, PIN, ключи шифрования или закрытые ключи. Существенные изменения этой политики публикуются с новой датой и должны быть доступны из приложения."))
)),
"support" to CocoLegalDocument("Поддержка UNYRI", "По вопросам приложения, безопасности и конфиденциальности: bbdimka@gmail.com. Не отправляйте секреты аккаунта.", "Обновлено: 4 ноября 2026 года", listOf(
CocoLegalSection("Сообщить об ошибке", listOf("Откройте настройки → «Помощь и приложение» → «Сообщить о проблеме». Укажите, что делали, что ожидали и что произошло. Полезны версия и номер сборки, модель телефона, ОС и примерное время события. Скриншоты и диагностику прикладывайте только после просмотра.", "Встроенный отчёт идёт на выбранный сервер. Для письма издателю используйте bbdimka@gmail.com и укажите адрес сервера, если это необходимо для разбора. Не включайте в письмо историю переписки без необходимости.")),
CocoLegalSection("Нежелательный контакт и опасный контент", listOf("В меню чата выберите «Заблокировать собеседника» или «Пожаловаться на собеседника». В жалобе опишите нарушение без паролей и ключей. Описание получает оператор выбранного сервера в читаемом виде; личная переписка целиком автоматически не передаётся.", "Для обращения о публичном профиле или комнате укажите сервер и достаточный идентификатор профиля или комнаты в письме. Оператор рассматривает обращение и использует доступные меры ограничения или удаления. При непосредственной угрозе жизни обращайтесь в местные экстренные службы: UNYRI не является экстренной службой.")),
CocoLegalSection("Данные и удаление аккаунта", listOf("Удаление доступно внутри приложения: настройки аккаунта → «Удалить аккаунт». Требуется подтверждение намерения и пароля. Для обычного удаления писать в поддержку не нужно. Если вход недоступен, свяжитесь с оператором сервера; издателю можно написать на bbdimka@gmail.com. Независимые копии у других людей не удаляются.", "Перед удалением сохраните нужные данные доступными в вашей версии средствами. Не считайте перенос или резервную копию завершёнными, пока новое устройство не подтвердило восстановление нужной истории.")),
CocoLegalSection("Подключение и тестовые сборки", listOf("Проверьте адрес выбранного сервера и его карточку. Для собственного сервера доступность, правила и хранение определяет его оператор. Возможности уведомлений и работы в фоне зависят от ОС, разрешений и конкретной сборки; постоянная работа в фоне и доставка без интернета не гарантируются.", "Устанавливая тестовую сборку, прочитайте её ограничения и сохраните важные данные. Описание проблем по почте и встроенные отчёты помогают разбору, но подтверждённый срок ответа устанавливается оператором отдельно."))
)),
"terms" to CocoLegalDocument("Условия использования UNYRI", "Эти условия относятся к приложению UNYRI издателя Dmytro Novopolskyi и официальному серверу UNYRI Cloud. Для другого выбранного сервера действуют также правила его оператора, показанные в карточке сервера.", "Обновлено: 4 ноября 2026 года", listOf(
CocoLegalSection("Аккаунт и доступ", listOf("Используйте приложение, если это разрешено применимым законом и возрастными ограничениями приложения. Не выдавайте себя за другого человека и не получайте доступ к чужим аккаунтам. Берегите пароль, устройства и ключи: не передавайте их поддержке и другим людям.", "Сохраняйте нужные вам копии истории и файлов. Возможности и ограничения тестовых версий могут меняться; приложение и сеть могут работать с ошибками. UNYRI не заменяет экстренную связь.")),
CocoLegalSection("Запрещённые действия", listOf("Нельзя распространять материалы сексуальной эксплуатации детей, интимные материалы без согласия, угрозы, травлю, целевую ненависть или дискриминацию, мошенничество, спам, вредоносные программы и иной незаконный контент. Нельзя нарушать чужую приватность и права на материалы или размещать открыто порнографические материалы в публичных профилях и списках комнат.", "Нельзя атаковать сервис или других людей, обходить блокировки, ограничения доступа и лимиты, собирать чужие данные без законного основания, нарушать работу сервера или использовать сервис для незаконных действий. Не публикуйте пароли, ключи и личные сведения других людей.")),
CocoLegalSection("Профили, комнаты и модерация", listOf("Вы отвечаете за материалы и сведения, которые отправляете и публикуете. Публикуйте только то, на что у вас есть необходимые права. Другие участники могут сохранить полученную копию. Не раскрывайте секреты в имени, описании, аватаре или названии комнаты.", "О нарушении можно сообщить через меню чата или контакт поддержки. Оператор выбранного сервера рассматривает обращения и может ограничить доступ, приостановить аккаунт и удалить доступные ему публичные материалы в рамках своих правил и функций сервиса. Решения по зашифрованной переписке не требуют автоматической передачи всей переписки оператору. Блокировка собеседника доступна в приложении.")),
CocoLegalSection("Данные, удаление и выбранный сервер", listOf("Обработка данных описана в политике конфиденциальности. Прочитайте её до создания аккаунта. Согласие с условиями не является автоматическим согласием на необязательную диагностику.", "Аккаунт можно удалить в настройках аккаунта с подтверждением. Удаление необратимо для серверной истории; физическая очистка объектов выполняется фоновой очередью. Удаление приложения не удаляет аккаунт, а независимые копии у собеседников могут сохраниться. Правила стороннего сервера и его возможности определяет его оператор.")),
CocoLegalSection("Изменения и контакт", listOf("Важные изменения условий публикуются с новой датой. Новые обязательства не должны скрываться в техническом обновлении. Если вы не согласны с условиями, прекратите использование и воспользуйтесь удалением аккаунта, сохранив нужные данные заранее.", "По приложению и официальному серверу: bbdimka@gmail.com. Эти условия не ограничивают права, которые нельзя исключить по применимому закону, и не обещают отсутствие ошибок, абсолютную анонимность или неуязвимость сервиса."))
))
    )
    private val en = mapOf(
"privacy" to CocoLegalDocument("UNYRI Privacy Policy", "This policy explains the data UNYRI processes, why it is needed, and the controls available to you. End-to-end encryption of conversation content does not mean that a server receives no data.", "Updated: 4 November 2026", listOf(
CocoLegalSection("Who is responsible", listOf("The UNYRI app publisher and official UNYRI Cloud operator is Dmytro Novopolskyi. Contact for the app and privacy: bbdimka@gmail.com.", "This policy is intended for the app and the official UNYRI Cloud server. UNYRI also lets you choose another server, including one you host yourself. That server's operator separately determines its server-side processing, rules and retention. Check the server address and server card before signing in. The publisher's contact does not replace a third-party server operator's contact.")),
CocoLegalSection("Account and profile", listOf("The server processes your username, account identifier, chosen display name, bio and avatar, together with device and session information needed for sign-in, synchronization and account security. Passwords are checked using a protected hash; the account database does not store the plain password.", "Profiles, avatars, room names and room descriptions may be visible to others according to the feature and its settings. Do not place information there that you do not wish to disclose.")),
CocoLegalSection("Conversations, attachments and calls", listOf("Content in supported encrypted conversations and their attachments is encrypted on the device before upload. Participating devices receive the information needed to decrypt it. The server stores encrypted data and information needed for delivery and synchronization, including sender and recipient identifiers, timing, delivery state, size, and the association between an attachment and its message.", "For calls and rooms, the server processes signaling, participant identifiers, participation and connection state. A network server or relay can see connecting IP addresses and technical traffic information. A direct connection may reveal a network address to the other participant. Not all app information is end-to-end encrypted: public profiles and submitted report descriptions, for example, are readable by the server operator.", "If your build provides direct file transfer, the server may coordinate it and store transfer state even when the file itself travels between devices. This does not make the connection anonymous.")),
CocoLegalSection("Diagnostics, support and abuse reports", listOf("When diagnostics are enabled, technical operation and error codes, app, OS and device information, timings, memory and interface performance information may be sent to the selected server. The fields depend on the version: some installed builds also send available-storage measurements. These records may be linked to your account and device to investigate an issue.", "In Report a problem, you choose the description and report attachments, such as screenshots or permitted diagnostic material. Review them before submission; screenshots may contain personal information. Enabled online technical diagnostics are separate from a manually submitted report.", "Reporting a contact sends the selected server a reason and the description you enter in readable form. Your whole conversation is not automatically attached. Email to bbdimka@gmail.com reaches the publisher through an email service; an in-app report reaches the operator of the selected server.")),
CocoLegalSection("Purpose and access", listOf("Data is used to operate accounts, deliver and synchronize content, provide calls, prevent abuse, apply settings and handle support. Authorized administrators and infrastructure providers may access server data as needed for their work. The official server uses Scaleway; its processing terms and subprocessors are published at https://www.scaleway.com/en/contracts/ and https://www.scaleway.com/en/subprocessorlist/. Other servers may use different providers and be located in other countries.", "Android notifications use Google Firebase Cloud Messaging. It receives an installation identifier and delivery token, together with notification routing information such as chat, message or call identifiers. Ordinary pushes do not contain plaintext private messages; a separate administrative notice may contain the administrator’s text. Under Firebase’s published rules, the installation identifier remains until its deletion is requested through the API; removal from live and backup systems may then take up to 180 days. UNYRI account deletion currently does not request deletion of that Firebase ID. See https://firebase.google.com/support/privacy and https://firebase.google.com/docs/projects/manage-installations.", "Email to bbdimka@gmail.com is handled by Google’s mail service under its terms: https://policies.google.com/privacy and https://policies.google.com/technologies/retention. Camera, microphone and photo-library permissions are handled by the OS when you use the relevant features. Recipients see the information you send them and can keep independent copies. This policy does not promise an absence of network metadata or that a device or server cannot be compromised.")),
CocoLegalSection("Retention and deletion", listOf("Account data, history and an active encrypted history backup remain until you delete or replace them under the selected server rules. On the official server, daily technical diagnostic groups expire within approximately eight days and performance samples within a day. Manual bug reports are cleaned up after 30 days when the background task succeeds; it checks them hourly.", "Administrative audit records in the database currently have no automatic expiry: they remain until removed by the operator. Container technical logs are limited by size, to three files of approximately 10 MB per container; this is not a fixed number of days. Logs may contain account and device identifiers, network and technical events. Caddy HTTP access logging is currently off; this does not disable other system or provider logs. The app sets no automatic expiry for support email; correspondence remains in the mailbox until removed.", "Automated infrastructure backup is not configured in the checked UNYRI server setup. This differs from a user-enabled encrypted history backup and does not rule out technical records or copies maintained by infrastructure providers. Provider processing periods follow their terms. Account deletion should not be understood as immediate physical erasure of every copy at every provider.", "To delete an account, open account settings → Delete account and confirm your password and choice. Access is revoked and the account and associated server history are removed. Attachment and avatar objects are queued for cleanup no earlier than 24 hours later and after existing access capabilities expire, with retries on failure. Backup media is cleaned up by a separate queue after access capabilities expire. Direct conversations disappear for other participants after synchronization. Screenshots, exports and other independent copies held by people cannot be removed this way. Uninstalling the app does not delete your account.")),
CocoLegalSection("Choices and requests", listOf("Settings provide available controls for permissions, diagnostics, presence and read receipts, blocked contacts, cache and account deletion. Individual options depend on the version. Turning diagnostics off stops future submission under that setting; it does not automatically erase records already submitted.", "For questions about access, correction, portability, deletion or withdrawal of permission, contact bbdimka@gmail.com; also contact the operator when using a third-party server. Available rights and required response periods depend on applicable law. Do not send passwords, confirmation codes, PINs, encryption keys or private keys. Material policy changes are published with a new date and must remain accessible from the app."))
)),
"support" to CocoLegalDocument("UNYRI Support", "For app, security and privacy questions: bbdimka@gmail.com. Do not send account secrets.", "Updated: 4 November 2026", listOf(
CocoLegalSection("Report a problem", listOf("Open Settings → Help & app → Report a problem. Explain what you did, what you expected, and what happened. Useful details include version and build number, phone model, OS, and approximate event time. Review screenshots and diagnostics before attaching them.", "In-app reports go to the selected server. To email the publisher, use bbdimka@gmail.com and include the server address when needed to investigate. Do not include conversation history unnecessarily.")),
CocoLegalSection("Unwanted contact and harmful content", listOf("In the chat menu, choose Block contact or Report contact. Describe the issue without passwords or keys. The selected server's operator receives the description in readable form; your whole private conversation is not automatically sent.", "To report a public profile or room, include the server and enough information to identify the profile or room in an email. The operator reviews the request and uses available restriction or removal controls. For an immediate threat to life, contact local emergency services: UNYRI is not an emergency service.")),
CocoLegalSection("Data and account deletion", listOf("Account deletion is available in the app: account settings → Delete account. You confirm your intention and password. Normal deletion does not require contacting support. If sign-in is unavailable, contact the server operator; the publisher can be reached at bbdimka@gmail.com. Independent copies held by others are not removed.", "Save information you need before deletion using the tools available in your version. Do not consider a transfer or backup complete until the new device confirms that the history you need was restored.")),
CocoLegalSection("Connection and test builds", listOf("Check the selected server address and its server card. A self-hosted server's availability, rules and retention are determined by its operator. Notification and background behavior depend on the OS, permissions and build; uninterrupted background operation or delivery without internet is not guaranteed.", "Read a test build's limitations and preserve important information before installing it. Email and in-app reports help investigation; any guaranteed response period must be established separately by the operator."))
)),
"terms" to CocoLegalDocument("UNYRI Terms of Use", "These terms apply to the UNYRI app published by Dmytro Novopolskyi and the official UNYRI Cloud server. Another selected server also has its operator's rules, shown in its server card.", "Updated: 4 November 2026", listOf(
CocoLegalSection("Account and access", listOf("Use the app only when permitted by applicable law and the app's age restrictions. Do not impersonate another person or access someone else's account. Protect your password, devices and keys; do not give them to support or other people.", "Keep copies of history and files that you need. Test features and limits may change; the app and network can encounter errors. UNYRI is not a substitute for emergency communication.")),
CocoLegalSection("Prohibited conduct", listOf("Do not distribute child sexual exploitation material, nonconsensual intimate material, threats, harassment, targeted hate or discrimination, scams, spam, malware, or other illegal content. Do not violate others' privacy or rights in content, or place overtly pornographic material in public profiles and room listings.", "Do not attack the service or other people, evade blocks, access controls or limits, collect others' data without a lawful basis, disrupt servers, or use the service for illegal activity. Do not publish other people's passwords, keys or private information.")),
CocoLegalSection("Profiles, rooms and moderation", listOf("You are responsible for what you send and publish. Share only material for which you have the necessary rights. Other participants may keep their own copy. Do not put secrets in a name, bio, avatar or room title.", "Violations can be reported through the chat menu or support contact. The selected server's operator reviews reports and may restrict access, suspend an account or remove public material available to it within its rules and service controls. Action on encrypted conversations does not require automatic disclosure of an entire conversation to the operator. Contact blocking is available in the app.")),
CocoLegalSection("Data, deletion and the selected server", listOf("Data processing is described in the Privacy Policy. Read it before creating an account. Agreeing to these terms is not automatic consent to optional diagnostics.", "You can delete your account in account settings after confirmation. Server history deletion is irreversible; physical object cleanup runs through a background queue. Uninstalling the app does not delete the account, and independent copies held by contacts may remain. A third-party server's rules and capabilities are determined by its operator.")),
CocoLegalSection("Changes and contact", listOf("Important changes to these terms are published with a new date. New obligations should not be hidden in a technical update. If you disagree with the terms, stop using the service and use account deletion after preserving information you need.", "For the app and official server: bbdimka@gmail.com. These terms do not limit rights that cannot be excluded under applicable law. They do not promise error-free operation, absolute anonymity or an invulnerable service."))
))
    )
    fun document(id: String, language: String): CocoLegalDocument = (if (language == "ru") ru else en).getValue(id)
}
@Composable fun cocoLegalLanguage(): String = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
@Composable fun cocoLegalTitle(id: String): String = CocoLegalDocuments.document(id, cocoLegalLanguage()).title
@Composable fun cocoLegalAgreement(): String = if (cocoLegalLanguage() == "ru") "Я принимаю условия использования" else "I agree to the Terms of Use"
@Composable fun CocoLegalContent(documentID: String) {
    val document = CocoLegalDocuments.document(documentID, cocoLegalLanguage())
    val context = LocalContext.current
    val language = cocoLegalLanguage()
    val colors = com.messenger.app.ui.theme.LocalCocoColors.current
    androidx.compose.foundation.text.selection.SelectionContainer {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(document.version, style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
            Text(document.intro, color = colors.primaryText)
            document.sections.forEach { section ->
                Text(section.title, style = MaterialTheme.typography.titleMedium, color = colors.primaryText)
                section.paragraphs.forEach { Text(it, color = colors.primaryText) }
            }
            TextButton(onClick = { openCocoLegalDestination(context, "https://8c86b74c-0d0f-4ee0-9b74-bbfcf06d3f9f.pub.instances.scw.cloud/" + documentID, false, language) }) {
                Text(if (cocoLegalLanguage() == "ru") "Открыть на сайте" else "Open on website", color = colors.primaryText)
            }
            TextButton(onClick = { openCocoLegalDestination(context, "mailto:bbdimka@gmail.com", true, language) }) {
                Text("bbdimka@gmail.com", color = colors.primaryText)
            }
        }
    }
}
private fun openCocoLegalDestination(context: android.content.Context, uri: String, email: Boolean, language: String) {
    val action = if (email) android.content.Intent.ACTION_SENDTO else android.content.Intent.ACTION_VIEW
    runCatching { context.startActivity(android.content.Intent(action, Uri.parse(uri))) }.onFailure {
        val message = if (email) {
            if (language == "ru") "Не найдено почтовое приложение. Адрес: bbdimka@gmail.com" else "No email app is available. Contact: bbdimka@gmail.com"
        } else {
            if (language == "ru") "Не удалось открыть сайт. Текст доступен на этом экране." else "Could not open the website. The text is available on this screen."
        }
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
