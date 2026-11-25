
package com.dragosdomini.msmtools

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * MSM Monster Mapping
 *
 * Loads the existing mdata.json database.
 * Supports MIDI program/bank matching, ranked suggestions,
 * manual monster selection and database validation.
 *
 * IMPORTANT:
 * The current database does not contain verified pitch ranges.
 * This class does not invent them.
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
        SAME_PROGRAM_OTHER_BANK,
        NONE
    }

    data class MonsterSuggestion(
        val monster: Monster,
        val matchType: MatchType,
        val score: Int,
        val reason: String
    )

    private val lock = Any()

    @Volatile
    private var monsters: List<Monster> = emptyList()

    @Volatile
    private var loaded = false

    /**
     * Load mdata.json from Android assets.
     */
    @Throws(IOException::class)
    fun load(context: Context) {

        if (loaded) return

        synchronized(lock) {

            if (loaded) return

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
                    "Invalid mdata.json: ${e.message}",
                    e
                )
            }

            val parsed = mutableListOf<Monster>()

            val keys = json.keys()

            while (keys.hasNext()) {

                val key = keys.next()

                val id = key.toIntOrNull() ?: continue

                val entry = json.optJSONObject(key) ?: continue

                val name = entry
                    .optString("name", "")
                    .trim()

                if (name.isEmpty()) continue

                val program = entry.optInt(
                    "midi_program",
                    -1
                )

                val bank = entry.optInt(
                    "midi_bank_select_msb",
                    0
                )

                if (program !in -1..127) continue
                if (bank !in 0..127) continue

                parsed.add(
                    Monster(
                        id = id,
                        name = name,
                        posX = entry.optInt("pos_x", 0),
                        posY = entry.optInt("pos_y", 0),
                        flip = entry.optInt("flip", 0),
                        midiProgram = program,
                        midiBank = bank
                    )
                )
            }

            if (parsed.isEmpty()) {

                throw IOException(
                    "No valid monsters found in mdata.json."
                )
            }

            val duplicateIds = parsed
                .groupBy { it.id }
                .filterValues { it.size > 1 }
                .keys

            if (duplicateIds.isNotEmpty()) {

                throw IOException(
                    "Duplicate monster IDs: ${duplicateIds.joinToString()}"
                )
            }

            monsters = parsed.sortedBy { it.id }

            loaded = true
        }
    }

    /**
     * Normalize MIDI bank representation.
     *
     * Accepts either an MSB value (0-127)
     * or a combined MIDI bank value (0-16383).
     */
    private fun normalizeBank(bank: Int): Int? {

        if (bank !in 0..16383) return null

        return if (bank > 127) {
            bank ushr 7
        } else {
            bank
        }
    }

    /**
     * Retrieve all monsters.
     */
    fun getAllMonsters(
        context: Context
    ): List<Monster> {

        load(context)

        return monsters.toList()
    }

    /**
     * Retrieve monster count.
     */
    fun getMonsterCount(
        context: Context
    ): Int {

        load(context)

        return monsters.size
    }

    /**
     * Find monster by ID.
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
     * Find monster by name.
     */
    fun findMonsterByName(
        context: Context,
        name: String
    ): Monster? {

        load(context)

        if (name.isBlank()) return null

        return monsters.firstOrNull {
            it.name.equals(
                name.trim(),
                ignoreCase = true
            )
        }
    }

    /**
     * Search monster names.
     */
    fun searchMonsters(
        context: Context,
        query: String
    ): List<Monster> {

        load(context)

        if (query.isBlank()) return emptyList()

        return monsters.filter {
            it.name.contains(
                query.trim(),
                ignoreCase = true
            )
        }
    }

    /**
     * Check whether a monster ID exists.
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

    /**
     * Determine MIDI mapping compatibility.
     */
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

        return when {

            monster.midiBank == normalizedBank ->
                MatchType.EXACT

            monster.midiBank == 0 ->
                MatchType.BANK_FALLBACK

            else ->
                MatchType.NONE
        }
    }

    /**
     * Generate ranked monster suggestions.
     *
     * Exact matches are preferred.
     * Bank-zero fallback matches are secondary.
     * Other-bank matches are manual alternatives only.
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

        val sameProgram = monsters.filter {
            it.midiProgram == program
        }

        if (sameProgram.isEmpty()) {
            return emptyList()
        }

        val exact = sameProgram.filter {
            it.midiBank == normalizedBank
        }

        val fallback = sameProgram.filter {
            normalizedBank != 0 &&
                it.midiBank == 0
        }

        val otherBanks = sameProgram.filter {
            it.midiBank != normalizedBank &&
                it.midiBank != 0
        }

        val suggestions = mutableListOf<MonsterSuggestion>()

        exact.forEach {

            suggestions.add(
                MonsterSuggestion(
                    monster = it,
                    matchType = MatchType.EXACT,
                    score = 100,
                    reason = "Exact MIDI program and bank match."
                )
            )
        }

        fallback.forEach {

            suggestions.add(
                MonsterSuggestion(
                    monster = it,
                    matchType = MatchType.BANK_FALLBACK,
                    score = 75,
                    reason = "MIDI program matches. Database bank is 0."
                )
            )
        }

        otherBanks.forEach {

            suggestions.add(
                MonsterSuggestion(
                    monster = it,
                    matchType = MatchType.SAME_PROGRAM_OTHER_BANK,
                    score = 40,
                    reason = "MIDI program matches, but bank differs. Manual review recommended."
                )
            )
        }

        return suggestions.sortedWith(
            compareByDescending<MonsterSuggestion> {
                it.score
            }.thenBy {
                it.monster.name.lowercase(Locale.ROOT)
            }.thenBy {
                it.monster.id
            }
        )
    }

    /**
     * Backward-compatible suggestion method.
     *
     * Does not return unrelated monsters.
     * Does not default to Tweedle.
     */
    fun findMonsterSuggestions(
        context: Context,
        program: Int,
        bank: Int
    ): List<Monster> {

        return getDetailedSuggestions(
            context,
            program,
            bank
        )
            .filter {
                it.matchType == MatchType.EXACT ||
                    it.matchType == MatchType.BANK_FALLBACK
            }
            .map {
                it.monster
            }
    }

    /**
     * Find the best available instrument mapping.
     */
    fun findBestMonster(
        context: Context,
        program: Int,
        bank: Int
    ): Monster? {

        return findMonsterSuggestions(
            context,
            program,
            bank
        ).firstOrNull()
    }

    /**
     * Legacy method compatibility.
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

    /**
     * Require a valid mapping.
     *
     * Throws an informative exception instead
     * of silently selecting an unrelated monster.
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

                "No MIDI instrument program was detected. Please select a monster manually."

            } else {

                "No monster mapping found for MIDI program $program, bank $bank. Please select a monster manually."
            }
        )
    }

    /**
     * Return every monster for manual selection.
     */
    fun getManualSelectionOptions(
        context: Context
    ): List<Monster> {

        load(context)

        return monsters.sortedBy {
            it.name.lowercase(Locale.ROOT)
        }
    }

    /**
     * Legacy aliases.
     */
    fun getMonsterOptions(
        context: Context
    ): List<Monster> {

        return getManualSelectionOptions(context)
    }

    fun getMonsterSuggestions(
        context: Context,
        program: Int,
        bank: Int
    ): List<Monster> {

        return findMonsterSuggestions(
            context,
            program,
            bank
        )
    }

    /**
     * Clear cached database.
     */
    fun clearCache() {

        synchronized(lock) {

            monsters = emptyList()

            loaded = false
        }
    }
}
