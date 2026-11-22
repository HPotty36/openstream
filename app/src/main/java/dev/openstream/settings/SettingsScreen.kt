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
fun SettingsScreen(settings: AppSettings, library: dev.openstream.library.Library, onImported: () -> Unit, modifier: Modifier = Modifier) {
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
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("연령 제한 콘텐츠 표시")
                Note("로그인이 필요한 영상은 목록에 보여도 재생되지 않을 수 있습니다.")
            }
            Switch(showAgeRestricted, settings::setShowAgeRestricted)
        }

        val sponsorBlock by settings.sponsorBlock.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("스폰서 구간 건너뛰기")
                Note("YouTube 영상에서 SponsorBlock에 등록된 광고 구간을 건너뜁니다. 어떤 영상인지 서버가 알 수 없게 영상 ID 해시의 앞부분만 보냅니다.")
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
                ) { Text(label) }
            }
        }
        Note(
            when (leaveMode) {
                LeaveMode.PIP -> "영상이 작은 창으로 줄어들어 다른 앱 위에서 계속 재생됩니다."
                LeaveMode.AUDIO -> "화면을 끄거나 다른 앱으로 가도 소리가 계속 나옵니다. 알림을 펼쳐 정지를 누르면 멈춥니다."
                LeaveMode.PAUSE -> "영상이 멈춥니다."
            },
        )

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
                Note("구독한 채널을 30분마다 확인해서 방송이 시작되거나 새 영상이 올라오면 알립니다.")
            }
            Switch(notify, { on ->
                if (on && android.os.Build.VERSION.SDK_INT >= 33) askPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else setNotify(on)
            })
        }

        Spacer(Modifier.height(8.dp))
        Section("구독과 백업")
        DataSection(library, onImported)
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
        if (hint != null) "저장된 키: …$hint. 번역할 댓글과 채팅은 Anthropic으로 전송됩니다."
        else "키가 없으면 번역 버튼이 보이지 않습니다. 키는 이 기기에 암호화해 저장합니다.",
    )
}

@Composable
private fun Section(title: String) {
    // Grey, not the accent: the accent is kept for what can be pressed or is switched on.
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
