package com.noop.ui

import com.noop.R
import androidx.annotation.StringRes

/**
 * #1617: which empty-state copy a vital with fewer than two readings should show.
 *
 * The default ("not enough history yet") is honest for any vital NOOP can actually chart from this
 * strap - keep wearing it and readings accumulate. For Blood Oxygen that depends on a switch:
 *
 *  - **A WHOOP strap with the estimate off.** Both generations bank a strap-computed percentage: the
 *    5/MG at `@82` of a v18 record, the 4.0 at `@86` of a 104-byte v24 record (`aux_byte_86`). Either is
 *    an unverified candidate that ships default-off, so the screen stays empty until the user turns it
 *    on. Naming the switch beats implying more nights.
 *  - **A WHOOP strap with it on.** Genuinely just needs nights, so the default copy is right.
 *
 * The 4.0 used to be told that waiting could never help. That was drawn from the `spo2RedOff = 68` /
 * `spo2IrOff = 70` fields, and it still holds for THEM: across three captures `ir` is `red` plus a
 * constant, so a ratio-of-ratios over the pair carries no oxygenation information, and on a fourth
 * strap `red` tracked the skin-temperature ADC at r = 0.99 through one night. What changed is that the
 * percentage was never in those fields - it is in the result byte further down the same record.
 *
 * [family] must come from the REGISTRY (`DeviceFamily.forRegistryDevice`), never a live-connection
 * flag: such a flag reads false for a 4.0, for an Oura ring and for nothing-connected alike, and an
 * Oura DOES produce SpO2 (`nightlySpo2CeilingMean`'s 0x6F ceiling@100). A null family - a positively
 * non-WHOOP brand, or a row not yet loaded - therefore falls through to the neutral copy rather than
 * claiming a generation that has not been established (#1086/#171).
 *
 * Pure and Compose-free so the decision is unit-tested without a device or a strap. Android-only:
 * iOS's metric detail already points at import rather than promising accumulation.
 */
internal data class VitalEmptyState(@StringRes val titleRes: Int, @StringRes val bodyRes: Int)

internal fun spo2EmptyState(
    key: String,
    family: com.noop.protocol.DeviceFamily?,
    candidateDisplayOn: Boolean,
): VitalEmptyState {
    val default = VitalEmptyState(
        R.string.l10n_health_screen_not_enough_history_yet_0e2f93b6,
        R.string.l10n_health_screen_this_vital_needs_at_least_two_0e41b8b0,
    )
    if (key != "spo2") return default
    return when {
        family != null && !candidateDisplayOn -> VitalEmptyState(
            R.string.l10n_health_screen_the_blood_oxygen_estimate_is_turned_4c403ab2,
            R.string.l10n_health_screen_your_strap_reports_a_blood_oxygen_349fe34a,
        )
        else -> default
    }
}
