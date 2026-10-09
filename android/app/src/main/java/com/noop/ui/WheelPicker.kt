package com.noop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// MARK: - WheelPicker — a snap-scrolling wheel (onboarding age / weight / height, interval timer)
//
// Presentation only: no persistence here. Design-system tokens only (Palette / NoopType).

/**
 * A vertical snap wheel: [visibleCount] rows tall (odd), the centred row is the selection. Starts centred
 * on [selectedIndex] and reports the settled centre via [onSelectedIndexChange]. A tinted band marks the
 * centre so the affordance reads without a value label. Pure presentation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(
    options: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleCount: Int = 5,
    itemHeight: Dp = 40.dp,
) {
    if (options.isEmpty()) return
    val listState = rememberLazyListState()
    val fling = rememberSnapFlingBehavior(lazyListState = listState)
    // Centre the initial selection once (scrollToItem aligns the item to the content-area start, which the
    // vertical contentPadding pushes to the middle row). Guarded so a recomposition doesn't yank the wheel.
    LaunchedEffect(Unit) { listState.scrollToItem(selectedIndex.coerceIn(0, options.lastIndex)) }
    // The centred item = the visible item whose middle is nearest the viewport centre.
    val centre by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2f) - mid) }?.index ?: selectedIndex
        }
    }
    LaunchedEffect(centre) { onSelectedIndexChange(centre.coerceIn(0, options.lastIndex)) }

    Box(modifier = modifier.height(itemHeight * visibleCount), contentAlignment = Alignment.Center) {
        // Centre selection band.
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.surfaceInset),
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = itemHeight * ((visibleCount - 1) / 2)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(options) { i, label ->
                Box(Modifier.height(itemHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = NoopType.bodyNumber,
                        color = if (i == centre) Palette.textPrimary else Palette.textTertiary,
                    )
                }
            }
        }
    }
}
