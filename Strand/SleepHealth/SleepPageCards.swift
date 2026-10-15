//  SleepPageCards.swift
//  NOOP · Sleep — the bedtime highlight card: the category, the sentence, the usual bedtime over last
//  night's, and the nights behind them, as Health sets it (iOS 26).
//
//  Presentation only. The highlight arrives resolved from the caller.

import SwiftUI
import StrandDesign

/// Health's bedtime highlight: the two figures one under the other, then a fortnight of nights as grey
/// bars with last night's in the Sleep hue and the usual bedtime drawn across in Health's bedtime teal.
struct SleepHighlightCard: View {
    let highlight: SleepHighlight

    private let tint = StrandPalette.sleepScoreTitle

    var body: some View {
        HighlightCard(icon: "bed.double.fill", title: String(localized: "Sleep"), tint: tint,
                      sentence: highlight.sentence, showsEvidence: highlight.detail != nil) {
            if case .bedtime(let usual, let last, _, _, _) = highlight.detail {
                HighlightLatestFigures(
                    averageTitle: String(localized: "sleep.hl.avgBedtime", defaultValue: "Average Bedtime"),
                    average: [.init(text: SleepMoreDataView.clock(minutesOfNight: usual), isUnit: false)],
                    latestTitle: String(localized: "Last Night's Bedtime"),
                    latest: [.init(text: SleepMoreDataView.clock(minutesOfNight: last), isUnit: false)],
                    tint: tint)
            }
        } chart: {
            if case .bedtime(let usual, _, let nights, let first, let last) = highlight.detail {
                // A later bedtime is a taller bar; an hour under the earliest night keeps the bars standing.
                HighlightLatestBars(values: nights, average: usual, first: date(first), last: date(last), tint: tint,
                                    minimumFloor: 60)
            }
        }
    }

    private func date(_ day: Date) -> String {
        day.formatted(.dateTime.day().month(.abbreviated).locale(AppLanguage.activeLocale))
    }
}
