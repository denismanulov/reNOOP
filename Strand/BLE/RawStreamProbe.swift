import Foundation

/// Sends the WHOOP 4.0 raw-stream switch (SEND_R10_R11_REALTIME) on its own, apart from every other
/// command, and repeats it until the strap answers. Two callers: the Live screen, as an Experimental
/// default-off opt-in, and `StepAutoCalibrator`'s short bursts, which carry their own opt-in.
///
/// Why it exists. The Live screen sends that switch immediately before TOGGLE_REALTIME_HR. On one
/// WHOOP 4.0 (2026-10-03, a restored connection) the strap answered the toggle eight times out of
/// eight and the switch none, its own console log reported "Realtime raw disabled" at each toggle, and
/// no type-43 frame arrived. In the same session the strap answered only the last of nine handshake
/// commands written in one burst. That points at the first of two back-to-back writes being lost, but
/// it does not rule out a firmware that declines to enable the stream. This probe tells the two apart:
/// a switch written alone either is acknowledged and followed by type-43 frames, or it is not.
///
/// The Live-screen opt-in is the `rawStreamProbe` user default, set as a launch argument; there is no
/// UI. A burst is not gated here: its caller decides. Turning the stream off is not gated on either: once the probe has asked for the stream it records that in
/// `armedKey`, and any later connection keeps writing the off switch until the strap acknowledges it,
/// because a raw stream left running costs strap battery and competes with the history offload.
///
/// The acknowledgement is the strap's COMMAND_RESPONSE to the opcode. It carries the request's
/// sequence, not its payload, so it cannot say which direction it answers; the probe takes it as
/// answering the last direction it wrote.
final class RawStreamProbe {
    static let enabledKey = "rawStreamProbe"
    static let armedKey = "rawStreamProbe.armed"
    static let maxAttempts = 4
    static let firstDelay: TimeInterval = 1.5
    static let retryDelay: TimeInterval = 2.0

    private let defaults: UserDefaults
    private let send: (_ on: Bool) -> Void
    private let log: (String) -> Void
    private let schedule: (TimeInterval, @escaping () -> Void) -> Void

    /// The direction still waiting for the strap's answer, nil when nothing is outstanding.
    private var awaiting: Bool?
    private var attempts = 0
    /// Bumped whenever the outstanding request changes, so scheduled work from an older one is a no-op.
    private var generation = 0
    /// An opted-in Live screen is up and wants the stream kept on.
    private var liveWanted = false
    /// A calibration burst is running and wants the stream kept on.
    private var burstActive = false

    init(defaults: UserDefaults = .standard,
         send: @escaping (_ on: Bool) -> Void,
         log: @escaping (String) -> Void,
         schedule: @escaping (TimeInterval, @escaping () -> Void) -> Void = { delay, work in
             DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
         }) {
        self.defaults = defaults
        self.send = send
        self.log = log
        self.schedule = schedule
    }

    private var isEnabled: Bool { defaults.bool(forKey: Self.enabledKey) }
    private var isArmed: Bool { defaults.bool(forKey: Self.armedKey) }

    /// A Live screen asked for realtime data.
    func liveStarted() {
        guard isEnabled else { return }
        liveWanted = true
        request(on: true)
    }

    /// The Live screen left. Writes the off switch whenever the probe may have turned the stream on.
    func liveStopped() {
        liveWanted = false
        guard isEnabled || isArmed, !burstActive else { return }
        request(on: false)
    }

    /// A calibration burst begins: the stream goes on whatever the Live opt-in says.
    func burstStarted() {
        burstActive = true
        request(on: true)
    }

    /// The burst is over. The stream goes off unless an opted-in Live screen still wants it.
    func burstStopped() {
        burstActive = false
        guard !liveWanted else { return }
        request(on: false)
    }

    /// The connection finished its handshake. Restores the stream for a Live screen that is still up,
    /// and otherwise clears a stream an earlier connection or launch left on.
    func connectSettled(liveWanted: Bool) {
        if liveWanted {
            if isEnabled { request(on: true) }
        } else if isArmed {
            request(on: false)
        }
    }

    /// The link dropped: nothing can be written, and the next connection starts from `connectSettled`.
    func disconnected() {
        generation += 1
        awaiting = nil
        burstActive = false
    }

    /// The strap answered the raw-stream opcode.
    func responseReceived() {
        guard let direction = awaiting else { return }
        generation += 1
        awaiting = nil
        if !direction { defaults.set(false, forKey: Self.armedKey) }
        log("Raw stream probe: strap acknowledged the \(direction ? "on" : "off") switch"
            + " after \(attempts) write(s)")
    }

    private func request(on: Bool) {
        generation += 1
        awaiting = on
        attempts = 0
        if on { defaults.set(true, forKey: Self.armedKey) }
        scheduleAttempt(after: Self.firstDelay)
    }

    private func scheduleAttempt(after delay: TimeInterval) {
        let scheduled = generation
        schedule(delay) { [weak self] in
            guard let self, self.generation == scheduled, let direction = self.awaiting else { return }
            guard self.attempts < Self.maxAttempts else {
                self.awaiting = nil
                self.log("Raw stream probe: no answer to the \(direction ? "on" : "off") switch after"
                         + " \(self.attempts) writes; giving up until the next connection")
                return
            }
            self.attempts += 1
            self.log("Raw stream probe: writing the \(direction ? "on" : "off") switch alone"
                     + " (attempt \(self.attempts)/\(Self.maxAttempts))")
            self.send(direction)
            self.scheduleAttempt(after: Self.retryDelay)
        }
    }
}
