package com.noop.ui.workouts

import com.noop.ui.m3.FullScreenDialogBackdropEffect
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.noop.R
import com.noop.analytics.WorkoutSport
import com.noop.ui.RecentSportsPrefs
import com.noop.ui.m3.Health
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.groupItemShape
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

// MARK: - Choosing an activity (twin of iOS WorkoutSportPicker.swift)
//
// A searchable list of the catalogue with the recently used ones first, as Fitness lists workout types. Used
// to start a live session ("Other Workout") and to set the type of a workout added by hand. The search
// matches the English catalogue name and the translated one; for a typed workout a search with no match can
// be used as is (#519), while a live start keeps to the catalogue (the recording holds a catalogue sport).

/**
 * A Material full-screen dialog: the window fills the space between the system bars without a scrim, so the
 * app's own status and navigation bars stay as they are around it, and the content draws its own top bar.
 *
 * Compose sizes a dialog's window to its content and caps the content at the screen height, so the window
 * cannot reach under the gesture bar: the strip below it is the activity. While a dialog is up it counts
 * itself in the shell's nav-bar backdrop ([FullScreenDialogBackdropEffect]), and the app shell paints that
 * strip in the dialog's surface colour instead of the navigation bar's, which showed as a band along the
 * bottom of every such dialog.
 */
@Composable
internal fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    FullScreenDialogBackdropEffect()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        // The focused dialog window decides how the system bars' icons are drawn: dark on a light surface.
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            window?.let { w ->
                // No scrim: the dialog is the whole screen between the bars.
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) { content() }
    }
}

/** The activity list presented to begin a session: picking a row starts it (the caller closes the list). */
@Composable
internal fun StartWorkoutPicker(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    FullScreenDialog(onDismiss) {
        SportPickerContent(
            title = stringResource(R.string.picker_choose),
            selected = null,
            allowFreeText = false,
            closeIsBack = false,
            onClose = onDismiss,
            onPick = onStart,
        )
    }
}

/** The list itself: a top bar, the search field, Recent and All Workouts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SportPickerContent(
    title: String,
    selected: String?,
    allowFreeText: Boolean,
    closeIsBack: Boolean,
    onClose: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var query by rememberSaveable { mutableStateOf("") }
    val trimmed = query.trim()
    val res = context.resources
    val catalogue = remember(locale) {
        val collator = Collator.getInstance(locale)
        WorkoutSport.all.map { it.name }.sortedWith { a, b -> collator.compare(localizedSport(res, a), localizedSport(res, b)) }
    }
    val recent = remember {
        RecentSportsPrefs.recent(context).mapNotNull { r -> WorkoutSport.all.firstOrNull { it.name.equals(r, ignoreCase = true) }?.name }
    }
    val folded = fold(trimmed, locale)
    val matches = if (trimmed.isEmpty()) catalogue else catalogue.filter {
        fold(it, locale).contains(folded) || fold(localizedSport(res, it), locale).contains(folded)
    }
    val showUse = allowFreeText && trimmed.isNotEmpty() &&
        WorkoutSport.all.none { it.name.equals(trimmed, ignoreCase = true) || localizedSport(res, it.name).equals(trimmed, ignoreCase = true) }

    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                if (closeIsBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                } else {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.manual_close))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
    TextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = M3Dimens.screenPadding, vertical = 4.dp),
        placeholder = { Text(stringResource(R.string.picker_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 32.dp),
    ) {
        if (trimmed.isEmpty() && recent.isNotEmpty()) {
            item(key = "recent-h") { PickerHeader(stringResource(R.string.workouts_recent)) }
            items(recent.size, key = { "recent-$it" }) { i ->
                SportPickerRow(recent[i], i, recent.size, selected, onPick)
            }
        }
        if (trimmed.isEmpty()) {
            item(key = "all-h") { PickerHeader(stringResource(R.string.workouts_all)) }
        }
        val count = matches.size + if (showUse) 1 else 0
        items(matches.size, key = { "all-${matches[it]}" }) { i ->
            SportPickerRow(matches[i], i, count, selected, onPick)
        }
        if (showUse) {
            item(key = "use") {
                ListRow(
                    shape = groupItemShape(count - 1, count),
                    title = stringResource(R.string.picker_use, trimmed),
                    leading = { Icon(Icons.Filled.AddCircle, contentDescription = null, tint = Health.colors.fitness) },
                    onClick = { onPick(trimmed) },
                    modifier = Modifier.padding(top = if (count > 1) M3Dimens.groupGap else 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun SportPickerRow(name: String, index: Int, count: Int, selected: String?, onPick: (String) -> Unit) {
    val isSelected = selected != null && selected.equals(name, ignoreCase = true)
    ListRow(
        shape = groupItemShape(index, count),
        title = sportLabel(name),
        leading = { SportBadge(name, size = 36.dp, iconSize = 20.dp) },
        trailing = if (isSelected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = Health.colors.fitness) }
        } else null,
        onClick = { onPick(name) },
        modifier = Modifier.padding(top = if (index == 0) 0.dp else M3Dimens.groupGap),
    )
}

/** Case- and accent-insensitive form for matching ("Café" finds "cafe"). */
private fun fold(s: String, locale: Locale): String =
    Normalizer.normalize(s.lowercase(locale), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
