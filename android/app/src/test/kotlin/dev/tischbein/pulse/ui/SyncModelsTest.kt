package dev.tischbein.pulse.ui

import dev.tischbein.pulse.data.SessionDigest
import dev.tischbein.pulse.data.TestSessions
import dev.tischbein.pulse.data.db.SyncOverview
import dev.tischbein.pulse.data.db.SyncState
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncModelsTest {
    private val utc = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 14, 18, 0).toInstant(utc).toEpochMilli()
    private val at1402 = LocalDateTime.of(2026, 11, 14, 14, 2).toInstant(utc).toEpochMilli()

    @Test
    fun `sync off shows nothing but Off`() {
        val p = syncPanel(configured = false, overview = SyncOverview(pending = 5, lastError = "Wrong API token"), nowMs = now, zone = utc)
        assertEquals(SyncPanel.OFF, p)
        assertFalse(p.attention)
        assertNull(syncBadge(SyncState.PENDING, syncConfigured = false))
        assertNull(syncBadge(SyncState.FAILED, syncConfigured = false))
    }

    @Test
    fun `all fine is quiet`() {
        val p = syncPanel(true, SyncOverview(lastSyncedAtEpochMs = at1402), now, utc)
        assertEquals("All synced · today 14:02", p.summary)
        assertFalse(p.attention)
        assertNull(p.waiting)
        assertNull(p.problem)
        assertNull(syncBadge(SyncState.SYNCED, syncConfigured = true))
    }

    @Test
    fun `waiting without a problem is not a warning`() {
        val p = syncPanel(true, SyncOverview(pending = 2), now, utc)
        assertEquals("2 waiting", p.summary)
        assertEquals("2 sessions", p.waiting)
        assertEquals("never", p.lastSync)
        assertFalse(p.attention)
        assertEquals(SyncBadge.WAITING, syncBadge(SyncState.PENDING, syncConfigured = true))
    }

    @Test
    fun `a problem or a rejection asks for attention`() {
        val p = syncPanel(true, SyncOverview(pending = 1, lastError = "Wrong API token", lastErrorAtEpochMs = at1402), now, utc)
        assertEquals("Not syncing: Wrong API token", p.summary)
        assertEquals("Wrong API token · today 14:02", p.problem)
        assertTrue(p.attention)

        val r = syncPanel(true, SyncOverview(failed = 1, rejectedReason = "Too large for the server (HTTP 413)"), now, utc)
        assertEquals("1 session rejected", r.summary)
        assertEquals("1 session rejected by the server: Too large for the server (HTTP 413)", r.rejected)
        assertTrue(r.attention)
        assertEquals(SyncBadge.REJECTED, syncBadge(SyncState.FAILED, syncConfigured = true))
    }

    @Test
    fun `times read naturally`() {
        assertEquals("today 14:02", whenText(at1402, now, utc))
        assertEquals("yesterday 14:02", whenText(at1402 - 86_400_000L, now, utc))
        assertEquals("10 Nov 14:02", whenText(at1402 - 4 * 86_400_000L, now, utc))
    }

    @Test
    fun `session rows carry the mark only when sync is on and the session is not synced`() {
        val base = SessionDigest.of("a", TestSessions.fourByFourResult(), TestSessions.STARTED_AT)
        val digests = listOf(base.copy(id = "p", syncState = SyncState.PENDING), base.copy(id = "s", syncState = SyncState.SYNCED), base.copy(id = "f", syncState = SyncState.FAILED))
        assertEquals(listOf(null, null, null), sessionRows(digests, utc, syncConfigured = false).map { it.sync })
        assertEquals(
            mapOf("p" to SyncBadge.WAITING, "s" to null, "f" to SyncBadge.REJECTED),
            sessionRows(digests, utc, syncConfigured = true).associate { it.id to it.sync },
        )
    }
}
