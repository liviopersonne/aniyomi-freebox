package eu.kanade.tachiyomi.ui.entries.anime

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.commandiron.wheel_picker_compose.WheelTextPicker
import eu.kanade.domain.entries.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.entries.anime.model.Anime
import eu.kanade.domain.entries.anime.model.hasCustomCover
import eu.kanade.domain.items.episode.model.Episode
import eu.kanade.presentation.components.ChangeCategoryDialog
import eu.kanade.presentation.components.DuplicateAnimeDialog
import eu.kanade.presentation.components.LoadingScreen
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.entries.DeleteItemsDialog
import eu.kanade.presentation.entries.DownloadCustomAmountDialog
import eu.kanade.presentation.entries.EditCoverAction
import eu.kanade.presentation.entries.anime.AnimeScreen
import eu.kanade.presentation.entries.anime.EpisodeSettingsDialog
import eu.kanade.presentation.entries.anime.components.AnimeCoverDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.anime.isLocalOrStub
import eu.kanade.tachiyomi.ui.browse.anime.migration.search.MigrateAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoriesTab
import eu.kanade.tachiyomi.ui.entries.anime.track.AnimeTrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab.httpFreeboxService
import eu.kanade.tachiyomi.ui.player.ExternalIntents
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.logcat
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeScreen(
    private val animeId: Long,
    val fromSource: Boolean = false,
) : Screen, AssistContentScreen {

    private var assistUrl: String? = null

    override val key = uniqueScreenKey

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { AnimeInfoScreenModel(context, animeId, fromSource) }

        val state by screenModel.state.collectAsState()

        if (state is AnimeScreenState.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as AnimeScreenState.Success
        val isAnimeHttpSource = remember { successState.source is AnimeHttpSource }

        LaunchedEffect(successState.anime, screenModel.source) {
            if (isAnimeHttpSource) {
                try {
                    withIOContext {
                        assistUrl = getAnimeUrl(screenModel.anime, screenModel.source)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to get anime URL" }
                }
            }
        }

        AnimeScreen(
            state = successState,
            snackbarHostState = screenModel.snackbarHostState,
            isTabletUi = isTabletUi(),
            onBackClicked = navigator::pop,
            onEpisodeClicked = { episode, alt ->
                scope.launchIO {
                    val extPlayer = screenModel.playerPreferences.alwaysUseExternalPlayer().get() != alt
                    openEpisode(context, episode, extPlayer)
                }
            },
            onDownloadEpisode = screenModel::runEpisodeDownloadActions.takeIf { !successState.source.isLocalOrStub() },
            onAddToLibraryClicked = {
                screenModel.toggleFavorite()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onWebViewClicked = { openAnimeInWebView(context, screenModel.anime, screenModel.source) }.takeIf { isAnimeHttpSource },
            onWebViewLongClicked = { copyAnimeUrl(context, screenModel.anime, screenModel.source) }.takeIf { isAnimeHttpSource },
            onTrackingClicked = screenModel::showTrackDialog.takeIf { successState.trackingAvailable },
            onTagClicked = { scope.launch { performGenreSearch(navigator, it, screenModel.source!!) } },
            onFilterButtonClicked = screenModel::showSettingsDialog,
            onRefresh = screenModel::fetchAllFromSource,
            onContinueWatching = {
                scope.launchIO {
                    val extPlayer = screenModel.playerPreferences.alwaysUseExternalPlayer().get()
                    continueWatching(context, screenModel.getNextUnseenEpisode(), extPlayer)
                }
            },
            onSearch = { query, global -> scope.launch { performSearch(navigator, query, global) } },
            onCoverClicked = screenModel::showCoverDialog,
            onShareClicked = { shareAnime(context, screenModel.anime, screenModel.source) }.takeIf { isAnimeHttpSource },
            onDownloadActionClicked = screenModel::runDownloadAction.takeIf { !successState.source.isLocalOrStub() },
            onEditCategoryClicked = screenModel::promptChangeCategories.takeIf { successState.anime.favorite },
            onMigrateClicked = { navigator.push(MigrateAnimeSearchScreen(successState.anime.id)) }.takeIf { successState.anime.favorite },
            changeAnimeSkipIntro = screenModel::showAnimeSkipIntroDialog.takeIf { successState.anime.favorite },
            onMultiBookmarkClicked = screenModel::bookmarkEpisodes,
            onMultiMarkAsSeenClicked = screenModel::markEpisodesSeen,
            onMarkPreviousAsSeenClicked = screenModel::markPreviousEpisodeSeen,
            onMultiDeleteClicked = screenModel::showDeleteEpisodeDialog,
            onEpisodeSelected = screenModel::toggleSelection,
            onAllEpisodeSelected = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
        )

        val onDismissRequest = {
            screenModel.dismissDialog()
            if (screenModel.autoOpenTrack && screenModel.isFromChangeCategory) {
                screenModel.isFromChangeCategory = false
                screenModel.showTrackDialog()
            }
        }
        when (val dialog = (state as? AnimeScreenState.Success)?.dialog) {
            null -> {}
            is AnimeInfoScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoriesTab(false)) },
                    onConfirm = { include, _ ->
                        screenModel.moveAnimeToCategoriesAndAddToLibrary(dialog.anime, include)
                    },
                )
            }
            is AnimeInfoScreenModel.Dialog.DeleteEpisodes -> {
                DeleteItemsDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.toggleAllSelection(false)
                        screenModel.deleteEpisodes(dialog.episodes)
                    },
                    isManga = false,
                )
            }
            is AnimeInfoScreenModel.Dialog.DownloadCustomAmount -> {
                DownloadCustomAmountDialog(
                    maxAmount = dialog.max,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { amount ->
                        val episodesToDownload = screenModel.getUnseenEpisodesSorted().take(amount)
                        if (episodesToDownload.isNotEmpty()) {
                            screenModel.startDownload(episodes = episodesToDownload, startNow = false)
                        }
                    },
                )
            }
            is AnimeInfoScreenModel.Dialog.DuplicateAnime -> DuplicateAnimeDialog(
                onDismissRequest = onDismissRequest,
                onConfirm = { screenModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                onOpenAnime = { navigator.push(AnimeScreen(dialog.duplicate.id)) },
                duplicateFrom = screenModel.getSourceOrStub(dialog.duplicate),
            )
            AnimeInfoScreenModel.Dialog.SettingsSheet -> EpisodeSettingsDialog(
                onDismissRequest = onDismissRequest,
                anime = successState.anime,
                onDownloadFilterChanged = screenModel::setDownloadedFilter,
                onUnseenFilterChanged = screenModel::setUnseenFilter,
                onBookmarkedFilterChanged = screenModel::setBookmarkedFilter,
                onSortModeChanged = screenModel::setSorting,
                onDisplayModeChanged = screenModel::setDisplayMode,
                onSetAsDefault = screenModel::setCurrentSettingsAsDefault,
            )
            AnimeInfoScreenModel.Dialog.TrackSheet -> {
                NavigatorAdaptiveSheet(
                    screen = AnimeTrackInfoDialogHomeScreen(
                        animeId = successState.anime.id,
                        animeTitle = successState.anime.title,
                        sourceId = successState.source.id,
                    ),
                    enableSwipeDismiss = { it.lastItem is AnimeTrackInfoDialogHomeScreen },
                    onDismissRequest = onDismissRequest,
                )
            }
            AnimeInfoScreenModel.Dialog.FullCover -> {
                val sm = rememberScreenModel { AnimeCoverScreenModel(successState.anime.id) }
                val anime by sm.state.collectAsState()
                if (anime != null) {
                    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                        if (it == null) return@rememberLauncherForActivityResult
                        sm.editCover(context, it)
                    }
                    AnimeCoverDialog(
                        coverDataProvider = { anime!! },
                        snackbarHostState = sm.snackbarHostState,
                        isCustomCover = remember(anime) { anime!!.hasCustomCover() },
                        onShareClick = { sm.shareCover(context) },
                        onSaveClick = { sm.saveCover(context) },
                        onEditClick = {
                            when (it) {
                                EditCoverAction.EDIT -> getContent.launch("image/*")
                                EditCoverAction.DELETE -> sm.deleteCustomCover(context)
                            }
                        },
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    LoadingScreen(Modifier.systemBarsPadding())
                }
            }
            AnimeInfoScreenModel.Dialog.ChangeAnimeSkipIntro -> {
                ChangeIntroLength(
                    anime = successState.anime,
                    onDismissRequest = onDismissRequest,
                    defaultIntroLength = screenModel.playerPreferences.defaultIntroLength().get(),
                )
            }
        }
    }

    private suspend fun continueWatching(context: Context, unseenEpisode: Episode?, useExternalPlayer: Boolean) {
        if (unseenEpisode != null) openEpisode(context, unseenEpisode, useExternalPlayer)
    }

    private suspend fun openEpisode(context: Context, episode: Episode, useExternalPlayer: Boolean) {
        if (httpFreeboxService.state == 3) {
            val intent = ExternalIntents()
            intent.getExternalIntent(context, episode.animeId, episode.id)
            httpFreeboxService.stopVideo()
            httpFreeboxService.playVideo(intent.episodeUrl)
        } else {
            if (useExternalPlayer) {
                context.startActivity(ExternalIntents.newIntent(context, episode.animeId, episode.id))
            } else {
                context.startActivity(PlayerActivity.newIntent(context, episode.animeId, episode.id))
            }
        }
    }

    private fun getAnimeUrl(anime_: Anime?, source_: AnimeSource?): String? {
        val anime = anime_ ?: return null
        val source = source_ as? AnimeHttpSource ?: return null

        return try {
            source.getAnimeUrl(anime.toSAnime())
        } catch (e: Exception) {
            null
        }
    }

    private fun openAnimeInWebView(context: Context, anime_: Anime?, source_: AnimeSource?) {
        getAnimeUrl(anime_, source_)?.let { url ->
            val intent = WebViewActivity.newIntent(context, url, source_?.id, anime_?.title)
            context.startActivity(intent)
        }
    }

    private fun shareAnime(context: Context, anime_: Anime?, source_: AnimeSource?) {
        try {
            getAnimeUrl(anime_, source_)?.let { url ->
                val intent = url.toUri().toShareIntent(context, type = "text/plain")
                context.startActivity(
                    Intent.createChooser(
                        intent,
                        context.getString(R.string.action_share),
                    ),
                )
            }
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    private suspend fun performSearch(navigator: Navigator, query: String, global: Boolean) {
        if (global) {
            navigator.push(GlobalAnimeSearchScreen(query))
            return
        }

        if (navigator.size < 2) {
            return
        }

        when (val previousController = navigator.items[navigator.size - 2]) {
            is HomeScreen -> {
                navigator.pop()
                AnimeLibraryTab.search(query)
            }
            is BrowseAnimeSourceScreen -> {
                navigator.pop()
                previousController.search(query)
            }
        }
    }

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    private suspend fun performGenreSearch(navigator: Navigator, genreName: String, source: AnimeSource) {
        if (navigator.size < 2) {
            return
        }

        val previousController = navigator.items[navigator.size - 2]
        if (previousController is BrowseAnimeSourceScreen && source is AnimeHttpSource) {
            navigator.pop()
            previousController.searchGenre(genreName)
        } else {
            performSearch(navigator, genreName, global = false)
        }
    }

    /**
     * Copy Anime URL to Clipboard
     */
    private fun copyAnimeUrl(context: Context, anime_: Anime?, source_: AnimeSource?) {
        val anime = anime_ ?: return
        val source = source_ as? AnimeHttpSource ?: return
        val url = source.getAnimeUrl(anime.toSAnime())
        context.copyToClipboard(url, url)
    }
}

@Composable
fun ChangeIntroLength(
    anime: Anime,
    onDismissRequest: () -> Unit,
    defaultIntroLength: Int,
) {
    val scope = rememberCoroutineScope()
    val setAnimeViewerFlags: SetAnimeViewerFlags = Injekt.get()
    val titleText = R.string.action_change_intro_length
    var newLength = 0
    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false, // Doesn't work https://issuetracker.google.com/issues/246909281
        ),
        title = { Text(text = stringResource(titleText)) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    WheelTextPicker(
                        modifier = Modifier.align(Alignment.Center),
                        texts = remember { 1..255 }.map {
                            stringResource(
                                R.string.seconds_short,
                                it,
                            )
                        },
                        onScrollFinished = {
                            newLength = it + 1
                            null
                        },
                        startIndex = if (anime.viewerFlags > 0) {
                            anime.viewerFlags.toInt() - 1
                        } else {
                            defaultIntroLength
                        },
                    )
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launchIO {
                        setAnimeViewerFlags.awaitSetSkipIntroLength(anime.id, newLength.toLong())
                        onDismissRequest()
                    }
                    Unit
                },
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
