package com.noop.ui.m3

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import kotlinx.coroutines.launch
import kotlin.math.abs

// MARK: - Material 3 choice dialogs
//
// The two ways a Pixel Settings page asks for a value: a single-choice list (radio buttons, the list
// preference) and, for a long numeric range, a snapping wheel (height, weight, max heart rate). Both are
// presentation only: the caller hands in the labels and the current index and writes the chosen one.

/** A single-choice list dialog: one radio row per option, picking a row closes it. */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 420.dp).selectableGroup(),
            ) {
                itemsIndexed(options) { i, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = M3Dimens.minTouch)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = i == selectedIndex, role = Role.RadioButton) { onPick(i) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = i == selectedIndex, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    )
}

/** A wheel in a dialog: scroll to a value, then OK writes it. */
@Composable
fun WheelDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableIntStateOf(selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Wheel(
                options = options,
                selectedIndex = current,
                onSelectedIndexChange = { current = it },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text(stringResource(R.string.summary_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    )
}

/**
 * A vertical snap wheel, [visibleCount] rows tall; the centred row is the selection, marked by a tonal
 * band. Tapping a row scrolls it to the centre, so the wheel also works without a drag (TalkBack).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Wheel(
    options: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleCount: Int = 5,
    itemHeight: Dp = 44.dp,
) {
    if (options.isEmpty()) return
    val listState = rememberLazyListState()
    val fling = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { listState.scrollToItem(selectedIndex.coerceIn(0, options.lastIndex)) }
    val centre by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2f) - mid) }?.index ?: selectedIndex
        }
    }
    LaunchedEffect(centre) { onSelectedIndexChange(centre.coerceIn(0, options.lastIndex)) }

    Box(modifier = modifier.height(itemHeight * visibleCount), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = itemHeight * ((visibleCount - 1) / 2)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(options) { i, label ->
                val selected = i == centre
                Box(
                    Modifier
                        .height(itemHeight)
                        .fillMaxWidth()
                        .clickable { scope.launch { listState.animateScrollToItem(i) } }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = if (selected) {
                            MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum")
                        } else {
                            MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
                        },
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A notice with two ways out that are not a yes and a no: [confirmLabel] acknowledges it, [secondaryLabel]
 * is the other answer (as "Don't Show Again" beside "OK"). Dismissing it otherwise counts as neither.
 * [detail] is a second paragraph under [message], its own string rather than text joined on in code.
 */
@Composable
fun NoticeDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    onDismiss: () -> Unit,
    detail: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { DialogParagraphs(message, detail) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onSecondary) { Text(secondaryLabel) } },
    )
}

/** A dialog's body as one or two paragraphs, [M3Dimens.itemGap] apart. */
@Composable
fun DialogParagraphs(first: String, second: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap)) {
        Text(first)
        if (second != null) Text(second)
    }
}

/** A plain confirmation dialog: title, message, a (destructive when [destructive]) confirm button and Cancel. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    )
}
