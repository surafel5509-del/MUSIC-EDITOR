package com.studioone.mobile.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FeaturedPlayList
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sliders
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.studioone.mobile.R
import com.studioone.feature.collab.CollabScreen
import com.studioone.feature.editor.EditorScreen
import com.studioone.feature.home.ProjectListScreen
import com.studioone.feature.instruments.InstrumentsScreen
import com.studioone.feature.library.LibraryScreen
import com.studioone.feature.mixer.MixerScreen
import com.studioone.feature.recorder.RecorderScreen
import com.studioone.feature.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val PROJECT = "project/{projectId}"
    const val SETTINGS = "settings"
    const val COLLAB = "collab/{projectId}"

    fun project(projectId: String) = "project/$projectId"
    fun collab(projectId: String) = "collab/$projectId"

    // Workspace tabs (nested inside the project graph).
    const val TAB_ARRANGE = "arrange"
    const val TAB_MIXER = "mixer"
    const val TAB_INSTRUMENTS = "instruments"
    const val TAB_LIBRARY = "library"
}

private data class WorkspaceTab(val route: String, val labelRes: Int, val icon: ImageVector)

private val workspaceTabs = listOf(
    WorkspaceTab(Routes.TAB_ARRANGE, R.string.nav_arrange, Icons.AutoMirrored.Filled.FeaturedPlayList),
    WorkspaceTab(Routes.TAB_MIXER, R.string.nav_mixer, Icons.Filled.Sliders),
    WorkspaceTab(Routes.TAB_INSTRUMENTS, R.string.nav_instruments, Icons.Filled.Piano),
    WorkspaceTab(Routes.TAB_LIBRARY, R.string.nav_library, Icons.Filled.LibraryMusic),
)

/**
 * Root navigation graph:
 *   home -> project workspace (bottom tabs) -> recorder / collab
 *   home -> settings
 * Deep link: studioone://project/{id}
 */
@Composable
fun StudioOneNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            ProjectListScreen(
                onOpenProject = { projectId ->
                    navController.navigate(Routes.project(projectId))
                },
            )
        }

        composable(Routes.SETTINGS) { SettingsScreen() }

        // ---- Project workspace --------------------------------------------
        composable(
            route = Routes.PROJECT,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "studioone://project/{projectId}" }),
        ) {
            ProjectWorkspace(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenCollab = { projectId -> navController.navigate(Routes.collab(projectId)) },
            )
        }

        composable(
            route = Routes.COLLAB,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) {
            CollabScreen()
        }
    }
}

/** Scaffold with bottom tabs hosting arrange/mixer/instruments/library. */
@Composable
private fun ProjectWorkspace(
    onOpenSettings: () -> Unit,
    onOpenCollab: (String) -> Unit,
) {
    val tabNavController = rememberNavController()
    val backStack by tabNavController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                workspaceTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route ||
                            backStack?.destination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            tabNavController.navigate(tab.route) {
                                popUpTo(tabNavController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = tabNavController,
            startDestination = Routes.TAB_ARRANGE,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.TAB_ARRANGE) {
                EditorScreen(
                    onOpenMixer = {
                        tabNavController.navigate(Routes.TAB_MIXER)
                    },
                    onOpenRecorder = { /* Recorder presented as overlay sheet in milestone 2 */ },
                )
            }
            composable(Routes.TAB_MIXER) {
                MixerScreen()
            }
            composable(Routes.TAB_INSTRUMENTS) { InstrumentsScreen() }
            composable(Routes.TAB_LIBRARY) { LibraryScreen(onAddToProject = { /* -> editor */ }) }
        }
    }
}
