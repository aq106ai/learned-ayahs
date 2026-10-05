package com.quran.learnedplayer

import android.content.Context
import com.quran.learnedplayer.data.Reciter
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The shared recitation corpus in `androidTest/assets/recitation`: real everyayah.com recordings
 * with a manifest naming the reciter, surah and ayah each one is.
 *
 * Every test that plays audio at the microphone reads it through here, so they are scored on
 * identical clips — a comparison across runs is worthless otherwise.
 *
 * Regenerate with `python .setup/make_recitation_corpus.py`. Al-Ikhlas is present in more than
 * one voice so the coach can be held to the same standard whichever reciter a student follows,
 * and one clip is a **deliberate mistake**: the same recording with a single word replaced by
 * another word from the same reciter (see [Clip.mistakeWordIndex]).
 */
object RecitationCorpus {

    data class Clip(
        val reciter: String,
        val surah: Int,
        val ayah: Int,
        val file: String,
        val seconds: Double,
        /** Content-word index deliberately misrecited in this clip, or null for a correct one. */
        val mistakeWordIndex: Int? = null,
        /** The word said in its place, for failure messages. */
        val saidInstead: String? = null,
    ) {
        val isCorrectRecitation: Boolean get() = mistakeWordIndex == null
    }

    fun load(testContext: Context): List<Clip> {
        val manifest = JSONArray(
            testContext.assets.open("recitation/manifest.json").bufferedReader().use { it.readText() },
        )
        return (0 until manifest.length()).map { i ->
            val entry = manifest.getJSONObject(i)
            Clip(
                reciter = entry.getString("reciter"),
                surah = entry.getInt("surah"),
                ayah = entry.getInt("ayah"),
                file = entry.getString("file"),
                seconds = entry.getDouble("seconds"),
                mistakeWordIndex = if (entry.has("mistakeWordIndex")) {
                    entry.getInt("mistakeWordIndex")
                } else {
                    null
                },
                saidInstead = entry.optString("saidInstead").takeIf { it.isNotBlank() },
            )
        }
    }

    /** The correct recitation of one surah by one reciter, in ayah order. */
    fun surah(testContext: Context, reciter: Reciter, surah: Int): List<Clip> =
        load(testContext)
            .filter { it.reciter == reciter.name && it.surah == surah && it.isCorrectRecitation }
            .sortedBy { it.ayah }

    /** The one clip of [surah]:[ayah] that has a word wrong, if the corpus carries one. */
    fun misrecited(testContext: Context, reciter: Reciter, surah: Int, ayah: Int): Clip? =
        load(testContext).firstOrNull {
            it.reciter == reciter.name && it.surah == surah && it.ayah == ayah &&
                !it.isCorrectRecitation
        }

    /** Mono 16kHz float samples in [-1, 1], the shape a recognizer wants them in. */
    fun samples(testContext: Context, clip: Clip): FloatArray {
        val bytes = testContext.assets.open("recitation/${clip.file}").use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var dataOffset = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                dataOffset = pos + 8
                dataLength = size
                break
            }
            pos += 8 + size + (size and 1)
        }
        require(dataOffset > 0) { "no data chunk in ${clip.file}" }
        val sampleCount = minOf(dataLength, bytes.size - dataOffset) / 2
        return FloatArray(sampleCount) { i -> buf.getShort(dataOffset + i * 2) / 32768f }
    }

    /**
     * Copies a clip out to a real file. MediaPlayer can be pointed at an asset file descriptor,
     * but only while the asset is stored uncompressed; copying removes that dependency on the
     * packer's compression choices.
     */
    fun extractToCache(testContext: Context, appContext: Context, clip: Clip): File {
        val out = File(appContext.cacheDir, "corpus-${clip.file}")
        if (!out.exists() || out.length() == 0L) {
            testContext.assets.open("recitation/${clip.file}").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }
}
