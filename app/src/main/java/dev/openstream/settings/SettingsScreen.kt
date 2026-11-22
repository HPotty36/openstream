package dev.openstream.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val THEME_LABELS = listOf(
    ThemeMode.SYSTEM to "시스템 설정",
    ThemeMode.LIGHT to "라이트",
    ThemeMode.DARK to "다크",
)

@Composable
fun SettingsScreen(settings: AppSettings, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("테마")
        val mode by settings.themeMode.collectAsState()
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            THEME_LABELS.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = { settings.setThemeMode(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, THEME_LABELS.size),
                ) { Text(label) }
            }
        }

        Spacer(Modifier.height(8.dp))
        Section("번역")
        ApiKey(settings)

        Spacer(Modifier.height(8.dp))
        Section("콘텐츠")
        val showAgeRestricted by settings.showAgeRestricted.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("연령 제한 콘텐츠 표시")
                Note("로그인이 필요한 영상은 목록에 보여도 재생되지 않을 수 있습니다.")
            }
            Switch(showAgeRestricted, settings::setShowAgeRestricted)
        }
    }
}

@Composable
private fun ApiKey(settings: AppSettings) {
    val hint by settings.apiKeyHint.collectAsState()
    var input by remember { mutableStateOf("") }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Anthropic API 키") },
        placeholder = { Text("sk-ant-…") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = input.isNotBlank(), onClick = { settings.setApiKey(input); input = "" }) { Text("저장") }
        if (hint != null) TextButton(onClick = { settings.setApiKey(null) }) { Text("삭제") }
    }
    Note(
        if (hint != null) "저장된 키: …$hint. 번역할 댓글과 채팅은 Anthropic으로 전송됩니다."
        else "키가 없으면 번역 버튼이 보이지 않습니다. 키는 이 기기에 암호화해 저장합니다.",
    )
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
