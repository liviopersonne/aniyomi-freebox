package eu.kanade.tachiyomi.ui.library.manga

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.util.fastAll
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.category.model.Category
import eu.kanade.domain.entries.manga.model.Manga
import eu.kanade.domain.entries.manga.model.isLocal
import eu.kanade.domain.library.manga.LibraryManga
import eu.kanade.domain.library.model.display
import eu.kanade.domain.library.service.LibraryPreferences
import eu.kanade.presentation.components.ChangeCategoryDialog
import eu.kanade.presentation.components.DeleteLibraryEntryDialog
import eu.kanade.presentation.components.EmptyScreen
import eu.kanade.presentation.components.EmptyScreenAction
import eu.kanade.presentation.components.LibraryBottomActionMenu
import eu.kanade.presentation.components.LoadingScreen
import eu.kanade.presentation.components.Scaffold
import eu.kanade.presentation.entries.DownloadCustomAmountDialog
import eu.kanade.presentation.library.LibraryToolbar
import eu.kanade.presentation.library.manga.MangaLibraryContent
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateService
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.GlobalMangaSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoriesTab
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.lang.launchIO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy

object MangaLibraryTab : Tab {

    val libraryPreferences: LibraryPreferences by injectLazy()
    private val fromMore = libraryPreferences.bottomNavStyle().get() == 2

    override val options: TabOptions
        @Composable
        get() {
            val title = if (fromMore) {
                R.string.label_library
            } else {
                R.string.label_manga_library
            }
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            val index: UShort = if (fromMore) 5u else 1u
            return TabOptions(
                index = index,
                title = stringResource(title),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        val screenModel = rememberScreenModel { MangaLibraryScreenModel() }
        val state by screenModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }

        val onClickRefresh: (Category?) -> Boolean = {
            val started = MangaLibraryUpdateService.start(context, it)
            scope.launch {
                val msgRes = if (started) R.string.updating_category else R.string.update_already_running
                snackbarHostState.showSnackbar(context.getString(msgRes))
            }
            started
        }
        val onClickFilter: () -> Unit = {
            scope.launch { sendSettingsSheetIntent(state.categories[screenModel.activeCategoryIndex]) }
        }

        val onClickCast: () -> Unit = {
            when (AnimeLibraryTab.httpFreeboxService.state) {
                0 -> { // Request connection
                    scope.launch {
                        if (AnimeLibraryTab.httpFreeboxService.searchFreebox()) {
                            if (AnimeLibraryTab.httpFreeboxService.getAppToken()) {
                                Toast.makeText(context, "Confirm connection on Freebox (you have 1min30)", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Error fetching app token", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "No freebox found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                1 -> { // Confirm connection
                    scope.launch {
                        when (AnimeLibraryTab.httpFreeboxService.appTokenValid()) {
                            -1 -> {
                                AnimeLibraryTab.httpFreeboxService.state = 0
                                Toast.makeText(context, "App Token is invalid", Toast.LENGTH_SHORT).show()
                            }
                            0 -> Toast.makeText(context, "App Token is pending", Toast.LENGTH_SHORT).show()
                            1 -> {
                                if (AnimeLibraryTab.httpFreeboxService.getSessionToken()) {
                                    when (AnimeLibraryTab.httpFreeboxService.freeboxPlayerAvailable()) {
                                        -1 -> Toast.makeText(context, "Freebox player not found", Toast.LENGTH_SHORT).show()
                                        0 -> Toast.makeText(context, "Freebox player protected by password", Toast.LENGTH_SHORT).show()
                                        1 -> Toast.makeText(context, "Service connected !", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Error fetching session token", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
                2 -> { // Try to find Freebox Player
                    scope.launch {
                        when (AnimeLibraryTab.httpFreeboxService.freeboxPlayerAvailable()) {
                            -1 -> Toast.makeText(context, "Freebox player not found", Toast.LENGTH_SHORT).show()
                            0 -> Toast.makeText(context, "Freebox player protected by password", Toast.LENGTH_SHORT).show()
                            1 -> Toast.makeText(context, "Service connected !", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                3 -> { // Disconnect
                    scope.launch {
                        if (AnimeLibraryTab.httpFreeboxService.logout()) {
                            Toast.makeText(context, "Successfully logged out", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Logout failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }


        val navigateUp: (() -> Unit)? = if (fromMore) navigator::pop else null

        val defaultTitle = if (fromMore) stringResource(R.string.label_library) else stringResource(R.string.label_manga_library)

        Scaffold(
            topBar = { scrollBehavior ->
                val title = state.getToolbarTitle(
                    defaultTitle = defaultTitle,
                    defaultCategoryTitle = stringResource(R.string.label_default),
                    page = screenModel.activeCategoryIndex,
                )
                val tabVisible = state.showCategoryTabs && state.categories.size > 1
                LibraryToolbar(
                    hasActiveFilters = state.hasActiveFilters,
                    selectedCount = state.selection.size,
                    title = title,
                    onClickUnselectAll = screenModel::clearSelection,
                    onClickSelectAll = { screenModel.selectAll(screenModel.activeCategoryIndex) },
                    onClickInvertSelection = { screenModel.invertSelection(screenModel.activeCategoryIndex) },
                    castState = AnimeLibraryTab.httpFreeboxService.state,
                    onClickCast = onClickCast,
                    onClickFilter = onClickFilter,
                    onClickRefresh = { onClickRefresh(null) },
                    onClickOpenRandomEntry = {
                        scope.launch {
                            val randomItem = screenModel.getRandomLibraryItemForCurrentCategory()
                            if (randomItem != null) {
                                navigator.push(MangaScreen(randomItem.libraryManga.manga.id))
                            } else {
                                snackbarHostState.showSnackbar(context.getString(R.string.information_no_entries_found))
                            }
                        }
                    },
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = screenModel::search,
                    scrollBehavior = scrollBehavior.takeIf { !tabVisible }, // For scroll overlay when no tab
                    navigateUp = navigateUp,
                )
            },
            bottomBar = {
                LibraryBottomActionMenu(
                    visible = state.selectionMode,
                    onChangeCategoryClicked = screenModel::openChangeCategoryDialog,
                    onMarkAsViewedClicked = { screenModel.markReadSelection(true) },
                    onMarkAsUnviewedClicked = { screenModel.markReadSelection(false) },
                    onDownloadClicked = screenModel::runDownloadActionSelection
                        .takeIf { state.selection.fastAll { !it.manga.isLocal() } },
                    onDeleteClicked = screenModel::openDeleteMangaDialog,
                    isManga = true,
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when {
                state.isLoading -> LoadingScreen(modifier = Modifier.padding(contentPadding))
                state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                    val handler = LocalUriHandler.current
                    EmptyScreen(
                        textResource = R.string.information_empty_library,
                        modifier = Modifier.padding(contentPadding),
                        actions = listOf(
                            EmptyScreenAction(
                                stringResId = R.string.getting_started_guide,
                                icon = Icons.Outlined.HelpOutline,
                                onClick = { handler.openUri("https://aniyomi.org/help/guides/getting-started") },
                            ),
                        ),
                    )
                }
                else -> {
                    MangaLibraryContent(
                        categories = state.categories,
                        searchQuery = state.searchQuery,
                        selection = state.selection,
                        contentPadding = contentPadding,
                        currentPage = { screenModel.activeCategoryIndex },
                        hasActiveFilters = state.hasActiveFilters,
                        showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
                        onChangeCurrentPage = { screenModel.activeCategoryIndex = it },
                        onMangaClicked = { navigator.push(MangaScreen(it)) },
                        onContinueReadingClicked = { it: LibraryManga ->
                            scope.launchIO {
                                val chapter = screenModel.getNextUnreadChapter(it.manga)
                                if (chapter != null) {
                                    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
                                } else {
                                    snackbarHostState.showSnackbar(context.getString(R.string.no_next_chapter))
                                }
                            }
                            Unit
                        }.takeIf { state.showMangaContinueButton },
                        onToggleSelection = { screenModel.toggleSelection(it) },
                        onToggleRangeSelection = {
                            screenModel.toggleRangeSelection(it)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onRefresh = onClickRefresh,
                        onGlobalSearchClicked = {
                            navigator.push(GlobalMangaSearchScreen(screenModel.state.value.searchQuery ?: ""))
                        },
                        getNumberOfMangaForCategory = { state.getMangaCountForCategory(it) },
                        getDisplayModeForPage = { state.categories[it].display },
                        getColumnsForOrientation = { screenModel.getColumnsPreferenceForCurrentOrientation(it) },
                    ) { state.getLibraryItemsByPage(it) }
                }
            }
        }

        val onDismissRequest = screenModel::closeDialog
        when (val dialog = state.dialog) {
            is MangaLibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        screenModel.clearSelection()
                        navigator.push(CategoriesTab(true))
                    },
                    onConfirm = { include, exclude ->
                        screenModel.clearSelection()
                        screenModel.setMangaCategories(dialog.manga, include, exclude)
                    },
                )
            }
            is MangaLibraryScreenModel.Dialog.DeleteManga -> {
                DeleteLibraryEntryDialog(
                    containsLocalEntry = dialog.manga.any(Manga::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteManga, deleteChapter ->
                        screenModel.removeMangas(dialog.manga, deleteManga, deleteChapter)
                        screenModel.clearSelection()
                    },
                    isManga = true,
                )
            }
            is MangaLibraryScreenModel.Dialog.DownloadCustomAmount -> {
                DownloadCustomAmountDialog(
                    maxAmount = dialog.max,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { amount ->
                        screenModel.downloadUnreadChapters(dialog.manga, amount)
                        screenModel.clearSelection()
                    },
                )
            }
            null -> {}
        }

        BackHandler(enabled = state.selectionMode || state.searchQuery != null) {
            when {
                state.selectionMode -> screenModel.clearSelection()
                state.searchQuery != null -> screenModel.search(null)
            }
        }

        LaunchedEffect(state.selectionMode) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(screenModel::search) }
            launch { requestSettingsSheetEvent.receiveAsFlow().collectLatest { onClickFilter() } }
        }
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet in LibraryController
    private val requestSettingsSheetEvent = Channel<Unit>()
    private val openSettingsSheetEvent_ = Channel<Category>()
    val openSettingsSheetEvent = openSettingsSheetEvent_.receiveAsFlow()
    private suspend fun sendSettingsSheetIntent(category: Category) = openSettingsSheetEvent_.send(category)
    suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
