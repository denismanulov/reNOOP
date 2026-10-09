package com.noop.analytics

import com.noop.data.StepSample
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The WHOOP 4.0 capture of 2026-10-03: 20 minutes of 1 Hz history across a seated stretch, two counted
 * walks and a bout of hand-waving. The firmware counter rose 45630 -> 45894, and every bout opened with an
 * 11 or 12 step release. The consecutive-sample rate gate kept 217 of those 264. Same series and same
 * expectations as the Swift `Whoop4StepCaptureTests`.
 */
class Whoop4StepCaptureTest {

    private val firstTs = 1_791_024_239L
    private val spanSeconds = 1_204

    /**
     * (seconds since [firstTs], counter) at every record where the counter changed. Index 0 is the first
     * record. The series is rebuilt at 1 Hz, flat between entries.
     */
    private val changes: List<Pair<Int, Int>> = listOf(
        0 to 45630, 27 to 45642, 905 to 45654, 906 to 45655, 907 to 45657, 908 to 45658, 909 to 45660,
        910 to 45661, 911 to 45663, 912 to 45664, 913 to 45666, 914 to 45667, 915 to 45669, 916 to 45672,
        917 to 45673, 918 to 45674, 919 to 45676, 920 to 45678, 921 to 45679, 922 to 45682, 924 to 45684,
        925 to 45685, 926 to 45687, 927 to 45689, 928 to 45691, 929 to 45693, 930 to 45695, 931 to 45696,
        932 to 45699, 933 to 45701, 934 to 45702, 935 to 45704, 936 to 45705, 937 to 45707, 939 to 45709,
        940 to 45710, 941 to 45714, 942 to 45715, 943 to 45716, 944 to 45718, 945 to 45719, 946 to 45721,
        947 to 45722, 948 to 45724, 949 to 45726, 950 to 45727, 951 to 45729, 952 to 45730, 953 to 45732,
        955 to 45734, 956 to 45736, 957 to 45737, 958 to 45738, 959 to 45740, 960 to 45743, 962 to 45745,
        964 to 45747, 965 to 45751, 966 to 45752, 967 to 45753, 969 to 45756, 970 to 45757, 971 to 45759,
        972 to 45761, 973 to 45763, 974 to 45764, 975 to 45766, 976 to 45767, 977 to 45768, 978 to 45769,
        1052 to 45780, 1053 to 45782, 1054 to 45783, 1055 to 45785, 1056 to 45787, 1057 to 45788, 1058 to 45790,
        1059 to 45792, 1060 to 45793, 1061 to 45795, 1062 to 45797, 1063 to 45798, 1064 to 45800, 1065 to 45803,
        1066 to 45805, 1067 to 45806, 1068 to 45807, 1069 to 45809, 1070 to 45810, 1071 to 45812, 1072 to 45813,
        1073 to 45815, 1074 to 45816, 1075 to 45818, 1076 to 45819, 1077 to 45820, 1078 to 45823, 1079 to 45824,
        1080 to 45826, 1081 to 45827, 1082 to 45830, 1083 to 45831, 1084 to 45832, 1160 to 45844, 1161 to 45845,
        1162 to 45846, 1163 to 45848, 1164 to 45852, 1165 to 45854, 1166 to 45856, 1167 to 45858, 1168 to 45860,
        1169 to 45862, 1170 to 45864, 1171 to 45866, 1172 to 45868, 1173 to 45870, 1174 to 45872, 1175 to 45874,
        1176 to 45876, 1177 to 45878, 1178 to 45880, 1179 to 45882, 1180 to 45885, 1181 to 45887, 1182 to 45889,
        1183 to 45891, 1184 to 45892, 1185 to 45894,
    )

    private val samples: List<StepSample> by lazy {
        val counterAt = changes.toMap()
        var counter = changes[0].second
        (0..spanSeconds).map { offset ->
            counterAt[offset]?.let { counter = it }
            StepSample(deviceId = "my-whoop", ts = firstTs + offset, counter = counter)
        }
    }

    @Test fun everyCountedStepSurvivesTheGate() {
        assertEquals(264, StepsCounter.stepsInWindow(samples))
    }

    @Test fun sleepAwareCounterAgrees() {
        val count = SleepAwareStepCounter.count(samples, emptyList())
        assertEquals(264, count.totalTicks)
        assertEquals(0, count.rejectedImplausibleTicks)
    }

    @Test fun dailyTotalIsTheCounterRise() {
        val daily = AnalyticsEngine.analyzeDay(day = "2026-10-03", steps = samples, profile = UserProfile()).daily
        assertEquals(264, daily.steps)
    }
}
