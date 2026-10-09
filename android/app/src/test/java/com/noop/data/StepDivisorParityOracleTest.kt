package com.noop.data

import com.noop.analytics.AnalyzeRecentDayCache
import com.noop.analytics.PhysiologicalStepCycleEngine
import com.noop.analytics.StepCalibration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which divisor each day's step total uses, and what that total then is, against the Swift source
 * of truth by ORACLE, not by eye.
 *
 * [expected] is the verbatim stdout of `Strand/Data/StepCalibrationStore.swift` (unchanged but for its
 * removed package import) compiled standalone over
 * `Packages/StrandAnalytics/Sources/StrandAnalytics/StepCalibration.swift`
 * (`swiftc -O StepCalibration.swift StepCalibrationStore.swift main.swift && ./t`, Swift 6.3 on macOS
 * arm64), driven through one scripted week on a fresh `UserDefaults` suite: the opt-in off with a
 * learned state already stored, switched on, measurements today, a day rollover, a second day's
 * measurement, a refused ratio, a skipped day, a measurement for a day already left, off again, on
 * again. After every step it prints the snapshot's state, the stored state, and for ten days the
 * divisor (as its IEEE-754 bit pattern), the `stepDiv=` number the day cache key carries, and nine tick
 * totals scaled the way the three Swift readers scale them
 * (`Int((Double(ticks) / max(divisor, 0.5)).rounded())` in `AnalyticsEngine.analyzeDay`,
 * `DayCycleIntelligenceIntegration` and `WorkoutDetailView`).
 *
 * The manual divisors include `Double(Float(1.26))`, because Android keeps that preference as a Float,
 * and 0.3, under the 0.5 floor.
 *
 * The oracle only guards this direction. `StepAutoCalibratorTests.testTheStoreAnswersWithTheManualDivisorUnlessOptedIn`
 * on the Swift side is what stops Swift drifting away from Kotlin.
 */
class StepDivisorParityOracleTest {

    /** Verbatim stdout of the Swift build. Do not hand-edit: regenerate from the oracle. */
    private val expected = """
        record day=2026-10-03 steps=4059000000000000 ticks=4060400000000000 accepts=true -> emaTicks=0000000000000000 emaSteps=0000000000000000 day=2026-10-03 dayTicks=4060400000000000 daySteps=4059000000000000 accepted=1 frozen={}
        == off with a stored state: snapshot manual=3ff0000000000000 today=2026-10-03 enabled=false ==
        snapshot.state=nil
        stored=emaTicks=0000000000000000 emaSteps=0000000000000000 day=2026-10-03 dayTicks=4060400000000000 daySteps=4059000000000000 accepted=1 frozen={}
        2026-09-30 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-01 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-02 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-03 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-04 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-05 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-06 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-07 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-08 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-09 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        == off, another manual: snapshot manual=3ff428f5c0000000 today=2026-10-04 enabled=false ==
        snapshot.state=nil
        stored=emaTicks=0000000000000000 emaSteps=0000000000000000 day=2026-10-03 dayTicks=4060400000000000 daySteps=4059000000000000 accepted=1 frozen={}
        2026-09-30 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-01 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-02 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-03 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-04 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-05 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-06 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-07 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-08 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-09 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        == on, next day: snapshot manual=3ff0000000000000 today=2026-10-04 enabled=true ==
        snapshot.state=emaTicks=403a000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=1 frozen={2026-10-03=3ff4cccccccccccd}
        stored=emaTicks=403a000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=1 frozen={2026-10-03=3ff4cccccccccccd}
        2026-09-30 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-01 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-02 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-03 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-04 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-05 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-06 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-07 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-08 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-09 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        record day=2026-10-04 steps=404e000000000000 ticks=4050800000000000 accepts=true -> emaTicks=403a000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=4050800000000000 daySteps=404e000000000000 accepted=2 frozen={2026-10-03=3ff4cccccccccccd}
        == one measurement today: snapshot manual=3ff0000000000000 today=2026-10-04 enabled=true ==
        snapshot.state=emaTicks=403a000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=4050800000000000 daySteps=404e000000000000 accepted=2 frozen={2026-10-03=3ff4cccccccccccd}
        stored=emaTicks=403a000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=4050800000000000 daySteps=404e000000000000 accepted=2 frozen={2026-10-03=3ff4cccccccccccd}
        2026-09-30 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-01 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-02 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-03 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-04 factor=3ff3bbbbbbbbbbbc stepDiv=4608233258713070524 steps=0,1,30,54,214,4054,10009,53136,100100099
        2026-10-05 factor=3ff36db6db6db6db stepDiv=4608147475863025371 steps=0,1,30,54,217,4118,10166,53970,101670297
        2026-10-06 factor=3ff36db6db6db6db stepDiv=4608147475863025371 steps=0,1,30,54,217,4118,10166,53970,101670297
        2026-10-07 factor=3ff36db6db6db6db stepDiv=4608147475863025371 steps=0,1,30,54,217,4118,10166,53970,101670297
        2026-10-08 factor=3ff36db6db6db6db stepDiv=4608147475863025371 steps=0,1,30,54,217,4118,10166,53970,101670297
        2026-10-09 factor=3ff36db6db6db6db stepDiv=4608147475863025371 steps=0,1,30,54,217,4118,10166,53970,101670297
        record day=2026-10-04 steps=404ec00000000000 ticks=4053800000000000 accepts=true -> emaTicks=403a000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=4062000000000000 daySteps=405e600000000000 accepted=3 frozen={2026-10-03=3ff4cccccccccccd}
        == two measurements today: snapshot manual=3ff0000000000000 today=2026-10-04 enabled=true ==
        snapshot.state=emaTicks=403a000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=4062000000000000 daySteps=405e600000000000 accepted=3 frozen={2026-10-03=3ff4cccccccccccd}
        stored=emaTicks=403a000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=4062000000000000 daySteps=405e600000000000 accepted=3 frozen={2026-10-03=3ff4cccccccccccd}
        2026-09-30 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-01 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-02 factor=3ff0000000000000 stepDiv=4607182418800017408 steps=0,1,37,66,264,5000,12345,65535,123456789
        2026-10-03 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-04 factor=3ff3e032e1c9f019 stepDiv=4608273352871243801 steps=0,1,30,53,213,4025,9938,52756,99382715
        2026-10-05 factor=3ff3b13b13b13b14 stepDiv=4608221711021718292 steps=0,1,30,54,215,4063,10030,53247,100308641
        2026-10-06 factor=3ff3b13b13b13b14 stepDiv=4608221711021718292 steps=0,1,30,54,215,4063,10030,53247,100308641
        2026-10-07 factor=3ff3b13b13b13b14 stepDiv=4608221711021718292 steps=0,1,30,54,215,4063,10030,53247,100308641
        2026-10-08 factor=3ff3b13b13b13b14 stepDiv=4608221711021718292 steps=0,1,30,54,215,4063,10030,53247,100308641
        2026-10-09 factor=3ff3b13b13b13b14 stepDiv=4608221711021718292 steps=0,1,30,54,215,4063,10030,53247,100308641
        == rollover: snapshot manual=3ff428f5c0000000 today=2026-10-05 enabled=true ==
        snapshot.state=emaTicks=4048cccccccccccd emaSteps=4044266666666666 day=2026-10-05 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=3 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019}
        stored=emaTicks=4048cccccccccccd emaSteps=4044266666666666 day=2026-10-05 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=3 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019}
        2026-09-30 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-01 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-02 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-03 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-04 factor=3ff3e032e1c9f019 stepDiv=4608273352871243801 steps=0,1,30,53,213,4025,9938,52756,99382715
        2026-10-05 factor=3ff3b13b13b13b14 stepDiv=4608221711021718292 steps=0,1,30,54,215,4063,10030,53247,100308641
        2026-10-06 factor=3ff3b13b13b13b14 stepDiv=4608221711021718292 steps=0,1,30,54,215,4063,10030,53247,100308641
        2026-10-07 factor=3ff3b13b13b13b14 stepDiv=4608221711021718292 steps=0,1,30,54,215,4063,10030,53247,100308641
        2026-10-08 factor=3ff3b13b13b13b14 stepDiv=4608221711021718292 steps=0,1,30,54,215,4063,10030,53247,100308641
        2026-10-09 factor=3ff3b13b13b13b14 stepDiv=4608221711021718292 steps=0,1,30,54,215,4063,10030,53247,100308641
        record day=2026-10-05 steps=404d200000000000 ticks=4055c00000000000 accepts=true -> emaTicks=4048cccccccccccd emaSteps=4044266666666666 day=2026-10-05 dayTicks=4055c00000000000 daySteps=404d200000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019}
        == hand in pocket: snapshot manual=3ff428f5c0000000 today=2026-10-05 enabled=true ==
        snapshot.state=emaTicks=4048cccccccccccd emaSteps=4044266666666666 day=2026-10-05 dayTicks=4055c00000000000 daySteps=404d200000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019}
        stored=emaTicks=4048cccccccccccd emaSteps=4044266666666666 day=2026-10-05 dayTicks=4055c00000000000 daySteps=404d200000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019}
        2026-09-30 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-01 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-02 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-03 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-04 factor=3ff3e032e1c9f019 stepDiv=4608273352871243801 steps=0,1,30,53,213,4025,9938,52756,99382715
        2026-10-05 factor=3ff510fc53a15280 stepDiv=4608608469604455040 steps=0,1,28,50,201,3798,9376,49774,93766058
        2026-10-06 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-07 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-08 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-09 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        record day=2026-10-05 steps=404e000000000000 ticks=4060400000000000 accepts=false -> emaTicks=4048cccccccccccd emaSteps=4044266666666666 day=2026-10-05 dayTicks=4055c00000000000 daySteps=404d200000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019}
        == after a refused ratio: snapshot manual=3ff428f5c0000000 today=2026-10-05 enabled=true ==
        snapshot.state=emaTicks=4048cccccccccccd emaSteps=4044266666666666 day=2026-10-05 dayTicks=4055c00000000000 daySteps=404d200000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019}
        stored=emaTicks=4048cccccccccccd emaSteps=4044266666666666 day=2026-10-05 dayTicks=4055c00000000000 daySteps=404d200000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019}
        2026-09-30 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-01 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-02 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-03 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-04 factor=3ff3e032e1c9f019 stepDiv=4608273352871243801 steps=0,1,30,53,213,4025,9938,52756,99382715
        2026-10-05 factor=3ff510fc53a15280 stepDiv=4608608469604455040 steps=0,1,28,50,201,3798,9376,49774,93766058
        2026-10-06 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-07 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-08 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-09 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        == two days later: snapshot manual=3ff428f5c0000000 today=2026-10-07 enabled=true ==
        snapshot.state=emaTicks=404c8a3d70a3d70c emaSteps=4045f1eb851eb852 day=2026-10-07 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019,2026-10-05=3ff510fc53a15280}
        stored=emaTicks=404c8a3d70a3d70c emaSteps=4045f1eb851eb852 day=2026-10-07 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019,2026-10-05=3ff510fc53a15280}
        2026-09-30 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-01 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-02 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-03 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-04 factor=3ff3e032e1c9f019 stepDiv=4608273352871243801 steps=0,1,30,53,213,4025,9938,52756,99382715
        2026-10-05 factor=3ff510fc53a15280 stepDiv=4608608469604455040 steps=0,1,28,50,201,3798,9376,49774,93766058
        2026-10-06 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-07 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-08 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-09 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        record day=2026-10-05 steps=404e000000000000 ticks=4052c00000000000 accepts=true -> emaTicks=404c8a3d70a3d70c emaSteps=4045f1eb851eb852 day=2026-10-07 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019,2026-10-05=3ff510fc53a15280}
        == after a late measurement: snapshot manual=3ff428f5c0000000 today=2026-10-07 enabled=true ==
        snapshot.state=emaTicks=404c8a3d70a3d70c emaSteps=4045f1eb851eb852 day=2026-10-07 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019,2026-10-05=3ff510fc53a15280}
        stored=emaTicks=404c8a3d70a3d70c emaSteps=4045f1eb851eb852 day=2026-10-07 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019,2026-10-05=3ff510fc53a15280}
        2026-09-30 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-01 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-02 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-03 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-04 factor=3ff3e032e1c9f019 stepDiv=4608273352871243801 steps=0,1,30,53,213,4025,9938,52756,99382715
        2026-10-05 factor=3ff510fc53a15280 stepDiv=4608608469604455040 steps=0,1,28,50,201,3798,9376,49774,93766058
        2026-10-06 factor=3ff428f5c0000000 stepDiv=4608353354660184064 steps=0,1,29,52,210,3968,9798,52012,97981579
        2026-10-07 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-08 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-09 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        == off again: snapshot manual=4038000000000000 today=2026-10-08 enabled=false ==
        snapshot.state=nil
        stored=emaTicks=404c8a3d70a3d70c emaSteps=4045f1eb851eb852 day=2026-10-07 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019,2026-10-05=3ff510fc53a15280}
        2026-09-30 factor=4038000000000000 stepDiv=4627448617123184640 steps=0,0,2,3,11,208,514,2731,5144033
        2026-10-01 factor=4038000000000000 stepDiv=4627448617123184640 steps=0,0,2,3,11,208,514,2731,5144033
        2026-10-02 factor=4038000000000000 stepDiv=4627448617123184640 steps=0,0,2,3,11,208,514,2731,5144033
        2026-10-03 factor=4038000000000000 stepDiv=4627448617123184640 steps=0,0,2,3,11,208,514,2731,5144033
        2026-10-04 factor=4038000000000000 stepDiv=4627448617123184640 steps=0,0,2,3,11,208,514,2731,5144033
        2026-10-05 factor=4038000000000000 stepDiv=4627448617123184640 steps=0,0,2,3,11,208,514,2731,5144033
        2026-10-06 factor=4038000000000000 stepDiv=4627448617123184640 steps=0,0,2,3,11,208,514,2731,5144033
        2026-10-07 factor=4038000000000000 stepDiv=4627448617123184640 steps=0,0,2,3,11,208,514,2731,5144033
        2026-10-08 factor=4038000000000000 stepDiv=4627448617123184640 steps=0,0,2,3,11,208,514,2731,5144033
        2026-10-09 factor=4038000000000000 stepDiv=4627448617123184640 steps=0,0,2,3,11,208,514,2731,5144033
        == on again, manual under the floor: snapshot manual=3fd3333333333333 today=2026-10-08 enabled=true ==
        snapshot.state=emaTicks=404c8a3d70a3d70c emaSteps=4045f1eb851eb852 day=2026-10-08 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019,2026-10-05=3ff510fc53a15280,2026-10-07=3ff4cef24b026436}
        stored=emaTicks=404c8a3d70a3d70c emaSteps=4045f1eb851eb852 day=2026-10-08 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=4 frozen={2026-10-03=3ff4cccccccccccd,2026-10-04=3ff3e032e1c9f019,2026-10-05=3ff510fc53a15280,2026-10-07=3ff4cef24b026436}
        2026-09-30 factor=3fd3333333333333 stepDiv=4599075939470750515 steps=0,2,74,132,528,10000,24690,131070,246913578
        2026-10-01 factor=3fd3333333333333 stepDiv=4599075939470750515 steps=0,2,74,132,528,10000,24690,131070,246913578
        2026-10-02 factor=3fd3333333333333 stepDiv=4599075939470750515 steps=0,2,74,132,528,10000,24690,131070,246913578
        2026-10-03 factor=3ff4cccccccccccd stepDiv=4608533498688228557 steps=0,1,28,51,203,3846,9496,50412,94966761
        2026-10-04 factor=3ff3e032e1c9f019 stepDiv=4608273352871243801 steps=0,1,30,53,213,4025,9938,52756,99382715
        2026-10-05 factor=3ff510fc53a15280 stepDiv=4608608469604455040 steps=0,1,28,50,201,3798,9376,49774,93766058
        2026-10-06 factor=3fd3333333333333 stepDiv=4599075939470750515 steps=0,2,74,132,528,10000,24690,131070,246913578
        2026-10-07 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-08 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
        2026-10-09 factor=3ff4cef24b026436 stepDiv=4608535858742715446 steps=0,1,28,51,203,3845,9492,50391,94928495
    """.trimIndent()

    private val days = listOf(
        "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05",
        "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09",
    )
    private val tickTotals = listOf(0, 1, 37, 66, 264, 5000, 12345, 65535, 123_456_789)

    private fun hex(x: Double): String = String.format(Locale.ROOT, "%016x", x.toRawBits())

    private fun dump(s: StepCalibration.State): String {
        val frozen = s.frozen.keys.sorted().joinToString(",") { "$it=${hex(s.frozen.getValue(it))}" }
        return "emaTicks=${hex(s.emaTicks)} emaSteps=${hex(s.emaSteps)} day=${s.day ?: "nil"}" +
            " dayTicks=${hex(s.dayTicks)} daySteps=${hex(s.daySteps)} accepted=${s.accepted} frozen={$frozen}"
    }

    private fun render(): String {
        val prefs = MemoryKeyValuePrefs()
        val out = StringBuilder()

        fun show(title: String, manual: Double, today: String) {
            out.append("== $title: snapshot manual=${hex(manual)} today=$today" +
                " enabled=${StepCalibrationStore.isEnabled(prefs)} ==\n")
            val snapshot = StepCalibrationStore.snapshot(manual = manual, today = today, prefs = prefs)
            out.append("snapshot.state=${snapshot.state?.let(::dump) ?: "nil"}\n")
            out.append("stored=${dump(StepCalibrationStore.load(prefs))}\n")
            for (day in days) {
                val f = snapshot.factor(day)
                // The number after `stepDiv=` in the key the engine builds for this day.
                val keyed = AnalyzeRecentDayCache.streamsWitness("s", false, f).substringAfter("|stepDiv=")
                val steps = tickTotals.joinToString(",") {
                    PhysiologicalStepCycleEngine.scaledCycleSteps(it, f).toString()
                }
                out.append("$day factor=${hex(f)} stepDiv=$keyed steps=$steps\n")
            }
        }

        fun record(day: String, steps: Double, ticks: Double) {
            val state = StepCalibrationStore.record(prefs, day = day, steps = steps, ticks = ticks)
            out.append("record day=$day steps=${hex(steps)} ticks=${hex(ticks)}" +
                " accepts=${StepCalibration.accepts(steps, ticks)} -> ${dump(state)}\n")
        }

        val floatManual = 1.26f.toDouble()   // Android keeps the manual divisor as a Float

        // 1. Opt-in off, with a learned state already stored: every day answers the manual divisor.
        record("2026-10-03", steps = 100.0, ticks = 130.0)
        show("off with a stored state", manual = 1.0, today = "2026-10-03")
        show("off, another manual", manual = floatManual, today = "2026-10-04")

        // 2. Opt-in on: the stored day is frozen on the way to today.
        StepCalibrationStore.setEnabled(prefs, true)
        show("on, next day", manual = 1.0, today = "2026-10-04")

        // 3. Measurements today.
        record("2026-10-04", steps = 60.0, ticks = 66.0)
        show("one measurement today", manual = 1.0, today = "2026-10-04")
        record("2026-10-04", steps = 61.5, ticks = 78.0)
        show("two measurements today", manual = 1.0, today = "2026-10-04")

        // 4. A new day with no measurement yet, and a manual divisor that is not on the Double grid.
        show("rollover", manual = floatManual, today = "2026-10-05")
        record("2026-10-05", steps = 58.25, ticks = 87.0)
        show("hand in pocket", manual = floatManual, today = "2026-10-05")

        // 5. A refused ratio and a measurement for a day already left change nothing.
        record("2026-10-05", steps = 60.0, ticks = 130.0)
        show("after a refused ratio", manual = floatManual, today = "2026-10-05")
        show("two days later", manual = floatManual, today = "2026-10-07")
        record("2026-10-05", steps = 60.0, ticks = 75.0)
        show("after a late measurement", manual = floatManual, today = "2026-10-07")

        // 6. Off again: manual for every day, the learned state untouched. Then on, under the floor.
        StepCalibrationStore.setEnabled(prefs, false)
        show("off again", manual = 24.0, today = "2026-10-08")
        StepCalibrationStore.setEnabled(prefs, true)
        show("on again, manual under the floor", manual = 0.3, today = "2026-10-08")

        return out.toString().trimEnd('\n')
    }

    @Test
    fun matchesTheSwiftBuildBitForBit() {
        val want = expected.lines()
        val got = render().lines()
        for (index in 0 until minOf(want.size, got.size)) {
            assertEquals("line ${index + 1} of the oracle", want[index], got[index])
        }
        assertEquals("line count", want.size, got.size)
        // The comparison cannot quietly stop comparing: 162 lines of Swift output.
        assertEquals(162, want.size)
    }
}
