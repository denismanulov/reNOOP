import SwiftUI
import StrandDesign

/// Settings → Backup, laid out like Settings → iCloud Backup. Folder backup (pick a folder, daily
/// auto-backup as an on-launch catch-up, back up now, restore a snapshot from that folder) plus the
/// whole-file export / import and the WHOOP-format CSV export. Snapshots are the `.noopbak` whole-DB
/// format. Point the folder at Google Drive / iCloud / Dropbox for off-device sync with no account.
struct BackupSyncView: View {
    @EnvironmentObject var model: AppModel

    @State private var auto = FolderBackup.autoEnabled
    @State private var folderLabel = FolderBackup.folderLabel()
    @State private var lastMs = FolderBackup.lastBackupMs
    @State private var keep = FolderBackup.keepCount
    @State private var busy = false

    // Result alert (backup outcome / restore outcome).
    @State private var alertTitle = ""
    @State private var alertMessage = ""
    @State private var showAlert = false

    // Restore-from-folder flow (must-fix #1 + #2): a sheet lists the folder's snapshots; choosing one
    // arms a destructive confirmation; only confirming runs the overwrite.
    @State private var showRestoreSheet = false
    @State private var snapshots: [FolderBackup.Snapshot] = []
    @State private var pendingRestore: FolderBackup.Snapshot?
    @State private var confirmRestore = false

    // Whole-file export / import / CSV (the old Settings "Backup & restore" card), on the same page.
    @State private var showOversizeRestoreConfirm = false
    @State private var oversizeRestoreMessage = ""
    /// The picked file awaiting the same Replace-all-data question Restore asks, and its answer.
    @State private var pendingImport: String?
    @State private var importAnswer: CheckedContinuation<Bool, Never>?

    var body: some View {
        Form {
            Section {
                LabeledContent("Folder", value: folderLabel ?? String(localized: "Not set"))
                Button(folderLabel == nil ? "Choose folder" : "Change folder") { chooseFolder() }
                    .disabled(busy)
                #if os(iOS)
                // #52: some iOS 26 pickers never return a folder; back up inside NOOP's own Files folder.
                if !FolderBackup.useInternalFolder {
                    Button("reNOOP folder") { useNoopFolder() }
                        .disabled(busy)
                }
                #endif
            }

            Section {
                Button {
                    backupNow()
                } label: {
                    HStack {
                        Text(busy ? "Working…" : "Back up now")
                        if busy { Spacer(); ProgressView().controlSize(.small) }
                    }
                }
                .disabled(folderLabel == nil || busy)
                Button("Restore…") { openRestorePicker() }
                    .disabled(folderLabel == nil || busy)
                Toggle("Daily", isOn: $auto)
                    .disabled(folderLabel == nil)
                    .onChangeCompat(of: auto) { on in FolderBackup.autoEnabled = on }
                // Wired to FolderBackup.keepCount; the next backup prunes the oldest beyond this count.
                Picker("Keep backups", selection: $keep) {
                    ForEach(FolderBackup.keepOptions, id: \.self) { n in Text("\(n)").tag(n) }
                }
                .settingsPicker()
                .onChangeCompat(of: keep) { n in FolderBackup.keepCount = n }
                if lastMs > 0 {
                    // Daily is on but the last success is days old: a moved or disconnected cloud folder
                    // stops backups silently, so the value turns to the warning colour here rather than
                    // the problem surfacing at restore time.
                    LabeledContent("Last backup") {
                        Text(relativeTime(lastMs))
                            .foregroundStyle(backupIsStale ? StrandPalette.statusWarning : StrandPalette.textSecondary)
                    }
                }
            }

            Section {
                Button("Export to file…") { runExport() }
                Button("Import from file…") { runImport() }
                Button("Export as CSV…") { runCsvExport() }
            } header: {
                Text("File")
            }
            .disabled(busy)

            StorageSections()
        }
        .settingsPage("Backup")
        // Result of a backup or a restore.
        .alert(alertTitle, isPresented: $showAlert) {
            Button("OK", role: .cancel) {}
        } message: { Text(alertMessage) }
        // #1807: a restore refused only for size is recoverable, so it gets its own two-button alert.
        .alert("Backup problem", isPresented: $showOversizeRestoreConfirm) {
            Button("Restore") { runImport(allowOversize: true) }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text(oversizeRestoreMessage)
        }
        // Pick which snapshot to restore - the folder's own snapshots, newest first (must-fix #1).
        .sheet(isPresented: $showRestoreSheet) {
            RestorePickerSheet(snapshots: snapshots) { chosen in
                showRestoreSheet = false
                pendingRestore = chosen
                if chosen != nil { confirmRestore = true }
            }
        }
        // Explicit in-app destructive confirmation BEFORE any overwrite (must-fix #2).
        .alert("Restore this backup?", isPresented: $confirmRestore, presenting: pendingRestore) { snap in
            Button("Replace all data", role: .destructive) { runRestore(snap) }
            Button("Cancel", role: .cancel) { pendingRestore = nil }
        } message: { snap in
            // A hand-named file with no resolved date (timeMs 0) confirms by NAME, not "1 Jan 1970".
            Text(snap.timeMs > 0
                ? "Replace all current data with the backup from \(absoluteTime(snap.timeMs))? This cannot be undone."
                : "Replace all current data with the backup \(snap.name)? This cannot be undone.")
        }
        .alert("Restore this backup?",
               isPresented: Binding(get: { pendingImport != nil }, set: { if !$0 { pendingImport = nil } }),
               presenting: pendingImport) { _ in
            Button("Replace all data", role: .destructive) { answerImport(true) }
            Button("Cancel", role: .cancel) { answerImport(false) }
        } message: { name in
            Text("Replace all current data with the backup \(name)? This cannot be undone.")
        }
    }

    private var backupIsStale: Bool {
        auto && folderLabel != nil
            && BackupSync.isBackupStale(lastBackupMs: lastMs, nowMs: Int(Date().timeIntervalSince1970 * 1000.0))
    }

    // MARK: - File export / import

    private func runExport() {
        busy = true
        Task {
            let result = await DataBackup.runExport(checkpoint: { await model.repo.checkpointForBackup() })
            handleBackup(result)
        }
    }

    private func runImport(allowOversize: Bool = false) {
        busy = true
        Task {
            // The oversize retry comes from its own Restore alert, answered after this one.
            let result = await DataBackup.runImport(allowOversize: allowOversize) { name in
                if allowOversize { return true }
                return await withCheckedContinuation { importAnswer = $0; pendingImport = name }
            }
            #if os(iOS)
            StoreRestartPrompt.shared.restoreDidFinish()
            #endif
            handleBackup(result)
        }
    }

    private func answerImport(_ replace: Bool) {
        importAnswer?.resume(returning: replace)
        importAnswer = nil
    }

    private func runCsvExport() {
        busy = true
        Task {
            let result = await CsvExport.run(repo: model.repo)
            busy = false
            switch result {
            case .cancelled:
                return
            case .exported:
                Confirmation.shared.show(String(localized: "CSV exported"))
            case .failure(let message):
                alertTitle = String(localized: "Export problem")
                alertMessage = message
                showAlert = true
            }
        }
    }

    @MainActor
    private func handleBackup(_ result: DataBackup.BackupResult) {
        busy = false
        switch result {
        case .cancelled:
            return
        case .exported:
            Confirmation.shared.show(String(localized: "Backup exported"))
        case .exportedOversize(let url, let bytes, let limit):
            // #1807: the file is written and worth keeping — say so, then what restoring it will ask.
            let size = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
            let cap = ByteCountFormatter.string(fromByteCount: limit, countStyle: .file)
            alertTitle = String(localized: "Backup exported")
            alertMessage = String(localized: "Saved to \(url.lastPathComponent). At \(size) it is over \(cap), so restoring it asks once more.")
            showAlert = true
        case .restoreTooLarge(let name, let limit):
            let cap = ByteCountFormatter.string(fromByteCount: limit, countStyle: .file)
            oversizeRestoreMessage = String(localized: "\(name) is over \(cap). Restore it only if you exported it yourself. You'll choose the file again.")
            showOversizeRestoreConfirm = true
        case .imported:
            // iOS: the root alert (`StoreRestartPrompt`, raised when the restore returned) asks for the
            // relaunch the swapped store needs; a capsule here would be one more thing to miss.
            #if os(macOS)
            // ST-6: an outcome, not a question — the capsule, as Backed up already does.
            Confirmation.shared.show(String(localized: "Restored. Reopen reNOOP."))
            #endif
        case .failure(let message):
            alertTitle = String(localized: "Backup problem")
            alertMessage = message
            showAlert = true
        }
    }

    // MARK: - Actions

    private func chooseFolder() {
        #if os(macOS)
        if FolderBackup.pickFolder() != nil { folderLabel = FolderBackup.folderLabel() }
        #else
        // #1000a assumed the iOS picker was refusing to ENABLE its Select button, leaving the user with
        // only Cancel. #2356 disproved that for at least one case: a reporter's log shows the delegate
        // firing after they picked an iCloud folder and pressed Open, so the button worked and iOS
        // declined the grant instead. Both still arrive here as nil, and UIKit gives us nothing to tell
        // them apart, which is exactly why the alert below describes the outcome rather than a cause.
        // Keep it that way: the previous guess is what sent the last investigation at the wrong failure.
        // Mildly chatty on a genuine Cancel; honest and actionable whenever no folder comes back.
        // `busy` guards against a double-tap stacking a second picker presentation on top of the first.
        busy = true
        Task {
            // Clear in a `defer` so it clears on ANY exit. It matters more here than elsewhere: every
            // control on this screen is `.disabled(busy)`, so a pick that never returned wedged the whole
            // screen — including the "Use NOOP's own folder" escape hatch. DocumentPicker now guarantees
            // the continuation resumes, but the flag must not depend on that promise holding.
            defer { busy = false }
            let picked = await FolderBackup.pickFolder()
            if picked != nil {
                folderLabel = FolderBackup.folderLabel()
            } else if !FolderBackup.useInternalFolder {
                // Only nag when there's no working destination. If the internal fallback is already
                // active, a cancelled picker changed nothing — and the button the message points at is
                // hidden, so alerting here would send the user chasing a control that isn't shown.
                alertTitle = String(localized: "No folder selected")
                alertMessage = String(localized: "If Open does nothing, tap reNOOP folder to back up inside reNOOP.")
                showAlert = true
            }
        }
        #endif
    }

    #if os(iOS)
    // #52: picker-free fallback. Back up inside NOOP's own Files-visible folder (On My iPhone → NOOP →
    // Backups). No folder picker, no security-scoped bookmark — works even where the picker won't select.
    private func useNoopFolder() {
        FolderBackup.useNoopFolder()
        // The Folder row now names it; the capsule confirms the switch (ST-6).
        folderLabel = FolderBackup.folderLabel()
        Confirmation.shared.show(String(localized: "Using reNOOP's folder"))
    }
    #endif

    private func backupNow() {
        busy = true
        Task {
            defer { busy = false }   // any exit, incl. cancellation — see chooseFolder
            let ok = await FolderBackup.backupNow(checkpoint: { await model.repo.checkpointForBackup() })
            await MainActor.run {
                lastMs = FolderBackup.lastBackupMs
                if ok {
                    Confirmation.shared.show(String(localized: "Backed up"))
                } else {
                    alertTitle = String(localized: "Backup problem")
                    alertMessage = String(localized: "Backup failed - re-pick the folder and try again.")
                    showAlert = true
                }
            }
        }
    }

    private func openRestorePicker() {
        snapshots = FolderBackup.listSnapshots()
        if snapshots.isEmpty {
            alertTitle = String(localized: "No backups found")
            alertMessage = String(localized: "There are no reNOOP backups in your folder yet. Use Back up now first.")
            showAlert = true
        } else {
            showRestoreSheet = true
        }
    }

    private func runRestore(_ snap: FolderBackup.Snapshot) {
        pendingRestore = nil
        busy = true
        Task {
            defer { busy = false }   // any exit, incl. cancellation — see chooseFolder
            // The restore is synchronous file I/O; run it off the main actor so the UI stays responsive
            // for a large store, then report on the main actor.
            let result = await Task.detached(priority: .userInitiated) {
                FolderBackup.restore(snapshotNamed: snap.name)
            }.value
            await MainActor.run {
                #if os(iOS)
                StoreRestartPrompt.shared.restoreDidFinish()
                #endif
                switch result {
                case .imported:
                    #if os(macOS)
                    Confirmation.shared.show(String(localized: "Restored. Reopen reNOOP."))
                    #endif
                    return
                case .failure(let m):
                    alertTitle = String(localized: "Restore problem"); alertMessage = m
                case .restoreTooLarge(let name, let limit):
                    // #1807: recoverable, but not from here — this view restores a snapshot directly and
                    // has no confirm step to hang the override on. Point at the path that does, rather
                    // than leaving the user with a refusal and nowhere to go.
                    let cap = ByteCountFormatter.string(fromByteCount: limit, countStyle: .file)
                    alertTitle = String(localized: "Backup problem")
                    alertMessage = String(localized: "\(name) is over \(cap). Restore it with Import from file….")
                case .cancelled, .exported, .exportedOversize:
                    alertTitle = String(localized: "Restore problem"); alertMessage = String(localized: "Couldn't restore that backup.")
                }
                showAlert = true
            }
        }
    }

    // MARK: - Formatting

    private func relativeTime(_ ms: Int) -> String {
        let f = RelativeDateTimeFormatter()
        return f.localizedString(for: Date(timeIntervalSince1970: Double(ms) / 1000.0), relativeTo: Date())
    }

    private func absoluteTime(_ ms: Int) -> String {
        let f = DateFormatter()
        // #1821: localized DATE style untouched; only the hour cycle is the reader's.
        f.locale = AppClock.formattingLocale
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000.0))
    }
}

/// The snapshot chooser shown before a restore (must-fix #1: pick from the folder, newest first).
/// Reports the chosen snapshot (or nil if dismissed) back to the host, which then arms the destructive
/// confirmation.
private struct RestorePickerSheet: View {
    let snapshots: [FolderBackup.Snapshot]
    let onChoose: (FolderBackup.Snapshot?) -> Void

    var body: some View {
        NavigationStack {
            List(snapshots) { snap in
                Button { onChoose(snap) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            // A hand-named file whose date lookup failed has timeMs 0; show its name as the
                            // primary line rather than "1 Jan 1970". The filename subtitle then only repeats
                            // when we DO have a real date to head the row.
                            Text(primaryLabel(snap))
                                .font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                            if snap.timeMs > 0 {
                                Text(snap.name)
                                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                            }
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .accessibilityLabel(accessibilityLabel(snap))
            }
            .navigationTitle("Choose a backup")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    SheetCloseButton { onChoose(nil) }
                }
            }
        }
        // A macOS `.sheet` sizes to its content's ideal height, and a `List` inside a `NavigationStack`
        // reports a near-zero intrinsic height there — so without an explicit frame the sheet collapses
        // to just the title + Cancel and clips every row, leaving the user an empty "Choose a backup"
        // with backups that ARE in the folder (the caller only opens this sheet when the list is
        // non-empty). Give it a real size, the same way `AddDeviceWizard`/`HealthView` frame their macOS
        // sheets with a fixed size. iOS/iPadOS sheets already take a sensible height, so the frame is
        // macOS-only. A longer backup list scrolls within the List; a short one leaves trailing space. (#1093)
        #if os(macOS)
        .frame(width: 460, height: 420)
        #endif
    }

    /// The row's headline: a friendly date when we resolved one, else the filename (never the epoch date).
    private func primaryLabel(_ snap: FolderBackup.Snapshot) -> String {
        snap.timeMs > 0 ? absoluteTime(snap.timeMs) : snap.name
    }

    /// VoiceOver label: reads the resolved date when we have one, else the filename (no epoch date).
    private func accessibilityLabel(_ snap: FolderBackup.Snapshot) -> String {
        snap.timeMs > 0 ? String(localized: "Restore backup from \(absoluteTime(snap.timeMs))")
                        : String(localized: "Restore backup \(snap.name)")
    }

    private func absoluteTime(_ ms: Int) -> String {
        let f = DateFormatter()
        // #1821: localized DATE style untouched; only the hour cycle is the reader's. Second copy of
        // this helper in the file - a single-shot replace fixed only the first, which the sweep caught.
        f.locale = AppClock.formattingLocale
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000.0))
    }
}
