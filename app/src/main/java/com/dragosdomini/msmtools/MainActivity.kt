
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

    // Classic MSM workshop palette
    private val backgroundColor = Color.rgb(48, 65, 43)
    private val woodLight = Color.rgb(173, 121, 75)
    private val woodColor = Color.rgb(119, 75, 46)
    private val woodDark = Color.rgb(65, 40, 30)

    private val panelColor = Color.rgb(91, 59, 40)
    private val panelInnerColor = Color.rgb(235, 211, 161)

    private val goldColor = Color.rgb(242, 190, 83)
    private val goldDark = Color.rgb(151, 101, 42)

    private val creamColor = Color.rgb(255, 240, 204)
    private val parchmentText = Color.rgb(81, 55, 36)
    private val mutedColor = Color.rgb(205, 180, 135)

    private val greenColor = Color.rgb(99, 145, 65)
    private val greenDark = Color.rgb(49, 91, 43)

    private val openMidi =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                selectedMidi = uri
                convertedMsm = null

                selectedFileText.text =
                    uri.lastPathSegment ?: "MIDI file selected"

                statusText.text =
                    "Your song is ready to convert."
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
        window.navigationBarColor = woodDark
        window.decorView.systemUiVisibility = 0

        singingFont = loadFont("fonts/Singing_Monstrous.ttf")

        bodyFont =
            loadFont("fonts/Fredoka-VariableFont_wdth,wght.ttf")
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

            setPadding(
                dp(18),
                dp(24),
                dp(18),
                dp(32)
            )
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
            textSize = 32f

            typeface = singingFont ?: Typeface.DEFAULT_BOLD

            setTextColor(goldColor)
            gravity = Gravity.CENTER
            letterSpacing = 0.025f

            setShadowLayer(
                dp(3).toFloat(),
                0f,
                dp(3).toFloat(),
                woodDark
            )
        }

        root.addView(
            title,
            matchWidth()
        )

        val subtitle = TextView(this).apply {
            text = "Turn your melodies into Monster Composer songs!"
            textSize = 15f
            typeface = bodyFont ?: Typeface.DEFAULT

            setTextColor(creamColor)
            gravity = Gravity.CENTER

            setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(22)
            )
        }

        root.addView(
            subtitle,
            matchWidth()
        )

        // Main wooden workshop panel

        val mainPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(15),
                dp(19),
                dp(15),
                dp(19)
            )

            background = woodenBackground(
                radius = 23,
                border = goldColor,
                borderWidth = 4
            )

            elevation = dp(7).toFloat()
        }

        root.addView(
            mainPanel,
            matchWidth()
        )

        // Workshop title

        val panelTitle = makeText(
            text = "SONG WORKSHOP",
            size = 23f,
            color = goldColor,
            font = singingFont,
            centered = true
        )

        mainPanel.addView(
            panelTitle,
            matchWidth()
        )

        val divider = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    woodDark,
                    goldColor,
                    woodDark
                )
            )
        }

        val dividerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(3)
        ).apply {
            setMargins(
                dp(4),
                dp(15),
                dp(4),
                dp(18)
            )
        }

        mainPanel.addView(
            divider,
            dividerParams
        )

        // MIDI selection section

        val selectCard = makeInnerPanel()

        selectCard.addView(
            makeText(
                "1. CHOOSE YOUR SONG",
                18f,
                parchmentText,
                singingFont
            )
        )

        val selectDescription = makeText(
            "Select a MIDI file from your device.",
            14f,
            parchmentText,
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
            matchWidth(height = dp(55))
        )

        selectedFileText = makeText(
            "No song selected yet.",
            13f,
            parchmentText,
            bodyFont
        ).apply {
            gravity = Gravity.CENTER

            setPadding(
                dp(6),
                dp(12),
                dp(6),
                dp(4)
            )
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
                18f,
                parchmentText,
                singingFont
            )
        )

        settingsCard.addView(
            makeText(
                "Choose the shortest note length to preserve.",
                14f,
                parchmentText,
                bodyFont
            ).apply {
                setPadding(
                    0,
                    dp(7),
                    0,
                    dp(12)
                )
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

            setPadding(
                dp(10),
                dp(5),
                dp(10),
                dp(5)
            )

            background = roundedBackground(
                color = creamColor,
                stroke = goldDark,
                radius = 10,
                strokeWidth = 2
            )
        }

        settingsCard.addView(
            noteLengthSpinner,
            matchWidth(height = dp(52))
        )

        mainPanel.addView(
            settingsCard,
            matchWidth()
        )

        addSpace(mainPanel, 18)

        // Conversion button

        convertButton = makeButton(
            text = "CONVERT TO MSM",
            color = goldColor
        ) {
            convertMidi()
        }

        convertButton.setTextColor(
            Color.rgb(69, 43, 28)
        )

        convertButton.textSize = 19f

        mainPanel.addView(
            convertButton,
            matchWidth(height = dp(62))
        )

        addSpace(mainPanel, 18)

        // Workshop status

        val statusPanel = makeInnerPanel()

        statusPanel.addView(
            makeText(
                "WORKSHOP STATUS",
                18f,
                parchmentText,
                singingFont
            )
        )

        statusText = makeText(
            "Choose a MIDI song to get started.",
            14f,
            parchmentText,
            bodyFont
        ).apply {
            setPadding(
                0,
                dp(12),
                0,
                dp(4)
            )

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

        // Footer

        val footer = makeText(
            "MY SINGING MONSTERS COMPOSER TOOL",
            12f,
            creamColor,
            bodyFont
        ).apply {
            gravity = Gravity.CENTER

            setPadding(
                0,
                dp(22),
                0,
                0
            )
        }

        root.addView(
            footer,
            matchWidth()
        )

        setContentView(scrollView)
    }

    // Wooden panel background

    private fun woodenBackground(
        radius: Int,
        border: Int,
        borderWidth: Int
    ): GradientDrawable {

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                woodLight,
                woodColor,
                woodDark
            )
        ).apply {

            cornerRadius = dp(radius).toFloat()

            setStroke(
                dp(borderWidth),
                border
            )
        }
    }

    // Parchment inner panels

    private fun makeInnerPanel(): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(14),
                dp(15),
                dp(14),
                dp(15)
            )

            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.rgb(248, 228, 185),
                    panelInnerColor,
                    Color.rgb(213, 184, 132)
                )
            ).apply {

                cornerRadius = dp(15).toFloat()

                setStroke(
                    dp(3),
                    goldDark
                )
            }

            elevation = dp(3).toFloat()
        }
    }

    // Text creation

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

    // MSM-style dimensional buttons

    private fun makeButton(
        text: String,
        color: Int,
        action: () -> Unit
    ): Button {

        return Button(this).apply {

            this.text = text

            textSize = 16f

            typeface =
                singingFont ?: Typeface.DEFAULT_BOLD

            setTextColor(creamColor)

            isAllCaps = false

            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    lighten(color, 35),
                    color,
                    greenDark
                )
            ).apply {

                cornerRadius = dp(14).toFloat()

                setStroke(
                    dp(3),
                    goldColor
                )
            }

            elevation = dp(5).toFloat()

            setPadding(
                dp(12),
                dp(5),
                dp(12),
                dp(5)
            )

            setOnClickListener {
                if (isEnabled) action()
            }
        }
    }

    private fun lighten(color: Int, amount: Int): Int {

        val red = (Color.red(color) + amount).coerceAtMost(255)
        val green = (Color.green(color) + amount).coerceAtMost(255)
        val blue = (Color.blue(color) + amount).coerceAtMost(255)

        return Color.rgb(red, green, blue)
    }

    private fun roundedBackground(
        color: Int,
        stroke: Int,
        radius: Int,
        strokeWidth: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(color)

            cornerRadius = dp(radius).toFloat()

            setStroke(
                dp(strokeWidth),
                stroke
            )
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

        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    // MIDI conversion — original converter integration preserved

    private fun convertMidi() {

        val midiUri = selectedMidi

        if (midiUri == null) {
            statusText.text =
                "Please select a MIDI file first."
            return
        }

        convertButton.isEnabled = false

        statusText.text = "Reading your song..."

        try {

            val midiBytes = contentResolver
                .openInputStream(midiUri)
                ?.use { it.readBytes() }
                ?: throw IOException(
                    "Could not open MIDI file."
                )

            val parsedMidi = MidiReader.read(midiBytes)

            val noteDivision = when (
                noteLengthSpinner.selectedItemPosition
            ) {
                1 -> 16
                2 -> 32
                else -> 8
            }

            statusText.text =
                "Converting your monsters' music..."

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
                "Conversion failed:\n" +
                (e.message ?: "Unknown error")

        } finally {

            convertButton.isEnabled = true
        }
    }

    // Save converted MSM file

    private fun saveConvertedFile(uri: Uri) {

        val data = convertedMsm

        if (data == null) {

            statusText.text =
                "There is no converted song to save."

            return
        }

        try {

            contentResolver.openOutputStream(uri)?.use {

                it.write(data)
                it.flush()

            } ?: throw IOException(
                "Could not create output file."
            )

            statusText.text =
                "Your MSM song was saved successfully!"

        } catch (e: Exception) {

            statusText.text =
                "Could not save the file:\n${e.message}"
        }
    }
}
