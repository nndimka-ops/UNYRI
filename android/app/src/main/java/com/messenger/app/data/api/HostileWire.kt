package com.messenger.app.data.api

class HostileWireException : IllegalArgumentException("Invalid server numeric value")

object HostileWire {
    fun range(value: Long, min: Long, max: Long): Long {
        if (value !in min..max) throw HostileWireException()
        return value
    }
    fun device(value: Int): Int = range(value.toLong(), 1, 127).toInt()
    fun id(value: Int): Int = range(value.toLong(), 0, Int.MAX_VALUE.toLong()).toInt()
    fun ice(value: Int): Int = range(value.toLong(), 0, 16).toInt()
    fun dimension(value: Int): Int = range(value.toLong(), 1, 65535).toInt()
    fun duration(value: Long): Long = range(value, 0, Int.MAX_VALUE.toLong())
    fun size(value: Long): Long = range(value, 0, 16L * 1024 * 1024 * 1024)
    fun nonnegative(value: Long): Long = range(value, 0, Long.MAX_VALUE)
}

internal fun DeviceBundleDto.validateWireIntegers() {
    HostileWire.device(signalDeviceId)
    listOfNotNull(registrationId, signedPrekeyId, kyberPrekeyId, oneTimePrekeyId).forEach(HostileWire::id)
}
