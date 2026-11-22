
package com.dragosdomini.msmtools

import android.content.Context
import net.jpountz.lz4.LZ4Factory
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * MSM MIDI Converter
 *
 * Converts parsed MIDI arrangements into My Singing Monsters
 * Composer-compatible compressed MSM files.
 *
 * Preserves the original Python converter's XML structure
 * and raw LZ4 compression format.
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

    // --------------------------------------------------
    // MAIN CONVERTER
    // --------------------------------------------------

    /**
     * Converts ParsedMidi into an MSM file.
     *
     * monsterAssignments:
     *
     * Key:
     * MIDI part index, starting at zero.
     *
     * Value:
     * Monster ID from mdata.json.
     *
     * Manual monster assignments always override
     * automatic instrument matching.
     */
    @Throws(IOException::class)
    fun convert(
        context: Context,
        midi: ParsedMidi,
        minimumNote: Int,
        monsterAssignments: Map<Int, Int> = emptyMap()
    ): ByteArray {

        // Validate note settings.

        if (minimumNote !in listOf(8, 16, 32)) {
            throw IOException(
                "Invalid minimum note setting. " +
                "Choose 1/8, 1/16, or 1/32."
            )
        }

        // Validate MIDI timing.

        if (midi.ticksPerBeat <= 0) {
            throw IOException(
                "Invalid MIDI timing division."
            )
        }

        if (midi.tempo <= 0) {
            throw IOException(
                "Invalid MIDI tempo."
            )
        }

        if (midi.timeNumerator !in 1..12 ||
            midi.timeDenominator !in listOf(2, 4, 8)
        ) {
            throw IOException(
                "Unsupported time signature: " +
                "${midi.timeNumerator}/${midi.timeDenominator}."
            )
        }

        if (midi.tracks.isEmpty()) {
            throw IOException(
                "No MIDI instrument tracks were found."
            )
        }

        // Calculate the minimum note duration.

        val noteDivision =
            midi.ticksPerBeat / (minimumNote / 4)

        if (noteDivision <= 0) {
            throw IOException(
                "MIDI timing division is too small."
            )
        }

        // Load monster database.

        val monsters = loadMonsterDatabase(context)

        // Generate Composer XML.

        val xml = buildXml(
            midi = midi,
            monsters = monsters,
            noteDivision = noteDivision,
            monsterAssignments = monsterAssignments
        )

        // Encode XML.

        val xmlBytes = xml.toByteArray(
            StandardCharsets.UTF_8
        )

        // Original Python converter format:
        // XML followed by a terminating zero byte.

        val input = xmlBytes + byteArrayOf(0)

        // Compress as a raw LZ4 block.
        // Do not use an LZ4 frame wrapper.

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

        return compressed.copyOf(compressedLength)
    }

    // --------------------------------------------------
    // MONSTER DATABASE
    // --------------------------------------------------

    /**
     * Reads all monsters from the existing mdata.json.
     */
    @Throws(IOException::class)
    fun loadMonsterDatabase(
        context: Context
    ): List<Monster> {

        val jsonText = try {

            context.assets
                .open("mdata.json")
                .bufferedReader()
                .use { it.readText() }

        } catch (e: Exception) {

            throw IOException(
                "Unable to load monster database: ${e.message}"
            )
        }

        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {

            throw IOException(
                "Monster database contains invalid JSON."
            )
        }

        val monsters = mutableListOf<Monster>()

        val keys = json.keys()

        while (keys.hasNext()) {

            val idString = keys.next()

            val id = idString.toIntOrNull()
                ?: continue

            val entry = json.optJSONObject(idString)
                ?: continue

            val name = entry.optString("name", "")

            if (name.isBlank()) continue

            monsters.add(
                Monster(
                    id = id,
                    name = name,
                    x = entry.optInt("pos_x", 0),
                    y = entry.optInt("pos_y", 0),
                    flip = entry.optInt("flip", 0),
                    program = entry.optInt(
                        "midi_program",
                        -1
                    ),
                    bank = entry.optInt(
                        "midi_bank_select_msb",
                        0
                    )
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
    // MIDI BANK NORMALIZATION
    // --------------------------------------------------

    /**
     * MidiReader stores bank select as:
     *
     * (MSB << 7) | LSB
     *
     * mdata.json stores only the MSB.
     *
     * Convert the combined value back into the MSB
     * before comparing monster mappings.
     */
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

    /**
     * Finds exact program and bank matches.
     */
    fun findExactMatches(
        monsters: List<Monster>,
        program: Int,
        bank: Int
    ): List<Monster> {

        val normalizedBank = normalizeBank(bank)

        return monsters.filter {

            it.program == program &&
            it.bank == normalizedBank
        }
    }

    /**
     * Finds the best automatic monster match.
     */
    fun findMonster(
        monsters: List<Monster>,
        program: Int,
        bank: Int
    ): Monster? {

        val exactMatches = findExactMatches(
            monsters,
            program,
            bank
        )

        if (exactMatches.isNotEmpty()) {
            return exactMatches.first()
        }

        // No automatic match found.
        return null
    }

    /**
     * Returns monsters available for manual selection.
     */
    fun getMonsterOptions(
        context: Context
    ): List<Monster> {

        return loadMonsterDatabase(context)
            .sortedBy {
                it.name.lowercase(Locale.ROOT)
            }
    }

    /**
     * Returns every monster matching an instrument.
     */
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

    // --------------------------------------------------
    // XML GENERATION
    // --------------------------------------------------

    private fun buildXml(
        midi: ParsedMidi,
        monsters: List<Monster>,
        noteDivision: Int,
        monsterAssignments: Map<Int, Int>
    ): String {

        val output = StringBuilder()

        val assignments = StringBuilder()

        val trackXml = StringBuilder()

        // ROOT OBJECT

        output.append(
            "<?xml version=\"1.0\"?>\n"
        )

        output.append(
            "<SFSOBJECT key=\"root\">\n"
        )

        // ISLAND ARRAY

        output.append(
            "\t<SFSARRAY key=\"islands\">\n"
        )

        output.append(
            "\t\t<SFSOBJECT key=\"\">\n"
        )

        output.append(
            "\t\t\t<INT key=\"island\" value=\"11\"/>\n"
        )

        // MONSTER ARRAY

        output.append(
            "\t\t\t<SFSARRAY key=\"monsters\">\n"
        )

        // Process every MIDI part independently.

        for ((index, track) in midi.tracks.withIndex()) {

            if (track.notes.isEmpty()) {
                continue
            }

            // ------------------------------------------
            // MONSTER SELECTION
            // ------------------------------------------

            val manuallySelectedId =
                monsterAssignments[index]

            val monster = if (manuallySelectedId != null) {

                monsters.firstOrNull {
                    it.id == manuallySelectedId
                } ?: throw IOException(
                    "Selected monster ID $manuallySelectedId " +
                    "does not exist in mdata.json."
                )

            } else {

                findMonster(
                    monsters,
                    track.program,
                    track.bank
                ) ?: throw IOException(
                    "No monster mapping found for MIDI " +
                    "program ${track.program}, " +
                    "bank ${track.bank} " +
                    "in track ${track.name}.\n\n" +
                    "Please select a monster manually."
                )
            }

            // ------------------------------------------
            // MONSTER IDENTIFIERS
            // ------------------------------------------

            val monsterId = monster.id

            val userMonsterId = index + 1

            val userTrackId = index + 2

            // Preserve MIDI positioning overrides.

            val x = track.posX ?: monster.x

            val y = track.posY ?: monster.y

            val flip = track.flip ?: monster.flip

            // ------------------------------------------
            // MONSTER XML
            // ------------------------------------------

            output.append(
                "\t\t\t\t<SFSOBJECT key=\"\">\n"
            )

            output.append(
                "\t\t\t\t\t<INT key=\"flip\" " +
                "value=\"$flip\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<INT key=\"monster\" " +
                "value=\"$monsterId\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<INT key=\"pos_x\" " +
                "value=\"$x\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<INT key=\"pos_y\" " +
                "value=\"$y\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<INT key=\"muted\" " +
                "value=\"${track.muted}\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<LONG key=\"user_monster_id\" " +
                "value=\"$userMonsterId 0\"/>\n"
            )

            output.append(
                "\t\t\t\t\t<DOUBLE key=\"volume\" " +
                "value=\"${formatVolume(track.volume)}\"/>\n"
            )

            output.append(
                "\t\t\t\t</SFSOBJECT>\n"
            )

            // ------------------------------------------
            // MONSTER-TO-TRACK ASSIGNMENT
            // ------------------------------------------

            assignments.append(
                "\t\t\t\t<SFSOBJECT key=\"\">\n"
            )

            assignments.append(
                "\t\t\t\t\t<LONG key=\"monster\" " +
                "value=\"$userMonsterId 0\"/>\n"
            )

            assignments.append(
                "\t\t\t\t\t<LONG key=\"track\" " +
                "value=\"$userTrackId 0\"/>\n"
            )

            assignments.append(
                "\t\t\t\t</SFSOBJECT>\n"
            )

            // ------------------------------------------
            // NOTE CONVERSION
            // ------------------------------------------

            val convertedNotes = mutableListOf<Int>()

            val noteLengths = mutableListOf<Int>()

            val noteTimes = mutableListOf<Int>()

            for (note in track.notes) {

                // Map MIDI pitch into the Composer range.

                val pitch = when (note.pitch) {

                    in 36..57 -> note.pitch + 24

                    in 58..84 -> note.pitch

                    in 85..96 -> note.pitch - 12

                    else -> throw IOException(
                        "MIDI note ${note.pitch} in track " +
                        "${track.name} is outside the " +
                        "supported Composer pitch range."
                    )
                }

                val msmNote = noteMap[pitch]
                    ?: throw IOException(
                        "Could not convert MIDI pitch $pitch " +
                        "in track ${track.name}."
                    )

                // Calculate note duration.

                val lengthTicks =
                    note.endTick - note.startTick

                if (lengthTicks <= 0) {

                    throw IOException(
                        "A note in track ${track.name} " +
                        "has zero or negative duration."
                    )
                }

                // Convert MIDI ticks to Composer divisions.

                val start =
                    (note.startTick / noteDivision).toInt()

                val length =
                    (lengthTicks / noteDivision).toInt()

                if (length <= 0) {

                    throw IOException(
                        "A note in track ${track.name} " +
                        "is shorter than the selected " +
                        "minimum note length.\n\n" +
                        "Try selecting 1/16 or 1/32."
                    )
                }

                convertedNotes.add(msmNote)

                noteTimes.add(start)

                noteLengths.add(length)
            }

            // ------------------------------------------
            // TRACK XML
            // ------------------------------------------

            trackXml.append(
                "\t\t<SFSOBJECT key=\"\">\n"
            )

            trackXml.append(
                "\t\t\t<INT key=\"format\" value=\"2 0\"/>\n"
            )

            trackXml.append(
                "\t\t\t<INTARRAY key=\"lengths\">"
            )

            trackXml.append(
                noteLengths.joinToString("") {
                    "$it,"
                }
            )

            trackXml.append(
                "</INTARRAY>\n"
            )

            trackXml.append(
                "\t\t\t<STRING key=\"name\" value=\"\"/>\n"
            )

            trackXml.append(
                "\t\t\t<INTARRAY key=\"notes\">"
            )

            trackXml.append(
                convertedNotes.joinToString("") {
                    "$it,"
                }
            )

            trackXml.append(
                "</INTARRAY>\n"
            )

            trackXml.append(
                "\t\t\t<INTARRAY key=\"times\">"
            )

            trackXml.append(
                noteTimes.joinToString("") {
                    "$it,"
                }
            )

            trackXml.append(
                "</INTARRAY>\n"
            )

            trackXml.append(
                "\t\t\t<LONG key=\"user\" value=\"1 0\"/>\n"
            )

            trackXml.append(
                "\t\t\t<LONG key=\"user_track_id\" " +
                "value=\"$userTrackId 0\"/>\n"
            )

            trackXml.append(
                "\t\t</SFSOBJECT>\n"
            )
        }

        // ----------------------------------------------
        // CLOSE MONSTER ARRAY
        // ----------------------------------------------

        output.append(
            "\t\t\t</SFSARRAY>\n"
        )

        output.append(
            "\t\t\t<STRING key=\"name\" " +
            "value=\"${escapeXml(midi.songName)}\"/>\n"
        )

        // ----------------------------------------------
        // SONG SETTINGS
        // ----------------------------------------------

        output.append(
            "\t\t\t<SFSOBJECT key=\"song\">\n"
        )

        output.append(
            "\t\t\t\t<INT key=\"key_sig\" value=\"0\"/>\n"
        )

        output.append(
            "\t\t\t\t<INT key=\"tempo\" " +
            "value=\"${midi.tempo}\"/>\n"
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

        output.append(
            "\t\t\t</SFSOBJECT>\n"
        )

        output.append(
            "\t\t\t<LONG key=\"user\" value=\"1 0\"/>\n"
        )

        output.append(
            "\t\t\t<LONG key=\"user_island_id\" value=\"1 0\"/>\n"
        )

        output.append(
            "\t\t</SFSOBJECT>\n"
        )

        output.append(
            "\t</SFSARRAY>\n"
        )

        // ----------------------------------------------
        // FINAL TRACK ARRAY
        // ----------------------------------------------

        output.append(
            "\t<SFSARRAY key=\"tracks\">\n"
        )

        output.append(trackXml)

        output.append(
            "\t</SFSARRAY>\n"
        )

        // ----------------------------------------------
        // VERSION
        // ----------------------------------------------

        output.append(
            "\t<INT key=\"versionCode\" value=\"3\"/>\n"
        )

        output.append(
            "</SFSOBJECT>"
        )

        return output.toString()
    }

    // --------------------------------------------------
    // FORMATTING HELPERS
    // --------------------------------------------------

    private fun formatVolume(value: Float): String {

        return String.format(
            Locale.US,
            "%.6f",
            value
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
}
