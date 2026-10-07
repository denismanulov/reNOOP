//  DeviceReadsView.swift
//  NOOP · Devices — "What NOOP Reads": a row per metric and whether it comes live off THIS strap (a
//  WHOOP 4.0 or a 5.0/MG), is an on-device estimate, or isn't available. Marks mirror the decoder and
//  analytics truth (Interpreter / AnalyticsEngine / HistoricalStreams); the marks carry the meaning.

import SwiftUI
import StrandDesign
import WhoopProtocol

struct DeviceReadsView: View {
    /// The strap generation, when the registry knows it; nil (the legacy "WHOOP" row) shows both.
    let family: DeviceFamily?
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .footnote) private var scaledColumn: CGFloat = 56


    /// Tri-state support for a metric on a given strap — honest, never overstated.
    private enum LimitState {
        case full, partial, none

        /// SF Symbol glyph shown in the strap column.
        var glyph: String {
            switch self {
            case .full:    return "checkmark"
            case .partial: return "minus"
            case .none:    return "xmark"
            }
        }

        var tint: Color {
            switch self {
            case .full:    return StrandPalette.settingsGreen
            case .partial: return StrandPalette.settingsOrange
            case .none:    return StrandPalette.settingsGray
            }
        }

        /// Spoken label for the row's accessibility description.
        var spoken: String {
            switch self {
            case .full:    return String(localized: "Yes")
            case .partial: return String(localized: "partly")
            case .none:    return String(localized: "No")
            }
        }
    }

    /// One row: a metric, and how it reads on a 4.0 vs a 5.0/MG.
    private struct LimitRow: Identifiable {
        let feature: LocalizedStringKey
        let spokenFeature: String
        let whoop4: LimitState
        let whoop5: LimitState
        var id: String { spokenFeature }
    }

    private let rows: [LimitRow] = [
        LimitRow(feature: "Live heart rate", spokenFeature: String(localized: "Live heart rate"), whoop4: .full, whoop5: .full),
        LimitRow(feature: "HRV (rMSSD)", spokenFeature: String(localized: "HRV"), whoop4: .full, whoop5: .full),
        LimitRow(feature: "Sleep staging", spokenFeature: String(localized: "Sleep staging"), whoop4: .full, whoop5: .full),
        LimitRow(feature: "Recovery & strain", spokenFeature: String(localized: "Recovery & strain"), whoop4: .full, whoop5: .full),
        // `.partial` on BOTH generations: the displayed respiratory rate is always
        // `SleepStager.respRateFromRR` — an on-device RSA estimate off the R-R stream, which is what
        // `.partial` means — computed with NO family branch (`AnalyticsEngine`'s `respRateDaily`). The
        // 5.0/MG v18 wire carries no respiratory channel at all (`Whoop5HistoricalTests…` pins
        // `resp_rate_raw` nil); the 4.0 v24 layout DOES carry `resp_rate_raw`, but it is a raw ADC stored
        // unconverted (schema: "resp rate computed server-side", `HistoricalStreams` keeps it as a raw
        // `RespSample`) and never becomes the shown value. Neither is "read live off the strap" (`.full`)
        // — which is also why an over-counted-R-R 4.0 night (#1331) blanks it.
        LimitRow(feature: "Respiratory rate", spokenFeature: String(localized: "Respiratory rate"), whoop4: .partial, whoop5: .partial),
        LimitRow(feature: "Stress (on-device)", spokenFeature: String(localized: "Stress"), whoop4: .full, whoop5: .full),
        LimitRow(feature: "Workout detection", spokenFeature: String(localized: "Workout detection"), whoop4: .full, whoop5: .full),
        LimitRow(feature: "Skin temperature", spokenFeature: String(localized: "Skin temperature"), whoop4: .partial, whoop5: .full),
        // `.full` on a 4.0 as well: its 104-byte v24 record carries the firmware's own cumulative step
        // counter (`step_counter@92`), counted the same way as the 5/MG counter. The motion-volume
        // estimate only fills days that have no counter rows.
        LimitRow(feature: "Steps", spokenFeature: String(localized: "Steps"), whoop4: .full, whoop5: .full),
        LimitRow(feature: "Blood oxygen (SpO₂ %)", spokenFeature: String(localized: "Blood oxygen"), whoop4: .none, whoop5: .none),
        LimitRow(feature: "ECG", spokenFeature: String(localized: "ECG"), whoop4: .none, whoop5: .partial),
        LimitRow(feature: "Blood pressure", spokenFeature: String(localized: "Blood pressure"), whoop4: .none, whoop5: .none),
    ]

    var body: some View {
        Form {
            Section {
                if family == nil {
                    HStack {
                        Spacer()
                        Text(verbatim: "4.0").frame(width: column)
                        Text(verbatim: "5.0/MG").frame(width: column)
                    }
                    .font(StrandFont.pro(13))
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .accessibilityHidden(true)
                }
                ForEach(rows) { row in
                    let layout = dts.isAccessibilitySize
                        ? AnyLayout(VStackLayout(alignment: .trailing, spacing: 4)) : AnyLayout(HStackLayout())
                    layout {
                        Text(row.feature).foregroundStyle(StrandPalette.textPrimary)
                            .frame(maxWidth: dts.isAccessibilitySize ? .infinity : nil, alignment: .leading)
                        if !dts.isAccessibilitySize { Spacer() }
                        HStack {
                            switch family {
                            case .whoop4?: supportCell(row.whoop4)
                            case .whoop5?: supportCell(row.whoop5)
                            case nil: supportCell(row.whoop4); supportCell(row.whoop5)
                            }
                        }
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(a11yLabel(row))
                }
            }
        }
        .settingsPage("What reNOOP Reads")
    }

    /// Width of each strap column, shared by the header and the marks so they line up. Its text is capped
    /// at xxxLarge, so the column stops growing there too.
    private var column: CGFloat { min(scaledColumn, 84) }

    private func supportCell(_ state: LimitState) -> some View {
        Image(systemName: state.glyph)
            .fontWeight(.semibold)
            .foregroundStyle(state.tint)
            .frame(width: family == nil ? column : nil)
            .accessibilityHidden(true)
    }

    private func a11yLabel(_ row: LimitRow) -> String {
        switch family {
        case .whoop4?: return "\(row.spokenFeature): \(row.whoop4.spoken)"
        case .whoop5?: return "\(row.spokenFeature): \(row.whoop5.spoken)"
        case nil: return "\(row.spokenFeature): WHOOP 4.0 \(row.whoop4.spoken), 5.0/MG \(row.whoop5.spoken)"
        }
    }
}
