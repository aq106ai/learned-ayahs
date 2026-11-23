package com.quran.learnedplayer.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile

/** One encoded audio sample, kept in a scratch file until the muxer wants it. */
class AudioSample(val offset: Long, val size: Int, val presentationUs: Long, val flags: Int)

/** The video's soundtrack, encoded: its AAC format and samples, stored in [file]. */
class EncodedAudio(val format: MediaFormat, val file: File, val samples: List<AudioSample>)

/** A clip to put on the soundtrack, and the audio file it comes from. */
class ClipSource(val kind: ClipKind, val surah: Int, val ayah: Int, val file: File)

/**
 * The soundtrack and its timeline, plus word timings measured from the audio for clips that have
 * none of their own ([IntroWordTimings]). Keyed by the clip on the timeline.
 */
data class BuiltAudio(
    val timeline: VideoTimeline,
    val audio: EncodedAudio,
    val estimatedSegments: Map<TimelineClip, List<LongRange>> = emptyMap(),
)

/**
 * Builds the soundtrack: each clip decoded (MP3 from everyayah, or anything MediaExtractor reads),
 * converted to 44.1 kHz stereo, laid on the [VideoTimeline] with silence between clips, and
 * encoded once, start to finish, as AAC. The timeline it returns is measured from the decoded
 * audio itself, so the words light up against what is actually heard.
 */
object AudioPipeline {
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 128_000
    private const val TIMEOUT_US = 10_000L

    fun build(
        clips: List<ClipSource>,
        scratch: File,
        endCardMs: Long = 0,
        onClipDone: (Int) -> Unit = {},
        checkCancelled: () -> Unit = {},
        timingsFor: (surah: Int, ayah: Int) -> List<LongRange>? = { _, _ -> null },
        /** Timings of the Bismillah clip when it is the reciter's own 1:1 (cut where they end). */
        bismillahTimings: List<LongRange>? = null,
        /** Letter weights of a clip's words, for clips whose word timings should be estimated. */
        estimateWords: (ClipKind) -> List<Int>? = { null },
    ): BuiltAudio {
        val encoder = MediaCodec.createEncoderByType(MIME)
        val format = MediaFormat.createAudioFormat(MIME, PcmResampler.RATE, PcmResampler.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val out = RandomAccessFile(scratch, "rw").apply { setLength(0) }
        val writer = AacWriter(encoder, out)
        val timeline = VideoTimeline.Builder()
        val estimated = mutableMapOf<TimelineClip, List<LongRange>>()
        try {
            clips.forEachIndexed { index, clip ->
                checkCancelled()
                val pcm = trimToAyah(decode(clip.file), clip, timingsFor, bismillahTimings)
                // Silence up to where this clip starts.
                writer.silenceUntil(timeline.nextStartMs())
                val placed = timeline.add(clip.kind, clip.surah, clip.ayah, PcmResampler.durationMs(pcm.size))
                estimateWords(clip.kind)?.let { weights ->
                    val levels = IntroWordTimings.frameLevels(pcm, PcmResampler.RATE, PcmResampler.CHANNELS)
                    IntroWordTimings.estimate(levels, weights)?.let { estimated[placed] = it }
                }
                writer.write(pcm)
                onClipDone(index)
            }
            val built = timeline.build(endCardMs)
            writer.silenceUntil(built.totalMs)
            writer.finish()
            val audio = EncodedAudio(writer.outputFormat ?: error("The audio encoder gave no format"), scratch, writer.samples)
            // The timeline's clips are the ones placed above (build copies the same instances).
            return BuiltAudio(built, audio, estimated)
        } finally {
            out.close()
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    /**
     * An ayah file that runs on past its word timings (EveryAyah's Maher Al Muaiqly 2:258 holds
     * 2:259 as well) is cut where the ayah ends ([com.quran.learnedplayer.data.WordSync.ayahEndMs]),
     * so the video does not recite the next ayah under this one.
     */
    private fun trimToAyah(
        pcm: ShortArray,
        clip: ClipSource,
        timingsFor: (Int, Int) -> List<LongRange>?,
        bismillahTimings: List<LongRange>?,
    ): ShortArray {
        val timings = when (clip.kind) {
            ClipKind.AYAH -> timingsFor(clip.surah, clip.ayah)
            ClipKind.BISMILLAH -> bismillahTimings ?: return pcm
            ClipKind.AUDHU -> return pcm
        }
        val end = com.quran.learnedplayer.data.WordSync.ayahEndMs(timings, PcmResampler.durationMs(pcm.size))
            ?: return pcm
        val frames = end * PcmResampler.RATE / 1000L
        val samples = (frames * PcmResampler.CHANNELS).toInt().coerceAtMost(pcm.size)
        return pcm.copyOf(samples)
    }

    /** Feeds PCM to the AAC encoder and stores what comes out. */
    private class AacWriter(private val encoder: MediaCodec, private val out: RandomAccessFile) {
        val samples = mutableListOf<AudioSample>()
        var outputFormat: MediaFormat? = null
        private var framesWritten = 0L
        private val info = MediaCodec.BufferInfo()

        fun silenceUntil(ms: Long) {
            val target = ms * PcmResampler.RATE / 1000
            val missing = target - framesWritten
            if (missing > 0) write(ShortArray((missing * PcmResampler.CHANNELS).toInt()))
        }

        fun write(pcm: ShortArray) {
            var offset = 0
            while (offset < pcm.size) {
                val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) {
                    drain(false)
                    continue
                }
                val buffer = encoder.getInputBuffer(index)!!
                buffer.clear()
                val count = minOf(buffer.remaining() / 2, pcm.size - offset) / PcmResampler.CHANNELS * PcmResampler.CHANNELS
                val bytes = buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                bytes.put(pcm, offset, count)
                val ptsUs = framesWritten * 1_000_000L / PcmResampler.RATE
                encoder.queueInputBuffer(index, 0, count * 2, ptsUs, 0)
                offset += count
                framesWritten += count / PcmResampler.CHANNELS
                drain(false)
            }
        }

        fun finish() {
            while (true) {
                val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val ptsUs = framesWritten * 1_000_000L / PcmResampler.RATE
                    encoder.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
        }

        private fun drain(untilEnd: Boolean) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = encoder.outputFormat
                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(bytes)
                            val at = out.length()
                            out.seek(at)
                            out.write(bytes)
                            samples += AudioSample(at, info.size, info.presentationTimeUs, info.flags)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    /** The file's first audio track as 44.1 kHz stereo PCM. */
    fun decode(file: File): ShortArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            error("${file.name} has no audio")
        }
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        // The encoder's own silence at each end of an MP3 (its LAME header says how much). The
        // player drops it to join ayahs gaplessly; so does the video, where the file says.
        val delayFrames = inputFormat.intOrZero(MediaFormat.KEY_ENCODER_DELAY)
        val paddingFrames = inputFormat.intOrZero(MediaFormat.KEY_ENCODER_PADDING)
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcm = ShortArray(1 shl 16)
        var size = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (index >= 0) {
                    if (info.size > 0) {
                        val shorts = decoder.getOutputBuffer(index)!!.apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                        }.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        val count = shorts.remaining()
                        if (size + count > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, size + count))
                        shorts.get(pcm, size, count)
                        size += count
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
        }
        val from = (delayFrames * channels).coerceIn(0, size)
        val to = (size - paddingFrames * channels).coerceIn(from, size)
        return PcmResampler.toStereo(pcm.copyOfRange(from, to), rate, channels)
    }

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0).coerceAtLeast(0) else 0
}
