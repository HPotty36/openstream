package dev.openstream.translate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A small pressable word, for the actions under a title or a description. */
@Composable
fun TextAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/**
 * Text with a translate action under it. With [auto], text in neither Korean nor English starts out
 * translated, as titles do in the lists. [collapsedLines] folds long text behind a 더보기 action.
 * [leading] goes at the start of the action row, before the actions.
 */
@Composable
fun TranslatableText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    kind: TextKind = TextKind.TEXT,
    auto: Boolean = false,
    collapsedLines: Int? = null,
    // Times in the text ("1:23") jump there and web addresses open; null leaves them plain.
    durationMs: Long? = null,
    onTimestamp: ((Long) -> Unit)? = null,
    onLink: ((String) -> Unit)? = null,
    leading: @Composable RowScope.() -> Unit = {},
) {
    val translator = LocalAutoTranslate.current?.translator
    val scope = rememberCoroutineScope()
    val autoShown = if (auto) autoTranslated(text, kind) else text
    var manual by remember(text) { mutableStateOf<String?>(null) }
    var choice by remember(text) { mutableStateOf<Boolean?>(null) }
    var busy by remember(text) { mutableStateOf(false) }
    var error by remember(text) { mutableStateOf<String?>(null) }
    var expanded by remember(text) { mutableStateOf(false) }
    var cut by remember(text) { mutableStateOf(false) }

    val translation = manual ?: autoShown.takeIf { it != text }
    val showing = translation != null && (choice ?: auto)
    val canTranslate = translator != null && translator.hasKey() && needsTranslation(text)
    val folds = collapsedLines != null

    Column(modifier) {
        Text(
            dev.openstream.watch.linkedText(if (showing) translation!! else text, durationMs, onTimestamp, onLink),
            style = style,
            color = color,
            maxLines = if (!folds || expanded) Int.MAX_VALUE else collapsedLines!!,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (folds && !expanded && it.hasVisualOverflow) cut = true },
            modifier = if (folds) Modifier.fillMaxWidth().clickable(enabled = cut) { expanded = !expanded } else Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
        // With nothing to press, the row keeps only what [leading] puts in it, so a short text sits
        // close to what follows.
        val hasActions = cut || translation != null || busy || canTranslate
        Row(
            if (hasActions) Modifier.fillMaxWidth().heightIn(min = 32.dp) else Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.weight(1f))
            if (cut) TextAction(if (expanded) "접기" else "더보기") { expanded = !expanded }
            when {
                translation != null -> TextAction(if (showing) "원문 보기" else "번역 보기") { choice = !showing }
                busy -> TextAction("번역 중", enabled = false) {}
                canTranslate -> TextAction("번역") {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            manual = translator!!.translate(listOf(text), kind).first()
                            choice = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "번역하지 못했습니다."
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
    }
}
