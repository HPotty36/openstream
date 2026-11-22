package dev.openstream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.openstream.platform.Page
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** A list that grows one page at a time. */
class Paged<T>(private val scope: CoroutineScope, private val load: suspend (String?) -> Page<T>) {
    var items by mutableStateOf<List<T>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var ended by mutableStateOf(false); private set
    private var next: String? = null

    fun loadMore() {
        if (loading || ended) return
        loading = true
        error = null
        scope.launch {
            try {
                val page = load(next)
                items = items + page.items
                next = page.next
                ended = page.next == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }
}

fun errorText(e: Throwable): String = when (e) {
    is AgeRestrictedContentException -> "로그인해야 볼 수 있는 연령 제한 영상입니다."
    is GeographicRestrictionException -> "이 지역에서는 볼 수 없는 영상입니다."
    is PrivateContentException -> "비공개 영상입니다."
    is PaidContentException -> "유료 영상입니다."
    is ContentNotAvailableException -> "볼 수 없는 영상입니다."
    is ReCaptchaException -> "요청이 많아 잠시 차단되었습니다. 조금 뒤에 다시 시도하세요."
    is IOException -> "네트워크에 연결할 수 없습니다."
    else -> e.message ?: e.javaClass.simpleName
}

/** The last row of a paged list: loads the next page when it scrolls into view. */
@Composable
fun <T> PagedFooter(paged: Paged<T>, empty: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val error = paged.error
        when {
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = paged::loadMore) { Text("다시 시도") }
            }
            paged.ended -> if (paged.items.isEmpty()) {
                Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                LaunchedEffect(paged.items.size) { paged.loadMore() }
            }
        }
    }
}
