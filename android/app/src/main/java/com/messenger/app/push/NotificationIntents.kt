package com.messenger.app.push

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import com.messenger.app.MainActivity

object NotificationIntents {
    internal const val EXTRA_PROOF = "notification_route_proof"

    private fun proof(context: Context, target: NotificationTapTarget, create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(context, 0,
            Intent(context, NotificationRouteProofReceiver::class.java)
                .setAction("${context.packageName}.NOTIFICATION_ROUTE_PROOF")
                .setData(Uri.parse("coco://notification-proof/${target.workspace}/${target.owner}/${target.device}/${target.chat}/${target.message}")),
            PendingIntent.FLAG_IMMUTABLE or (if (create) 0 else PendingIntent.FLAG_NO_CREATE))

    fun open(context: Context, target: NotificationTapTarget): Intent {
        require(target.valid())
        return Intent(context, MainActivity::class.java)
            .setData(Uri.parse("coco://notification/${target.workspace}/${target.owner}/${target.chat}"))
            .putExtra(MessagePushHandlerImpl.EXTRA_WORKSPACE, target.workspace)
            .putExtra(MessagePushHandlerImpl.EXTRA_OWNER_ID, target.owner)
            .putExtra(MessagePushHandlerImpl.EXTRA_DEVICE_ID, target.device)
            .putExtra(MessagePushHandlerImpl.EXTRA_CHAT_ID, target.chat)
            .putExtra(MessagePushHandlerImpl.EXTRA_MESSAGE_ID, target.message)
            .putExtra(EXTRA_PROOF, proof(context, target, create = true))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    /** Structure is necessary, but never proves that an exported launch is our tap. */
    fun read(context: Context, intent: Intent): NotificationTapTarget? = runCatching {
        val target = NotificationTapTarget(
            intent.getStringExtra(MessagePushHandlerImpl.EXTRA_WORKSPACE) ?: return@runCatching null,
            intent.getStringExtra(MessagePushHandlerImpl.EXTRA_OWNER_ID) ?: return@runCatching null,
            intent.getStringExtra(MessagePushHandlerImpl.EXTRA_DEVICE_ID) ?: return@runCatching null,
            intent.getStringExtra(MessagePushHandlerImpl.EXTRA_CHAT_ID) ?: return@runCatching null,
            intent.getStringExtra(MessagePushHandlerImpl.EXTRA_MESSAGE_ID) ?: return@runCatching null,
        ).takeIf { it.valid() } ?: return@runCatching null
        @Suppress("DEPRECATION")
        val supplied = intent.getParcelableExtra<PendingIntent>(EXTRA_PROOF) ?: return@runCatching null
        val expected = proof(context, target, create = false)
        target.takeIf { NotificationRouteProofPolicy.accepts(android.os.Process.myUid(), supplied.creatorUid,
            expected != null && supplied == expected) }
    }.getOrNull()

    /** Preserve a pending verified tap over recreation without retaining framework launch input. */
    fun verifiedCopy(context: Context, source: Intent): Intent? {
        val target = read(context, source) ?: return null
        @Suppress("DEPRECATION")
        val capability = source.getParcelableExtra<PendingIntent>(EXTRA_PROOF) ?: return null
        return Intent(context, MainActivity::class.java)
            .putExtra(MessagePushHandlerImpl.EXTRA_WORKSPACE, target.workspace)
            .putExtra(MessagePushHandlerImpl.EXTRA_OWNER_ID, target.owner)
            .putExtra(MessagePushHandlerImpl.EXTRA_DEVICE_ID, target.device)
            .putExtra(MessagePushHandlerImpl.EXTRA_CHAT_ID, target.chat)
            .putExtra(MessagePushHandlerImpl.EXTRA_MESSAGE_ID, target.message)
            .putExtra(EXTRA_PROOF, capability)
    }
}
