package com.quran.learnedplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.ui.home.IntroDialog
import com.quran.learnedplayer.ui.home.SurahListScreen
import com.quran.learnedplayer.ui.settings.AddByDescriptionScreen
import com.quran.learnedplayer.ui.settings.SettingsScreen
import com.quran.learnedplayer.ui.surah.SurahDetailScreen

import com.quran.learnedplayer.ui.recitation.RecitationScreen

object Routes {
    const val HOME = "home"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val ADD_BY_DESCRIPTION = "add_by_description"
    const val RECITATION = "recitation?globalId={globalId}"
    const val SURAH = "surah/{surah}"

    fun surah(surah: Int) = "surah/$surah"
    fun recitation(globalId: Int = 1) = "recitation?globalId=$globalId"
}

/**
 * Top-level navigation. The merged player (Phase 2's immersive reader + panels) is the app's
 * default screen once onboarding is done — [Routes.HOME], the surah/juz browse-and-mark screen,
 * is then reached deliberately (the player's empty state, or the surah panel's "Manage ayahs"
 * button) rather than being the front door. A fresh install still opens on Home so the
 * first-run intro dialog and marking flow have somewhere to live.
 */
@Composable
fun AppNav(
    viewModel: PlayerViewModel,
    onPlayRequested: ((() -> Unit) -> Unit),
    onImportLearnedAyahs: () -> Unit,
    onExportLearnedAyahs: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // Computed once for the activity's lifetime: onboarding completing mid-session must not yank
    // the user off Home onto the player. It only takes effect on the next cold start.
    val startDestination = remember { if (PlayerSettings.seenIntro) Routes.PLAYER else Routes.HOME }

    // Onboarding is an app-level concern, not the picker's: `seenIntro` is global, but the
    // dialog used to live inside SurahListScreen, so it could only ever appear if the app
    // happened to be on Home. Now that the player is the default destination — and a restored
    // back stack can put the player up even on a launch where the intro hasn't been seen — the
    // dialog is hoisted here, above the NavHost, so it shows over whatever is on screen.
    val seenIntro by PlayerSettings.seenIntroFlow.collectAsState()
    if (!seenIntro) {
        IntroDialog(onDismiss = { PlayerSettings.seenIntro = true })
    }

    // Distinct from `seenIntro`, which flips the instant the modal is dismissed — before the
    // user has marked anything or ever seen the player. This tracks whether the player has
    // actually been reached *this session*, which is what the Home/SurahDetail "back to main"
    // button needs: while it's false there's nothing to pop back to (Home is the stack root), so
    // the button reads "Proceed" and pushes forward; once true, it reads "Back" and pops.
    var hasReachedPlayer by remember { mutableStateOf(startDestination == Routes.PLAYER) }
    val isOnboarding = !hasReachedPlayer
    val backToMain: () -> Unit = {
        if (hasReachedPlayer) {
            navController.popBackStack(Routes.PLAYER, inclusive = false)
        } else {
            hasReachedPlayer = true
            navController.navigate(Routes.PLAYER)
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.HOME) {
            SurahListScreen(
                viewModel = viewModel,
                onOpenSurah = { navController.navigate(Routes.surah(it)) },
                onAddByDescription = { navController.navigate(Routes.ADD_BY_DESCRIPTION) },
                onExportLearnedAyahs = onExportLearnedAyahs,
                onImportLearnedAyahs = onImportLearnedAyahs,
                isOnboarding = isOnboarding,
                onBackToMain = backToMain,
            )
        }

        composable(
            route = Routes.SURAH,
            arguments = listOf(navArgument("surah") { type = NavType.IntType }),
        ) { entry ->
            val surah = entry.arguments?.getInt("surah") ?: 1
            SurahDetailScreen(
                surah = surah,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                isOnboarding = isOnboarding,
                onBackToMain = backToMain,
                onPlayRequested = onPlayRequested,
            )
        }

        composable(Routes.PLAYER) {
            PlayerScreen(
                viewModel = viewModel,
                onPlayRequested = onPlayRequested,
                onBrowseSurahs = { navController.navigate(Routes.HOME) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenRecitation = { globalId -> navController.navigate(Routes.recitation(globalId)) },
            )
        }

        composable(
            route = Routes.RECITATION,
            arguments = listOf(navArgument("globalId") { type = NavType.IntType; defaultValue = 1 }),
        ) { entry ->
            val globalId = entry.arguments?.getInt("globalId") ?: 1
            RecitationScreen(
                initialGlobalId = globalId,
                playerViewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.ADD_BY_DESCRIPTION) {
            AddByDescriptionScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
