
package com.dragosdomini.msmtools

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var selectedMidi: Uri? = null
    private var convertedMsm: ByteArray? = null
    private var selectedFileName = "converted_song"

    private lateinit var statusText: TextView
    private lateinit var noteLengthSpinner: Spinner

    private val openMidi =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                selectedMidi = uri
                selectedFileName = "converted_song"
                statusText.text = "MIDI selected: ${uri.lastPathSegment ?: "file"}"
            }
        }

    private val saveMsm =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                saveConvertedFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "MSM MIDI Tools"
            textSize = 26f
        }

        val description = TextView(this).apply {
            text = "Convert MIDI songs into My Singing Monsters Composer files."
            textSize = 15f
            setPadding(0, 12, 0, 24)
        }

        val selectButton = Button(this).apply {
            text = "Select MIDI File"
            setOnClickListener {
                openMidi.launch(
                    arrayOf("audio/midi", "audio/x-midi", "application/octet-stream", "*/*")
                )
            }
        }

        val noteLabel = TextView(this).apply {
            text = "Minimum note length"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        }

        noteLengthSpinner = Spinner(this)
        val noteOptions = listOf(
            "1/8 note",
            "1/16 note",
            "1/32 note"
        )

        noteLengthSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            noteOptions
        )

        val convertButton = Button(this).apply {
            text = "Convert MIDI to MSM"
            setOnClickListener {
                convertMidi()
            }
        }

        statusText = TextView(this).apply {
            text = "Choose a MIDI file to begin."
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }

        layout.addView(title)
        layout.addView(description)
        layout.addView(selectButton)
        layout.addView(noteLabel)
        layout.addView(noteLengthSpinner)
        layout.addView(convertButton)
        layout.addView(statusText)

        setContentView(layout)
    }

    private fun convertMidi() {
        val midiUri = selectedMidi

        if (midiUri == null) {
            statusText.text = "Please select a MIDI file first."
            return
        }

        statusText.text = "Reading MIDI file..."

        try {
            val midiBytes = contentResolver.openInputStream(midiUri)?.use {
                it.readBytes()
            } ?: throw IOException("Could not open MIDI file.")

            val parsedMidi = MidiReader.read(midiBytes)

            val noteDivision = when (noteLengthSpinner.selectedItemPosition) {
                1 -> 16
                2 -> 32
                else -> 8
            }

            statusText.text = "Converting song..."

            val msmBytes = MsmConverter.convert(
                context = this,
                midi = parsedMidi,
                minimumNote = noteDivision
            )

            convertedMsm = msmBytes

            statusText.text =
                "Conversion complete! ${parsedMidi.tracks.size} MIDI tracks processed."

            saveMsm.launch("converted_song.msm")

        } catch (e: Exception) {
            convertedMsm = null
            statusText.text = "Conversion failed: ${e.message ?: "Unknown error"}"
        }
    }

    private fun saveConvertedFile(uri: Uri) {
        val data = convertedMsm

        if (data == null) {
            statusText.text = "There is no converted file to save."
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(data)
                it.flush()
            } ?: throw IOException("Could not create output file.")

            statusText.text = "MSM file saved successfully."

        } catch (e: Exception) {
            statusText.text = "Could not save file: ${e.message}"
        }
    }
}

