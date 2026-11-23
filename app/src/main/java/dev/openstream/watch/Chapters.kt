package dev.openstream.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.openstream.platform.Chapter

/**
 * The chapter playing now, under the video; pressed, the whole list opens, and a chapter in it jumps there.
 * The list shows YouTube's picture for each chapter when there is one.
 */
@Composable
fun ChapterBar(chapters: List<Chapter>, current: Int, onSeek: ((Long) -> Unit)?) {
    var open by remember(chapters) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ViewList, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("챕터 ${current + 1}/${chapters.size}", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                chapters[current].title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (open) "챕터 접기" else "챕터 모두 보기",
                tint = colors.onSurfaceVariant,
            )
        }
        if (open) {
            val pictures = chapters.any { it.thumbnailUrl != null }
            chapters.forEachIndexed { index, chapter ->
                val now = index == current
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (now) Modifier.background(colors.surfaceVariant.copy(alpha = 0.5f)) else Modifier)
                        .clickable(enabled = onSeek != null) { onSeek?.invoke(chapter.startMs) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pictures) {
                        AsyncImage(
                            chapter.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 80.dp, height = 45.dp).clip(RoundedCornerShape(6.dp))
                                .background(colors.surfaceVariant),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (now) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(clockTime(chapter.startMs), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                }
            }
        }
    }
}
