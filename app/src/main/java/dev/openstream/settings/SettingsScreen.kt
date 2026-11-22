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

private val LEAVE_LABELS = listOf(
    LeaveMode.PIP to "작은 창",
    LeaveMode.AUDIO to "소리만",
    LeaveMode.PAUSE to "멈춤",
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    library: dev.openstream.library.Library,
    updater: dev.openstream.update.Updater,
    /** Outlives the screen, so an update keeps downloading after the settings are left. */
    appScope: kotlinx.coroutines.CoroutineScope,
    onImported: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
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
                    colors = choiceColors(),
                    icon = {},
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
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("연령 제한 콘텐츠 표시")
                Note("로그인이 필요한 영상은 재생되지 않습니다.")
            }
            Switch(showAgeRestricted, settings::setShowAgeRestricted)
        }

        val sponsorBlock by settings.sponsorBlock.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("스폰서 구간 건너뛰기")
                Note("YouTube 영상에서 SponsorBlock에 등록된 구간을 건너뜁니다.")
            }
            Switch(sponsorBlock, settings::setSponsorBlock)
        }

        Spacer(Modifier.height(8.dp))
        Section("재생")
        val leaveMode by settings.leaveMode.collectAsState()
        // The sound-only mode shows a notification with a stop button, so it asks for the permission once.
        val askForPlayback = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) {}
        Text("재생 중에 앱을 벗어나면")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LEAVE_LABELS.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = leaveMode == value,
                    onClick = {
                        settings.setLeaveMode(value)
                        if (value == LeaveMode.AUDIO && android.os.Build.VERSION.SDK_INT >= 33) {
                            askForPlayback.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, LEAVE_LABELS.size),
                    colors = choiceColors(),
                    icon = {},
                ) { Text(label) }
            }
        }
        // The other two choices say what they do in their own name.
        if (leaveMode == LeaveMode.AUDIO) Note("알림을 펼쳐 정지를 누르면 멈춥니다.")

        Spacer(Modifier.height(8.dp))
        Section("알림")
        val context = androidx.compose.ui.platform.LocalContext.current
        val notify by settings.notifyUpdates.collectAsState()
        fun setNotify(on: Boolean) {
            settings.setNotifyUpdates(on)
            dev.openstream.notify.scheduleUpdates(context, on)
        }
        val askPermission = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { granted -> setNotify(granted) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("새 영상·방송 알림")
                Note("구독한 채널을 30분마다 확인합니다.")
            }
            Switch(notify, { on ->
                if (on && android.os.Build.VERSION.SDK_INT >= 33) askPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else setNotify(on)
            })
        }

        Spacer(Modifier.height(8.dp))
        Section("구독과 백업")
        DataSection(library, onImported)

        Spacer(Modifier.height(8.dp))
        Section("업데이트")
        dev.openstream.update.UpdateSection(updater, dev.openstream.BuildConfig.VERSION_NAME, appScope)
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
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
        if (hint != null) "저장된 키 …$hint. 한국어·영어가 아닌 제목과 채널 이름은 화면에 나올 때, 댓글·채팅·설명은 번역을 누를 때 Anthropic으로 보냅니다."
        else "키를 저장하면 한국어·영어가 아닌 제목과 채널 이름은 번역해서 보여 주고, 댓글·채팅·설명에는 번역 버튼이 생깁니다.",
    )
}

/** The chosen one of several is filled light, the same way as the chips elsewhere in the app. */
@Composable
private fun choiceColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.onSurface,
    activeContentColor = MaterialTheme.colorScheme.surface,
    activeBorderColor = MaterialTheme.colorScheme.outline,
    inactiveBorderColor = MaterialTheme.colorScheme.outline,
)

@Composable
private fun Section(title: String) {
    // Grey, not the accent: the accent is kept for what can be pressed or is switched on.
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
