package com.quran.learnedplayer.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads that are either complete or absent.
 *
 * The bytes go to a temporary `.part` file and are renamed into place only once the whole
 * response has arrived and looks like audio. Whether a file is cached is decided by its presence,
 * so writing straight to the final name meant a download cut short — the app killed, the
 * connection dropped — left a truncated MP3 that was then played, and never re-fetched, as if it
 * were whole. A captive-portal or error page answered with 200 is refused the same way.
 */
internal object AtomicDownload {
    private const val USER_AGENT = "LearnedAyahsPlayer/1.0"

    fun toFile(url: String, dest: File): Boolean {
        val dir = dest.parentFile ?: return false
        dir.mkdirs()
        // A unique temporary name: the same clip can be fetched by two jobs at once (play and
        // "save offline", or Recite's prefetch and its bulk fill), and with a shared name one
        // job's cleanup would delete or truncate the other's file mid-download.
        val part = try {
            File.createTempFile("${dest.name}.", ".part", dir)
        } catch (e: IOException) {
            return false
        }
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (connection.responseCode !in 200..299) return false
                val expected = connection.contentLengthLong
                connection.inputStream.use { input ->
                    part.outputStream().use { output -> input.copyTo(output) }
                }
                val size = part.length()
                if (size <= 0L || (expected > 0L && size != expected)) return false
                if (!looksLikeAudio(part)) return false
                part.renameTo(dest)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        } finally {
            if (part.exists()) part.delete()
        }
    }

    /** MP3 (an ID3 tag or an MPEG frame sync), WAV or Ogg — not an HTML page. */
    fun looksLikeAudio(file: File): Boolean {
        val head = ByteArray(4)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (read < 3) return false
        fun at(i: Int) = head[i].toInt() and 0xFF
        val id3 = at(0) == 'I'.code && at(1) == 'D'.code && at(2) == '3'.code
        val frameSync = at(0) == 0xFF && (at(1) and 0xE0) == 0xE0
        val riff = read >= 4 && String(head, Charsets.US_ASCII) == "RIFF"
        val ogg = read >= 4 && String(head, Charsets.US_ASCII) == "OggS"
        return id3 || frameSync || riff || ogg
    }
}
