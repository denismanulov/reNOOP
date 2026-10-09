package com.noop.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How [WhoopBleClient] is wired to [StepCalibrationCoordinator], read from the source: the client cannot
 * be built in a plain-JVM test, and what matters here is exactly what a refactor would move without
 * noticing. Which code can put the raw-stream switch on the wire, where the six events are fed from, and
 * that the connect handshake still writes what it wrote before.
 */
class StepCalibrationWiringTest {

    private val mainRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/com/noop").takeIf(File::isDirectory) ?: File(it, "src/main/java/com/noop") }
        .first { it.isDirectory }

    private val client: String = File(mainRoot, "ble/WhoopBleClient.kt").readText()

    /** Code only: comment lines dropped, so prose that names a call does not count as one. */
    private fun code(source: String): String =
        source.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
            .joinToString("\n")

    /** The body of a member function declared at four spaces, up to its closing brace. */
    private fun body(signature: String): String {
        val start = client.indexOf("    $signature")
        assertTrue("$signature not found", start >= 0)
        val end = client.indexOf("\n    }\n", start)
        assertTrue(end > start)
        return code(client.substring(start, end))
    }

    private fun count(haystack: String, needle: String): Int = haystack.windowed(needle.length).count { it == needle }

    /**
     * Two places can write the switch, and no third: the connect handshake's off switch, which was there
     * before, and the coordinator's sender. Nothing else in the app names the command or its payload.
     */
    @Test
    fun onlyTheHandshakeAndTheCoordinatorCanWriteTheSwitch() {
        val mentions = HashMap<String, Int>()
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = code(file.readText())
            val n = count(text, "SEND_R10_R11_REALTIME") + count(text, "RawStreamSwitch.command") + count(text, "RawStreamSwitch.payload")
            if (n > 0) mentions[file.relativeTo(mainRoot).path.replace(File.separatorChar, '/')] = n
        }
        assertEquals(
            mapOf(
                "protocol/Enums.kt" to 2,              // the enum case and its name-table entry
                "protocol/RawStreamSwitch.kt" to 1,    // `command = CommandNumber.SEND_R10_R11_REALTIME`
                "ble/WhoopBleClient.kt" to 3,          // the handshake's off switch; the coordinator's command and payload
            ),
            mentions,
        )
        val text = code(client)
        assertEquals(1, count(text, "send(CommandNumber.SEND_R10_R11_REALTIME, byteArrayOf(0))"))
        assertEquals(
            1,
            count(text, "sendSwitch = { on -> send(RawStreamSwitch.command, RawStreamSwitch.payload(on), withResponse = true) }"),
        )
    }

    /** Each event is fed from exactly one place, and on the main looper. */
    @Test
    fun eachEventIsFedOnceOnTheMainLooper() {
        val text = code(client)
        for (call in listOf(
            "stepCalibration.connectSettled()", "stepCalibration.disconnected()", "stepCalibration.offloadStarted()",
            "stepCalibration.offloadEnded(reason)", "stepCalibration.rawImu(imu)", "stepCalibration.switchAcknowledged()",
        )) {
            assertEquals(call, 1, count(text, call))
            assertEquals("$call must hop to the main looper", 1, count(text, "onMainLooper { $call }"))
        }
        assertEquals("and nothing else reaches into it", 6 + 1, count(text, "stepCalibration."))   // + wantsFrames
    }

    /** The handshake writes what it wrote before, in the same order; the hook comes after all of it. */
    @Test
    fun theConnectHandshakeIsUnchangedAndTheHookFollowsIt() {
        val handshake = body("private fun runConnectHandshake() {")
        val sends = Regex("""\bsend\(CommandNumber\.([A-Z0-9_]+)""").findAll(handshake).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "GET_HELLO_HARVARD", "REPORT_VERSION_INFO", "GET_HELLO", "GET_CLOCK", "GET_CLOCK",
                "SEND_R10_R11_REALTIME", "GET_DATA_RANGE", "TOGGLE_REALTIME_HR",
            ),
            sends,
        )
        val hook = handshake.indexOf("onMainLooper { stepCalibration.connectSettled() }")
        assertTrue(hook > handshake.lastIndexOf("send("))
        assertTrue(hook > handshake.indexOf("handler.postDelayed({ requestSync(BackfillTrigger.CONNECT) }, INITIAL_BACKFILL_DELAY_MS)"))
        assertEquals("the handshake sends nothing new", 1, count(handshake, "stepCalibration."))
    }

    @Test
    fun theOffloadAndDisconnectHooksSitWhereTheEventsHappen() {
        val begin = body("private fun beginBackfill() {")
        assertTrue(begin.indexOf("stepCalibration.offloadStarted()") > begin.indexOf("backfilling = true"))
        val exit = body("private fun exitBackfilling(reason: String) {")
        assertTrue(exit.indexOf("stepCalibration.offloadEnded(reason)") > exit.indexOf("backfilling = false"))
        assertTrue(body("private fun reset() {").contains("stepCalibration.disconnected()"))
    }

    /** Raw frames and the acknowledgement are taken from a WHOOP 4.0 link only. */
    @Test
    fun theFrameHooksAreForAWhoop4Only() {
        val text = code(client)
        assertEquals(1, count(text, "if (stepCalibration.wantsFrames && connectedFamily == DeviceFamily.WHOOP4) {"))
        assertEquals(
            1,
            count(text, "if (connectedFamily == DeviceFamily.WHOOP4 && RawStreamSwitch.isAcknowledgement(frame, parsed)) {"),
        )
        assertEquals(1, count(text, "isWhoop4 = { familyEstablished && connectedFamily == DeviceFamily.WHOOP4 },"))
    }

    /** The application wires the on-screen signal; without it the client's default refuses every burst. */
    @Test
    fun theOnScreenSignalIsWired() {
        assertEquals(1, count(code(client), "private val appOnScreen: () -> Boolean = { true },"))
        val application = code(File(mainRoot, "NoopApplication.kt").readText())
        assertEquals(1, count(application, "appOnScreen = { AppOnScreen.isOnScreen },"))
        assertEquals(1, count(application, "registerActivityLifecycleCallbacks(AppOnScreen)"))
    }
}
