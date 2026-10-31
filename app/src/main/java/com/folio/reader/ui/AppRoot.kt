package com.folio.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.folio.reader.core.format.DocFormat
import com.folio.reader.data.prefs.UserPrefs
import com.folio.reader.ui.bookmarks.BookmarksScreen
import com.folio.reader.ui.bookmarks.LinkSheet
import com.folio.reader.ui.browser.BrowserScreen
import com.folio.reader.ui.browser.DownloadsScreen
import com.folio.reader.ui.browser.HistoryScreen
import com.folio.reader.ui.browser.OfflinePageScreen
import com.folio.reader.ui.collections.CollectionDetailScreen
import com.folio.reader.ui.collections.CollectionsScreen
import com.folio.reader.ui.collections.TagScreen
import com.folio.reader.ui.documents.DocumentsScreen
import com.folio.reader.ui.home.HomeScreen
import com.folio.reader.ui.nav.Routes
import com.folio.reader.ui.reader.ReaderScreen
import com.folio.reader.ui.search.SearchScreen
import com.folio.reader.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private data class Dest(val route: String, val label: String, val icon: ImageVector, val selected: ImageVector)

private val DESTS = listOf(
    Dest(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Dest(Routes.DOCUMENTS, "Library", Icons.Outlined.LibraryBooks, Icons.Filled.LibraryBooks),
    Dest(Routes.BOOKMARKS, "Bookmarks", Icons.Outlined.BookmarkBorder, Icons.Filled.Bookmark),
    Dest(Routes.COLLECTIONS, "Collections", Icons.Outlined.FolderSpecial, Icons.Filled.FolderSpecial),
    Dest(Routes.SEARCH, "Search", Icons.Outlined.Search, Icons.Filled.Search),
)

@Composable
fun AppRoot(vm: MainViewModel, prefs: UserPrefs) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val topLevel = DESTS.any { it.route == route } || route == Routes.SETTINGS
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val navigate: (String) -> Unit = { r ->
        if (DESTS.any { it.route == r }) {
            nav.navigate(r) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } else nav.navigate(r) { launchSingleTop = true }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(uris)
    }
    val onImport = { importLauncher.launch(DocFormat.supportedMimeTypes) }
    val viewOnceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.openViewer(it) }
    }
    val onOpenOnce = { viewOnceLauncher.launch(DocFormat.supportedMimeTypes) }
    val onSaveLink = { vm.startLinkDraft() }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Navigate -> navigate(e.route)
                is UiEvent.Message -> scope.launch {
                    val r = snackbar.showSnackbar(e.text, actionLabel = e.actionLabel, withDismissAction = e.actionLabel == null, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed && e.actionRoute != null) navigate(e.actionRoute)
                }
            }
        }
    }

    val linkDraft by vm.linkDraft.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    linkDraft?.let { d ->
        LinkSheet(
            draft = d, collections = collections,
            onChange = vm::updateLinkDraft, onDismiss = vm::cancelLinkDraft,
            onSave = { vm.saveLinkDraft(false) }, onSaveAnyway = { vm.saveLinkDraft(true) },
            onOpenExisting = { vm.cancelLinkDraft(); navigate(Routes.browser(d.duplicateOf!!.url)) },
            onCreateCollection = { name -> vm.createCollection(name) { id -> vm.updateLinkDraft(d.copy(collectionId = id)) } },
        )
    }

    val duplicate by vm.duplicate.collectAsStateWithLifecycle()
    duplicate?.let { (_, existing) ->
        AlertDialog(
            onDismissRequest = vm::dismissDuplicate,
            title = { Text("Already in your library") },
            text = { Text("“${existing.title}” has identical content. Open the existing copy, or keep a second copy?") },
            confirmButton = { TextButton(onClick = { vm.resolveDuplicate(false) }) { Text("Open existing") } },
            dismissButton = { TextButton(onClick = { vm.resolveDuplicate(true) }) { Text("Keep both") } },
        )
    }

    val importing by vm.importing.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxSize()) {
        if (wide && topLevel) {
            NavigationRail(header = {
                FloatingActionButton(onClick = onImport, modifier = Modifier.padding(vertical = 12.dp)) { Icon(Icons.Default.Add, "Import") }
            }) {
                Spacer(Modifier.weight(1f))
                (DESTS + Dest(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)).forEach { d ->
                    NavigationRailItem(selected = route == d.route, onClick = { navigate(d.route) },
                        icon = { Icon(if (route == d.route) d.selected else d.icon, null) }, label = { Text(d.label) })
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!wide && topLevel) {
                    NavigationBar {
                        DESTS.forEach { d ->
                            NavigationBarItem(selected = route == d.route, onClick = { navigate(d.route) },
                                icon = { Icon(if (route == d.route) d.selected else d.icon, null) }, label = { Text(d.label, maxLines = 1) })
                        }
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (importing > 0) LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding())
                NavHost(
                    navController = nav, startDestination = Routes.HOME, modifier = Modifier.weight(1f),
                    enterTransition = { fadeIn() + slideInHorizontally { it / 12 } },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() + slideOutHorizontally { it / 12 } },
                ) {
                    composable(Routes.HOME) { HomeScreen(navigate, onImport, onSaveLink, onOpenOnce) }
                    composable(Routes.DOCUMENTS) { DocumentsScreen(navigate, onImport) }
                    composable(Routes.BOOKMARKS) { BookmarksScreen(navigate, onSaveLink) }
                    composable(Routes.COLLECTIONS) { CollectionsScreen(navigate) }
                    composable(Routes.SEARCH) { SearchScreen(navigate) }
                    composable(Routes.SETTINGS) { SettingsScreen(navigate, { nav.popBackStack() }) }
                    composable(Routes.COLLECTION, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        CollectionDetailScreen(it.arguments!!.getLong("id"), navigate, back = { nav.popBackStack() })
                    }
                    composable(Routes.TAG, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        TagScreen(it.arguments!!.getLong("id"), navigate, back = { nav.popBackStack() })
                    }
                    composable(
                        Routes.READER,
                        arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("pos") { type = NavType.IntType; defaultValue = -1 }),
                    ) { ReaderScreen(navigate, back = { nav.popBackStack() }) }
                    composable(Routes.BROWSER, arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                        BrowserScreen(it.arguments?.getString("url"), navigate, back = { nav.popBackStack() })
                    }
                    composable(Routes.OFFLINE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        OfflinePageScreen(it.arguments!!.getLong("id"), navigate, back = { nav.popBackStack() })
                    }
                    composable(Routes.VIEW, arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                        com.folio.reader.ui.viewer.ViewerScreen(navigate, back = { nav.popBackStack() })
                    }
                    composable(Routes.DOWNLOADS) { DownloadsScreen(navigate, back = { nav.popBackStack() }) }
                    composable(Routes.HISTORY) { HistoryScreen(navigate, back = { nav.popBackStack() }) }
                }
            }
        }
    }
}
