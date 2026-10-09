package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.OtherStrapAppWarning
import com.noop.ble.OtherStrapApps
import com.noop.ui.m3.NoticeDialog

/**
 * The root warning that another app is pulling the same strap's history ([OtherStrapAppWarning], raised
 * by the BLE client on [com.noop.ble.ForeignOffloadDetector]'s evidence). Twin of the alert on the iOS root
 * view: OK puts it away for this process, "Don't Show Again" for good.
 *
 * The sentence says what was concluded, another app pulling the history. The known strap apps that are
 * installed here and may use Bluetooth are listed under it rather than named as the one doing it: which
 * app pulled is not something the link tells us, and with two listed it need not be both.
 */
@Composable
internal fun OtherAppSyncingDialog() {
    val warning = OtherStrapAppWarning.shared
    val presented by warning.presented.collectAsStateWithLifecycle()
    if (!presented) return

    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    // Read when the warning comes up; empty when the syncing app is not one reNOOP knows.
    val others = remember { OtherStrapApps.ableToSync(context) }
    val advice = stringResource(
        if (OtherStrapApps.nearbyDevicesRevocable()) R.string.other_app_syncing_message
        else R.string.other_app_syncing_message_uninstall,
    )
    val names = OtherStrapApps.phrase(others, locale)
    NoticeDialog(
        title = stringResource(R.string.other_app_syncing_title),
        message = advice,
        detail = names?.let { stringResource(R.string.other_app_syncing_installed, it) },
        confirmLabel = stringResource(R.string.summary_ok),
        onConfirm = warning::dismiss,
        secondaryLabel = stringResource(R.string.other_app_dont_show_again),
        onSecondary = {
            NoopPrefs.of(context).edit().putBoolean(NoopPrefs.KEY_OTHER_APP_WARNING_MUTED, true).apply()
            warning.dismiss()
        },
        onDismiss = warning::dismiss,
    )
}
