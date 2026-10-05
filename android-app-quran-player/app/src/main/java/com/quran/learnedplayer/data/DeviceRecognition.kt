package com.quran.learnedplayer.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.Executor

/**
 * What this phone's speech service can do with Arabic, and how to get it more.
 *
 * Recite needs three things the platform only started exposing in Android 13 (API 33): a way to
 * *ask* whether Arabic is supported on-device, a way to *fetch* the on-device model, and a way to
 * hand the recogniser audio we captured ourselves. Below that the answers were guesswork —
 * `isRecognitionAvailable()` says nothing about languages, and the only way to get an offline pack
 * was to send the user into system settings and hope.
 *
 * All the API-33 surface is confined here so the rest of the app never has to version-check.
 */
object DeviceRecognition {

    private const val TAG = "DeviceRecognition"

    /**
     * Arabic locales in preference order.
     *
     * Speech services disagree about which tags they accept: Google answers `ar-SA`, some Samsung
     * builds only answer bare `ar`. Recite tries them in order rather than failing on the first.
     */
    val ARABIC_LOCALES = listOf("ar-SA", "ar", "ar-EG")

    /**
     * Whether Recite can run at all on this device.
     *
     * Deliberately an API-level gate, not a capability probe. The whole feature is built on
     * streaming our own microphone capture into the recogniser ([RecognizerIntent.EXTRA_AUDIO_SOURCE],
     * API 33), which is what stops the service opening — and audibly re-opening — the mic at every
     * pause between phrases. Without it the experience is the one users complained about, so it is
     * better to say the phone is not supported than to ship the broken version to it.
     */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** A recognition intent for [language], without the audio-source extras. */
    fun arabicIntent(language: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        }

    data class ArabicSupport(
        /** Arabic variants already downloaded and usable with no network. */
        val installedOnDevice: List<String>,
        /** Arabic variants this service could download. */
        val downloadable: List<String>,
        /** The language tag the service actually answered for, for the download to reuse. */
        val answeredFor: String? = null,
    ) {
        val hasOfflineArabic: Boolean get() = installedOnDevice.isNotEmpty()
        val canDownloadArabic: Boolean get() = downloadable.isNotEmpty()
    }

    /**
     * Asks the speech service what it can do with Arabic. Calls [onResult] on [executor]; calls it
     * with null when no Arabic tag could be answered at all.
     *
     * **Every tag in [ARABIC_LOCALES] is tried, not just the first.** Services disagree about
     * which they accept, and asking only `ar-SA` means a phone that would have answered for bare
     * `ar` or `ar-EG` is written off as having no Arabic — which is exactly what
     * `Failed to get language pack of required locale: error 12` looks like from the outside.
     */
    fun queryArabicSupport(
        context: Context,
        executor: Executor,
        onResult: (ArabicSupport?) -> Unit,
    ) {
        if (!isSupported) {
            onResult(null)
            return
        }
        queryFrom(context, executor, ARABIC_LOCALES, onResult)
    }

    private fun queryFrom(
        context: Context,
        executor: Executor,
        remaining: List<String>,
        onResult: (ArabicSupport?) -> Unit,
    ) {
        val language = remaining.firstOrNull()
        if (language == null) {
            onResult(null)
            return
        }
        val next = { queryFrom(context, executor, remaining.drop(1), onResult) }
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            .getOrNull()
        if (recognizer == null) {
            onResult(null)
            return
        }
        // Answers arrive asynchronously and the recognizer must outlive the call, so it is only
        // destroyed once the callback has fired.
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val installed = support.installedOnDeviceLanguages.filter { it.isArabic() }
                val downloadable = (support.supportedOnDeviceLanguages + support.pendingOnDeviceLanguages)
                    .filter { it.isArabic() }
                    .distinct()
                runCatching { recognizer.destroy() }
                if (installed.isEmpty() && downloadable.isEmpty() && remaining.size > 1) {
                    // Answered, but knows no Arabic under this tag. Another spelling may fare
                    // better before we conclude the phone has none.
                    Log.i(TAG, "$language reports no Arabic; trying the next tag")
                    next()
                    return
                }
                onResult(
                    ArabicSupport(
                        installedOnDevice = installed,
                        downloadable = downloadable - installed.toSet(),
                        answeredFor = language,
                    ),
                )
            }

            override fun onError(error: Int) {
                Log.i(TAG, "recognition support query for $language failed with $error")
                runCatching { recognizer.destroy() }
                next()
            }
        }
        runCatching {
            recognizer.checkRecognitionSupport(arabicIntent(language), executor, callback)
        }.onFailure {
            runCatching { recognizer.destroy() }
            next()
        }
    }

    /**
     * Asks the service to download its on-device Arabic model.
     *
     * Before API 33 there was no such call and the only option was to send the user to the system
     * voice-input screen. [onDone] reports success, and is also called with false when the platform
     * cannot do this — the caller falls back to network recognition rather than blocking.
     */
    fun downloadArabicModel(
        context: Context,
        executor: Executor,
        preferredLanguage: String? = null,
        onProgress: (Int) -> Unit = {},
        onDone: (Boolean) -> Unit,
    ) {
        if (!isSupported) {
            onDone(false)
            return
        }
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            .getOrNull()
        if (recognizer == null) {
            onDone(false)
            return
        }
        // The variant the service said it could fetch, when the support query found one. Asking
        // for `ar-SA` regardless is how a phone that offers only `ar-EG` gets told it has no
        // Arabic: the query already knew better and the download ignored it.
        val language = preferredLanguage ?: ARABIC_LOCALES.first()
        Log.i(TAG, "requesting on-device Arabic model for $language")
        val intent = arabicIntent(language)
        // The listener overload is API 34; on 33 the fire-and-forget call is all there is, so the
        // caller is told to expect nothing more than "asked for".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val listener = object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = onProgress(completedPercent)

                override fun onSuccess() {
                    runCatching { recognizer.destroy() }
                    onDone(true)
                }

                override fun onScheduled() = Unit

                override fun onError(error: Int) {
                    Log.i(TAG, "Arabic model download failed with $error")
                    runCatching { recognizer.destroy() }
                    onDone(false)
                }
            }
            runCatching { recognizer.triggerModelDownload(intent, executor, listener) }
                .onFailure {
                    runCatching { recognizer.destroy() }
                    onDone(false)
                }
        } else {
            runCatching { recognizer.triggerModelDownload(intent) }
            runCatching { recognizer.destroy() }
            onDone(true)
        }
    }

    private fun String.isArabic(): Boolean = startsWith("ar", ignoreCase = true)
}
