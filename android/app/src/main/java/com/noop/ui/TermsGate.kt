package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow

/**
 * Current Terms of Use version. Bump on a MATERIAL change (risk / liability / medical / affiliation
 * wording) to re-prompt every user for a fresh acknowledgment; leave it for typo fixes. Mirrors macOS
 * `Terms.currentVersion`. The full text ships in TERMS.md.
 */
object Terms {
    const val CURRENT_VERSION = "2.0"

    /**
     * Plain-English summary of TERMS.md §1–§6 — kept identical to the macOS `Terms.points`. Each is
     * a (headline, body) pair of string-resource ids so the gate is localized like the rest of the
     * app (PR #984); the English source wording lives in values/strings.xml, byte-identical to what
     * used to be hardcoded here. The binding text stays TERMS.md — a translation is a courtesy, not
     * the agreement.
     */
    val points: List<Pair<Int, Int>> = listOf(
        R.string.terms_point_independent_head to R.string.terms_point_independent_body,
        R.string.terms_point_tos_head to R.string.terms_point_tos_body,
        R.string.terms_point_experimental_head to R.string.terms_point_experimental_body,
        R.string.terms_point_medical_head to R.string.terms_point_medical_body,
        R.string.terms_point_warranty_head to R.string.terms_point_warranty_body,
    )

    /**
     * The affirmative attestations the user must EACH tick before Accept enables (clickwrap). Kept as
     * separate, conspicuous consents rather than one blanket box so each is a distinct, knowing
     * acknowledgment — the load-bearing ones being the non-affiliation attestation and the liability
     * waiver. Mirrors macOS `Terms.attestations`; the English source lives in values/strings.xml.
     * NOTE: the exact legal phrasing should be reviewed by a solicitor before this ships publicly.
     */
    val attestations: List<Int> = listOf(
        R.string.terms_attest_not_affiliated,
        R.string.terms_attest_own_device,
        R.string.terms_attest_asis,
        R.string.terms_attest_liability,
    )
}

/**
 * First-run acknowledgment gate (clickwrap), shown over everything — before onboarding, pairing, or any
 * Bluetooth access — until [Terms.CURRENT_VERSION] is accepted, and again if the terms materially change.
 * Laid out as the setup page the onboarding opens with (twin of Swift `TermsGateView`): the points, one row
 * per statement with a tick the user sets (none pre-set), "Terms of Use", and Accept, enabled only once
 * every statement is ticked. Acceptance is persisted by the caller. Rendered alone (the host returns before
 * anything else), so TalkBack never reaches content behind it (CR-7).
 */
@Composable
fun TermsGateScreen(onAccept: () -> Unit) {
    // One flag per Terms.attestations entry; every one must be ticked before Accept enables.
    val checks = remember { mutableStateListOf(*Array(Terms.attestations.size) { false }) }
    val allChecked = checks.all { it }
    var showTerms by remember { mutableStateOf(false) }

    SetupPage(
        step = null,
        title = stringResource(R.string.terms_title),
        message = stringResource(R.string.terms_subtitle),
        glyph = { SetupGlyph(Icons.Filled.Description) },
        primary = SetupAction(stringResource(R.string.terms_accept_short), enabled = allChecked, onClick = onAccept),
    ) {
        TermsPoints()
        // Each statement is its own row with a trailing tick, as setup lists a choice.
        ListGroup {
            Terms.attestations.forEachIndexed { idx, resId ->
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(resId),
                        modifier = Modifier.semantics { selected = checks[idx] },
                        role = Role.Checkbox,
                        trailing = {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                // Shown only once ticked, as setup marks a choice; the row keeps its width.
                                modifier = Modifier.size(24.dp).alpha(if (checks[idx]) 1f else 0f),
                            )
                        },
                        onClick = { checks[idx] = !checks[idx] },
                    )
                }
            }
        }
        ListGroup {
            item { shape ->
                ListRow(
                    shape = shape,
                    title = stringResource(R.string.terms_of_use),
                    trailing = { ChevronRight() },
                    onClick = { showTerms = true },
                )
            }
        }
    }

    if (showTerms) {
        AlertDialog(
            onDismissRequest = { showTerms = false },
            title = { Text(stringResource(R.string.terms_of_use)) },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TermsPoints()
                    Text(
                        stringResource(R.string.terms_footer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showTerms = false }) { Text(stringResource(R.string.onboarding_done)) } },
        )
    }
}

/** The plain-English points of [Terms], headline over body. */
@Composable
private fun TermsPoints() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Terms.points.forEach { (head, body) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(head), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
