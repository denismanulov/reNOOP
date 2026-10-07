//  SettingsFeaturePages.swift
//  NOOP · Settings → Workouts, Scores.

import SwiftUI
import StrandDesign
import StrandAnalytics

// MARK: - Workouts

struct WorkoutsSettingsPage: View {
    /// Opt-in: after a sync, offer to save a sustained raised-HR stretch as a workout. Never automatic.
    @AppStorage(PuffinExperiment.autoDetectWorkoutsKey) private var autoDetectWorkoutsEnabled = false
    /// #703: the live-workout view holds the screen awake while recording. Shared with Android verbatim.
    @AppStorage("workoutKeepScreenOn") private var workoutKeepScreenOn = false
    /// Live-HR Live Activity (Lock Screen + Dynamic Island), iOS only (#336).
    @AppStorage(UnitPrefs.liveActivityKey) private var liveActivityEnabled = true
    /// The Lift Log session's own Live Activity switch, separate from the heart-rate one.
    @AppStorage(UnitPrefs.liftLiveActivityKey) private var liftLiveActivityEnabled = true

    var body: some View {
        Form {
            Section {
                Toggle("Auto-detect workouts", isOn: $autoDetectWorkoutsEnabled)
            }
            Section {
                Toggle("Keep screen on", isOn: $workoutKeepScreenOn)
                #if os(iOS)
                Toggle("Heart rate in Dynamic Island", isOn: $liveActivityEnabled)
                Toggle("Gym session in Dynamic Island", isOn: $liftLiveActivityEnabled)
                #endif
            }
        }
        .settingsPage("Workouts")
    }
}

// MARK: - Scores

struct ScoresSettingsPage: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var profile: ProfileStore

    /// #268: show Effort on NOOP's 0–100 axis or WHOOP's 0–21. Display-only.
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    /// #1545 opt-in: Banister's exponential TRIMP instead of Edwards' zones. Re-scores the window.
    @AppStorage(PuffinExperiment.banisterEffortKey) private var banisterEffortEnabled = false

    /// #141: whole night or deep sleep only. Changes the number, so a switch re-scores.
    @AppStorage(UnitPrefs.hrvWindowKey) private var hrvWindowRaw = HrvWindow.whole.rawValue

    /// Experimental, default off: learn the WHOOP 4.0 ticks-per-step divisor from short raw-accelerometer
    /// measurements instead of using the manual one. Changes each day's step total, so a switch re-scores.
    @AppStorage(StepCalibrationStore.enabledKey) private var stepAutoCalibrationEnabled = false

    @State private var showScoringGuide = false
    @State private var showRecalibrateConfirm = false
    @State private var showStepsCalibration = false

    var body: some View {
        Form {
            Section {
                Picker("Effort scale", selection: $effortScaleRaw) {
                    Text("0-100").tag(EffortScale.hundred.rawValue)
                    Text("0-21").tag(EffortScale.whoop.rawValue)
                }
                .settingsPicker()
                Toggle("Exponential scale", isOn: $banisterEffortEnabled)
                    .onChangeCompat(of: banisterEffortEnabled) { _ in
                        // The recipe changes stored Effort for every day in the window: re-score now.
                        Task { await model.intelligence.analyzeRecent(); await model.repo.refresh() }
                    }
            } header: {
                Text("Effort")
            }

            Section {
                // #139/#132: daily steps = counter ticks ÷ this divisor (5/MG @57, WHOOP 4.0 @92).
                // Variable increment, shown to two places because the grid is 0.01 below 1.5.
                LabeledContent("Step calibration") {
                    Stepper {
                        Text(String(format: "%.2f", profile.stepTicksPerStep))
                            .monospacedDigit()
                    } onIncrement: {
                        profile.stepTicksPerStep = ProfileStore.steppedStepScale(profile.stepTicksPerStep, up: true)
                    } onDecrement: {
                        profile.stepTicksPerStep = ProfileStore.steppedStepScale(profile.stepTicksPerStep, up: false)
                    }
                    .fixedSize()
                    .accessibilityLabel("Step calibration, \(String(format: "%.2f", profile.stepTicksPerStep)) counter ticks per step")
                }
                Toggle("Auto step calibration (WHOOP 4.0)", isOn: $stepAutoCalibrationEnabled)
                    .onChangeCompat(of: stepAutoCalibrationEnabled) { _ in
                        Task { await model.intelligence.analyzeRecent(); await model.repo.refresh() }
                    }
                if stepAutoCalibrationEnabled {
                    LabeledContent("Learned divisor today", value: stepAutoCalibrationSummary)
                }
                // WHOOP 4.0 steps ESTIMATE (a separate thing from the 5/MG counter divisor above). Opens a
                // sheet, which a row marks with its value alone; a chevron promises a push (ST-10).
                Button {
                    showStepsCalibration = true
                } label: {
                    LabeledContent("Steps estimate", value: stepsCalibrationSummary)
                        .foregroundStyle(StrandPalette.textPrimary)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } header: {
                Text("Steps")
            }

            Section {
                Picker("HRV window", selection: $hrvWindowRaw) {
                    Text("Night").tag(HrvWindow.whole.rawValue)
                    Text("Deep sleep").tag(HrvWindow.deep.rawValue)
                }
                .settingsPicker()
                .onChangeCompat(of: hrvWindowRaw) { _ in
                    // #201/#195: analyzeRecent re-scores and re-folds the baseline in one pass.
                    Task { await model.intelligence.analyzeRecent(); await model.repo.refresh() }
                }
                Button("Reset baseline") { showRecalibrateConfirm = true }
            } header: {
                Text("Charge")
            }

            Section {
                Button("How scores work") { showScoringGuide = true }
                    .foregroundStyle(StrandPalette.textPrimary)
            }
        }
        .settingsPage("Scores")
        .sheet(isPresented: $showScoringGuide) { ScoringGuideView(onClose: { showScoringGuide = false }) }
        // ST-6: one name for the action — the row, the question and its button all say reset.
        .confirmationDialog("Reset your Charge baseline?",
                            isPresented: $showRecalibrateConfirm, titleVisibility: .visible) {
            Button("Reset baseline", role: .destructive) { recalibrate() }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("It rebuilds over about 4 nights. History stays.")
        }
        .sheet(isPresented: $showStepsCalibration) {
            StepsCalibrationSheet(repo: model.repo, onClose: { showStepsCalibration = false })
                .environmentObject(profile)
        }
    }

    /// Manual, the auto-fit confidence, or not yet calibrated.
    /// Today's learned divisor and how many measurements stand behind the calibration so far.
    private var stepAutoCalibrationSummary: String {
        let tz = TimeZone.current.secondsFromGMT()
        let today = AnalyticsEngine.dayString(Int(Date().timeIntervalSince1970), offsetSec: tz)
        let snapshot = StepCalibrationStore.snapshot(manual: profile.stepTicksPerStep, today: today)
        let accepted = snapshot.state?.accepted ?? 0
        return String(format: "%.2f", snapshot.factor(day: today)) + " (\(accepted))"
    }

    private var stepsCalibrationSummary: String {
        if profile.stepsManualCoefficient > 0 { return String(localized: "Manual") }
        if profile.stepsCalibrationCoefficient > 0 {
            return String(localized: "Auto · \(StepsCalibrationFormat.confidenceLabel(profile.stepsCalibrationConfidence)) confidence")
        }
        return String(localized: "Not calibrated")
    }

    /// Re-anchors every baseline that feeds Charge from now (`Baselines.recalibrateRecoveryBaselines`),
    /// then re-scores and refreshes. No stored day is deleted.
    private func recalibrate() {
        Baselines.recalibrateRecoveryBaselines()
        Task {
            await model.intelligence.analyzeRecent()
            await model.repo.refresh()
        }
        Confirmation.shared.show(String(localized: "Baseline reset"))
    }
}
