package com.dragosdomini.msmtools

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.NinePatch
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
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

    // Classic MSM composer & workshop palette
    private val backgroundColor = Color.rgb(18, 18, 18)
    private val woodDark = Color.rgb(58, 35, 24)
    private val woodOutline = Color.rgb(28, 16, 10)

    private val panelInnerColor = Color.rgb(235, 211, 161)
    private val panelWellColor = Color.rgb(214, 184, 130)

    private val goldColor = Color.rgb(246, 196, 82)
    private val goldShadow = Color.rgb(156, 98, 28)
    private val goldDark = Color.rgb(138, 88, 32)

    private val creamColor = Color.rgb(255, 244, 214)
    private val parchmentText = Color.rgb(74, 48, 30)

    private val greenColor = Color.rgb(112, 168, 62)
    private val greenShadow = Color.rgb(46, 88, 34)

    private val openMidi =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                selectedMidi = uri
                convertedMsm = null

                selectedFileText.text =
                    uri.lastPathSegment ?: "MIDI file selected"
                selectedFileText.setTextColor(parchmentText)

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
        window.navigationBarColor = woodOutline
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

        val container = FrameLayout(this).apply {
            setBackgroundColor(backgroundColor)
            background = loadPanelBackground("images/panel.png")
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            setPadding(
                dp(26),
                dp(28),
                dp(26),
                dp(34)
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

        // Main title

        val title = OutlinedTextView(this).apply {
            text = "MSM MIDI TOOLS"
            textSize = 32f
            typeface = singingFont ?: Typeface.DEFAULT_BOLD
            setTextColor(goldColor)
            strokeColor = woodOutline
            strokeWidthPx = dp(6).toFloat()
            gravity = Gravity.CENTER
            letterSpacing = 0.03f
        }

        root.addView(
            title,
            matchWidth()
        )

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
                dp(6),
                dp(8),
                dp(18)
            )
        }

        root.addView(
            subtitle,
            matchWidth()
        )

        // 1. MIDI selection section

        val selectCard = makeInnerPanel()

        selectCard.addView(
            makeStepHeader("1. CHOOSE YOUR SONG")
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
            topMargin = dp(6)
            bottomMargin = dp(12)
        }

        selectCard.addView(
            selectDescription,
            descriptionParams
        )

        val selectButton = makeButton(
            text = "SELECT MIDI FILE",
            topColor = lighten(greenColor, 26),
            baseColor = greenColor,
            bottomColor = greenShadow,
            textColor = creamColor,
            strokeColor = woodOutline
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
            parchmentText,
            bodyFont
        ).apply {
            gravity = Gravity.CENTER
            background = recessedWellBackground()

            setPadding(
                dp(10),
                dp(10),
                dp(10),
                dp(10)
            )
        }

        val fileWellParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
        }

        selectCard.addView(
            selectedFileText,
            fileWellParams
        )

        root.addView(
            selectCard,
            matchWidth()
        )

        addSpace(root, 14)

        // 2. Note settings section

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
                    dp(6),
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
                dp(12),
                dp(6),
                dp(12),
                dp(6)
            )

            background = roundedBackground(
                color = creamColor,
                stroke = goldDark,
                radius = 11,
                strokeWidth = 3
            )
        }

        settingsCard.addView(
            noteLengthSpinner,
            matchWidth(height = dp(52))
        )

        root.addView(
            settingsCard,
            matchWidth()
        )

        addSpace(root, 16)

        // Conversion button

        convertButton = makeButton(
            text = "CONVERT TO MSM",
            topColor = lighten(goldColor, 28),
            baseColor = goldColor,
            bottomColor = goldShadow,
            textColor = creamColor,
            strokeColor = woodOutline
        ) {
            convertMidi()
        }

        convertButton.textSize = 20f

        root.addView(
            convertButton,
            matchWidth(height = dp(62))
        )

        addSpace(root, 16)

        // Workshop status

        val statusPanel = makeInnerPanel()

        statusPanel.addView(
            makeStepHeader("WORKSHOP STATUS")
        )

        statusText = makeText(
            "Choose a MIDI song to get started.",
            14f,
            parchmentText,
            bodyFont
        ).apply {
            gravity = Gravity.CENTER
            background = recessedWellBackground()

            setPadding(
                dp(10),
                dp(12),
                dp(10),
                dp(12)
            )
        }

        val statusWellParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        }

        statusPanel.addView(
            statusText,
            statusWellParams
        )

        root.addView(
            statusPanel,
            matchWidth()
        )

        // Footer

        val footer = OutlinedTextView(this).apply {
            text = "MY SINGING MONSTERS COMPOSER TOOL"
            textSize = 12f
            typeface = bodyFont ?: Typeface.DEFAULT
            setTextColor(creamColor)
            strokeColor = woodOutline
            strokeWidthPx = dp(2).toFloat()
            gravity = Gravity.CENTER

            setPadding(
                0,
                dp(18),
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

    private fun loadPanelBackground(
        assetPath: String = "images/panel.png"
    ): Drawable {
        return try {
            assets.open(assetPath).use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                    ?: return GradientDrawable().apply {
                        setColor(backgroundColor)
                    }

                val chunk = bitmap.ninePatchChunk
                if (chunk != null && NinePatch.isNinePatchChunk(chunk)) {
                    NinePatchDrawable(
                        resources,
                        bitmap,
                        chunk,
                        Rect(),
                        null
                    )
                } else {
                    BitmapDrawable(resources, bitmap).apply {
                        gravity = Gravity.FILL
                    }
                }
            }
        } catch (_: Exception) {
            GradientDrawable().apply {
                setColor(backgroundColor)
            }
        }
    }

    private fun makeStepHeader(titleText: String): OutlinedTextView {
        return OutlinedTextView(this).apply {
            text = titleText
            textSize = 18f
            typeface = singingFont ?: Typeface.DEFAULT_BOLD
            setTextColor(goldColor)
            strokeColor = woodOutline
            strokeWidthPx = dp(4).toFloat()
        }
    }

    private fun makeInnerPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(14)
            )

            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.rgb(250, 232, 192),
                    panelInnerColor,
                    Color.rgb(214, 184, 130)
                )
            ).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(3), woodDark)
            }

            elevation = dp(4).toFloat()
        }
    }

    private fun recessedWellBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(panelWellColor)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(2), goldDark)
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
        topColor: Int,
        baseColor: Int,
        bottomColor: Int,
        textColor: Int,
        strokeColor: Int,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            this.text = text
            textSize = 17f
            typeface = singingFont ?: Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            isAllCaps = false

            background = msmButtonDrawable(
                topColor = topColor,
                baseColor = baseColor,
                bottomColor = bottomColor,
                strokeColor = strokeColor,
                radiusDp = 14
            )

            elevation = dp(5).toFloat()

            setPadding(
                dp(14),
                dp(6),
                dp(14),
                dp(6)
            )

            setOnClickListener {
                if (isEnabled) action()
            }
        }
    }

    private fun msmButtonDrawable(
        topColor: Int,
        baseColor: Int,
        bottomColor: Int,
        strokeColor: Int,
        radiusDp: Int
    ): StateListDrawable {
        val normalBase = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                topColor,
                baseColor,
                bottomColor
            )
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(3), strokeColor)
        }

        val gloss = GradientDrawable().apply {
            setColor(Color.argb(65, 255, 255, 255))
            cornerRadius = dp((radiusDp - 4).coerceAtLeast(4)).toFloat()
        }

        val normalState = LayerDrawable(arrayOf(normalBase, gloss)).apply {
            val side = dp(6)
            val top = dp(4)
            val bottom = dp(24)
            setLayerInset(1, side, top, side, bottom)
        }

        val pressedState = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                bottomColor,
                baseColor,
                topColor
            )
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(3), strokeColor)
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedState)
            addState(intArrayOf(-android.R.attr.state_enabled), normalBase.apply { alpha = 140 })
            addState(intArrayOf(), normalState)
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
            setStroke(dp(strokeWidth), stroke)
        }
    }

    private fun addSpace(parent: LinearLayout, height: Int) {
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

    // MIDI conversion — original converter integration preserved

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

            val noteDivision = when (noteLengthSpinner.selectedItemPosition) {
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
            statusText.text = "Could not save the file:\n${e.message}"
        }
    }
}

class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var strokeColor: Int = Color.parseColor("#1C100A")
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
