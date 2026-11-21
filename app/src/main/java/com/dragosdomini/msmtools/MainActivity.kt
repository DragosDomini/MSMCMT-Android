
package com.dragosdomini.msmtools

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatTextView
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

    // Classic MSM colours

    private val backgroundColor = Color.rgb(24, 27, 24)
    private val woodDark = Color.rgb(55, 35, 25)
    private val woodOutline = Color.rgb(32, 19, 12)

    private val parchmentColor = Color.rgb(235, 211, 161)
    private val parchmentDark = Color.rgb(210, 178, 125)

    private val goldColor = Color.rgb(246, 196, 82)
    private val goldDark = Color.rgb(151, 98, 34)

    private val creamColor = Color.rgb(255, 242, 207)
    private val parchmentText = Color.rgb(74, 48, 30)

    private val greenColor = Color.rgb(112, 168, 62)
    private val greenDark = Color.rgb(46, 88, 34)

    // MIDI selection

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

    // MSM saving

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

        singingFont = loadFont(
            "fonts/Singing_Monstrous.ttf"
        )

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

    // --------------------------------------------------
    // MAIN INTERFACE
    // --------------------------------------------------

    private fun buildInterface() {

        val container = FrameLayout(this).apply {
            setBackgroundColor(backgroundColor)
        }

        // Actual repository artwork.
        // CENTER_CROP preserves the original image proportions.

        val backgroundImage = ImageView(this).apply {

            setBackgroundColor(backgroundColor)

            scaleType = ImageView.ScaleType.CENTER_CROP

            setImageDrawable(
                loadPanelBackground()
            )

            contentDescription = "Classic MSM panel background"
        }

        container.addView(
            backgroundImage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Scrollable content

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            setPadding(
                dp(22),
                dp(28),
                dp(22),
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

        container.addView(
            scrollView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // TITLE

        val title = OutlinedTextView(this).apply {

            text = "MSM MIDI TOOLS"
            textSize = 31f

            typeface = singingFont ?: Typeface.DEFAULT_BOLD

            setTextColor(goldColor)

            strokeColor = woodOutline
            strokeWidthPx = dp(5).toFloat()

            gravity = Gravity.CENTER
            letterSpacing = 0.025f
        }

        root.addView(title, matchWidth())

        val subtitle = OutlinedTextView(this).apply {

            text = "Turn your melodies into Monster Composer songs!"

            textSize = 14f

            typeface = bodyFont ?: Typeface.DEFAULT

            setTextColor(creamColor)

            strokeColor = woodOutline
            strokeWidthPx = dp(3).toFloat()

            gravity = Gravity.CENTER

            setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(24)
            )
        }

        root.addView(subtitle, matchWidth())

        // WORKSHOP CONTENT

        val workshop = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(15),
                dp(18),
                dp(15),
                dp(18)
            )

            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(235, 65, 43, 34),
                    Color.argb(245, 39, 28, 25)
                )
            ).apply {

                cornerRadius = dp(19).toFloat()

                setStroke(
                    dp(3),
                    goldColor
                )
            }

            elevation = dp(8).toFloat()
        }

        root.addView(
            workshop,
            matchWidth()
        )

        val workshopTitle = makeOutlinedText(
            "SONG WORKSHOP",
            23f,
            goldColor,
            singingFont,
            dp(4)
        )

        workshopTitle.gravity = Gravity.CENTER

        workshop.addView(
            workshopTitle,
            matchWidth()
        )

        val separator = View(this).apply {

            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    woodDark,
                    goldColor,
                    woodDark
                )
            )
        }

        workshop.addView(
            separator,
            LinearLayout.LayoutParams(
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
        )

        // MIDI FILE CARD

        val fileCard = makeInnerPanel()

        fileCard.addView(
            makeStepHeader("1. CHOOSE YOUR SONG")
        )

        fileCard.addView(
            makeText(
                "Select a MIDI file from your device.",
                14f,
                parchmentText,
                bodyFont
            ).apply {
                setPadding(
                    0,
                    dp(7),
                    0,
                    dp(13)
                )
            },
            matchWidth()
        )

        val selectButton = makeButton(
            "SELECT MIDI FILE",
            greenColor,
            creamColor
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

        fileCard.addView(
            selectButton,
            matchWidth(dp(55))
        )

        selectedFileText = makeText(
            "No song selected yet.",
            13f,
            parchmentText,
            bodyFont
        ).apply {

            gravity = Gravity.CENTER

            background = recessedBackground()

            setPadding(
                dp(10),
                dp(12),
                dp(10),
                dp(12)
            )
        }

        fileCard.addView(
            selectedFileText,
            matchWidth()
        )

        workshop.addView(
            fileCard,
            matchWidth()
        )

        addSpace(workshop, 15)

        // NOTE SETTINGS

        val settingsCard = makeInnerPanel()

        settingsCard.addView(
            makeStepHeader("2. NOTE SETTINGS")
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

            background = recessedBackground()

            setPadding(
                dp(12),
                dp(6),
                dp(12),
                dp(6)
            )
        }

        settingsCard.addView(
            noteLengthSpinner,
            matchWidth(dp(52))
        )

        workshop.addView(
            settingsCard,
            matchWidth()
        )

        addSpace(workshop, 17)

        // CONVERSION BUTTON

        convertButton = makeButton(
            "CONVERT TO MSM",
            goldColor,
            Color.rgb(66, 42, 25)
        ) {
            convertMidi()
        }

        convertButton.textSize = 19f

        workshop.addView(
            convertButton,
            matchWidth(dp(62))
        )

        addSpace(workshop, 17)

        // STATUS PANEL

        val statusCard = makeInnerPanel()

        statusCard.addView(
            makeStepHeader("WORKSHOP STATUS")
        )

        statusText = makeText(
            "Choose a MIDI song to get started.",
            14f,
            parchmentText,
            bodyFont
        ).apply {

            gravity = Gravity.CENTER

            background = recessedBackground()

            setPadding(
                dp(10),
                dp(14),
                dp(10),
                dp(14)
            )
        }

        statusCard.addView(
            statusText,
            matchWidth()
        )

        workshop.addView(
            statusCard,
            matchWidth()
        )

        // FOOTER

        val footer = makeOutlinedText(
            "MY SINGING MONSTERS COMPOSER TOOL",
            12f,
            creamColor,
            bodyFont,
            dp(2)
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

        setContentView(container)
    }

    // --------------------------------------------------
    // PANEL IMAGE LOADER
    // --------------------------------------------------

    private fun loadPanelBackground(): BitmapDrawable? {

        return try {

            assets.open("images/panel.png").use { stream ->

                val bitmap = BitmapFactory.decodeStream(stream)
                    ?: return null

                BitmapDrawable(resources, bitmap).apply {

                    // The ImageView handles scaling.
                    // Do not stretch the BitmapDrawable itself.

                }
            }

        } catch (e: Exception) {

            null
        }
    }

    // --------------------------------------------------
    // UI COMPONENTS
    // --------------------------------------------------

    private fun makeInnerPanel(): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(13),
                dp(14),
                dp(13),
                dp(14)
            )

            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.rgb(249, 230, 188),
                    parchmentColor,
                    parchmentDark
                )
            ).apply {

                cornerRadius = dp(14).toFloat()

                setStroke(
                    dp(3),
                    goldDark
                )
            }

            elevation = dp(4).toFloat()
        }
    }

    private fun makeStepHeader(text: String): TextView {

        return makeText(
            text,
            18f,
            parchmentText,
            singingFont
        )
    }

    private fun makeText(
        text: String,
        size: Float,
        color: Int,
        font: Typeface?
    ): TextView {

        return TextView(this).apply {

            this.text = text
            textSize = size

            setTextColor(color)

            typeface = font ?: Typeface.DEFAULT
        }
    }

    private fun makeOutlinedText(
        text: String,
        size: Float,
        color: Int,
        font: Typeface?,
        stroke: Int
    ): OutlinedTextView {

        return OutlinedTextView(this).apply {

            this.text = text
            textSize = size

            typeface = font ?: Typeface.DEFAULT

            setTextColor(color)

            strokeColor = woodOutline
            strokeWidthPx = stroke.toFloat()
        }
    }

    private fun makeButton(
        text: String,
        color: Int,
        textColor: Int,
        action: () -> Unit
    ): Button {

        return Button(this).apply {

            this.text = text

            textSize = 16f

            typeface = singingFont ?: Typeface.DEFAULT_BOLD

            setTextColor(textColor)

            isAllCaps = false

            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    lighten(color, 35),
                    color,
                    greenDark
                )
            ).apply {

                cornerRadius = dp(13).toFloat()

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

                if (isEnabled) {
                    action()
                }
            }
        }
    }

    private fun recessedBackground(): GradientDrawable {

        return GradientDrawable().apply {

            setColor(parchmentDark)

            cornerRadius = dp(10).toFloat()

            setStroke(
                dp(2),
                goldDark
            )
        }
    }

    private fun lighten(color: Int, amount: Int): Int {

        return Color.rgb(
            (Color.red(color) + amount).coerceAtMost(255),
            (Color.green(color) + amount).coerceAtMost(255),
            (Color.blue(color) + amount).coerceAtMost(255)
        )
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

    // --------------------------------------------------
    // MIDI CONVERSION
    // --------------------------------------------------

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

    // --------------------------------------------------
    // MSM SAVING
    // --------------------------------------------------

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

// --------------------------------------------------
// OUTLINED MSM TEXT
// --------------------------------------------------

class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var strokeColor: Int = Color.BLACK
    var strokeWidthPx: Float = 5f

    override fun onDraw(canvas: Canvas) {

        val originalColor = currentTextColor

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidthPx
        paint.strokeJoin = Paint.Join.ROUND

        setTextColor(strokeColor)

        super.onDraw(canvas)

        paint.style = Paint.Style.FILL

        setTextColor(originalColor)

        super.onDraw(canvas)
    }
}
