
package com.dragosdomini.msmtools

import android.content.Context
import org.json.JSONObject
import net.jpountz.lz4.LZ4Factory
import java.io.IOException
import java.nio.charset.StandardCharsets

object MsmConverter {

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

    private data class Monster(
        val id: Int,
        val name: String,
        val x: Int,
        val y: Int,
        val flip: Int,
        val program: Int,
        val bank: Int
    )

    fun convert(
        context: Context,
        midi: ParsedMidi,
        minimumNote: Int
    ): ByteArray {

        if (minimumNote != 8 &&
            minimumNote != 16 &&
            minimumNote != 32
        ) {
            throw IOException("Invalid minimum note setting.")
        }

        val division = midi.ticksPerBeat / (minimumNote / 4)
        if (division <= 0) {
            throw IOException("MIDI timing division is too small.")
        }

        val monsterDatabase = loadMonsterDatabase(context)

        val xml = buildXml(
            midi = midi,
            monsters = monsterDatabase,
            noteDivision = division
        )

        val xmlBytes = xml.toByteArray(StandardCharsets.UTF_8)

        // The original Python converter compresses XML plus
        // one terminating zero byte using a raw LZ4 block.
        val input = xmlBytes + byteArrayOf(0)

        val compressor = LZ4Factory
            .fastestInstance()
            .highCompressor()

        val maximumSize = compressor.maxCompressedLength(input.size)
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

    private fun loadMonsterDatabase(
        context: Context
    ): List<Monster> {

        val jsonText = context.assets.open("mdata.json")
            .bufferedReader()
            .use { it.readText() }

        val json = JSONObject(jsonText)
        val monsters = mutableListOf<Monster>()

        val keys = json.keys()

        while (keys.hasNext()) {
            val idString = keys.next()
            val entry = json.getJSONObject(idString)

            monsters.add(
                Monster(
                    id = idString.toInt(),
                    name = entry.getString("name"),
                    x = entry.getInt("pos_x"),
                    y = entry.getInt("pos_y"),
                    flip = entry.getInt("flip"),
                    program = entry.getInt("midi_program"),
                    bank = entry.getInt("midi_bank_select_msb")
                )
            )
        }

        return monsters
    }

    private fun buildXml(
        midi: ParsedMidi,
        monsters: List<Monster>,
        noteDivision: Int
    ): String {

        val output = StringBuilder()

        output.append("<?xml version=\"1.0\"?>\n")
        output.append("<SFSOBJECT key=\"root\">\n")

        output.append("\t<SFSARRAY key=\"islands\">\n")
        output.append("\t\t<SFSOBJECT key=\"\">\n")

        output.append("\t\t\t<INT key=\"island\" value=\"11\"/>\n")
        output.append("\t\t\t<SFSARRAY key=\"monsters\">\n")

        val assignments = StringBuilder()
        val trackXml = StringBuilder()

        for ((index, track) in midi.tracks.withIndex()) {

            val monster = monsters.firstOrNull {
                it.program == track.program &&
                it.bank == track.bank
            } ?: throw IOException(
                "No monster mapping found for MIDI program " +
                "${track.program}, bank ${track.bank}. " +
                "Check your instrument selection."
            )

            val monsterId = monster.id
            val userMonsterId = index + 1
            val userTrackId = index + 2

            val x = track.posX ?: monster.x
            val y = track.posY ?: monster.y
            val flip = track.flip ?: monster.flip

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
                "\t\t\t\t\t<LONG key=\"user_monster_id\" value=\"$userMonsterId 0\"/>\n"
            )
            output.append(
                "\t\t\t\t\t<DOUBLE key=\"volume\" value=\"${formatVolume(track.volume)}\"/>\n"
            )
            output.append("\t\t\t\t</SFSOBJECT>\n")

            assignments.append("\t\t\t\t<SFSOBJECT key=\"\">\n")
            assignments.append(
                "\t\t\t\t\t<LONG key=\"monster\" value=\"$userMonsterId 0\"/>\n"
            )
            assignments.append(
                "\t\t\t\t\t<LONG key=\"track\" value=\"$userTrackId 0\"/>\n"
            )
            assignments.append("\t\t\t\t</SFSOBJECT>\n")

            val convertedNotes = mutableListOf<Int>()
            val noteLengths = mutableListOf<Int>()
            val noteTimes = mutableListOf<Int>()

            for (note in track.notes) {

                var pitch = note.pitch

                pitch = when {
                    pitch in 36..57 -> pitch + 24
                    pitch in 58..84 -> pitch
                    pitch in 85..96 -> pitch - 12
                    else -> throw IOException(
                        "MIDI note $pitch is outside the supported range."
                    )
                }

                val msmNote = noteMap[pitch]
                    ?: throw IOException(
                        "Could not convert MIDI pitch $pitch."
                    )

                val lengthTicks = note.endTick - note.startTick

                if (lengthTicks <= 0) {
                    throw IOException(
                        "A note in track ${index + 1} has zero length."
                    )
                }

                val start = (note.startTick / noteDivision).toInt()
                val length = (lengthTicks / noteDivision).toInt()

                if (length <= 0) {
                    throw IOException(
                        "A note in track ${index + 1} is shorter than " +
                        "the selected minimum note length. " +
                        "Try 1/16 or 1/32."
                    )
                }

                convertedNotes.add(msmNote)
                noteTimes.add(start)
                noteLengths.add(length)
            }

            trackXml.append("\t\t<SFSOBJECT key=\"\">\n")
            trackXml.append(
                "\t\t\t<INT key=\"format\" value=\"2 0\"/>\n"
            )

            trackXml.append("\t\t\t<INTARRAY key=\"lengths\">")
            trackXml.append(noteLengths.joinToString("") { "$it," })
            trackXml.append("</INTARRAY>\n")

            trackXml.append(
                "\t\t\t<STRING key=\"name\" value=\"\"/>\n"
            )

            trackXml.append("\t\t\t<INTARRAY key=\"notes\">")
            trackXml.append(convertedNotes.joinToString("") { "$it," })
            trackXml.append("</INTARRAY>\n")

            trackXml.append("\t\t\t<INTARRAY key=\"times\">")
            trackXml.append(noteTimes.joinToString("") { "$it," })
            trackXml.append("</INTARRAY>\n")

            trackXml.append(
                "\t\t\t<LONG key=\"user\" value=\"1 0\"/>\n"
            )
            trackXml.append(
                "\t\t\t<LONG key=\"user_track_id\" value=\"$userTrackId 0\"/>\n"
            )

            trackXml.append("\t\t</SFSOBJECT>\n")
        }

        output.append("\t\t\t</SFSARRAY>\n")

        output.append(
            "\t\t\t<STRING key=\"name\" value=\"${escapeXml(midi.songName)}\"/>\n"
        )

        output.append("\t\t\t<SFSOBJECT key=\"song\">\n")
        output.append(
            "\t\t\t\t<INT key=\"key_sig\" value=\"0\"/>\n"
        )
        output.append(
            "\t\t\t\t<INT key=\"tempo\" value=\"${midi.tempo}\"/>\n"
        )
        output.append(
            "\t\t\t\t<INT key=\"time_denom\" value=\"${midi.timeDenominator}\"/>\n"
        )
        output.append(
            "\t\t\t\t<INT key=\"time_numerator\" value=\"${midi.timeNumerator}\"/>\n"
        )

        output.append("\t\t\t\t<SFSARRAY key=\"tracks\">\n")
        output.append(assignments)
        output.append("\t\t\t\t</SFSARRAY>\n")

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

        output.append("\t<SFSARRAY key=\"tracks\">\n")
        output.append(trackXml)
        output.append("\t</SFSARRAY>\n")

        output.append(
            "\t<INT key=\"versionCode\" value=\"3\"/>\n"
        )

        output.append("</SFSOBJECT>")

        return output.toString()
    }

    private fun formatVolume(value: Float): String {
        return String.format(
            java.util.Locale.US,
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

