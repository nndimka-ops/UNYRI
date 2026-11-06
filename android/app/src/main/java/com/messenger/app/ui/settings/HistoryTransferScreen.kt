package com.messenger.app.ui.settings

import android.graphics.Bitmap
import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.history.*
import com.messenger.app.data.api.AuthApiException
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.*

@Composable
fun HistoryTransferScreen(session:HistoryKeySettingsSession,receive:Boolean,callBusy:Boolean,
    onOpenBackupSettings:(()->Unit)?=null,onReportProblem:(()->Unit)?=null,onClose:()->Unit) {
    val scope=rememberCoroutineScope()
    val context=androidx.compose.ui.platform.LocalContext.current
    val transferApp=context.applicationContext as com.messenger.app.MessengerApp
    val transferAuth by transferApp.authRepository.authState.collectAsState()
    val transferUser=(transferAuth as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user
    val serverLabel=android.net.Uri.parse(com.messenger.app.data.api.ServerEndpoint.current()).host.orEmpty()
    var qr by remember { mutableStateOf<HistoryTransferQR?>(null) }
    var record by remember { mutableStateOf<HistoryTransferRecord?>(null) }
    var private by remember { mutableStateOf<ByteArray?>(null) }
    var pasted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmMatch by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var done by remember { mutableStateOf(false) }
    var handoff by remember { mutableStateOf(false) }
    var preparedAt by remember { mutableStateOf<Long?>(null) }
    var expired by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }
    var pendingApproval by remember { mutableStateOf<HistoryTransferQR?>(null) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    var phase by remember { mutableStateOf(HistoryTransferPhase.PREPARE) }
    var failureDetails by remember { mutableStateOf<Pair<HistoryTransferPhase,HistoryTransferFailure>?>(null) }
    var showFailureDetails by remember { mutableStateOf(false) }
    val keyguard=remember(context) { context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager }
    var deviceSecure by remember(keyguard) { mutableStateOf(keyguard?.isDeviceSecure==true) }
    val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner,keyguard) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)deviceSecure=keyguard?.isDeviceSecure==true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val latestQR=rememberUpdatedState(qr)
    val latestPrivate=rememberUpdatedState(private)
    val latestDone=rememberUpdatedState(done || handoff)
    fun reportFailure(stage:HistoryTransferPhase,failure:HistoryTransferFailure,text:Int) {
        val details=stage to failure
        if(failureDetails!=details)transferApp.performanceDiagnostics.historyTransferFailure(stage,failure)
        failureDetails=details;error=text
    }
    fun reportFailure(stage:HistoryTransferPhase,failure:Throwable,text:Int?=null) {
        val details=classifyHistoryTransferFailure(failure)
        val copy=text ?: when(details.category) {
            HistoryTransferFailureCategory.SERVER -> R.string.history_transfer_server_unavailable
            HistoryTransferFailureCategory.REQUEST_REJECTED -> R.string.history_transfer_request_rejected
            HistoryTransferFailureCategory.NETWORK -> R.string.history_transfer_network
            HistoryTransferFailureCategory.UNEXPECTED -> R.string.history_transfer_unexpected
            else -> historyOperationErrorText(failure)
        }
        reportFailure(stage,details,copy)
    }
    fun action(stage:HistoryTransferPhase,block:suspend ()->Unit) {
        if(busy)return; busy=true; error=null;failureDetails=null;showFailureDetails=false;phase=stage
        actionJob=scope.launch { try { block() } catch(e:CancellationException){throw e} catch(failure:Exception){reportFailure(phase,failure)} finally{busy=false} }
    }
    fun parse(text:String) {
        if(text.toByteArray().size>1024) { reportFailure(HistoryTransferPhase.VALIDATE_CODE,HistoryTransferFailure(HistoryTransferFailureCategory.INVALID_CODE),R.string.history_transfer_invalid_code);return }
        runCatching { session.validateQR(text) }.onSuccess { qr=it;record=null;confirmMatch=false;pasted="";error=null;failureDetails=null }
            .onFailure { if(it is HistoryTransferValidationFailure)reportFailure(HistoryTransferPhase.VALIDATE_CODE,it)
                else reportFailure(HistoryTransferPhase.VALIDATE_CODE,HistoryTransferFailure(HistoryTransferFailureCategory.INVALID_CODE),R.string.history_transfer_invalid_code) }
    }
    fun createRequest() = action(HistoryTransferPhase.CREATE_REQUEST) {
        private?.fill(0); private=null; qr=null;record=null;expired=false;confirmMatch=false
        val secret=HistoryTransferCrypto.privateKey(); private=secret
        val created=session.createTransfer(HistoryTransferCrypto.public(secret)); qr=created.qr();record=created
    }
    val scanner=rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::parse) }
    val prompt=stringResource(R.string.history_qr_scan)
    val latestCallBusy=rememberUpdatedState(callBusy)
    val approvalLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pinned=pendingApproval;pendingApproval=null
        if(result.resultCode==Activity.RESULT_OK && pinned!=null && latestQR.value==pinned && !latestCallBusy.value) {
            action(HistoryTransferPhase.APPROVE) { record=session.approveTransfer(pinned) }
        }
    }
    fun approveWithDeviceUnlock(pinned:HistoryTransferQR) {
        val lock=keyguard
        if(lock?.isDeviceSecure!=true){deviceSecure=false;reportFailure(HistoryTransferPhase.DEVICE_UNLOCK,HistoryTransferFailure(HistoryTransferFailureCategory.DEVICE_LOCK),R.string.history_transfer_device_lock_required);return}
        @Suppress("DEPRECATION")
        val intent=lock.createConfirmDeviceCredentialIntent(context.getString(R.string.history_qr_approve),context.getString(R.string.history_qr_approve_notice))
        if(intent==null){reportFailure(HistoryTransferPhase.DEVICE_UNLOCK,HistoryTransferFailure(HistoryTransferFailureCategory.DEVICE_LOCK),R.string.history_transfer_device_lock_required);return}
        pendingApproval=pinned
        runCatching { approvalLauncher.launch(intent) }.onFailure { pendingApproval=null;reportFailure(HistoryTransferPhase.DEVICE_UNLOCK,HistoryTransferFailure(HistoryTransferFailureCategory.DEVICE_LOCK),R.string.history_transfer_device_lock_required) }
    }
    fun launchScanner() {
        if(latestCallBusy.value || receive)return
        if(keyguard?.isDeviceSecure!=true){deviceSecure=false;return}
        cameraDenied=false
        runCatching { scanner.launch(ScanOptions().setCaptureActivity(com.messenger.app.capture.SharedQrCaptureActivity::class.java)
            .setOrientationLocked(false).setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt)
            .setBeepEnabled(false).setBarcodeImageEnabled(false).setTimeout(60_000L)) }
            .onFailure { reportFailure(HistoryTransferPhase.SCAN,HistoryTransferFailure(HistoryTransferFailureCategory.CAMERA),R.string.history_transfer_camera_failed) }
    }
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted)launchScanner() else cameraDenied=true
    }
    DisposableEffect(Unit) { onDispose {
        session.clearTransferPreparation()
        latestPrivate.value?.fill(0)
        val closing=latestQR.value
        if(closing!=null && !latestDone.value) CoroutineScope(Dispatchers.IO).launch { runCatching { withTimeout(5000){session.cancelTransfer(closing)} } }
    } }
    LaunchedEffect(receive,deviceSecure,callBusy) {
        if(!receive && deviceSecure && !callBusy && preparedAt==null && error==null) action(HistoryTransferPhase.PREPARE) {
            preparedAt=session.prepareTransfer { HistoryAutoBackup.success(context,session.scopeId()) }
        }
    }
    LaunchedEffect(qr?.transfer,handoff) {
        val pinned=qr ?: return@LaunchedEffect
        if(!handoff) { delay((pinned.expires-System.currentTimeMillis()).coerceAtLeast(0)); expired=true;actionJob?.cancelAndJoin();if(!handoff){private?.fill(0);private=null;record=null;confirmMatch=false} }
    }
    LaunchedEffect(qr?.transfer,receive,handoff,expired) {
        val pinned=qr ?: return@LaunchedEffect
        if(receive && !handoff && !expired) {
                var failures=0
                var wait=3000L
                while(isActive && !expired && !handoff) {
                  try {
                    delay(wait); val current=session.readTransfer(pinned); record=current;error=null;failureDetails=null;failures=0;wait=3000L
                    if(current.status!="waiting")break
                  } catch(e:CancellationException){throw e} catch(e:Exception){
                    reportFailure(HistoryTransferPhase.WAIT,e)
                    val status=when(e){is AuthApiException.Api->e.httpStatus;is AuthApiException.Network->e.httpStatus;else->null}
                    val pause=HistoryTransferRetryPolicy.delay(status,e is AuthApiException.Network && status==null,failures,System.currentTimeMillis(),pinned.expires)
                    if(pause==null){private?.fill(0);private=null;record=null;qr=null;break}
                    failures++;wait=pause
                  }
                }
        }
    }
    SettingsScaffold(stringResource(if(receive)R.string.history_transfer_new_role else R.string.history_transfer_old_role),onClose) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(when {
                done -> R.string.history_transfer_done_title
                handoff -> R.string.history_transfer_restoring
                record?.status=="ready" -> R.string.history_transfer_compare_title
                receive -> R.string.history_transfer_new_title
                preparedAt!=null -> R.string.history_transfer_scan_title
                else -> R.string.history_transfer_prepare_title
            }),style=MaterialTheme.typography.headlineSmall)
            if(!done)Text(stringResource(R.string.history_transfer_direction),style=MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.history_transfer_account,transferUser?.username.orEmpty()),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.history_transfer_server,serverLabel),style=MaterialTheme.typography.bodySmall)
            if(busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(when(phase) {
                    HistoryTransferPhase.CREATE_REQUEST -> R.string.history_transfer_progress_request
                    HistoryTransferPhase.APPROVE -> R.string.history_transfer_progress_approve
                    HistoryTransferPhase.ACCEPT,HistoryTransferPhase.RESTORE -> R.string.history_transfer_restoring
                    else -> R.string.history_qr_preparing
                }),style=MaterialTheme.typography.bodyMedium)
            }
            error?.let { Text(stringResource(it),color=MaterialTheme.colorScheme.error) }
            if(error!=null && failureDetails!=null) {
                if(!receive && onOpenBackupSettings!=null && failureDetails?.second?.category in setOf(
                        HistoryTransferFailureCategory.KEY_MISSING,HistoryTransferFailureCategory.UPLOADS_DISABLED,HistoryTransferFailureCategory.WRONG_KEY)) {
                    SettingsPrimaryButton(enabled=!busy,onClick=onOpenBackupSettings){Text(stringResource(R.string.history_settings_title))}
                }
                SettingsSecondaryButton(onClick={showFailureDetails=!showFailureDetails}){Text(stringResource(R.string.history_transfer_error_details))}
                if(showFailureDetails) {
                    val details=requireNotNull(failureDetails)
                    Text(stringResource(R.string.history_transfer_error_reference,details.second.reference(details.first)),style=MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.history_transfer_report_hint),style=MaterialTheme.typography.bodySmall)
                    if(onReportProblem!=null)SettingsSecondaryButton(enabled=!busy,onClick=onReportProblem){Text(stringResource(R.string.feedback_title))}
                }
            }
            if(!receive && !deviceSecure && !done && !handoff) {
                Text(stringResource(R.string.history_transfer_lock_before_prepare))
                SettingsPrimaryButton(enabled=!busy,onClick={
                    runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                        .onFailure { reportFailure(HistoryTransferPhase.REQUIREMENTS,HistoryTransferFailure(HistoryTransferFailureCategory.DEVICE_LOCK),R.string.history_transfer_device_lock_required) }
                }){Text(stringResource(R.string.history_transfer_open_lock_settings))}
            }
            if(done)Text(stringResource(R.string.history_transfer_done_body),color=com.messenger.app.ui.theme.LocalCocoColors.current.success)
            if(expired) {
                Text(stringResource(R.string.history_transfer_expired))
                SettingsPrimaryButton(enabled=!busy,onClick={
                    if(receive)createRequest() else {qr=null;expired=false;error=null}
                }){Text(stringResource(if(receive)R.string.history_transfer_show_qr else R.string.history_transfer_scan_again))}
            }
            if(receive && qr==null && !expired && !handoff && !done) {
                Text(stringResource(R.string.history_transfer_receive_intro))
                SettingsPrimaryButton(enabled=!busy,onClick={createRequest()}) {Text(stringResource(R.string.history_transfer_show_qr))}
            }
            if(handoff && !done) {
                Text(stringResource(R.string.history_transfer_resume_body))
                if(!busy)SettingsPrimaryButton(onClick={action(HistoryTransferPhase.RESTORE){session.restore();done=true}}){Text(stringResource(R.string.history_transfer_continue))}
            }
            if(!receive && record?.status!="ready" && !expired && !handoff && !done) {
                if(qr==null) {
                    if(preparedAt==null) {
                        Text(stringResource(R.string.history_transfer_prepare_body))
                        if(!busy && deviceSecure)SettingsPrimaryButton(enabled=!callBusy,onClick={action(HistoryTransferPhase.PREPARE){preparedAt=session.prepareTransfer { HistoryAutoBackup.success(context,session.scopeId()) }}}){Text(stringResource(R.string.history_qr_prepare))}
                    } else {
                        Text(stringResource(R.string.history_qr_ready_scan))
                        Text(stringResource(R.string.history_transfer_scan_tips),style=MaterialTheme.typography.bodySmall)
                        SettingsPrimaryButton(enabled=!busy && !callBusy && deviceSecure,onClick={
                            if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)launchScanner()
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        }) { Text(prompt) }
                        SettingsSecondaryButton(enabled=!busy,onClick={manualEntry=!manualEntry}){Text(stringResource(R.string.history_qr_manual))}
                        if(manualEntry) {
                            OutlinedTextField(value=pasted,onValueChange={pasted=it.take(1024)},label={Text(stringResource(R.string.history_qr_paste))},modifier=Modifier.fillMaxWidth())
                            SettingsSecondaryButton(enabled=!busy && pasted.isNotBlank(),onClick={parse(pasted)}){Text(stringResource(R.string.history_qr_check))}
                        }
                    }
                }
                if(callBusy)Text(stringResource(R.string.history_qr_end_call))
                if(cameraDenied) {
                    Text(stringResource(R.string.history_qr_camera_denied))
                    SettingsSecondaryButton(onClick={runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) }.onFailure {error=R.string.history_transfer_camera_failed}}){Text(stringResource(R.string.history_qr_open_settings))}
                }
            }
            qr?.takeIf { !expired && !handoff && !done }?.let { pinned ->
                if(receive && record?.status=="waiting") {
                    Text(stringResource(R.string.history_transfer_qr_not_key),style=MaterialTheme.typography.bodyMedium)
                    val bitmap by produceState<Bitmap?>(null,pinned.transfer) {
                        try { value=withContext(Dispatchers.Default) {
                            val size=640
                            val matrix=MultiFormatWriter().encode(pinned.text(),BarcodeFormat.QR_CODE,size,size,
                                mapOf(EncodeHintType.MARGIN to 4,EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,EncodeHintType.CHARACTER_SET to "UTF-8"))
                            val pixels=IntArray(size*size) { index -> if(matrix[index%size,index/size])android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                            Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).apply { setPixels(pixels,0,size,0,0,size,size) }
                        } } catch(cancelled:CancellationException){throw cancelled}
                        catch(failure:Exception){reportFailure(HistoryTransferPhase.RENDER_QR,failure,R.string.history_transfer_qr_render_failed)}
                    }
                    Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                        val image=bitmap
                        if(image!=null)Image(image.asImageBitmap(),contentDescription=stringResource(R.string.history_qr_receive),
                            modifier=Modifier.widthIn(max=320.dp).fillMaxWidth().aspectRatio(1f).background(Color.White).padding(12.dp),filterQuality=FilterQuality.None)
                        else if(error==null)CircularProgressIndicator()
                    }
                    Text(stringResource(R.string.history_qr_waiting))
                    // Public QR payload can be copied manually without exposing any key.
                    SettingsSecondaryButton(onClick={manualEntry=!manualEntry}) { Text(stringResource(R.string.history_qr_manual)) }
                    if(manualEntry)androidx.compose.foundation.text.selection.SelectionContainer { Text(pinned.text(),style=MaterialTheme.typography.bodySmall) }
                }
                if(!receive && record?.status!="ready") {
                    Text(stringResource(R.string.history_qr_approve_notice))
                    SettingsPrimaryButton(enabled=!busy && pendingApproval==null && !callBusy,onClick={approveWithDeviceUnlock(pinned)}){Text(stringResource(R.string.history_qr_approve))}
                }
                record?.takeIf { it.status=="ready" }?.let { ready ->
                    val code=runCatching { HistoryTransferCrypto.comparison(pinned,ready) }.getOrNull()
                    if(code!=null) {
                        Text(code,style=MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.history_qr_compare))
                        if(receive) {
                            SettingsPrimaryButton(enabled=!busy,onClick={confirmMatch=true}){Text(stringResource(R.string.history_transfer_match_button))}
                            if(confirmMatch)AlertDialog(onDismissRequest={confirmMatch=false},title={Text(stringResource(R.string.history_transfer_match_title))},text={Text(stringResource(R.string.history_transfer_match_body))},confirmButton={TextButton(onClick={confirmMatch=false;action(HistoryTransferPhase.ACCEPT) {
                                session.acceptTransfer(pinned,requireNotNull(private),ready,true) { handoff=true;private?.fill(0);private=null;record=null }
                                phase=HistoryTransferPhase.RESTORE
                                session.restore();done=true
                            }}){Text(stringResource(R.string.history_transfer_start))}},dismissButton={TextButton(onClick={confirmMatch=false}){Text(stringResource(R.string.cancel))}})
                        } else Text(stringResource(R.string.history_transfer_confirm_new))
                    } else Text(stringResource(R.string.history_transfer_invalid_code),color=MaterialTheme.colorScheme.error)
                }
            }
            SettingsSecondaryButton(onClick=onClose){Text(stringResource(if(done)R.string.history_qr_close else R.string.cancel))}
        }
    }
}
