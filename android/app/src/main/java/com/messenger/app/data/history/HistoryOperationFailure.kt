package com.messenger.app.data.history

/** Safe UI categories, without message contents, URLs or key material. */
class HistorySourceNotReady : Exception()
class HistorySourceChanged : Exception()
class HistoryMediaUnavailable : Exception()
class HistoryInsufficientSpace : Exception()
class HistoryRecoveryKeyMissing : Exception()
class HistoryUploadsDisabled : Exception()
