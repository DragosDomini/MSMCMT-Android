
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
 * Supports:
 * - Exact MIDI program and bank matching.
 * - Bank-zero fallback matching.
 * - Multiple monster recommendations.
 * - Best-match selection.
 * - Manual monster lookup and searching.
 * - Unknown MIDI instruments without Tweedle fallback.
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

    enum class MatchType {
        EXACT,
        BANK_FALLBACK,
        NONE
    }

    data class MonsterSuggestion(
        val monster: Monster,
        val matchType: MatchType,
        val score: Int,
        val reason: String
    )

    private var monsters: List<Monster> = emptyList()
    private var loaded = false

    // --------------------------------------------------
    // DATABASE LOADING
    // --------------------------------------------------

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

        monsters = loadedMonsters
            .sortedBy { it.id }

        loaded = true
    }

    // --------------------------------------------------
    // BANK NORMALIZATION
    // --------------------------------------------------

    /**
     * MidiReader stores the complete 14-bit bank value:
     *
     * (MSB shl 7) or LSB
     *
     * mdata.json stores only the MSB.
     *
     * This function converts the complete bank value
     * into the format used by the monster database.
     */
    private fun normalizeBank(bank: Int): Int? {

        if (bank !in 0..16383) {
            return null
        }

        return if (bank > 127) {
            bank ushr 7
        } else {
            bank
        }
    }

    // --------------------------------------------------
    // MONSTER DATABASE ACCESS
    // --------------------------------------------------

    fun getAllMonsters(
        context: Context
    ): List<Monster> {

        load(context)

        return monsters.toList()
    }

    fun getMonsterCount(
        context: Context
    ): Int {

        load(context)

        return monsters.size
    }

    // --------------------------------------------------
    // MATCH TYPE
    // --------------------------------------------------

    fun getMatchType(
        context: Context,
        program: Int,
        bank: Int,
        monster: Monster
    ): MatchType {

        load(context)

        if (program !in 0..127) {
            return MatchType.NONE
        }

        val normalizedBank = normalizeBank(bank)
            ?: return MatchType.NONE

        if (monster.midiProgram != program) {
            return MatchType.NONE
        }

        if (monster.midiBank == normalizedBank) {
            return MatchType.EXACT
        }

        if (monster.midiBank == 0) {
            return MatchType.BANK_FALLBACK
        }

        return MatchType.NONE
    }

    // --------------------------------------------------
    // BEST MATCH
    // --------------------------------------------------

    /**
     * Automatically finds the best matching monster.
     *
     * Exact program and bank matches are preferred.
     *
     * Bank-zero alternatives are used only when no
     * exact match exists.
     *
     * Returns null when no valid recommendation exists.
     */
    fun findBestMonster(
        context: Context,
        program: Int,
        bank: Int
    ): Monster? {

        val suggestions = findMonsterSuggestions(
            context,
            program,
            bank
        )

        return suggestions.firstOrNull()
    }

    // --------------------------------------------------
    // MATCH SUGGESTIONS
    // --------------------------------------------------

    /**
     * Finds all recommended monsters for a MIDI instrument.
     *
     * Matching priority:
     *
     * 1. Exact MIDI program and bank.
     * 2. Matching MIDI program with bank zero.
     * 3. No match.
     *
     * Unknown programs are never automatically matched.
     */
    fun findMonsterSuggestions(
        context: Context,
        program: Int,
        bank: Int
    ): List<Monster> {

        load(context)

        if (program !in 0..127) {
            return emptyList()
        }

        val normalizedBank = normalizeBank(bank)
            ?: return emptyList()

        // Exact matches always receive priority.

        val exactMatches = monsters.filter {
            it.midiProgram == program &&
            it.midiBank == normalizedBank
        }

        if (exactMatches.isNotEmpty()) {
            return exactMatches.sortedBy { it.id }
        }

        // Offer bank-zero alternatives when an exact
        // program and bank combination cannot be found.

        if (normalizedBank != 0) {

            return monsters.filter {
                it.midiProgram == program &&
                it.midiBank == 0
            }.sortedBy { it.id }
        }

        return emptyList()
    }

    // --------------------------------------------------
    // DETAILED SUGGESTIONS
    // --------------------------------------------------

    /**
     * Returns recommendations with match explanations.
     *
     * Useful for displaying why a particular monster
     * was recommended in MainActivity.
     */
    fun getDetailedSuggestions(
        context: Context,
        program: Int,
        bank: Int
    ): List<MonsterSuggestion> {

        load(context)

        if (program !in 0..127) {
            return emptyList()
        }

        val normalizedBank = normalizeBank(bank)
            ?: return emptyList()

        val exactMatches = monsters.filter {
            it.midiProgram == program &&
            it.midiBank == normalizedBank
        }

        if (exactMatches.isNotEmpty()) {

            return exactMatches
                .sortedBy { it.id }
                .map { monster ->

                    MonsterSuggestion(
                        monster = monster,
                        matchType = MatchType.EXACT,
                        score = 100,
                        reason = "Exact MIDI program and bank match"
                    )
                }
        }

        if (normalizedBank != 0) {

            return monsters.filter {
                it.midiProgram == program &&
                it.midiBank == 0
            }
                .sortedBy { it.id }
                .map { monster ->

                    MonsterSuggestion(
                        monster = monster,
                        matchType = MatchType.BANK_FALLBACK,
                        score = 75,
                        reason = "Matching MIDI program; bank-zero alternative"
                    )
                }
        }

        return emptyList()
    }

    // --------------------------------------------------
    // SINGLE MONSTER LOOKUP
    // --------------------------------------------------

    /**
     * Finds a monster using an exact MIDI program and bank.
     *
     * If no exact match exists, a bank-zero alternative
     * may be returned.
     */
    fun findMonster(
        context: Context,
        program: Int,
        bank: Int
    ): Monster? {

        return findBestMonster(
            context,
            program,
            bank
        )
    }

    // --------------------------------------------------
    // MONSTER ID LOOKUP
    // --------------------------------------------------

    fun findMonsterById(
        context: Context,
        monsterId: Int
    ): Monster? {

        load(context)

        return monsters.firstOrNull {
            it.id == monsterId
        }
    }

    // --------------------------------------------------
    // MONSTER NAME SEARCH
    // --------------------------------------------------

    fun searchMonsters(
        context: Context,
        query: String
    ): List<Monster> {

        load(context)

        if (query.isBlank()) {
            return emptyList()
        }

        val searchTerm = query.trim()

        return monsters.filter {
            it.name.contains(
                searchTerm,
                ignoreCase = true
            )
        }
    }

    // --------------------------------------------------
    // MONSTER NAME LOOKUP
    // --------------------------------------------------

    fun findMonsterByName(
        context: Context,
        name: String
    ): Monster? {

        load(context)

        if (name.isBlank()) {
            return null
        }

        return monsters.firstOrNull {
            it.name.equals(
                name.trim(),
                ignoreCase = true
            )
        }
    }

    // --------------------------------------------------
    // REQUIRED MONSTER
    // --------------------------------------------------

    /**
     * Resolves an instrument or returns a useful error.
     *
     * Does not invent a monster when no mapping exists.
     */
    @Throws(IOException::class)
    fun requireMonster(
        context: Context,
        program: Int,
        bank: Int
    ): Monster {

        return findBestMonster(
            context,
            program,
            bank
        ) ?: throw IOException(
            if (program == -1) {
                "No MIDI instrument was specified for this track. " +
                    "Please select a monster manually."
            } else {
                "No monster mapping found for MIDI program " +
                    "$program, bank $bank. " +
                    "Please check your instrument selection."
            }
        )
    }

    // --------------------------------------------------
    // MANUAL SELECTION VALIDATION
    // --------------------------------------------------

    /**
     * Checks whether a manually selected monster exists.
     */
    fun isValidMonster(
        context: Context,
        monsterId: Int
    ): Boolean {

        return findMonsterById(
            context,
            monsterId
        ) != null
    }

    // --------------------------------------------------
    // CACHE MANAGEMENT
    // --------------------------------------------------

    /**
     * Clears the cached database.
     *
     * Useful when testing updated mdata.json files.
     */
    @Synchronized
    fun clearCache() {

        monsters = emptyList()

        loaded = false
    }
}
