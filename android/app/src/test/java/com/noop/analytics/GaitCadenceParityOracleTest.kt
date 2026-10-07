package com.noop.analytics

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [GaitCadence] against the Swift source of truth by ORACLE, not by eye.
 *
 * [expected] is the verbatim stdout of the Swift implementation compiled standalone
 * (`swiftc -O GaitCadence.swift GaitFixtures.swift main.swift && ./oracle`, Swift 6.3 on macOS arm64) from
 * `Packages/StrandAnalytics/Sources/StrandAnalytics/GaitCadence.swift`: the Swift suite's own signals,
 * the three recordings whole and in fourteen sub-windows each, at other sample rates and with the axes
 * permuted, twenty-seven signals built from integers alone, and the guards. Every number is printed as
 * its IEEE-754 bit pattern; the text after `//` on a line is the same value in decimal, for the reader.
 *
 * WHAT IS HELD BIT FOR BIT. The fixed-seed generator and Swift's `Double.random(in:using:)`, the three
 * decoded recordings, the integer-built signals (FNV-1a over the bits of every sample), and which inputs
 * yield no estimate at all.
 *
 * WHAT IS HELD TO [ESTIMATE_TOLERANCE]. Every estimate. The detector calls `cos` for the Hann window and
 * for each Goertzel coefficient, and maths libraries do not round `cos` alike: of the 33,255 distinct
 * arguments this suite passes to it, Apple's libm and fdlibm (`StrictMath`) disagree in the last bit on
 * 1,512, and the arm64 HotSpot routine behind `kotlin.math.cos` (JBR 21) on 2,816. Neither Kotlin
 * spelling reproduces Apple's bits, so the twin uses `StrictMath`, whose results the Java specification
 * fixes (fdlibm): this JVM, the CI one and an Android runtime are meant to agree with each other, which
 * the platform routine does not promise. Not run on a device. The largest gap to a Swift value below is
 * 1.2e-14. That the gap is the maths library and nothing else was checked once, in a scratch build that
 * is not kept: with Apple's own `cos` and `sin` values substituted for every argument, this twin
 * reproduced all 161 lines below bit for bit.
 *
 * The trigonometric test signals (`gait(...)`, `harmonic`, `noStride`, `short`) are themselves built
 * with `sin`, so their checksums are not compared; three samples of each are held to [SAMPLE_TOLERANCE].
 *
 * The oracle only guards this direction. `GaitCadenceTests` on the Swift side is what stops Swift
 * drifting away from Kotlin.
 */
class GaitCadenceParityOracleTest {

    /** Verbatim stdout of the Swift build. Do not hand-edit: regenerate from the oracle. */
    private val expected = """
        == swift-rng ==
        splitmix(42) first4=bdd732262feb6e95,28efe333b266f103,47526757130f9f52,581ce1ff0e4ae394
        random(-0.3...0.3, seed 42) x4000 fnv=1cdfd2f0d517e58d first=3fc28d5bd841936e,bfca1e6f0a174784,bfc100e0ff7a481d
        random(-0.02...0.02, seed 1207) x12000 fnv=b880aaa997e53995 first=bf8c99c5dffc1d29,bf86fa54a7a40e48,3f9418c036320ce1
        == signals ==
        gait(1.2): n=4000 fnv=cb7742cb437ec74f x0=bf8c99c5dffc1d29 y1=3ff058ff6f5c0e29 z2=3fbfb595eaefe049
        gait(1.55): n=4000 fnv=cb3dbf45f3f6b3b3 x0=bf8a98115172d27b y1=3ff09ef6d70e9ba5 z2=3fc469bb25b2e861
        gait(1.9): n=4000 fnv=cf80a11964c25eba x0=3f9462156a263853 y1=3ff08d4f9fa14fd7 z2=3fc398174602df25
        gait(2.35): n=4000 fnv=1222075cb607904f x0=3f8feff2b43705ee y1=3ff0c39b3524a2d9 z2=3fc349157ff3993c
        gait(2.9): n=4000 fnv=4c378c879f160bcc x0=bf3afdd06e0dd300 y1=3ff080b7af604798 z2=3fc330acac6b0bd0
        harmonic: n=4000 fnv=c9c9591065635f91 x0=bf90ebee53ad44b3 y1=3ff23b3a422c6697 z2=3fb0a8017a31ee86
        noStride: n=4000 fnv=c3c386055d83bcfa x0=bf90ebee53ad44b3 y1=3ff23b3a422c6697 z2=bf597652da355040
        still: n=4000 fnv=f2be0c30269dc874 x0=bf6843c891c7b276 y1=3fefe2982276ad1c z2=3f1e3ab857ad3a00
        fidget: n=4000 fnv=985c0119bf791374 x0=3fc28d5bd841936e y1=3fe978643d7a2e1f z2=3fbcd77ae88f73f0
        short: n=1200 fnv=3c1211d5f0927a0c x0=bf53b81407e99e80 y1=3ff054ad07a7f188 z2=3fc3e6dde950ecf0
        == estimate: swift test signals ==
        gait(1.2): stepHz=3ff3333524f71fe1 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.2000018543203554 1.0 1.0
        gait(1.55): stepHz=3ff8ccd442a9532a stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.5500071147882388 1.0 1.0
        gait(1.9): stepHz=3ffe6650315b9913 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.8999788215674627 1.0 1.0
        gait(2.35): stepHz=4002ccdcdac6f68c stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 2.3500306217163764 1.0 1.0
        gait(2.9): stepHz=4007333b4deaca14 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 2.900015457847312 1.0 1.0
        harmonic: stepHz=3ff4ccd02e18cb55 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3000032234819703 1.0 1.0
        noStride: stepHz=4004cce5c9f9064f stepStrength=3fe781f69ded287a strideStrength=3ff0000000000000 // 2.600047662651185 0.7346146664142588 1.0
        still: nil
        fidget: nil
        short: nil
        == estimate: recordings ==
        armSwinging: n=1100 fnv=67bcf2af769ee5b8
        armSwinging full: stepHz=3ff5718808c55dba stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3402176230064087 1.0 1.0
        armSwinging [0+499]: nil
        armSwinging [0+500]: stepHz=3ff4ad84d59dcad5 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.2923630089177796 1.0 1.0
        armSwinging [0+501]: stepHz=3ff4ad7681b9c147 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.2923493449598753 1.0 1.0
        armSwinging [0+625]: stepHz=3ff44a0ec7f7decc stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.2680805026301867 1.0 1.0
        armSwinging [0+750]: nil
        armSwinging [125+500]: nil
        armSwinging [125+750]: stepHz=3ff5a42ab5b5fe75 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.352579793747194 1.0 1.0
        armSwinging [250+500]: nil
        armSwinging [250+625]: stepHz=3ff5a81b740addd0 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3535418064983453 1.0 1.0
        armSwinging [600+500]: stepHz=3ff534f32eaf6fc0 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3254272292751779 1.0 1.0
        armSwinging [475+625]: stepHz=3ff52d7323d991a0 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3235961342225906 1.0 1.0
        armSwinging [350+750]: stepHz=3ff53f1bf8a9b9fb stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3279075349233824 1.0 1.0
        armSwinging [37+613]: stepHz=3ff41bcb67aecad6 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.2567857790096943 1.0 1.0
        armSwinging [300+800]: stepHz=3ff546bdc2294909 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3297708115028988 1.0 1.0
        armSwinging zxy: stepHz=3ff5718808c55dba stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3402176230064087 1.0 1.0
        armSwinging x-long: stepHz=3ff42baed2d1329e stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.260664771561245 1.0 1.0
        armSwinging rate=20.0: stepHz=3ff12774aef8de08 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.072132762417711 1.0 1.0
        armSwinging rate=24.0: stepHz=3ff495dca5490259 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.2865873772099319 1.0 1.0
        armSwinging rate=30.0: stepHz=3ff9baee46f5d02d stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.6081373950846782 1.0 1.0
        armSwinging rate=33.3: stepHz=3ffc8e2cfe45f796 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.7847108776618135 1.0 1.0
        handInPocket: n=1200 fnv=06ceda1e65964c5e
        handInPocket full: stepHz=3ff4e4e31384afdc stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3058806192811891 1.0 1.0
        handInPocket [0+499]: nil
        handInPocket [0+500]: stepHz=3ff4f590d4fc7fe4 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3099525756624582 1.0 1.0
        handInPocket [0+501]: stepHz=3ff4f5009ca3ac08 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3098150366520276 1.0 1.0
        handInPocket [0+625]: stepHz=3ff4c8cbf5631a2c stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.299022635024481 1.0 1.0
        handInPocket [0+750]: stepHz=3ff4c585738ec3d9 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.298222972294971 1.0 1.0
        handInPocket [125+500]: stepHz=3ff4a2d0b239db4f stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.289749809451411 1.0 1.0
        handInPocket [125+750]: stepHz=3ff4d74f0261bea5 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3025655835189494 1.0 1.0
        handInPocket [250+500]: stepHz=3ff4d82d7eabe4aa stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3027777622321914 1.0 1.0
        handInPocket [250+625]: stepHz=3ff4f17098dd87bd stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.308945271616252 1.0 1.0
        handInPocket [700+500]: nil
        handInPocket [575+625]: nil
        handInPocket [450+750]: nil
        handInPocket [37+613]: stepHz=3ff4bad6fcd47962 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.295615184418772 1.0 1.0
        handInPocket [300+900]: stepHz=3ff5716bbe4c3bd3 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3401906426913583 1.0 1.0
        handInPocket zxy: stepHz=3ff4e4e31384afdc stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3058806192811891 1.0 1.0
        handInPocket x-long: stepHz=3ff4c4f6d39467ee stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.29808695457768 1.0 1.0
        handInPocket rate=20.0: stepHz=3ff0b70924b1311b stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.0446864541327787 1.0 1.0
        handInPocket rate=24.0: stepHz=3ff40f0443217838 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.2536661741537625 1.0 1.0
        handInPocket rate=30.0: stepHz=3ff912c01ebea601 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.567077751251759 1.0 1.0
        handInPocket rate=33.3: stepHz=3ffbd4e9e854fc77 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.7394808841197837 1.0 1.0
        walk: n=1250 fnv=3e80212034db4045
        walk full: stepHz=3ff7b105c2f90730 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.4807183853267354 1.0 1.0
        walk [0+499]: nil
        walk [0+500]: stepHz=3ff87143058ac942 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.5276518074502552 1.0 1.0
        walk [0+501]: stepHz=3ff87139e0ea7c17 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.5276430879382639 1.0 1.0
        walk [0+625]: stepHz=3ff874fa03bb177b stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.5285587449775153 1.0 1.0
        walk [0+750]: stepHz=3ff854f57785231e stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.520741907954466 1.0 1.0
        walk [125+500]: stepHz=3ff877351a549077 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.52910337720189 1.0 1.0
        walk [125+750]: stepHz=3ff7c03795bab21d stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.4844280098724376 1.0 1.0
        walk [250+500]: stepHz=3ff7c9fe7bfec8fe stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.4868149608196854 1.0 1.0
        walk [250+625]: stepHz=3ff75edb803d5c26 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.460658551155356 1.0 1.0
        walk [750+500]: stepHz=3ff84077ababc859 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.5157391267680593 1.0 1.0
        walk [625+625]: stepHz=3ff82135e2e20457 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.5081080305683565 1.0 1.0
        walk [500+750]: stepHz=3ff7fc37229f5465 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.4990760185658243 1.0 1.0
        walk [37+613]: stepHz=3ff870d91561dfc1 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.5275507769820111 1.0 1.0
        walk [300+950]: stepHz=3ff7b014dc63b37a stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.4804886445010355 1.0 1.0
        walk zxy: stepHz=3ff7b105c2f90730 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.4807183853267354 1.0 1.0
        walk x-long: stepHz=3ff871d25a2238db stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.5277884980055692 1.0 1.0
        walk rate=20.0: stepHz=3ff2f67061870eb3 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.1851657685919406 1.0 1.0
        walk rate=24.0: stepHz=3ff6be4eb1ed3ec3 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.421461768175434 1.0 1.0
        walk rate=30.0: stepHz=3ffc70e25d7f68c0 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.7775596287015532 1.0 1.0
        walk rate=33.3: stepHz=3fff936ef58b52f9 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.9734944907733605 1.0 1.0
        == estimate: integer-exact signals ==
        counts(n=4000 rate=100.0 period=80 arm=1000 step=800 harmonic=0 noise=80 seed=1) fnv=8ba25c603d3e2a69
          ->: stepHz=3ff3ffe2c01b20e2 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.249972105421016 1.0 1.0
        counts(n=4000 rate=100.0 period=64 arm=1000 step=800 harmonic=0 noise=80 seed=2) fnv=e93cbc89e5301bc0
          ->: stepHz=3ff8ff79147ea16c stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.5623713303158082 1.0 1.0
        counts(n=4000 rate=100.0 period=50 arm=1000 step=800 harmonic=0 noise=80 seed=3) fnv=4c910f4792a1e935
          ->: stepHz=3ffffff0c68b6ddd stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.9999854808473778 1.0 1.0
        counts(n=4000 rate=100.0 period=43 arm=1000 step=800 harmonic=0 noise=80 seed=4) fnv=736d21dac6fd528a
          ->: stepHz=40029aec5e392d03 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 2.3256461487959554 1.0 1.0
        counts(n=4000 rate=100.0 period=36 arm=1000 step=800 harmonic=0 noise=80 seed=5) fnv=42fe891077b69570
          ->: stepHz=4006391a56574728 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 2.7778822656045072 1.0 1.0
        counts(n=4000 rate=100.0 period=30 arm=1000 step=800 harmonic=0 noise=80 seed=6) fnv=cff2169c1f47479c
          ->: stepHz=400aaa7ec373b9b7 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 3.3332495946590046 1.0 1.0
        counts(n=3000 rate=100.0 period=76 arm=500 step=800 harmonic=600 noise=80 seed=7) fnv=555e1b039633f7c5
          ->: stepHz=3ff50d9f864e37d1 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3158259626684308 1.0 1.0
        counts(n=3000 rate=100.0 period=76 arm=0 step=800 harmonic=600 noise=80 seed=8) fnv=8c9eacabbef28b60
          ->: stepHz=40050d82e13e8e3d stepStrength=3fe870e1c375f994 strideStrength=3ff0000000000000 // 2.631597289773508 0.7637795274347803 1.0
        counts(n=2000 rate=100.0 period=60 arm=900 step=700 harmonic=0 noise=40 seed=9) fnv=e37ed141786d3f89
          ->: stepHz=3ffaaabc890c4e64 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.6666837075653342 1.0 1.0
        counts(n=1999 rate=100.0 period=60 arm=900 step=700 harmonic=0 noise=40 seed=10) fnv=2851f1f5ef8bf5cb
          ->: nil
        counts(n=2500 rate=50.0 period=32 arm=1000 step=800 harmonic=0 noise=80 seed=11) fnv=9bf15033fe1773d7
          ->: stepHz=3ff8ffacd10f1975 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.56242067016282 1.0 1.0
        counts(n=2000 rate=50.0 period=25 arm=1200 step=600 harmonic=300 noise=60 seed=12) fnv=a478cade803273c1
          ->: stepHz=3ffffffeeaa4342e stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.9999989667587772 1.0 1.0
        counts(n=1500 rate=25.0 period=18 arm=1000 step=800 harmonic=0 noise=80 seed=13) fnv=17070cde3027c29c
          ->: stepHz=3ff6394dc27a8665 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3889901730366543 1.0 1.0
        counts(n=1000 rate=25.0 period=12 arm=1000 step=800 harmonic=0 noise=80 seed=14) fnv=f5bbe870cf0b9462
          ->: stepHz=4000aa79e8016f24 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 2.083240330261008 1.0 1.0
        counts(n=6000 rate=100.0 period=70 arm=300 step=200 harmonic=0 noise=300 seed=15) fnv=a07e1c5d2d38a65e
          ->: stepHz=3ff6da2e9a71a846 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.4282671006171923 1.0 1.0
        counts(n=4000 rate=100.0 period=90 arm=1000 step=800 harmonic=0 noise=0 seed=16) fnv=8e640e46ba76e556
          ->: stepHz=3ff1c6eeba7fbb01 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.1110675130000234 1.0 1.0
        counts(n=4000 rate=100.0 period=100 arm=1000 step=800 harmonic=0 noise=80 seed=17) fnv=98d696ce20af37f1
          ->: stepHz=3ff0000267f15161 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.000002294565171 1.0 1.0
        counts(n=4000 rate=100.0 period=110 arm=1000 step=800 harmonic=0 noise=80 seed=18) fnv=8073a91c58986de0
          ->: nil
        counts(n=4000 rate=100.0 period=27 arm=1000 step=800 harmonic=0 noise=80 seed=19) fnv=407ab8ecab166810
          ->: nil
        counts(n=4000 rate=100.0 period=26 arm=1000 step=800 harmonic=0 noise=80 seed=20) fnv=4b7ca6ca717c073b
          ->: nil
        counts(n=4000 rate=100.0 period=80 arm=60 step=50 harmonic=0 noise=10 seed=21) fnv=c68cbf967257c442
          ->: nil
        counts(n=4000 rate=100.0 period=80 arm=100 step=90 harmonic=0 noise=20 seed=22) fnv=09aa3b0b89da32e5
          ->: nil
        counts(n=4000 rate=100.0 period=80 arm=2000 step=100 harmonic=0 noise=2000 seed=23) fnv=3a7c074c3f80976c
          ->: nil
        counts(n=4000 rate=200.0 period=150 arm=1000 step=800 harmonic=0 noise=80 seed=24) fnv=b7cf72fe083871e9
          ->: stepHz=3ff5551dcfb49e1f stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3332803834436004 1.0 1.0
        counts(n=400 rate=8.02 period=6 arm=1000 step=800 harmonic=0 noise=80 seed=25) fnv=7044842730f36a0a
          ->: nil
        counts(n=400 rate=8.03 period=6 arm=1000 step=800 harmonic=0 noise=80 seed=26) fnv=0d21bf000d86708c
          ->: stepHz=3ff56a4ef7c7173d stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.3384542158905355 1.0 1.0
        counts(n=400 rate=10.0 period=6 arm=1000 step=800 harmonic=0 noise=80 seed=27) fnv=c225aee7611fc7ef
          ->: stepHz=3ffaaa435ff068d2 stepStrength=3ff0000000000000 strideStrength=3ff0000000000000 // 1.6665681598301956 1.0 1.0
        == estimate: guards ==
        empty: nil
        y empty: nil
        rate 0: nil
        rate nan: nil
        rate -100: nil
        nyquist guard limit=40200a3d70a3d70a // 8.02
        rate == limit (needs 20 s): nil
        rate == limit.nextUp: nil
        constant: nil
        nan sample: nil
        inf sample: nil
        stepBand=3ff0000000000000...400ccccccccccccd minimumSeconds=4034000000000000
    """.trimIndent()

    private fun hex(v: Double): String = java.lang.Long.toHexString(v.toRawBits()).padStart(16, '0')

    /** FNV-1a (64-bit) over the little-endian bytes of every sample's bit pattern. */
    private fun fnv(vararg arrays: DoubleArray): String {
        var h = 0xcbf29ce484222325uL.toLong()
        for (array in arrays) {
            for (v in array) {
                var b = v.toRawBits()
                repeat(8) {
                    h = h xor (b and 0xff)
                    h *= 0x100000001b3L
                    b = b ushr 8
                }
            }
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }

    private fun show(out: StringBuilder, name: String, e: GaitCadence.Estimate?) {
        if (e == null) {
            out.appendLine("$name: nil")
        } else {
            out.appendLine(
                "$name: stepHz=${hex(e.stepHz)} stepStrength=${hex(e.stepStrength)}" +
                    " strideStrength=${hex(e.strideStrength)}",
            )
        }
    }

    private fun estimate(s: GaitFixtures.Axes, rate: Double) = GaitCadence.estimate(s.x, s.y, s.z, rate)

    private class Counts(
        val n: Int, val rate: Double, val period: Int, val arm: Int, val step: Int,
        val harmonic: Int, val noise: Int, val seed: Long,
    )

    private fun render(): String {
        val out = StringBuilder()

        out.appendLine("== swift-rng ==")
        val raw = GaitTestSignals.SeededGenerator(42)
        out.appendLine(
            "splitmix(42) first4=" + (0 until 4).joinToString(",") { java.lang.Long.toHexString(raw.next()) },
        )
        val wide = GaitTestSignals.SeededGenerator(42)
        val r = DoubleArray(4000) { wide.nextDouble(-0.3, 0.3) }
        out.appendLine("random(-0.3...0.3, seed 42) x4000 fnv=${fnv(r)} first=${hex(r[0])},${hex(r[1])},${hex(r[2])}")
        val narrow = GaitTestSignals.SeededGenerator(1207)
        val r3 = DoubleArray(12000) { narrow.nextDouble(-0.02, 0.02) }
        out.appendLine(
            "random(-0.02...0.02, seed 1207) x12000 fnv=${fnv(r3)} first=${hex(r3[0])},${hex(r3[1])},${hex(r3[2])}",
        )

        out.appendLine("== signals ==")
        val named = ArrayList<Pair<String, GaitFixtures.Axes>>()
        for (stepHz in listOf(1.20, 1.55, 1.90, 2.35, 2.90)) {
            named.add("gait($stepHz)" to GaitTestSignals.gait(stepHz = stepHz))
        }
        named.add("harmonic" to GaitTestSignals.gait(stepHz = 1.3, armG = 0.12, stepG = 0.2, harmonicG = 0.15))
        named.add("noStride" to GaitTestSignals.noStrideLine())
        named.add("still" to GaitTestSignals.gait(stepHz = 1.5, armG = 0.0, stepG = 0.0, noiseG = 0.005))
        named.add("fidget" to GaitTestSignals.fidget())
        named.add("short" to GaitTestSignals.gait(stepHz = 1.6, seconds = 12.0))
        for ((name, s) in named) {
            out.appendLine(
                "$name: n=${s.x.size} fnv=${fnv(s.x, s.y, s.z)} x0=${hex(s.x[0])} y1=${hex(s.y[1])} z2=${hex(s.z[2])}",
            )
        }

        out.appendLine("== estimate: swift test signals ==")
        for ((name, s) in named) show(out, name, estimate(s, 100.0))

        out.appendLine("== estimate: recordings ==")
        val recordings = listOf(
            "armSwinging" to GaitFixtures.ARM_SWINGING,
            "handInPocket" to GaitFixtures.HAND_IN_POCKET,
            "walk" to GaitFixtures.WALK,
        )
        for ((name, base64) in recordings) {
            val s = GaitFixtures.axes(base64)
            val n = s.x.size
            out.appendLine("$name: n=$n fnv=${fnv(s.x, s.y, s.z)}")
            show(out, "$name full", estimate(s, GaitFixtures.SAMPLE_RATE))
            // Sub-windows: (offset, length) in samples at 25 Hz.
            val windows = listOf(
                0 to 499, 0 to 500, 0 to 501, 0 to 625, 0 to 750, 125 to 500, 125 to 750, 250 to 500, 250 to 625,
                n - 500 to 500, n - 625 to 625, n - 750 to 750, 37 to 613, 300 to n - 300,
            )
            for ((offset, length) in windows) {
                if (!(offset >= 0 && offset + length <= n && length > 0)) continue
                val end = offset + length
                show(
                    out, "$name [$offset+$length]",
                    GaitCadence.estimate(
                        s.x.copyOfRange(offset, end), s.y.copyOfRange(offset, end), s.z.copyOfRange(offset, end),
                        GaitFixtures.SAMPLE_RATE,
                    ),
                )
            }
            // Axes permuted and one axis longer than the others: the shortest decides the window.
            show(out, "$name zxy", GaitCadence.estimate(s.z, s.x, s.y, GaitFixtures.SAMPLE_RATE))
            show(
                out, "$name x-long",
                GaitCadence.estimate(s.x, s.y.copyOf(700), s.z.copyOf(650), GaitFixtures.SAMPLE_RATE),
            )
            // The same samples read at other rates (the cadence scales with the rate).
            for (rate in listOf(20.0, 24.0, 30.0, 33.3)) show(out, "$name rate=$rate", estimate(s, rate))
        }

        out.appendLine("== estimate: integer-exact signals ==")
        val counts = listOf(
            Counts(4000, 100.0, 80, 1000, 800, 0, 80, 1), Counts(4000, 100.0, 64, 1000, 800, 0, 80, 2),
            Counts(4000, 100.0, 50, 1000, 800, 0, 80, 3), Counts(4000, 100.0, 43, 1000, 800, 0, 80, 4),
            Counts(4000, 100.0, 36, 1000, 800, 0, 80, 5), Counts(4000, 100.0, 30, 1000, 800, 0, 80, 6),
            Counts(3000, 100.0, 76, 500, 800, 600, 80, 7), Counts(3000, 100.0, 76, 0, 800, 600, 80, 8),
            Counts(2000, 100.0, 60, 900, 700, 0, 40, 9), Counts(1999, 100.0, 60, 900, 700, 0, 40, 10),
            Counts(2500, 50.0, 32, 1000, 800, 0, 80, 11), Counts(2000, 50.0, 25, 1200, 600, 300, 60, 12),
            Counts(1500, 25.0, 18, 1000, 800, 0, 80, 13), Counts(1000, 25.0, 12, 1000, 800, 0, 80, 14),
            Counts(6000, 100.0, 70, 300, 200, 0, 300, 15), Counts(4000, 100.0, 90, 1000, 800, 0, 0, 16),
            Counts(4000, 100.0, 100, 1000, 800, 0, 80, 17), Counts(4000, 100.0, 110, 1000, 800, 0, 80, 18),
            Counts(4000, 100.0, 27, 1000, 800, 0, 80, 19), Counts(4000, 100.0, 26, 1000, 800, 0, 80, 20),
            Counts(4000, 100.0, 80, 60, 50, 0, 10, 21), Counts(4000, 100.0, 80, 100, 90, 0, 20, 22),
            Counts(4000, 100.0, 80, 2000, 100, 0, 2000, 23), Counts(4000, 200.0, 150, 1000, 800, 0, 80, 24),
            Counts(400, 8.02, 6, 1000, 800, 0, 80, 25), Counts(400, 8.03, 6, 1000, 800, 0, 80, 26),
            Counts(400, 10.0, 6, 1000, 800, 0, 80, 27),
        )
        for (c in counts) {
            val s = GaitTestSignals.counts(c.n, c.period, c.arm, c.step, c.harmonic, c.noise, c.seed)
            out.appendLine(
                "counts(n=${c.n} rate=${c.rate} period=${c.period} arm=${c.arm} step=${c.step}" +
                    " harmonic=${c.harmonic} noise=${c.noise} seed=${c.seed}) fnv=${fnv(s.x, s.y, s.z)}",
            )
            show(out, "  ->", estimate(s, c.rate))
        }

        out.appendLine("== estimate: guards ==")
        val s = GaitTestSignals.counts(4000, 80, 1000, 800, 0, 80, 1)
        val none = DoubleArray(0)
        show(out, "empty", GaitCadence.estimate(none, none, none, 100.0))
        show(out, "y empty", GaitCadence.estimate(s.x, none, s.z, 100.0))
        show(out, "rate 0", estimate(s, 0.0))
        show(out, "rate nan", estimate(s, Double.NaN))
        show(out, "rate -100", estimate(s, -100.0))
        val limit = 2 * (0.30 + 371.toDouble() * 0.01)
        out.appendLine("nyquist guard limit=${hex(limit)}")
        show(out, "rate == limit (needs 20 s)", estimate(s, limit))
        show(out, "rate == limit.nextUp", estimate(s, Math.nextUp(limit)))
        show(
            out, "constant",
            GaitCadence.estimate(DoubleArray(4000) { 0.1 }, DoubleArray(4000) { 1.0 }, DoubleArray(4000) { 0.2 }, 100.0),
        )
        val withNan = s.x.copyOf()
        withNan[1234] = Double.NaN
        show(out, "nan sample", GaitCadence.estimate(withNan, s.y, s.z, 100.0))
        val withInf = s.y.copyOf()
        withInf[77] = Double.POSITIVE_INFINITY
        show(out, "inf sample", GaitCadence.estimate(s.x, withInf, s.z, 100.0))
        out.appendLine(
            "stepBand=${hex(GaitCadence.STEP_BAND.start)}...${hex(GaitCadence.STEP_BAND.endInclusive)}" +
                " minimumSeconds=${hex(GaitCadence.MINIMUM_SECONDS)}",
        )
        return out.toString().trimEnd('\n')
    }

    @Test
    fun matchesTheSwiftBuild() {
        val want = expected.lines().map { it.substringBefore(" // ") }
        val got = render().lines()
        var estimates = 0
        var largestGap = 0.0
        for (index in 0 until minOf(want.size, got.size)) {
            val where = "line ${index + 1}"
            val signalBuiltWithSin = SIN_BUILT.any { want[index].startsWith("$it: n=") }
            // Everything but the tolerated values has to be the same text: names, counts, checksums, nil.
            assertEquals(where, skeleton(want[index], signalBuiltWithSin), skeleton(got[index], signalBuiltWithSin))
            val wantValues = VALUE.findAll(want[index]).toList()
            val gotValues = VALUE.findAll(got[index]).toList()
            for ((w, g) in wantValues.zip(gotValues)) {
                val key = w.groupValues[1]
                val gap = abs(double(w.groupValues[2]) - double(g.groupValues[2]))
                val tolerance = if (key in SAMPLE_KEYS) SAMPLE_TOLERANCE else ESTIMATE_TOLERANCE
                assertTrue(
                    "$where $key: Swift ${w.groupValues[2]}, Kotlin ${g.groupValues[2]}, gap $gap",
                    gap <= tolerance,
                )
                if (key == "stepHz") estimates += 1
                if (key !in SAMPLE_KEYS && gap > largestGap) largestGap = gap
            }
        }
        assertEquals("line count", want.size, got.size)
        // The comparison cannot quietly stop comparing: 161 lines, 80 of them estimates.
        assertEquals(161, want.size)
        assertEquals(80, estimates)
        assertTrue("largest gap $largestGap", largestGap < ESTIMATE_TOLERANCE)
    }

    private fun double(bits: String): Double = Double.fromBits(java.lang.Long.parseUnsignedLong(bits, 16))

    /** The line with every tolerated value blanked, and the checksum too when the signal was built with `sin`. */
    private fun skeleton(line: String, signalBuiltWithSin: Boolean): String {
        val blanked = VALUE.replace(line) { "${it.groupValues[1]}=~" }
        return if (signalBuiltWithSin) CHECKSUM.replace(blanked, "fnv=~") else blanked
    }

    private companion object {
        /** Hz, and the same bound for the two strengths (0...1). Seven orders below one grid bin. */
        const val ESTIMATE_TOLERANCE = 1e-12

        /** g. One or two units in the last place of a sample near 1 g. */
        const val SAMPLE_TOLERANCE = 1e-15
        val SAMPLE_KEYS = setOf("x0", "y1", "z2")
        val VALUE = Regex("(stepHz|stepStrength|strideStrength|x0|y1|z2)=([0-9a-f]{16})")
        val CHECKSUM = Regex("fnv=[0-9a-f]{16}")
        val SIN_BUILT =
            listOf("gait(1.2)", "gait(1.55)", "gait(1.9)", "gait(2.35)", "gait(2.9)", "harmonic", "noStride", "short")
    }
}
