package com.garethjohnstone.oximeter.ble

/**
 * One 69-byte frame, decoded.
 *
 *   [0]     0xFF sync
 *   [1]     0x44 length of the remainder
 *   [2]     device type, 0x01 = WT1, 0x30 = M70C
 *   [3]     constant 0x00
 *   [4]     SpO2 %                      127 = invalid
 *   [5]     pulse, bpm                  127 = invalid
 *   [6]     perfusion index, low 7 bits
 *   [7]     perfusion index, high 6 bits
 *   [8..37] 30 samples, range 0-31, purpose not established
 *   [38..67] 30 plethysmograph samples, range 0-127
 *   [68]    checksum, sum of bytes 1..67
 *
 * Perfusion index is 13 bits in hundredths of a percent, split across two bytes
 * because no payload byte may exceed 0x7F. 0x1FFF means invalid. Confirmed
 * against the device's own display.
 *
 * Two frames a second, so both traces run at 60 Hz.
 */
data class LiveFrame(
    val deviceType: Int,
    val spo2: Int?,
    val heartRate: Int?,
    val perfusionIndex: Float?,
    val pleth: IntArray,
    val secondaryTrace: IntArray
)

object FrameParser {

    private const val INVALID_BYTE = 127
    private const val INVALID_PI = 0x1FFF

    fun parse(f: ByteArray): LiveFrame? {
        if (f.size < FrameAssembler.FRAME_LEN) return null

        fun u(i: Int) = f[i].toInt() and 0xFF

        val piRaw = ((u(7) and 0x3F) shl 7) or (u(6) and 0x7F)
        val spo2 = u(4)
        val hr = u(5)

        return LiveFrame(
            deviceType = u(2),
            spo2 = if (spo2 == INVALID_BYTE || spo2 == 0) null else spo2,
            heartRate = if (hr == INVALID_BYTE || hr == 0) null else hr,
            perfusionIndex = if (piRaw == INVALID_PI) null else piRaw / 100f,
            pleth = IntArray(30) { u(38 + it) and 0x7F },
            secondaryTrace = IntArray(30) { u(8 + it) and 0x7F }
        )
    }

    fun deviceTypeName(type: Int) = when (type) {
        0x01 -> "WT1"
        0x30 -> "M70C"
        else -> "type 0x%02X".format(type)
    }
}
