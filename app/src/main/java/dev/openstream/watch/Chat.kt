package dev.openstream.watch

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.openstream.platform.ChatMessage
import dev.openstream.platform.StreamItem
import dev.openstream.platform.StreamingPlatform
import kotlinx.coroutines.CancellationException

private const val KEPT = 300

/** The chat of a live broadcast, read-only. Follows the newest message unless the reader scrolled up. */
@Composable
fun Chat(platform: StreamingPlatform, item: StreamItem, modifier: Modifier, header: @Composable () -> Unit) {
    val messages = remember(item.id) { mutableStateListOf<ChatMessage>() }
    var status by remember(item.id) { mutableStateOf<String?>("새 채팅을 기다리는 중입니다.") }
    LaunchedEffect(item.id) {
        try {
            platform.liveChat(item.id).collect { message ->
                status = null
                if (messages.size >= KEPT) messages.removeRange(0, KEPT / 5)
                messages.add(message)
            }
            status = "채팅이 끝났습니다."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "채팅에 연결하지 못했습니다."
        }
    }

    val list = rememberLazyListState()
    LaunchedEffect(messages.size) {
        val lastSeen = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        // Two rows sit above the messages: the header and the title.
        if (messages.isNotEmpty() && lastSeen >= list.layoutInfo.totalItemsCount - 3) list.scrollToItem(messages.size + 1)
    }
    LazyColumn(modifier, state = list) {
        item { header() }
        item {
            Text("채팅", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
        }
        items(messages) { message ->
            val author = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = author, fontWeight = FontWeight.Medium)) { append(message.author) }
                    append("  ")
                    append(message.text)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
            )
        }
        status?.let {
            item {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
