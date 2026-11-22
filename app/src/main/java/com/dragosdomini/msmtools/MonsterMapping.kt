
package com.dragosdomini.msmtools

import android.content.Context
import org.json.JSONObject

data class MonsterOption(
    val id: Int,
    val name: String,
    val posX: Int,
    val posY: Int,
    val flip: Int,
    val program: Int,
    val bank: Int
)

data class InstrumentMapping(
    val trackIndex: Int,
    val instrumentName: String,
    val program: Int,
    val bank: Int,
    val suggestedMonster: MonsterOption?,
    val availableMonsters: List<MonsterOption>
)

object MonsterMapping {

    fun load(context: Context): List<MonsterOption> {
        val jsonText = context.assets.open("mdata.json")
            .bufferedReader()
            .use { it.readText() }

        val json = JSONObject(jsonText)
        val result = mutableListOf<MonsterOption>()
        val keys = json.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val entry = json.getJSONObject(key)

            result.add(
                MonsterOption(
                    id = key.toInt(),
                    name = entry.getString("name"),
                    posX = entry.getInt("pos_x"),
                    posY = entry.getInt("pos_y"),
                    flip = entry.getInt("flip"),
                    program = entry.getInt("midi_program"),
                    bank = entry.getInt("midi_bank_select_msb")
                )
            )
        }

        return result.sortedBy { it.name }
    }

    fun suggest(
        program: Int,
        monsters: List<MonsterOption>
    ): MonsterOption? {

        val preferredNames = when (program) {

            in 24..31 -> listOf(
                "Riff", "Humbug", "Arackulele"
            )

            in 32..39 -> listOf(
                "Rare Riff", "Riff", "Rare Drumpler"
            )

            in 40..47 -> listOf(
                "Bowgart", "Rare Bowgart"
            )

            in 48..55 -> listOf(
                "Dandidoo", "Mammott", "Toe Jammer"
            )

            in 56..63 -> listOf(
                "Mammott", "Tweedle", "Quibble"
            )

            in 64..79 -> listOf(
                "Reedling", "Spunge", "Tweedle"
            )

            in 80..95 -> listOf(
                "Cybop", "Dipsters", "Furcorn"
            )

            else -> listOf(
                "Mammott", "Tweedle", "Riff"
            )
        }

        return preferredNames.firstNotNullOfOrNull { name ->
            monsters.firstOrNull {
                it.name.equals(name, ignoreCase = true)
            }
        }
    }

    fun createMappings(
        context: Context,
        midi: ParsedMidi
    ): List<InstrumentMapping> {

        val monsters = load(context)

        return midi.tracks.mapIndexed { index, track ->

            val exact = monsters.firstOrNull {
                it.program == track.program &&
                it.bank == track.bank
            }

            InstrumentMapping(
                trackIndex = index,
                instrumentName = track.name,
                program = track.program,
                bank = track.bank,
                suggestedMonster = exact
                    ?: suggest(track.program, monsters),
                availableMonsters = monsters
            )
        }
    }
}
