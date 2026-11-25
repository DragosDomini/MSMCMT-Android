
package com.cyndyrfyrestudios.msmcmt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class PitchSegment(
    val minPitch: Int,
    val maxPitch: Int,
    val offset: Int
) {
    init {
        require(minPitch in 0..127)
        require(maxPitch in minPitch..127)
        require(offset in -48..48)
    }

    fun contains(pitch: Int): Boolean =
        pitch in minPitch..maxPitch
}

data class Monster(
    val id: String,
    val name: String,
    val posX: Int,
    val posY: Int,
    val flip: Int,
    val midiProgram: Int,
    val midiBankSelectMsb: Int,
    val pitchSegments: List<PitchSegment> = emptyList()
) {
    val hasPitchData: Boolean
        get() = pitchSegments.isNotEmpty()
}

enum class CompatibilityStatus {
    FULL_MATCH,
    OCTAVE_ADJUSTABLE,
    PARTIAL_MATCH,
    INCOMPATIBLE,
    UNKNOWN
}

data class PitchAdjustment(
    val originalPitch: Int,
    val adjustedPitch: Int,
    val storedPitch: Int,
    val offset: Int,
    val octaveShift: Int
)

data class MonsterCompatibility(
    val monster: Monster,
    val status: CompatibilityStatus,
    val score: Int,
    val totalNotes: Int,
    val compatibleNotes: Int,
    val incompatibleNotes: Int,
    val minimumInputPitch: Int,
    val maximumInputPitch: Int,
    val octaveShift: Int,
    val adjustments: List<PitchAdjustment>,
    val explanation: String
) {
    val accommodatesEntireTrack: Boolean
        get() = totalNotes > 0 &&
            compatibleNotes == totalNotes &&
            incompatibleNotes == 0 &&
            status != CompatibilityStatus.UNKNOWN
}

object MonsterMapping {

    private val monsters = mutableMapOf<String, Monster>()

    private const val DEFAULT_LEGACY_MIN = 0
    private const val DEFAULT_LEGACY_MAX = 127

    /**
     * Load the monster database from app/src/main/assets/mdata.json.
     */
    fun load(context: Context): List<Monster> {
        val json = context.assets.open("mdata.json")
            .bufferedReader()
            .use { it.readText() }

        return loadFromJson(json)
    }

    /**
     * Parse and validate the complete monster database.
     */
    @Synchronized
    fun loadFromJson(json: String): List<Monster> {
        val root = JSONObject(json)
        val loaded = mutableMapOf<String, Monster>()

        val keys = root.keys()

        while (keys.hasNext()) {
            val id = keys.next()
            val item = root.optJSONObject(id) ?: continue

            val name = item.optString("name", "").trim()

            if (name.isEmpty()) continue

            val segments = parsePitchSegments(item)

            val monster = Monster(
                id = id,
                name = name,
                posX = item.optInt("pos_x", 0),
                posY = item.optInt("pos_y", 0),
                flip = item.optInt("flip", 0),
                midiProgram = item.optInt("midi_program", 0),
                midiBankSelectMsb = item.optInt(
                    "midi_bank_select_msb",
                    0
                ),
                pitchSegments = segments
            )

            loaded[id] = monster
        }

        synchronized(monsters) {
            monsters.clear()
            monsters.putAll(loaded)
        }

        return loaded.values.sortedBy { it.name }
    }

    /**
     * Parse pitch_segments.
     *
     * Format:
     * [
     *   [minimum sounding MIDI pitch,
     *    maximum sounding MIDI pitch,
     *    storage offset]
     * ]
     */
    private fun parsePitchSegments(
        item: JSONObject
    ): List<PitchSegment> {

        if (!item.has("pitch_segments")) {
            return emptyList()
        }

        val array = item.optJSONArray("pitch_segments")
            ?: throw IllegalArgumentException(
                "pitch_segments must be an array"
            )

        val result = mutableListOf<PitchSegment>()

        for (i in 0 until array.length()) {
            val segment = array.optJSONArray(i)
                ?: throw IllegalArgumentException(
                    "Invalid pitch segment at index $i"
                )

            if (segment.length() != 3) {
                throw IllegalArgumentException(
                    "Pitch segment must contain exactly 3 values"
                )
            }

            val min = segment.optInt(0, -1)
            val max = segment.optInt(1, -1)
            val offset = segment.optInt(2, Int.MIN_VALUE)

            result.add(
                PitchSegment(
                    minPitch = min,
                    maxPitch = max,
                    offset = offset
                )
            )
        }

        if (result.isEmpty()) {
            throw IllegalArgumentException(
                "Pitch segment list cannot be empty"
            )
        }

        return result.sortedBy { it.minPitch }
    }

    fun getAllMonsters(): List<Monster> =
        synchronized(monsters) {
            monsters.values.sortedBy { it.name }
        }

    fun getMonster(id: String): Monster? =
        synchronized(monsters) {
            monsters[id]
        }

    fun getMonsterByName(name: String): Monster? =
        synchronized(monsters) {
            monsters.values.firstOrNull {
                it.name.equals(name, ignoreCase = true)
            }
        }

    /**
     * Find the best matching pitch segment for one MIDI note.
     *
     * The pitch segment describes the sounding range.
     * offset converts sounding pitch into stored MSM pitch.
     */
    fun findSegment(
        monster: Monster,
        soundingPitch: Int
    ): PitchSegment? {
        return monster.pitchSegments.firstOrNull {
            it.contains(soundingPitch)
        }
    }

    /**
     * Convert sounding pitch into the monster's stored pitch.
     */
    fun soundingToStoredPitch(
        monster: Monster,
        soundingPitch: Int
    ): Int? {

        val segment = findSegment(monster, soundingPitch)
            ?: return null

        val stored = soundingPitch + segment.offset

        return stored.takeIf { it in 0..127 }
    }

    /**
     * Check a complete track against one monster.
     *
     * All notes are checked, not just minimum and maximum.
     *
     * Octave adjustments are tested consistently across
     * the entire track.
     */
    fun analyzeCompatibility(
        monster: Monster,
        pitches: List<Int>
    ): MonsterCompatibility {

        if (pitches.isEmpty()) {
            return MonsterCompatibility(
                monster = monster,
                status = CompatibilityStatus.UNKNOWN,
                score = 0,
                totalNotes = 0,
                compatibleNotes = 0,
                incompatibleNotes = 0,
                minimumInputPitch = 0,
                maximumInputPitch = 0,
                octaveShift = 0,
                adjustments = emptyList(),
                explanation = "No MIDI notes were found."
            )
        }

        require(pitches.all { it in 0..127 }) {
            "MIDI pitches must be between 0 and 127."
        }

        if (!monster.hasPitchData) {
            return MonsterCompatibility(
                monster = monster,
                status = CompatibilityStatus.UNKNOWN,
                score = 0,
                totalNotes = pitches.size,
                compatibleNotes = 0,
                incompatibleNotes = pitches.size,
                minimumInputPitch = pitches.minOrNull() ?: 0,
                maximumInputPitch = pitches.maxOrNull() ?: 0,
                octaveShift = 0,
                adjustments = emptyList(),
                explanation = "Pitch data is unavailable for this monster."
            )
        }

        val shifts = listOf(
            0, -12, 12, -24, 24, -36, 36,
            -48, 48
        )

        val evaluations = shifts.map { shift ->
            evaluateShift(monster, pitches, shift)
        }

        val best = evaluations.maxWithOrNull(
            compareBy<MonsterCompatibility> {
                it.compatibleNotes
            }.thenBy {
                it.score
            }.thenBy {
                -abs(it.octaveShift)
            }
        )!!

        return best
    }

    private fun evaluateShift(
        monster: Monster,
        pitches: List<Int>,
        shift: Int
    ): MonsterCompatibility {

        val adjustments = mutableListOf<PitchAdjustment>()
        var compatible = 0

        pitches.forEach { original ->

            val adjusted = original + shift

            val segment = if (adjusted in 0..127) {
                findSegment(monster, adjusted)
            } else {
                null
            }

            if (segment != null) {
                val stored = adjusted + segment.offset

                if (stored in 0..127) {
                    compatible++

                    adjustments.add(
                        PitchAdjustment(
                            originalPitch = original,
                            adjustedPitch = adjusted,
                            storedPitch = stored,
                            offset = segment.offset,
                            octaveShift = shift
                        )
                    )
                }
            }
        }

        val total = pitches.size
        val incompatible = total - compatible

        val score = if (total == 0) {
            0
        } else {
            ((compatible.toDouble() / total) * 100)
                .toInt()
        }

        val status = when {
            compatible == total && shift == 0 ->
                CompatibilityStatus.FULL_MATCH

            compatible == total ->
                CompatibilityStatus.OCTAVE_ADJUSTABLE

            compatible > 0 ->
                CompatibilityStatus.PARTIAL_MATCH

            else ->
                CompatibilityStatus.INCOMPATIBLE
        }

        val explanation = when (status) {
            CompatibilityStatus.FULL_MATCH ->
                "All notes fit the monster's pitch segments."

            CompatibilityStatus.OCTAVE_ADJUSTABLE ->
                "All notes fit after a ${shift}-semitone octave adjustment."

            CompatibilityStatus.PARTIAL_MATCH ->
                "$compatible of $total notes fit. $incompatible notes remain outside the range."

            CompatibilityStatus.INCOMPATIBLE ->
                "No notes fit this monster's available pitch segments."

            CompatibilityStatus.UNKNOWN ->
                "Pitch compatibility is unknown."
        }

        return MonsterCompatibility(
            monster = monster,
            status = status,
            score = score,
            totalNotes = total,
            compatibleNotes = compatible,
            incompatibleNotes = incompatible,
            minimumInputPitch = pitches.minOrNull() ?: 0,
            maximumInputPitch = pitches.maxOrNull() ?: 0,
            octaveShift = shift,
            adjustments = adjustments,
            explanation = explanation
        )
    }

    /**
     * Automatically rank all monsters against a complete track.
     *
     * This function never changes a monster assignment.
     */
    fun rankAlternatives(
        pitches: List<Int>,
        excludeMonsterId: String? = null
    ): List<MonsterCompatibility> {

        return getAllMonsters()
            .filter { it.id != excludeMonsterId }
            .filter { it.hasPitchData }
            .map {
                analyzeCompatibility(it, pitches)
            }
            .sortedWith(
                compareByDescending<MonsterCompatibility> {
                    it.accommodatesEntireTrack
                }.thenByDescending {
                    it.compatibleNotes
                }.thenByDescending {
                    it.score
                }.thenBy {
                    abs(it.octaveShift)
                }.thenBy {
                    it.monster.name
                }
            )
    }

    /**
     * Convenience function for the currently selected monster.
     */
    fun analyzeSelectedMonster(
        monsterId: String,
        pitches: List<Int>
    ): MonsterCompatibility? {

        val monster = getMonster(monsterId)
            ?: return null

        return analyzeCompatibility(monster, pitches)
    }

    /**
     * Return the nearest available sounding pitch.
     *
     * Used when a note is outside all pitch segments.
     * This is a nearest-pitch fallback, not an octave-wide
     * reassignment of the entire track.
     */
    fun nearestPlayablePitch(
        monster: Monster,
        requestedPitch: Int
    ): PitchAdjustment? {

        if (!monster.hasPitchData) return null

        val candidates = mutableListOf<PitchAdjustment>()

        monster.pitchSegments.forEach { segment ->

            val nearest = requestedPitch.coerceIn(
                segment.minPitch,
                segment.maxPitch
            )

            val stored = nearest + segment.offset

            if (stored in 0..127) {
                candidates.add(
                    PitchAdjustment(
                        originalPitch = requestedPitch,
                        adjustedPitch = nearest,
                        storedPitch = stored,
                        offset = segment.offset,
                        octaveShift = nearest - requestedPitch
                    )
                )
            }
        }

        return candidates.minByOrNull {
            abs(it.adjustedPitch - requestedPitch)
        }
    }

    /**
     * Explicit assignment validation.
     *
     * This only checks the proposed assignment.
     * The caller must request user approval before committing it.
     */
    fun canApproveAssignment(
        monsterId: String,
        pitches: List<Int>
    ): Boolean {

        val compatibility = analyzeSelectedMonster(
            monsterId,
            pitches
        ) ?: return false

        return compatibility.accommodatesEntireTrack
    }

    fun clear() {
        synchronized(monsters) {
            monsters.clear()
        }
    }
}
