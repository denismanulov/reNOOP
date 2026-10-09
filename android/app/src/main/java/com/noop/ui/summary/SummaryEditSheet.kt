package com.noop.ui.summary

import com.noop.ui.m3.SheetBackdropEffect
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.noop.R
import com.noop.ui.KeyMetric
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PeriodSegmented
import com.noop.ui.m3.RowIcon

// MARK: - Summary "Edit" sheet
//
// The Pinned section's Edit, as a Material bottom sheet: the layout choice (Detailed / Compact) at the top,
// then the pinned metrics in order — drag a row by its handle to move it, minus to unpin — and the metrics
// that are not pinned, plus to pin. Every change is saved as it is made, as Google's own sheets do; the
// rings and the two fitness tiles are not listed (the rings are always drawn and the tiles are changed by
// long-pressing them), and the stored list keeps their entries untouched.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SummaryEditSheet(
    layout: SummaryLayout,
    pinned: List<KeyMetric>,
    unpinned: List<KeyMetric>,
    onLayout: (SummaryLayout) -> Unit,
    onPinned: (List<KeyMetric>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SheetBackdropEffect(MaterialTheme.colorScheme.surfaceContainer)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = M3Dimens.screenPadding)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.summary_customize),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_done)) }
            }

            Text(
                stringResource(R.string.summary_layout),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            PeriodSegmented(
                options = listOf(
                    stringResource(R.string.summary_layout_detailed),
                    stringResource(R.string.summary_layout_compact),
                ),
                selectedIndex = if (layout == SummaryLayout.COMPACT) 1 else 0,
                onSelect = { onLayout(if (it == 1) SummaryLayout.COMPACT else SummaryLayout.DETAILED) },
            )

            PinnedList(pinned = pinned, onPinned = onPinned)

            ListGroup(header = stringResource(R.string.summary_edit_hidden)) {
                if (unpinned.isEmpty()) {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.summary_nothing_hidden),
                            titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                unpinned.forEach { metric ->
                    item { shape ->
                        val title = stringResource(metric.titleRes)
                        ListRow(
                            shape = shape,
                            title = title,
                            leading = {
                                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.AddCircle,
                                        contentDescription = stringResource(R.string.today_customize_show, title),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            trailing = { RowIcon(keyMetricIcon(metric), keyMetricTint(metric)) },
                            onClick = { onPinned(pinned + metric) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The pinned rows, reordered by dragging a row's handle; each move is saved when the finger lifts. */
@Composable
private fun PinnedList(pinned: List<KeyMetric>, onPinned: (List<KeyMetric>) -> Unit) {
    // The order being dragged, kept locally so the rows follow the finger without a save per step.
    var order by remember(pinned) { mutableStateOf(pinned) }
    var dragging by remember { mutableStateOf<KeyMetric?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { M3Dimens.groupGap.toPx() }
    val latestOrder by rememberUpdatedState(order)

    fun move(from: Int, to: Int) {
        if (from !in order.indices || to !in order.indices) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    ListGroup(header = stringResource(R.string.summary_edit_shown)) {
        if (order.isEmpty()) {
            item { shape ->
                ListRow(
                    shape = shape,
                    title = stringResource(R.string.summary_pin_prompt),
                    titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        order.forEachIndexed { index, metric ->
            item { shape ->
                val title = stringResource(metric.titleRes)
                val up = stringResource(R.string.today_customize_move_up, title)
                val down = stringResource(R.string.today_customize_move_down, title)
                val lifted = dragging == metric
                // Rows are positional, so the handle under the finger may show another metric after a swap;
                // the drag follows the metric it started on, not the row.
                val rowMetric by rememberUpdatedState(metric)
                ListRow(
                    shape = shape,
                    title = title,
                    modifier = Modifier
                        .onSizeChanged { if (index == 0) rowHeight = it.height }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            if (lifted) {
                                translationY = dragOffset
                                shadowElevation = 8.dp.toPx()
                            }
                        }
                        .semantics {
                            customActions = listOfNotNull(
                                if (index > 0) CustomAccessibilityAction(up) {
                                    move(index, index - 1); onPinned(order); true
                                } else null,
                                if (index < order.lastIndex) CustomAccessibilityAction(down) {
                                    move(index, index + 1); onPinned(order); true
                                } else null,
                            )
                        },
                    leading = {
                        IconButton(onClick = { onPinned(order - metric) }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Filled.RemoveCircle,
                                contentDescription = stringResource(R.string.today_customize_hide, title),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RowIcon(keyMetricIcon(metric), keyMetricTint(metric))
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = stringResource(R.string.summary_drag_to_reorder),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(40.dp)
                                    .padding(8.dp)
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { dragging = rowMetric; dragOffset = 0f },
                                            onDragEnd = {
                                                dragging = null
                                                dragOffset = 0f
                                                onPinned(latestOrder)
                                            },
                                            onDragCancel = {
                                                dragging = null
                                                dragOffset = 0f
                                                onPinned(latestOrder)
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                val pitch = rowHeight + gap
                                                if (pitch <= 0f) return@detectDragGestures
                                                val held = dragging ?: return@detectDragGestures
                                                val at = latestOrder.indexOf(held)
                                                if (dragOffset > pitch / 2 && at < latestOrder.lastIndex) {
                                                    move(at, at + 1)
                                                    dragOffset -= pitch
                                                } else if (dragOffset < -pitch / 2 && at > 0) {
                                                    move(at, at - 1)
                                                    dragOffset += pitch
                                                }
                                            },
                                        )
                                    },
                            )
                        }
                    },
                )
            }
        }
    }
}
