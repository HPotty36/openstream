package dev.openstream.watch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.openstream.platform.ChatMessage
import dev.openstream.platform.StreamItem
import dev.openstream.platform.StreamingPlatform
import dev.openstream.settings.AppSettings
import dev.openstream.translate.Translator
import dev.openstream.translate.needsTranslation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val KEPT = 300
// Chat is translated in rounds, not message by message: fewer requests, and the model sees the lines together.
private const val ROUND_MS = 3_000L
private const val ROUND_SIZE = 20

/** The chat of a live broadcast, read-only. Follows the newest message unless the reader scrolled up. */
@Composable
fun Chat(
    platform: StreamingPlatform,
    item: StreamItem,
    translator: Translator,
    settings: AppSettings,
    modifier: Modifier,
    header: @Composable () -> Unit,
) {
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

    val keyHint by settings.apiKeyHint.collectAsState()
    var translating by rememberSaveable(item.id) { mutableStateOf(false) }
    var translateError by remember(item.id) { mutableStateOf<String?>(null) }
    val translations = remember(item.id) { mutableStateMapOf<String, String>() }
    LaunchedEffect(translating) {
        if (!translating) return@LaunchedEffect
        translateError = null
        while (true) {
            val batch = messages.takeLast(ROUND_SIZE * 2).map { it.text }.distinct()
                .filter { needsTranslation(it) && it !in translations }.takeLast(ROUND_SIZE)
            if (batch.isNotEmpty()) {
                try {
                    batch.zip(translator.translate(batch)).forEach { (from, to) -> translations[from] = to }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Stop instead of repeating a request that keeps failing.
                    translateError = e.message ?: "번역하지 못했습니다."
                    translating = false
                    return@LaunchedEffect
                }
            }
            delay(ROUND_MS)
        }
    }

    val list = rememberLazyListState()
    LaunchedEffect(messages.size, translations.size) {
        val lastSeen = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        // Two rows sit above the messages: the header and the title.
        if (messages.isNotEmpty() && lastSeen >= list.layoutInfo.totalItemsCount - 3) list.scrollToItem(messages.size + 1)
    }
    LazyColumn(modifier, state = list) {
        item { header() }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("채팅", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
                if (keyHint != null) {
                    TextButton(onClick = { translating = !translating }) { Text(if (translating) "번역 끄기" else "번역 켜기") }
                }
            }
            translateError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(messages) { message ->
            val author = MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = author, fontWeight = FontWeight.Medium)) { append(message.author) }
                        append("  ")
                        append(message.text)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                val translated = translations[message.text]
                if (translating && translated != null && translated != message.text) {
                    Text(translated, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
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
