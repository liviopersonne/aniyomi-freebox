package eu.kanade.presentation.library

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.OverflowMenu
import eu.kanade.presentation.components.Pill
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.theme.active
import eu.kanade.tachiyomi.R

@Composable
fun LibraryToolbar(
    hasActiveFilters: Boolean,
    selectedCount: Int,
    title: LibraryToolbarTitle,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
    castState: Int,
    onClickCast: () -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickOpenRandomEntry: () -> Unit,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    navigateUp: (() -> Unit)? = null,
) = when {
    selectedCount > 0 -> LibrarySelectionToolbar(
        selectedCount = selectedCount,
        onClickUnselectAll = onClickUnselectAll,
        onClickSelectAll = onClickSelectAll,
        onClickInvertSelection = onClickInvertSelection,
        navigateUp = navigateUp,
    )
    else -> LibraryRegularToolbar(
        title = title,
        hasFilters = hasActiveFilters,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        castState = castState,
        onClickCast = onClickCast,
        onClickFilter = onClickFilter,
        onClickRefresh = onClickRefresh,
        onClickOpenRandomEntry = onClickOpenRandomEntry,
        scrollBehavior = scrollBehavior,
        navigateUp = navigateUp,
    )
}

@Composable
fun LibraryRegularToolbar(
    title: LibraryToolbarTitle,
    hasFilters: Boolean,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    castState: Int,
    onClickCast: () -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickOpenRandomEntry: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    navigateUp: (() -> Unit)? = null,
) {
    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
    SearchToolbar(
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.text,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, false),
                    overflow = TextOverflow.Ellipsis,
                )
                if (title.numberOfEntries != null) {
                    Pill(
                        text = "${title.numberOfEntries}",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                        fontSize = 14.sp,
                    )
                }
            }
        },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        actions = {
            val castTint: Color = when (castState) {
                1 -> Color.Yellow // Pending
                2 -> Color.Red // Connected but no freebox player
                3 -> Color.Cyan // Connected
                else -> LocalContentColor.current // Disconnected
            }
            IconButton(onClick = onClickCast) {
                Icon(Icons.Outlined.CastConnected, contentDescription = stringResource(R.string.action_cast), tint = castTint)
            }

            val filterTint = if (hasFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
            IconButton(onClick = onClickFilter) {
                Icon(Icons.Outlined.FilterList, contentDescription = stringResource(R.string.action_filter), tint = filterTint)
            }

            OverflowMenu { closeMenu ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.pref_category_library_update)) },
                    onClick = {
                        onClickRefresh()
                        closeMenu()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.action_open_random_manga)) },
                    onClick = {
                        onClickOpenRandomEntry()
                        closeMenu()
                    },
                )
            }
        },
        scrollBehavior = scrollBehavior,
        navigateUp = navigateUp,
    )
}

@Composable
fun LibrarySelectionToolbar(
    selectedCount: Int,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
    navigateUp: (() -> Unit)? = null,
) {
    AppBar(
        titleContent = { Text(text = "$selectedCount") },
        actions = {
            IconButton(onClick = onClickSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
            }
            IconButton(onClick = onClickInvertSelection) {
                Icon(Icons.Outlined.FlipToBack, contentDescription = stringResource(R.string.action_select_inverse))
            }
        },
        isActionMode = true,
        onCancelActionMode = onClickUnselectAll,
        navigateUp = navigateUp,
    )
}

@Immutable
data class LibraryToolbarTitle(
    val text: String,
    val numberOfEntries: Int? = null,
)
