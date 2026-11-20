
package com.dragosdomini.msmtools

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var selectedMidi: Uri? = null
    private var convertedMsm: ByteArray? = null

    private lateinit var statusText: TextView
    private lateinit var selectedFileText: TextView
    private lateinit var noteLengthSpinner: Spinner
    private lateinit var convertButton: Button

    private var singingFont: Typeface? = null
    private var bodyFont: Typeface? = null

    private val backgroundColor = Color.rgb(42, 29, 48)
    private val panelColor = Color.rgb(75, 48, 61)
    private val panelInnerColor = Color.rgb(48, 35, 48)
    private val goldColor = Color.rgb(244, 194, 91)
    private val creamColor = Color.rgb(255, 235, 190)
    private val mutedColor = Color.rgb(197, 174, 157)
    private val greenColor = Color.rgb(100, 153, 76)

    private val openMidi =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                selectedMidi = uri
                convertedMsm = null

                selectedFileText.text =
                    uri.lastPathSegment ?: "MIDI file selected"

                statusText.text = "Your song is ready to convert."
            }
        }

    private val saveMsm =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/octet-stream"
            )
        ) { uri ->
            if (uri != null) {
                saveConvertedFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility = 0

        singingFont = loadFont("fonts/Singing_Monstrous.ttf")
        bodyFont = loadFont("fonts/Fredoka-VariableFont_wdth,wght.ttf")
            ?: loadFont("fonts/Hobeaux-Bold.otf")

        buildInterface()
    }

    private fun loadFont(path: String): Typeface? {
        return try {
            Typeface.createFromAsset(assets, path)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildInterface() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(backgroundColor)
            isFillViewport = true
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        scrollView.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Main title
        val title = TextView(this).apply {
            text = "MSM MIDI TOOLS"
            textSize = 31f
            typeface = singingFont ?: Typeface.DEFAULT_BOLD
            setTextColor(goldColor)
            gravity = Gravity.CENTER
            letterSpacing = 0.025f
        }

        root.addView(
            title,
            matchWidth(
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitle = TextView(this).apply {
            text = "Turn your melodies into Monster Composer songs!"
            textSize = 15f
            typeface = bodyFont ?: Typeface.DEFAULT
            setTextColor(creamColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }

        root.addView(
            subtitle,
            matchWidth()
        )

        // Main wooden-style panel
        val mainPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = roundedBackground(
                color = panelColor,
                stroke = goldColor,
                radius = 22f,
                strokeWidth = 3
            )
        }

        root.addView(
            mainPanel,
            matchWidth()
        )

        val panelTitle = makeText(
            text = "SONG WORKSHOP",
            size = 21f,
            color = goldColor,
            font = singingFont,
            centered = true
        )

        mainPanel.addView(
            panelTitle,
            matchWidth()
        )

        val divider = View(this).apply {
            setBackgroundColor(goldColor)
        }

        val dividerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(2)
        ).apply {
            setMargins(0, dp(14), 0, dp(18))
        }

        mainPanel.addView(divider, dividerParams)

        // MIDI selection section
        val selectCard = makeInnerPanel()

        selectCard.addView(
            makeText(
                "1. CHOOSE YOUR SONG",
                17f,
                creamColor,
                singingFont
            )
        )

        val selectDescription = makeText(
            "Select a MIDI file from your device.",
            14f,
            mutedColor,
            bodyFont
        )

        val descriptionParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(7)
            bottomMargin = dp(14)
        }

        selectCard.addView(
            selectDescription,
            descriptionParams
        )

        val selectButton = makeButton(
            text = "SELECT MIDI FILE",
            color = greenColor
        ) {
            openMidi.launch(
                arrayOf(
                    "audio/midi",
                    "audio/x-midi",
                    "application/octet-stream",
                    "*/*"
                )
            )
        }

        selectCard.addView(
            selectButton,
            matchWidth(height = dp(54))
        )

        selectedFileText = makeText(
            "No song selected yet.",
            13f,
            creamColor,
            bodyFont
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(12), dp(6), dp(4))
        }

        selectCard.addView(
            selectedFileText,
            matchWidth()
        )

        mainPanel.addView(
            selectCard,
            matchWidth()
        )

        addSpace(mainPanel, 16)

        // Note settings section
        val settingsCard = makeInnerPanel()

        settingsCard.addView(
            makeText(
                "2. NOTE SETTINGS",
                17f,
                creamColor,
                singingFont
            )
        )

        settingsCard.addView(
            makeText(
                "Choose the shortest note length to preserve.",
                14f,
                mutedColor,
                bodyFont
            ).apply {
                setPadding(0, dp(7), 0, dp(12))
            },
            matchWidth()
        )

        noteLengthSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "1/8 note",
                    "1/16 note",
                    "1/32 note"
                )
            )
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBackground(
                color = creamColor,
                stroke = goldColor,
                radius = 10f,
                strokeWidth = 2
            )
        }

        settingsCard.addView(
            noteLengthSpinner,
            matchWidth(height = dp(50))
        )

        mainPanel.addView(
            settingsCard,
            matchWidth()
        )

        addSpace(mainPanel, 18)

        // Convert button
        convertButton = makeButton(
            text = "CONVERT TO MSM",
            color = goldColor
        ) {
            convertMidi()
        }

        convertButton.setTextColor(Color.rgb(64, 42, 39))

        mainPanel.addView(
            convertButton,
            matchWidth(height = dp(58))
        )

        addSpace(mainPanel, 18)

        // Status section
        val statusPanel = makeInnerPanel()

        statusPanel.addView(
            makeText(
                "WORKSHOP STATUS",
                16f,
                goldColor,
                singingFont
            )
        )

        statusText = makeText(
            "Choose a MIDI song to get started.",
            14f,
            creamColor,
            bodyFont
        ).apply {
            setPadding(0, dp(10), 0, dp(2))
            gravity = Gravity.CENTER
        }

        statusPanel.addView(
            statusText,
            matchWidth()
        )

        mainPanel.addView(
            statusPanel,
            matchWidth()
        )

        val footer = makeText(
            "MY SINGING MONSTERS COMPOSER TOOL",
            12f,
            mutedColor,
            bodyFont
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }

        root.addView(
            footer,
            matchWidth()
        )

        setContentView(scrollView)
    }

    private fun makeInnerPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(15), dp(14), dp(15))
            background = roundedBackground(
                color = panelInnerColor,
                stroke = Color.rgb(119, 84, 91),
                radius = 15f,
                strokeWidth = 1
            )
        }
    }

    private fun makeText(
        text: String,
        size: Float,
        color: Int,
        font: Typeface?,
        centered: Boolean = false
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = font ?: Typeface.DEFAULT
            if (centered) {
                gravity = Gravity.CENTER
            }
        }
    }

    private fun makeButton(
        text: String,
        color: Int,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            this.text = text
            textSize = 16f
            typeface = singingFont ?: Typeface.DEFAULT_BOLD
            setTextColor(creamColor)
            isAllCaps = false
            background = roundedBackground(
                color = color,
                stroke = goldColor,
                radius = 14f,
                strokeWidth = 2
            )
            elevation = dp(3).toFloat()
            setOnClickListener {
                action()
            }
        }
    }

    private fun roundedBackground(
        color: Int,
        stroke: Int,
        radius: Float,
        strokeWidth: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius.toInt()).toFloat()
            setStroke(dp(strokeWidth), stroke)
        }
    }

    private fun addSpace(
        parent: LinearLayout,
        height: Int
    ) {
        parent.addView(
            View(this),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(height)
            )
        )
    }

    private fun matchWidth(
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun convertMidi() {
        val midiUri = selectedMidi

        if (midiUri == null) {
            statusText.text = "Please select a MIDI file first."
            return
        }

        convertButton.isEnabled = false
        statusText.text = "Reading your song..."

        try {
            val midiBytes = contentResolver
                .openInputStream(midiUri)
                ?.use { it.readBytes() }
                ?: throw IOException("Could not open MIDI file.")

            val parsedMidi = MidiReader.read(midiBytes)

            val noteDivision = when (
                noteLengthSpinner.selectedItemPosition
            ) {
                1 -> 16
                2 -> 32
                else -> 8
            }

            statusText.text = "Converting your monsters' music..."

            val msmBytes = MsmConverter.convert(
                context = this,
                midi = parsedMidi,
                minimumNote = noteDivision
            )

            convertedMsm = msmBytes

            statusText.text =
                "Conversion complete!\n" +
                "${parsedMidi.tracks.size} instrument tracks processed."

            saveMsm.launch("converted_song.msm")

        } catch (e: Exception) {
            convertedMsm = null

            statusText.text =
                "Conversion failed:\n${e.message ?: "Unknown error"}"

        } finally {
            convertButton.isEnabled = true
        }
    }

    private fun saveConvertedFile(uri: Uri) {
        val data = convertedMsm

        if (data == null) {
            statusText.text = "There is no converted song to save."
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(data)
                it.flush()
            } ?: throw IOException("Could not create output file.")

            statusText.text = "Your MSM song was saved successfully!"

        } catch (e: Exception) {
            statusText.text =
                "Could not save the file:\n${e.message}"
        }
    }
}
