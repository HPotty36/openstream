package dev.openstream.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openstream.platform.StreamItem

/**
 * [tablet]: the window is large on both sides, as on a tablet, a desktop window or an external monitor.
 * A phone turned sideways is wide but not a tablet. [wide]: there is room for two panes next to each other.
 */
data class WindowLayout(val tablet: Boolean, val wide: Boolean)

@Composable
fun windowLayout(): WindowLayout {
    val config = LocalConfiguration.current
    val tablet = config.smallestScreenWidthDp >= 600
    return WindowLayout(tablet, tablet && config.screenWidthDp >= 840)
}

/** A video as a tile, for the grids of the tablet layout. */
@Composable
fun VideoCard(item: StreamItem, onClick: () -> Unit, showChannel: Boolean = true, showPlatform: Boolean = false) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Thumbnail(item, Modifier.fillMaxWidth())
        Text(
            dev.openstream.translate.autoTranslated(item.title),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        MetaLine(item, showChannel, showPlatform, Modifier.padding(top = 2.dp))
    }
}
