package com.noop.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.ui.ProfileAvatarStore
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.TonalIcon
import com.noop.ui.m3.TonalPair

// MARK: - Shared pieces of the Settings tree
//
// Every Settings page is the same frame Pixel Settings uses: a large collapsing top app bar with the back
// arrow, then segmented list groups. Rows lead with a tonal icon circle on the root and with nothing on
// the sub-pages, and carry their current value as the supporting line, as Android writes a preference's
// summary under its title.

/** A Settings page: large collapsing top bar, then a lazy list of groups. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = title, onBack = onBack, large = true, scrollBehavior = scrollBehavior)
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding,
                end = M3Dimens.screenPadding,
                top = 8.dp,
                bottom = M3Dimens.bottomBarClearance + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
            content = content,
        )
    }
}

/** A root row: tonal icon circle, title, optional summary line, optional trailing slot. */
@Composable
internal fun IconRow(
    shape: androidx.compose.ui.graphics.Shape,
    icon: ImageVector,
    pair: TonalPair,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    ListRow(
        shape = shape,
        title = title,
        subtitle = subtitle,
        leading = { TonalIcon(icon, pair) },
        trailing = trailing,
        onClick = onClick,
    )
}

/** A sub-page row that shows its value as the summary line and opens something on tap. */
@Composable
internal fun ValueRow(
    shape: androidx.compose.ui.graphics.Shape,
    title: String,
    value: String?,
    enabled: Boolean = true,
    chevron: Boolean = false,
    onClick: (() -> Unit)?,
) {
    ListRow(
        shape = shape,
        title = title,
        subtitle = value,
        enabled = enabled,
        trailing = if (chevron) { { ChevronRight() } } else null,
        onClick = onClick,
    )
}

/** A row whose action is under way: its title, then a small spinner where a value would sit. */
@Composable
internal fun BusyTrailing() {
    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
}

/**
 * The profile circle of the Settings header and Health Details: the photo, else the name's initials, else
 * a person glyph, on the tertiary container (the mockup's monogram).
 */
@Composable
internal fun ProfileMonogram(initials: String, size: Dp, modifier: Modifier = Modifier, textStyle: TextStyle? = null) {
    val bitmap = ProfileAvatarStore.bitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
        return
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (initials.isNotEmpty()) {
            Text(
                initials,
                style = textStyle ?: MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        } else {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

/** A short confirmation, the Android twin of iOS's `Confirmation.shared.show` capsule. */
internal fun confirm(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
