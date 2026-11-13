package dev.tischbein.pulse.data

import dev.tischbein.pulse.data.db.StoredSession
import dev.tischbein.pulse.data.export.SessionJson
import dev.tischbein.pulse.data.export.toDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DB rows -> JSON must produce exactly the document described in docs/session-format.md. */
class SessionDocumentTest {
    private val result = TestSessions.fourByFourResult()
    private val stored: StoredSession = result.toStored(TestSessions.sessionRow())
    private val json = Json.parseToJsonElement(SessionJson.encode(stored.toDocument())).jsonObject
    private val example = Json.parseToJsonElement(TestSessions.contractExample()).jsonObject

    @Test
    fun `document has every field of the contract example with the same JSON types`() {
        val problems = shapeProblems(example, json)
        assertTrue(problems.isEmpty(), "Differences from docs/session-format.md:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `only documented extra fields are added`() {
        assertEquals(setOf("pauses", "complete"), json.keys - example.keys)
    }

    @Test
    fun `every array is filled so the shape test really checks elements`() {
        for (key in listOf("samples", "gaps", "phases", "intervals", "events", "pauses")) {
            assertTrue(json.getValue(key).jsonArray.isNotEmpty(), "$key is empty")
        }
    }

    @Test
    fun `values carry over unchanged`() {
        assertEquals(1, json.getValue("schema_version").jsonPrimitive.content.toInt())
        assertEquals("interval_4x4", json.getValue("mode").jsonPrimitive.content)
        assertEquals("2026-11-14T17:02:11Z", json.getValue("started_at").jsonPrimitive.content)
        // ended_at = started_at + duration, so the dates agree with summary.duration_ms.
        val duration = json.getValue("summary").jsonObject.getValue("duration_ms").jsonPrimitive.content.toLong()
        assertEquals(java.time.Instant.ofEpochMilli(TestSessions.STARTED_AT + duration).toString(), json.getValue("ended_at").jsonPrimitive.content)
        assertEquals(TestSessions.HR_MAX, json.getValue("hr_max_used").jsonPrimitive.content.toInt())
        assertEquals("polar_h10", json.getValue("device").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(true, json.getValue("complete").jsonPrimitive.content.toBoolean())

        val samples = json.getValue("samples").jsonArray
        assertEquals(result.samples.size, samples.size)
        // The invalid reading stays null (never interpolated) with no RR intervals.
        val invalid = samples.map { it.jsonObject }.single { it.getValue("t_ms").jsonPrimitive.content == "20000" }
        assertEquals(JsonNull, invalid.getValue("hr"))
        assertEquals(JsonArray(emptyList()), invalid.getValue("rr_ms"))

        assertEquals(result.events.map { it.type.wireName }, json.getValue("events").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(result.gaps.size, json.getValue("gaps").jsonArray.size)
        assertEquals(listOf("warmup", "work", "recovery", "work", "recovery", "cooldown"), json.getValue("phases").jsonArray.map { it.jsonObject.getValue("kind").jsonPrimitive.content })
        val settings = json.getValue("settings") as JsonObject
        assertEquals(60_000L, settings.getValue("work_ms").jsonPrimitive.content.toLong())
    }

    @Test
    fun `nullable summary fields are written as explicit nulls`() {
        val summary = json.getValue("summary").jsonObject
        assertEquals(JsonNull, summary.getValue("drift_pct")) // 4x4 has no drift
        assertTrue("new_hr_max" in summary)
    }

    @Test
    fun `rr encoding round trips`() {
        assertEquals(listOf(652, 648), decodeRr(encodeRr(listOf(652, 648))))
        assertEquals(emptyList(), decodeRr(encodeRr(emptyList())))
    }
}
