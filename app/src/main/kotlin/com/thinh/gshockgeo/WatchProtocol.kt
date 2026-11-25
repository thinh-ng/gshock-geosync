package com.thinh.gshockgeo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.UUID

/** Casio GBX-100 BLE frames, from the decompiled G-SHOCK MOVE app and tests against the watch. */
object WatchProtocol {
    private fun casioUuid(prefix: String): UUID = UUID.fromString("$prefix-b012-49a8-b1f8-394fb2032b0f")

    val SERVICE: UUID = casioUuid("26eb000d")
    val DATA_REQUEST: UUID = casioUuid("26eb0023")
    val CONVOY: UUID = casioUuid("26eb0024")
    val READ_REQUEST: UUID = casioUuid("26eb002c")
    val ALL_FEATURES: UUID = casioUuid("26eb002d")
    val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CLASS_TIME: Byte = 0x09
    const val CLASS_LOCATION: Byte = 0x24
    const val CLASS_TIDE: Byte = 0x2E
    const val TIDE_SETTINGS_UNIT: Byte = 0x02
    const val TIDE_SOURCE_BUILT_IN: Byte = 0x01
    const val TIDE_SOURCE_APP_PORT: Byte = 0x04
    const val CATEGORY_TIDE: Byte = 0x23
    const val HOME_TIME: Byte = 0x00

    // Written big-endian but read back little-endian; a little-endian write is silently dropped.
    fun locationFrame(lat: Double, lon: Double, order: ByteOrder = ByteOrder.BIG_ENDIAN, radioId: Byte = 0): ByteArray =
        ByteBuffer.allocate(20).order(order)
            .put(CLASS_LOCATION).put(HOME_TIME).put(1)
            .putDouble(lat).putDouble(lon)
            .put(radioId)
            .array()

    fun parseLocation(frame: ByteArray): Pair<Double, Double>? {
        if (frame.size < 19 || frame[0] != CLASS_LOCATION || frame[2] != 1.toByte()) return null
        val buf = ByteBuffer.wrap(frame, 3, 16).order(ByteOrder.LITTLE_ENDIAN)
        return buf.double to buf.double
    }

    fun timeFrame(now: LocalDateTime = LocalDateTime.now()): ByteArray =
        ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
            .put(CLASS_TIME)
            .putShort(now.year.toShort())
            .put(now.monthValue.toByte()).put(now.dayOfMonth.toByte())
            .put(now.hour.toByte()).put(now.minute.toByte()).put(now.second.toByte())
            .put(now.dayOfWeek.value.toByte())
            .put((now.nano / 1_000_000 * 256 / 1000).toByte())
            .put(1)
            .array()
}

fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }
