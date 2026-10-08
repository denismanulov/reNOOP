//  Spo2EmptyState.swift
//  NOOP · Metric page — which note an empty Blood Oxygen page carries.

import Foundation
import WhoopProtocol

/// What an empty Blood Oxygen page says beside its "No Data" card.
///
/// Both WHOOP generations bank a strap-computed percentage: the 5/MG at `@82` of a v18 record, the 4.0
/// at `@86` of a 104-byte v24 record (`aux_byte_86`). Either is an unverified candidate that ships
/// default-off, so with the switch off the page stays empty however long the strap is worn, and the
/// only honest thing to say is where the switch is. With it on the page just needs nights.
///
/// `family` must come from the REGISTRY (`DeviceFamily.forRegistryDevice`), never a live-connection
/// flag. A nil family is a positively non-WHOOP brand or a row not in the registry: it has no
/// strap-estimate switch to be pointed at, whatever the stored toggle says (#1086/#171).
///
/// Pure, so the decision is tested without a device. Twin of Kotlin `spo2EmptyState` (`VitalGates.kt`).
enum Spo2EmptyState: Equatable {
    /// Nothing to add to the page's own empty state.
    case standard
    /// The strap has an estimate and the switch that shows it is off.
    case estimateOff

    static func resolve(key: String, family: DeviceFamily?, candidateDisplayOn: Bool) -> Spo2EmptyState {
        guard key == "spo2", family != nil, !candidateDisplayOn else { return .standard }
        return .estimateOff
    }
}
