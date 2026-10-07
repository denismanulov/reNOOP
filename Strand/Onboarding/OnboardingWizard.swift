import SwiftUI
import UniformTypeIdentifiers
import CoreBluetooth
import StrandDesign
import WhoopStore

// MARK: - OnboardingWizard
//
// First run, laid out as iPhone and Apple Watch setup on iOS 26 (OnBoardingKit's welcome page): a picture
// at the top, a bold title with one sentence under it, whatever the step needs, and the buttons in a tray
// at the bottom. Steps push onto a navigation stack, so they slide in and go back exactly as setup does,
// under the system's glass ‹.
//
//  1 Welcome
//  · Other strap apps    — only when NOOP / WHOOP is installed beside reNOOP: two apps split the history
//  2 Find your strap     — wear it, pick the model, Scan; turns into "Connected" once the strap bonds
//  3 About you           — date of birth / sex / units / weight / height; only answered rows reach ProfileStore
//  4 Your history        — optional WHOOP / Apple Health import; Done → onFinished()
//
// Notification permission is left to the features that need it (their toggles ask on their own). The legal
// gate and the keys written on finish stay with the host; this view only calls onFinished() when complete.

public struct OnboardingWizard: View {

    /// Called when the user finishes onboarding.
    public var onFinished: () -> Void

    public init(onFinished: @escaping () -> Void) {
        self.onFinished = onFinished
    }

    /// Opens on a later step (index 0…4), with the steps before it behind the back button (the DEBUG screenshot
    /// harness). The conditional other-apps step is only in the path when it is the one asked for.
    init(onFinished: @escaping () -> Void, startAt index: Int) {
        self.onFinished = onFinished
        let last = Step(rawValue: min(max(index, 0), Step.allCases.count - 1)) ?? .welcome
        _path = State(initialValue: Step.allCases.filter {
            $0 != .welcome && $0.rawValue <= last.rawValue && ($0 != .otherApps || $0 == last)
        })
    }

    // NOTE: the root deliberately does NOT observe the fast-updating model/live/profile env objects —
    // doing so re-rendered the whole wizard on every HR tick. Child steps observe what they need.

    fileprivate enum Step: Int, CaseIterable, Hashable {
        case welcome, otherApps, scan, profile, importData
    }

    /// The steps pushed over Welcome; the last one is on screen.
    @State private var path: [Step] = []
    /// Which About You rows the user has answered, kept here so going back and forth doesn't reset them.
    @State private var profileAnswers = ProfileAnswers()

    public var body: some View {
        NavigationStack(path: $path) {
            page(.welcome)
                .navigationDestination(for: Step.self) { page($0) }
        }
        .tint(StrandPalette.settingsBlue)
    }

    @ViewBuilder private func page(_ step: Step) -> some View {
        let next = { push(after: step) }
        Group {
            switch step {
            case .welcome:    WelcomeStep(next: next)
            case .otherApps:  OtherAppsStep(next: next)
            case .scan:       ScanStep(next: next)
            case .profile:    ProfileStep(answers: $profileAnswers, next: next)
            case .importData: ImportStep(next: next)
            }
        }
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    // MARK: Navigation

    /// The next step, or finish after the last one. The other-apps step is skipped when no other strap app
    /// is installed: most people never see it.
    @MainActor
    private func push(after step: Step) {
        guard var next = Step(rawValue: step.rawValue + 1) else { onFinished(); return }
        if next == .otherApps, OtherStrapApps.installed().isEmpty { next = .scan }
        path.append(next)
    }
}

// MARK: - Page

/// One setup page, measured off OnBoardingKit's welcome page on iOS 26: the glyph in an 82 pt slot 28 pt
/// under the bar, 38 pt margins, a 22 pt bold title and a 22 pt secondary sentence flush left, the step's
/// own content, and a tray of 52 pt capsules whose last button ends 38 pt above the bottom edge. Shared with
/// the Terms gate, which opens before this wizard.
struct SetupPage<Art: View, Content: View, Tray: View>: View {
    /// The first page has no bar above it, so its glyph starts lower by the bar's height.
    var isFirst = false
    let title: String
    let message: String
    @ViewBuilder var art: () -> Art
    @ViewBuilder var content: () -> Content
    @ViewBuilder var tray: () -> Tray

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                art()
                    .frame(maxWidth: .infinity)
                    .frame(height: 82)
                    .padding(.top, isFirst ? SetupMetrics.firstPageTop : 28)
                    .padding(.bottom, 40)
                Text(verbatim: title)
                    .font(StrandFont.pro(22, weight: .bold))
                    .foregroundStyle(StrandPalette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, SetupMetrics.margin)
                Text(verbatim: message)
                    .font(StrandFont.pro(22))
                    .lineSpacing(2)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, SetupMetrics.margin)
                content()
                    .padding(.top, 40)
                    .padding(.horizontal, SetupMetrics.margin)
            }
            .frame(maxWidth: SetupMetrics.column, alignment: .leading)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 24)
        }
        #if os(iOS)
        .scrollBounceBehavior(.basedOnSize)
        #endif
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 10) { tray() }
                .padding(.horizontal, SetupMetrics.margin)
                .padding(.top, 24)
                .padding(.bottom, SetupMetrics.trayBottom)
                .frame(maxWidth: SetupMetrics.column)
                .frame(maxWidth: .infinity)
                .background(StrandPalette.plainPage)
        }
        .background(StrandPalette.plainPage.ignoresSafeArea())
    }
}

extension SetupPage where Content == EmptyView {
    init(isFirst: Bool = false, title: String, message: String,
         @ViewBuilder art: @escaping () -> Art, @ViewBuilder tray: @escaping () -> Tray) {
        self.init(isFirst: isFirst, title: title, message: message, art: art, content: { EmptyView() }, tray: tray)
    }
}

enum SetupMetrics {
    static let margin: CGFloat = 38
    /// The widest a page runs (a Mac window, an iPad): an iPhone's width.
    static let column: CGFloat = 480
    #if os(iOS)
    /// Above the home indicator's safe area: 4 + 34 = 38 pt from the edge.
    static let trayBottom: CGFloat = 4
    /// 28 pt under a 54 pt navigation bar the first page doesn't have.
    static let firstPageTop: CGFloat = 82
    #else
    static let trayBottom: CGFloat = 24
    static let firstPageTop: CGFloat = 28
    #endif
}

/// The tray's buttons: a blue prominent-glass capsule for the step's action, a plain glass one beside it.
struct SetupButton: View {
    let title: LocalizedStringKey
    var prominent = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(StrandFont.pro(17, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 24)
        }
        .setupButtonStyle(prominent: prominent)
    }
}

extension View {
    @ViewBuilder
    fileprivate func setupButtonStyle(prominent: Bool) -> some View {
        let styled = self
            .controlSize(.large)
            .tint(prominent ? StrandPalette.settingsBlue : StrandPalette.textPrimary)
        #if compiler(>=6.2)
        if #available(iOS 26.0, macOS 26.0, *) {
            if prominent { styled.buttonStyle(.glassProminent) } else { styled.buttonStyle(.glass) }
        } else {
            styled.setupButtonFallback(prominent: prominent)
        }
        #else
        styled.setupButtonFallback(prominent: prominent)
        #endif
    }

    @ViewBuilder
    fileprivate func setupButtonFallback(prominent: Bool) -> some View {
        #if os(iOS)
        if prominent {
            self.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
        } else {
            self.buttonStyle(.bordered).buttonBorderShape(.capsule)
        }
        #else
        if prominent { self.buttonStyle(.borderedProminent) } else { self.buttonStyle(.bordered) }
        #endif
    }
}

/// A grouped card of rows, as the setup screens list their choices (Apps & Data): 26 pt corners on the
/// grouped fill, hairlines inset to the text.
struct SetupCard<Rows: View>: View {
    @ViewBuilder var rows: () -> Rows

    var body: some View {
        VStack(spacing: 0) { rows() }
            .background(StrandPalette.plainPageCard, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
    }
}

struct SetupDivider: View {
    var inset: CGFloat = 16

    var body: some View {
        Rectangle()
            .fill(StrandPalette.hairline)
            .frame(height: NoopMetrics.hairlineWidth)
            .padding(.leading, inset)
            .padding(.trailing, 16)
    }
}

/// A row in a setup card: the label left, its value or a chevron right.
struct SetupRow<Leading: View, Trailing: View>: View {
    /// Puts the trailing value under the title at accessibility sizes, as Settings does.
    var stacks = true
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var trailing: () -> Trailing
    @Environment(\.dynamicTypeSize) private var dts

    init(stacks: Bool = true, @ViewBuilder leading: @escaping () -> Leading,
         @ViewBuilder trailing: @escaping () -> Trailing) {
        self.stacks = stacks
        self.leading = leading
        self.trailing = trailing
    }

    var body: some View {
        let stacked = stacks && dts.isAccessibilitySize
        let layout = stacked ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                             : AnyLayout(HStackLayout(spacing: 12))
        layout {
            HStack(spacing: 12) { leading() }
            if !stacked { Spacer(minLength: 8) }
            trailing()
        }
        .font(StrandFont.pro(17))
        .foregroundStyle(StrandPalette.textPrimary)
        .padding(.horizontal, 16)
        .padding(.vertical, stacked ? 10 : 0)
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// The strap the user picked (shared with the Live screen through `selectedWhoopModel`), as its glyph.
private struct SelectedStrapArt: View {
    @AppStorage("selectedWhoopModel") private var selectedModelRaw = WhoopModel.whoop4.rawValue

    var body: some View {
        DeviceArtwork(kind: .whoop(registryModel: selectedModelRaw), size: 82)
    }
}

// MARK: - 1 · Welcome

private struct WelcomeStep: View {
    let next: () -> Void

    var body: some View {
        SetupPage(isFirst: true,
                  title: String(localized: "Welcome to reNOOP"),
                  message: String(localized: "Your strap's data, kept only on \(Platform.deviceNounPhrase)."),
                  art: { BrandMark(size: 82) },
                  tray: { SetupButton(title: "Get Started", action: next) })
    }
}

// MARK: - 1½ · Other strap apps

/// Shown only when another app that syncs WHOOP straps is installed (`OtherStrapApps`). The strap keeps one
/// history queue and drops each chunk as soon as any app acks it, so two apps on one strap each end up with
/// holes — on 2026-09-30 a whole night went to upstream NOOP and never reached reNOOP. Continue is never
/// blocked: the choice is the user's, this step only makes sure it is made knowingly.
private struct OtherAppsStep: View {
    let next: () -> Void
    @State private var installed: [String] = []

    var body: some View {
        SetupPage(title: installed.isEmpty ? String(localized: "No Other Strap Apps")
                                           : String(localized: "One App per Strap"),
                  message: message,
                  art: {
                      SetupGlyph(systemName: installed.isEmpty ? "checkmark.circle" : "exclamationmark.triangle",
                                 tint: installed.isEmpty ? StrandPalette.settingsGreen : StrandPalette.settingsOrange)
                  }) {
            if !installed.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    SetupCard {
                        ForEach(Array(installed.enumerated()), id: \.element) { i, name in
                            if i > 0 { SetupDivider() }
                            SetupRow(stacks: false) {
                                Text(verbatim: name)
                            } trailing: {
                                Text("Installed")
                                    .foregroundStyle(StrandPalette.textSecondary)
                            }
                        }
                    }
                    // iOS tells an app whether another is INSTALLED, never what it may do: turning its
                    // Bluetooth off (the advice above) cannot change this card, and without this line
                    // "Check Again" reads as if the fix had not worked.
                    Text("reNOOP can see that it's installed, not its Bluetooth access. If you turned it off, just continue.")
                        .font(StrandFont.pro(13))
                        .foregroundStyle(StrandPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 16)
                }
            }
        } tray: {
            SetupButton(title: "Continue", action: next)
            if !installed.isEmpty {
                SetupButton(title: "Check Again", prominent: false) { refresh() }
            }
        }
        .onAppear { refresh() }
        #if os(iOS)
        // Back from Settings or the Home Screen after turning the other app off or deleting it.
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            refresh()
        }
        #endif
    }

    private var message: String {
        guard let apps = OtherStrapApps.phrase(installed) else {
            return String(localized: "No other strap app found on this iPhone.")
        }
        return String(localized: "\(apps) can also sync your strap, and each hour of its history goes to whichever app syncs first. Turn off Bluetooth for it in Settings or delete it.")
    }

    private func refresh() {
        withAnimation(StrandMotion.gentle) { installed = OtherStrapApps.installed() }
    }
}

// MARK: - 2 · Find your strap

private struct ScanStep: View {
    let next: () -> Void
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var live: LiveState

    @State private var scanning = false
    /// Set when a scan ran its calm beat without bonding.
    @State private var notFound = false
    /// The calm beat after a Scan tap; a new tap or leaving the step cancels it.
    @State private var timeout: Task<Void, Never>?
    /// Bluetooth access turned off for NOOP in Settings: say so rather than blame the strap.
    @State private var bluetoothDenied = false

    /// Which strap to look for — shared with the Live screen via the same key.
    @AppStorage("selectedWhoopModel") private var selectedModelRaw = WhoopModel.whoop4.rawValue
    private var selectedModel: WhoopModel { WhoopModel(rawValue: selectedModelRaw) ?? .whoop4 }

    var body: some View {
        SetupPage(title: live.bonded ? String(localized: "Connected") : String(localized: "Find Your Strap"),
                  message: message,
                  art: { art }) {
            VStack(alignment: .leading, spacing: 24) {
                if !live.bonded {
                    SetupCard {
                        ForEach(Array(WhoopModel.allCases.enumerated()), id: \.element) { i, strap in
                            if i > 0 { SetupDivider() }
                            Button { restartScan(for: strap) } label: {
                                SetupRow(stacks: false) {
                                    Text(verbatim: strap.displayName)
                                } trailing: {
                                    Image(systemName: "checkmark")
                                        .font(StrandFont.pro(17, weight: .semibold))
                                        .foregroundStyle(StrandPalette.settingsBlue)
                                        .opacity(strap == selectedModel ? 1 : 0)
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(strap == selectedModel ? .isSelected : [])
                        }
                    }
                }
                status
            }
        } tray: {
            if live.bonded {
                SetupButton(title: "Continue", action: next)
            } else {
                #if os(iOS)
                if bluetoothDenied {
                    SetupButton(title: "Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                } else {
                    scanButton
                }
                #else
                scanButton
                #endif
                // WHOOP leads, but isn't required: other straps and imports live under Devices.
                SetupButton(title: "Set Up Later", prominent: false, action: next)
            }
        }
        .onAppear { refreshBluetoothAccess() }
        #if os(iOS)
        // Back from Settings (or the system's Bluetooth prompt).
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            refreshBluetoothAccess()
        }
        #endif
        .onDisappear {
            timeout?.cancel()
            timeout = nil
            scanning = false
        }
    }

    private var scanButton: some View {
        SetupButton(title: "Scan") { startScan() }
            .disabled(scanning)
    }

    /// The picked strap's glyph; a green check once it bonds.
    @ViewBuilder private var art: some View {
        if live.bonded {
            SetupGlyph(systemName: "checkmark.circle", tint: StrandPalette.settingsGreen)
        } else {
            SelectedStrapArt()
        }
    }

    /// "Searching…" beside a spinner while a scan runs, as Quickly Set Up looks for nearby devices.
    @ViewBuilder private var status: some View {
        if !live.bonded && !bluetoothDenied && (scanning || live.connected) {
            HStack(spacing: 10) {
                ProgressView()
                Text(live.connected ? "Connecting…" : "Searching…")
                    .font(StrandFont.pro(22))
                    .foregroundStyle(StrandPalette.textSecondary)
            }
        }
    }

    /// One sentence for where the search stands.
    private var message: String {
        if live.bonded {
            // `ForeignOffloadDetector` saw another app pull this strap's history while we are connected.
            if live.otherAppSyncingAt != nil {
                return String(localized: "Your strap is bonded, but another app is syncing it too. Keep only one.")
            }
            if let pct = live.batteryPct { return String(localized: "Your strap is bonded · \(Int(pct))% battery.") }
            return String(localized: "Your strap is bonded and ready to stream.")
        }
        if bluetoothDenied {
            return String(localized: "Bluetooth is off for reNOOP.")
        }
        if notFound {
            // #130: 5.0/MG bonds to one host at a time, so the WHOOP app holding it hides it from a scan.
            return selectedModel == .whoop5mg
                ? String(localized: "Not found. Unpair it in the WHOOP app, close that app and try again.")
                : String(localized: "Not found. Wear it, charge it and close the WHOOP app.")
        }
        return String(localized: "Wear it snug on your wrist or bicep, sensor against skin.")
    }

    /// Reads the app's Bluetooth authorization; it never asks for it.
    private func refreshBluetoothAccess() {
        bluetoothDenied = CBManager.authorization == .denied
    }

    private func startScan(model scanModel: WhoopModel? = nil) {
        let modelToScan = scanModel ?? selectedModel
        timeout?.cancel()
        scanning = true
        notFound = false
        model.scan(model: modelToScan)
        // After a calm beat without a bond, say what usually helps.
        timeout = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 12_000_000_000)
            guard !Task.isCancelled else { return }
            timeout = nil
            refreshBluetoothAccess()
            if !live.bonded {
                scanning = false
                withAnimation(StrandMotion.gentle) { notFound = true }
            }
        }
    }

    private func restartScan(for newModel: WhoopModel) {
        selectedModelRaw = newModel.rawValue
        guard !live.bonded else { return }
        model.disconnect()
        startScan(model: newModel)
    }
}

// MARK: - 3 · About you

/// The About You rows the user has answered. A row nobody touched shows "Not Set" and is never written.
fileprivate struct ProfileAnswers {
    var birth = false
    var height = false
    var weight = false
    /// Written on Continue, so picking Not Set again leaves the profile as it was.
    var sex: String?
}

/// Health's details at first setup: the avatar, then plain rows with the value on the right; a row opens
/// its wheel in place. Every row starts Not Set.
private struct ProfileStep: View {
    @Binding var answers: ProfileAnswers
    let next: () -> Void
    @EnvironmentObject private var profile: ProfileStore

    // The stored profile is always SI. Body measurements and exercise distance can follow the regional
    // conventions independently; an unset distance choice follows the body choice for compatibility.
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    @AppStorage(UnitPrefs.distanceSystemKey) private var distanceSystemRaw = ""
    private var unitSystem: UnitSystem { UnitSystem(rawValue: unitSystemRaw) ?? .metric }
    private var distanceUnitSystem: UnitSystem {
        UnitPrefs.resolveDistance(system: unitSystem, override: distanceSystemRaw)
    }
    private var distanceSystemBinding: Binding<String> {
        Binding(get: { distanceUnitSystem.rawValue }, set: { distanceSystemRaw = $0 })
    }

    /// The row whose wheel is open, one at a time.
    @State private var open: Field?
    private enum Field { case birth, sex, weight, height }

    private let sexes: [(String, String)] = [
        ("female", String(localized: "Female")), ("male", String(localized: "Male")),
        ("nonbinary", String(localized: "Other"))
    ]

    var body: some View {
        let imperial = unitSystem == .imperial
        SetupPage(title: String(localized: "About You"),
                  message: String(localized: "For your zones, calories and baselines."),
                  art: { SummaryAvatar(imageData: profile.avatarImageData, initials: profile.initials, size: 82) }) {
            VStack(spacing: 24) {
                SetupCard {
                    // #146: a date of birth, so age advances on its own instead of going stale.
                    wheelRow(.birth, "Date of Birth",
                             value: answers.birth
                                ? profile.dateOfBirth.formatted(.dateTime.day().month(.abbreviated).year()) : nil) {
                        DatePicker("Date of Birth", selection: $profile.dateOfBirth,
                                   in: ProfileStore.dateOfBirthRange, displayedComponents: .date)
                    }
                    SetupDivider()
                    wheelRow(.sex, "Sex", value: answers.sex.flatMap { key in sexes.first { $0.0 == key }?.1 }) {
                        Picker("Sex", selection: $answers.sex) {
                            Text("Not Set").tag(String?.none)
                            ForEach(sexes, id: \.0) { key, label in Text(label).tag(String?.some(key)) }
                        }
                    }
                    SetupDivider()
                    wheelRow(.height, "Height",
                             value: answers.height ? ProfileHeightPicker.text(cm: profile.heightCm, imperial: imperial) : nil) {
                        ProfileHeightPicker(imperial: imperial)
                    }
                    SetupDivider()
                    wheelRow(.weight, "Weight",
                             value: answers.weight ? ProfileWeightPicker.text(kg: profile.weightKg, imperial: imperial) : nil) {
                        ProfileWeightPicker(imperial: imperial)
                    }
                }
                // Two explicit choices: "Metric/Imperial" alone cannot describe mixed conventions such as
                // Canadian pounds with kilometres.
                SetupCard {
                    menuRow("Body Measurements", selection: $unitSystemRaw) {
                        Text("Metric").tag(UnitSystem.metric.rawValue)
                        Text("Imperial").tag(UnitSystem.imperial.rawValue)
                    }
                    SetupDivider()
                    menuRow("Distance", selection: distanceSystemBinding) {
                        Text("Kilometres").tag(UnitSystem.metric.rawValue)
                        Text("Miles").tag(UnitSystem.imperial.rawValue)
                    }
                }
            }
        } tray: {
            SetupButton(title: "Continue") {
                if let sex = answers.sex { profile.sex = sex }
                next()
            }
        }
    }

    /// Opening a Not Set wheel answers it with the value the wheel shows, as Health Details does; the wheels
    /// write straight to the profile from then on. Sex has a Not Set choice of its own, so it waits for
    /// Continue.
    private func answer(_ field: Field) {
        switch field {
        case .birth where !answers.birth:
            answers.birth = true
            profile.dateOfBirth = profile.dateOfBirth
        case .height where !answers.height:
            answers.height = true
            profile.heightCm = profile.heightCm
        case .weight where !answers.weight:
            answers.weight = true
            profile.weightKg = profile.weightKg
        default:
            break
        }
    }

    /// A value row that opens its wheel underneath on iOS; a Mac shows the control in the row once answered.
    @ViewBuilder
    private func wheelRow<P: View>(_ field: Field, _ title: LocalizedStringKey, value: String?,
                                   @ViewBuilder picker: () -> P) -> some View {
        #if os(iOS)
        Button {
            withAnimation(.easeInOut(duration: 0.25)) {
                if open != field { answer(field) }
                open = open == field ? nil : field
            }
        } label: {
            SetupRow {
                Text(title)
            } trailing: {
                valueText(value)
                    .foregroundStyle(open == field ? StrandPalette.settingsBlue : StrandPalette.textSecondary)
            }
        }
        .buttonStyle(.plain)
        if open == field {
            picker()
                .labelsHidden()
                .pickerStyle(.wheel)
                .datePickerStyle(.wheel)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 16)
        }
        #else
        if value == nil && field != .sex {
            Button { answer(field) } label: {
                SetupRow {
                    Text(title)
                } trailing: {
                    valueText(nil).foregroundStyle(StrandPalette.textSecondary)
                }
            }
            .buttonStyle(.plain)
        } else {
            let control = picker()
            SetupRow {
                Text(title)
            } trailing: {
                control.labelsHidden().fixedSize()
            }
        }
        #endif
    }

    @ViewBuilder private func valueText(_ value: String?) -> some View {
        if let value { Text(verbatim: value) } else { Text("Not Set") }
    }

    private func menuRow<Options: View>(_ title: LocalizedStringKey, selection: Binding<String>,
                                        @ViewBuilder options: () -> Options) -> some View {
        let options = options()
        return SetupRow {
            Text(title)
        } trailing: {
            Picker(title, selection: selection) { options }
                .labelsHidden()
                .pickerStyle(.menu)
                .tint(StrandPalette.textSecondary)
                .fixedSize()
        }
    }
}

// MARK: - 4 · Your history (optional)

/// As setup's "Apps & Data": the sources as rows of one card.
private struct ImportStep: View {
    let next: () -> Void
    @EnvironmentObject private var model: AppModel
    @State private var showingImporter = false
    @State private var importTarget: ImportTarget = .whoop
    @ScaledMetric(relativeTo: .title3) private var glyphWidth: CGFloat = 26

    var body: some View {
        SetupPage(title: String(localized: "Bring Your History"),
                  message: String(localized: "Optional. You can import later too."),
                  art: { SetupGlyph(systemName: "square.and.arrow.down.on.square") }) {
            VStack(alignment: .leading, spacing: 12) {
                SetupCard {
                    importRow(.whoop, title: String(localized: "WHOOP Export"))
                    SetupDivider(inset: 54)
                    importRow(.appleHealth, title: String(localized: "Apple Health Export"))
                }
                if !model.hasActiveImport, let summary = lastSummary {
                    // Styled off the typed failure flag, not a substring match.
                    Text(summary)
                        .font(StrandFont.pro(15))
                        .foregroundStyle(model.importFailed(importKind) ? StrandPalette.settingsRed : StrandPalette.textSecondary)
                        .padding(.horizontal, 16)
                }
            }
        } tray: {
            SetupButton(title: "Done", action: next)
        }
        .fileImporter(
            isPresented: $showingImporter,
            allowedContentTypes: importTarget.allowedContentTypes,
            allowsMultipleSelection: false
        ) { result in
            handleImportResult(result, for: importTarget)
        }
    }

    private func importRow(_ target: ImportTarget, title: String) -> some View {
        Button { presentImporter(target) } label: {
            SetupRow(stacks: false) {
                Image(systemName: target.systemImage)
                    .font(StrandFont.pro(20))
                    .foregroundStyle(StrandPalette.settingsBlue)
                    .frame(width: glyphWidth)
                Text(verbatim: title)
            } trailing: {
                if model.hasActiveImport && importTarget == target {
                    ProgressView()
                } else {
                    Image(systemName: "chevron.right")
                        .font(StrandFont.pro(15, weight: .semibold))
                        .foregroundStyle(StrandPalette.textTertiary)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(model.hasActiveImport)
    }

    /// The AppModel source kind matching the last-chosen import target.
    private var importKind: DataSourceImportKind {
        switch importTarget {
        case .whoop: return .whoop
        case .appleHealth: return .appleHealth
        }
    }

    /// The summary for the source the user last imported in this step.
    private var lastSummary: String? {
        switch importTarget {
        case .whoop: return model.whoopImportSummary
        case .appleHealth: return model.appleHealthImportSummary
        }
    }

    private func presentImporter(_ target: ImportTarget) {
        importTarget = target
        showingImporter = true
    }

    private func handleImportResult(_ result: Result<[URL], Error>, for target: ImportTarget) {
        guard case .success(let urls) = result, let url = urls.first else { return }
        switch target {
        case .whoop:
            model.importWhoop(url: url)
        case .appleHealth:
            model.importAppleHealth(url: url)
        }
    }

    private enum ImportTarget {
        case whoop
        case appleHealth

        var systemImage: String {
            switch self {
            case .whoop: return "doc.zipper"
            case .appleHealth: return "heart.text.square"
            }
        }

        var allowedContentTypes: [UTType] {
            // See DataSourcesView: `.folder` is a macOS-only affordance (pick an unzipped export
            // directory). On iOS it greys out the .zip in the Files picker (issue #179), so iOS
            // offers only the concrete file types.
            switch self {
            case .whoop:
                #if os(macOS)
                return [.zip, .folder]
                #else
                return [.zip]
                #endif
            case .appleHealth:
                #if os(macOS)
                return [.zip, .xml, .folder]
                #else
                return [.zip, .xml]
                #endif
            }
        }
    }
}

// MARK: - Preview

#if DEBUG
private struct OnboardingPreview: View {
    @StateObject private var model = AppModel()
    var body: some View {
        OnboardingWizard(onFinished: {})
            .environmentObject(model)
            .environmentObject(model.live)
            .environmentObject(model.profile)
            .frame(width: 1100, height: 780)
    }
}

#Preview("Onboarding") { OnboardingPreview() }
#endif
