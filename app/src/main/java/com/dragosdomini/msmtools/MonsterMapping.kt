
package com.dragosdomini.msmtools

import android.app.AlertDialog
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

    /**
     * Loads all monsters from mdata.json.
     */
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

        return result.sortedBy { it.name.lowercase() }
    }

    /**
     * Finds monsters matching a MIDI instrument.
     */
    fun findExactMatches(
        program: Int,
        bank: Int,
        monsters: List<MonsterOption>
    ): List<MonsterOption> {

        return monsters.filter {
            it.program == program &&
            it.bank == bank
        }
    }

    /**
     * Finds monsters with a matching instrument program,
     * even if the bank differs.
     */
    fun findProgramMatches(
        program: Int,
        monsters: List<MonsterOption>
    ): List<MonsterOption> {

        return monsters.filter {
            it.program == program
        }
    }

    /**
     * Provides an automatic suggestion.
     *
     * Exact program and bank matches take priority.
     * Program-only matches are used next.
     */
    fun suggest(
        program: Int,
        bank: Int,
        monsters: List<MonsterOption>
    ): MonsterOption? {

        val exact = findExactMatches(
            program,
            bank,
            monsters
        )

        if (exact.isNotEmpty()) {
            return exact.first()
        }

        val programMatches = findProgramMatches(
            program,
            monsters
        )

        if (programMatches.isNotEmpty()) {
            return programMatches.first()
        }

        // No exact mapping exists.
        // Leave the choice to the user rather than
        // silently assigning an unrelated monster.

        return null
    }

    /**
     * Creates one mapping entry for every MIDI track.
     */
    fun createMappings(
        context: Context,
        midi: ParsedMidi
    ): List<InstrumentMapping> {

        val monsters = load(context)

        return midi.tracks.mapIndexed { index, track ->

            InstrumentMapping(
                trackIndex = index,
                instrumentName = track.name,
                program = track.program,
                bank = track.bank,

                suggestedMonster = suggest(
                    track.program,
                    track.bank,
                    monsters
                ),

                availableMonsters = monsters
            )
        }
    }

    /**
     * Displays a monster selection dialog for every
     * MIDI instrument track.
     *
     * Returns:
     * Map<MIDI track index, Monster ID>
     */
    fun selectMonsters(
        context: Context,
        midi: ParsedMidi,
        onComplete: (Map<Int, Int>) -> Unit
    ) {

        val mappings = try {
            createMappings(context, midi)
        } catch (e: Exception) {

            AlertDialog.Builder(context)
                .setTitle("Monster Database Error")
                .setMessage(
                    e.message ?: "Could not load monster database."
                )
                .setPositiveButton("OK", null)
                .show()

            return
        }

        if (mappings.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("No Instrument Tracks")
                .setMessage("No MIDI instrument tracks were found.")
                .setPositiveButton("OK", null)
                .show()

            return
        }

        val assignments = mutableMapOf<Int, Int>()

        fun showTrackSelection(index: Int) {

            if (index >= mappings.size) {

                onComplete(assignments.toMap())
                return
            }

            val mapping = mappings[index]

            val monsters = mapping.availableMonsters

            val suggested = mapping.suggestedMonster

            val selectedIndex = monsters.indexOfFirst {
                it.id == suggested?.id
            }

            val labels = monsters.map { monster ->

                val exact = monster.program == mapping.program &&
                    monster.bank == mapping.bank

                val programMatch =
                    monster.program == mapping.program

                when {
                    exact -> "${monster.name}  ★ Exact match"
                    programMatch -> "${monster.name}  ★ Instrument match"
                    else -> monster.name
                }
            }.toTypedArray()

            val title =
                "Track ${index + 1} of ${mappings.size}"

            val description =
                "${mapping.instrumentName}\n\n" +
                "MIDI Program: ${mapping.program}\n" +
                "MIDI Bank: ${mapping.bank}\n\n" +
                "Choose the monster that should perform this part."

            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(description)
                .setSingleChoiceItems(
                    labels,
                    selectedIndex
                ) { dialog, which ->

                    val chosen = monsters[which]

                    assignments[mapping.trackIndex] = chosen.id

                    dialog.dismiss()

                    showTrackSelection(index + 1)
                }
                .setNeutralButton(
                    "Use Suggested"
                ) { dialog, _ ->

                    if (suggested != null) {

                        assignments[mapping.trackIndex] =
                            suggested.id

                        dialog.dismiss()

                        showTrackSelection(index + 1)

                    } else {

                        dialog.dismiss()

                        AlertDialog.Builder(context)
                            .setTitle("No Automatic Match")
                            .setMessage(
                                "Please select a monster manually " +
                                "for ${mapping.instrumentName}."
                            )
                            .setPositiveButton("Choose Monster") {
                                _, _ ->
                                showTrackSelection(index)
                            }
                            .show()
                    }
                }
                .setNegativeButton("Cancel") { dialog, _ ->

                    dialog.dismiss()
                }
                .show()
        }

        showTrackSelection(0)
    }
}
