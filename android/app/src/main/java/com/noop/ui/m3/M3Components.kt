package com.noop.ui.m3

import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R

// MARK: - Material 3 building blocks for the redesigned screens
//
// Android twin of the iOS 26 redesign's shared pieces (SummaryCard, SummarySectionHeader,
// SummaryCardTitleRow, grouped Form rows): same anatomy, drawn the way Google's own apps draw it —
// tonal surfaceContainer cards instead of white-on-grey, segmented list groups (large outer corners,
// small inner corners, 2 dp gaps) instead of inset-grouped tables, Material Switch / SegmentedButton.
// Colours come from MaterialTheme.colorScheme (Material You) and [Health.colors] (fixed data hues);
// no literal colours below this line.

/** Spacing and shape tokens for the redesigned screens. */
object M3Dimens {
    val screenPadding = 16.dp
    val cardRadius = 24.dp
    val heroRadius = 28.dp
    val groupOuter = 24.dp
    val groupInner = 4.dp
    val groupGap = 2.dp
    val cardPadding = 16.dp
    val itemGap = 12.dp
    val sectionTop = 20.dp
    val rowMinHeight = 56.dp
    val rowTwoLineHeight = 72.dp
    val iconCircle = 40.dp
    val minTouch = 48.dp

    /** Space left under a tab root's last item so it clears the navigation bar and mini-player. */
    val bottomBarClearance = 24.dp
}

/** The shape of item [index] of [count] in a segmented list group. */
fun groupItemShape(index: Int, count: Int): Shape {
    val outer = M3Dimens.groupOuter
    val inner = M3Dimens.groupInner
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(outer, outer, inner, inner)
        index == count - 1 -> RoundedCornerShape(inner, inner, outer, outer)
        else -> RoundedCornerShape(inner)
    }
}

/**
 * A tab root's large title (Pixel apps' expanded top app bar), with optional trailing actions such as
 * the profile avatar. Marked as a heading for TalkBack.
 */
@Composable
fun LargeTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = M3Dimens.screenPadding, end = 8.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

/**
 * The top app bar of a pushed screen (a metric page, All Data, Training Load): back arrow, one-line title,
 * optional actions. [large] is the expanded bar a Pixel app gives a list page (All Metrics, Trends); it
 * collapses into the small bar as [scrollBehavior] scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushedTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    val back: @Composable () -> Unit = {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
        }
    }
    if (large) {
        LargeTopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = modifier,
            navigationIcon = back,
            actions = actions,
            colors = TopAppBarDefaults.largeTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            // The app Scaffold already pads its content below the status bar.
            windowInsets = WindowInsets(0, 0, 0, 0),
            scrollBehavior = scrollBehavior,
        )
    } else {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = modifier,
            navigationIcon = back,
            actions = actions,
            colors = colors,
            windowInsets = WindowInsets(0, 0, 0, 0),
            scrollBehavior = scrollBehavior,
        )
    }
}

/** A section title (titleLarge) with an optional trailing text action, e.g. "Pinned" … "Edit". */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = M3Dimens.sectionTop, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * The one card of the redesign: a tonal surfaceContainerLow block, 24 dp corners, 16 dp padding.
 * Clickable when [onClick] is given (the whole card is one target, as on iOS).
 */
@Composable
fun HealthCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    shape: Shape = RoundedCornerShape(M3Dimens.cardRadius),
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(M3Dimens.cardPadding),
    verticalSpacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth().clip(shape).background(color)
    val clickable = if (onClick != null) {
        base.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else base
    Column(
        modifier = clickable.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}

/**
 * A card's title row: coloured icon + coloured title, then an optional stamp ("Today", "07:12") and a
 * chevron when the card opens something.
 */
@Composable
fun CardTitleRow(
    icon: ImageVector?,
    title: String,
    tint: Color,
    modifier: Modifier = Modifier,
    stamp: String? = null,
    chevron: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (stamp != null) {
            Text(
                text = stamp,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (chevron) ChevronRight()
    }
}

/** The trailing "opens something" chevron, in onSurfaceVariant. */
@Composable
fun ChevronRight(modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(20.dp),
    )
}

/** A big figure with a smaller, quieter unit after it: "52 ms", "7 h 12 min" is two of these. */
@Composable
fun ValueWithUnit(
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    unitStyle: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    unitColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = valueStyle.copy(fontFeatureSettings = "tnum"), color = color, maxLines = 1)
        if (!unit.isNullOrEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(
                unit,
                style = unitStyle,
                color = unitColor,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

/** A Pixel-Settings style icon: [icon] centred on a tonal circle, or on another [shape] (see M3Expressive). */
@Composable
fun TonalIcon(
    icon: ImageVector,
    pair: TonalPair,
    modifier: Modifier = Modifier,
    size: Dp = M3Dimens.iconCircle,
    shape: Shape = CircleShape,
) {
    Box(
        modifier = modifier.size(size).clip(shape).background(pair.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = pair.content, modifier = Modifier.size(size * 0.55f))
    }
}

/** Collects the rows of one [ListGroup]; each row receives the shape for its position. */
class ListGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable (Shape) -> Unit>()

    /** Adds one row; draw it with the [Shape] handed in (usually via [ListRow]). */
    fun item(content: @Composable (Shape) -> Unit) {
        rows += content
    }
}

/**
 * A segmented list group: rows stacked with 2 dp gaps, the group's outer corners large and the inner
 * ones small (Android 16 Settings). An optional [header] sits above in the primary colour.
 */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: ListGroupScope.() -> Unit,
) {
    val scope = ListGroupScope().apply(content)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(M3Dimens.groupGap)) {
        if (header != null) {
            Text(
                text = header,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        val count = scope.rows.size
        scope.rows.forEachIndexed { i, row -> row(groupItemShape(i, count)) }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

/**
 * One list row: optional leading slot, a title with an optional supporting line, optional trailing
 * slot. Clickable when [onClick] is set; a row without [onClick] is plain content.
 */
@Composable
fun ListRow(
    shape: Shape,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: (() -> Unit)? = null,
) {
    val base = modifier
        .fillMaxWidth()
        .heightIn(min = if (subtitle != null) M3Dimens.rowTwoLineHeight else M3Dimens.rowMinHeight)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
    val rowModifier = if (onClick != null) base.clickable(enabled = enabled, role = role, onClick = onClick) else base
    Row(
        modifier = rowModifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.38f),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** A plain 24 dp leading icon for [ListRow], tinted (defaults to onSurfaceVariant). */
@Composable
fun RowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
}

/** Material 3 switch with the check-mark thumb Google's apps use. */
@Composable
fun M3Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = if (checked) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else null,
    )
}

/** A [ListRow] whose whole row toggles a switch (TalkBack reads it as one switch). */
@Composable
fun SwitchRow(
    shape: Shape,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    ListRow(
        shape = shape,
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        leading = leading,
        enabled = enabled,
        role = Role.Switch,
        onClick = { onCheckedChange(!checked) },
        trailing = { M3Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** A connected single-choice segmented control ("W | M | 6M | Y", "D | W | M | 6M"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentDescriptions: List<String>? = null,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                modifier = if (contentDescriptions != null) {
                    Modifier.semantics { this.contentDescription = contentDescriptions[i] }
                } else Modifier,
            ) { FitLabel(label, reserve = if (i == selectedIndex) SegmentCheckWidth else 0.dp) }
        }
    }
}

/** The selected segment's check mark (18 dp) and the gap after it: the width its label gives up. */
private val SegmentCheckWidth = 26.dp

/**
 * A one-line label that steps its size down until it fits its slot less [reserve]. A segment has a fixed
 * share of the row, so a long word at a large font scale ("Comparisons") was cut off; it now stays whole,
 * as large as the segment allows, never under half the reader's size. Plain layout only: a segmented
 * button asks its label for intrinsic sizes, which a subcomposing layout cannot answer.
 */
@Composable
private fun FitLabel(text: String, reserve: Dp = 0.dp) {
    val style = LocalTextStyle.current
    var scale by remember(text, style.fontSize, reserve) { mutableFloatStateOf(1f) }
    var settled by remember(text, style.fontSize, reserve) { mutableStateOf(false) }
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = if (scale < 1f) style.copy(fontSize = style.fontSize * scale) else style,
        onTextLayout = { result ->
            if ((result.hasVisualOverflow || result.isLineEllipsized(0)) && scale > 0.5f) scale -= 0.05f else settled = true
        },
        modifier = Modifier
            // The label is measured in the segment's width less the check mark's, and reports its own size.
            .layout { measurable, constraints ->
                val room = if (constraints.hasBoundedWidth) {
                    constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - reserve.roundToPx()).coerceAtLeast(0))
                } else constraints
                val placeable = measurable.measure(room)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            // Nothing is drawn while the size is still stepping down, so the label does not flicker.
            .drawWithContent { if (settled) drawContent() },
    )
}

/**
 * Runs [content] with the reader's font scale capped at [max]. Only for chrome whose items share a fixed
 * width (the navigation bar's four labels): the text still grows, to a point, and TalkBack reads the full
 * name. Content text is never capped; it reflows.
 */
@Composable
fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, max), content = content)
    }
}

/**
 * The search field of a list page (Browse, All Metrics): a pill with the search glyph and, once something
 * is typed, a clear button. A text field rather than a docked search bar, whose fixed 56 dp clips the text
 * at large font sizes; this one grows with it.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = clearLabel)
                }
            }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

/** The quiet sync line at the foot of the Summary: "Updated just now" / "Syncing…". */
@Composable
fun SyncFooter(text: String, syncing: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (syncing) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Sync, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The one notice of the redesign (iOS `NoticeCard`): icon, title, optional message, optional action
 * and dismiss. [emphasis] picks the container: error for failures, secondary for information.
 */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    error: Boolean = false,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(M3Dimens.cardRadius),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            if (action != null && onAction != null) {
                TextButton(onClick = onAction) { Text(action, color = content) }
            }
            if (trailing != null) trailing()
        }
    }
}

/**
 * Empty state (iOS ContentUnavailableView): a large icon, a title, an optional line and an optional
 * tonal button, centred.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.size(4.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}
