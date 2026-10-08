import SwiftUI
import Foundation
import StrandDesign

/// Silent haptic HIIT interval timer.
///
/// Train hands-free: the strap buzzes every transition so you never have to look at the screen. Strong
/// triple-buzz at the start of each work block, a short single buzz into rest, a 3-2-1 tick on the last
/// seconds of every phase, and a long 5-loop buzz when the whole session finishes. With no strap bonded it
/// still works as a big glanceable visual timer, and the iPhone's own haptics mirror every cue.
///
/// The setup page is modelled on the Fitness app's custom workout: a start card, then the blocks. Starting
/// opens the same dark recording screen a workout uses (`IntervalRunView`).
struct IntervalTimerView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var live: LiveState
    /// Owned at the app root, like the gym session, so the timer keeps running — and shows under the tab bar
    /// (`NowRunningAccessory`) — after this page is left.
    @EnvironmentObject private var runner: IntervalTimerRunner
    #if os(iOS)
    @EnvironmentObject private var nowRunning: NowRunning
    #else
    @State private var showRun = false
    #endif

    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .title) private var timerGlyphSize: CGFloat = 30
    @ScaledMetric(relativeTo: .largeTitle) private var playSize: CGFloat = 50
    @ScaledMetric(relativeTo: .body) private var blockGlyphSize: CGFloat = 18
    @ScaledMetric(relativeTo: .body) private var blockGlyphWidth: CGFloat = 28
    @ScaledMetric(relativeTo: .largeTitle) private var stepSize: CGFloat = 44
    /// The block whose wheel is open, if any.
    @State private var editing: PhaseBlock?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                startCard
                blocks
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 32)
        }
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle(Text("Intervals"))
        .onAppear {
            // Strap buzz only when bonded, so the timer stays a pure visual tool otherwise.
            runner.buzz = { [weak model, weak live] loops in
                guard live?.bonded == true else { return }
                model?.buzz(loops: loops, gate: HapticPrefs.intervals)
            }
        }
        #if os(macOS)
        .sheet(isPresented: $showRun) { IntervalRunView(runner: runner) { showRun = false } }
        #endif
    }

    /// Fitness's start card: what will run, and the green play circle.
    private var startCard: some View {
        Button {
            if !runner.inProgress { runner.start() }
            #if os(iOS)
            nowRunning.expand(.intervals)
            #else
            showRun = true
            #endif
        } label: {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top) {
                    Image(systemName: "timer")
                        .font(.system(size: timerGlyphSize, weight: .semibold))
                        .foregroundStyle(StrandPalette.activityExerciseText)
                    Spacer()
                    Image(systemName: "play.fill")
                        .font(StrandFont.pro(20, weight: .bold))
                        .foregroundStyle(StrandPalette.fitnessOnAccent)
                        .frame(width: playSize, height: playSize)
                        .background(Circle().fill(StrandPalette.activityExerciseText))
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(runner.inProgress ? "Resume Intervals" : "Intervals")
                        .font(StrandFont.pro(22, weight: .bold))
                        .foregroundStyle(StrandPalette.textPrimary)
                    Text(verbatim: "\(runner.rounds) × \(IntervalTimerRunner.clock(runner.workSeconds)) / \(IntervalTimerRunner.clock(runner.restSeconds))")
                        .font(StrandFont.pro(17))
                        .foregroundStyle(StrandPalette.activityExerciseText)
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(StrandPalette.fitnessCard, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    /// The blocks, one card each: Work and Rest open Clock's countdown wheel in place, Rounds keeps − / +
    /// like Fitness's goal screen.
    private var blocks: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Workout")
                .font(StrandFont.pro(22, weight: .bold))
                .foregroundStyle(StrandPalette.textPrimary)
                .padding(.horizontal, 4)
            block("Work", symbol: "chevron.up.2", tint: StrandPalette.activityExerciseText,
                  value: IntervalTimerRunner.clock(runner.workSeconds),
                  minus: { runner.workSeconds = max(Self.minPhase, runner.workSeconds - 5) },
                  plus: { runner.workSeconds = min(Self.maxPhase, runner.workSeconds + 5) },
                  wheel: .work)
            // Its own key: "Rest" alone is the Sleep score's key, shown under another name.
            block("interval.rest", symbol: "chevron.down.2", tint: StrandPalette.activityStandText,
                  value: IntervalTimerRunner.clock(runner.restSeconds),
                  minus: { runner.restSeconds = max(Self.minPhase, runner.restSeconds - 5) },
                  plus: { runner.restSeconds = min(Self.maxPhase, runner.restSeconds + 5) },
                  wheel: .rest)
            block("Rounds", symbol: "repeat", tint: StrandPalette.textPrimary,
                  value: "\(runner.rounds)",
                  minus: { runner.rounds = max(1, runner.rounds - 1) },
                  plus: { runner.rounds = min(30, runner.rounds + 1) })
            Text("Total \(IntervalTimerRunner.clock(runner.totalPlanned))")
                .font(StrandFont.pro(15))
                .foregroundStyle(StrandPalette.textSecondary)
                .padding(.horizontal, 4)
        }
        .disabled(runner.running)
        .opacity(runner.running ? 0.5 : 1)
        .onChangeCompat(of: runner.running) { if $0 { editing = nil } }
    }

    /// A phase's shortest and longest length: 0:05, and the wheel's last stop, 59:55.
    private static let minPhase = 5
    private static let maxPhase = 59 * 60 + 55

    private func seconds(_ phase: PhaseBlock) -> Binding<Int> {
        switch phase {
        case .work: return $runner.workSeconds
        case .rest: return $runner.restSeconds
        }
    }

    private func block(_ title: LocalizedStringKey, symbol: String, tint: Color, value: String,
                       minus: @escaping () -> Void, plus: @escaping () -> Void,
                       wheel: PhaseBlock? = nil) -> some View {
        #if os(iOS)
        let usesWheel = wheel != nil
        #else
        let usesWheel = false
        #endif
        let layout = dts.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(spacing: 14))
        return VStack(spacing: 0) {
            layout {
                HStack(spacing: 14) {
                    Image(systemName: symbol)
                        .font(.system(size: blockGlyphSize, weight: .semibold))
                        .foregroundStyle(tint)
                        .frame(width: blockGlyphWidth)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(title)
                            .font(StrandFont.pro(17))
                            .foregroundStyle(StrandPalette.textPrimary)
                        Text(value)
                            .font(StrandFont.pro(28, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(tint)
                    }
                }
                if !dts.isAccessibilitySize { Spacer() }
                if !usesWheel {
                    HStack(spacing: 14) {
                        stepButton("minus", tint: tint, action: minus)
                        stepButton("plus", tint: tint, action: plus)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
            // One adjustable element per block, so VoiceOver reads "Work, 0:30" and swipes change it.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(title))
            .accessibilityValue(Text(value))
            .accessibilityAdjustableAction { direction in
                guard !runner.running else { return }
                switch direction {
                case .increment: plus()
                case .decrement: minus()
                @unknown default: break
                }
            }
            .modifier(WheelToggleAction(enabled: usesWheel) { if let wheel { toggle(wheel) } })

            #if os(iOS)
            if let wheel, editing == wheel {
                Rectangle().fill(StrandPalette.hairline).frame(height: NoopMetrics.hairlineWidth)
                durationWheel(seconds(wheel))
                    .padding(.vertical, 4)
                    .transition(.opacity)
                    // The row above is the block's one adjustable element; the wheel would read it twice.
                    .accessibilityHidden(true)
            }
            #endif
        }
        .padding(.horizontal, 16)
        .background(StrandPalette.summaryCard, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func toggle(_ phase: PhaseBlock) {
        withAnimation(StrandMotion.interactive) { editing = editing == phase ? nil : phase }
    }

    #if os(iOS)
    /// Clock's countdown wheel: minutes 0–59 and seconds in 5 s steps, each with its unit beside it.
    private func durationWheel(_ total: Binding<Int>) -> some View {
        let minutes = Binding<Int>(
            get: { min(59, total.wrappedValue / 60) },
            set: { total.wrappedValue = max(Self.minPhase, $0 * 60 + total.wrappedValue % 60) })
        let secs = Binding<Int>(
            get: { total.wrappedValue % 60 / 5 * 5 },
            set: { total.wrappedValue = max(Self.minPhase, min(59, total.wrappedValue / 60) * 60 + $0) })
        return HStack(spacing: 0) {
            wheelColumn(minutes, values: Array(0...59), unit: String(localized: "min"))
            wheelColumn(secs, values: Array(stride(from: 0, to: 60, by: 5)), unit: String(localized: "sec"))
        }
        .frame(height: 180)
    }

    private func wheelColumn(_ selection: Binding<Int>, values: [Int], unit: String) -> some View {
        HStack(spacing: 4) {
            Picker(unit, selection: selection) {
                ForEach(values, id: \.self) { Text(verbatim: "\($0)").tag($0) }
            }
            .pickerStyle(.wheel)
            .labelsHidden()
            Text(unit)
                .font(StrandFont.pro(17))
                .foregroundStyle(StrandPalette.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .clipped()
    }
    #endif

    private func stepButton(_ symbol: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(StrandFont.pro(17, weight: .bold))
                .foregroundStyle(tint)
                .frame(width: stepSize, height: stepSize)
                .background(Circle().fill(tint.opacity(0.18)))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

/// The two blocks set on a wheel.
private enum PhaseBlock { case work, rest }

/// A tap on a wheel block's row opens or closes its wheel, and so does VoiceOver's double-tap.
private struct WheelToggleAction: ViewModifier {
    let enabled: Bool
    let action: () -> Void

    func body(content: Content) -> some View {
        if enabled {
            content
                .contentShape(Rectangle())
                .onTapGesture(perform: action)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { action() }
        } else {
            content
        }
    }
}

// MARK: - Running

/// The interval in progress, on the same dark recording screen a workout uses: the phase and round, the
/// phase countdown as a large figure in the phase's hue, the heart rate and the time left overall, then the
/// panel with the total clock and stop / pause / skip.
struct IntervalRunView: View {
    @ObservedObject var runner: IntervalTimerRunner
    let onClose: () -> Void

    @ScaledMetric(relativeTo: .largeTitle) private var clockSize: CGFloat = 120
    @ScaledMetric(relativeTo: .body) private var glyphSize: CGFloat = 18
    @State private var confirmingEnd = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The one "Keep screen on" setting every recording screen honours (Settings → Workouts).
    @AppStorage(LiveWorkoutView.keepScreenOnKey) private var keepScreenOn = false

    /// Started, not running, not done: the pause the panel names.
    private var isPaused: Bool { !runner.running && runner.elapsed > 0 && !runner.isFinished }

    var body: some View {
        VStack(spacing: 0) {
            // Minimising leaves the timer running; the setup page offers to resume it.
            RecordingTopBar(onMinimize: onClose)
            RecordingFigures {
                VStack(alignment: .leading, spacing: 0) {
                    RecordingHeading(caption: runner.isFinished
                                        ? String(localized: "\(runner.rounds) rounds")
                                        : String(localized: "Round \(min(runner.currentRound, runner.rounds)) of \(runner.rounds)"),
                                     tint: runner.phaseColor, title: runner.phase.label)
                        .padding(.top, 8)
                    Spacer(minLength: 8)
                    Text(IntervalTimerRunner.clock(runner.isFinished ? runner.elapsed : runner.remaining))
                        .font(.system(size: clockSize, weight: .regular, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(runner.phaseColor)
                        .contentTransition(reduceMotion ? .identity : .numericText())
                        .lineLimit(1)
                        .minimumScaleFactor(0.4)
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(.white.opacity(0.15))
                            Capsule().fill(runner.phaseColor)
                                .frame(width: geo.size.width * (runner.isFinished ? 1 : runner.phaseProgress))
                                .animation(reduceMotion ? nil : .linear(duration: 1), value: runner.phaseProgress)
                        }
                    }
                    .frame(height: 8)
                    Spacer(minLength: 8)
                    LiftHeartRateFigure()
                    Spacer(minLength: 8)
                    LiveFigure(value: IntervalTimerRunner.clock(max(0, runner.totalPlanned - runner.elapsed)),
                               label: String(localized: "TOTAL\nLEFT"))
                    Spacer(minLength: 8)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 28)
                .padding(.bottom, 12)
            }

            RecordingPanel(
                glyph: AnyView(Image(systemName: "timer")
                    .font(.system(size: glyphSize, weight: .semibold))
                    .foregroundStyle(StrandPalette.activityExerciseText)),
                clock: {
                    RecordingClockText(text: IntervalTimerRunner.clock(runner.elapsed), paused: isPaused)
                        .accessibilityLabel(Text("Elapsed time"))
                },
                trailing: { EmptyView() },
                leading: {
                    RecordingButton(symbol: "xmark", destructive: true, label: "Finish") {
                        // A session under way asks first, as the workout's ✕ does; one not started or
                        // already finished has nothing to lose.
                        if runner.elapsed > 0 && !runner.isFinished {
                            confirmingEnd = true
                        } else {
                            endIntervals()
                        }
                    }
                },
                center: {
                    if runner.isFinished {
                        RecordingButton(symbol: "arrow.counterclockwise", size: 112, prominent: true,
                                        label: "Restart") { runner.resetToStart(); runner.start() }
                    } else {
                        RecordingButton(symbol: runner.running ? "pause.fill" : "play.fill", size: 112,
                                        prominent: !runner.running,
                                        label: runner.running ? "Pause" : "Resume") { runner.toggleRunning() }
                    }
                },
                right: {
                    RecordingButton(symbol: "forward.end.fill", label: "Skip") { runner.skipPhase() }
                        .disabled(runner.isFinished)
                })
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .confirmationDialog("Finish Intervals", isPresented: $confirmingEnd, titleVisibility: .hidden) {
            Button("Finish Intervals", role: .destructive) { endIntervals() }
            Button("Cancel", role: .cancel) {}
        }
        // Keep the screen awake while a session runs, when Settings → Workouts → Keep screen on says so, as
        // a workout and a gym session do (no-op on macOS); onDisappear is the safety net so leaving mid-run
        // never leaves the idle timer disabled app-wide.
        .onChangeCompat(of: runner.running) { ScreenIdle.keepAwake(keepScreenOn && $0) }
        .onChangeCompat(of: keepScreenOn) { ScreenIdle.keepAwake($0 && runner.running) }
        .onAppear { ScreenIdle.keepAwake(keepScreenOn && runner.running) }
        .onDisappear { ScreenIdle.keepAwake(false) }
        #if os(iOS)
        // iPhone haptics: a different feel per cue, re-firing on every token bump. Fires regardless of strap
        // bond so the timer is fully usable unstrapped.
        .sensoryFeedback(trigger: runner.hapticTick) { _, _ in
            switch runner.lastHaptic {
            case .work: return .impact(weight: .heavy)
            case .rest: return .impact(weight: .light)
            // A countdown tick is an impact, not `.selection`, which means a value being scrubbed.
            case .tick: return .impact(weight: .light)
            case .done: return .success
            }
        }
        #endif
    }

    private func endIntervals() {
        runner.stopAndReset()
        onClose()
    }
}

// MARK: - Runner

/// The timer's state and rules, owned at the app root so the running screen, the page and the tab bar's
/// accessory share one clock.
@MainActor
final class IntervalTimerRunner: ObservableObject {
    enum Phase {
        case work, rest, done
        var label: String {
            switch self {
            case .work: return String(localized: "Work")
            case .rest: return String(localized: "interval.rest", defaultValue: "Rest")
            case .done: return String(localized: "Done")
            }
        }
    }
    enum HapticCue { case work, rest, tick, done }

    @Published var workSeconds = 30 { didSet { if !running { resetToStart() } } }
    @Published var restSeconds = 15 { didSet { if !running { resetToStart() } } }
    @Published var rounds = 8 {
        didSet {
            if currentRound > rounds { currentRound = rounds }
            if !running { resetToStart() }
        }
    }

    @Published private(set) var phase: Phase = .work
    @Published private(set) var currentRound = 1
    /// Seconds left in the current phase.
    @Published private(set) var remaining = 30
    @Published private(set) var running = false
    /// Total elapsed seconds across the session.
    @Published private(set) var elapsed = 0
    @Published private(set) var lastHaptic: HapticCue = .work
    @Published private(set) var hapticTick = 0

    /// Strap buzz, set by the page (it knows whether a strap is bonded).
    var buzz: (UInt8) -> Void = { _ in }
    private var timer: Timer?

    init() {
        // Scheduled on `.common` rather than the default run loop mode: a plain `scheduledTimer` stalls
        // while the run loop is tracking a touch (holding a button, dragging), so the countdown would
        // freeze mid-press and then jump to catch up the moment the finger lifts.
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    deinit { timer?.invalidate() }

    var isFinished: Bool { phase == .done }
    /// Started and not reset — the page offers to resume rather than start over.
    var inProgress: Bool { !isFinished && (running || elapsed > 0) }

    var phaseDuration: Int {
        switch phase {
        case .work: return max(1, workSeconds)
        case .rest: return max(1, restSeconds)
        case .done: return 1
        }
    }

    var phaseProgress: Double {
        min(1, max(0, Double(phaseDuration - remaining) / Double(phaseDuration)))
    }

    var totalPlanned: Int {
        guard rounds > 0 else { return 0 }
        return workSeconds * rounds + restSeconds * max(0, rounds - 1)
    }

    var phaseColor: Color {
        switch phase {
        case .work, .done: return StrandPalette.activityExerciseText
        case .rest: return StrandPalette.activityStandText
        }
    }

    // MARK: Rules

    func start() {
        if isFinished { resetToStart() }
        if !running { toggleRunning() }
    }

    private func tick() {
        guard running, !isFinished else { return }
        // 3-2-1 countdown tick on the last seconds of the current phase.
        if remaining <= 3 && remaining >= 1 {
            buzz(1)
            haptic(.tick)
        }
        if remaining > 1 {
            remaining -= 1
            elapsed += 1
            return
        }
        // remaining hits 0 — advance to the next phase/round.
        elapsed += 1
        advancePhase()
    }

    /// Ends the current phase now, as its countdown reaching zero would.
    func skipPhase() {
        guard !isFinished else { return }
        advancePhase()
    }

    private func advancePhase() {
        switch phase {
        case .work:
            if currentRound >= rounds {
                finishSession()
            } else {
                phase = .rest
                remaining = max(1, restSeconds)
                buzz(1)                     // short cue into rest
                haptic(.rest)
            }
        case .rest:
            currentRound += 1
            phase = .work
            remaining = max(1, workSeconds)
            buzz(3)                         // strong cue into work
            haptic(.work)
        case .done:
            break
        }
    }

    private func finishSession() {
        withAnimation(.snappy) {
            phase = .done
            remaining = 0
            running = false
        }
        buzz(5)                             // long completion cue
        haptic(.done)
    }

    func toggleRunning() {
        if isFinished { return }
        if running {
            running = false
        } else {
            // Starting fresh from a clean reset → fire the opening work cue.
            let startingFresh = phase == .work && currentRound == 1 && remaining == max(1, workSeconds) && elapsed == 0
            running = true
            if startingFresh {
                buzz(3)
                haptic(.work)
            }
        }
    }

    func stopAndReset() {
        running = false
        resetToStart()
    }

    /// Back to round 1 / start of work, using the current setup.
    func resetToStart() {
        phase = .work
        currentRound = 1
        remaining = max(1, workSeconds)
        elapsed = 0
    }

    /// An iPhone haptic cue. Bumping the token re-triggers `.sensoryFeedback` even when a cue repeats.
    private func haptic(_ cue: HapticCue) {
        lastHaptic = cue
        hapticTick &+= 1
    }

    static func clock(_ seconds: Int) -> String {
        let s = max(0, seconds)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}
