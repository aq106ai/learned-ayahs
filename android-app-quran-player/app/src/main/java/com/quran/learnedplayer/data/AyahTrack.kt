package com.quran.learnedplayer.data

data class AyahTrack(
    val index: Int,
    val globalId: Int,
    val surah: Int,
    val ayah: Int,
    val filename: String,
    val remoteUrl: String,
    val localPath: String? = null,
) {
    val label: String get() = SurahNames.trackTitle(surah, ayah)

    fun withLocalPath(path: String): AyahTrack = copy(localPath = path)
}

data class PlaylistSnapshot(
    val sourceFileName: String,
    val tracks: List<AyahTrack>,
    val refreshedAt: Long = System.currentTimeMillis(),
)

data class DownloadProgress(
    val completed: Int,
    val total: Int,
    val currentFile: String? = null,
)
