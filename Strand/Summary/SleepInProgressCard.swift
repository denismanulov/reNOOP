//  SleepInProgressCard.swift
//  NOOP · Summary home — the night still being counted: how long so far, and the button that ends it.
//
//  While the wearer sleeps every scoring pass stores the night as it stands, so its total grows and
//  would otherwise read exactly like a finished night. The rule is `SleepInProgress` (StrandAnalytics);
//  this file holds what the Summary loads for it, the one place that turns that into the card's state,
//  and the card.

import SwiftUI
import StrandAnalytics
import StrandDesign
import WhoopStore

/// What the Summary reads about the picked day's night, in one load: the night as the Sleep page decodes
/// it, and the newest heart-rate sample on record for the active strap (unix seconds, nil with none).
struct SummarySleepLoad {
    var night: Night?
    var newestDataTs: Int?
}

/// The night still being counted, as the Summary's in-progress card shows it.
struct SummarySleepInProgress: Equatable {
    /// Minutes asleep so far.
    let asleepMinutes: Double

    /// The card's state, or nil when the card does not show. The ONE place the in-progress decision and
    /// the figure on the card come from: both are read off the same loaded night, against the one
    /// clock passed in, so the card cannot say "in progress" about a different night than the one it
    /// gives the time for.
    ///
    /// Shown only for today, only when the night on screen is the one whose wake day is today, and only
    /// when it has time asleep. `pendingWakeTs` is the tapped "I'm awake" not yet applied
    /// (`WakeMarkStore`): the card goes away on the tap, before any sync proves it.
    static func resolve(_ load: SummarySleepLoad, isToday: Bool, selectedDayKey: String, nowTs: Int,
                        pendingWakeTs: Int?) -> SummarySleepInProgress? {
        guard isToday, let night = load.night, night.stages.asleep > 0 else { return nil }
        let wakeTs = night.session.endTs
        guard Repository.localDayKey(Date(timeIntervalSince1970: TimeInterval(wakeTs))) == selectedDayKey else {
            return nil
        }
        guard SleepInProgress.isInProgress(bedTs: night.session.effectiveStartTs, lastAsleepTs: wakeTs,
                                           newestDataTs: load.newestDataTs, nowTs: nowTs,
                                           pendingWakeTs: pendingWakeTs) else { return nil }
        return SummarySleepInProgress(asleepMinutes: night.stages.asleep)
    }
}

/// The card under the scores. The button does not set the wake time: it marks the wearer awake, which
/// starts a sync, and the detector finds the moment in the strap's data. The tap survives only as an
/// upper bound on the night (`WakeMarkTrim`).
struct SleepInProgressCard: View {
    let state: SummarySleepInProgress
    let onAwake: () -> Void

    var body: some View {
        SummaryCard {
            VStack(alignment: .leading, spacing: 10) {
                SummaryCardTitleRow(icon: "bed.double.fill", title: String(localized: "Sleep still in progress"),
                                    tint: StrandPalette.healthSleepDeep, chevron: false)
                Text("Tap when you are up. reNOOP syncs and finds when you woke.")
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .center, spacing: 14) {
                        figure
                        Spacer(minLength: 8)
                        awakeButton
                    }
                    VStack(alignment: .leading, spacing: 10) {
                        figure
                        awakeButton
                    }
                }
            }
        }
    }

    private var figure: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Time Asleep")
                .font(StrandFont.footnote.weight(.semibold))
                .foregroundStyle(StrandPalette.textSecondary)
            SleepCardValueText(value: .duration(state.asleepMinutes), size: 24)
        }
        .accessibilityElement(children: .combine)
    }

    private var awakeButton: some View {
        Button(action: onAwake) {
            Text("I'm awake")
                .font(StrandFont.headline)
        }
        .sleepAwakeButton()
    }
}

private extension View {
    /// The card's one action: iOS 26's prominent Liquid Glass capsule in the Sleep hue, a bordered
    /// prominent capsule before it, and the Mac's native bezel (`.capsule` there is macOS 14).
    @ViewBuilder func sleepAwakeButton() -> some View {
        #if compiler(>=6.2) && os(iOS)
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glassProminent).tint(StrandPalette.healthSleepDeep)
        } else {
            self.buttonStyle(.borderedProminent).buttonBorderShape(.capsule).tint(StrandPalette.healthSleepDeep)
        }
        #elseif os(iOS)
        self.buttonStyle(.borderedProminent).buttonBorderShape(.capsule).tint(StrandPalette.healthSleepDeep)
        #else
        self.buttonStyle(.borderedProminent).tint(StrandPalette.healthSleepDeep)
        #endif
    }
}
