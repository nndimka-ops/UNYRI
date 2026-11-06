package com.messenger.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Survives rotation, but never retains profile/search state across logins. */
class AccountViewModelStores : ViewModel() {
    private var epoch: Int? = null
    private var owner: ViewModelStoreOwner? = null

    fun forSession(sessionEpoch: Int): ViewModelStoreOwner {
        if (epoch != sessionEpoch) {
            owner?.viewModelStore?.clear()
            owner = object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
            epoch = sessionEpoch
        }
        return checkNotNull(owner)
    }

    override fun onCleared() {
        owner?.viewModelStore?.clear()
        owner = null
    }
}
