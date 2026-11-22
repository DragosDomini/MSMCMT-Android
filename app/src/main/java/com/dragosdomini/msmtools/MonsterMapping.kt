
package com.dragosdomini.msmtools

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/**
 * MonsterMapping
 *
 * Loads monster information from mdata.json and provides
 * MIDI instrument-to-monster matching.
 *
 * Uses the existing MSMCMT-Android monster database.
 */
object MonsterMapping {

    data class Monster(
        val id: Int,
        val name: String,
        val posX: Int,
        val posY: Int,
        val flip: Int,
        val midiProgram: Int,
        val midiBank: Int
    )

    private var monsters: List<Monster> = emptyList()
    private var loaded = false

    /**
     * Load the existing monster database.
     */
    @Synchronized
    fun load(context: Context) {

        if (loaded) return

        val jsonText = try {
            context.assets.open("mdata.json")
                .bufferedReader()
                .use { it.readText() }
        } catch (e: Exception) {
            throw IOException(
                "Could not load mdata.json: ${e.message}"
            )
        }

        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            throw IOException(
                "Monster database contains invalid JSON."
            )
        }

        val loadedMonsters = mutableListOf<Monster>()

        val keys = json.keys()

        while (keys.hasNext()) {

            val idString = keys.next()

            val entry = json.optJSONObject(idString)
                ?: continue

            val id = idString.toIntOrNull()
                ?: continue

            val name = entry.optString("name", "")
            if (name.isBlank()) continue

            loadedMonsters.add(
                Monster(
                    id = id,
                    name = name,
                    posX = entry.optInt("pos_x", 0),
                    posY = entry.optInt("pos_y", 0),
                    flip = entry.optInt("flip", 0),
                    midiProgram = entry.optInt(
                        "midi_program",
                        -1
                    ),
                    midiBank = entry.optInt(
                        "midi_bank_select_msb",
                        0
                    )
                )
            )
        }

        if (loadedMonsters.isEmpty()) {
            throw IOException(
                "Monster database contains no valid monsters."
            )
        }

        monsters = loadedMonsters.sortedBy { it.id }
        loaded = true
    }

    /**
     * Return all available monsters.
     */
    fun getAllMonsters(context: Context): List<Monster> {
        load(context)
        return monsters.toList()
    }

    /**
     * Find a monster using an exact MIDI program and bank.
     *
     * Bank matching is attempted first because different
     * monsters can share the same MIDI program.
     */
    fun findMonster(
        context: Context,
        program: Int,
        bank: Int
    ): Monster? {

        load(context)

        if (program !in 0..127 || bank !in 0..127) {
            return null
        }

        // First: exact program and bank.
        val exactMatch = monsters.firstOrNull {
            it.midiProgram == program &&
            it.midiBank == bank
        }

        if (exactMatch != null) {
            return exactMatch
        }

        // Second: allow bank 0 as a fallback.
        // This helps MIDI files that omit bank selection.
        if (bank != 0) {

            val defaultBankMatch = monsters.firstOrNull {
                it.midiProgram == program &&
                it.midiBank == 0
            }

            if (defaultBankMatch != null) {
                return defaultBankMatch
            }
        }

        return null
    }

    /**
     * Find all monsters matching a MIDI instrument.
     *
     * Useful for the monster suggestion interface,
     * where the user can choose between alternatives.
     */
    fun findMonsterSuggestions(
        context: Context,
        program: Int,
        bank: Int
    ): List<Monster> {

        load(context)

        if (program !in 0..127 || bank !in 0..127) {
            return emptyList()
        }

        val exactMatches = monsters.filter {
            it.midiProgram == program &&
            it.midiBank == bank
        }

        if (exactMatches.isNotEmpty()) {
            return exactMatches
        }

        return monsters.filter {
            it.midiProgram == program &&
            it.midiBank == 0
        }
    }

    /**
     * Find a monster by its internal MSM ID.
     */
    fun findMonsterById(
        context: Context,
        monsterId: Int
    ): Monster? {

        load(context)

        return monsters.firstOrNull {
            it.id == monsterId
        }
    }

    /**
     * Search monsters by name.
     */
    fun searchMonsters(
        context: Context,
        query: String
    ): List<Monster> {

        load(context)

        if (query.isBlank()) {
            return emptyList()
        }

        return monsters.filter {
            it.name.contains(
                query.trim(),
                ignoreCase = true
            )
        }
    }

    /**
     * Resolve an instrument or return a useful error.
     */
    @Throws(IOException::class)
    fun requireMonster(
        context: Context,
        program: Int,
        bank: Int
    ): Monster {

        return findMonster(
            context,
            program,
            bank
        ) ?: throw IOException(
            "No monster mapping found for MIDI program " +
            "$program, bank $bank. Check your instrument selection."
        )
    }

    /**
     * Get the number of available monster mappings.
     */
    fun getMonsterCount(context: Context): Int {
        load(context)
        return monsters.size
    }

    /**
     * Reset the database cache.
     *
     * Useful when testing updated mdata.json files.
     */
    @Synchronized
    fun clearCache() {
        monsters = emptyList()
        loaded = false
    }
}
