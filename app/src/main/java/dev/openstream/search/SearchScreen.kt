package dev.openstream.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import dev.openstream.ui.SelectChip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import dev.openstream.ui.VideoCard
import dev.openstream.ui.windowLayout
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.openstream.platform.Channel
import dev.openstream.platform.SearchResult
import dev.openstream.platform.StreamItem
import dev.openstream.platform.StreamingPlatform
import dev.openstream.ui.ChannelRow
import dev.openstream.ui.Paged
import dev.openstream.ui.PagedFooter
import dev.openstream.ui.VideoRow
import kotlinx.coroutines.CoroutineScope

/** Lives above the screen, so the results are still there after coming back from a video. */
class SearchState(val platforms: List<StreamingPlatform>, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    var platform by mutableStateOf(platforms.first()); private set
    var results by mutableStateOf<Paged<SearchResult>?>(null); private set

    fun submit() {
        val text = query.trim()
        if (text.isEmpty()) return
        val where = platform
        results = Paged(scope) { where.search(text, it) }
    }

    /** Switching platforms repeats the search there. */
    fun choose(next: StreamingPlatform) {
        if (next === platform) return
        platform = next
        results = null
        submit()
    }

    fun clear() {
        query = ""
        results = null
    }
}

@Composable
fun SearchScreen(state: SearchState, onChannel: (Channel) -> Unit, onItem: (StreamItem) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 640.dp).fillMaxWidth(),
            singleLine = true,
            shape = CircleShape,
            placeholder = { Text("${state.platform.id.label} 검색") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = state::clear) { Icon(Icons.Outlined.Close, contentDescription = "지우기") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); state.submit() }),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.platforms.forEach { platform ->
                SelectChip(
                    selected = state.platform === platform,
                    onClick = { state.choose(platform) },
                    label = { Text(platform.id.label) },
                )
            }
        }
        val results = state.results ?: return@Column
        if (windowLayout().tablet) {
            LazyVerticalGrid(GridCells.Adaptive(260.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                gridItems(
                    results.items,
                    span = { GridItemSpan(if (it is SearchResult.OfChannel) maxLineSpan else 1) },
                ) { result ->
                    when (result) {
                        is SearchResult.OfChannel -> ChannelRow(result.channel) { onChannel(result.channel) }
                        is SearchResult.OfItem -> VideoCard(result.item, onClick = { onItem(result.item) })
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { PagedFooter(results, empty = "검색 결과가 없습니다.") }
            }
        } else {
            LazyColumn {
                items(results.items) { result ->
                    when (result) {
                        is SearchResult.OfChannel -> ChannelRow(result.channel) { onChannel(result.channel) }
                        is SearchResult.OfItem -> VideoRow(result.item, onClick = { onItem(result.item) })
                    }
                }
                item { PagedFooter(results, empty = "검색 결과가 없습니다.") }
            }
        }
    }
}
