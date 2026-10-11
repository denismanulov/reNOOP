package com.noop.friends

import com.noop.ui.friends.FriendWeek
import com.noop.ui.friends.FriendsBoard
import com.noop.ui.friends.FriendsBoardRow
import com.noop.ui.friends.FriendsDayName
import com.noop.ui.friends.FriendsHighlights
import com.noop.ui.friends.FriendsOrder
import com.noop.ui.friends.friendsClockDuration
import com.noop.ui.friends.friendsShortName
import com.noop.ui.friends.friendsWeekdayLetter
import com.noop.ui.friends.friendsWeekdayName
import com.noop.ui.workouts.WorkoutFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The Friends tab's list, its highlights and a person's week against the Swift functions' own answers,
 * by ORACLE and not by eye. `friends_board_oracle.txt` is the verbatim stdout of the Swift declarations
 * compiled standalone (Swift 6.3.3, macOS arm64):
 *
 *   swiftc -O FriendsDay.swift Models.swift Logic.swift Format.swift main.swift -o t
 *   ./t <cases> main -AppleLocale en_US -AppleLanguages "(en)"
 *   ./t <cases> ru   -AppleLocale ru_RU -AppleLanguages "(ru)"
 *
 * `FriendsDay.swift` is the package source as it stands. The other three hold declarations cut out of the
 * app's sources with nothing inside them changed: `FriendProfile`, `FriendFeedDay` and their decoding
 * (`FriendsAPI.swift`); `FriendsBoard`, `FriendsBoardRow`, `FriendsSort`, and `FriendsMetric.value`
 * (`FriendsBoard.swift`); `FriendsHighlights` (`FriendsHighlights.swift`); `FriendWeek`
 * (`FriendDetailView.swift`); and the functions of `FriendsFormat` named below (`FriendsPieces.swift`).
 * The SwiftUI views stay out.
 *
 * Each line is `<case> => <answer>`, escaped as [FriendsWireOracle] escapes. A case's people are JSON in
 * the wire's own shape, read here by [FriendsJson] and there by the app's decoder. The tests recompute
 * every line from its own left side. The sections:
 *
 *   rows, rows many     `FriendsBoard.rows`: "order, today, the word for the wearer, the wearer, friends…"
 *                       → each row as "id, me|friend, the figure's bits, standing, detail"
 *   rows ru             the same under a Russian locale, with Cyrillic names
 *   highlights          `FriendsHighlights.recent`: "now, friends…"
 *   week                `FriendWeek.days`: "anchor, one person" → each day with its Recovery
 *   anchor              `FriendWeek.anchor`
 *   clockDuration       `FriendsFormat.clockDuration`
 *   shortName           `FriendsFormat.shortName`
 *   weekdayLetter       `FriendsFormat.weekdayLetter` in the app's nine languages
 *   weekdayLabel        `FriendsFormat.weekdayLabel` from noon on 2026-10-10, in the nine languages
 *   whole               `FriendsFormat.whole`, the energy figure, in the nine languages
 *   order               `a.localizedCaseInsensitiveCompare(b)` for every pair of a list of names
 *
 * The oracle only guards this direction; `FriendsBoardTests`, `FriendsHighlightsTests` and
 * `FriendPageTests` on the Swift side are what stop Swift drifting away from Kotlin.
 */
class FriendsBoardOracleTest {

    private val lines: List<String> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("friends_board_oracle.txt")
        assertNotNull("friends_board_oracle.txt is not on the test classpath", stream)
        stream!!.bufferedReader().readLines()
    }

    private fun section(name: String): List<String> {
        val start = lines.indexOf("# $name")
        assertTrue("no section $name", start >= 0)
        return lines.drop(start + 1).takeWhile { !it.startsWith("# ") && it.isNotEmpty() }
    }

    private fun input(line: String): String = FriendsWireOracle.input(line)

    private fun person(json: String): FriendPerson = FriendsJson.person(json)

    private fun rows(case: String, locale: Locale): String {
        val f = case.split("\t")
        val people = f.drop(3).map(::person)
        val rows = FriendsBoard.rows(
            me = people[0], friends = people.drop(1), order = FriendsOrder.entries.first { it.wire == f[0] },
            todayKey = f[1], meName = f[2], names = FriendsBoard.nameOrder(locale),
        )
        return rows.joinToString("; ") { row ->
            val value = row.value?.let { java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(it)) } ?: "nil"
            val standing = when (val s = row.standing) {
                FriendsBoardRow.Standing.Today -> "today"
                is FriendsBoardRow.Standing.Stale -> "stale:" + s.dayKey
                FriendsBoardRow.Standing.NotShared -> "notShared"
                FriendsBoardRow.Standing.NoData -> "noData"
            }
            val detail = when (val d = row.detail) {
                null -> "nil"
                is FriendsBoardRow.Detail.Asleep -> "asleep:${d.minutes}"
                is FriendsBoardRow.Detail.Workouts -> "workouts:${d.minutes}"
                is FriendsBoardRow.Detail.Sessions -> "sessions:${d.count}" + (d.sport?.let { ":$it" } ?: "")
            }
            listOf(row.person.id, if (row.isMe) "me" else "friend", value, standing, detail).joinToString(",")
        }
    }

    private fun check(name: String, answer: (String) -> String) {
        val section = section(name)
        assertTrue("$name is empty", section.isNotEmpty())
        for (line in section) {
            val case = input(line)
            assertEquals(line, FriendsWireOracle.line(case, answer(case)))
        }
    }

    @Test
    fun theOracleRanUnderTheLocalesItSays() {
        assertEquals(listOf("current => en_US", "current => ru_RU"), lines.filter { it.startsWith("current => ") })
    }

    @Test
    fun theListIsOrderedAndFiguredAsTheSwiftTwinDoesIt() {
        check("rows") { rows(it, Locale.US) }
    }

    @Test
    fun aListOfSixtyFourAndMoreIsOrderedAsTheSwiftTwinOrdersIt() {
        check("rows many") { rows(it, Locale.US) }
    }

    @Test
    fun cyrillicNamesAreFiledAsTheSwiftTwinFilesThemInRussian() {
        check("rows ru") { rows(it, Locale("ru", "RU")) }
    }

    @Test
    fun theHighlightsAreTheSwiftTwins() {
        check("highlights") { case ->
            val f = case.split("\t")
            FriendsHighlights.recent(f.drop(1).map(::person), f[0].toLong()).joinToString("; ") {
                "${it.id},${it.endTs},${it.name},${it.avatarRev},${it.workout.sport},${it.workout.durationS}"
            }
        }
    }

    @Test
    fun aPersonsWeekIsTheSwiftTwins() {
        check("week") { case ->
            val f = case.split("\t")
            FriendWeek.days(f[0], person(f[1]).days).joinToString(" ") { day ->
                val summary = day.summary ?: return@joinToString day.key + ":none"
                day.key + ":" + (summary.recovery?.toString() ?: "empty")
            }
        }
    }

    @Test
    fun theWeekEndsWhereTheSwiftTwinEndsIt() {
        check("anchor") { case ->
            val (today, newest) = case.split(" ", limit = 2)
            FriendWeek.anchor(today, newest.takeIf { it != "nil" })
        }
    }

    @Test
    fun aWorkoutsLengthIsWrittenAsTheSwiftTwinWritesIt() {
        check("clockDuration") { friendsClockDuration(it.toInt()) }
    }

    /**
     * The Swift app asks the system's name parser for the given name; this takes the first word. They
     * agree on "Given Family" and on a single word. The names below are where they do not, and each is
     * checked to still differ, so a change on either side shows here.
     */
    @Test
    fun theNameInASentenceIsTheSwiftTwinsForPlainNamesAndTheFirstWordOtherwise() {
        val section = section("shortName")
        val differing = section.filter { line -> FriendsWireOracle.line(input(line), friendsShortName(input(line))) != line }
            .map(::input).toSet()
        assertEquals(SHORT_NAME_DIFFERS, differing)
        for (plain in listOf("Anna Smith", "Anna", "", "anna_92", "Анна Смирнова", "anna smith", "Maria de la Cruz", "Kim Jong Un")) {
            assertTrue(plain, section.any { input(it) == plain } && plain !in differing)
        }
    }

    @Test
    fun aWeekdaysLetterIsTheSwiftTwinsInEveryLanguage() {
        check("weekdayLetter") { case ->
            val (locale, key) = case.split(" ", limit = 2)
            friendsWeekdayLetter(key, locale(locale))
        }
    }

    /**
     * Two to six days back a day goes by its weekday, whose name is the Swift twin's. Today, yesterday
     * and anything else are named by the app's own stamp, which is not text this test can make: for
     * those it checks only which of the four ways the day is named.
     */
    @Test
    fun aDayOfThePastWeekIsNamedAsTheSwiftTwinNamesIt() {
        val today = LocalDate.parse("2026-10-10")
        val section = section("weekdayLabel")
        assertEquals(9 * 12, section.size)
        for (line in section) {
            val (locale, key) = input(line).split(" ", limit = 2)
            val answer = FriendsWireOracle.unescape(line.substringAfter(" => "))
            when (val name = FriendsDayName.of(key, today, weekdays = true)) {
                null -> assertEquals(line, key, answer)
                FriendsDayName.Today -> assertEquals(line, "Today", answer)
                FriendsDayName.Yesterday -> assertEquals(line, "Yesterday", answer)
                is FriendsDayName.Weekday -> assertEquals(line, answer, friendsWeekdayName(name.day, locale(locale)))
                is FriendsDayName.OnDate -> assertTrue(line, answer.any { it.isDigit() } && answer !in listOf("Today", "Yesterday"))
            }
        }
    }

    /**
     * The energy on a card is written by the Workouts tab's own formatter. On the JVM these tests run
     * on, it groups a four-digit number in Spanish, Italian, Polish and Portuguese where the Swift twin
     * does not (those languages group from five digits). On a phone the same call is answered by
     * Android's ICU, which was not run here.
     */
    @Test
    fun aWholeFigureIsGroupedAsTheSwiftTwinGroupsItButForFourDigitsInFourLanguagesOnTheJvm() {
        val differing = section("whole").filter { line ->
            val (locale, value) = input(line).split(" ", limit = 2)
            FriendsWireOracle.line(input(line), WorkoutFormat.grouped(value.toDouble(), locale(locale))) != line
        }.map(::input)
        assertEquals(
            listOf("es", "it", "pl", "pt_PT").flatMap { listOf("$it 1000.0", "$it 1234.0") },
            differing,
        )
    }

    /**
     * Two names are put in order by [FriendsBoard.nameOrder] as the Swift twin's
     * `localizedCaseInsensitiveCompare` puts them: case never tells two names apart, an accent does.
     * The JVM these tests run on carries its own collation rules, which part from the Swift answers
     * for "Ø" against a name in S or Z and for a space, a hyphen or an underscore inside a name; every
     * other pair agrees. On a phone the collator is Android's ICU, which was not run here.
     */
    @Test
    fun twoNamesAreOrderedAsTheSwiftTwinOrdersThemButForWhatTheJvmCollatesItsOwnWay() {
        val names = FriendsBoard.nameOrder(Locale.US)
        val section = section("order")
        assertEquals(42 * 42, section.size)
        val differing = section.filter { line ->
            val (a, b) = input(line).split("\t", limit = 2)
            val compared = names.compare(a, b)
            FriendsWireOracle.line(input(line), if (compared < 0) "<" else if (compared > 0) ">" else "=") != line
        }.map { input(it).split("\t", limit = 2).sorted().joinToString(" / ") }.toSortedSet()
        assertEquals(JVM_ORDER_DIFFERS.toSortedSet(), differing)
        fun swift(a: String, b: String) = section.first { input(it) == "$a\t$b" }.substringAfter(" => ")
        assertEquals("the case of a letter is ignored", "=", swift("anna", "ANNA"))
        assertEquals("an accent is not", "<", swift("Emile", "\u00e9mile"))
    }

    private fun locale(tag: String): Locale = Locale.forLanguageTag(tag.replace('_', '-'))

    private companion object {
        val SHORT_NAME_DIFFERS: Set<String> = setOf(
            // A title in front: the parser skips it.
            "Dr. Anna Smith", "Dr Anna Smith", "Mrs Anna", "Sir Ian McKellen", "Prof. Dr. Hans M\u00fcller",
            // The family name first, and names the parser reads its own way.
            "Smith, Anna", "Mary Jane Watson", "Ludwig van Beethoven", "Anna O'Neil", "\uD83D\uDE00 Anna",
            // Scripts that put the family name first.
            "\u738b\u5c0f\u660e", "\u5c71\u7530 \u592a\u90ce", "\uc774\ubbfc\uc900",
        )

        val JVM_ORDER_DIFFERS: Set<String> = setOf(
            "Zoe / \u00d8ystein", "Zo\u00eb / \u00d8ystein", "zoe / \u00d8ystein", "ss / \u00d8ystein", "\u00d8ystein / \u00dfeta",
            "De Boer / deBoer", "de Boer / deBoer",
            "anna 92 / anna_92", "anna 92 / anna92", "anna-92 / anna92",
        )
    }
}
