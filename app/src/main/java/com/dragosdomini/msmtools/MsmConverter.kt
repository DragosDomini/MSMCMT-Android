
package com.cyndyrfyrestudios.msmtools

import android.content.Context
import net.jpountz.lz4.LZ4Factory
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * MSM Composer MIDI Converter
 *
 * Maintains the original SFSOBJECT XML structure
 * and raw LZ4 compression format.
 *
 * Features:
 * - Individual MIDI track conversion
 * - Manual monster assignments
 * - Exact MIDI program/bank matching
 * - No automatic Tweedle fallback
 * - Track-wide octave correction
 * - Note duration protection
 * - Tempo and time signature preservation
 * - Export validation
 */
object MsmConverter {

    // --------------------------------------------------
    // NOTE MAPPING
    // --------------------------------------------------

    private val noteMap = mapOf(
        58 to 31,
        59 to 28,
        60 to 32,
        61 to 33,
        62 to 36,
        63 to 37,
        64 to 40,
        65 to 44,
        66 to 45,
        67 to 48,
        68 to 49,
        69 to 52,
        70 to 53,
        71 to 56,
        72 to 60,
        73 to 61,
        74 to 64,
        75 to 65,
        76 to 68,
        77 to 72,
        78 to 73,
        79 to 76,
        80 to 77,
        81 to 80,
        82 to 81,
        83 to 84,
        84 to 85
    )

    private val playablePitches = noteMap.keys

    // --------------------------------------------------
    // MONSTER DATA
    // --------------------------------------------------

    data class Monster(
        val id: Int,
        val name: String,
        val x: Int,
        val y: Int,
        val flip: Int,
        val program: Int,
        val bank: Int
    )

    data class PitchAdjustment(
        val originalPitch: Int,
        val adjustedPitch: Int,
        val octaveShift: Int,
        val msmNote: Int
    )

    data class TrackConversionReport(
        val trackIndex: Int,
        val trackName: String,
        val monsterId: Int,
        val monsterName: String,
        val originalMinimumPitch: Int,
        val originalMaximumPitch: Int,
        val adjustedMinimumPitch: Int,
        val adjustedMaximumPitch: Int,
        val octaveShift: Int,
        val adjustedNoteCount: Int,
        val totalNoteCount: Int
    )

    data class ConversionReport(
        val songName: String,
        val tempo: Int,
        val upperNumeral: Int,
        val lowerNumeral: Int,
        val tracks: List<TrackConversionReport>
    )

    private data class ConvertedTrack(
        val xml: String,
        val monsterXml: String,
        val assignmentXml: String,
        val report: TrackConversionReport
    )

    private data class OctaveSolution(
        val shift: Int,
        val pitches: List<Int>,
        val cost: Int
    )

    // --------------------------------------------------
    // MAIN CONVERTER
    // --------------------------------------------------

    @Throws(IOException::class)
    fun convert(
        context: Context,
        midi: ParsedMidi,
        minimumNote: Int,
        monsterAssignments: Map<Int, Int> = emptyMap()
    ): ByteArray {

        val xml = buildXml(
            context,
            midi,
            minimumNote,
            monsterAssignments
        )

        val input = xml.toByteArray(
            StandardCharsets.UTF_8
        ) + byteArrayOf(0)

        return try {

            val compressor = LZ4Factory
                .fastestInstance()
                .highCompressor()

            val maximumSize =
                compressor.maxCompressedLength(input.size)

            val compressed = ByteArray(maximumSize)

            val compressedLength = compressor.compress(
                input,
                0,
                input.size,
                compressed,
                0,
                maximumSize
            )

            if (compressedLength <= 0) {
                throw IOException(
                    "MSM compression returned an invalid result."
                )
            }

            compressed.copyOf(compressedLength)

        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(
                "Unable to compress MSM composition: ${e.message}",
                e
            )
        }
    }

    // --------------------------------------------------
    // CONVERSION REPORT
    // --------------------------------------------------

    @Throws(IOException::class)
    fun analyzeConversion(
        context: Context,
        midi: ParsedMidi,
        minimumNote: Int,
        monsterAssignments: Map<Int, Int> = emptyMap()
    ): ConversionReport {

        validateMidi(midi, minimumNote)

        val monsters = loadMonsterDatabase(context)

        val reports = mutableListOf<TrackConversionReport>()

        midi.tracks.forEachIndexed { index, track ->

            if (track.notes.isEmpty()) return@forEachIndexed

            val monster = resolveMonster(
                monsters,
                track.program,
                track.bank,
                monsterAssignments[index],
                track.name
            )

            val solution = findBestOctaveSolution(track.notes)

            val originalPitches = track.notes.map { it.pitch }

            reports.add(
                TrackConversionReport(
                    trackIndex = index,
                    trackName = track.name,
                    monsterId = monster.id,
                    monsterName = monster.name,
                    originalMinimumPitch = originalPitches.minOrNull()!!,
                    originalMaximumPitch = originalPitches.maxOrNull()!!,
                    adjustedMinimumPitch = solution.pitches.minOrNull()!!,
                    adjustedMaximumPitch = solution.pitches.maxOrNull()!!,
                    octaveShift = solution.shift,
                    adjustedNoteCount = solution.pitches.countIndexed { i, pitch ->
                        pitch != track.notes[i].pitch
                    },
                    totalNoteCount = track.notes.size
                )
            )
        }

        if (reports.isEmpty()) {
            throw IOException(
                "The MIDI file contains no playable notes."
            )
        }

        return ConversionReport(
            songName = midi.songName,
            tempo = midi.tempo,
            upperNumeral = midi.timeNumerator,
            lowerNumeral = midi.timeDenominator,
            tracks = reports
        )
    }

    // --------------------------------------------------
    // MIDI VALIDATION
    // --------------------------------------------------

    private fun validateMidi(
        midi: ParsedMidi,
        minimumNote: Int
    ) {

        if (minimumNote !in listOf(8, 16, 32)) {
            throw IOException(
                "Invalid note division. Select 1/8, 1/16, or 1/32."
            )
        }

        if (midi.ticksPerBeat <= 0) {
            throw IOException(
                "Invalid MIDI ticks-per-beat value."
            )
        }

        if (midi.tempo !in 1..1000) {
            throw IOException(
                "Invalid MIDI tempo: ${midi.tempo}."
            )
        }

        if (midi.timeNumerator !in 1..12) {
            throw IOException(
                "Invalid upper numeral: ${midi.timeNumerator}."
            )
        }

        if (midi.timeDenominator !in listOf(2, 4, 8)) {
            throw IOException(
                "Invalid lower numeral: ${midi.timeDenominator}."
            )
        }

        if (midi.tracks.isEmpty()) {
            throw IOException(
                "The MIDI contains no instrument tracks."
            )
        }

        midi.tracks.forEach { track ->

            track.notes.forEach { note ->

                if (note.pitch !in 0..127) {
                    throw IOException(
                        "Invalid MIDI pitch in ${track.name}."
                    )
                }

                if (note.startTick < 0 ||
                    note.endTick <= note.startTick
                ) {
                    throw IOException(
                        "Invalid note timing in ${track.name}."
                    )
                }
            }
        }
    }

    // --------------------------------------------------
    // MONSTER DATABASE
    // --------------------------------------------------

    @Throws(IOException::class)
    fun loadMonsterDatabase(
        context: Context
    ): List<Monster> {

        val jsonText = try {

            context.applicationContext.assets
                .open("mdata.json")
                .bufferedReader()
                .use { it.readText() }

        } catch (e: Exception) {

            throw IOException(
                "Unable to load mdata.json: ${e.message}",
                e
            )
        }

        val json = try {

            JSONObject(jsonText)

        } catch (e: Exception) {

            throw IOException(
                "Monster database contains invalid JSON.",
                e
            )
        }

        val monsters = mutableListOf<Monster>()

        val keys = json.keys()

        while (keys.hasNext()) {

            val key = keys.next()

            val id = key.toIntOrNull() ?: continue

            val entry = json.optJSONObject(key) ?: continue

            val name = entry.optString("name", "").trim()

            if (name.isBlank()) continue

            val program = entry.optInt("midi_program", -1)
            val bank = entry.optInt("midi_bank_select_msb", 0)

            if (program !in -1..127) continue
            if (bank !in 0..127) continue

            monsters.add(
                Monster(
                    id = id,
                    name = name,
                    x = entry.optInt("pos_x", 0),
                    y = entry.optInt("pos_y", 0),
                    flip = entry.optInt("flip", 0),
                    program = program,
                    bank = bank
                )
            )
        }

        if (monsters.isEmpty()) {
            throw IOException(
                "No valid monsters were found in mdata.json."
            )
        }

        return monsters.sortedBy { it.id }
    }

    // --------------------------------------------------
    // BANK NORMALIZATION
    // --------------------------------------------------

    private fun normalizeBank(bank: Int): Int {

        return if (bank > 127) {
            bank ushr 7
        } else {
            bank
        }
    }

    // --------------------------------------------------
    // MONSTER MATCHING
    // --------------------------------------------------

    fun findExactMatches(
        monsters: List<Monster>,
        program: Int,
        bank: Int
    ): List<Monster> {

        if (program !in 0..127 || bank !in 0..16383) {
            return emptyList()
        }

        val normalizedBank = normalizeBank(bank)

        return monsters.filter {

            it.program == program &&
                it.bank == normalizedBank
        }
    }

    fun findMonster(
        monsters: List<Monster>,
        program: Int,
        bank: Int
    ): Monster? {

        return findExactMatches(
            monsters,
            program,
            bank
        ).firstOrNull()
    }

    fun getMonsterOptions(
        context: Context
    ): List<Monster> {

        return loadMonsterDatabase(context)
            .sortedBy {
                it.name.lowercase(Locale.ROOT)
            }
    }

    fun getMonsterSuggestions(
        context: Context,
        program: Int,
        bank: Int
    ): List<Monster> {

        val monsters = loadMonsterDatabase(context)

        return findExactMatches(
            monsters,
            program,
            bank
        )
    }

    private fun resolveMonster(
        monsters: List<Monster>,
        program: Int,
        bank: Int,
        assignedId: Int?,
        trackName: String
    ): Monster {

        if (assignedId != null) {

            return monsters.firstOrNull {
                it.id == assignedId
            } ?: throw IOException(
                "Selected monster ID $assignedId does not exist."
            )
        }

        return findMonster(
            monsters,
            program,
            bank
        ) ?: throw IOException(
            "No exact monster mapping was found for $trackName.\n\n" +
                "MIDI program: $program\n" +
                "MIDI bank: $bank\n\n" +
                "Please select a monster manually before exporting."
        )
    }

    // --------------------------------------------------
    // OCTAVE CORRECTION
    // --------------------------------------------------

    /**
     * Finds one octave shift for the entire track.
     *
     * Applying the same shift to every note preserves
     * the original melodic intervals.
     *
     * The supported conversion pitches are 58..84.
     *
     * This is a Composer pitch-range correction,
     * NOT a verified monster-specific pitch range.
     */

    private fun findBestOctaveSolution(
        notes: List<MidiNote>
    ): OctaveSolution {

        if (notes.isEmpty()) {
            throw IOException("Cannot convert an empty track.")
        }

        var best: OctaveSolution? = null

        for (octaves in -12..12) {

            val shift = octaves * 12

            val adjusted = notes.map {
                it.pitch + shift
            }

            // Reject octave shifts that place any note
            // outside the available Composer pitch map.

            if (adjusted.any { it !in playablePitches }) {
                continue
            }

            val cost = notes.indices.sumOf { index ->

                abs(
                    adjusted[index] - notes[index].pitch
                )
            }

            val candidate = OctaveSolution(
                shift = shift,
                pitches = adjusted,
                cost = cost
            )

            if (
                best == null ||
                candidate.cost < best.cost ||
                (
                    candidate.cost == best.cost &&
                        abs(candidate.shift) < abs(best.shift)
                    )
            ) {
                best = candidate
            }
        }

        return best ?: throw IOException(
            "The notes in this track cannot fit inside the " +
                "available Composer pitch range using octave correction. " +
                "Manual musical transposition is required."
        )
    }

    fun getPitchAdjustments(
        notes: List<MidiNote>
    ): List<PitchAdjustment> {

        val solution = findBestOctaveSolution(notes)

        return notes.mapIndexed { index, note ->

            val pitch = solution.pitches[index]

            PitchAdjustment(
                originalPitch = note.pitch,
                adjustedPitch = pitch,
                octaveShift = solution.shift,
                msmNote = noteMap[pitch]
                    ?: throw IOException(
                        "No Composer mapping exists for pitch $pitch."
                    )
            )
        }
    }

    // --------------------------------------------------
    // NOTE TIMING
    // --------------------------------------------------

    private fun calculateNoteDivision(
        ticksPerBeat: Int,
        minimumNote: Int
    ): Long {

        val result = ticksPerBeat.toDouble() /
            (minimumNote.toDouble() / 4.0)

        return result.roundToInt()
            .coerceAtLeast(1)
            .toLong()
    }

    private fun quantizePosition(
        ticks: Long,
        noteDivision: Long
    ): Int {

        if (ticks < 0) {
            throw IOException("Negative note position detected.")
        }

        val result = (
            ticks.toDouble() / noteDivision.toDouble()
            ).roundToInt()

        if (result < 0) {
            throw IOException("Invalid note position.")
        }

        return result
    }

    private fun quantizeDuration(
        startTick: Long,
        endTick: Long,
        noteDivision: Long
    ): Int {

        val duration = endTick - startTick

        if (duration <= 0) {
            throw IOException("Invalid note duration.")
        }

        // Preserve extremely short notes as one grid unit.
        // Never silently discard them.

        return (
            duration.toDouble() / noteDivision.toDouble()
            ).roundToInt().coerceAtLeast(1)
    }

    // --------------------------------------------------
    // XML GENERATION
    // --------------------------------------------------

    @Throws(IOException::class)
    fun buildXml(
        context: Context,
        midi: ParsedMidi,
        minimumNote: Int,
        monsterAssignments: Map<Int, Int> = emptyMap()
    ): String {

        validateMidi(midi, minimumNote)

        val monsters = loadMonsterDatabase(context)

        val noteDivision = calculateNoteDivision(
            midi.ticksPerBeat,
            minimumNote
        )

        val output = StringBuilder()

        val assignments = StringBuilder()

        val trackXml = StringBuilder()

        val usedAssignments = mutableSetOf<Int>()

        output.append("<?xml version=\"1.0\"?>\n")
        output.append("<SFSOBJECT key=\"root\">\n")

        output.append("\t<SFSARRAY key=\"islands\">\n")
        output.append("\t\t<SFSOBJECT key=\"\">\n")

        output.append(
            "\t\t\t<INT key=\"island\" value=\"11\"/>\n"
        )

        output.append(
            "\t\t\t<SFSARRAY key=\"monsters\">\n"
        )

        var convertedTrackCount = 0

        for ((index, track) in midi.tracks.withIndex()) {

            if (track.notes.isEmpty()) continue

            // ------------------------------------------
            // MONSTER ASSIGNMENT
            // ------------------------------------------

            val monster = resolveMonster(
                monsters = monsters,
                program = track.program,
                bank = track.bank,
                assignedId = monsterAssignments[index],
                trackName = track.name
            )

            monsterAssignments[index]?.let {
                usedAssignments.add(index)
            }

            // ------------------------------------------
            // PITCH CORRECTION
            // ------------------------------------------

            val pitchSolution = findBestOctaveSolution(
                track.notes
            )

            val monsterId = monster.id

            val userMonsterId = index + 1
            val userTrackId = index + 2

            val x = track.posX ?: monster.x
            val y = track.posY ?: monster.y
            val flip = track.flip ?: monster.flip

            // ------------------------------------------
            // MONSTER XML
            // ------------------------------------------

            output.append("\t\t\t\t<SFSOBJECT key=\"\">\n")

            output.append(
                "\t\t\t\t\t<INT key=\"flip\" value=\"$flip\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<INT key=\"monster\" value=\"$monsterId\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<INT key=\"pos_x\" value=\"$x\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<INT key=\"pos_y\" value=\"$y\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<INT key=\"muted\" value=\"${track.muted}\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<LONG key=\"user_monster_id\" " +
                    "value=\"$userMonsterId 0\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<DOUBLE key=\"volume\" " +
                    "value=\"${formatVolume(track.volume)}\"/>\n"
            )

            output.append("\t\t\t\t</SFSOBJECT>\n")

            // ------------------------------------------
            // MONSTER TRACK ASSIGNMENT
            // ------------------------------------------

            assignments.append("\t\t\t\t<SFSOBJECT key=\"\">\n")

            assignments.append(
                "\t\t\t\t\t<LONG key=\"monster\" " +
                    "value=\"$userMonsterId 0\"/>\n"
            )

            assignments.append(
                "\t\t\t\t\t<LONG key=\"track\" " +
                    "value=\"$userTrackId 0\"/>\n"
            )

            assignments.append("\t\t\t\t</SFSOBJECT>\n")

            // ------------------------------------------
            // NOTE CONVERSION
            // ------------------------------------------

            val convertedNotes = mutableListOf<Int>()
            val noteLengths = mutableListOf<Int>()
            val noteTimes = mutableListOf<Int>()

            for ((noteIndex, note) in track.notes.withIndex()) {

                val pitch = pitchSolution.pitches[noteIndex]

                val msmNote = noteMap[pitch]
                    ?: throw IOException(
                        "No MSM note mapping exists for pitch $pitch " +
                            "in ${track.name}."
                    )

                val start = quantizePosition(
                    note.startTick,
                    noteDivision
                )

                val length = quantizeDuration(
                    note.startTick,
                    note.endTick,
                    noteDivision
                )

                convertedNotes.add(msmNote)
                noteTimes.add(start)
                noteLengths.add(length)
            }

            // ------------------------------------------
            // TRACK XML
            // ------------------------------------------

            trackXml.append("\t\t<SFSOBJECT key=\"\">\n")

            trackXml.append(
                "\t\t\t<INT key=\"format\" value=\"2 0\"/>\n"
            )

            trackXml.append(
                "\t\t\t<INTARRAY key=\"lengths\">"
            )

            trackXml.append(
                noteLengths.joinToString("") { "$it," }
            )

            trackXml.append("</INTARRAY>\n")

            trackXml.append(
                "\t\t\t<STRING key=\"name\" value=\"\"/>\n"
            )

            trackXml.append(
                "\t\t\t<INTARRAY key=\"notes\">"
            )

            trackXml.append(
                convertedNotes.joinToString("") { "$it," }
            )

            trackXml.append("</INTARRAY>\n")

            trackXml.append(
                "\t\t\t<INTARRAY key=\"times\">"
            )

            trackXml.append(
                noteTimes.joinToString("") { "$it," }
            )

            trackXml.append("</INTARRAY>\n")

            trackXml.append(
                "\t\t\t<LONG key=\"user\" value=\"1 0\"/>\n"
            )

            trackXml.append(
                "\t\t\t<LONG key=\"user_track_id\" " +
                    "value=\"$userTrackId 0\"/>\n"
            )

            trackXml.append("\t\t</SFSOBJECT>\n")

            convertedTrackCount++
        }

        // ----------------------------------------------
        // ASSIGNMENT VALIDATION
        // ----------------------------------------------

        if (convertedTrackCount == 0) {
            throw IOException(
                "No tracks were converted. The MIDI contains no playable notes."
            )
        }

        // Reject assignments for nonexistent or empty parts.
        monsterAssignments.keys.forEach { index ->

            if (index !in midi.tracks.indices ||
                midi.tracks[index].notes.isEmpty()
            ) {
                throw IOException(
                    "Monster assignment refers to an invalid MIDI track: $index."
                )
            }
        }

        // ----------------------------------------------
        // CLOSE MONSTER ARRAY
        // ----------------------------------------------

        output.append("\t\t\t</SFSARRAY>\n")

        output.append(
            "\t\t\t<STRING key=\"name\" " +
                "value=\"${escapeXml(midi.songName)}\"/>\n"
        )

        // ----------------------------------------------
        // SONG SETTINGS
        // ----------------------------------------------

        output.append("\t\t\t<SFSOBJECT key=\"song\">\n")

        output.append(
            "\t\t\t\t<INT key=\"key_sig\" value=\"0\"/>\n"
        )

        output.append(
            "\t\t\t\t<INT key=\"tempo\" value=\"${midi.tempo}\"/>\n"
        )

        output.append(
            "\t\t\t\t<INT key=\"time_denom\" " +
                "value=\"${midi.timeDenominator}\"/>\n"
        )

        output.append(
            "\t\t\t\t<INT key=\"time_numerator\" " +
                "value=\"${midi.timeNumerator}\"/>\n"
        )

        // ----------------------------------------------
        // TRACK ASSIGNMENTS
        // ----------------------------------------------

        output.append(
            "\t\t\t\t<SFSARRAY key=\"tracks\">\n"
        )

        output.append(assignments)

        output.append(
            "\t\t\t\t</SFSARRAY>\n"
        )

        output.append(
            "\t\t\t\t<LONG key=\"user\" value=\"1 0\"/>\n"
        )

        output.append("\t\t\t</SFSOBJECT>\n")

        output.append(
            "\t\t\t<LONG key=\"user\" value=\"1 0\"/>\n"
        )

        output.append(
            "\t\t\t<LONG key=\"user_island_id\" value=\"1 0\"/>\n"
        )

        output.append("\t\t</SFSOBJECT>\n")

        output.append("\t</SFSARRAY>\n")

        // ----------------------------------------------
        // FINAL TRACK ARRAY
        // ----------------------------------------------

        output.append("\t<SFSARRAY key=\"tracks\">\n")

        output.append(trackXml)

        output.append("\t</SFSARRAY>\n")

        // ----------------------------------------------
        // VERSION
        // ----------------------------------------------

        output.append(
            "\t<INT key=\"versionCode\" value=\"3\"/>\n"
        )

        output.append("</SFSOBJECT>")

        return output.toString()
    }

    // --------------------------------------------------
    // FORMATTING HELPERS
    // --------------------------------------------------

    private fun formatVolume(value: Float): String {

        return String.format(
            Locale.US,
            "%.6f",
            value.coerceIn(0f, 1f)
        )
    }

    private fun escapeXml(value: String): String {

        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&apos;")
    }

    // --------------------------------------------------
    // SMALL COLLECTION HELPER
    // --------------------------------------------------

    private inline fun <T> List<T>.countIndexed(
        predicate: (Int, T) -> Boolean
    ): Int {

        var count = 0

        forEachIndexed { index, value ->
            if (predicate(index, value)) count++
        }

        return count
    }
}
