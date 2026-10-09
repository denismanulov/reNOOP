package com.noop.ui.settings

import com.noop.ui.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * Health Details shows SI values in the reader's units and number format and maps a wheel index back to
 * the SI value it stores. Pinned: every wheel round-trips its own options, the iOS ranges, the "unset"
 * sentinels (waist 0, max heart rate 0 = auto), and a locale's decimal comma.
 */
class HealthDetailsFormatTest {
    private val ru = Locale.forLanguageTag("ru")

    @Test
    fun `weight uses the reader's decimal separator and half kilograms`() {
        assertEquals("75.5 kg", HealthDetailsFormat.weight(75.5, imperial = false, Locale.US, "kg", "lb"))
        assertEquals("75,5 кг", HealthDetailsFormat.weight(75.5, imperial = false, ru, "кг", "фунт"))
        assertEquals("75 kg", HealthDetailsFormat.weight(75.1, imperial = false, Locale.US, "kg", "lb"))
        assertEquals("165 lb", HealthDetailsFormat.weight(74.84, imperial = true, Locale.US, "kg", "lb"))
    }

    @Test
    fun `height reads in centimetres or feet and inches`() {
        assertEquals("178 cm", HealthDetailsFormat.height(178.0, imperial = false, "cm"))
        assertEquals("5′ 10″", HealthDetailsFormat.height(177.8, imperial = true, "cm"))
    }

    @Test
    fun `an unset waist has no value`() {
        assertNull(HealthDetailsFormat.waist(0.0, imperial = false, "cm", "in"))
        assertEquals("86 cm", HealthDetailsFormat.waist(86.0, imperial = false, "cm", "in"))
        assertEquals("34 in", HealthDetailsFormat.waist(86.36, imperial = true, "cm", "in"))
    }

    @Test
    fun `every wheel round-trips its own options`() {
        for (imperial in listOf(false, true)) {
            val heights = HealthDetailsFormat.heightOptions(imperial, "cm")
            heights.indices.forEach { i ->
                assertEquals(i, HealthDetailsFormat.heightIndex(HealthDetailsFormat.heightCm(i, imperial), imperial))
            }
            val weights = HealthDetailsFormat.weightOptions(imperial, Locale.US, "kg", "lb")
            weights.indices.forEach { i ->
                assertEquals(i, HealthDetailsFormat.weightIndex(HealthDetailsFormat.weightKg(i, imperial), imperial))
            }
            val waists = HealthDetailsFormat.waistOptions(imperial, "Not set", "cm", "in")
            waists.indices.forEach { i ->
                assertEquals(i, HealthDetailsFormat.waistIndex(HealthDetailsFormat.waistCm(i, imperial), imperial))
            }
        }
        val hrMax = HealthDetailsFormat.hrMaxOptions("Auto", "bpm")
        hrMax.indices.forEach { i -> assertEquals(i, HealthDetailsFormat.hrMaxIndex(HealthDetailsFormat.hrMaxOverride(i))) }
    }

    @Test
    fun `the wheels cover the iOS ranges inside the profile clamps`() {
        val metricWeights = HealthDetailsFormat.weightOptions(false, Locale.US, "kg", "lb")
        assertEquals("30 kg", metricWeights.first())
        assertEquals("250 kg", metricWeights.last())
        assertEquals(441, metricWeights.size)
        assertEquals(30.0, HealthDetailsFormat.weightKg(0, imperial = false), 1e-9)
        assertEquals(250.0, HealthDetailsFormat.weightKg(metricWeights.lastIndex, imperial = false), 1e-9)
        val heights = HealthDetailsFormat.heightOptions(false, "cm")
        assertEquals("120 cm", heights.first())
        assertEquals("230 cm", heights.last())
        assertEquals("3′ 11″", HealthDetailsFormat.heightOptions(true, "cm").first())
        assertEquals("7′ 7″", HealthDetailsFormat.heightOptions(true, "cm").last())
        val hrMax = HealthDetailsFormat.hrMaxOptions("Auto", "bpm")
        assertEquals("Auto", hrMax.first())
        assertEquals("100 bpm", hrMax[1])
        assertEquals("230 bpm", hrMax.last())
    }

    @Test
    fun `the first wheel row of waist and max heart rate is the unset sentinel`() {
        assertEquals(0.0, HealthDetailsFormat.waistCm(0, imperial = false), 0.0)
        assertEquals(0, HealthDetailsFormat.waistIndex(0.0, imperial = true))
        assertEquals(0, HealthDetailsFormat.hrMaxOverride(0))
        assertEquals(0, HealthDetailsFormat.hrMaxIndex(0))
        assertEquals(185, HealthDetailsFormat.hrMaxOverride(HealthDetailsFormat.hrMaxIndex(185)))
    }

    @Test
    fun `zone ranges end a beat before the next zone, the top zone is open`() {
        assertEquals("134–144 bpm", HealthDetailsFormat.zoneRange(3, 134.0, 145.0, "bpm"))
        assertEquals("165+ bpm", HealthDetailsFormat.zoneRange(5, 165.0, 187.0, "bpm"))
    }

    @Test
    fun `the date of birth carries the age`() {
        val text = HealthDetailsFormat.birth(LocalDate.of(1990, 3, 12), 36, Locale.UK)
        assertEquals("12 Mar 1990 (36)", text)
    }

    @Test
    fun `initials take the first letter of up to two words`() {
        assertEquals("DB", ProfileStore.initialsOf("Denis Balasov"))
        assertEquals("А", ProfileStore.initialsOf("антон"))
        assertEquals("AP", ProfileStore.initialsOf("  Anton   Petrov Ivanovich "))
        assertEquals("", ProfileStore.initialsOf("   "))
    }

    @Test
    fun `the date of birth range is ages 13 to 100`() {
        val today = LocalDate.of(2026, 10, 1)
        val range = ProfileStore.dateOfBirthRange(today)
        assertEquals(LocalDate.of(1926, 10, 1), range.start)
        assertEquals(LocalDate.of(2013, 10, 1), range.endInclusive)
        assertTrue(LocalDate.of(1990, 3, 12) in range)
    }
}
