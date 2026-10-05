package com.quran.learnedplayer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.quran.learnedplayer.data.LearnedAyahsExport
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.ui.AppNav
import com.quran.learnedplayer.ui.theme.LearnedAyahsTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()
    private var pendingAfterNotification: (() -> Unit)? = null
    private var autoLoadedThisSession = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingAfterNotification?.invoke()
        pendingAfterNotification = null
    }

    /**
     * File pickers use the Storage Access Framework, which grants access per-file at pick time —
     * hence no storage permission in the manifest. [pendingImport] says how to read what was
     * picked, since the same picker serves both formats.
     */
    private var pendingImport: ((Uri) -> Unit)? = null

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val handler = pendingImport ?: viewModel::importExportedFile
        pendingImport = null
        uri?.let(handler)
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri?.let(viewModel::exportTo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, LearnedAyahsApp.CRASH_LOG)
        if (crashFile.exists()) {
            showCrashScreen(crashFile)
            return
        }

        setContent {
            LearnedAyahsTheme {
                AppNav(
                    viewModel = viewModel,
                    onPlayRequested = ::runWithNotificationPermission,
                    onImportLearnedAyahs = ::importLearnedAyahs,
                    onExportLearnedAyahs = ::exportLearnedAyahs,
                )
            }
        }

        autoLoadLibrary()
    }

    private fun autoLoadLibrary() {
        if (autoLoadedThisSession) return
        val state = viewModel.uiState.value
        if (state.tracks.isNotEmpty() || state.isLoading) return
        autoLoadedThisSession = true
        viewModel.refreshPlaylist()
    }

    private fun importLearnedAyahs() {
        pendingImport = viewModel::importExportedFile
        openDocumentLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun exportLearnedAyahs() {
        createDocumentLauncher.launch(LearnedAyahsExport.suggestedFileName())
    }

    private fun runWithNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingAfterNotification = action
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showCrashScreen(crashFile: File) {
        val crashText = runCatching { crashFile.readText() }
            .getOrDefault("(could not read crash log)")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1419"))
            setPadding(40, 60, 40, 40)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val heading = TextView(this).apply {
            text = "App crashed — here is the error\n(screenshot this and send it)"
            setTextColor(Color.parseColor("#FF6B6B"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val body = TextView(this).apply {
            text = crashText
            setTextColor(Color.parseColor("#E8EEF7"))
            textSize = 12f
            setTextIsSelectable(true)
        }

        val retry = Button(this).apply {
            text = "Clear & try again"
            setOnClickListener {
                crashFile.delete()
                recreate()
            }
        }

        container.addView(heading)
        container.addView(retry)
        container.addView(body)

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#0F1419"))
                addView(container)
            },
        )
    }

}
