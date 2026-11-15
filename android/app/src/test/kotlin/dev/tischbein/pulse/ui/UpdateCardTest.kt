package dev.tischbein.pulse.ui

import dev.tischbein.pulse.update.ExpectedFile
import dev.tischbein.pulse.update.SessionGate
import dev.tischbein.pulse.update.UpdateOffer
import dev.tischbein.pulse.update.UpdatePhase
import dev.tischbein.pulse.update.UpdateTexts
import dev.tischbein.pulse.update.UpdateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the banner says and offers in each phase, and that it never shows during a session. */
class UpdateCardTest {
    private val offer = UpdateOffer(7, "0.5.2", "Faster sync.", "/api/app/apk?v=7", ExpectedFile(10, "0".repeat(64)))

    private fun card(phase: UpdatePhase) = UpdateCard.of(UpdateView(offer, phase), sessionRunning = false)!!

    @Test
    fun `each phase has its sentence and at most one button`() {
        assertEquals(UpdateCardUi("Update to 0.5.2", "Faster sync.", null, button = UpdateButton.UPDATE), card(UpdatePhase.Ready))
        assertEquals(UpdateButton.OPEN_SETTINGS, card(UpdatePhase.AskPermission).button)
        assertEquals(UpdateTexts.PERMISSION, card(UpdatePhase.AskPermission).status)
        with(card(UpdatePhase.Downloading(42))) {
            assertEquals("Downloading… 42 %", status)
            assertEquals(42, progress)
            assertNull(button.label)
        }
        assertNull(card(UpdatePhase.Checking).button.label)
        assertEquals(UpdateTexts.DOWNLOADED, card(UpdatePhase.Downloaded).status)
        assertEquals("Update", card(UpdatePhase.Downloaded).button.label)
        assertNull(card(UpdatePhase.Installing).button.label)
        with(card(UpdatePhase.Failed(UpdateTexts.DAMAGED))) {
            assertEquals(UpdateTexts.DAMAGED, status)
            assertTrue(problem)
            assertEquals("Try again", button.label)
        }
    }

    @Test
    fun `no banner without an offer, and none while a session runs or is being set up`() {
        assertNull(UpdateCard.of(UpdateView(null), sessionRunning = false))
        listOf(UpdatePhase.Ready, UpdatePhase.Downloading(5), UpdatePhase.Downloaded, UpdatePhase.Failed("x"), UpdatePhase.AskPermission).forEach {
            assertNull(UpdateCard.of(UpdateView(offer, it), sessionRunning = true))
        }
    }

    @Test
    fun `Start waits only while the install is with Android`() {
        assertEquals(UpdateTexts.START_WAITS, UpdateCard.startBlocked(UpdateView(offer, UpdatePhase.Installing)))
        assertNull(UpdateCard.startBlocked(UpdateView(offer, UpdatePhase.Downloading(10))))
        assertNull(UpdateCard.startBlocked(null))
    }

    @Test
    fun `the session gate - from Start until the service is gone`() {
        val gate = SessionGate()
        assertFalse(gate.running.value)
        assertTrue(gate.tryStartSession())
        assertTrue(gate.running.value)
        gate.serviceAlive(true)
        assertTrue(gate.running.value)
        assertFalse(gate.tryBeginInstall())
        gate.serviceAlive(false)
        assertFalse(gate.running.value)
        assertTrue(gate.tryBeginInstall())
        assertFalse(gate.tryStartSession())
        gate.endInstall()
        assertTrue(gate.tryStartSession())
        gate.startFailed()
        assertFalse(gate.running.value)
    }
}
