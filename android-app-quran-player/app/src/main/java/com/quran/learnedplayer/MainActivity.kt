package com.quran.learnedplayer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import android.widget.Toast
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

    /**
     * Shown in place of the app after a crash (see LearnedAyahsApp): the report, with buttons to
     * copy or share it, since that is what a bug report needs and a screenshot of a long stack
     * trace rarely captures it. Plain Views, so it works even if Compose itself was the problem.
     */
    private fun showCrashScreen(crashFile: File) {
        val crashText = runCatching { crashFile.readText() }
            .getOrDefault("(could not read crash log)")

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A1710"))
            setPadding(px(20), px(32), px(20), px(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val heading = TextView(this).apply {
            text = "Learned Ayahs stopped unexpectedly"
            setTextColor(Color.parseColor("#E6F4EB"))
            textSize = 20f
            setPadding(0, 0, 0, px(8))
        }

        val explanation = TextView(this).apply {
            text = "Your learned ayahs and settings are safe. Please copy or share this report " +
                "and send it to the developers (a GitHub issue is best) so it can be fixed."
            setTextColor(Color.parseColor("#8DB29B"))
            textSize = 14f
            setPadding(0, 0, 0, px(16))
        }

        val copy = Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Learned Ayahs crash report", crashText))
                Toast.makeText(this@MainActivity, "Report copied", Toast.LENGTH_SHORT).show()
            }
        }

        val share = Button(this).apply {
            text = "Share report"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Learned Ayahs crash report")
                    putExtra(Intent.EXTRA_TEXT, crashText)
                }
                runCatching { startActivity(Intent.createChooser(send, "Share crash report")) }
            }
        }

        val dismiss = Button(this).apply {
            text = "Continue to the app"
            setOnClickListener {
                crashFile.delete()
                recreate()
            }
        }

        val body = TextView(this).apply {
            text = crashText
            setTextColor(Color.parseColor("#E6F4EB"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, px(16), 0, 0)
        }

        container.addView(heading)
        container.addView(explanation)
        container.addView(copy)
        container.addView(share)
        container.addView(dismiss)
        container.addView(body)

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#0A1710"))
                addView(container)
            },
        )
    }
}
