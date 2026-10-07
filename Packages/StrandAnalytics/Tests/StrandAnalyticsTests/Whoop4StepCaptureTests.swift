import XCTest
@testable import StrandAnalytics
import WhoopProtocol

/// The WHOOP 4.0 capture of 2026-10-03: 20 minutes of 1 Hz history across a seated stretch, two
/// counted walks and a bout of hand-waving. The firmware counter rose 45630 -> 45894, and every bout
/// opened with an 11 or 12 step release. The consecutive-sample rate gate kept 217 of those 264.
final class Whoop4StepCaptureTests: XCTestCase {

    private let firstTs = 1_791_024_239
    private let spanSeconds = 1_204

    /// (seconds since `firstTs`, counter) at every record where the counter changed. Index 0 is the
    /// first record. The series is rebuilt at 1 Hz, flat between entries.
    private let changes: [(Int, Int)] = [
        (0, 45630), (27, 45642), (905, 45654), (906, 45655), (907, 45657), (908, 45658), (909, 45660),
        (910, 45661), (911, 45663), (912, 45664), (913, 45666), (914, 45667), (915, 45669), (916, 45672),
        (917, 45673), (918, 45674), (919, 45676), (920, 45678), (921, 45679), (922, 45682), (924, 45684),
        (925, 45685), (926, 45687), (927, 45689), (928, 45691), (929, 45693), (930, 45695), (931, 45696),
        (932, 45699), (933, 45701), (934, 45702), (935, 45704), (936, 45705), (937, 45707), (939, 45709),
        (940, 45710), (941, 45714), (942, 45715), (943, 45716), (944, 45718), (945, 45719), (946, 45721),
        (947, 45722), (948, 45724), (949, 45726), (950, 45727), (951, 45729), (952, 45730), (953, 45732),
        (955, 45734), (956, 45736), (957, 45737), (958, 45738), (959, 45740), (960, 45743), (962, 45745),
        (964, 45747), (965, 45751), (966, 45752), (967, 45753), (969, 45756), (970, 45757), (971, 45759),
        (972, 45761), (973, 45763), (974, 45764), (975, 45766), (976, 45767), (977, 45768), (978, 45769),
        (1052, 45780), (1053, 45782), (1054, 45783), (1055, 45785), (1056, 45787), (1057, 45788), (1058, 45790),
        (1059, 45792), (1060, 45793), (1061, 45795), (1062, 45797), (1063, 45798), (1064, 45800), (1065, 45803),
        (1066, 45805), (1067, 45806), (1068, 45807), (1069, 45809), (1070, 45810), (1071, 45812), (1072, 45813),
        (1073, 45815), (1074, 45816), (1075, 45818), (1076, 45819), (1077, 45820), (1078, 45823), (1079, 45824),
        (1080, 45826), (1081, 45827), (1082, 45830), (1083, 45831), (1084, 45832), (1160, 45844), (1161, 45845),
        (1162, 45846), (1163, 45848), (1164, 45852), (1165, 45854), (1166, 45856), (1167, 45858), (1168, 45860),
        (1169, 45862), (1170, 45864), (1171, 45866), (1172, 45868), (1173, 45870), (1174, 45872), (1175, 45874),
        (1176, 45876), (1177, 45878), (1178, 45880), (1179, 45882), (1180, 45885), (1181, 45887), (1182, 45889),
        (1183, 45891), (1184, 45892), (1185, 45894),
    ]

    private var samples: [StepSample] {
        var counterAt: [Int: Int] = [:]
        for (offset, counter) in changes { counterAt[offset] = counter }
        var counter = changes[0].1
        return (0...spanSeconds).map { offset in
            if let changed = counterAt[offset] { counter = changed }
            return StepSample(ts: firstTs + offset, counter: counter)
        }
    }

    func testEveryCountedStepSurvivesTheGate() {
        XCTAssertEqual(StepsCounter.stepsInWindow(samples), 264)
    }

    func testSleepAwareCounterAgrees() {
        let count = SleepAwareStepCounter.count(samples, sleepSessions: [])
        XCTAssertEqual(count.totalTicks, 264)
        XCTAssertEqual(count.rejectedImplausibleTicks, 0)
    }

    func testDailyTotalIsTheCounterRise() {
        let daily = AnalyticsEngine.analyzeDay(day: "2026-10-03", steps: samples,
                                               profile: UserProfile()).daily
        XCTAssertEqual(daily.steps, 264)
    }
}
