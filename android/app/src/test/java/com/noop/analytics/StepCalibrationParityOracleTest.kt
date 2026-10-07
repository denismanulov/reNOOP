package com.noop.analytics

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [StepCalibration] against the Swift source of truth by ORACLE, not by eye.
 *
 * [expected] is the verbatim stdout of the Swift implementation compiled standalone
 * (`swiftc -O StepCalibration.swift main.swift && ./oracle`, Swift 6.3 on macOS arm64) from
 * `Packages/StrandAnalytics/Sources/StrandAnalytics/StepCalibration.swift`. Every number is printed as
 * its IEEE-754 bit pattern and compared as text, so this is bit for bit: the calibration uses only
 * `+ - * /`, which both languages round identically, and the order of the operations is what a port
 * can get wrong.
 *
 *  - `accepts`: a grid of step and tick counts around every edge (the 30-step minimum and both ends of
 *    the ratio band, each with the value one ulp either side), with zero, negative, NaN and infinity.
 *  - The Swift suite's own scenarios, state after state.
 *  - `long run`: 48 rounds of generated measurements, with fractional step counts, refused ratios,
 *    skipped days, measurements for a day already left and for the next day. After each round the
 *    whole state and the factor for six days around it are printed.
 *  - `json`: Swift's own `JSONEncoder` output for five states, and what its decoder makes of fifteen
 *    hand-written documents. [readsWhatSwiftEncodes] decodes the former; the latter are compared here.
 *    The reverse direction (Swift decoding what [StepCalibration.State.toJson] writes) was run once in
 *    a scratch build and agreed for the same five states; nothing in this repository repeats it.
 *
 * The oracle only guards this direction. `StepCalibrationTests` on the Swift side is what stops Swift
 * drifting away from Kotlin.
 */
class StepCalibrationParityOracleTest {

    /** Verbatim stdout of the Swift build. Do not hand-edit: regenerate from the oracle. */
    private val expected = """
        == constants ==
        ratioBand=3ff0000000000000...3ffb333333333333 minimumSteps=403e000000000000 dayAlpha=3fc999999999999a priorSteps=405e000000000000 frozenDayLimit=400
        == accepts ==
        steps=4034000000000000: 00000000000000000000000
        steps=403dffffffffffff: 00000000000000000000000
        steps=403e000000000000: 00000000101110110010000
        steps=403e000000000001: 00000000101110110010000
        steps=404b800000000000: 00001110101110110000000
        steps=404e000000000000: 00000110101110110001000
        steps=404f800000000000: 00000110101110110001000
        steps=4059000000000000: 00000001101110110001000
        steps=405e000000000000: 00000000101110110000000
        steps=41cdcd6500000000: 00000000101110110000100
        steps=0000000000000000: 00000000000000000000000
        steps=c04e000000000000: 00000000000000000000000
        steps=7ff8000000000000: 00000000000000000000000
        steps=7ff0000000000000: 00000000000000000000000
        == octave ==
        ratio=3ff23d70a3d70a3d ticks=4051199999999999 true false false
        ratio=3ff30a3d70a3d70a ticks=4051d99999999999 true false false
        ratio=3ff3d70a3d70a3d7 ticks=405299999999999a true false false
        ratio=3ff4a3d70a3d70a3 ticks=4053599999999999 true false false
        ratio=3ff570a3d70a3d70 ticks=4054199999999999 true false false
        ratio=3ff63d70a3d70a3d ticks=4054d99999999999 true false false
        ratio=3ff70a3d70a3d70a ticks=4055999999999999 true false false
        ratio=3ff7d70a3d70a3d7 ticks=405659999999999a true false false
        == swift test scenarios ==
        manual: emaTicks=0000000000000000 emaSteps=0000000000000000 day=2026-10-03 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        manual: factor(10-03)=3ff428f5c28f5c29 factor(09-30)=3ff428f5c28f5c29 longRun=nil
        firstDay: emaTicks=0000000000000000 emaSteps=0000000000000000 day=2026-10-03 dayTicks=4074400000000000 daySteps=406e000000000000 accepted=2 frozen={}
        firstDay: factor=3ff599999999999a
        refused: emaTicks=0000000000000000 emaSteps=0000000000000000 day=2026-10-03 dayTicks=4052c00000000000 daySteps=404e000000000000 accepted=1 frozen={}
        refused: unchanged=true
        leaving: emaTicks=4039000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=1 frozen={2026-10-03=3ff4000000000000}
        leaving: longRun=3ff4000000000000 factor(10-04)=3ff4000000000000
        ownEvidence: emaTicks=4049000000000000 emaSteps=4044000000000000 day=2026-10-04 dayTicks=40747ccccccccccd daySteps=406b800000000000 accepted=5 frozen={2026-10-03=3ff4000000000000}
        ownEvidence: today=3ff67c15af48e27c expected=3ff67c15af48e27c history=3ff4000000000000
        ema: emaTicks=404799999999999a emaSteps=4042000000000000 day=2026-10-03 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=2 frozen={2026-10-01=3ff3333333333333,2026-10-02=3ff4a7904a7904a8}
        ema: longRun=3ff4fa4fa4fa4fa5 expected=3ff4fa4fa4fa4fa5
        unmeasured: emaTicks=403a000000000000 emaSteps=4034000000000000 day=2026-10-03 dayTicks=405b800000000000 daySteps=4059000000000000 accepted=2 frozen={2026-10-01=3ff4cccccccccccd,2026-10-02=3ff4cccccccccccd}
        unmeasured: factor(10-02)=3ff4cccccccccccd
        left: emaTicks=4039000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=1 frozen={2026-10-03=3ff4000000000000}
        left: unchanged=true
        bounded before: emaTicks=405f400000000000 emaSteps=4059000000000000 day=2026-0429 dayTicks=405f400000000000 daySteps=4059000000000000 accepted=430 frozen=#400:ec7b890954ae9bf1:2026-0029..2026-0428
        bounded after: emaTicks=405f400000000000 emaSteps=4059000000000000 day=2027-0000 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=430 frozen=#400:fef5ef7eff649a4d:2026-0030..2026-0429
        bounded: count=400 has2026-0000=false has2026-0029=false has2026-0030=true
        == long run ==
        r0 2026-01-02: emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        r0 factors: today=3ff428f5c28f5c29 running=3ff428f5c28f5c29 yesterday=3ff428f5c28f5c29 tomorrow=3ff428f5c28f5c29 nextMonth=3ff428f5c28f5c29 past=3ff428f5c28f5c29 longRun=nil
        r1 2026-01-05: emaTicks=0000000000000000 emaSteps=0000000000000000 day=2026-01-05 dayTicks=4055800000000000 daySteps=404eae5604189375 accepted=1 frozen={}
        r1 factors: today=3ff66c9f09051094 running=3ff66c9f09051094 yesterday=3ff428f5c28f5c29 tomorrow=3ff66c9f09051094 nextMonth=3ff66c9f09051094 past=3ff428f5c28f5c29 longRun=nil
        r2 2026-01-07: emaTicks=4031333333333333 emaSteps=40288b780346dc5e day=2026-01-07 dayTicks=4068800000000000 daySteps=4060749374bc6a7f accepted=3 frozen={2026-01-05=3ff66c9f09051094}
        r2 factors: today=3ff727cdfe1f3f79 running=3ff727cdfe1f3f79 yesterday=3ff428f5c28f5c29 tomorrow=3ff7713efc75af57 nextMonth=3ff7713efc75af57 past=3ff428f5c28f5c29 longRun=3ff66c9f09051094
        r3 2026-01-08: emaTicks=4031333333333333 emaSteps=40288b780346dc5e day=2026-01-07 dayTicks=406ee00000000000 daySteps=4065f5c28f5c28f6 accepted=4 frozen={2026-01-05=3ff66c9f09051094}
        r3 factors: today=3ff67adb457604af running=3ff677714e3052ec yesterday=3ff677714e3052ec tomorrow=3ff67adb457604af nextMonth=3ff67adb457604af past=3ff428f5c28f5c29 longRun=3ff66c9f09051094
        r4 2026-01-08: emaTicks=404f947ae147ae15 emaSteps=40467a1a0cf1800b day=2026-01-08 dayTicks=4051000000000000 daySteps=404f6645a1cac083 accepted=5 frozen={2026-01-05=3ff66c9f09051094,2026-01-07=3ff677714e3052ec}
        r4 factors: today=3ff4b582a3c6b92a running=3ff4b582a3c6b92a yesterday=3ff677714e3052ec tomorrow=3ff52547f0461329 nextMonth=3ff52547f0461329 past=3ff428f5c28f5c29 longRun=3ff67adb457604af
        r5 2026-01-10: emaTicks=4050083126e978d6 emaSteps=404842ef911cf357 day=2026-01-10 dayTicks=406b600000000000 daySteps=4065466e978d4fe0 accepted=7 frozen={2026-01-05=3ff66c9f09051094,2026-01-07=3ff677714e3052ec,2026-01-08=3ff4b582a3c6b92a}
        r5 factors: today=3ff4d1787e93043a running=3ff4d1787e93043a yesterday=3ff428f5c28f5c29 tomorrow=3ff4e28436b6765c nextMonth=3ff4e28436b6765c past=3ff428f5c28f5c29 longRun=3ff52547f0461329
        r6 2026-01-10: emaTicks=4050083126e978d6 emaSteps=404842ef911cf357 day=2026-01-10 dayTicks=407a900000000000 daySteps=407340f1a9fbe76d accepted=9 frozen={2026-01-05=3ff66c9f09051094,2026-01-07=3ff677714e3052ec,2026-01-08=3ff4b582a3c6b92a}
        r6 factors: today=3ff5d04323d8bb5d running=3ff5d04323d8bb5d yesterday=3ff428f5c28f5c29 tomorrow=3ff5b708c05bd43f nextMonth=3ff5b708c05bd43f past=3ff428f5c28f5c29 longRun=3ff52547f0461329
        r7 2026-01-11: emaTicks=4050083126e978d6 emaSteps=404842ef911cf357 day=2026-01-10 dayTicks=407a900000000000 daySteps=407340f1a9fbe76d accepted=9 frozen={2026-01-05=3ff66c9f09051094,2026-01-07=3ff677714e3052ec,2026-01-08=3ff4b582a3c6b92a}
        r7 factors: today=3ff5b708c05bd43f running=3ff5d04323d8bb5d yesterday=3ff5d04323d8bb5d tomorrow=3ff5b708c05bd43f nextMonth=3ff5b708c05bd43f past=3ff428f5c28f5c29 longRun=3ff52547f0461329
        r8 2026-01-16: emaTicks=406104e9c4545847 emaSteps=4059115a47968ef1 day=2026-01-16 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=12 frozen={2026-01-05=3ff66c9f09051094,2026-01-07=3ff677714e3052ec,2026-01-08=3ff4b582a3c6b92a,2026-01-10=3ff5d04323d8bb5d,2026-01-13=3ff6ba7ea3f07ea1,2026-01-15=3ff562ac2044961e}
        r8 factors: today=3ff5b9c4d7e87c16 running=3ff5b9c4d7e87c16 yesterday=3ff562ac2044961e tomorrow=3ff5b9c4d7e87c16 nextMonth=3ff5b9c4d7e87c16 past=3ff428f5c28f5c29 longRun=3ff5b9c4d7e87c16
        r9 2026-01-16: emaTicks=406104e9c4545847 emaSteps=4059115a47968ef1 day=2026-01-16 dayTicks=406c200000000000 daySteps=40644883126e978e accepted=14 frozen={2026-01-05=3ff66c9f09051094,2026-01-07=3ff677714e3052ec,2026-01-08=3ff4b582a3c6b92a,2026-01-10=3ff5d04323d8bb5d,2026-01-13=3ff6ba7ea3f07ea1,2026-01-15=3ff562ac2044961e}
        r9 factors: today=3ff5fd7c36454b9e running=3ff5fd7c36454b9e yesterday=3ff562ac2044961e tomorrow=3ff5dbb2b49fef98 nextMonth=3ff5dbb2b49fef98 past=3ff428f5c28f5c29 longRun=3ff5b9c4d7e87c16
        r10 2026-01-18: emaTicks=40633d87d04379d2 emaSteps=405c2ae30d717bc6 day=2026-01-18 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=14 frozen=#7:c58ed29683b0c1d7:2026-01-05..2026-01-16
        r10 factors: today=3ff5dbb2b49fef98 running=3ff5dbb2b49fef98 yesterday=3ff428f5c28f5c29 tomorrow=3ff5dbb2b49fef98 nextMonth=3ff5dbb2b49fef98 past=3ff428f5c28f5c29 longRun=3ff5dbb2b49fef98
        r11 2026-01-18: emaTicks=40633d87d04379d2 emaSteps=405c2ae30d717bc6 day=2026-01-18 dayTicks=404a800000000000 daySteps=40470ac083126e98 accepted=15 frozen=#7:c58ed29683b0c1d7:2026-01-05..2026-01-16
        r11 factors: today=3ff4e6227a678f47 running=3ff4e6227a678f47 yesterday=3ff428f5c28f5c29 tomorrow=3ff5899936bf30e2 nextMonth=3ff5899936bf30e2 past=3ff428f5c28f5c29 longRun=3ff5dbb2b49fef98
        r12 2026-01-22: emaTicks=4060b79fd9cf94a8 emaSteps=4058d695b1763ae2 day=2026-01-22 dayTicks=406f000000000000 daySteps=4067404189374bc7 accepted=18 frozen=#8:6b7ea7e403e24f55:2026-01-05..2026-01-18
        r12 factors: today=3ff569afa84c6ba5 running=3ff569afa84c6ba5 yesterday=3ff428f5c28f5c29 tomorrow=3ff578dc381ea4cb nextMonth=3ff578dc381ea4cb past=3ff428f5c28f5c29 longRun=3ff5899936bf30e2
        r13 2026-01-23: emaTicks=406392e647d943ba emaSteps=405d2bc52b41809e day=2026-01-23 dayTicks=4056c00000000000 daySteps=404e5a3d70a3d70c accepted=19 frozen=#9:0ebfc58859960828:2026-01-05..2026-01-22
        r13 factors: today=3ff650f40c1426af running=3ff650f40c1426af yesterday=3ff569afa84c6ba5 tomorrow=3ff5c2e531de4a46 nextMonth=3ff5c2e531de4a46 past=3ff428f5c28f5c29 longRun=3ff578dc381ea4cb
        r14 2026-01-24: emaTicks=4061ef1e9fe102fb emaSteps=405a5f3d7aab2f9a day=2026-01-24 dayTicks=4067600000000000 daySteps=40632526e978d4fe accepted=21 frozen=#10:5ee228a859fbb784:2026-01-05..2026-01-23
        r14 factors: today=3ff483554e3b070f running=3ff483554e3b070f yesterday=3ff650f40c1426af tomorrow=3ff52b1cfefdcd8a nextMonth=3ff52b1cfefdcd8a past=3ff428f5c28f5c29 longRun=3ff5c2e531de4a46
        r15 2026-01-25: emaTicks=4061ef1e9fe102fb emaSteps=405a5f3d7aab2f9a day=2026-01-24 dayTicks=4071300000000000 daySteps=4069d3df3b645a1d accepted=22 frozen=#10:5ee228a859fbb784:2026-01-05..2026-01-23
        r15 factors: today=3ff59ba84577e49f running=3ff5776201187624 yesterday=3ff5776201187624 tomorrow=3ff59ba84577e49f nextMonth=3ff59ba84577e49f past=3ff428f5c28f5c29 longRun=3ff5c2e531de4a46
        r16 2026-01-27: emaTicks=406538e54cb40262 emaSteps=405f6dbdad177d54 day=2026-01-27 dayTicks=407d200000000000 daySteps=40730d89374bc6a8 accepted=25 frozen=#11:5351e533e02b630d:2026-01-05..2026-01-24
        r16 factors: today=3ff7a737a55141b4 running=3ff7a737a55141b4 yesterday=3ff428f5c28f5c29 tomorrow=3ff6af0ba85fd798 nextMonth=3ff6af0ba85fd798 past=3ff428f5c28f5c29 longRun=3ff59ba84577e49f
        r17 2026-02-01: emaTicks=406ca0b7709001e8 emaSteps=4064314f8e8e1b32 day=2026-02-01 dayTicks=4064600000000000 daySteps=405f4a3d70a3d70a accepted=27 frozen=#12:b47c6f55878a2802:2026-01-05..2026-01-27
        r17 factors: today=3ff5bdb9f01785a3 running=3ff5bdb9f01785a3 yesterday=3ff428f5c28f5c29 tomorrow=3ff66258085f8fdb nextMonth=3ff66258085f8fdb past=3ff428f5c28f5c29 longRun=3ff6af0ba85fd798
        r18 2026-02-02: emaTicks=406ca0b7709001e8 emaSteps=4064314f8e8e1b32 day=2026-02-01 dayTicks=4064600000000000 daySteps=405f4a3d70a3d70a accepted=27 frozen=#12:b47c6f55878a2802:2026-01-05..2026-01-27
        r18 factors: today=3ff66258085f8fdb running=3ff5bdb9f01785a3 yesterday=3ff5bdb9f01785a3 tomorrow=3ff66258085f8fdb nextMonth=3ff66258085f8fdb past=3ff428f5c28f5c29 longRun=3ff6af0ba85fd798
        r19 2026-02-06: emaTicks=4068c8237b3d71dc emaSteps=406238a59ab927f0 day=2026-02-06 dayTicks=4057c00000000000 daySteps=4055d8f5c28f5c2a accepted=30 frozen=#14:e6ec9da6117137b8:2026-01-05..2026-02-05
        r19 factors: today=3ff3eb9ecd3b7903 running=3ff3eb9ecd3b7903 yesterday=3ff46c854b3fa9aa tomorrow=3ff531058d23ec0a nextMonth=3ff531058d23ec0a past=3ff428f5c28f5c29 longRun=3ff5c2c08ce3dc6d
        r20 2026-02-09: emaTicks=4066334f95cac17d emaSteps=4060c30375a275f8 day=2026-02-09 dayTicks=405b000000000000 daySteps=4050116872b020c5 accepted=31 frozen=#15:9c2abc99b069632d:2026-01-05..2026-02-06
        r20 factors: today=3ff72d75d847237a running=3ff72d75d847237a yesterday=3ff428f5c28f5c29 tomorrow=3ff5cd01356798d8 nextMonth=3ff5cd01356798d8 past=3ff428f5c28f5c29 longRun=3ff531058d23ec0a
        r21 2026-02-11: emaTicks=40616b1436e829ea emaSteps=405998aa3bf8023c day=2026-02-11 dayTicks=406e200000000000 daySteps=40672e5e353f7cee accepted=34 frozen=#17:5d6cf3357c670eb9:2026-01-05..2026-02-10
        r21 factors: today=3ff52dd518923460 running=3ff52dd518923460 yesterday=3ff5b77fa555d02a tomorrow=3ff578320a9522bb nextMonth=3ff578320a9522bb past=3ff428f5c28f5c29 longRun=3ff5c6a4e0ce9630
        r22 2026-02-14: emaTicks=4061f1549e05398c emaSteps=405ac34854e91be5 day=2026-02-14 dayTicks=4052c00000000000 daySteps=40481126e978d4ff accepted=36 frozen=#19:0eac65b9de1cb40b:2026-01-05..2026-02-13
        r22 factors: today=3ff672fb7735b02f running=3ff672fb7735b02f yesterday=3ff56c3e6104818a tomorrow=3ff5ce20459f07f1 nextMonth=3ff5ce20459f07f1 past=3ff428f5c28f5c29 longRun=3ff574315295cac0
        r23 2026-02-15: emaTicks=40603aaa18042e0a emaSteps=4057d12428135ed1 day=2026-02-15 dayTicks=406de00000000000 daySteps=40675cbc6a7ef9dc accepted=39 frozen=#20:b0d8aaa3b1833fd9:2026-01-05..2026-02-14
        r23 factors: today=3ff4fc7757f3595b running=3ff4fc7757f3595b yesterday=3ff672fb7735b02f tomorrow=3ff55cd68123c160 nextMonth=3ff55cd68123c160 past=3ff428f5c28f5c29 longRun=3ff5ce20459f07f1
        r24 2026-02-15: emaTicks=40603aaa18042e0a emaSteps=4057d12428135ed1 day=2026-02-15 dayTicks=406de00000000000 daySteps=40675cbc6a7ef9dc accepted=39 frozen=#20:b0d8aaa3b1833fd9:2026-01-05..2026-02-14
        r24 factors: today=3ff4fc7757f3595b running=3ff4fc7757f3595b yesterday=3ff672fb7735b02f tomorrow=3ff55cd68123c160 nextMonth=3ff55cd68123c160 past=3ff428f5c28f5c29 longRun=3ff5ce20459f07f1
        r25 2026-02-17: emaTicks=40603aaa18042e0a emaSteps=4057d12428135ed1 day=2026-02-15 dayTicks=406de00000000000 daySteps=40675cbc6a7ef9dc accepted=39 frozen=#20:b0d8aaa3b1833fd9:2026-01-05..2026-02-14
        r25 factors: today=3ff55cd68123c160 running=3ff4fc7757f3595b yesterday=3ff55cd68123c160 tomorrow=3ff55cd68123c160 nextMonth=3ff55cd68123c160 past=3ff428f5c28f5c29 longRun=3ff5ce20459f07f1
        r26 2026-02-19: emaTicks=4062f554e0035808 emaSteps=405c6601e4424966 day=2026-02-19 dayTicks=404e800000000000 daySteps=40433d4fdf3b645a accepted=40 frozen=#21:965142ccdc674a01:2026-01-05..2026-02-15
        r26 factors: today=3ff65594c8aeb7aa running=3ff65594c8aeb7aa yesterday=3ff428f5c28f5c29 tomorrow=3ff5acd277d655fd nextMonth=3ff5acd277d655fd past=3ff428f5c28f5c29 longRun=3ff55cd68123c160
        r27 2026-02-22: emaTicks=4060b110b335e006 emaSteps=4058a48980215e5b day=2026-02-21 dayTicks=4048000000000000 daySteps=4046b7ced916872c accepted=41 frozen=#22:00ff00c44ff7eb74:2026-01-05..2026-02-19
        r27 factors: today=3ff52e9624b4c460 running=3ff45d4b7f4cd462 yesterday=3ff45d4b7f4cd462 tomorrow=3ff52e9624b4c460 nextMonth=3ff52e9624b4c460 past=3ff428f5c28f5c29 longRun=3ff5acd277d655fd
        r28 2026-02-23: emaTicks=405d1b4deb896670 emaSteps=4055fc6915d025cd day=2026-02-23 dayTicks=4062800000000000 daySteps=405634395810624e accepted=42 frozen=#23:57aaf23bef1f76a1:2026-01-05..2026-02-21
        r28 factors: today=3ff7834346814371 running=3ff7834346814371 yesterday=3ff428f5c28f5c29 tomorrow=3ff64960c1d77e71 nextMonth=3ff64960c1d77e71 past=3ff428f5c28f5c29 longRun=3ff52e9624b4c460
        r29 2026-02-24: emaTicks=40610485916a28fa emaSteps=4058690ed8841c9a day=2026-02-24 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=43 frozen=#24:f454e8bc06665e11:2026-01-05..2026-02-23
        r29 factors: today=3ff64f0a7ca3f4ed running=3ff64f0a7ca3f4ed yesterday=3ff753c29450d27a tomorrow=3ff64f0a7ca3f4ed nextMonth=3ff64f0a7ca3f4ed past=3ff428f5c28f5c29 longRun=3ff64f0a7ca3f4ed
        r30 2026-02-24: emaTicks=40610485916a28fa emaSteps=4058690ed8841c9a day=2026-02-24 dayTicks=4053c00000000000 daySteps=4051804189374bc7 accepted=44 frozen=#24:f454e8bc06665e11:2026-01-05..2026-02-23
        r30 factors: today=3ff4bdef651f4894 running=3ff4bdef651f4894 yesterday=3ff753c29450d27a tomorrow=3ff5a99183c917c4 nextMonth=3ff5a99183c917c4 past=3ff428f5c28f5c29 longRun=3ff64f0a7ca3f4ed
        r31 2026-02-25: emaTicks=405f2da2824374c3 emaSteps=4057074c2f0e593d day=2026-02-25 dayTicks=405b000000000000 daySteps=40507df3b645a1cb accepted=45 frozen=#25:a1592468d796ec4c:2026-01-05..2026-02-24
        r31 factors: today=3ff745222fa37af9 running=3ff745222fa37af9 yesterday=3ff4bdef651f4894 tomorrow=3ff659c03eee9f25 nextMonth=3ff659c03eee9f25 past=3ff428f5c28f5c29 longRun=3ff5a99183c917c4
        r32 2026-02-28: emaTicks=405f2da2824374c3 emaSteps=4057074c2f0e593d day=2026-02-25 dayTicks=405b000000000000 daySteps=40507df3b645a1cb accepted=45 frozen=#25:a1592468d796ec4c:2026-01-05..2026-02-24
        r32 factors: today=3ff659c03eee9f25 running=3ff745222fa37af9 yesterday=3ff659c03eee9f25 tomorrow=3ff659c03eee9f25 nextMonth=3ff659c03eee9f25 past=3ff428f5c28f5c29 longRun=3ff5a99183c917c4
        r33 2026-03-01: emaTicks=405e57b53502c3d0 emaSteps=4055b8a0e3b3015a day=2026-03-01 dayTicks=4060000000000000 daySteps=405395810624dd30 accepted=46 frozen=#26:1b2da531566cfd65:2026-01-05..2026-02-25
        r33 factors: today=3ff7d9495c04c493 running=3ff7d9495c04c493 yesterday=3ff428f5c28f5c29 tomorrow=3ff70c5e68cde392 nextMonth=3ff70c5e68cde392 past=3ff428f5c28f5c29 longRun=3ff659c03eee9f25
        r34 2026-03-02: emaTicks=405eac90f7356974 emaSteps=40554b341dc9c6ec day=2026-03-02 dayTicks=404c800000000000 daySteps=4047a8f5c28f5c29 accepted=47 frozen=#27:6fd7124f3f5fb992:2026-01-05..2026-03-01
        r34 factors: today=3ff5fb0a1ebbafa3 running=3ff5fb0a1ebbafa3 yesterday=3ff7d9495c04c493 tomorrow=3ff696815582f275 nextMonth=3ff696815582f275 past=3ff428f5c28f5c29 longRun=3ff70c5e68cde392
        r35 2026-03-03: emaTicks=405eac90f7356974 emaSteps=40554b341dc9c6ec day=2026-03-02 dayTicks=404c800000000000 daySteps=4047a8f5c28f5c29 accepted=47 frozen=#27:6fd7124f3f5fb992:2026-01-05..2026-03-01
        r35 factors: today=3ff696815582f275 running=3ff5fb0a1ebbafa3 yesterday=3ff5fb0a1ebbafa3 tomorrow=3ff696815582f275 nextMonth=3ff696815582f275 past=3ff428f5c28f5c29 longRun=3ff70c5e68cde392
        r36 2026-03-04: emaTicks=405eac90f7356974 emaSteps=40554b341dc9c6ec day=2026-03-02 dayTicks=404c800000000000 daySteps=4047a8f5c28f5c29 accepted=47 frozen=#27:6fd7124f3f5fb992:2026-01-05..2026-03-01
        r36 factors: today=3ff696815582f275 running=3ff5fb0a1ebbafa3 yesterday=3ff696815582f275 tomorrow=3ff696815582f275 nextMonth=3ff696815582f275 past=3ff428f5c28f5c29 longRun=3ff70c5e68cde392
        r37 2026-03-06: emaTicks=405b63a72c2abac4 emaSteps=405366a8ab495b8e day=2026-03-06 dayTicks=4064400000000000 daySteps=406016872b020c4a accepted=49 frozen=#28:63e4488c008b2a33:2026-01-05..2026-03-02
        r37 factors: today=3ff5521af6344ff6 running=3ff5521af6344ff6 yesterday=3ff428f5c28f5c29 tomorrow=3ff5dec67433a155 nextMonth=3ff5dec67433a155 past=3ff428f5c28f5c29 longRun=3ff696815582f275
        r38 2026-03-07: emaTicks=40616e42de777de8 emaSteps=405a32ad700b2c2a day=2026-03-07 dayTicks=404c800000000000 daySteps=40494c083126e979 accepted=51 frozen=#29:107f24bac30444b5:2026-01-05..2026-03-06
        r38 factors: today=3ff4529bb7e82bf8 running=3ff4529bb7e82bf8 yesterday=3ff48cfc1662f698 tomorrow=3ff4f07cc305c47e nextMonth=3ff4f07cc305c47e past=3ff428f5c28f5c29 longRun=3ff54a83bf0a76ee
        r39 2026-03-08: emaTicks=405ebd37ca58c974 emaSteps=40577cf1f8266de2 day=2026-03-08 dayTicks=4071400000000000 daySteps=4068c36c8b439582 accepted=54 frozen=#30:78f1c72a47340bdb:2026-01-05..2026-03-07
        r39 factors: today=3ff5c7f7d4ee0b6b running=3ff5c7f7d4ee0b6b yesterday=3ff4529bb7e82bf8 tomorrow=3ff567ec3d8f8e83 nextMonth=3ff567ec3d8f8e83 past=3ff428f5c28f5c29 longRun=3ff4f07cc305c47e
        r40 2026-03-11: emaTicks=4063321650f05095 emaSteps=405cb220316cfa1c day=2026-03-11 dayTicks=4062e00000000000 daySteps=405c73645a1cac08 accepted=56 frozen=#31:edc2707935af5ab2:2026-01-05..2026-03-08
        r40 factors: today=3ff551f502c98bae running=3ff551f502c98bae yesterday=3ff428f5c28f5c29 tomorrow=3ff55ef584fc5eae nextMonth=3ff55ef584fc5eae past=3ff428f5c28f5c29 longRun=3ff567ec3d8f8e83
        r41 2026-03-12: emaTicks=406321ab73f373aa emaSteps=405ca59439901db2 day=2026-03-12 dayTicks=4062200000000000 daySteps=405c9ced916872b0 accepted=58 frozen=#32:c62647bdb2748c61:2026-01-05..2026-03-11
        r41 factors: today=3ff4d56fff94f82b running=3ff4d56fff94f82b yesterday=3ff551f502c98bae tomorrow=3ff526ab9cb4d5c6 nextMonth=3ff526ab9cb4d5c6 past=3ff428f5c28f5c29 longRun=3ff55ef584fc5eae
        r42 2026-03-13: emaTicks=406321ab73f373aa emaSteps=405ca59439901db2 day=2026-03-12 dayTicks=4062200000000000 daySteps=405c9ced916872b0 accepted=58 frozen=#32:c62647bdb2748c61:2026-01-05..2026-03-11
        r42 factors: today=3ff526ab9cb4d5c6 running=3ff4d56fff94f82b yesterday=3ff4d56fff94f82b tomorrow=3ff526ab9cb4d5c6 nextMonth=3ff526ab9cb4d5c6 past=3ff428f5c28f5c29 longRun=3ff55ef584fc5eae
        r43 2026-03-15: emaTicks=4062ee22c3292955 emaSteps=405ca3d94b21c84c day=2026-03-15 dayTicks=4051800000000000 daySteps=4047f4395810624e accepted=59 frozen=#33:7fc04c15f00c98c2:2026-01-05..2026-03-12
        r43 factors: today=3ff5c957b560c384 running=3ff5c957b560c384 yesterday=3ff428f5c28f5c29 tomorrow=3ff55ca27baf8781 nextMonth=3ff55ca27baf8781 past=3ff428f5c28f5c29 longRun=3ff526ab9cb4d5c6
        r44 2026-03-18: emaTicks=4060e4e89c20edde emaSteps=40594eb391b643ab day=2026-03-17 dayTicks=4071700000000000 daySteps=40669ee978d4fdf4 accepted=61 frozen=#34:e41c9f3a678fca81:2026-01-05..2026-03-15
        r44 factors: today=3ff66207772f57c8 running=3ff7597c82fe31ca yesterday=3ff7597c82fe31ca tomorrow=3ff66207772f57c8 nextMonth=3ff66207772f57c8 past=3ff428f5c28f5c29 longRun=3ff55ca27baf8781
        r45 2026-03-22: emaTicks=40647d86e34d8b18 emaSteps=405d4b5371b3ceeb day=2026-03-22 dayTicks=404a000000000000 daySteps=404669fbe76c8b44 accepted=62 frozen=#35:30b95e5edbc66e0e:2026-01-05..2026-03-17
        r45 factors: today=3ff557d9a6329b78 running=3ff557d9a6329b78 yesterday=3ff428f5c28f5c29 tomorrow=3ff60c983343d09d nextMonth=3ff60c983343d09d past=3ff428f5c28f5c29 longRun=3ff66207772f57c8
        r46 2026-03-22: emaTicks=40647d86e34d8b18 emaSteps=405d4b5371b3ceeb day=2026-03-22 dayTicks=406cc00000000000 daySteps=406457ae147ae148 accepted=64 frozen=#35:30b95e5edbc66e0e:2026-01-05..2026-03-17
        r46 factors: today=3ff683e4f6a9c733 running=3ff683e4f6a9c733 yesterday=3ff428f5c28f5c29 tomorrow=3ff671315c1bdbc0 nextMonth=3ff671315c1bdbc0 past=3ff428f5c28f5c29 longRun=3ff66207772f57c8
        r47 2026-03-23: emaTicks=40647d86e34d8b18 emaSteps=405d4b5371b3ceeb day=2026-03-22 dayTicks=406cc00000000000 daySteps=406457ae147ae148 accepted=64 frozen=#35:30b95e5edbc66e0e:2026-01-05..2026-03-17
        r47 factors: today=3ff671315c1bdbc0 running=3ff683e4f6a9c733 yesterday=3ff683e4f6a9c733 tomorrow=3ff671315c1bdbc0 nextMonth=3ff671315c1bdbc0 past=3ff428f5c28f5c29 longRun=3ff66207772f57c8
        == json ==
        empty state: emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        empty json: {"accepted":0,"daySteps":0,"dayTicks":0,"emaSteps":0,"emaTicks":0,"frozen":{}}
        empty roundTrip=true
        dayOnly state: emaTicks=0000000000000000 emaSteps=0000000000000000 day=2026-10-03 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        dayOnly json: {"accepted":0,"day":"2026-10-03","daySteps":0,"dayTicks":0,"emaSteps":0,"emaTicks":0,"frozen":{}}
        dayOnly roundTrip=true
        twoDays state: emaTicks=4039000000000000 emaSteps=4034000000000000 day=2026-10-04 dayTicks=4054000000000000 daySteps=404b800000000000 accepted=2 frozen={2026-10-03=3ff4000000000000}
        twoDays json: {"accepted":2,"day":"2026-10-04","daySteps":55,"dayTicks":80,"emaSteps":20,"emaTicks":25,"frozen":{"2026-10-03":1.25}}
        twoDays roundTrip=true
        pockets state: emaTicks=405a63d70a3d70a4 emaSteps=4053000000000000 day=2026-10-05 dayTicks=0000000000000000 daySteps=0000000000000000 accepted=5 frozen={2026-10-03=3ff4000000000000,2026-10-04=3ff67c15af48e27c}
        pockets json: {"accepted":5,"day":"2026-10-05","daySteps":0,"dayTicks":0,"emaSteps":76,"emaTicks":105.56,"frozen":{"2026-10-03":1.25,"2026-10-04":1.4052941176470588}}
        pockets roundTrip=true
        longRun state: emaTicks=40647d86e34d8b18 emaSteps=405d4b5371b3ceeb day=2026-03-22 dayTicks=406cc00000000000 daySteps=406457ae147ae148 accepted=64 frozen=#35:30b95e5edbc66e0e:2026-01-05..2026-03-17
        longRun json: {"accepted":64,"day":"2026-03-22","daySteps":162.74,"dayTicks":230,"emaSteps":117.17696802672769,"emaTicks":163.92271580834563,"frozen":{"2026-01-05":1.4015188553176232,"2026-01-07":1.4041607908486027,"2026-01-08":1.294314040900256,"2026-01-10":1.3633452797182237,"2026-01-13":1.4205309299358435,"2026-01-15":1.3365899334397748,"2026-01-16":1.374386035914291,"2026-01-18":1.3061852246691659,"2026-01-22":1.3383022855912057,"2026-01-23":1.394763991529924,"2026-01-24":1.3416461985392596,"2026-01-27":1.4783245523177415,"2026-02-01":1.3588199022854972,"2026-02-05":1.2764943065072694,"2026-02-06":1.245024491966945,"2026-02-09":1.4485987137179266,"2026-02-10":1.3572994669348284,"2026-02-11":1.3236895522889185,"2026-02-13":1.3389266767263472,"2026-02-14":1.4030718475942299,"2026-02-15":1.311637252385103,"2026-02-19":1.3958938445243612,"2026-02-21":1.2727770779286165,"2026-02-23":1.4579492372114644,"2026-02-24":1.2963708829961762,"2026-02-25":1.4543783055193542,"2026-03-01":1.4905484766461867,"2026-03-02":1.373788948108065,"2026-03-06":1.2844200968242259,"2026-03-07":1.2701680358757113,"2026-03-08":1.3613203351567005,"2026-03-11":1.332509051215975,"2026-03-12":1.3021087630909374,"2026-03-15":1.361655910976169,"2026-03-17":1.4593472592269898}}
        longRun roundTrip=true
        decode missing accepted: nil
        decode missing frozen: nil
        decode missing emaTicks: nil
        decode null day: emaTicks=3ff8000000000000 emaSteps=3ff0000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=2 frozen={}
        decode string number: nil
        decode frozen string value: nil
        decode frozen array: nil
        decode numeric day: nil
        decode fractional accepted: nil
        decode accepted 2.0: emaTicks=3ff8000000000000 emaSteps=3ff0000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=2 frozen={}
        decode extra key: emaTicks=3ff8000000000000 emaSteps=3ff0000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=2 frozen={2026-10-03=3ff4000000000000}
        decode exponent: emaTicks=402e000000000000 emaSteps=3ee4f8b588e368f1 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=2 frozen={}
        decode not json: nil
        decode array: nil
        decode empty object: nil
    """.trimIndent()

    private fun hex(v: Double): String = java.lang.Long.toHexString(v.toRawBits()).padStart(16, '0')

    private fun opt(v: Double?): String = if (v == null) "nil" else hex(v)

    /** FNV-1a (64-bit) over each key's UTF-8 bytes and its value's bits, keys in order. */
    private fun fnvFrozen(frozen: Map<String, Double>): String {
        var h = 0xcbf29ce484222325uL.toLong()
        for (key in frozen.keys.sorted()) {
            for (b in key.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            var bits = frozen.getValue(key).toRawBits()
            repeat(8) {
                h = h xor (bits and 0xff)
                h *= 0x100000001b3L
                bits = bits ushr 8
            }
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }

    private fun dump(s: StepCalibration.State): String {
        val keys = s.frozen.keys.sorted()
        val frozen = if (keys.size <= 6) {
            "{" + keys.joinToString(",") { "$it=${hex(s.frozen.getValue(it))}" } + "}"
        } else {
            "#${keys.size}:${fnvFrozen(s.frozen)}:${keys.first()}..${keys.last()}"
        }
        return "emaTicks=${hex(s.emaTicks)} emaSteps=${hex(s.emaSteps)} day=${s.day ?: "nil"}" +
            " dayTicks=${hex(s.dayTicks)} daySteps=${hex(s.daySteps)} accepted=${s.accepted} frozen=$frozen"
    }

    private class Lcg(private var s: Long) {
        fun next(): Int {
            s = s * 6364136223846793005L + 1442695040888963407L
            return (s ushr 33).toInt()
        }
    }

    private fun recorded(state: StepCalibration.State, day: String, steps: Double, ticks: Double) =
        StepCalibration.recorded(state, day = day, steps = steps, ticks = ticks)

    private fun factor(state: StepCalibration.State, day: String, manual: Double) =
        StepCalibration.factor(state, day = day, manual = manual)

    private fun dayKey(i: Int): String = String.format(Locale.ROOT, "2026-%02d-%02d", 1 + i / 28, 1 + i % 28)

    /** The state the `long run` section ends on; the `json` section encodes it. */
    private var longRunState = StepCalibration.State()

    private fun render(): String {
        val out = StringBuilder()
        val band = StepCalibration.RATIO_BAND

        out.appendLine("== constants ==")
        out.appendLine(
            "ratioBand=${hex(band.start)}...${hex(band.endInclusive)} minimumSteps=${hex(StepCalibration.MINIMUM_STEPS)}" +
                " dayAlpha=${hex(StepCalibration.DAY_ALPHA)} priorSteps=${hex(StepCalibration.PRIOR_STEPS)}" +
                " frozenDayLimit=${StepCalibration.FROZEN_DAY_LIMIT}",
        )

        out.appendLine("== accepts ==")
        val stepsGrid = listOf(
            20.0, 29.999999999999996, 30.0, 30.000000000000004, 55.0, 60.0, 63.0, 100.0, 120.0, 1e9, 0.0, -60.0,
            Double.NaN, Double.POSITIVE_INFINITY,
        )
        for (steps in stepsGrid) {
            val ticksGrid = listOf(
                0.0, -1.0, 0.5, 25.0, 59.0, 74.0, 90.0, 110.0, steps, Math.nextDown(steps), Math.nextUp(steps),
                steps * 1.7, Math.nextDown(steps * 1.7), Math.nextUp(steps * 1.7),
                steps * 1.14, steps * 1.49, steps * 2 * 1.14, steps / 2 * 1.49, 51.0, 102.0, 1.7e9,
                Double.NaN, Double.POSITIVE_INFINITY,
            )
            out.appendLine(
                "steps=${hex(steps)}: " +
                    ticksGrid.joinToString("") { if (StepCalibration.accepts(steps = steps, ticks = it)) "1" else "0" },
            )
        }

        out.appendLine("== octave ==")
        var index = 0
        // Swift's stride(from: 1.14, through: 1.49, by: 0.05): one fused multiply-add per value.
        while (Math.fma(index.toDouble(), 0.05, 1.14) <= 1.49) {
            val trueRatio = Math.fma(index.toDouble(), 0.05, 1.14)
            val steps = 60.0
            val ticks = steps * trueRatio
            out.appendLine(
                "ratio=${hex(trueRatio)} ticks=${hex(ticks)} ${StepCalibration.accepts(steps, ticks)}" +
                    " ${StepCalibration.accepts(steps * 2, ticks)} ${StepCalibration.accepts(steps / 2, ticks)}",
            )
            index += 1
        }

        out.appendLine("== swift test scenarios ==")
        run {
            val state = StepCalibration.advanced(StepCalibration.State(), to = "2026-10-03")
            out.appendLine("manual: ${dump(state)}")
            out.appendLine(
                "manual: factor(10-03)=${hex(factor(state, "2026-10-03", 1.26))}" +
                    " factor(09-30)=${hex(factor(state, "2026-09-30", 1.26))}" +
                    " longRun=${opt(StepCalibration.longRunFactor(state))}",
            )
        }
        run {
            var state = StepCalibration.State()
            state = recorded(state, "2026-10-03", steps = 60.0, ticks = 72.0)
            state = recorded(state, "2026-10-03", steps = 180.0, ticks = 252.0)
            out.appendLine("firstDay: ${dump(state)}")
            out.appendLine("firstDay: factor=${hex(factor(state, "2026-10-03", 1.0))}")
        }
        run {
            val start = recorded(StepCalibration.State(), "2026-10-03", steps = 60.0, ticks = 75.0)
            val after = recorded(start, "2026-10-03", steps = 120.0, ticks = 75.0)
            out.appendLine("refused: ${dump(start)}")
            out.appendLine("refused: unchanged=${after == start}")
        }
        run {
            var state = recorded(StepCalibration.State(), "2026-10-03", steps = 100.0, ticks = 125.0)
            state = StepCalibration.advanced(state, to = "2026-10-04")
            out.appendLine("leaving: ${dump(state)}")
            out.appendLine(
                "leaving: longRun=${opt(StepCalibration.longRunFactor(state))}" +
                    " factor(10-04)=${hex(factor(state, "2026-10-04", 1.0))}",
            )
        }
        run {
            var state = recorded(StepCalibration.State(), "2026-10-03", steps = 200.0, ticks = 250.0)
            repeat(4) { state = recorded(state, "2026-10-04", steps = 55.0, ticks = 55 * 1.49) }
            out.appendLine("ownEvidence: ${dump(state)}")
            out.appendLine(
                "ownEvidence: today=${hex(factor(state, "2026-10-04", 1.0))}" +
                    " expected=${hex((220 * 1.49 + 120 * 1.25) / 340)} history=${hex(factor(state, "2026-10-03", 1.0))}",
            )
        }
        run {
            var state = StepCalibration.State()
            state = recorded(state, "2026-10-01", steps = 100.0, ticks = 120.0)
            state = recorded(state, "2026-10-02", steps = 100.0, ticks = 140.0)
            state = StepCalibration.advanced(state, to = "2026-10-03")
            out.appendLine("ema: ${dump(state)}")
            out.appendLine(
                "ema: longRun=${opt(StepCalibration.longRunFactor(state))}" +
                    " expected=${hex((0.8 * 24 + 28) / (0.8 * 20 + 20))}",
            )
        }
        run {
            var state = recorded(StepCalibration.State(), "2026-10-01", steps = 100.0, ticks = 130.0)
            state = StepCalibration.advanced(state, to = "2026-10-02")
            state = StepCalibration.advanced(state, to = "2026-10-03")
            state = recorded(state, "2026-10-03", steps = 100.0, ticks = 110.0)
            out.appendLine("unmeasured: ${dump(state)}")
            out.appendLine("unmeasured: factor(10-02)=${hex(factor(state, "2026-10-02", 1.0))}")
        }
        run {
            var state = recorded(StepCalibration.State(), "2026-10-03", steps = 100.0, ticks = 125.0)
            state = StepCalibration.advanced(state, to = "2026-10-04")
            val after = recorded(state, "2026-10-03", steps = 100.0, ticks = 150.0)
            out.appendLine("left: ${dump(after)}")
            out.appendLine("left: unchanged=${after == state}")
        }
        run {
            var state = StepCalibration.State()
            for (i in 0 until StepCalibration.FROZEN_DAY_LIMIT + 30) {
                state = recorded(state, String.format(Locale.ROOT, "2026-%04d", i), steps = 100.0, ticks = 125.0)
            }
            out.appendLine("bounded before: ${dump(state)}")
            state = StepCalibration.advanced(state, to = "2027-0000")
            out.appendLine("bounded after: ${dump(state)}")
            out.appendLine(
                "bounded: count=${state.frozen.size} has2026-0000=${state.frozen["2026-0000"] != null}" +
                    " has2026-0029=${state.frozen["2026-0029"] != null}" +
                    " has2026-0030=${state.frozen["2026-0030"] != null}",
            )
        }

        out.appendLine("== long run ==")
        val g = Lcg(20261003)
        var state = StepCalibration.State()
        var dayIndex = 0
        for (round in 0 until 48) {
            dayIndex += listOf(1, 1, 1, 1, 2, 3, 0, 1)[g.next() % 8]
            val measurements = g.next() % 5
            repeat(measurements) {
                // Mostly today; sometimes a day already left, sometimes tomorrow.
                val offset = listOf(0, 0, 0, 0, 0, 0, -1, -3, 1)[g.next() % 9]
                val seconds = (20 + g.next() % 41).toDouble()
                val stepHz = 1.1 + (g.next() % 900).toDouble() / 1000
                val steps = stepHz * seconds
                val ticks = (steps * (0.95 + (g.next() % 850).toDouble() / 1000)).toInt().toDouble()
                val day = dayKey(maxOf(dayIndex + offset, 0))
                if (offset == 1) dayIndex += 1
                state = recorded(state, day, steps = steps, ticks = ticks)
            }
            if (g.next() % 3 == 0) state = StepCalibration.advanced(state, to = dayKey(dayIndex))
            val today = dayKey(dayIndex)
            out.appendLine("r$round $today: ${dump(state)}")
            out.appendLine(
                "r$round factors: today=${hex(factor(state, today, 1.26))}" +
                    " running=${hex(factor(state, state.day ?: today, 1.26))}" +
                    " yesterday=${hex(factor(state, dayKey(maxOf(dayIndex - 1, 0)), 1.26))}" +
                    " tomorrow=${hex(factor(state, dayKey(dayIndex + 1), 1.26))}" +
                    " nextMonth=${hex(factor(state, dayKey(dayIndex + 30), 1.26))}" +
                    " past=${hex(factor(state, "2025-12-31", 1.26))}" +
                    " longRun=${opt(StepCalibration.longRunFactor(state))}",
            )
        }
        longRunState = state

        out.appendLine("== json ==")
        for ((name, s) in jsonStates()) {
            out.appendLine("$name state: ${dump(s)}")
            out.appendLine("$name roundTrip=${StepCalibration.State.fromJson(s.toJson()!!) == s}")
        }
        // What the synthesized decoder refuses or accepts.
        val documents = listOf(
            "missing accepted" to """{"emaTicks":0,"emaSteps":0,"dayTicks":0,"daySteps":0,"frozen":{}}""",
            "missing frozen" to """{"emaTicks":0,"emaSteps":0,"dayTicks":0,"daySteps":0,"accepted":0}""",
            "missing emaTicks" to """{"emaSteps":0,"dayTicks":0,"daySteps":0,"frozen":{},"accepted":0}""",
            "null day" to
                """{"emaTicks":1.5,"emaSteps":1,"day":null,"dayTicks":0,"daySteps":0,"frozen":{},"accepted":2}""",
            "string number" to
                """{"emaTicks":"1.5","emaSteps":1,"dayTicks":0,"daySteps":0,"frozen":{},"accepted":2}""",
            "frozen string value" to
                """{"emaTicks":1.5,"emaSteps":1,"dayTicks":0,"daySteps":0,"frozen":{"2026-10-03":"x"},"accepted":2}""",
            "frozen array" to """{"emaTicks":1.5,"emaSteps":1,"dayTicks":0,"daySteps":0,"frozen":[],"accepted":2}""",
            "numeric day" to
                """{"emaTicks":1.5,"emaSteps":1,"day":20261003,"dayTicks":0,"daySteps":0,"frozen":{},"accepted":2}""",
            "fractional accepted" to
                """{"emaTicks":1.5,"emaSteps":1,"dayTicks":0,"daySteps":0,"frozen":{},"accepted":2.5}""",
            "accepted 2.0" to """{"emaTicks":1.5,"emaSteps":1,"dayTicks":0,"daySteps":0,"frozen":{},"accepted":2.0}""",
            "extra key" to
                """{"emaTicks":1.5,"emaSteps":1,"dayTicks":0,"daySteps":0,"frozen":{"2026-10-03":1.25},"accepted":2,"later":true}""",
            "exponent" to """{"emaTicks":1.5e1,"emaSteps":1E-5,"dayTicks":0,"daySteps":0,"frozen":{},"accepted":2}""",
            "not json" to "state",
            "array" to "[]",
            "empty object" to "{}",
        )
        for ((name, text) in documents) {
            out.appendLine("decode $name: ${StepCalibration.State.fromJson(text)?.let(::dump) ?: "nil"}")
        }
        return out.toString().trimEnd('\n')
    }

    private fun jsonStates(): List<Pair<String, StepCalibration.State>> {
        val twoDays = recorded(
            recorded(StepCalibration.State(), "2026-10-03", steps = 100.0, ticks = 125.0),
            "2026-10-04", steps = 55.0, ticks = 80.0,
        )
        var pockets = recorded(StepCalibration.State(), "2026-10-03", steps = 200.0, ticks = 250.0)
        repeat(4) { pockets = recorded(pockets, "2026-10-04", steps = 55.0, ticks = 55 * 1.49) }
        pockets = StepCalibration.advanced(pockets, to = "2026-10-05")
        return listOf(
            "empty" to StepCalibration.State(),
            "dayOnly" to StepCalibration.advanced(StepCalibration.State(), to = "2026-10-03"),
            "twoDays" to twoDays,
            "pockets" to pockets,
            "longRun" to longRunState,
        )
    }

    /** The Swift lines that carry Swift's own JSON text; [readsWhatSwiftEncodes] consumes them. */
    private fun isSwiftJson(line: String) = SWIFT_JSON.containsMatchIn(line)

    @Test
    fun matchesTheSwiftBuildBitForBit() {
        val want = expected.lines().filterNot(::isSwiftJson)
        val got = render().lines()
        for (index in 0 until minOf(want.size, got.size)) {
            assertEquals("line ${index + 1} of the oracle (json lines aside)", want[index], got[index])
        }
        assertEquals("line count", want.size, got.size)
        // The comparison cannot quietly stop comparing: 174 lines of Swift output, 5 of them JSON text.
        assertEquals(169, want.size)
    }

    /**
     * Each `<name> json:` line is what Swift's `JSONEncoder` wrote for the state dumped on the
     * `<name> state:` line before it. Decoding it here must give that state, bit for bit.
     */
    @Test
    fun readsWhatSwiftEncodes() {
        val lines = expected.lines()
        var read = 0
        for ((index, line) in lines.withIndex()) {
            if (!isSwiftJson(line)) continue
            val name = line.substringBefore(" json: ")
            val state = StepCalibration.State.fromJson(line.substringAfter(" json: "))
            assertNotNull("$name: Swift's encoding did not decode", state)
            assertEquals("$name state: ${dump(state!!)}", lines[index - 1])
            read += 1
        }
        assertEquals(5, read)
    }

    /** A state that has no JSON form encodes to null instead of throwing. */
    @Test
    fun aStateWithoutAJsonFormEncodesToNull() {
        assertEquals(null, StepCalibration.State(emaTicks = Double.NaN).toJson())
        assertEquals(null, StepCalibration.State(frozen = mapOf("2026-10-03" to Double.POSITIVE_INFINITY)).toJson())
        assertTrue(StepCalibration.State().toJson() != null)
    }

    private companion object {
        /** `<name> json: {...}`, and not the `decode not json:` line of the refusal table. */
        val SWIFT_JSON = Regex("^[A-Za-z]+ json: \\{")
    }
}
