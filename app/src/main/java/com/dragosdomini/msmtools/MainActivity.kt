package com.dragosdomini.msmtools

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var selectedMidi: Uri? = null
    private var selectedName: String? = null
    private var convertedMsm: ByteArray? = null
    private var currentMidi: ParsedMidi? = null

    private var selectedNoteDivision = 8
    private var selectedNumerator = 4
    private var selectedDenominator = 4

    private val monsterAssignments = mutableMapOf<Int, Int>()
    private val skippedParts = mutableSetOf<Int>()
    private var currentPartIndex = 0
    private var allMonsters: List<MonsterMapping.Monster> = emptyList()

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var fileText: TextView
    private lateinit var convertButton: Button
    private lateinit var numeratorText: TextView
    private lateinit var denominatorText: TextView
    private lateinit var divisionText: TextView

    private var loadingAnimator: ValueAnimator? = null
    private val cream = Color.rgb(255, 238, 204)
    private val gold = Color.rgb(238, 185, 85)
    private val dark = Color.rgb(35, 23, 47)
    private val panel = Color.rgb(74, 49, 91)

    private val openMidi = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedMidi = uri
            selectedName = queryName(uri)
            fileText.text = selectedName ?: "MIDI selected"
            status("MIDI file ready. Choose your composition settings.")
            convertButton.isEnabled = true
        }
    }

    private val saveMsm = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) saveConvertedFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = dark
        window.navigationBarColor = dark
        selectedNumerator = savedInstanceState?.getInt("timeNumerator") ?: 4
        selectedDenominator = savedInstanceState?.getInt("timeDenominator") ?: 4
        selectedNoteDivision = savedInstanceState?.getInt("noteDivision") ?: 8
        buildInterface()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("timeNumerator", selectedNumerator)
        outState.putInt("timeDenominator", selectedDenominator)
        outState.putInt("noteDivision", selectedNoteDivision)
        super.onSaveInstanceState(outState)
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(dark)
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        val title = text("MSM MIDI CONVERTER", 27f, gold, true)
        title.gravity = Gravity.CENTER
        root.addView(title, matchWrap())
        val subtitle = text("Turn MIDI arrangements into Composer songs", 14f, cream, false)
        subtitle.gravity = Gravity.CENTER
        subtitle.setPadding(0, dp(5), 0, dp(20))
        root.addView(subtitle, matchWrap())

        val filePanel = section("SONG FILE")
        fileText = text("No MIDI file selected", 15f, cream, false)
        fileText.setPadding(0, dp(8), 0, dp(12))
        filePanel.addView(fileText)
        filePanel.addView(actionButton("SELECT MIDI FILE") {
            openMidi.launch(arrayOf("audio/midi", "audio/x-midi", "application/octet-stream"))
        })
        root.addView(filePanel, matchWrap())

        val settings = section("COMPOSITION SETTINGS")
        settings.addView(text("Time signature", 16f, gold, true))
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        numeratorText = text("", 20f, cream, true)
        denominatorText = text("", 20f, cream, true)
        val numeratorControls = selectorColumn(
            "TOP NUMBER", (1..12).map(Int::toString), selectedNumerator.toString()
        ) { selectedNumerator = it.toInt(); updateSelectors() }
        val denominatorControls = selectorColumn(
            "BOTTOM NUMBER", listOf("2", "4", "8"), selectedDenominator.toString()
        ) { selectedDenominator = it.toInt(); updateSelectors() }
        timeRow.addView(numeratorControls, LinearLayout.LayoutParams(0, dp(150), 1f))
        timeRow.addView(denominatorControls, LinearLayout.LayoutParams(0, dp(150), 1f))
        settings.addView(timeRow)

        settings.addView(text("Smallest note division", 16f, gold, true).apply {
            setPadding(0, dp(12), 0, dp(5))
        })
        divisionText = text("", 16f, cream, true)
        val divisions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(8, 16, 32).forEach { value ->
            divisions.addView(actionButton("1/$value") {
                selectedNoteDivision = value
                updateSelectors()
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        settings.addView(divisions)
        settings.addView(divisionText)
        root.addView(settings, matchWrap())

        convertButton = actionButton("CONVERT MIDI TO MSM") { convertMidi() }
        convertButton.isEnabled = false
        root.addView(convertButton, matchWrap())

        statusText = text("Select a MIDI file to begin.", 14f, cream, false)
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(dp(8), dp(16), dp(8), dp(10))
        root.addView(statusText, matchWrap())
        updateSelectors()
    }

    private fun selectorColumn(
        heading: String,
        values: List<String>,
        selected: String,
        onSelect: (String) -> Unit
    ): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        column.addView(text(heading, 11f, gold, true))
        column.addView(text(selected, 25f, cream, true))
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        values.forEach { value ->
            choices.addView(actionButton(value) { onSelect(value) },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(dp(2), dp(5), dp(2), 0)
                })
        }
        column.addView(choices)
        return column
    }

    private fun updateSelectors() {
        numeratorText.text = selectedNumerator.toString()
        denominatorText.text = selectedDenominator.toString()
        divisionText.text = "Selected: 1/$selectedNoteDivision"
        // Rebuild selector labels so the active value is always visible.
        numeratorText.text = "$selectedNumerator / $selectedDenominator"
    }

    private fun convertMidi() {
        val uri = selectedMidi ?: return
        convertButton.isEnabled = false
        status("Reading MIDI…")
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("Could not read the selected MIDI file.")
                val parsed = MidiReader.read(bytes)
                if (parsed.tracks.isEmpty()) throw IOException("No note tracks were found.")
                val configuredMidi = parsed.copy(
                    timeNumerator = selectedNumerator,
                    timeDenominator = selectedDenominator
                )
                val monsters = MonsterMapping.getAllMonsters(this)
                runOnUiThread {
                    currentMidi = configuredMidi
                    allMonsters = monsters
                    monsterAssignments.clear()
                    skippedParts.clear()
                    currentPartIndex = 0
                    showMonsterSelection()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status("Could not read MIDI:\n${e.message}", true)
                    convertButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun showMonsterSelection() {
        val midi = currentMidi ?: return
        while (currentPartIndex < midi.tracks.size &&
            currentPartIndex in skippedParts
        ) currentPartIndex++

        if (currentPartIndex >= midi.tracks.size) {
            showCompositionReview()
            return
        }

        val track = midi.tracks[currentPartIndex]
        val suggestions = MonsterMapping.findMonsterSuggestions(
            this, track.program, track.bank
        )
        val best = suggestions.firstOrNull()
        val assignedId = monsterAssignments[currentPartIndex]
        val chosen = assignedId?.let { id -> allMonsters.firstOrNull { it.id == id } } ?: best
        val message = buildString {
            append("Part ${currentPartIndex + 1} of ${midi.tracks.size}\n")
            append(track.name)
            append("\n\nMIDI instrument: ${instrumentName(track.program)}")
            append("\nProgram: ${track.program}   Bank: ${track.bank}")
            append("\nNotes: ${track.notes.size}")
            append("\n\n")
            if (best != null) {
                append("Best automatic match: ${best.name}")
                append("\nPlease approve this assignment or choose another monster.")
            } else {
                append("No automatic monster match was found.")
                append("\nChoose a monster manually before continuing.")
            }
        }

        val options = allMonsters.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose monster")
            .setMessage(message)
            .setSingleChoiceItems(options, allMonsters.indexOfFirst { it.id == chosen?.id }) { dialog, which ->
                val monster = allMonsters[which]
                monsterAssignments[currentPartIndex] = monster.id
                (dialog as AlertDialog).setTitle("Selected: ${monster.name}")
            }
            .setNeutralButton("SKIP PART") { _, _ ->
                skippedParts.add(currentPartIndex)
                currentPartIndex++
                showMonsterSelection()
            }
            .setNegativeButton("CANCEL") { _, _ ->
                convertButton.isEnabled = true
                status("Assignment cancelled.")
            }
            .setPositiveButton("ACCEPT & NEXT") { _, _ ->
                val selected = monsterAssignments[currentPartIndex]
                    ?: chosen?.id
                if (selected == null) {
                    status("Choose a monster for this part before continuing.", true)
                    showMonsterSelection()
                } else {
                    monsterAssignments[currentPartIndex] = selected
                    currentPartIndex++
                    showMonsterSelection()
                }
            }
            .setOnCancelListener {
                convertButton.isEnabled = true
            }
            .show()
    }

    private fun showCompositionReview() {
        val midi = currentMidi ?: return
        val review = midi.tracks.mapIndexed { index, track ->
            val monster = allMonsters.firstOrNull { it.id == monsterAssignments[index] }
            "${index + 1}. ${track.name} → ${monster?.name ?: "Skipped"}"
        }.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Review composition")
            .setMessage(
                "Time signature: $selectedNumerator/$selectedDenominator\n" +
                    "Note division: 1/$selectedNoteDivision\n\n$review"
            )
            .setNegativeButton("BACK") { _, _ ->
                currentPartIndex = 0
                showMonsterSelection()
            }
            .setPositiveButton("GENERATE MSM") { _, _ ->
                startConversion()
            }
            .show()
    }

    private fun startConversion() {
        val midi = currentMidi ?: return
        val assignments = monsterAssignments.toMap()
        status("Generating MSM file…")
        convertButton.isEnabled = false
        Thread {
            try {
                val output = MsmConverter.convert(
                    context = this,
                    midi = midi,
                    minimumNote = selectedNoteDivision,
                    monsterAssignments = assignments
                )
                if (output.isEmpty()) throw IOException("The converter returned an empty file.")
                runOnUiThread {
                    convertedMsm = output
                    convertButton.isEnabled = true
                    status("Conversion complete. Choose where to save your MSM song.")
                    val name = (selectedName ?: "converted_song")
                        .substringBeforeLast('.').ifBlank { "converted_song" }
                    saveMsm.launch("$name.msm")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    convertButton.isEnabled = true
                    status("Conversion failed:\n${e.message}", true)
                }
            }
        }.start()
    }

    private fun saveConvertedFile(uri: Uri) {
        val bytes = convertedMsm ?: return
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(bytes)
                it.flush()
            } ?: throw IOException("Could not open the destination file.")
            status("MSM song saved successfully.")
        } catch (e: Exception) {
            status("Could not save file:\n${e.message}", true)
        }
    }

    private fun section(heading: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(14))
            background = rounded(panel, gold, 2, 14)
        }
        box.addView(text(heading, 17f, gold, true))
        return box.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, dp(12)) }
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(dark)
            typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
            background = rounded(gold, cream, 1, 12)
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView {
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

    private fun rounded(fill: Int, stroke: Int, width: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(width), stroke)
            cornerRadius = dp(radius).toFloat()
        }

    private fun status(message: String, error: Boolean = false) {
        if (::statusText.isInitialized) {
            statusText.text = message
            statusText.setTextColor(if (error) Color.rgb(255, 135, 120) else cream)
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    private fun instrumentName(program: Int): String =
        if (program in 0..127) "GM instrument ${program + 1}" else "Instrument not specified"

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        loadingAnimator?.cancel()
        loadingAnimator = null
        super.onDestroy()
    }
}
