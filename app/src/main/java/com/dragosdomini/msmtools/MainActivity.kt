
package com.dragosdomini.msmtools

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    // --------------------------------------------------
    // STATE
    // --------------------------------------------------

    private var selectedMidi: Uri? = null
    private var selectedName = "No MIDI selected"

    private var currentMidi: ParsedMidi? = null
    private var convertedMsm: ByteArray? = null

    private var selectedTempo = 120
    private var selectedNumerator = 4
    private var selectedDenominator = 4
    private var selectedNoteDivision = 8

    private val monsterAssignments = mutableMapOf<Int, Int>()

    private var currentPartIndex = 0

    private var allMonsters: List<MonsterMapping.Monster> =
        emptyList()

    private var suggestions =
        emptyList<MonsterMapping.MonsterSuggestion>()

    private var selectedMonsterId: Int? = null

    private var busy = false

    // --------------------------------------------------
    // COLORS
    // --------------------------------------------------

    private val colors = object {

        val background = Color.rgb(35, 23, 47)
        val panel = Color.rgb(74, 49, 91)
        val panelLight = Color.rgb(92, 64, 108)

        val gold = Color.rgb(238, 185, 85)
        val cream = Color.rgb(255, 238, 204)

        val muted = Color.rgb(194, 176, 205)
        val green = Color.rgb(119, 204, 137)
        val red = Color.rgb(255, 135, 120)
    }

    // --------------------------------------------------
    // VIEWS
    // --------------------------------------------------

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout

    private lateinit var fileNameView: TextView
    private lateinit var bpmView: TextView
    private lateinit var timeSignatureView: TextView
    private lateinit var statusView: TextView

    private lateinit var numeratorSpinner: Spinner
    private lateinit var denominatorSpinner: Spinner

    private lateinit var tempoInput: EditText

    private lateinit var convertButton: Button

    private lateinit var trackStatusView: TextView

    // --------------------------------------------------
    // FILE PICKER
    // --------------------------------------------------

    private val midiPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->

        if (uri != null) {

            selectedMidi = uri

            selectedName = getFileName(uri)
                ?: "Selected MIDI"

            fileNameView.text = selectedName

            readMidi(uri)
        }
    }

    private val msmSaver = registerForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/octet-stream"
        )
    ) { uri ->

        if (uri != null) {
            saveMsm(uri)
        }
    }

    // --------------------------------------------------
    // LIFECYCLE
    // --------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background

        selectedNumerator =
            savedInstanceState?.getInt("numerator") ?: 4

        selectedDenominator =
            savedInstanceState?.getInt("denominator") ?: 4

        selectedNoteDivision =
            savedInstanceState?.getInt("division") ?: 8

        selectedTempo =
            savedInstanceState?.getInt("tempo") ?: 120

        buildInterface()

        loadMonsterDatabase()
    }

    override fun onSaveInstanceState(outState: Bundle) {

        outState.putInt("numerator", selectedNumerator)
        outState.putInt("denominator", selectedDenominator)
        outState.putInt("division", selectedNoteDivision)
        outState.putInt("tempo", selectedTempo)

        super.onSaveInstanceState(outState)
    }

    // --------------------------------------------------
    // MAIN INTERFACE
    // --------------------------------------------------

    private fun buildInterface() {

        val scroll = ScrollView(this).apply {
            setBackgroundColor(colors.background)
            isFillViewport = true
        }

        root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(18),
                dp(22),
                dp(18),
                dp(30)
            )
        }

        content = root

        scroll.addView(root)

        setContentView(scroll)

        // HEADER

        val header = TextView(this).apply {

            text = "MSM MIDI CONVERTER"

            textSize = 27f

            gravity = Gravity.CENTER

            setTextColor(colors.gold)

            typeface = Typeface.create(
                "sans-serif-rounded",
                Typeface.BOLD
            )
        }

        root.addView(header, matchWrap())

        val subtitle = TextView(this).apply {

            text = "Compose your music. Monster by monster."

            textSize = 14f

            gravity = Gravity.CENTER

            setTextColor(colors.cream)

            setPadding(0, dp(7), 0, dp(22))
        }

        root.addView(subtitle, matchWrap())

        // FILE PANEL

        val filePanel = section("SONG FILE")

        fileNameView = text(
            "No MIDI selected",
            15f,
            colors.cream,
            false
        )

        fileNameView.setPadding(
            0,
            dp(10),
            0,
            dp(12)
        )

        filePanel.addView(fileNameView)

        filePanel.addView(
            button("SELECT MIDI FILE") {

                midiPicker.launch(
                    arrayOf(
                        "audio/midi",
                        "audio/x-midi",
                        "application/octet-stream"
                    )
                )
            }
        )

        root.addView(filePanel, matchWrap())

        // SONG INFORMATION

        val songPanel = section("SONG INFORMATION")

        bpmView = text(
            "BPM: Awaiting MIDI",
            16f,
            colors.gold,
            true
        )

        songPanel.addView(bpmView)

        tempoInput = EditText(this).apply {

            setTextColor(colors.cream)

            setHintTextColor(colors.muted)

            hint = "Enter BPM"

            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER

            setText(selectedTempo.toString())

            background = rounded(
                colors.panelLight,
                colors.gold,
                1,
                10
            )

            setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(8)
            )
        }

        songPanel.addView(
            tempoInput,
            matchWrap()
        )

        root.addView(songPanel, matchWrap())

        // COMPOSITION SETTINGS

        val settings = section("COMPOSITION SETTINGS")

        settings.addView(
            text(
                "TIME SIGNATURE",
                14f,
                colors.gold,
                true
            )
        )

        val timeRow = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER

        }

        numeratorSpinner = createSpinner(
            (1..12).map { it.toString() },
            selectedNumerator.toString()
        ) {

            selectedNumerator = it.toInt()
            updateTimeSignature()
        }

        denominatorSpinner = createSpinner(
            listOf("2", "4", "8"),
            selectedDenominator.toString()
        ) {

            selectedDenominator = it.toInt()
            updateTimeSignature()
        }

        timeRow.addView(
            spinnerColumn("UPPER NUMERAL", numeratorSpinner),
            weightParams()
        )

        timeRow.addView(
            spinnerColumn("LOWER NUMERAL", denominatorSpinner),
            weightParams()
        )

        settings.addView(timeRow)

        timeSignatureView = text(
            "4 / 4",
            24f,
            colors.gold,
            true
        )

        timeSignatureView.gravity = Gravity.CENTER

        settings.addView(timeSignatureView)

        // NOTE DIVISION

        settings.addView(
            text(
                "SMALLEST NOTE DIVISION",
                14f,
                colors.gold,
                true
            ).apply {
                setPadding(0, dp(15), 0, dp(8))
            }
        )

        val divisionRow = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL
        }

        listOf(8, 16, 32).forEach { value ->

            divisionRow.addView(
                button("1/$value") {

                    selectedNoteDivision = value

                    updateDivisionButtons()
                },
                weightParams()
            )
        }

        settings.addView(divisionRow)

        root.addView(settings, matchWrap())

        // TRACK INFORMATION

        val tracksPanel = section("MONSTER ASSIGNMENTS")

        trackStatusView = text(
            "No MIDI tracks loaded.",
            14f,
            colors.cream,
            false
        )

        tracksPanel.addView(trackStatusView)

        root.addView(tracksPanel, matchWrap())

        // CONVERSION BUTTON

        convertButton = button(
            "ANALYZE COMPOSITION"
        ) {

            if (currentMidi == null) {

                showStatus("Select a MIDI file first.", true)

            } else {

                beginAssignments()
            }
        }

        convertButton.isEnabled = false

        root.addView(
            convertButton,
            matchWrap()
        )

        // STATUS

        statusView = text(
            "Welcome! Select a MIDI file to begin.",
            14f,
            colors.cream,
            false
        )

        statusView.gravity = Gravity.CENTER

        statusView.setPadding(
            dp(8),
            dp(17),
            dp(8),
            dp(10)
        )

        root.addView(statusView, matchWrap())

        updateTimeSignature()
        updateDivisionButtons()
    }

    // --------------------------------------------------
    // LOAD DATABASE
    // --------------------------------------------------

    private fun loadMonsterDatabase() {

        thread {

            try {

                val monsters =
                    MonsterMapping.getAllMonsters(this)

                runOnUiThread {

                    allMonsters = monsters

                    showStatus(
                        "${monsters.size} monsters loaded."
                    )
                }

            } catch (e: Exception) {

                runOnUiThread {

                    showStatus(
                        "Monster database error: ${e.message}",
                        true
                    )
                }
            }
        }
    }

    // --------------------------------------------------
    // READ MIDI
    // --------------------------------------------------

    private fun readMidi(uri: Uri) {

        showStatus("Reading MIDI composition...")

        convertButton.isEnabled = false

        thread {

            try {

                val bytes = contentResolver
                    .openInputStream(uri)
                    ?.use { it.readBytes() }
                    ?: throw IOException(
                        "Unable to open MIDI file."
                    )

                val parsed = MidiReader.read(bytes)

                if (parsed.tracks.isEmpty()) {

                    throw IOException(
                        "No musical tracks were found."
                    )
                }

                runOnUiThread {

                    currentMidi = parsed

                    selectedTempo = parsed.tempo

                    selectedNumerator =
                        parsed.timeNumerator

                    selectedDenominator =
                        parsed.timeDenominator

                    tempoInput.setText(
                        selectedTempo.toString()
                    )

                    selectSpinnerValue(
                        numeratorSpinner,
                        selectedNumerator.toString()
                    )

                    selectSpinnerValue(
                        denominatorSpinner,
                        selectedDenominator.toString()
                    )

                    updateTimeSignature()

                    bpmView.text =
                        "DETECTED BPM: ${parsed.tempo}"

                    fileNameView.text = selectedName

                    val totalNotes =
                        parsed.tracks.sumOf { it.notes.size }

                    trackStatusView.text =
                        "${parsed.tracks.size} musical parts detected\n" +
                        "$totalNotes individual notes found\n\n" +
                        "Every part will be checked separately."

                    monsterAssignments.clear()

                    currentPartIndex = 0

                    convertButton.isEnabled = true

                    showStatus(
                        "MIDI successfully analyzed. Review your composition."
                    )
                }

            } catch (e: Exception) {

                runOnUiThread {

                    showStatus(
                        "MIDI reading failed:\n${e.message}",
                        true
                    )

                    convertButton.isEnabled = false
                }
            }
        }
    }

    // --------------------------------------------------
    // BEGIN ASSIGNMENTS
    // --------------------------------------------------

    private fun beginAssignments() {

        val midi = currentMidi ?: return

        if (busy) return

        val bpm = tempoInput.text
            .toString()
            .toIntOrNull()

        if (bpm == null || bpm !in 1..1000) {

            showStatus("Enter a valid BPM.", true)

            return
        }

        selectedTempo = bpm

        monsterAssignments.clear()

        currentPartIndex = 0

        showNextPart()
    }

    // --------------------------------------------------
    // NEXT TRACK
    // --------------------------------------------------

    private fun showNextPart() {

        val midi = currentMidi ?: return

        while (
            currentPartIndex < midi.tracks.size &&
            midi.tracks[currentPartIndex].notes.isEmpty()
        ) {
            currentPartIndex++
        }

        if (currentPartIndex >= midi.tracks.size) {

            showCompositionReview()

            return
        }

        val track = midi.tracks[currentPartIndex]

        suggestions =
            MonsterMapping.getDetailedSuggestions(
                this,
                track.program,
                track.bank
            )

        selectedMonsterId =
            monsterAssignments[currentPartIndex]
                ?: suggestions.firstOrNull {
                    it.matchType == MonsterMapping.MatchType.EXACT ||
                        it.matchType == MonsterMapping.MatchType.BANK_FALLBACK
                }?.monster?.id

        showMonsterDialog(track)
    }

    // --------------------------------------------------
    // MONSTER SELECTION
    // --------------------------------------------------

    private fun showMonsterDialog(
        track: MidiTrackData
    ) {

        val midi = currentMidi ?: return

        val automatic = suggestions.firstOrNull()

        val message = buildString {

            append(
                "PART ${currentPartIndex + 1} OF ${midi.tracks.size}\n\n"
            )

            append("Track: ${track.name}\n")

            append(
                "Instrument: ${instrumentName(track.program)}\n"
            )

            append("Program: ${track.program}\n")

            append("Bank: ${track.bank}\n")

            append("Notes: ${track.notes.size}\n\n")

            if (automatic != null) {

                append(
                    "Recommended monster: ${automatic.monster.name}\n"
                )

                append(
                    "Match: ${automatic.matchType}\n"
                )

                append(
                    automatic.reason
                )

            } else {

                append(
                    "No reliable automatic match was found.\n"
                )

                append(
                    "Please select the monster manually."
                )
            }
        }

        val selected = selectedMonsterId

        val selectedMonster = allMonsters.firstOrNull {
            it.id == selected
        }

        val dialogContent = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(18),
                dp(8),
                dp(18),
                dp(8)
            )
        }

        val information = TextView(this).apply {

            text = message

            textSize = 14f

            setTextColor(colors.cream)

            setPadding(0, 0, 0, dp(12))
        }

        dialogContent.addView(information)

        val currentSelection = TextView(this).apply {

            text = "Selected: ${selectedMonster?.name ?: "None"}"

            textSize = 16f

            setTextColor(colors.gold)

            typeface = Typeface.DEFAULT_BOLD

            setPadding(0, dp(8), 0, dp(12))
        }

        dialogContent.addView(currentSelection)

        val recommendationButton = button(
            "VIEW MATCH SUGGESTIONS"
        ) {

            showSuggestionPicker {
                currentSelection.text =
                    "Selected: ${allMonsters.firstOrNull { it.id == selectedMonsterId }?.name ?: "None"}"
            }

        }

        dialogContent.addView(recommendationButton)

        val manualButton = button(
            "BROWSE ALL MONSTERS"
        ) {

            showManualMonsterPicker {

                currentSelection.text =
                    "Selected: ${allMonsters.firstOrNull { it.id == selectedMonsterId }?.name ?: "None"}"
            }
        }

        dialogContent.addView(manualButton)

        val dialog = AlertDialog.Builder(this)
            .setTitle("MONSTER ASSIGNMENT")
            .setView(dialogContent)
            .setPositiveButton("ACCEPT & NEXT", null)
            .setNeutralButton("CHANGE MONSTER", null)
            .setNegativeButton("CANCEL", null)
            .create()

        dialog.setOnShowListener {

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {

                    val monsterId = selectedMonsterId

                    if (monsterId == null) {

                        showStatus(
                            "Choose a monster before continuing.",
                            true
                        )

                        return@setOnClickListener
                    }

                    monsterAssignments[currentPartIndex] =
                        monsterId

                    dialog.dismiss()

                    currentPartIndex++

                    showNextPart()
                }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener {

                    showManualMonsterPicker {

                        currentSelection.text =
                            "Selected: ${allMonsters.firstOrNull { it.id == selectedMonsterId }?.name ?: "None"}"
                    }
                }

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener {

                    dialog.dismiss()

                    showStatus(
                        "Monster selection paused."
                    )
                }
        }

        dialog.setOnCancelListener {

            showStatus("Monster selection paused.")
        }

        dialog.show()
    }

    // --------------------------------------------------
    // SUGGESTION PICKER
    // --------------------------------------------------

    private fun showSuggestionPicker(
        onSelected: () -> Unit
    ) {

        if (suggestions.isEmpty()) {

            AlertDialog.Builder(this)
                .setTitle("No automatic matches")
                .setMessage(
                    "No matching MIDI instrument mapping was found. " +
                        "Use Browse All Monsters to choose manually."
                )
                .setPositiveButton("OK", null)
                .show()

            return
        }

        val options = suggestions.map {

            "${it.monster.name} — ${it.score}%\n${it.reason}"
        }.toTypedArray()

        val selectedIndex = suggestions.indexOfFirst {
            it.monster.id == selectedMonsterId
        }

        AlertDialog.Builder(this)
            .setTitle("MONSTER RECOMMENDATIONS")
            .setSingleChoiceItems(
                options,
                selectedIndex
            ) { dialog, which ->

                selectedMonsterId =
                    suggestions[which].monster.id

                onSelected()

                dialog.dismiss()
            }
            .setNegativeButton("BACK", null)
            .show()
    }

    // --------------------------------------------------
    // MANUAL MONSTER PICKER
    // --------------------------------------------------

    private fun showManualMonsterPicker(
        onSelected: () -> Unit
    ) {

        val picker = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(dp(15), dp(10), dp(15), dp(10))
        }

        val search = EditText(this).apply {

            hint = "Search monsters..."

            setTextColor(colors.cream)

            setHintTextColor(colors.muted)

            background = rounded(
                colors.panel,
                colors.gold,
                1,
                10
            )

            setPadding(
                dp(12),
                dp(10),
                dp(12),
                dp(10)
            )
        }

        picker.addView(search)

        val list = ListView(this)

        picker.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(390)
            )
        )

        var displayed = allMonsters

        fun refresh(query: String) {

            displayed = if (query.isBlank()) {

                allMonsters

            } else {

                allMonsters.filter {
                    it.name.contains(
                        query,
                        ignoreCase = true
                    )
                }
            }

            list.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                displayed.map { it.name }
            )
        }

        refresh("")

        search.addTextChangedListener(
            object : android.text.TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    refresh(s?.toString() ?: "")
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) {}
            }
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("SELECT A MONSTER")
            .setView(picker)
            .setNegativeButton("CANCEL", null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->

            val monster = displayed.getOrNull(position)
                ?: return@setOnItemClickListener

            selectedMonsterId = monster.id

            onSelected()

            dialog.dismiss()
        }

        dialog.show()
    }

    // --------------------------------------------------
    // COMPOSITION REVIEW
    // --------------------------------------------------

    private fun showCompositionReview() {

        val midi = currentMidi ?: return

        val report = StringBuilder()

        report.append("SONG: ${midi.songName}\n\n")

        report.append("BPM: $selectedTempo\n")

        report.append(
            "TIME SIGNATURE: $selectedNumerator/$selectedDenominator\n"
        )

        report.append(
            "NOTE DIVISION: 1/$selectedNoteDivision\n\n"
        )

        report.append("MONSTER ASSIGNMENTS\n\n")

        midi.tracks.forEachIndexed { index, track ->

            if (track.notes.isEmpty()) return@forEachIndexed

            val monster = allMonsters.firstOrNull {

                it.id == monsterAssignments[index]
            }

            report.append(
                "${index + 1}. ${track.name}\n"
            )

            report.append(
                "   ${monster?.name ?: "UNASSIGNED"}\n"
            )

            report.append(
                "   ${track.notes.size} notes\n\n"
            )
        }

        AlertDialog.Builder(this)
            .setTitle("COMPOSITION REVIEW")
            .setMessage(report.toString())
            .setNegativeButton("BACK") { _, _ ->

                currentPartIndex =
                    (midi.tracks.size - 1).coerceAtLeast(0)

                while (
                    currentPartIndex > 0 &&
                    midi.tracks[currentPartIndex].notes.isEmpty()
                ) {
                    currentPartIndex--
                }

                showNextPart()
            }
            .setPositiveButton("GENERATE MSM") { _, _ ->

                generateMsm()
            }
            .show()
    }

    // --------------------------------------------------
    // GENERATE MSM
    // --------------------------------------------------

    private fun generateMsm() {

        val midi = currentMidi ?: return

        if (busy) return

        val assignments = monsterAssignments.toMap()

        for ((index, track) in midi.tracks.withIndex()) {

            if (track.notes.isEmpty()) continue

            if (assignments[index] == null) {

                showStatus(
                    "Part ${index + 1} has no monster assigned.",
                    true
                )

                return
            }
        }

        busy = true

        convertButton.isEnabled = false

        showStatus("Generating your MSM composition...")

        thread {

            try {

                val configuredMidi = midi.copy(

                    tempo = selectedTempo,

                    timeNumerator = selectedNumerator,

                    timeDenominator = selectedDenominator
                )

                // Run the converter's preflight analysis
                // before generating the actual file.

                val analysis = MsmConverter.analyzeConversion(
                    this,
                    configuredMidi,
                    selectedNoteDivision,
                    assignments
                )

                val output = MsmConverter.convert(
                    context = this,
                    midi = configuredMidi,
                    minimumNote = selectedNoteDivision,
                    monsterAssignments = assignments
                )

                if (output.isEmpty()) {

                    throw IOException(
                        "The generated MSM file is empty."
                    )
                }

                runOnUiThread {

                    convertedMsm = output

                    busy = false

                    convertButton.isEnabled = true

                    showConversionReport(analysis)
                }

            } catch (e: Exception) {

                runOnUiThread {

                    busy = false

                    convertButton.isEnabled = true

                    showStatus(
                        "Conversion failed:\n${e.message}",
                        true
                    )
                }
            }
        }
    }

    // --------------------------------------------------
    // CONVERSION REPORT
    // --------------------------------------------------

    private fun showConversionReport(
        report: MsmConverter.ConversionReport
    ) {

        val message = StringBuilder()

        message.append(
            "Your composition has been generated.\n\n"
        )

        message.append("BPM: ${report.tempo}\n")

        message.append(
            "Time signature: " +
                "${report.upperNumeral}/${report.lowerNumeral}\n\n"
        )

        report.tracks.forEach {

            message.append("${it.monsterName}\n")

            message.append(
                "Pitch range: ${it.originalMinimumPitch}–" +
                    "${it.originalMaximumPitch}\n"
            )

            message.append(
                "Octave adjustment: ${it.octaveShift}\n"
            )

            message.append(
                "Adjusted notes: ${it.adjustedNoteCount}/" +
                    "${it.totalNoteCount}\n\n"
            )
        }

        AlertDialog.Builder(this)
            .setTitle("CONVERSION COMPLETE")
            .setMessage(message.toString())
            .setPositiveButton("SAVE MSM") { _, _ ->

                val name = selectedName
                    .substringBeforeLast(".")
                    .ifBlank { "converted_song" }

                msmSaver.launch("$name.msm")
            }
            .setNegativeButton("CLOSE", null)
            .show()

        showStatus(
            "Composition generated successfully.",
            false
        )
    }

    // --------------------------------------------------
    // SAVE FILE
    // --------------------------------------------------

    private fun saveMsm(uri: Uri) {

        val bytes = convertedMsm ?: return

        try {

            contentResolver.openOutputStream(uri)?.use {

                it.write(bytes)

                it.flush()
            } ?: throw IOException(
                "Unable to open destination."
            )

            showStatus(
                "Your MSM composition was saved successfully."
            )

        } catch (e: Exception) {

            showStatus(
                "File saving failed: ${e.message}",
                true
            )
        }
    }

    // --------------------------------------------------
    // SPINNER
    // --------------------------------------------------

    private fun createSpinner(
        values: List<String>,
        selected: String,
        onChanged: (String) -> Unit
    ): Spinner {

        val spinner = Spinner(this)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            values
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinner.adapter = adapter

        spinner.setSelection(
            values.indexOf(selected).coerceAtLeast(0),
            false
        )

        spinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {

                    onChanged(values[position])
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {}
            }

        return spinner
    }

    private fun spinnerColumn(
        heading: String,
        spinner: Spinner
    ): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.CENTER

            addView(
                text(
                    heading,
                    11f,
                    colors.gold,
                    true
                )
            )

            addView(
                spinner,
                matchWrap()
            )
        }
    }

    private fun selectSpinnerValue(
        spinner: Spinner,
        value: String
    ) {

        val adapter = spinner.adapter ?: return

        for (index in 0 until adapter.count) {

            if (adapter.getItem(index).toString() == value) {

                spinner.setSelection(index)

                return
            }
        }
    }

    // --------------------------------------------------
    // SETTINGS DISPLAY
    // --------------------------------------------------

    private fun updateTimeSignature() {

        timeSignatureView.text =
            "$selectedNumerator / $selectedDenominator"
    }

    private fun updateDivisionButtons() {

        showStatus(
            "Smallest note division: 1/$selectedNoteDivision"
        )
    }

    // --------------------------------------------------
    // GENERAL UI HELPERS
    // --------------------------------------------------

    private fun section(title: String): LinearLayout {

        val box = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(15),
                dp(14),
                dp(15),
                dp(15)
            )

            background = rounded(
                colors.panel,
                colors.gold,
                2,
                15
            )
        }

        box.addView(
            text(
                title,
                16f,
                colors.gold,
                true
            )
        )

        box.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {

                setMargins(
                    0,
                    dp(8),
                    0,
                    dp(12)
                )
            }

        return box
    }

    private fun button(
        label: String,
        action: () -> Unit
    ): Button {

        return Button(this).apply {

            text = label

            textSize = 13f

            isAllCaps = false

            setTextColor(colors.background)

            typeface = Typeface.create(
                "sans-serif-rounded",
                Typeface.BOLD
            )

            background = rounded(
                colors.gold,
                colors.cream,
                1,
                12
            )

            setOnClickListener {
                action()
            }
        }
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean
    ): TextView {

        return TextView(this).apply {

            text = value

            textSize = size

            setTextColor(color)

            typeface = Typeface.create(
                "sans-serif-rounded",
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }
    }

    private fun rounded(
        fill: Int,
        stroke: Int,
        width: Int,
        radius: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(fill)

            setStroke(
                dp(width),
                stroke
            )

            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun weightParams() =
        LinearLayout.LayoutParams(
            0,
            dp(52),
            1f
        ).apply {

            setMargins(
                dp(4),
                dp(4),
                dp(4),
                dp(4)
            )
        }

    private fun dp(value: Int): Int {

        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    // --------------------------------------------------
    // FILE NAME
    // --------------------------------------------------

    private fun getFileName(uri: Uri): String? {

        contentResolver.query(
            uri,
            null,
            null,
            null,
            null
        )?.use { cursor ->

            val index = cursor.getColumnIndex(
                OpenableColumns.DISPLAY_NAME
            )

            if (index >= 0 && cursor.moveToFirst()) {

                return cursor.getString(index)
            }
        }

        return uri.lastPathSegment
    }

    // --------------------------------------------------
    // INSTRUMENT NAMES
    // --------------------------------------------------

    private fun instrumentName(program: Int): String {

        return if (program in 0..127) {

            "GM Instrument ${program + 1}"

        } else {

            "Unspecified MIDI instrument"
        }
    }

    // --------------------------------------------------
    // STATUS
    // --------------------------------------------------

    private fun showStatus(
        message: String,
        error: Boolean = false
    ) {

        if (!::statusView.isInitialized) return

        statusView.text = message

        statusView.setTextColor(
            if (error) colors.red else colors.cream
        )
    }
}
