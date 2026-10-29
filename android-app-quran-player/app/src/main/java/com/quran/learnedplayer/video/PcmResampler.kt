package com.quran.learnedplayer.video

/**
 * Converts decoded 16-bit PCM to the one format the video's audio track uses: [RATE] Hz stereo.
 * everyayah's files are mostly 44.1 kHz stereo, but not all of them, and one AAC track can only
 * have one sample rate. Linear interpolation is plenty for speech.
 */
object PcmResampler {
    const val RATE = 44_100
    const val CHANNELS = 2

    fun toStereo(input: ShortArray, rate: Int, channels: Int): ShortArray {
        require(channels >= 1) { "No channels" }
        val frames = input.size / channels
        // Mix down or up to stereo first.
        val stereo = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val left = input[f * channels].toInt()
            val right = if (channels >= 2) input[f * channels + 1].toInt() else left
            stereo[f * 2] = left.toShort()
            stereo[f * 2 + 1] = right.toShort()
        }
        if (rate == RATE || frames == 0) return stereo
        val outFrames = (frames.toLong() * RATE / rate).toInt()
        val out = ShortArray(outFrames * 2)
        val step = rate.toDouble() / RATE
        for (o in 0 until outFrames) {
            val position = o * step
            val i = position.toInt().coerceAtMost(frames - 1)
            val j = (i + 1).coerceAtMost(frames - 1)
            val t = position - i
            for (c in 0..1) {
                val a = stereo[i * 2 + c]
                val b = stereo[j * 2 + c]
                out[o * 2 + c] = (a + (b - a) * t).toInt().toShort()
            }
        }
        return out
    }

    fun durationMs(stereoSamples: Int): Long = stereoSamples / CHANNELS * 1000L / RATE
}
