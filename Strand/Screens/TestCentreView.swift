import SwiftUI
import StrandDesign
import StrandAnalytics
import StrandImport
import OuraProtocol
import PolarProtocol
import WhoopStore
import WhoopProtocol

/// Settings -> Developer: the Test Centre page. The single home for every diagnostic,
/// log and test control (spec section 7). `extra` is the Developer page's own rows, drawn after the
/// diagnostics.
///
/// Four sections: domain test modes (rendered from the registry projection), diagnostic tools, export
/// and auto-export, advanced/experimental. Section 1 iterates TestCentreLayout.visibleModes so adding a
/// profile later is a registry entry, never a new screen. The lower three sections re-host the same
/// bindings and actions that live in SettingsView (strap log, scheduled export, the 5/MG
/// experimental toggles) so the Test Centre is the one place to find them; SettingsView keeps a thin nav
/// link in. No em-dash in any string here.
struct TestCentreView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var live: LiveState

    /// The Developer page's rows, drawn after the diagnostics.
    var extra: AnyView = AnyView(EmptyView())

    /// The Report orchestrator: assembles the redacted bundle, runs the mandatory review gate, shares.
    @StateObject private var report = TestCentreReport()

    /// Re-read activation on appear so a toggle flip elsewhere reflects here.
    @State private var refreshToken = 0

    @State private var infoTitle = ""
    @State private var infoMessage = ""
    @State private var showInfo = false

    // #1853: skin-temp absolute backfill (on-demand, diagnostic-first). Runs the walker that fills
    // `skinTempC` for nights outside the 21-night rescore window. In-progress flag + last result line.
    @State private var skinTempBackfillRunning = false
    @State private var skinTempBackfillStatus: String?

    // Section 3: scheduled daily auto-export, the same ScheduledDebugExport store the Settings card uses.
    @State private var debugExportOn = ScheduledDebugExport.isEnabled
    @State private var debugExportMinutes = ScheduledDebugExport.timeMinutes
    // Retention (#650): how many scheduled-export generations to keep, and the manual clear confirm.
    @State private var debugExportKeep = ScheduledDebugExport.keepCount
    @State private var showClearExportsConfirm = false

    // WHOOP 5/MG developer controls. These use the same persisted keys as the former Settings card;
    // moving the UI does not reset an opt-in or lose the ability to undo a persistent strap write.
    @AppStorage(PuffinExperiment.defaultsKey) private var puffinExperiments = false
    @AppStorage(PuffinFrameRecorder.enabledKey) private var puffinCapture = false
    @AppStorage(PuffinExperiment.ecgRawDataKey) private var ecgRawDataEnabled = false

    /// The strap model the user last picked, the same key SettingsView's showFiveMGControls gate reads.
    @AppStorage("selectedWhoopModel") private var selectedWhoopModelRaw = WhoopModel.whoop4.rawValue

    // #polar-debug: the Polar strap-identity diagnostic toggle. Only rendered when a Polar strap is paired.
    @AppStorage(AppModel.polarDebugLoggingKey) private var polarDebugLogging = false

    /// The model NOOP auto-detects for a PAIRED Polar strap, from its stored advertised name (no live
    /// connection needed) — e.g. "Polar H10 identified — PMD ecg,acc; HRV via standard R-R". `nil` when no
    /// Polar strap is paired, which hides the whole toggle so a non-Polar user never sees Polar debug.
    private var polarIdentity: String? {
        let name = model.deviceRegistry?.devices.first { PolarModel.isPolar(advertisedName: $0.model) }?.model
        return PolarModel.debugIdentification(advertisedName: name)
    }

    // #1284 residual 3: experimental Oura 0x49-onset keying, only offered when an Oura ring is paired.
    @AppStorage(AppModel.ouraOnsetKeyingKey) private var ouraOnsetKeying = false
    // Packed-notification A/B: the official app's SetNotification mask `ff` vs NOOP's `3f`, next connect.
    @AppStorage(AppModel.ouraNotifyMaskFullKey) private var ouraNotifyMaskFull = false
    private var ouraPaired: Bool { model.deviceRegistry?.devices.contains { $0.brand == "Oura" } ?? false }

    /// The profile the 0x20 write experiment prefills from. Values are OVERRIDABLE in the field below:
    /// the encoding is unverified, so the point is to try 175 (cm) and then 1750 (mm) without a rebuild.
    @EnvironmentObject var profile: ProfileStore

    // 0x20 user-info write EXPERIMENT (see OuraUserInfoWrite). Nothing here fires automatically.
    @State private var userInfoField: OuraUserInfoField = .height
    @State private var userInfoValueText = ""
    @State private var userInfoRawHex = ""
    @State private var showUserInfoConfirm = false

    /// The value bytes the current selection would send, or nil when the entry is not usable yet.
    /// Raw-hex mode is how the date-of-birth (age) path is probed: there is no age setter and no capture
    /// pins the 9-byte type-5 layout, so a guess must be typed deliberately, never offered as "Age".
    private var userInfoValueBytes: [UInt8]? {
        if userInfoField == .dateOfBirth {
            let cleaned = userInfoRawHex.filter { !$0.isWhitespace }
            guard cleaned.count == userInfoField.valueByteCount * 2 else { return nil }
            var out: [UInt8] = []
            var idx = cleaned.startIndex
            while idx < cleaned.endIndex {
                let next = cleaned.index(idx, offsetBy: 2)
                guard let b = UInt8(cleaned[idx..<next], radix: 16) else { return nil }
                out.append(b); idx = next
            }
            return out
        }
        guard let n = UInt32(userInfoValueText.trimmingCharacters(in: .whitespaces)) else { return nil }
        return try? OuraUserInfoWrite.encodeLE(n, width: userInfoField.valueByteCount)
    }

    /// The exact frame that would go on the wire, so it can be read BEFORE sending.
    private var userInfoFramePreview: String {
        guard let v = userInfoValueBytes, let cmd = try? OuraUserInfoWrite.command(userInfoField, value: v) else {
            return "enter a value"
        }
        return cmd.bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// Prefill from the NOOP profile when the field changes. Sex maps through the 0x5c gender code.
    private func prefillUserInfoValue() {
        switch userInfoField {
        case .height: userInfoValueText = String(Int(profile.heightCm.rounded()))
        case .weight: userInfoValueText = String(Int(profile.weightKg.rounded()))
        case .gender: userInfoValueText = String(OuraUserInfoWrite.genderCode(forSex: profile.sex))
        case .unit:   userInfoValueText = "0"
        case .dateOfBirth: userInfoValueText = ""
        }
    }

    // Feature-mode write EXPERIMENT (see OuraCommands.setFeatureMode, OURA_PROTOCOL.md s7.5). Nothing
    // here fires automatically. Scoped to SpO2 / real-steps / exercise-HR / CVA-PPG-sampler — the
    // features [open_oura-feat]'s local-write evidence actually covers — and mode off/automatic only,
    // the only mode value that evidence covers. Daytime HR and resting HR are deliberately excluded:
    // daytime HR already has its own dedicated live-HR enable path (different mode value, 0x03
    // connected_live, wired into wear detection — don't duplicate it here), and resting HR has no
    // app-level toggle at all per OURA_PROTOCOL.md s7.1 (firmware-computed, no SetFeatureMode target).
    //
    // One Enable/Disable button PER feature (not a shared feature+mode picker pair): the combined
    // picker made it easy to write the wrong feature by forgetting the other picker was still on its
    // last selection. A disable round-trip (Off, confirm the ring actually reports off, then Automatic
    // to restore) is also the only test available on a ring whose features are already
    // account-unlocked, since a plain enable is a no-op there (2026-09-11 hardware run).
    private struct PendingFeatureWrite: Identifiable {
        let feature: UInt8
        let mode: UInt8
        var id: String { "\(feature)-\(mode)" }
        var framePreview: String {
            OuraCommands.setFeatureMode(feature, mode: mode).bytes.map { String(format: "%02x", $0) }.joined()
        }
    }
    @State private var pendingFeatureWrite: PendingFeatureWrite?

    // Section 4: Experimental algorithms. Bound to the SAME PuffinExperiment keys the Android card writes, so
    // the platforms stay in lockstep. The PPG-HR sub-lag interpolation variant and the HRV-readiness readout,
    // both default OFF.
    @AppStorage(PuffinExperiment.ppgHrSubLagInterpKey) private var ppgHrSubLagInterpEnabled = false
    @AppStorage(PuffinExperiment.hrvReadinessKey) private var hrvReadinessEnabled = false
    /// Sleep staging V2 (default ON). Read at the staging call site in `Repository`.
    @AppStorage(PuffinExperiment.experimentalSleepV2Key) private var experimentalSleepV2Enabled = true
    /// #364 follow-up (default OFF): fold a wake block with no locomotion back into light sleep.
    @AppStorage(PuffinExperiment.motionAwareWakeKey) private var motionAwareWakeEnabled = false
    /// #103: surface the unverified strap SpO₂ estimate when no calibrated reading exists.
    @AppStorage(PuffinExperiment.spo2CandidateDisplayKey) private var spo2CandidateDisplayEnabled = false

    /// True when the connected strap is a 5/MG, so the 5/MG experimental block shows. Mirrors the
    /// SettingsView gate (#22): a confident 4.0 owner never sees controls that cannot touch their strap.
    private var is5MG: Bool { selectedWhoopModelRaw == WhoopModel.whoop5mg.rawValue }

    /// The "whole app" report profile for the section-3 manual Report button. master is not a registry
    /// mode (it has no wear-and-capture flow), so the deep-link self-applies the test:all label via this.
    static let masterReportMode = TestMode(
        domain: .master, title: String(localized: "Bug report"), blurb: "", icon: "ladybug", priority: .high,
        captures: [], questionnaire: [], liveReadout: [],
        capture: .toggle, includesScreenshot: false, requires5MG: false)

    var body: some View {
        Form {
            testModesSection
            bugReportSection
            diagnosticsSections
            extra
            if is5MG {
                rawDataCollectorSection
                fiveMGProtocolDiagnosticsSections
            }
            if ouraPaired {
                ouraToggleSections
                ouraUserInfoWriteSection
                ouraEnableFeatureSection
            }
            exportSections
            experimentalAlgorithmsSections
        }
        .settingsPage("Developer")
        .id(refreshToken)
        .onAppear {
            refreshToken &+= 1
            ScheduledDebugExport.activateIfEnabled()
            if userInfoValueText.isEmpty { prefillUserInfoValue() }
            // If the Display mode was already on when the screen appears, (re)start its frame monitor and
            // wire the sink, so a monitor that was torn down (the screen left and came back) resumes.
            if TestCentre.active(.display) { DisplayMonitorWiring.start(live: live, model: model) }
        }
        .onDisappear {
            // Leaving the screen tears the frame monitor down so no display link survives a navigation
            // away. The mode flag stays on (the user's test is still active); the monitor resumes on
            // .onAppear above. This keeps the perpetual-display-link contract: a link exists only while the
            // Test Centre is on screen with the mode on.
            DisplayPerformanceMonitor.shared.stop()
        }
        .sheet(item: $report.pending) { _ in
            ReportReviewSheet(report: report)
        }
        .confirmationDialog("Clear scheduled exports?",
                            isPresented: $showClearExportsConfirm, titleVisibility: .visible) {
            Button("Clear", role: .destructive) { clearScheduledExports() }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This deletes every scheduled strap-log and raw-capture file reNOOP has saved. This can't be undone.")
        }
        .alert("Write to the ring?", isPresented: $showUserInfoConfirm) {
            Button("Cancel", role: .cancel) { }
            Button("Write", role: .destructive) {
                guard let v = userInfoValueBytes else { return }
                if model.sourceCoordinator?.ouraSource?.writeUserInfo(field: userInfoField, value: v) != true {
                    infoTitle = "Not written"
                    infoMessage = "No connected Oura ring. Connect the ring, then try again."
                    showInfo = true
                }
            }
        } message: {
            Text("Sends \(userInfoFramePreview) to the ring. This changes ring-side config. Restore with the firmware default (height 176, weight 75, sex 2) or write zeros.")
        }
        .alert("Write to the ring?", isPresented: Binding(
            get: { pendingFeatureWrite != nil },
            set: { if !$0 { pendingFeatureWrite = nil } }
        ), presenting: pendingFeatureWrite) { pending in
            Button("Cancel", role: .cancel) { }
            Button("Write", role: .destructive) {
                if model.sourceCoordinator?.ouraSource?.writeFeatureMode(feature: pending.feature, mode: pending.mode) != true {
                    infoTitle = "Not written"
                    infoMessage = "No connected Oura ring. Connect the ring, then try again."
                    showInfo = true
                }
            }
        } message: { pending in
            Text("Sends \(pending.framePreview) to the ring. Unvalidated on reNOOP's own hardware (OURA_PROTOCOL.md \u{00A7}7.5). Watch the strap log for the follow-up feature-status read.")
        }
        .alert(infoTitle, isPresented: $showInfo) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(infoMessage)
        }
    }

    // MARK: - Section 1: Domain test modes (rendered from the registry projection)

    private var testModesSection: some View {
        Section {
            ForEach(TestCentreLayout.visibleModes(is5MG: is5MG)) { mode in
                TestModeRows(mode: mode, report: report)
            }
        } header: {
            Text("Test modes")
        }
    }

    /// The generic "whole app" report: the master profile so the deep-link self-applies the test:all
    /// label. master is not in the registry (it is not a wear-and-capture mode), so it is built inline.
    private var bugReportSection: some View {
        Section {
            Button("Report a bug with my log") {
                report.start(mode: TestCentreView.masterReportMode, live: live, repo: model.repo)
            }
            if let status = report.lastStatus {
                Text(status).foregroundStyle(StrandPalette.textSecondary)
            }
            // M3 (#812): the mobile copy fallback. If the user cannot attach the .zip in the GitHub
            // composer, this pastes the redacted report into the clipboard to drop straight into the issue.
            // Only appears after a confirmed share on the path that offers it.
            if let reportText = report.copyableReport {
                Button("Copy report.txt") { PlatformPasteboard.copy(reportText) }
                    .accessibilityLabel("Copy the redacted report to the clipboard")
            }
        } header: {
            Text("Bug report")
        }
    }

    // MARK: - Section 2: Diagnostic tools (strap log, skin-temp backfill, Polar)

    @ViewBuilder private var diagnosticsSections: some View {
        // Strap log, the same exportableLogText the Settings + Live strap-log controls share. The
        // environment dump is the IOSDiagnostics-backed block exportableLogText already carries.
        Section {
            NavigationLink("Strap Log") { StrapLogPage() }
            Button("Copy strap log") { PlatformPasteboard.copy(live.exportableLogText()) }
            Button("Save strap log…") {
                Task {
                    let extra = await DebugDataDiagnostics.dynamicLines(repo: model.repo)
                    FileExport.exportText(live.exportableLogText(extraHeaderLines: extra),
                                          suggestedName: FileExport.timestampedName("noop-strap-log", ext: "txt"))
                }
            }
        } header: {
            Text("Diagnostics")
        }

        // #1853: skin-temp absolute backfill (on-demand, diagnostic-first). Fills `skinTempC` for nights
        // outside the 21-night rescore window that never got an absolute. Fill-only: it can only fill a
        // NULL, never overwrite a measured value or touch the deviation.
        Section {
            Button("Backfill skin-temp absolutes") { runSkinTempBackfill() }
                .disabled(skinTempBackfillRunning)
            if let status = skinTempBackfillStatus {
                Text(status).foregroundStyle(StrandPalette.textSecondary)
            }
        }

        // #polar-debug: only when a Polar strap is paired. The footer names the model auto-detected from
        // the paired record; the toggle also writes it to the strap log on each connect.
        if let identity = polarIdentity {
            Section {
                Toggle("Polar debug logging", isOn: $polarDebugLogging)
            } footer: {
                Text(identity)
            }
        }
    }

    // MARK: - Section 2a: WHOOP 5/MG (only when the selected strap is a 5/MG)

    private var rawDataCollectorSection: some View {
        Section {
            NavigationLink {
                RawDataCollectorView()
            } label: {
                LabeledContent {
                    Text(live.connected ? "Connected" : "Not connected")
                } label: {
                    Text("5/MG Raw Data Collector")
                }
            }
        } header: {
            Text(verbatim: "WHOOP 5/MG")
        }
    }

    /// Developer tools for unmapped 5/MG protocol features. None of them is required for normal
    /// recording, history sync, or the bounded Raw Data Collector.
    @ViewBuilder private var fiveMGProtocolDiagnosticsSections: some View {
        Section {
            Toggle("Protocol probes", isOn: $puffinExperiments)
        } header: {
            Text("5/MG protocol diagnostics")
        }

        Section {
            Toggle("WHOOP MG ECG raw-data gate", isOn: $ecgRawDataEnabled)
            if ecgRawDataEnabled {
                Group {
                    Button("Gate on") { model.ble.setEcgRawDataGate(true) }
                    Button("Gate off") { model.ble.setEcgRawDataGate(false) }
                }
                .disabled(!live.encryptedBond || live.whoop5Variant != Whoop5Variant.mg.label)
                if let result = live.ecgRawDataGate {
                    Text(result.summary).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        } footer: {
            Text("MG-only protocol instrumentation, not a medical ECG feature.")
        }

        Section {
            Toggle("Passive history/protocol trace", isOn: $puffinCapture)
        }
    }

    // MARK: - Section 2b: Oura (consolidated; only when an Oura ring is paired)

    @ViewBuilder private var ouraToggleSections: some View {
        // #1284 residual 3: experimental Oura onset keying.
        Section {
            Toggle("Oura onset keying (experimental)", isOn: $ouraOnsetKeying)
        } header: {
            Text("Oura")
        }

        // Packed-notification A/B (OURA_PROTOCOL.md s2.3). Takes effect at the NEXT connect only;
        // nothing is written until then and nothing persists on the ring.
        Section {
            Toggle("Oura notification mask ff (experimental)", isOn: $ouraNotifyMaskFull)
        } footer: {
            Text("At the next connect, sends the official app\u{2019}s notification mask (1c 01 ff) instead of reNOOP\u{2019}s 3f.")
        }
    }

    /// The 0x20 user-info WRITE experiment. EXPERIMENTAL, manual, one field at a time.
    ///
    /// This is the only control in NOOP that writes user data to a ring. It exists to answer one
    /// question: does a 0x20 write change what tag 0x5c reports? Nothing calls it automatically, and
    /// deliberately NOT on connect: the value encoding is unverified, an automatic write would destroy
    /// the clean before-state the readout depends on, and the connect/bond window is the app's most
    /// fragile path (#1635). Read the strap log for the WRITE line, the 0x20 ACK, and the next 0x5c.
    private var ouraUserInfoWriteSection: some View {
        Section {
            Picker("Field", selection: $userInfoField) {
                Text("Height").tag(OuraUserInfoField.height)
                Text("Sex").tag(OuraUserInfoField.gender)
                Text("Weight").tag(OuraUserInfoField.weight)
                Text("DOB (raw)").tag(OuraUserInfoField.dateOfBirth)
            }
            .settingsPicker()
            .onChange(of: userInfoField) { _ in prefillUserInfoValue() }

            if userInfoField == .dateOfBirth {
                TextField("18 hex chars (9 bytes)", text: $userInfoRawHex)
            } else {
                TextField("value", text: $userInfoValueText)
            }

            Text("Frame: \(userInfoFramePreview)")
                .foregroundStyle(StrandPalette.textSecondary)

            Button("Write to ring") { showUserInfoConfirm = true }
                .disabled(userInfoValueBytes == nil)
            Button("Write zeros") {
                _ = model.sourceCoordinator?.ouraSource?.writeUserInfo(
                    field: userInfoField,
                    value: [UInt8](repeating: 0, count: userInfoField.valueByteCount))
            }
        } header: {
            Text("Oura user-info write (experimental)")
        } footer: {
            Text("Tested on Gen 3 only.")
        }
    }

    /// The `SetFeatureMode` WRITE experiment (`2f 03 22 <id> <mode>`) — hardware-CONFIRMED 2026-09-11:
    /// disabling then re-enabling SpO2 flipped `mode` `1→0→1`, reproduced both directions on a real
    /// Gen 3 ring. `docs/OURA_PROTOCOL.md` s7.5 still marks the account-gate-bypass claim itself
    /// unvalidated (this ring was already cloud-entitled), but the write mechanism itself is proven.
    /// Scoped to SpO2 / real-steps / exercise-HR / CVA-PPG-sampler, mode off/automatic only — daytime
    /// HR and resting HR are deliberately excluded (see the state-var comment above).
    ///
    /// Each row shows the ring's OWN last-known status live (mirrored via `AppModel.ouraFeatureStatuses`
    /// off `OuraLiveSource.featureStatuses`) — originally log-only, changed after the same hardware run
    /// made clear that reading the strap log for every check was the wrong tradeoff for a control meant
    /// to be poked repeatedly. The strap log still carries the WRITE line and the 0x23 ACK either way.
    ///
    /// One Enable/Disable button PER feature — see the state-var comment above for why this replaced
    /// a shared feature+mode picker pair.
    private var ouraEnableFeatureSection: some View {
        Section {
            ouraFeatureEnableRow(label: "SpO2", feature: OuraCommands.featureSpO2)
            ouraFeatureEnableRow(label: "Real steps", feature: OuraCommands.featureRealSteps)
            ouraFeatureEnableRow(label: "Exercise HR", feature: OuraCommands.featureExerciseHR)
            ouraFeatureEnableRow(label: "CVA PPG sampler", feature: OuraCommands.featureCvaPpg)
        } header: {
            Text("Oura feature enable (experimental)")
        } footer: {
            Text("Unvalidated on reNOOP's own hardware (OURA_PROTOCOL.md \u{00A7}7.5).")
        }
    }

    private func ouraFeatureEnableRow(label: LocalizedStringKey, feature: UInt8) -> some View {
        HStack(spacing: NoopMetrics.space3) {
            VStack(alignment: .leading, spacing: NoopMetrics.spaceHalf) {
                Text(label)
                HStack(spacing: NoopMetrics.space1) {
                    StatusDot(color: ouraFeatureStatusIsOn(feature) ? StrandPalette.settingsGreen : StrandPalette.settingsGray)
                    Text(ouraFeatureStatusLabel(feature))
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
            }
            Spacer()
            // Borderless so each button takes its own tap instead of the whole row firing both.
            Button("Enable") { pendingFeatureWrite = PendingFeatureWrite(feature: feature, mode: 0x01) }
            Button("Disable") { pendingFeatureWrite = PendingFeatureWrite(feature: feature, mode: 0x00) }
        }
        .buttonStyle(.borderless)
    }

    /// The ring's own last-known status for this feature, mirrored live off `OuraLiveSource` (never a
    /// guess from what we last SENT — see the 2026-09-11 hardware run where a self-induced disable had
    /// to be told apart from a genuine cloud gate). "Unknown" until the connect-time auto-probe (SpO2/
    /// real-steps) or an explicit Enable/Disable has produced at least one read-back this connection.
    /// `mode` is what Enable/Disable actually controls, so it drives BOTH the primary label and the
    /// color — a real 2026-09-11 hardware run showed SpO2 correctly enabled (`mode=automatic`) read as
    /// "still off" because the label led with `status` instead, and `status=0`'s word ("off") sat right
    /// next to the Enable/Disable buttons, reading as a second, contradicting toggle. `status` is real
    /// and worth showing (SpO2 samples periodically, not continuously, so `status=0` "idle" is its
    /// NORMAL resting state even while enabled) but only as a silent-by-default qualifier, in vocabulary
    /// that cannot be mistaken for another on/off switch — "active"/"searching"/etc, never "on"/"off".
    private func ouraFeatureStatusLabel(_ feature: UInt8) -> String {
        guard let st = model.ouraFeatureStatuses[Int(feature)] else { return String(localized: "Unknown") }
        let mode = ouraFeatureModeLabel(st.mode)
        guard let qualifier = ouraFeatureStatusQualifier(st.status) else { return mode }
        return "\(mode) \u{00B7} \(qualifier)"
    }

    private func ouraFeatureModeLabel(_ mode: Int) -> String {
        switch mode {
        case 0: return String(localized: "Off")
        case 1: return String(localized: "Automatic")
        case 2: return String(localized: "Requested")
        case 3: return String(localized: "Connected live")
        default: return String(localized: "Unknown")
        }
    }

    /// nil for status=0 ("idle") — the expected resting state for an ENABLED feature between samples,
    /// not worth a qualifier every time. Non-nil only when the ring reports something beyond that.
    private func ouraFeatureStatusQualifier(_ status: Int) -> String? {
        switch status {
        case 0: return nil
        case 1: return String(localized: "active")
        case 2: return String(localized: "searching")
        case 3: return String(localized: "no PPG")
        case 4: return String(localized: "cold")
        case 5: return String(localized: "movement")
        case 6: return String(localized: "identifying")
        default: return String(localized: "unknown")
        }
    }

    /// Colors the row on `mode` (what Enable/Disable controls), not `status` (a transient sampling
    /// detail) — see the doc comment on `ouraFeatureStatusLabel` for why conflating the two misled a
    /// real hardware test into reading a correctly-enabled SpO2 as still off.
    private func ouraFeatureStatusIsOn(_ feature: UInt8) -> Bool {
        model.ouraFeatureStatuses[Int(feature)]?.mode == 1
    }

    // MARK: - Section 3: Export and auto-export (scheduled export)

    @ViewBuilder private var exportSections: some View {
        // Scheduled daily auto-export, the same ScheduledDebugExport reads/writes as the Settings
        // Diagnostics card.
        Section {
            Toggle("Daily auto-export of the strap log", isOn: $debugExportOn)
                .onChangeCompat(of: debugExportOn) { on in ScheduledDebugExport.setEnabled(on) }
            if debugExportOn {
                DatePicker("Time of day", selection: debugExportTimeBinding, displayedComponents: .hourAndMinute)
                    .accessibilityLabel("Daily auto-export time")
                // Retention (#650): how many scheduled-export generations to keep. Wired to
                // ScheduledDebugExport.keepCount; the next write prunes the oldest beyond this count.
                Picker("Keep last exports", selection: $debugExportKeep) {
                    ForEach(ScheduledDebugExport.keepOptions, id: \.self) { n in Text("\(n)").tag(n) }
                }
                .settingsPicker()
                .onChangeCompat(of: debugExportKeep) { n in ScheduledDebugExport.keepCount = n }
                Button("Run now") { runScheduledExportNow() }
            }
        } header: {
            Text("Export")
        }

        // Manual clear (#650): always available, even with the toggle off, since files written while it
        // was on can outlive that toggle flip.
        Section {
            Button("Clear scheduled exports", role: .destructive) { showClearExportsConfirm = true }
        }
    }

    // MARK: - Section 4: Experimental algorithms (opt-in, off-by-default research variants)

    /// The single home for OPT-IN, non-clinical research variants that swap which model computes a metric
    /// (never detection, never a stored WHOOP value). Each toggle writes the SAME PuffinExperiment key its
    /// Android twin reads. Hosts the HR-from-PPG sub-lag interpolation variant. Twin of the Android
    /// ExperimentalAlgorithmsCard.
    @ViewBuilder private var experimentalAlgorithmsSections: some View {
        Section {
            Toggle("Sleep staging (V2)", isOn: $experimentalSleepV2Enabled)
            Toggle("Motion-aware wake refinement", isOn: $motionAwareWakeEnabled)
            // Not gated on the strap, as on Android: a WHOOP 4.0 (v24 `aux_byte_86`), a 5/MG (v18 `@82`)
            // and an Oura ring each have a candidate behind this one switch.
            Toggle("Blood Oxygen: strap estimate", isOn: $spo2CandidateDisplayEnabled)
                .onChangeCompat(of: spo2CandidateDisplayEnabled) { _ in
                    Task { await model.intelligence.analyzeRecent(); await model.repo.refresh() }
                }
            Toggle("HR-from-PPG sub-lag interpolation (v26 gap-fill)", isOn: $ppgHrSubLagInterpEnabled)
            Toggle("HRV readiness (Plews/Altini)", isOn: $hrvReadinessEnabled)
            // The toggle's OWN effect, shown in place: when on, the live Plews/Altini reading. Nothing
            // renders when off, so the flag off is zero behaviour change and feeds no downstream gate.
            if hrvReadinessEnabled { hrvReadinessReadout }
        } header: {
            Text("Experiments")
        }
    }

    /// The inline, opt-in HRV-readiness reading rendered under the "HRV readiness (Plews/Altini)" toggle when
    /// the flag is on, so the toggle's own effect is visible in place. Reads the SAME repo-merged nightly
    /// `DailyMetric.avgHrv` series (oldest-first) the recovery UI has and runs it through the pure
    /// `HRVReadiness` engine; it never touches the default Charge ring or analyzeDay. Below
    /// `HRVReadiness.minNights` valid nights it shows the honest calibrating count, never a fabricated tier.
    /// Twin of the Android `HrvReadinessReadoutTC`.
    @ViewBuilder private var hrvReadinessReadout: some View {
        if let r = HRVReadiness.evaluate(avgHrv: model.repo.days.map { $0.avgHrv }) {
            let label = hrvTierLabel(r.tier)
            let base = Int(r.baseline7Ms.rounded())
            let lo = Int(r.normalLowMs.rounded())
            let hi = Int(r.normalHighMs.rounded())
            let watch = r.overreachingWatch ? ", overreaching watch" : ""
            LabeledContent {
                Text(label.word).foregroundStyle(label.color)
            } label: {
                VStack(alignment: .leading, spacing: NoopMetrics.spaceHalf) {
                    Text("HRV readiness (experimental)")
                    Text("7-night baseline \(base) ms, normal \(lo) to \(hi) ms\(watch)")
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        } else {
            LabeledContent("HRV readiness (experimental)") {
                Text("Calibrating (\(hrvValidNightCount)/\(HRVReadiness.minNights) nights)")
            }
        }
    }

    /// Tier -> (label word, colour). A plain function (NOT a result-builder switch) so the readiness switch
    /// never lands inside the `@ViewBuilder` body above. Twin of the Android `when (result.tier)` mapping.
    private func hrvTierLabel(_ tier: ReadinessTier) -> (word: String, color: Color) {
        switch tier {
        case .primed:     return ("primed", StrandPalette.statusPositive)
        case .normal:     return ("normal", StrandPalette.textPrimary)
        case .suppressed: return ("suppressed", StrandPalette.statusWarning)
        }
    }

    /// Valid-night count for the calibrating readout, counted the same way the engine's gate does (the shared
    /// `Baselines.hrvCfg` bounds) so the "N/minNights" it shows matches when a reading will first appear.
    private var hrvValidNightCount: Int {
        let cfg = Baselines.hrvCfg
        return model.repo.days.reduce(0) { acc, d in
            if let v = d.avgHrv, cfg.minVal <= v && v <= cfg.maxVal { return acc + 1 }
            return acc
        }
    }

    // MARK: - Shared actions (same calls as the SettingsView controls these re-host)

    /// The manual "Clear scheduled exports" action (#650): wipes every scheduled strap-log / raw-capture
    /// file NOOP has dropped into Documents, regardless of the retention setting, then confirms via the
    /// same info alert the other export actions use.
    private func clearScheduledExports() {
        let removed = ScheduledDebugExport.clearScheduledExports()
        infoTitle = String(localized: "Scheduled exports cleared")
        infoMessage = removed > 0
            ? String(localized: "Removed \(removed) file(s).")
            : String(localized: "No scheduled exports to clear.")
        showInfo = true
    }

    /// #1853: run the skin-temp absolute backfill on demand (diagnostic-first trigger). The walker
    /// resolves the WHOOP 4.0 window anchor from the current scoring window, pages through the
    /// candidate nights, and fills each NULL `skinTempC`. The result is logged to the strap log and
    /// shown under the button so the user sees exactly what filled, declined, and had no raw data.
    private func runSkinTempBackfill() {
        guard !skinTempBackfillRunning else { return }
        skinTempBackfillRunning = true
        skinTempBackfillStatus = String(localized: "Backfilling…")
        Task { @MainActor in
            guard let store = await model.repo.storeHandle() else {
                skinTempBackfillStatus = String(localized: "No on-device store yet.")
                skinTempBackfillRunning = false
                return
            }
            let walker = SkinTempBackfillWalker(store: store)
            let result = await walker.runBackfill()
            let anchorNote = result.windowAnchorRaw != nil
                ? "anchor from current window"
                : "no 4.0 anchor (5/MG or too few worn samples)"
            let endNote = result.reachedEnd ? "reached end" : "hit page budget"
            let line = "skin-temp backfill (#1853): filled \(result.filledCount), " +
                       "declined \(result.declinedCount), no raw data \(result.noRawDataCount) " +
                       "(\(anchorNote), \(endNote))"
            live.append(log: line)
            skinTempBackfillStatus = line
            skinTempBackfillRunning = false
            // Refresh so the newly-filled absolutes show in the charts/explorer.
            await model.repo.refresh()
        }
    }

    private var debugExportTimeBinding: Binding<Date> {
        Binding(
            get: {
                var c = DateComponents()
                c.hour = debugExportMinutes / 60
                c.minute = debugExportMinutes % 60
                return Calendar.current.date(from: c) ?? Date()
            },
            set: { date in
                let c = Calendar.current.dateComponents([.hour, .minute], from: date)
                let m = (c.hour ?? 7) * 60 + (c.minute ?? 0)
                debugExportMinutes = m
                ScheduledDebugExport.setTimeMinutes(m)
            }
        )
    }

    private func runScheduledExportNow() {
        Task { @MainActor in
            await model.ble.flushPuffinCaptures()
            let url = ScheduledDebugExport.runNow(captureURL: live.puffinCaptureURL)
            if let url {
                infoTitle = String(localized: "Strap log exported")
                #if os(iOS)
                infoMessage = String(localized: "Saved \(url.lastPathComponent) to reNOOP's folder in the Files app.")
                #else
                infoMessage = String(localized: "Saved \(url.lastPathComponent) to your Documents folder.")
                #endif
            } else {
                infoTitle = String(localized: "Export failed")
                infoMessage = String(localized: "Couldn't write the strap log right now.")
            }
            showInfo = true
        }
    }
}

/// One domain test mode: a toggle wired to TestCentre (the single prefs namespace), and while it is on,
/// the guided progress, the live readout rows and a Share action.
private struct TestModeRows: View {
    let mode: TestMode
    @ObservedObject var report: TestCentreReport
    @EnvironmentObject var live: LiveState
    @EnvironmentObject var model: AppModel
    @State private var on: Bool

    init(mode: TestMode, report: TestCentreReport) {
        self.mode = mode
        _report = ObservedObject(wrappedValue: report)
        _on = State(initialValue: TestCentre.active(mode.domain))
    }

    private var elapsed: Double? {
        TestCentre.startedAt(mode.domain).map { Date().timeIntervalSince($0) }
    }

    /// The HONEST per-mode captured-day count for a guided row (#965): distinct days THIS mode produced its
    /// own trace on, read from the same shareable log the report exports, so each active mode accumulates
    /// its OWN count instead of every guided row sharing one elapsed-clock number. nil for a toggle mode
    /// (no "K of N") and when the mode is off. Recomputes with `live.log`, which the coalesced `logRevision` invalidates (#2547), so the row updates as
    /// new capture days land.
    private var capturedUnits: Int? {
        guard on, case .guided = mode.capture else { return nil }
        return CaptureAccumulator.capturedDays(domain: mode.domain,
                                               reportText: live.exportableLogText(),
                                               tzOffsetSeconds: TimeZone.current.secondsFromGMT())
    }

    private var isGuided: Bool {
        if case .guided = mode.capture { return true }
        return false
    }

    var body: some View {
        Toggle(isOn: $on) {
            Label {
                Text(mode.title).foregroundStyle(StrandPalette.textPrimary)
            } icon: {
                SettingsIcon(systemName: mode.icon, color: Self.iconColor(mode.domain))
            }
        }
        .accessibilityLabel("\(mode.title) test mode")
        .onChangeCompat(of: on) { isOn in
            if isOn { TestCentre.activate(mode.domain) } else { TestCentre.deactivate(mode.domain) }
            // Display & Performance owns a live frame monitor. It must run ONLY while the mode is on: start
            // it on toggle-on (after wiring its sink to the redacting .display log), tear it down on
            // toggle-off so no display link survives. Zero-cost when off.
            if mode.domain == .display {
                if isOn { DisplayMonitorWiring.start(live: live, model: model) } else { DisplayPerformanceMonitor.shared.stop() }
            }
        }

        if on {
            // A toggle mode's status is just "On", which the switch already shows; a guided mode's
            // "Capturing K of N" is the one fact worth its own row.
            if isGuided {
                Text(TestCentreLayout.statusText(for: mode, active: on, elapsedSeconds: elapsed,
                                                 capturedUnits: capturedUnits))
                    .foregroundStyle(StrandPalette.textSecondary)
            }
            // Live readout (Group E/F): the per-mode rows binding the registry's liveReadout ids, shown
            // only while the mode is on so an inactive mode stays one row.
            readout
            // "Share", not "Report", to match the strap-log button and the sheet this opens, whose own copy
            // already reads "Nothing leaves this phone until you tap Share". The word also describes what
            // the tap does: it builds a redacted bundle and hands it to the share sheet. Nothing is sent
            // anywhere.
            Button {
                report.start(mode: mode, live: live, repo: model.repo)
            } label: {
                Label("Share", systemImage: "square.and.arrow.up")
            }
        }
    }

    @ViewBuilder private var readout: some View {
        switch mode.domain {
        case .sleep:      SleepReadoutPanel(live: live)
        case .battery:    BatteryReadoutPanel(live: live)
        case .connection: ConnectionReadoutPanel(live: live)
        case .recovery:   RecoveryReadoutPanel(live: live)
        case .hrv:        HrvReadoutPanel(live: live)
        case .steps:      StepsReadoutPanel(live: live)
        case .workouts:   WorkoutsReadoutPanel(live: live)
        case .dataImport: ImportReadoutPanel(live: live)
        case .display:    DisplayReadoutPanel(live: live)
        default:          EmptyView()
        }
    }

    /// The row tile colour per domain, from the Settings icon palette so the list matches the root.
    private static func iconColor(_ domain: TestDomain) -> Color {
        switch domain {
        case .sleep:         return StrandPalette.settingsIndigo
        case .connection:    return StrandPalette.settingsBlue
        case .workouts:      return StrandPalette.settingsGreen
        case .display:       return StrandPalette.settingsPurple
        case .dataImport:    return StrandPalette.settingsTeal
        case .steps:         return StrandPalette.settingsOrange
        case .notifications: return StrandPalette.settingsRed
        case .battery:       return StrandPalette.settingsGreen
        case .recovery:      return StrandPalette.settingsCyan
        case .hrv:           return StrandPalette.settingsPink
        case .stress:        return StrandPalette.settingsOrange
        case .longevity:     return StrandPalette.settingsPurple
        case .sources, .universal, .master: return StrandPalette.settingsGray
        }
    }
}

/// Wires the Display monitor's sink to the redacting `.display` log and starts it. The sink is set every
/// start so a fresh LiveState (e.g. after a screen re-entry) is always the live target.
private enum DisplayMonitorWiring {
    @MainActor static func start(live: LiveState, model: AppModel) {
        DisplayPerformanceMonitor.shared.emit = { [weak live] line in
            live?.append(log: line, domain: .display)
        }
        // CAPTURE-D (#797): wire the data-volume provider so start() emits one `dataVolume` line read STRAIGHT
        // from the store (Repository.dataVolumeSnapshot queries the store, not the @Published caches), so an
        // import-driven-lag report shows the read-set behind the frame stats.
        DisplayPerformanceMonitor.shared.dataVolumeProvider = { [weak model] in
            await model?.repo.dataVolumeSnapshot()
        }
        DisplayPerformanceMonitor.shared.start()
    }
}

/// The Health-Checklist status dot beside a short status line.
private struct StatusDot: View {
    let color: Color
    var body: some View {
        Circle().fill(color).frame(width: 8, height: 8).accessibilityHidden(true)
    }
}

/// The Sleep & Rest live-readout panel (Group E): HR density, gravity coverage, and the gate that
/// fired tonight, bound from the pure `SleepReadout` source over LiveState's live buffers + tagged log
/// tail. No hardcoded colours; uses the same tokens as the surrounding Test Centre rows.
private struct SleepReadoutPanel: View {
    @ObservedObject var live: LiveState

    var body: some View {
        let hrDensity = SleepReadout.hrDensityPerMinute(hr: live.recentHrSamples)
        let gravCoverage = SleepReadout.gravityCoverageFraction(gravity: live.recentGravitySamples, hr: live.recentHrSamples)
        let lastGate = SleepReadout.lastGateFired(taggedTail: live.taggedTail(domain: .sleep))
        ReadoutRow(label: String(localized: "HR density (per min)"),
                   value: live.recentHrSamples.isEmpty ? String(localized: "no live HR yet") : String(format: "%.1f", hrDensity))
        ReadoutRow(label: String(localized: "Gravity coverage"),
                   value: live.recentGravitySamples.isEmpty ? String(localized: "no live gravity yet") : String(format: "%.0f%%", gravCoverage * 100))
        ReadoutRow(label: String(localized: "Last gate fired"), value: lastGate ?? String(localized: "no night yet"))
    }
}

/// The Battery & Charging live-readout panel (Group F): current SoC, the "~X days left" estimate, and
/// whether the discharge slope is the user's own measured rate or the rated fallback. Bound from
/// LiveState.batteryReadout over the SAME banked SoC series the Today badge reads, so the panel never
/// diverges from the headline number. No hardcoded colours; uses the same ReadoutRow tokens as the Sleep
/// panel above. No em-dash in any string here.
private struct BatteryReadoutPanel: View {
    @ObservedObject var live: LiveState

    var body: some View {
        ReadoutRow(label: String(localized: "Current charge"), value: live.batteryReadout("currentSoc"))
        ReadoutRow(label: String(localized: "Estimated runtime left"), value: live.batteryReadout("estimateDaysLeft"))
        ReadoutRow(label: String(localized: "Slope source"), value: live.batteryReadout("slopeSource"))
    }
}

/// The Connection & Sync live-readout panel: connection uptime, the involuntary-reconnect count this run,
/// and the last offload result, all parsed from the `.connection`-tagged log tail (plus the live connection
/// state for the up/down headline) by the pure `ConnectionReadout`. Binding off the tagged tail mirrors the
/// Recovery / HRV panels, so the BLE layer needs no new published properties. No hardcoded colours; uses the
/// same ReadoutRow tokens as the other panels. No em-dash in any string here.
private struct ConnectionReadoutPanel: View {
    @ObservedObject var live: LiveState

    var body: some View {
        let tail = live.taggedTail(domain: .connection)
        let now = Int(Date().timeIntervalSince1970)
        // Trust the live link state for the up/down headline; fall back to the tagged tail for the
        // since-when. Once disconnected, the link state is the source of truth (the tail may still hold a
        // stale uptimeStart from the last connect).
        let uptime = live.connected
            ? ConnectionReadout.uptimeLabel(taggedTail: tail, nowUnix: now)
            : String(localized: "not connected")
        let reconnects = ConnectionReadout.reconnectCount(taggedTail: tail)
        let lastOffload = ConnectionReadout.lastOffloadResult(taggedTail: tail)
        // #990: rows drained this session (the running/final offload tally) BESIDE the persisted all-time
        // counter, so a strap stuck in a pull-restart loop still shows the install-lifetime progress the
        // per-session number keeps resetting away.
        let sessionRows = ConnectionReadout.sessionRows(taggedTail: tail)
        let allTimeRows = TestCentre.cumulativeDrainedRows()
        // #987: clock latch + frame liveness. The correlated device clock is parsed from the same log the
        // export ships (pure ConnectionReadout parsers), the last-frame stamp off the non-published
        // LiveState field FrameRouter writes.
        let deviceClock = ConnectionReadout.clockCorrelatedDevice(logLines: live.log)
        let rtcWarning = ConnectionReadout.rtcWarning(deviceClockUnix: deviceClock,
                                                      strapNewestUnix: live.strapRange?.newestUnix,
                                                      batteryPct: live.batterySamples.last?.soc)
        ReadoutRow(label: String(localized: "Connection uptime"), value: uptime)
        ReadoutRow(label: String(localized: "Reconnects this run"), value: String(reconnects))
        ReadoutRow(label: String(localized: "Last offload result"), value: lastOffload ?? String(localized: "no offload yet"))
        ReadoutRow(label: String(localized: "Rows drained (session)"),
                   value: sessionRows.map(String.init) ?? String(localized: "no offload yet"))
        ReadoutRow(label: String(localized: "Rows drained (all time)"), value: String(allTimeRows))
        ReadoutRow(label: String(localized: "Clock latched"),
                   value: ConnectionReadout.clockLatchedLabel(deviceClockUnix: deviceClock,
                                                              strapNewestUnix: live.strapRange?.newestUnix))
        ReadoutRow(label: String(localized: "Last frame"),
                   value: ConnectionReadout.lastFrameLabel(lastFrameUnix: live.lastFrameAtUnix, nowUnix: now))
        if let rtcWarning {
            // #987: the plain-words 1970/71 warning - amber, not a bare token, because this is the
            // single most common "no history" root cause and the fix is in the sentence.
            Text(rtcWarning)
                .font(StrandFont.caption)
                .foregroundStyle(StrandPalette.statusWarning)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityLabel(rtcWarning)
        }
    }
}

/// The Recovery (Charge) live-readout panel (Group G): the last Charge term-breakdown from the
/// `.recovery`-tagged log tail (the score + band, or the nil reason when a night could not be scored).
/// Bound from the pure `TestReadout.lastChargeBreakdown`, parsed from the SAME tagged lines the Recovery
/// emitter writes, so the panel never diverges from the headline Charge number. No hardcoded colours;
/// uses the same ReadoutRow tokens as the Sleep / Battery panels. No em-dash in any string here.
private struct RecoveryReadoutPanel: View {
    @ObservedObject var live: LiveState

    var body: some View {
        let last = TestReadout.lastChargeBreakdown(taggedTail: live.taggedTail(domain: .recovery))
        ReadoutRow(label: String(localized: "Last Charge breakdown"), value: last ?? String(localized: "no night scored yet"))
    }
}

/// The HRV & Autonomic live-readout panel (Group G): the last HRV computation from the `.hrv`-tagged log
/// tail (RMSSD / SDNN, or "no reading" when the cleaning gates filtered the capture out). Bound from the
/// pure `TestReadout.lastHrvComputation`, parsed from the SAME tagged lines the HRV emitter writes, so the
/// panel reads the same outcome the snapshot screen showed. No hardcoded colours. No em-dash here.
private struct HrvReadoutPanel: View {
    @ObservedObject var live: LiveState

    var body: some View {
        let last = TestReadout.lastHrvComputation(taggedTail: live.taggedTail(domain: .hrv))
        ReadoutRow(label: String(localized: "Last HRV reading"), value: last ?? String(localized: "no reading yet"))
    }
}

/// The Steps live-readout panel: today's steps and the calibration state, parsed from the `.steps`-tagged
/// log tail the Steps test-mode emitters write (the WHOOP-4 calibration / estimate lines and the 5/MG raw
/// scaledSteps), by the pure `StepsReadout`. Binding off the tagged tail mirrors the Recovery / HRV panels,
/// so the analytics layer needs no new published properties. No hardcoded colours; uses the same ReadoutRow
/// tokens as the other panels. No em-dash in any string here.
private struct StepsReadoutPanel: View {
    @ObservedObject var live: LiveState

    var body: some View {
        let tail = live.taggedTail(domain: .steps)
        let steps = StepsReadout.stepsToday(taggedTail: tail)
        let calState = StepsReadout.calibrationState(taggedTail: tail)
        ReadoutRow(label: String(localized: "Steps today"), value: steps.map(String.init) ?? String(localized: "no estimate yet"))
        ReadoutRow(label: String(localized: "Calibration"), value: calState ?? String(localized: "no calibration yet"))
    }
}

/// The Workouts & GPS live-readout panel: the last session summary (event + sport + counts), parsed from the
/// `.workouts`-tagged log tail the session-lifecycle emitter writes, by the pure `WorkoutsReadout`. Binding
/// off the tagged tail mirrors the Recovery / HRV / Steps panels, so the app layer needs no new published
/// properties. No hardcoded colours; uses the same ReadoutRow tokens as the other panels. No em-dash here.
private struct WorkoutsReadoutPanel: View {
    @ObservedObject var live: LiveState

    var body: some View {
        let summary = WorkoutsReadout.lastSessionSummary(taggedTail: live.taggedTail(domain: .workouts))
        ReadoutRow(label: String(localized: "Last session"), value: summary ?? String(localized: "no session yet"))
    }
}

/// The Import & Data Ingest live-readout panel: the last import summary (parser source + version, and the
/// most recent per-stage and day-delta fragment), parsed from the `.dataImport`-tagged log tail the import
/// emitters write, by the pure `ImportReadout`. Binding off the tagged tail mirrors the other app-level
/// panels, so the import layer needs no new published properties. No hardcoded colours; uses the same
/// ReadoutRow tokens as the other panels. No em-dash in any string here.
private struct ImportReadoutPanel: View {
    @ObservedObject var live: LiveState

    var body: some View {
        let summary = ImportReadout.lastImportSummary(taggedTail: live.taggedTail(domain: .dataImport))
        ReadoutRow(label: String(localized: "Last import"), value: summary ?? String(localized: "no import yet"))
    }
}

/// The Display & Performance live-readout panel: the device-metrics summary (size / size-class / Dynamic
/// Type / orientation / theme) and the latest frame-time summary, parsed from the `.display`-tagged log
/// tail the device-metrics + frame-monitor emitters write, by the pure `DisplayReadout`. Binding off the
/// tagged tail mirrors the other app-level panels, so the monitor needs no new published property. No
/// hardcoded colours; uses the same ReadoutRow tokens as the other panels. No em-dash in any string here.
private struct DisplayReadoutPanel: View {
    @ObservedObject var live: LiveState

    var body: some View {
        let tail = live.taggedTail(domain: .display)
        let metrics = DisplayReadout.deviceMetricsNow(taggedTail: tail)
        let frames = DisplayReadout.frameSummaryNow(taggedTail: tail)
        ReadoutRow(label: String(localized: "Device metrics"), value: metrics ?? String(localized: "reading…"))
        ReadoutRow(label: String(localized: "Frame summary"), value: frames ?? String(localized: "no window yet"))
    }
}

/// A compact key/value readout row for the Test Centre live panels (Group E/F/G): the label on the left,
/// the grey value on the right, as a Settings read-only row.
private struct ReadoutRow: View {
    let label: String
    let value: String
    var body: some View {
        LabeledContent(label, value: value)
    }
}

/// The mandatory review-before-share sheet (spec sections 9 and 12): shows the exact redacted report.txt
/// the user is about to share, with ✕ (cancel) and Share. Nothing leaves the device until Share.
private struct ReportReviewSheet: View {
    @ObservedObject var report: TestCentreReport
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let preview = report.pending?.gate.previewText ?? ""
        return NavigationStack {
            Form {
                if report.pending?.modeInactive == true {
                    // #1002: the selected profile's test mode is not on, so this bundle carries no capture
                    // for the very thing being reported. Warn, with the fix, BEFORE the user ships it.
                    Section {
                        NoticeCard(title: Text("This test mode is off"),
                                   message: Text("Turn it on, reproduce the problem, then report again."),
                                   systemImage: "exclamationmark.triangle.fill", tone: .warning)
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(Color.clear)
                    }
                }
                Section {
                    ScrollView {
                        Text(preview.isEmpty ? String(localized: "(nothing to share yet)") : preview)
                            .font(StrandFont.mono)
                            .foregroundStyle(StrandPalette.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                    #if os(iOS)
                    .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
                    #endif
                    .frame(maxHeight: 360)
                } footer: {
                    Text("This is exactly what your report will contain. Nothing leaves \(Platform.deviceNounPhrase) until you tap Share.")
                }
            }
            .settingsPage("Review before sharing")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    SheetCloseButton { report.cancel(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        report.confirm(); dismiss()
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .tint(StrandPalette.settingsBlue)
                    .accessibilityLabel(Text("Share"))
                }
            }
        }
    }
}
