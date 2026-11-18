package com.messenger.app.data.selfhost

import com.messenger.app.data.api.AuthApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All results are fenced to the signed-in account and origin; no saved code or retry timer. */
class OwnerClaimController(private val owner: () -> String?, private val available: suspend () -> Boolean,
    private val submit: suspend (String) -> Unit, private val refreshRole: suspend () -> Unit) {
    private val mutable = MutableStateFlow(ClaimState.Checking)
    val state = mutable.asStateFlow()
    private var checkedOwner: String? = null
    private var claimedOwner: String? = null
    suspend fun checkAvailability(force: Boolean = false) {
        val captured = owner()
        if (!force && checkedOwner == captured && captured != null) return
        checkedOwner = captured; mutable.value = ClaimState.Checking
        if (claimedOwner != captured) claimedOwner = null
        if (claimedOwner != null) { retryRoleRefresh(); return }
        if (captured == null) { mutable.value = ClaimState.Unavailable; return }
        try {
            val result = available()
            if (owner() == captured) mutable.value = OwnerClaimPolicy.availability(result)
        } catch (cancel: CancellationException) {
            if (owner() == captured) { checkedOwner = null; mutable.value = ClaimState.Unavailable }
            throw cancel
        }
        catch (_: Exception) { if (owner() == captured) { checkedOwner = null; mutable.value = ClaimState.Unavailable } }
    }
    suspend fun claim(raw: String) {
        if (!OwnerClaimPolicy.canSubmit(mutable.value)) return
        val captured = owner() ?: return
        val code = OwnerClaimPolicy.normalize(raw)
        if (code == null) { mutable.value = ClaimState.Invalid; return }
        mutable.value = ClaimState.Submitting
        try {
            submit(code)
            if (owner() != captured) return
            claimedOwner = captured
            retryRoleRefresh()
        } catch (cancel: CancellationException) {
            if (owner() == captured) {
                checkedOwner = null
                mutable.value = if (claimedOwner == captured) ClaimState.RoleRefreshFailed else ClaimState.Failed
            }
            throw cancel
        }
        catch (error: Exception) {
            if (owner() == captured) mutable.value = OwnerClaimPolicy.refusal((error as? AuthApiException.Api)?.code)
        }
    }
    suspend fun retryRoleRefresh() {
        val captured = claimedOwner ?: return
        if (owner() != captured) return
        mutable.value = ClaimState.Submitting
        try {
            refreshRole()
            if (owner() == captured) mutable.value = ClaimState.Success
        } catch (cancel: CancellationException) {
            if (owner() == captured) { checkedOwner = null; mutable.value = ClaimState.RoleRefreshFailed }
            throw cancel
        }
        catch (_: Exception) { if (owner() == captured) mutable.value = ClaimState.RoleRefreshFailed }
    }
}
