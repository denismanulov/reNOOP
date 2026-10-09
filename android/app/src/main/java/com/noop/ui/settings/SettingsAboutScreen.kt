package com.noop.ui.settings

import com.noop.ui.workouts.FullScreenDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.noop.BuildConfig
import com.noop.R
import com.noop.ui.HowNoopWorksScreen
import com.noop.ui.NoopPrefs
import com.noop.ui.WhatsNewSheet
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.SwitchRow
import com.noop.update.UpdateCheck
import com.noop.update.UpdateWatch
import kotlinx.coroutines.launch

// MARK: - About reNOOP (twin of iOS AboutSettingsPage)
//
// Laid out like Settings > About phone: the version (seven taps unlock Developer, as the build number
// does on Android), the help sheets and the project link, Updates (a user-initiated GitHub release check
// plus the daily automatic one), and the upstream credits reNOOP is built on.

private const val PROJECT_URL = "https://github.com/ryanbr/noop"

@Composable
internal fun SettingsAboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var versionTaps by remember { mutableIntStateOf(0) }
    var showWhatsNew by remember { mutableStateOf(false) }
    var showHowItWorks by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheck.Result?>(null) }
    var autoCheck by remember { mutableStateOf(UpdateWatch.isEnabled(context)) }
    val developerOn = stringResource(R.string.settings_developer_on)

    SettingsPage(title = stringResource(R.string.settings_about_renoop), onBack = onBack) {
        item {
            ListGroup {
                item { shape ->
                    ValueRow(shape, stringResource(R.string.settings_version), BuildConfig.VERSION_NAME) {
                        val tap = DeveloperUnlock.tap(NoopPrefs.of(context), versionTaps)
                        versionTaps = tap.count
                        if (tap.unlockedNow) confirm(context, developerOn)
                    }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ListRow(shape = shape, title = stringResource(R.string.l10n_settings_screen_what_s_new_4d8dc5fe), onClick = { showWhatsNew = true })
                }
                item { shape ->
                    ListRow(shape = shape, title = stringResource(R.string.l10n_settings_screen_how_noop_works_3396b27a), onClick = { showHowItWorks = true })
                }
                item { shape ->
                    ListRow(shape = shape, title = "GitHub", onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))) }
                    })
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_updates)) {
                item { shape ->
                    val status = when (val r = result) {
                        null -> null
                        is UpdateCheck.Result.UpToDate -> stringResource(R.string.settings_up_to_date)
                        is UpdateCheck.Result.Available -> null
                        UpdateCheck.Result.Failed -> stringResource(R.string.l10n_settings_screen_couldn_t_check_try_again_b3c885d9)
                    }
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_settings_screen_check_for_updates_736b9062),
                        subtitle = if (checking) stringResource(R.string.l10n_settings_screen_checking_820d6004) else status,
                        titleColor = MaterialTheme.colorScheme.primary,
                        enabled = !checking,
                        trailing = if (checking) { { BusyTrailing() } } else null,
                        onClick = {
                            if (!checking) {
                                checking = true
                                result = null
                                scope.launch {
                                    result = UpdateCheck.check(BuildConfig.VERSION_NAME)
                                    checking = false
                                }
                            }
                        },
                    )
                }
                (result as? UpdateCheck.Result.Available)?.let { available ->
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.l10n_settings_screen_version_avail_version_is_available_5b401bd4, available.version),
                            titleColor = MaterialTheme.colorScheme.primary,
                            trailing = { Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(available.url))) }
                            },
                        )
                    }
                }
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_settings_screen_check_automatically_7cd229d2),
                        checked = autoCheck,
                        onCheckedChange = {
                            autoCheck = it
                            UpdateWatch.setEnabled(context, it)
                        },
                    )
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_built_on)) {
                item { shape ->
                    ListRow(shape = shape, title = "johnmiddleton12/my-whoop", subtitle = stringResource(R.string.settings_whoop4_protocol))
                }
                item { shape ->
                    ListRow(shape = shape, title = "b-nnett/goose", subtitle = stringResource(R.string.settings_whoop5_protocol))
                }
            }
        }
    }

    if (showWhatsNew) {
        FullScreenSheet(onDismiss = { showWhatsNew = false }) { WhatsNewSheet(onClose = { showWhatsNew = false }) }
    }
    if (showHowItWorks) {
        FullScreenSheet(onDismiss = { showHowItWorks = false }) { HowNoopWorksScreen(onClose = { showHowItWorks = false }) }
    }
}

/**
 * One of the app's explainer sheets, full screen over the page (they carry their own close button): the
 * same edge-to-edge window as every other full-screen dialog.
 */
@Composable
internal fun FullScreenSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    FullScreenDialog(onDismiss = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() }
    }
}
