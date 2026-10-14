package com.naury.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private const val ICON_TINT_ALPHA = 0.14f

/** 아이콘 원 안에 넣은 머리 아이콘. 대화상자·시트의 성격(삭제·내보내기 등)을 색과 모양으로 함께 알린다. */
@Composable
internal fun HeaderIcon(icon: ImageVector, tint: Color, size: Int = 48) {
    Box(
        Modifier
            .size(size.dp)
            .background(tint.copy(alpha = ICON_TINT_ALPHA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size / 2).dp))
    }
}

/**
 * 확인 대화상자. 되돌릴 수 없는 동작은 [destructive]로 오류 색 단추를 쓴다.
 * 단추는 세로로 쌓아 긴 글자도 잘리지 않게 한다.
 */
@Composable
internal fun PulseConfirmDialog(
    icon: ImageVector,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 2.dp,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Column(
                Modifier.padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                HeaderIcon(icon, accent)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Column(Modifier.padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(confirmLabel, fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.pulse_cancel))
                    }
                }
            }
        }
    }
}

/** 아래에서 올라오는 시트. 머리(아이콘·제목·설명), 내용, 아래 고정 단추 줄로 나눈다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PulseSheet(
    icon: ImageVector,
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                HeaderIcon(icon, MaterialTheme.colorScheme.primary, size = 40)
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl, vertical = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                content = content,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.m)) { actions() }
        }
    }
}

/** 시트 안의 작은 소제목. */
@Composable
internal fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s),
    )
}

/** 전체 화면 편집기(재실행·모의 응답 규칙)의 머리 줄. 닫기, 제목, 오른쪽 주 동작. */
@Composable
internal fun EditorHeader(title: String, onClose: () -> Unit, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pulse_close)) }
            Column(Modifier.weight(1f).padding(start = Spacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            action?.let { Box(Modifier.padding(end = Spacing.m)) { it() } }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 둘 중 하나를 고르는 분할 버튼. [firstSelected]가 true면 앞쪽이 선택된 상태다. */
@Composable
internal fun TwoChoiceRow(first: String, second: String, firstSelected: Boolean, onSelect: (first: Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = firstSelected, onClick = {
            onSelect(true)
        }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) {
            Text(first)
        }
        SegmentedButton(selected = !firstSelected, onClick = {
            onSelect(false)
        }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) {
            Text(second)
        }
    }
}

/** 파일로 저장할지 공유할지 고르는 줄. */
@Composable
internal fun DestinationChoice(share: Boolean, onShare: (Boolean) -> Unit) {
    SheetLabel(stringResource(R.string.pulse_export_destination))
    TwoChoiceRow(stringResource(R.string.pulse_export_to_file), stringResource(R.string.pulse_export_to_share), !share) { onShare(!it) }
}
