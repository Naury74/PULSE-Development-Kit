package com.naury.pulse.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.naury.pulse.api.PulseInspectorRegistry

/** `Pulse.open`이 여는 화면. 외부 앱이 열 수 없도록 `exported=false`로 선언한다. */
internal class PulseInspectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val inspector = PulseInspectorRegistry.current
            if (inspector != null) {
                PulseInspectorScreen(inspector, onClose = ::finish)
            } else {
                PulseTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.safeDrawingPadding().padding(Spacing.l)) { Notice(stringResource(R.string.pulse_not_initialized)) }
                    }
                }
            }
        }
    }
}
