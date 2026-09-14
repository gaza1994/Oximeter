package com.garethjohnstone.oximeter.ble

/**
 * Reassembles the notification stream into whole frames.
 *
 * Frames are a fixed 69 bytes: 0xFF sync, a length byte of 0x44, 66 bytes of
 * payload and a checksum. No byte stuffing - instead every payload byte is
 * limited to 7 bits, which is what keeps 0xFF unambiguous as the sync marker.
 *
 * The checksum is verified before a frame is emitted, so joining mid-stream or
 * losing a notification costs one frame rather than corrupting the readings.
 */
class FrameAssembler(private val onFrame: (ByteArray) -> Unit) {

    private val buf = ArrayList<Byte>(4 * FRAME_LEN)

    var badChecksums = 0
        private set
    var resyncs = 0
        private set

    fun feed(chunk: ByteArray) {
        chunk.forEach { buf.add(it) }

        while (true) {
            val start = indexOfSync()
            if (start < 0) {
                if (buf.size > 4 * FRAME_LEN) {
                    repeat(buf.size - FRAME_LEN) { buf.removeAt(0) }
                }
                return
            }
            if (start > 0) {
                resyncs++
                repeat(start) { buf.removeAt(0) }
            }
            if (buf.size < FRAME_LEN) return

            val frame = ByteArray(FRAME_LEN) { buf[it] }
            if (checksumOf(frame) == (frame[68].toInt() and 0xFF)) {
                repeat(FRAME_LEN) { buf.removeAt(0) }
                onFrame(frame)
            } else {
                badChecksums++
                repeat(2) { buf.removeAt(0) }   // false sync, step past it
            }
        }
    }

    private fun indexOfSync(): Int {
        for (i in 0 until buf.size - 1) {
            if ((buf[i].toInt() and 0xFF) == SYNC && (buf[i + 1].toInt() and 0xFF) == LENGTH_BYTE) {
                return i
            }
        }
        return -1
    }

    private fun checksumOf(frame: ByteArray): Int {
        var sum = 0
        for (i in 1..67) sum += frame[i].toInt() and 0xFF
        return sum and 0xFF
    }

    fun reset() {
        buf.clear()
        badChecksums = 0
        resyncs = 0
    }

    companion object {
        const val SYNC = 0xFF
        const val LENGTH_BYTE = 0x44
        const val FRAME_LEN = 69
    }
}
