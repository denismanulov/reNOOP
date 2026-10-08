import SwiftUI
import StrandDesign

/// Coach settings (#2243), a sheet with ✕ as Health and Fitness draw theirs: the connection (provider,
/// server, model, key), what the coach may read, its instructions, the morning brief, and the two ways
/// to end a conversation.
///
/// The provider can only be chosen while nothing is connected. A stored key records which provider it
/// belongs to and is never sent anywhere else, so switching provider under a key would leave a key that
/// cannot be used; Disconnect is the way to change it (#2206 is the record of what happens when that
/// control is put somewhere a presentation does not render — a sheet renders in all three).
struct CoachSettingsView: View {
    @EnvironmentObject var coach: AICoachEngine
    @Environment(\.dismiss) private var dismiss

    /// Pending key text (never persisted here, handed to `setKey`). Also the repair for a rejected key:
    /// `setKey` replaces the stored key and keeps the transcript.
    @State private var keyDraft = ""
    /// Whether the model picker is in free-text "Custom…" mode, and the id typed there.
    @State private var customModel = false
    @State private var customModelDraft = ""
    @State private var showClearConfirm = false
    @State private var showDisconnectConfirm = false

    /// Morning-brief settings, read from `CoachBriefScheduler` on init.
    @State private var briefEnabled: Bool = CoachBriefScheduler.isEnabled
    @State private var briefMinutes: Int = CoachBriefScheduler.timeMinutes
    @State private var briefGenerating = false
    @State private var briefStatus: String?

    private let customModelTag = "__custom__"

    var body: some View {
        NavigationStack {
            Form {
                connectionSection
                if coach.isConfigured {
                    dataSection
                    Section {
                        NavigationLink {
                            CoachInstructionsPage()
                        } label: {
                            LabeledContent("Instructions") {
                                Text(coach.hasCustomSystemPrompt ? "Custom" : "Default")
                            }
                        }
                    }
                    briefSection
                    Section {
                        Button("Clear Conversation", role: .destructive) { showClearConfirm = true }
                            .disabled(coach.messages.isEmpty)
                        Button("Disconnect", role: .destructive) { showDisconnectConfirm = true }
                            .confirmationDialog("Disconnect \(coach.provider.displayName)?",
                                                isPresented: $showDisconnectConfirm, titleVisibility: .visible) {
                                Button("Disconnect", role: .destructive) {
                                    coach.disconnect()
                                    keyDraft = ""
                                }
                            }
                    }
                }
            }
            .settingsForm()
            .navigationTitle(Text("Coach"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    SheetCloseButton { dismiss() }
                }
            }
            .confirmationDialog("Clear conversation?", isPresented: $showClearConfirm, titleVisibility: .visible) {
                Button("Clear", role: .destructive) { coach.clearConversation() }
                Button("Cancel", role: .cancel) {}
            }
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 560)
        #endif
        // A stale model catalogue is worth refreshing when the picker is about to be read. Rate-limited,
        // silent on failure, and a no-op without a key.
        .task { await coach.refreshModelsIfStale() }
    }

    // MARK: - Connection

    @ViewBuilder
    private var connectionSection: some View {
        Section {
            if coach.isConfigured {
                LabeledContent("Provider", value: coach.provider.displayName)
            } else {
                Picker("Provider", selection: $coach.provider) {
                    ForEach(AIProvider.allCases) { Text($0.displayName).tag($0) }
                }
            }
            if coach.provider == .custom {
                LabeledContent("Server URL") {
                    TextField("Server URL", text: $coach.customBaseURL, prompt: Text(verbatim: "http://localhost:11434/v1"))
                        .labelsHidden()
                        .multilineTextAlignment(.trailing)
                        .disableAutocorrection(true)
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        #endif
                }
                // How the key is sent is a server detail most readers never change.
                DisclosureGroup("Advanced") {
                    Picker("Key Header", selection: $coach.customAuthHeader) {
                        ForEach(CustomAIAuthHeader.allCases) { Text($0.displayName).tag($0) }
                    }
                }
            }
            Picker("Model", selection: modelPickerSelection) {
                ForEach(coach.availableModels, id: \.self) { Text($0).tag($0) }
                Text("Custom…").tag(customModelTag)
            }
            if customModel {
                TextField("Model ID", text: $customModelDraft)
                    .disableAutocorrection(true)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .onSubmit(applyCustomModel)
            }
            SecureField(coach.provider == .custom ? "API Key (optional)" : "API Key", text: $keyDraft)
                .onSubmit(commit)
            // Return saves the key; the row appears once there is a key to save, so an empty form never
            // shows a greyed button that reads as another field.
            if !keyDraftIsEmpty && !(coach.provider == .custom && !coach.isConfigured) {
                Button(coach.hasKey ? "Update Key" : "Save Key", action: commit)
            }
        } footer: {
            VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                // The field empties on save and the stored key is never shown again, so without this
                // line a saved key and no key look the same.
                if Self.showsKeySavedLine(hasKey: coach.hasKey, keyDraft: keyDraft) {
                    Text("A key is saved. Enter a new one to replace it.")
                }
                // Setup failures only: once connected, the conversation shows a failed send under itself.
                if !coach.isConfigured, let error = coach.errorText, !error.isEmpty {
                    Text(error).foregroundStyle(StrandPalette.settingsRed)
                }
            }
        }
        if coach.provider == .custom && !coach.isConfigured {
            Section {
                Button("Connect", action: commit)
                    .disabled(coach.customBaseURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        } else if coach.provider != .custom {
            Section {
                Button("Refresh Models") { Task { await coach.refreshModels() } }
                    .disabled(!coach.hasKey)
            }
        }
    }

    private var keyDraftIsEmpty: Bool { Self.isBlank(keyDraft) }

    private static func isBlank(_ draft: String) -> Bool {
        draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Whether the key field carries its "a key is saved" line: a key is stored and nothing is typed.
    /// The same emptiness test as the Save/Update row's, so exactly one of the two shows under a stored
    /// key. Twin of the `supporting` line on Android's key field.
    static func showsKeySavedLine(hasKey: Bool, keyDraft: String) -> Bool {
        hasKey && isBlank(keyDraft)
    }

    /// Bridges the model Picker to `coach.model`, with a "Custom…" sentinel that opens the free-text
    /// field instead of selecting a real id.
    private var modelPickerSelection: Binding<String> {
        Binding(
            get: { customModel ? customModelTag : coach.model },
            set: { newValue in
                if newValue == customModelTag {
                    customModel = true
                    if customModelDraft.isEmpty { customModelDraft = coach.model }
                } else {
                    customModel = false
                    coach.model = newValue
                }
            }
        )
    }

    private func applyCustomModel() {
        let trimmed = customModelDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        coach.setCustomModel(trimmed)
        customModel = false
    }

    /// Save the key (cloud) or connect the server (custom, the key optional there). The first successful
    /// connection closes the sheet onto the conversation.
    private func commit() {
        if customModel { applyCustomModel() }
        let wasConfigured = coach.isConfigured
        let trimmed = keyDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            coach.setKey(trimmed)
            keyDraft = ""
        }
        if coach.provider == .custom && !coach.customConnected { coach.connectCustom() }
        if !wasConfigured && coach.isConfigured { dismiss() }
    }

    // MARK: - Data

    /// Explicit, revocable permission for the coach to read and send the user's data (off by default),
    /// and the two opt-ins that depend on it.
    private var dataSection: some View {
        Section {
            Toggle("Use My Data", isOn: $coach.dataConsent)
            if coach.dataConsent {
                // v5: summaries of the strongest patterns + Lab Book, never raw readings.
                Toggle("Patterns and Lab Book", isOn: $coach.includeOnDeviceSignals)
                // K11: a chart image alongside the text, Gemini only.
                if coach.provider == .gemini {
                    Toggle("Chart Image", isOn: $coach.multimodalChartEnabled)
                }
            }
        } footer: {
            // The only place this is agreed to, so it names who receives what, whether on or off (#2033, CR-10).
            Text("\(coach.provider.displayName) receives Charge, sleep, HRV and workouts with sport, duration, distance and heart rate when you ask.")
        }
    }

    // MARK: - Morning brief

    /// K5: the scheduled morning-brief notification — toggle, time, and an explicit "Generate Now".
    private var briefSection: some View {
        Section {
            Toggle("Morning Brief", isOn: $briefEnabled)
                .onChangeCompat(of: briefEnabled) { on in
                    CoachBriefScheduler.setEnabled(on, generateBrief: { await coach.generateBrief() }) { outcome in
                        if outcome == .denied {
                            briefEnabled = false
                            briefStatus = String(localized: "Notifications are off for reNOOP.")
                        }
                    }
                }
            if briefEnabled {
                DatePicker("Time", selection: briefTimeBinding, displayedComponents: .hourAndMinute)
                Button {
                    generateBriefNow()
                } label: {
                    HStack {
                        Text("Generate Now")
                        if briefGenerating {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(briefGenerating)
            }
        } footer: {
            if let briefStatus { Text(briefStatus) }
        }
    }

    private var briefTimeBinding: Binding<Date> {
        Binding(
            get: {
                var c = DateComponents()
                c.hour = briefMinutes / 60
                c.minute = briefMinutes % 60
                return Calendar.current.date(from: c) ?? Date()
            },
            set: { date in
                let c = Calendar.current.dateComponents([.hour, .minute], from: date)
                let m = (c.hour ?? 7) * 60 + (c.minute ?? 0)
                briefMinutes = m
                CoachBriefScheduler.setTimeMinutes(m, generateBrief: { await coach.generateBrief() })
            }
        )
    }

    private func generateBriefNow() {
        Task {
            briefGenerating = true
            briefStatus = nil
            defer { briefGenerating = false }
            let text = await CoachBriefScheduler.generateNow { await coach.generateBrief() }
            if let text {
                coach.appendGeneratedBrief(text)
            } else {
                briefStatus = String(localized: "Couldn't generate a brief. Check the key and data access.")
            }
        }
    }
}

/// The instructions that frame every reply, edited in place (they persist in the engine and apply from
/// the next message), with Reset in the bar.
private struct CoachInstructionsPage: View {
    @EnvironmentObject var coach: AICoachEngine
    @State private var draft = ""

    var body: some View {
        Form {
            Section {
                TextEditor(text: $draft)
                    .font(StrandFont.pro(15))
                    .frame(minHeight: 320)
                    .onChangeCompat(of: draft) { coach.customSystemPrompt = $0 }
                    .accessibilityLabel(Text("Instructions"))
            }
        }
        .settingsPage("Instructions")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Reset") {
                    coach.resetSystemPrompt()
                    draft = coach.customSystemPrompt
                }
                .disabled(!coach.hasCustomSystemPrompt)
            }
        }
        .onAppear { draft = coach.customSystemPrompt }
    }
}
