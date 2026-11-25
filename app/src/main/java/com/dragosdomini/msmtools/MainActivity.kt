
package com.dragosdomini.msmtools

import android.app.Activity
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import java.io.InputStream

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MSMConverter"
    }

    private var selectedMonsterId: Int = -1
    private var midiPitches: List<Int> = emptyList()
    private var midiUri: Uri? = null

    private lateinit var mainLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var monsterText: TextView
    private lateinit var compatibilityText: TextView
    private lateinit var recommendationLayout: LinearLayout

    private val backgroundColor = Color.rgb(31, 43, 46)
    private val cardColor = Color.rgb(48, 65, 67)
    private val accentColor = Color.rgb(116, 207, 173)
    private val textColor = Color.WHITE

    private val midiPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {
                midiUri = uri
                loadMidi(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            buildInterface()
            loadMonsterDatabase()
        } catch (e: Exception) {
            Log.e(TAG, "Startup error", e)
            showStartupError(e)
        }
    }

    private fun buildInterface() {

        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 24)
            setBackgroundColor(backgroundColor)
        }

        val scrollView = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = TextView(this).apply {
            text = "MSM CONVERTER"
            textSize = 27f
            setTextColor(accentColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, 8)
        }

        val subtitle = TextView(this).apply {
            text = "MIDI composition and monster pitch mapping"
            textSize = 13f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        content.addView(header)
        content.addView(subtitle)

        content.addView(createSection("COMPOSITION"))

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

        statusText = createText(
            "No MIDI composition loaded."
        )

        content.addView(statusText)

        content.addView(createSection("MONSTER ASSIGNMENT"))

        monsterText = createText(
            "No monster selected."
        )

        content.addView(monsterText)

        content.addView(
            createButton("Select monster") {
                showMonsterPicker()
            }
        )

        content.addView(createSection("PITCH COMPATIBILITY"))

        compatibilityText = createText(
            "Import a MIDI file to analyze compatibility."
        )

        content.addView(compatibilityText)

        content.addView(
            createButton("Analyze composition") {
                analyzeSelectedMonster()
            }
        )

        content.addView(createSection("RECOMMENDED MONSTERS"))

        recommendationLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(recommendationLayout)

        content.addView(createSection("CONVERSION"))

        content.addView(
            createButton("Prepare MSM conversion") {
                prepareConversion()
            }
        )

        scrollView.addView(content)

        mainLayout.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(mainLayout)
    }

    private fun loadMonsterDatabase() {

        try {
            val monsters = MonsterMapping.load(this)

            if (monsters.isEmpty()) {
                throw IllegalStateException(
                    "Monster database contains no valid entries."
                )
            }

            statusText.text =
                "Monster database loaded: ${monsters.size} monsters."

            Log.i(TAG, "Loaded ${monsters.size} monsters")

        } catch (e: Exception) {

            Log.e(TAG, "Monster database loading failed", e)

            statusText.text =
                "Database error: ${e.localizedMessage}"

            showMessage(
                "Monster database error",
                e.localizedMessage ?: "Unable to load mdata.json"
            )
        }
    }

    private fun loadMidi(uri: Uri) {

        try {

            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

        } catch (_: Exception) {
            // Some document providers do not support persistence.
        }

        try {

            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException(
                    "Unable to open selected MIDI file."
                )

            input.use {
                val bytes = it.readBytes()

                if (bytes.size < 14 ||
                    String(bytes, 0, 4, Charsets.US_ASCII) != "MThd"
                ) {
                    throw IllegalArgumentException(
                        "The selected file is not a valid MIDI file."
                    )
                }

                midiPitches = readMidiPitches(bytes)
            }

            statusText.text =
                "MIDI loaded successfully.\n" +
                "Notes found: ${midiPitches.size}"

            if (midiPitches.isEmpty()) {
                compatibilityText.text =
                    "No pitched MIDI notes were found."
            } else {
                compatibilityText.text =
                    "MIDI ready for monster compatibility analysis."
            }

        } catch (e: Exception) {

            Log.e(TAG, "MIDI loading failed", e)

            midiPitches = emptyList()

            statusText.text =
                "MIDI loading failed: ${e.localizedMessage}"

            showMessage(
                "MIDI error",
                e.localizedMessage ?: "Unable to read MIDI."
            )
        }
    }

    /**
     * Reads MIDI note-on events.
     *
     * Full timing, tempo and track conversion remain
     * the responsibility of the existing MidiReader.
     */
    private fun readMidiPitches(bytes: ByteArray): List<Int> {

        var position = 0
        val pitches = mutableListOf<Int>()

        fun readInt(): Int {
            require(position + 4 <= bytes.size)
            val value =
                ((bytes[position].toInt() and 255) shl 24) or
                ((bytes[position + 1].toInt() and 255) shl 16) or
                ((bytes[position + 2].toInt() and 255) shl 8) or
                (bytes[position + 3].toInt() and 255)

            position += 4
            return value
        }

        fun readUnsignedShort(): Int {
            require(position + 2 <= bytes.size)
            val value =
                ((bytes[position].toInt() and 255) shl 8) or
                (bytes[position + 1].toInt() and 255)

            position += 2
            return value
        }

        fun readVariableLength(): Int {
            var value = 0
            var count = 0

            while (position < bytes.size && count < 4) {
                val b = bytes[position++].toInt() and 255
                value = (value shl 7) or (b and 127)
                count++

                if ((b and 128) == 0) return value
            }

            throw IllegalArgumentException(
                "Invalid MIDI variable-length value."
            )
        }

        require(String(bytes, 0, 4, Charsets.US_ASCII) == "MThd")

        position = 4

        val headerLength = readInt()
        require(headerLength >= 6)
        require(position + headerLength <= bytes.size)

        val format = readUnsignedShort()
        val trackCount = readUnsignedShort()
        readUnsignedShort()

        require(format in 0..2)
        require(trackCount > 0)

        position = 8 + headerLength

        repeat(trackCount) {

            require(position + 8 <= bytes.size)

            val marker = String(
                bytes,
                position,
                4,
                Charsets.US_ASCII
            )

            require(marker == "MTrk") {
                "Invalid MIDI track header."
            }

            position += 4

            val trackLength = readInt()

            require(trackLength >= 0)
            require(position + trackLength <= bytes.size)

            val trackEnd = position + trackLength

            var runningStatus = 0

            while (position < trackEnd) {

                readVariableLength()

                if (position >= trackEnd) break

                var status = bytes[position].toInt() and 255

                if (status >= 128) {
                    position++
                    runningStatus = status
                } else {
                    require(runningStatus >= 128)
                    status = runningStatus
                }

                when {

                    status == 0xFF -> {
                        position++
                        val length = readVariableLength()
                        require(position + length <= trackEnd)
                        position += length
                    }

                    status == 0xF0 || status == 0xF7 -> {
                        val length = readVariableLength()
                        require(position + length <= trackEnd)
                        position += length
                        runningStatus = 0
                    }

                    status >= 0xF0 -> {
                        throw IllegalArgumentException(
                            "Unsupported MIDI system event."
                        )
                    }

                    else -> {

                        val command = status and 0xF0
                        val dataLength =
                            if (command == 0xC0 || command == 0xD0) 1
                            else 2

                        require(position + dataLength <= trackEnd)

                        val note = bytes[position].toInt() and 255

                        if (command == 0x90 && dataLength == 2) {

                            val velocity =
                                bytes[position + 1].toInt() and 255

                            if (velocity > 0) {
                                pitches.add(note)
                            }
                        }

                        position += dataLength
                    }
                }
            }

            position = trackEnd
        }

        return pitches
    }

    private fun showMonsterPicker() {

        val monsters = MonsterMapping.getAllMonsters()

        if (monsters.isEmpty()) {
            showMessage(
                "No monsters",
                "The monster database has not loaded."
            )
            return
        }

        val names = monsters.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose your monster")
            .setItems(names) { _, index ->

                val selected = monsters[index]

                if (selectedMonsterId == -1) {

                    selectedMonsterId =
                        selected.id.toIntOrNull() ?: -1

                    updateMonsterDisplay()

                } else {

                    requestManualAssignment(selected.id)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestManualAssignment(monsterId: String) {

        val proposed = monsterId.toIntOrNull() ?: return

        val monster = MonsterMapping.getMonster(monsterId)
            ?: return

        AlertDialog.Builder(this)
            .setTitle("Change monster?")
            .setMessage(
                "Change the selected monster to ${monster.name}?\n\n" +
                "The current assignment will remain unchanged " +
                "until you approve."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Approve") { _, _ ->

                selectedMonsterId = proposed

                updateMonsterDisplay()
                analyzeSelectedMonster()
            }
            .show()
    }

    private fun updateMonsterDisplay() {

        val monster = MonsterMapping.getMonster(
            selectedMonsterId.toString()
        )

        monsterText.text =
            "Selected monster: ${monster?.name ?: "None"}"
    }

    private fun analyzeSelectedMonster() {

        if (midiPitches.isEmpty()) {
            showMessage(
                "No MIDI notes",
                "Import a MIDI composition first."
            )
            return
        }

        if (selectedMonsterId < 0) {
            showMonsterPicker()
            return
        }

        val result = MonsterMapping.analyzeSelectedMonster(
            selectedMonsterId.toString(),
            midiPitches
        )

        if (result == null) {
            showMessage(
                "Monster unavailable",
                "The selected monster could not be found."
            )
            return
        }

        compatibilityText.text = buildString {

            append("Monster: ${result.monster.name}\n")
            append("Compatibility: ${result.score}%\n")
            append("Compatible notes: ")
            append("${result.compatibleNotes}/${result.totalNotes}\n")
            append("Incompatible notes: ")
            append("${result.incompatibleNotes}\n")
            append("Octave adjustment: ")
            append("${result.octaveShift} semitones\n\n")
            append(result.explanation)
        }

        showAlternatives()
    }

    private fun showAlternatives() {

        recommendationLayout.removeAllViews()

        val alternatives = MonsterMapping.rankAlternatives(
            midiPitches,
            selectedMonsterId.toString()
        )

        alternatives.take(10).forEachIndexed { index, result ->

            val card = TextView(this).apply {

                text = buildString {
                    append("#${index + 1} ${result.monster.name}\n")
                    append("Compatibility: ${result.score}%\n")
                    append("Notes: ")
                    append("${result.compatibleNotes}/${result.totalNotes}\n")
                    append("Octave adjustment: ")
                    append("${result.octaveShift}\n")
                    append(result.explanation)
                    append("\n\nTap to review assignment")
                }

                textSize = 14f
                setTextColor(textColor)
                setPadding(18, 18, 18, 18)
                background = rounded(cardColor, 16f)
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    requestAlternativeApproval(result)
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            params.bottomMargin = 12

            recommendationLayout.addView(card, params)
        }
    }

    private fun requestAlternativeApproval(
        result: MonsterCompatibility
    ) {

        val proposedId =
            result.monster.id.toIntOrNull() ?: return

        val warning = if (result.accommodatesEntireTrack) {
            "Every note fits this monster."
        } else {
            "${result.incompatibleNotes} notes remain incompatible."
        }

        AlertDialog.Builder(this)
            .setTitle("Approve monster change?")
            .setMessage(
                "Current monster: ${
                    MonsterMapping.getMonster(
                        selectedMonsterId.toString()
                    )?.name ?: "Unknown"
                }\n\n" +
                "New monster: ${result.monster.name}\n" +
                "Compatibility: ${result.score}%\n" +
                "Octave adjustment: ${result.octaveShift}\n\n" +
                warning
            )
            .setNegativeButton("Keep current", null)
            .setPositiveButton("Approve change") { _, _ ->

                selectedMonsterId = proposedId

                updateMonsterDisplay()
                analyzeSelectedMonster()
            }
            .show()
    }

    private fun prepareConversion() {

        if (midiPitches.isEmpty()) {
            showMessage(
                "Missing MIDI",
                "Import a MIDI composition first."
            )
            return
        }

        if (selectedMonsterId < 0) {
            showMessage(
                "Missing monster",
                "Select a monster first."
            )
            return
        }

        val result = MsmPitchConverter.convertTrack(
            monsterId = selectedMonsterId,
            midiPitches = midiPitches,
            allowOctaveAdjustment = true,
            allowNearestPitchFallback = false
        )

        if (!result.success) {

            showMessage(
                "Conversion unavailable",
                result.message
            )

            return
        }

        showMessage(
            "Pitch conversion verified",
            "${result.monsterName}\n\n" +
                "${result.convertedNotes.size} notes converted.\n" +
                "Octave adjustment: ${result.octaveShift}\n\n" +
                "Pitch validation succeeded. " +
                "The existing MSM serialization step still needs to " +
                "generate the final song file."
        )
    }

    private fun createSection(title: String): TextView =
        TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(0, 22, 0, 10)
        }

    private fun createText(value: String): TextView =
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

            params.bottomMargin = 10
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

    private fun showStartupError(error: Exception) {

        Log.e(TAG, "Fatal startup error", error)

        AlertDialog.Builder(this)
            .setTitle("MSM Converter startup error")
            .setMessage(
                error.localizedMessage
                    ?: "An unexpected startup error occurred."
            )
            .setPositiveButton("Close", null)
            .show()
    }
}
