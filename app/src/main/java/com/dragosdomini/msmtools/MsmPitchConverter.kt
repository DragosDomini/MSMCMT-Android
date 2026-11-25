
package com.cyndyrfyrestudios.msmcmt

/**
 * Pitch conversion bridge between the existing MSM converter
 * and the new pitch_segments monster database.
 *
 * Does not change monster assignments automatically.
 */
object MsmPitchConverter {

    data class ConvertedNote(
        val originalPitch: Int,
        val soundingPitch: Int,
        val storedPitch: Int,
        val offset: Int,
        val octaveShift: Int,
        val adjusted: Boolean
    )

    data class ConversionResult(
        val success: Boolean,
        val monsterId: Int,
        val monsterName: String,
        val octaveShift: Int,
        val convertedNotes: List<ConvertedNote>,
        val failedNotes: List<Int>,
        val message: String
    )

    /**
     * Analyze and convert all notes using one consistent octave shift.
     */
    fun convertTrack(
        monsterId: Int,
        midiPitches: List<Int>,
        allowOctaveAdjustment: Boolean = true,
        allowNearestPitchFallback: Boolean = false
    ): ConversionResult {

        val monster = MonsterMapping.getMonster(monsterId.toString())
            ?: return failure(
                monsterId,
                "Monster ID $monsterId was not found."
            )

        if (midiPitches.isEmpty()) {
            return failure(monsterId, "The MIDI track contains no notes.")
        }

        if (midiPitches.any { it !in 0..127 }) {
            return failure(monsterId, "The track contains invalid MIDI pitches.")
        }

        val analysis = MonsterMapping.analyzeCompatibility(
            monster,
            midiPitches
        )

        if (!allowOctaveAdjustment &&
            analysis.status != CompatibilityStatus.FULL_MATCH
        ) {
            return failure(
                monsterId,
                "The selected monster cannot accommodate the entire track without octave adjustment."
            )
        }

        if (!analysis.accommodatesEntireTrack &&
            !allowNearestPitchFallback
        ) {
            return ConversionResult(
                success = false,
                monsterId = monsterId,
                monsterName = monster.name,
                octaveShift = analysis.octaveShift,
                convertedNotes = emptyList(),
                failedNotes = midiPitches.filter { pitch ->
                    val adjusted = pitch + analysis.octaveShift
                    MonsterMapping.soundingToStoredPitch(
                        monster,
                        adjusted
                    ) == null
                },
                message = analysis.explanation
            )
        }

        val converted = mutableListOf<ConvertedNote>()
        val failed = mutableListOf<Int>()

        midiPitches.forEach { original ->

            val adjusted = original + analysis.octaveShift

            var sounding = adjusted

            var stored = MonsterMapping.soundingToStoredPitch(
                monster,
                sounding
            )

            var offset = 0

            if (stored != null) {
                val segment = MonsterMapping.findSegment(
                    monster,
                    sounding
                )

                offset = segment?.offset ?: 0
            } else if (allowNearestPitchFallback) {

                val nearest = MonsterMapping.nearestPlayablePitch(
                    monster,
                    adjusted
                )

                if (nearest != null) {
                    sounding = nearest.adjustedPitch
                    stored = nearest.storedPitch
                    offset = nearest.offset
                }
            }

            if (stored == null) {
                failed.add(original)
            } else {
                converted.add(
                    ConvertedNote(
                        originalPitch = original,
                        soundingPitch = sounding,
                        storedPitch = stored,
                        offset = offset,
                        octaveShift = analysis.octaveShift,
                        adjusted = sounding != original
                    )
                )
            }
        }

        val success = failed.isEmpty()

        return ConversionResult(
            success = success,
            monsterId = monsterId,
            monsterName = monster.name,
            octaveShift = analysis.octaveShift,
            convertedNotes = converted,
            failedNotes = failed,
            message = if (success) {
                "All ${converted.size} notes converted successfully."
            } else {
                "${failed.size} notes could not be converted."
            }
        )
    }

    private fun failure(
        monsterId: Int,
        message: String
    ) = ConversionResult(
        success = false,
        monsterId = monsterId,
        monsterName = "",
        octaveShift = 0,
        convertedNotes = emptyList(),
        failedNotes = emptyList(),
        message = message
    )
}
