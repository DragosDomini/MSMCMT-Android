
package com.cyndyrfyrestudios.msmcmt

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MSMConverter"
    }

    // --------------------------------------------------
    // APPLICATION STATE
    // --------------------------------------------------

    private var parsedMidi: ParsedMidi? = null
    private var midiUri: Uri? = null
    private var pendingMidiFile: File? = null
    private var pendingMsmBytes: ByteArray? = null
    private var assignmentsValidated = false

    private val expandedTrackCards = mutableSetOf<Int>()

    private val assignments = mutableMapOf<Int, String>()

    private val selectedResults =
        mutableMapOf<Int, MonsterCompatibility>()

    // --------------------------------------------------
    // UI REFERENCES
    // --------------------------------------------------

    private lateinit var statusText: TextView
    private lateinit var songInfoText: TextView
    private lateinit var trackContainer: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var msmStatusText: TextView
    private lateinit var conversionProgress: ProgressBar
    private lateinit var exportMsmButton: Button

    // --------------------------------------------------
    // MSM THEME
    // --------------------------------------------------

    // Preserve the existing project fonts.
    private val headingTypeface by lazy {
        loadTypeface(
            "fonts/OpenDyslexic-Bold.ttf",
            Typeface.DEFAULT_BOLD
        )
    }

    private val bodyTypeface by lazy {
        loadTypeface(
            "fonts/Hobeaux-Bold.otf",
            Typeface.DEFAULT
        )
    }

    // Main theme colors.
    private val backgroundColor = Color.rgb(18, 25, 29)
    private val cardColor = Color.rgb(20, 25, 27)

    // Bright mint accent.
    private val accentColor = Color.rgb(91, 218, 174)

    // IMPORTANT:
    // The previous version used Color.BLACK for card text.
    // This made text invisible against the black MSM panels.
    private val textColor = Color.WHITE

    private val mutedColor = Color.rgb(226, 236, 232)

    private val shadowColor = Color.BLACK

    // --------------------------------------------------
    // FILE PICKERS
    // --------------------------------------------------

    private val midiPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                midiUri = uri
                importMidi(uri)
            }
        }

    private val msmPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                convertSelectedMsm(uri)
            }
        }

    private val msmSavePicker =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/octet-stream"
            )
        ) { uri ->
            if (uri != null) {
                saveConvertedMsm(uri)
            } else {
                statusText.text = "MSM export cancelled."
                conversionProgress.visibility = View.GONE
            }
        }

    private val midiSavePicker =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("audio/midi")
        ) { uri ->
            if (uri != null) {
                saveConvertedMidi(uri)
            } else {
                msmStatusText.text = "MIDI export cancelled."
            }
        }

    // --------------------------------------------------
    // ACTIVITY
    // --------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildInterface()
        loadDatabase()
    }

    // --------------------------------------------------
    // MAIN INTERFACE
    // --------------------------------------------------

    private fun buildInterface() {

        val scroll = ScrollView(this).apply {
            fillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(backgroundColor)
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                18,
                20,
                18,
                30
            )

            // Keep the original MSM background,
            // but darken it so text is easier to read.
            background = LayerDrawable(
                arrayOf(
                    assetDrawable("images/background.jpg")
                        ?.apply {
                            gravity = Gravity.FILL
                            alpha = 165
                        } ?: ColorDrawable(backgroundColor),

                    ColorDrawable(
                        Color.argb(
                            105,
                            8,
                            13,
                            17
                        )
                    )
                )
            )
        }

        // --------------------------------------------------
        // HEADER
        // --------------------------------------------------

        val header = TextView(this).apply {

            text = "MSM CONVERTER"
            textSize = 30f
            typeface = headingTypeface

            setTextColor(accentColor)

            gravity = Gravity.CENTER

            setShadowLayer(
                2f,
                0f,
                2f,
                shadowColor
            )

            setPadding(
                0,
                16,
                0,
                8
            )
        }

        val subtitle = TextView(this).apply {

            text = "My Singing Monsters composition converter"

            textSize = 16f
            typeface = bodyTypeface

            setTextColor(mutedColor)

            gravity = Gravity.CENTER

            setShadowLayer(
                2f,
                0f,
                1f,
                shadowColor
            )

            setPadding(
                4,
                0,
                4,
                26
            )
        }

        content.addView(header)
        content.addView(subtitle)

        // --------------------------------------------------
        // MSM → MIDI
        // --------------------------------------------------

        content.addView(section("MSM → MIDI"))

        content.addView(
            createButton("Export MSM → MIDI") {
                msmPicker.launch(arrayOf("*/*"))
            }
        )

        msmStatusText = createCardText(
            "Choose an MSM composition to convert it into a MIDI file."
        )

        content.addView(msmStatusText)

        conversionProgress = createAssetProgressBar()
        content.addView(conversionProgress)

        // --------------------------------------------------
        // MIDI → MSM
        // --------------------------------------------------

        content.addView(section("MIDI → MSM"))

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

        // --------------------------------------------------
        // MONSTER ASSIGNMENTS
        // --------------------------------------------------

        content.addView(
            section("MONSTER ASSIGNMENTS")
        )

        val explanation = TextView(this).apply {

            text =
                "Each MIDI part is analyzed independently. " +
                "If its assigned monster cannot play the entire " +
                "part, alternative monsters are recommended."

            textSize = 15f
            typeface = bodyTypeface

            setTextColor(Color.WHITE)

            setShadowLayer(
                1.5f,
                0f,
                1f,
                shadowColor
            )

            setLineSpacing(3f, 1.05f)

            setPadding(
                8,
                8,
                8,
                18
            )
        }

        content.addView(explanation)

        trackContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(trackContainer)

        // --------------------------------------------------
        // CONVERSION
        // --------------------------------------------------

        content.addView(section("CONVERSION"))

        content.addView(
            createButton("Validate all assignments") {
                validateAllAssignments()
            }
        )

        exportMsmButton = createButton("Export MIDI → MSM") {
            exportMidiToMsm()
        }.apply {

            isEnabled = false
            alpha = 0.55f
        }

        content.addView(exportMsmButton)

        content.addView(
            createButton("Refresh recommendations") {
                analyzeAllTracks()
            }
        )

        scroll.addView(content)

        setContentView(scroll)
    }

    // --------------------------------------------------
    // DATABASE
    // --------------------------------------------------

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
    // MSM → MIDI CONVERSION
    // --------------------------------------------------

    private fun convertSelectedMsm(uri: Uri) {

        msmStatusText.text = "Reading MSM composition..."

        conversionProgress.visibility = View.VISIBLE
        conversionProgress.progress = 8

        Thread {

            try {

                val inputFile = File(
                    cacheDir,
                    "msmcmt_input.msm"
                )

                val outputFile = File(
                    cacheDir,
                    "msmcmt_converted.mid"
                )

                contentResolver.openInputStream(uri)?.use { input ->

                    inputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }

                } ?: throw IllegalStateException(
                    "Unable to open the selected MSM file."
                )

                if (inputFile.length() == 0L) {
                    throw IllegalArgumentException(
                        "The selected MSM file is empty."
                    )
                }

                runOnUiThread {

                    conversionProgress.progress = 35

                    msmStatusText.text =
                        "Converting MSM composition..."
                }

                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this))
                }

                val converter = Python.getInstance()
                    .getModule("msmconverter")

                converter.callAttr(
                    "convert_msm_to_midi",
                    inputFile.absolutePath,
                    outputFile.absolutePath,
                    8,
                    null,
                    false,
                    null
                )

                if (!outputFile.exists() || outputFile.length() == 0L) {
                    throw IllegalStateException(
                        "The converter did not create a MIDI file."
                    )
                }

                runOnUiThread {

                    conversionProgress.progress = 100

                    pendingMidiFile = outputFile

                    msmStatusText.text =
                        "Conversion complete. Choose where to save your MIDI."

                    conversionProgress.visibility = View.GONE

                    midiSavePicker.launch(
                        inputFile.nameWithoutExtension + ".mid"
                    )
                }

            } catch (e: Exception) {

                Log.e(TAG, "MSM to MIDI conversion failed", e)

                runOnUiThread {

                    conversionProgress.visibility = View.GONE

                    msmStatusText.text =
                        "MSM conversion failed: ${e.message}"

                    showMessage(
                        "MSM conversion error",
                        e.message ?: "Unable to convert this MSM file."
                    )
                }
            }

        }.start()
    }

    // --------------------------------------------------
    // MIDI SAVE
    // --------------------------------------------------

    private fun saveConvertedMidi(uri: Uri) {

        val source = pendingMidiFile

        if (source == null || !source.exists()) {

            msmStatusText.text =
                "No converted MIDI file is waiting to be saved."

            return
        }

        try {

            contentResolver.openOutputStream(uri)?.use { output ->

                source.inputStream().use { input ->
                    input.copyTo(output)
                }

            } ?: throw IllegalStateException(
                "Unable to create the destination MIDI file."
            )

            msmStatusText.text =
                "MIDI exported successfully: ${source.name}"

            Toast.makeText(
                this,
                "MIDI file saved.",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            Log.e(TAG, "MIDI export failed", e)

            msmStatusText.text =
                "MIDI export failed: ${e.message}"

            showMessage(
                "MIDI export error",
                e.message ?: "Unable to save the MIDI file."
            )
        }
    }

    // --------------------------------------------------
    // MIDI → MSM EXPORT
    // --------------------------------------------------

    private fun exportMidiToMsm() {

        val midi = parsedMidi

        if (midi == null) {

            showMessage(
                "No composition",
                "Import a MIDI file first."
            )

            return
        }

        if (!assignmentsValidated) {

            showMessage(
                "Validation required",
                "Validate all monster assignments before exporting."
            )

            return
        }

        val assignmentIds = assignments.mapNotNull {
            (trackIndex, monsterId) ->

            monsterId.toIntOrNull()?.let {
                trackIndex to it
            }
        }.toMap()

        statusText.text = "Preparing MSM composition..."

        conversionProgress.visibility = View.VISIBLE
        conversionProgress.progress = 15

        exportMsmButton.isEnabled = false

        Thread {

            try {

                val output = MsmConverter.convert(
                    this,
                    midi,
                    8,
                    assignmentIds
                )

                runOnUiThread {

                    pendingMsmBytes = output

                    conversionProgress.progress = 100
                    conversionProgress.visibility = View.GONE

                    statusText.text =
                        "MSM composition ready. Choose where to save it."

                    exportMsmButton.isEnabled = assignmentsValidated

                    exportMsmButton.alpha =
                        if (exportMsmButton.isEnabled) 1f else 0.55f

                    val safeName = midi.songName
                        .ifBlank { "MSM_Composition" }
                        .replace(
                            Regex("[^A-Za-z0-9 _-]"),
                            "_"
                        )
                        .trim()
                        .ifBlank { "MSM_Composition" }

                    msmSavePicker.launch("$safeName.msm")
                }

            } catch (e: Exception) {

                Log.e(TAG, "MIDI to MSM export failed", e)

                runOnUiThread {

                    conversionProgress.visibility = View.GONE

                    exportMsmButton.isEnabled = assignmentsValidated

                    exportMsmButton.alpha =
                        if (exportMsmButton.isEnabled) 1f else 0.55f

                    statusText.text =
                        "MSM export failed: ${e.message}"

                    showMessage(
                        "MSM export error",
                        e.message
                            ?: "Unable to export this MIDI composition."
                    )
                }
            }

        }.start()
    }

    // --------------------------------------------------
    // MSM SAVE
    // --------------------------------------------------

    private fun saveConvertedMsm(uri: Uri) {

        val bytes = pendingMsmBytes

        if (bytes == null || bytes.isEmpty()) {

            statusText.text =
                "No converted MSM file is waiting to be saved."

            return
        }

        try {

            contentResolver.openOutputStream(uri)?.use { output ->

                output.write(bytes)
                output.flush()

            } ?: throw IllegalStateException(
                "Unable to create the destination MSM file."
            )

            statusText.text = "MSM exported successfully."

            Toast.makeText(
                this,
                "MSM composition saved.",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            Log.e(TAG, "MSM save failed", e)

            statusText.text =
                "MSM save failed: ${e.message}"

            showMessage(
                "MSM save error",
                e.message ?: "Unable to save the MSM file."
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

            assignments.clear()
            selectedResults.clear()
            expandedTrackCards.clear()

            assignmentsValidated = false

            exportMsmButton.isEnabled = false
            exportMsmButton.alpha = 0.55f

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

                append(
                    "Notes: ${result.tracks.sumOf { it.notes.size }}"
                )
            }

            analyzeAllTracks()

        } catch (e: Exception) {

            Log.e(TAG, "MIDI import failed", e)

            parsedMidi = null

            assignments.clear()
            selectedResults.clear()
            expandedTrackCards.clear()

            assignmentsValidated = false

            exportMsmButton.isEnabled = false
            exportMsmButton.alpha = 0.55f

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
    // COLLAPSIBLE TRACK CARDS
    // --------------------------------------------------

    private fun addTrackCard(
        index: Int,
        track: MidiTrackData,
        result: MonsterCompatibility,
        alternatives: List<MonsterCompatibility>,
        awaitingInitialApproval: Boolean = false
    ) {

        val card = createCard()

        val isCollapsed =
            assignments.containsKey(index) &&
            index !in expandedTrackCards

        if (isCollapsed) {

            card.addView(
                cardHeading(
                    "PART ${index + 1}: ${track.name}"
                )
            )

            card.addView(
                detailText(
                    "Monster: ${result.monster.name}  •  " +
                    "Compatibility: ${result.score}%  •  " +
                    "${track.notes.size} notes"
                )
            )

            card.addView(
                createButton("Expand part details") {

                    expandedTrackCards.add(index)
                    analyzeAllTracks()
                }
            )

            trackContainer.addView(card)
            return
        }

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

        if (assignments.containsKey(index)) {

            card.addView(
                createButton("Collapse part details") {

                    expandedTrackCards.remove(index)
                    analyzeAllTracks()
                }
            )
        }

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

                    setPadding(
                        14,
                        14,
                        14,
                        14
                    )

                    background = rounded(
                        Color.rgb(35, 51, 52),
                        12f
                    )
                }

                alternativeCard.addView(
                    cardHeading(
                        "#${rank + 1} ${alternative.monster.name}"
                    )
                )

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

                params.bottomMargin = 12

                card.addView(
                    alternativeCard,
                    params
                )
            }
        }

        trackContainer.addView(card)
    }

    // --------------------------------------------------
    // NO MATCH CARD
    // --------------------------------------------------

    private fun addNoMatchCard(
        index: Int,
        name: String
    ) {

        val card = createCard()

        card.addView(
            cardHeading(
                "PART ${index + 1}: $name"
            )
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

            setPadding(
                14,
                14,
                14,
                14
            )

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

            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD

            setTextColor(Color.WHITE)

            setShadowLayer(
                1.5f,
                0f,
                1f,
                shadowColor
            )
        }

        val score = TextView(this).apply {

            text = "${result.score}%"

            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD

            setTextColor(Color.WHITE)

            setShadowLayer(
                2f,
                0f,
                1f,
                shadowColor
            )
        }

        val details = TextView(this).apply {

            text =
                "${result.compatibleNotes}/${result.totalNotes} " +
                "notes accommodated\n" +
                "${result.incompatibleNotes} notes outside range\n" +
                "Octave adjustment: ${result.octaveShift} semitones\n\n" +
                result.explanation

            textSize = 14f
            typeface = bodyTypeface

            setTextColor(Color.WHITE)

            setLineSpacing(3f, 1.05f)

            setShadowLayer(
                1f,
                0f,
                1f,
                shadowColor
            )
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

            append(
                "\n\nYour assignment will only change after approval."
            )
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

                expandedTrackCards.remove(trackIndex)

                assignmentsValidated = false

                exportMsmButton.isEnabled = false
                exportMsmButton.alpha = 0.55f

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

            showMessage(
                "No composition",
                "Import a MIDI file first."
            )

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

            append(
                "Compatibility requirement: 70% or higher\n\n"
            )

            append("Parts passing: ${passed.size}\n\n")

            if (missing.isNotEmpty()) {

                append("Unassigned parts:\n")

                missing.forEach {
                    append("- $it\n")
                }

                append("\n")
            }

            if (belowThreshold.isNotEmpty()) {

                append("Parts below 70%:\n")

                belowThreshold.forEach {
                    append("- $it\n")
                }

                append("\n")
            }

            if (allPartsPassed) {

                append("All parts meet the requirement.\n")

                append(
                    "The composition is approved to proceed to conversion."
                )

            } else {

                append(
                    "Conversion remains locked until every part passes."
                )
            }
        }

        assignmentsValidated = allPartsPassed

        exportMsmButton.isEnabled = allPartsPassed

        exportMsmButton.alpha =
            if (allPartsPassed) 1f else 0.55f

        showMessage(
            if (allPartsPassed) "Composition approved"
            else "Composition not ready",
            message
        )
    }

    // --------------------------------------------------
    // ASSET HELPERS
    // --------------------------------------------------

    private fun assetDrawable(
        path: String
    ): BitmapDrawable? {

        return try {

            assets.open(path).use { stream ->

                val bitmap = BitmapFactory.decodeStream(stream)
                    ?: return null

                BitmapDrawable(
                    resources,
                    bitmap
                )
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to load asset: $path",
                e
            )

            null
        }
    }

    private fun loadTypeface(
        path: String,
        fallback: Typeface
    ): Typeface {

        return try {

            Typeface.createFromAsset(
                assets,
                path
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to load font: $path",
                e
            )

            fallback
        }
    }

    // --------------------------------------------------
    // CUSTOM MSM PROGRESS BAR
    // --------------------------------------------------

    private fun createAssetProgressBar(): ProgressBar {

        val bar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {

            max = 100
            progress = 0
            visibility = View.GONE

            progressDrawable = LayerDrawable(
                arrayOf(

                    assetDrawable("images/loadbar_bg.png")
                        ?: ColorDrawable(cardColor),

                    ClipDrawable(
                        assetDrawable("images/loadbar.png")
                            ?: ColorDrawable(accentColor),
                        Gravity.START,
                        ClipDrawable.HORIZONTAL
                    )
                )
            ).apply {

                setId(
                    0,
                    android.R.id.background
                )

                setId(
                    1,
                    android.R.id.progress
                )
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            22
        )

        params.topMargin = 8
        params.bottomMargin = 12

        bar.layoutParams = params

        return bar
    }

    // --------------------------------------------------
    // CARD HELPERS
    // --------------------------------------------------

    private fun createCard(): LinearLayout =
        LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                18,
                18,
                18,
                18
            )

            background = assetDrawable("images/panel.png")
                ?.apply {
                    gravity = Gravity.FILL
                }
                ?: rounded(cardColor, 18f)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            params.bottomMargin = 16

            layoutParams = params
        }

    // --------------------------------------------------
    // SECTION HEADINGS
    // --------------------------------------------------

    private fun section(
        title: String
    ): TextView =
        TextView(this).apply {

            text = title

            textSize = 17f
            typeface = headingTypeface

            setTextColor(accentColor)

            setShadowLayer(
                2f,
                0f,
                2f,
                shadowColor
            )

            setPadding(
                0,
                20,
                0,
                12
            )
        }

    // --------------------------------------------------
    // CARD HEADINGS
    // --------------------------------------------------

    private fun cardHeading(
        title: String
    ): TextView =
        TextView(this).apply {

            text = title

            textSize = 19f
            typeface = headingTypeface

            setTextColor(textColor)

            setShadowLayer(
                2f,
                0f,
                2f,
                shadowColor
            )

            setPadding(
                0,
                6,
                0,
                12
            )
        }

    // --------------------------------------------------
    // DESCRIPTION TEXT
    // --------------------------------------------------

    private fun detailText(
        value: String
    ): TextView =
        TextView(this).apply {

            text = value

            textSize = 15f
            typeface = bodyTypeface

            setTextColor(mutedColor)

            setShadowLayer(
                1.5f,
                0f,
                1f,
                shadowColor
            )

            setLineSpacing(
                3f,
                1.05f
            )

            setPadding(
                0,
                8,
                0,
                14
            )
        }

    // --------------------------------------------------
    // STATUS / INFORMATION PANELS
    // --------------------------------------------------

    private fun createCardText(
        value: String
    ): TextView =
        TextView(this).apply {

            text = value

            textSize = 16f
            typeface = bodyTypeface

            // White text instead of the old black text.
            setTextColor(Color.WHITE)

            setShadowLayer(
                1.5f,
                0f,
                1f,
                shadowColor
            )

            setLineSpacing(
                3f,
                1.05f
            )

            setPadding(
                18,
                18,
                18,
                18
            )

            background = assetDrawable("images/panel.png")
                ?.apply {
                    gravity = Gravity.FILL
                }
                ?: rounded(cardColor, 14f)
        }

    // --------------------------------------------------
    // BUTTONS
    // --------------------------------------------------

    private fun createButton(
        title: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {

            text = title

            isAllCaps = false

            textSize = 17f
            typeface = bodyTypeface

            setTextColor(Color.WHITE)

            setShadowLayer(
                1f,
                0f,
                1f,
                shadowColor
            )

            background = assetDrawable("images/button_base.png")
                ?.apply {
                    gravity = Gravity.FILL
                }
                ?: rounded(accentColor, 12f)

            setPadding(
                16,
                12,
                16,
                12
            )

            minHeight = 58

            setOnClickListener {
                action()
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            params.bottomMargin = 12

            layoutParams = params
        }

    // --------------------------------------------------
    // GENERAL UI HELPERS
    // --------------------------------------------------

    private fun rounded(
        color: Int,
        radius: Float
    ) = GradientDrawable().apply {

        setColor(color)
        cornerRadius = radius
    }

    // --------------------------------------------------
    // DIALOGS
    // --------------------------------------------------

    private fun showMessage(
        title: String,
        message: String
    ) {

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create()

        dialog.setOnShowListener {

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(
                    Color.rgb(75, 190, 150)
                )
        }

        dialog.show()
    }
}
