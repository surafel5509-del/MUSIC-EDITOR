package com.studioone.core.domain.theory

import com.studioone.core.domain.model.MidiNote

enum class ArpPattern { UP, DOWN, UP_DOWN, RANDOM, AS_PLAYED }

/**
 * Generates arpeggiated note sequences from held input notes. Used by the
 * instruments module; deterministic for a given seed so it is unit-testable.
 */
class Arpeggiator(
    var pattern: ArpPattern = ArpPattern.UP,
    var rateDivision: Double = 0.25, // quarter notes per step (1/16 at 4/4)
    var gatePercent: Double = 0.6,
    var octaveRange: Int = 1,
    var seed: Long = 42,
) {
    /** Expands held [notes] into a step sequence; [stepFrames] frames per step. */
    fun sequence(notes: List<MidiNote>, stepFrames: Long, totalSteps: Int): List<MidiNote> {
        if (notes.isEmpty() || totalSteps <= 0) return emptyList()
        val pitches = notes.map { it.pitch }.distinct().sorted()
        val pool = buildList {
            repeat(octaveRange) { octave -> pitches.forEach { add(it + octave * 12) } }
        }
        val ordered = when (pattern) {
            ArpPattern.UP -> pool
            ArpPattern.DOWN -> pool.reversed()
            ArpPattern.UP_DOWN -> (pool + pool.drop(1).dropLast(1).reversed()).ifEmpty { pool }
            ArpPattern.RANDOM -> pool
            ArpPattern.AS_PLAYED -> notes.map { it.pitch }
        }
        if (ordered.isEmpty()) return emptyList()

        val rng = java.util.Random(seed)
        val gateFrames = (stepFrames * gatePercent).toLong().coerceAtLeast(1)
        val baseVelocity = notes.maxOf { it.velocity }

        return (0 until totalSteps).map { step ->
            val pitch = when (pattern) {
                ArpPattern.RANDOM -> ordered[rng.nextInt(ordered.size)]
                else -> ordered[step % ordered.size]
            }
            MidiNote(
                startFrame = step * stepFrames,
                lengthFrames = gateFrames,
                pitch = pitch.coerceIn(0, 127),
                velocity = baseVelocity,
            )
        }
    }
}
