package com.studioone.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/**
 * App navigation graph.
 *
 * Rules (enforced by module boundaries):
 *  * features never navigate to each other directly — all routes live here
 *  * deep links: studioone.app/j/<token> -> share acceptance -> arranger
 *  * the editor suite (arranger/mixer/pianoroll/fx) shares the projectId arg
 */
object Routes {
    const val AUTH = "auth"
    const val ONBOARDING = "onboarding?page={page}"
    const val PROJECTS = "projects"
    const val ARRANGER = "arranger/{projectId}?collab={collab}"
    const val MIXER = "mixer/{projectId}"
    const val PIANO_ROLL = "pianoroll/{projectId}/{trackId}/{clipId}"
    const val FX_RACK = "fx/{projectId}/{trackId}"
    const val INSTRUMENTS = "instruments/{projectId}/{trackId}"
    const val DRUM_MACHINE = "drums/{projectId}/{trackId}"
    const val LOOPS = "loops"
    const val FEED = "feed"
    const val COLLAB = "collab/{projectId}"
    const val EXPORT = "export/{projectId}"
    const val SETTINGS = "settings"
    const val PAYWALL = "paywall"

    fun arranger(projectId: String, collab: Boolean = true) = "arranger/$projectId?collab=$collab"
    fun mixer(projectId: String) = "mixer/$projectId"
    fun pianoRoll(projectId: String, trackId: String, clipId: String) = "pianoroll/$projectId/$trackId/$clipId"
    fun fxRack(projectId: String, trackId: String) = "fx/$projectId/$trackId"
    fun instruments(projectId: String, trackId: String) = "instruments/$projectId/$trackId"
    fun drumMachine(projectId: String, trackId: String) = "drums/$projectId/$trackId"
}

@Composable
fun AppNavHost(
    isTablet: Boolean,
    onRequestRecordPermission: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Shell destinations get the navigation chrome; editor destinations are immersive.
    val shellDestinations = setOf(Routes.PROJECTS, Routes.FEED, Routes.LOOPS, Routes.SETTINGS)
    val showChrome = currentRoute in shellDestinations

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        bottomBar = {
            if (showChrome && !isTablet) {
                NavigationBar {
                    BottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = { navController.navigateShell(item.route) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(if (showChrome) padding else androidx.compose.foundation.layout.PaddingValues())) {
            if (showChrome && isTablet) {
                NavigationRail {
                    BottomItems.forEach { item ->
                        NavigationRailItem(
                            selected = currentRoute == item.route,
                            onClick = { navController.navigateShell(item.route) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                NavHost(navController = navController, startDestination = Routes.PROJECTS) {

                    composable(Routes.AUTH) {
                        com.studioone.mobile.feature.auth.AuthScreen(
                            onSignedIn = { navController.navigateShell(Routes.PROJECTS) },
                            onGoogleSignIn = { /* Credential Manager launcher wired in app module */ },
                            onFacebookSignIn = { /* Facebook Login SDK launcher */ },
                            onAppleSignIn = { /* AppAuth PKCE flow (Custom Tabs) */ },
                        )
                    }

                    composable(
                        route = Routes.ONBOARDING,
                        arguments = listOf(navArgument("page") { type = NavType.IntType; defaultValue = 0 }),
                    ) { entry ->
                        com.studioone.mobile.feature.onboarding.OnboardingScreen(
                            initialPage = entry.arguments?.getInt("page") ?: 0,
                            onRequestAudioPermission = onRequestRecordPermission,
                            onPickTemplate = { template ->
                                navController.navigateShell(Routes.PROJECTS)
                            },
                            onSkip = { navController.navigateShell(Routes.PROJECTS) },
                        )
                    }

                    composable(Routes.PROJECTS) {
                        com.studioone.mobile.feature.projects.ProjectsScreen(
                            onOpenProject = { id -> navController.navigate(Routes.arranger(id)) },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onUpgradeClick = { navController.navigate(Routes.PAYWALL) },
                        )
                    }

                    composable(
                        route = Routes.ARRANGER,
                        arguments = listOf(
                            navArgument("projectId") { type = NavType.StringType },
                            navArgument("collab") { type = NavType.BoolType; defaultValue = true },
                        ),
                    ) { entry ->
                        val projectId = entry.arguments?.getString("projectId").orEmpty()
                        com.studioone.mobile.feature.arranger.ArrangerScreen(
                            onOpenPianoRoll = { trackId, clipId ->
                                navController.navigate(Routes.pianoRoll(projectId, trackId, clipId))
                            },
                            onOpenMixer = { navController.navigate(Routes.mixer(projectId)) },
                            onOpenFxRack = { trackId ->
                                navController.navigate(Routes.fxRack(projectId, trackId))
                            },
                            onOpenLoops = { navController.navigate(Routes.LOOPS) },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(
                        route = Routes.MIXER,
                        arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                    ) { entry ->
                        val projectId = entry.arguments?.getString("projectId").orEmpty()
                        com.studioone.mobile.feature.mixer.MixerScreen(
                            projectId = projectId,
                            onBack = { navController.popBackStack() },
                            onOpenFxRack = { trackId ->
                                navController.navigate(Routes.fxRack(projectId, trackId))
                            },
                        )
                    }

                    composable(
                        route = Routes.PIANO_ROLL,
                        arguments = listOf(
                            navArgument("projectId") { type = NavType.StringType },
                            navArgument("trackId") { type = NavType.StringType },
                            navArgument("clipId") { type = NavType.StringType },
                        ),
                    ) {
                        com.studioone.mobile.feature.pianoroll.PianoRollScreen(
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(
                        route = Routes.FX_RACK,
                        arguments = listOf(
                            navArgument("projectId") { type = NavType.StringType },
                            navArgument("trackId") { type = NavType.StringType },
                        ),
                    ) {
                        com.studioone.mobile.feature.effects.FxRackScreen(
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(
                        route = Routes.INSTRUMENTS,
                        arguments = listOf(
                            navArgument("projectId") { type = NavType.StringType },
                            navArgument("trackId") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        com.studioone.mobile.feature.instruments.InstrumentsScreen(
                            projectId = entry.arguments?.getString("projectId") ?: "",
                            trackId = entry.arguments?.getString("trackId") ?: "",
                            onBack = { navController.popBackStack() },
                            onUpgradeClick = { navController.navigate(Routes.PAYWALL) },
                        )
                    }

                    composable(Routes.LOOPS) {
                        com.studioone.mobile.feature.loops.LoopsScreen(
                            onBack = { navController.popBackStack() },
                            onPreview = { /* Media3 preview player (PlaybackService) */ },
                            onStopPreview = { },
                            onAddToProject = { /* drop into open project via DragPayload bus */ },
                        )
                    }

                    composable(Routes.FEED) {
                        com.studioone.mobile.feature.social.FeedScreen(
                            onOpenPost = { },
                            onPlay = { },
                            onStopPlay = { },
                        )
                    }

                    composable(
                        route = Routes.COLLAB,
                        arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                    ) {
                        com.studioone.mobile.feature.collab.CollabPanel(
                            onNavigateToComment = { frame -> navController.popBackStack() },
                        )
                    }

                    composable(
                        route = Routes.EXPORT,
                        arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                    ) {
                        // Export is a sheet over the arranger; as a route it renders standalone.
                        com.studioone.mobile.feature.export.ExportSheet(
                            onDismiss = { navController.popBackStack() },
                            onShare = { },
                            onUpgrade = { navController.navigate(Routes.PAYWALL) },
                        )
                    }

                    composable(Routes.SETTINGS) {
                        com.studioone.mobile.feature.settings.SettingsScreen(
                            onOpenAccount = { navController.navigate(Routes.AUTH) },
                            onOpenPrivacy = { },
                            onOpenUpgrade = { navController.navigate(Routes.PAYWALL) },
                        )
                    }

                    composable(Routes.PAYWALL) {
                        com.studioone.mobile.feature.paywall.PaywallSheet(
                            onDismiss = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

private data class BottomItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val BottomItems = listOf(
    BottomItem(Routes.PROJECTS, "Studio", Icons.Default.Home),
    BottomItem(Routes.FEED, "Feed", Icons.Default.People),
    BottomItem(Routes.LOOPS, "Sounds", Icons.Default.LibraryMusic),
    BottomItem(Routes.SETTINGS, "You", Icons.Default.Person),
)

private fun NavHostController.navigateShell(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
