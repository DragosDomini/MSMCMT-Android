
package com.cyndyrfyrestudios.msmtools

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MSMConverter"
    }

    private var parsedMidi: ParsedMidi? = null
    private var midiUri: Uri? = null

    // Each MIDI part receives its own monster assignment.
    private val assignments = mutableMapOf<Int, String>()

    private val selectedResults =
        mutableMapOf<Int, MonsterCompatibility>()

    private lateinit var statusText: TextView
    private lateinit var songInfoText: TextView
    private lateinit var trackContainer: LinearLayout
    private lateinit var content: LinearLayout

    private val backgroundColor = Color.rgb(29, 40, 43)
    private val cardColor = Color.rgb(47, 64, 66)
    private val accentColor = Color.rgb(116, 207, 173)
    private val textColor = Color.WHITE
    private val mutedColor = Color.rgb(185, 201, 198)

    private val midiPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {
                midiUri = uri
                importMidi(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildInterface()
        loadDatabase()
    }

    // --------------------------------------------------
    // MAIN INTERFACE
    // --------------------------------------------------

    private fun buildInterface() {

        val scroll = ScrollView(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 20, 18, 30)
            setBackgroundColor(backgroundColor)
        }

        val header = TextView(this).apply {
            text = "MSM CONVERTER"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 6)
        }

        val subtitle = TextView(this).apply {
            text = "MIDI to Monster Composition"
            textSize = 14f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        content.addView(header)
        content.addView(subtitle)

        content.addView(section("COMPOSITION"))

        content.addView(
            createButton("Import MIDI composition") {
                midiPicker.launch(
                    arrayOf(
                        "audio/midi",
                        "audio/x-midi",
                        "application/octet-stream"
                    )
                )
            }
        )

        statusText = createCardText(
            "Waiting for a MIDI composition."
        )

        content.addView(statusText)

        songInfoText = createCardText(
            "Song information will appear here."
        )

        content.addView(songInfoText)

        content.addView(section("MONSTER ASSIGNMENTS"))

        val explanation = TextView(this).apply {
            text =
                "Each MIDI part is analyzed independently. " +
                "If its assigned monster cannot play the entire " +
                "part, alternative monsters are recommended."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(4, 4, 4, 14)
        }

        content.addView(explanation)

        trackContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(trackContainer)

        content.addView(section("CONVERSION"))

        content.addView(
            createButton("Validate all assignments") {
                validateAllAssignments()
            }
        )

        content.addView(
            createButton("Refresh recommendations") {
                analyzeAllTracks()
            }
        )

        scroll.addView(content)
        setContentView(scroll)
    }

    private fun loadDatabase() {

        try {
            val monsters = MonsterMapping.load(this)

            if (monsters.isEmpty()) {
                throw IllegalStateException(
                    "No monsters were found in mdata.json."
                )
            }

            statusText.text =
                "Monster database ready: ${monsters.size} monsters."

        } catch (e: Exception) {

            Log.e(TAG, "Database loading failed", e)

            statusText.text =
                "Database error: ${e.message}"

            showMessage(
                "Database error",
                e.message ?: "Unable to load monster database."
            )
        }
    }

    // --------------------------------------------------
    // MIDI IMPORT
    // --------------------------------------------------

    private fun importMidi(uri: Uri) {

        try {

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Temporary access is sufficient for this import.
            }

            val bytes = contentResolver
                .openInputStream(uri)
                ?.use { it.readBytes() }
                ?: throw IllegalStateException(
                    "Unable to open MIDI file."
                )

            val result = MidiReader.read(bytes)

            if (result.tracks.isEmpty()) {
                throw IllegalArgumentException(
                    "No playable MIDI parts were found."
                )
            }

            parsedMidi = result

            // New composition means new assignments.
            assignments.clear()
            selectedResults.clear()

            statusText.text =
                "MIDI imported successfully."

            songInfoText.text = buildString {
                append("Song: ${result.songName}\n")
                append("Tempo: ${result.tempo} BPM\n")
                append(
                    "Time signature: " +
                    "${result.timeNumerator}/" +
                    "${result.timeDenominator}\n"
                )
                append("MIDI parts: ${result.tracks.size}\n")
                append("Notes: ${result.tracks.sumOf { it.notes.size }}")
            }

            analyzeAllTracks()

        } catch (e: Exception) {

            Log.e(TAG, "MIDI import failed", e)

            parsedMidi = null
            assignments.clear()
            selectedResults.clear()

            statusText.text =
                "MIDI import failed: ${e.message}"

            trackContainer.removeAllViews()

            showMessage(
                "MIDI import error",
                e.message ?: "Unable to read MIDI file."
            )
        }
    }

    // --------------------------------------------------
    // TRACK ANALYSIS
    // --------------------------------------------------

    private fun analyzeAllTracks() {

        val midi = parsedMidi ?: return

        trackContainer.removeAllViews()
        selectedResults.clear()

        midi.tracks.forEachIndexed { index, track ->

            if (track.notes.isEmpty()) return@forEachIndexed

            val pitches = track.notes.map { it.pitch }

            val assignedId = assignments[index]

            if (assignedId != null) {

                val monster = MonsterMapping.getMonster(assignedId)

                if (monster != null) {

                    val result =
                        MonsterMapping.analyzeCompatibility(
                            monster,
                            pitches
                        )

                    selectedResults[index] = result

                    // Automatically recommend alternatives
                    // when the current monster cannot play all notes.
                    if (!result.accommodatesEntireTrack) {

                        val alternatives =
                            MonsterMapping.rankAlternatives(
                                pitches,
                                assignedId
                            )

                        if (alternatives.isNotEmpty()) {
                            addTrackCard(
                                index,
                                track,
                                result,
                                alternatives
                            )
                            return@forEachIndexed
                        }
                    }

                    addTrackCard(
                        index,
                        track,
                        result,
                        emptyList()
                    )

                    return@forEachIndexed
                }
            }

            // No assignment yet: automatically find the best match.
            val alternatives =
                MonsterMapping.rankAlternatives(pitches)

            if (alternatives.isEmpty()) {

                addNoMatchCard(index, track.name)

            } else {

                val best = alternatives.first()

                addTrackCard(
                    index,
                    track,
                    best,
                    alternatives.drop(1),
                    awaitingInitialApproval = true
                )
            }
        }

        if (trackContainer.childCount == 0) {
            trackContainer.addView(
                createCardText(
                    "No pitched notes were found in the MIDI parts."
                )
            )
        }
    }

    // --------------------------------------------------
    // TRACK CARDS
    // --------------------------------------------------

    private fun addTrackCard(
        index: Int,
        track: MidiTrackData,
        result: MonsterCompatibility,
        alternatives: List<MonsterCompatibility>,
        awaitingInitialApproval: Boolean = false
    ) {

        val card = createCard()

        card.addView(
            cardHeading(
                "PART ${index + 1}: ${track.name}"
            )
        )

        card.addView(
            detailText(
                "Notes: ${track.notes.size}\n" +
                "Instrument program: ${track.program}\n" +
                "Selected monster: ${result.monster.name}"
            )
        )

        card.addView(
            compatibilityIndicator(result)
        )

        if (awaitingInitialApproval) {

            card.addView(
                detailText(
                    "The app found a recommended monster. " +
                    "Approve it to create this assignment."
                )
            )

            card.addView(
                createButton("Approve recommended monster") {
                    requestAssignmentApproval(
                        index,
                        result,
                        initialAssignment = true
                    )
                }
            )

        } else {

            card.addView(
                createButton("Change monster") {
                    showMonsterPicker(index, track)
                }
            )
        }

        if (alternatives.isNotEmpty()) {

            card.addView(
                section("ALTERNATIVE MONSTERS")
            )

            alternatives.take(5).forEachIndexed { rank, alternative ->

                val alternativeCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 12, 12, 12)
                    background = rounded(
                        Color.rgb(58, 77, 78),
                        12f
                    )
                }

                val alternativeName = cardHeading(
                    "#${rank + 1} ${alternative.monster.name}"
                )

                alternativeCard.addView(alternativeName)

                alternativeCard.addView(
                    detailText(
                        "${alternative.score}% compatibility\n" +
                        "${alternative.compatibleNotes}/" +
                        "${alternative.totalNotes} notes accommodated\n" +
                        "Octave adjustment: " +
                        "${alternative.octaveShift} semitones"
                    )
                )

                alternativeCard.addView(
                    createButton("Review this monster") {
                        requestAssignmentApproval(
                            index,
                            alternative
                        )
                    }
                )

                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                params.bottomMargin = 10

                card.addView(alternativeCard, params)
            }
        }

        trackContainer.addView(card)
    }

    private fun addNoMatchCard(
        index: Int,
        name: String
    ) {

        val card = createCard()

        card.addView(
            cardHeading("PART ${index + 1}: $name")
        )

        card.addView(
            detailText(
                "No monsters with usable pitch data " +
                "were found for this part."
            )
        )

        card.addView(
            createButton("Choose monster manually") {
                parsedMidi?.tracks?.getOrNull(index)?.let {
                    showMonsterPicker(index, it)
                }
            }
        )

        trackContainer.addView(card)
    }

    // --------------------------------------------------
    // COMPATIBILITY DISPLAY
    // --------------------------------------------------

    private fun compatibilityIndicator(
        result: MonsterCompatibility
    ): View {

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            background = rounded(
                when {
                    result.accommodatesEntireTrack ->
                        Color.rgb(35, 91, 70)

                    result.score > 0 ->
                        Color.rgb(110, 79, 36)

                    else ->
                        Color.rgb(105, 47, 47)
                },
                12f
            )
        }

        val heading = TextView(this).apply {
            text = when {
                result.accommodatesEntireTrack ->
                    "FULL TRACK COMPATIBILITY"

                result.score > 0 ->
                    "PARTIAL COMPATIBILITY"

                else ->
                    "INCOMPATIBLE"
            }

            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val score = TextView(this).apply {
            text = "${result.score}%"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val details = TextView(this).apply {
            text =
                "${result.compatibleNotes}/${result.totalNotes} " +
                "notes accommodated\n" +
                "${result.incompatibleNotes} notes outside range\n" +
                "Octave adjustment: ${result.octaveShift} semitones\n\n" +
                result.explanation

            textSize = 12f
            setTextColor(Color.WHITE)
        }

        container.addView(heading)
        container.addView(score)
        container.addView(details)

        return container
    }

    // --------------------------------------------------
    // MANUAL MONSTER SELECTION
    // --------------------------------------------------

    private fun showMonsterPicker(
        trackIndex: Int,
        track: MidiTrackData
    ) {

        val monsters = MonsterMapping.getAllMonsters()

        if (monsters.isEmpty()) {
            showMessage(
                "No monsters",
                "The monster database is unavailable."
            )
            return
        }

        val pitches = track.notes.map { it.pitch }

        val ranked = monsters.map { monster ->
            MonsterMapping.analyzeCompatibility(
                monster,
                pitches
            )
        }.sortedWith(
            compareByDescending<MonsterCompatibility> {
                it.accommodatesEntireTrack
            }.thenByDescending {
                it.compatibleNotes
            }.thenByDescending {
                it.score
            }
        )

        val labels = ranked.map {
            "${it.monster.name} — ${it.score}%"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose monster for ${track.name}")
            .setItems(labels) { _, position ->

                requestAssignmentApproval(
                    trackIndex,
                    ranked[position]
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --------------------------------------------------
    // APPROVAL SYSTEM
    // --------------------------------------------------

    private fun requestAssignmentApproval(
        trackIndex: Int,
        result: MonsterCompatibility,
        initialAssignment: Boolean = false
    ) {

        val monsterId = result.monster.id

        val currentId = assignments[trackIndex]

        val currentName = currentId?.let {
            MonsterMapping.getMonster(it)?.name
        } ?: "Unassigned"

        val warning = when {
            result.accommodatesEntireTrack ->
                "All notes fit this monster."

            result.compatibleNotes > 0 ->
                "${result.incompatibleNotes} notes remain outside its range."

            else ->
                "This monster cannot accommodate the track."
        }

        val message = buildString {

            append("Current assignment: $currentName\n\n")
            append("Proposed monster: ${result.monster.name}\n")
            append("Compatibility: ${result.score}%\n")
            append(
                "Notes accommodated: " +
                "${result.compatibleNotes}/${result.totalNotes}\n"
            )
            append(
                "Octave adjustment: " +
                "${result.octaveShift} semitones\n\n"
            )
            append(warning)
            append("\n\nYour assignment will only change after approval.")
        }

        AlertDialog.Builder(this)
            .setTitle(
                if (initialAssignment) {
                    "Approve initial monster?"
                } else {
                    "Approve monster change?"
                }
            )
            .setMessage(message)
            .setNegativeButton(
                if (initialAssignment) "Leave unassigned"
                else "Keep current"
            ) { _, _ ->
                // No assignment change.
            }
            .setPositiveButton("Approve") { _, _ ->

                assignments[trackIndex] = monsterId

                Toast.makeText(
                    this,
                    "${result.monster.name} assigned.",
                    Toast.LENGTH_SHORT
                ).show()

                analyzeAllTracks()
            }
            .show()
    }

    // --------------------------------------------------
    // CONVERSION VALIDATION
    // --------------------------------------------------

    private fun validateAllAssignments() {
    val midi = parsedMidi

    if (midi == null) {
        showMessage("No composition", "Import a MIDI file first.")
        return
    }

    val minimumCompatibility = 70
    val missing = mutableListOf<String>()
    val belowThreshold = mutableListOf<String>()
    val passed = mutableListOf<String>()

    midi.tracks.forEachIndexed { index, track ->
        if (track.notes.isEmpty()) return@forEachIndexed

        val monsterId = assignments[index]

        if (monsterId == null) {
            missing.add(track.name)
            return@forEachIndexed
        }

        val monster = MonsterMapping.getMonster(monsterId)

        if (monster == null) {
            missing.add(track.name)
            return@forEachIndexed
        }

        val result = MonsterMapping.analyzeCompatibility(
            monster,
            track.notes.map { it.pitch }
        )

        if (result.score >= minimumCompatibility) {
            passed.add("${track.name}: ${result.score}%")
        } else {
            belowThreshold.add("${track.name}: ${result.score}%")
        }
    }

    val allPartsPassed =
        missing.isEmpty() &&
        belowThreshold.isEmpty() &&
        passed.isNotEmpty()

    val message = buildString {
        append("Compatibility requirement: 70% or higher\n\n")
        append("Parts passing: ${passed.size}\n\n")

        if (missing.isNotEmpty()) {
            append("Unassigned parts:\n")
            missing.forEach { append("- $it\n") }
            append("\n")
        }

        if (belowThreshold.isNotEmpty()) {
            append("Parts below 70%:\n")
            belowThreshold.forEach { append("- $it\n") }
            append("\n")
        }

        if (allPartsPassed) {
            append("All parts meet the requirement.\n")
            append("The composition is approved to proceed to conversion.")
        } else {
            append("Conversion remains locked until every part passes.")
        }
    }

    showMessage(
        if (allPartsPassed) "Composition approved"
        else "Composition not ready",
        message
    )
}

    // --------------------------------------------------
    // UI HELPERS
    // --------------------------------------------------

    private fun createCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(15, 15, 15, 15)
            background = rounded(cardColor, 18f)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            params.bottomMargin = 16
            layoutParams = params
        }

    private fun section(title: String): TextView =
        TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(0, 18, 0, 10)
        }

    private fun cardHeading(title: String): TextView =
        TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            setPadding(0, 4, 0, 10)
        }

    private fun detailText(value: String): TextView =
        TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, 6, 0, 12)
        }

    private fun createCardText(value: String): TextView =
        TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(textColor)
            setPadding(14, 14, 14, 14)
            background = rounded(cardColor, 14f)
        }

    private fun createButton(
        title: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            params.bottomMargin = 9
            layoutParams = params
        }

    private fun rounded(
        color: Int,
        radius: Float
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun showMessage(
        title: String,
        message: String
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
