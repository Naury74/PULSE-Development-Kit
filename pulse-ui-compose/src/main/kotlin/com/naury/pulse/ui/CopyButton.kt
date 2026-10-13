package com.naury.pulse.ui

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

/** 사용자가 누를 때만 [text]를 만들어 클립보드에 넣는다. */
@Composable
internal fun CopyButton(label: String = stringResource(R.string.pulse_copy), text: suspend () -> String) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.pulse_copied)
    OutlinedButton(
        onClick = {
            scope.launch {
                val clip = ClipData.newPlainText("PULSE", text())
                // 마스킹했더라도 개인 정보가 남을 수 있어 Android 13+의 클립보드 미리보기에서 내용을 숨긴다.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                }
                clipboard.setClipEntry(ClipEntry(clip))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }
        },
    ) { Text(label) }
}
