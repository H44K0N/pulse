package dev.tischbein.pulse.coaching

import dev.tischbein.pulse.sensor.HeartRateSample
import dev.tischbein.pulse.time.MillisClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Connects a [CoachingEngine] to a live sample stream, a ticking clock and the user's
 * Pause / Resume / End buttons.
 *
 * Samples, ticks and button presses are merged into ONE stream and handed to the engine
 * one at a time from a single coroutine. That is why the engine needs no locks: it is
 * never called from two places at once.
 *
 * In the app: `runner.run(source.samples)` inside the session's foreground service scope,
 * with a clock based on `SystemClock.elapsedRealtime()`. In tests: the same, under
 * `runTest`, with a clock reading the test scheduler's virtual time, so `delay` is instant.
 */
class CoachingRunner(
    private val engine: CoachingEngine,
    private val clock: MillisClock,
) {
    private sealed interface Input {
        data class Sample(val sample: HeartRateSample) : Input

        data object Tick : Input

        data object Pause : Input

        data object Resume : Input

        data object End : Input
    }

    private val commands = Channel<Input>(Channel.UNLIMITED)

    fun pause() {
        commands.trySend(Input.Pause)
    }

    fun resume() {
        commands.trySend(Input.Resume)
    }

    fun end() {
        commands.trySend(Input.End)
    }

    /**
     * Starts the engine and runs it until the session finishes (protocol complete, or
     * [end] was called). Returns the finished session. Cancelling the calling coroutine
     * stops it without a result.
     */
    suspend fun run(samples: Flow<HeartRateSample>): SessionResult {
        engine.start(clock.nowMs())
        val ticks =
            flow {
                while (true) {
                    delay(engine.config.tickIntervalMs)
                    emit(Input.Tick)
                }
            }
        merge(samples.map { Input.Sample(it) }, ticks, commands.receiveAsFlow())
            .first { input ->
                when (input) {
                    is Input.Sample -> engine.onSample(input.sample)
                    Input.Tick -> engine.onTick(clock.nowMs())
                    Input.Pause -> engine.pause(clock.nowMs())
                    Input.Resume -> engine.resume(clock.nowMs())
                    Input.End -> engine.end(clock.nowMs())
                }
                engine.isFinished
            }
        return engine.result!!
    }
}
