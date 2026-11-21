
package com.dragosdomini.msmtools

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
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
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var selectedMidi: Uri? = null
    private var convertedMsm: ByteArray? = null

    private lateinit var statusText: TextView
    private lateinit var selectedFileText: TextView
    private lateinit var noteLengthSpinner: Spinner
    private lateinit var convertButton: Button
    private lateinit var loadingContainer: FrameLayout
    private lateinit var loadingFill: ImageView
    private lateinit var loadingBackground: ImageView

    private var loadingAnimator: ValueAnimator? = null

    private var bodyFont: Typeface = Typeface.DEFAULT
    private var headingFont: Typeface = Typeface.DEFAULT_BOLD

    private var panelBitmap: Bitmap? = null
    private var loadingBitmap: Bitmap? = null
    private var loadingBackgroundBitmap: Bitmap? = null

    private val backgroundColor = Color.rgb(43, 70, 45)
    private val darkGreen = Color.rgb(30, 51, 34)

    private val parchmentColor = Color.rgb(244, 226, 185)
    private val parchmentDark = Color.rgb(218, 194, 148)

    private val darkText = Color.rgb(69, 53, 39)
    private val creamText = Color.rgb(255, 244, 215)
    private val goldColor = Color.rgb(231, 184, 85)

    // --------------------------------------------------
    // FILE PICKERS
    // --------------------------------------------------

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
                    "Song selected. Ready to convert!"
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

    // --------------------------------------------------
    // INITIALIZATION
    // --------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = darkGreen
        window.navigationBarColor = darkGreen
        window.decorView.systemUiVisibility = 0

        loadFonts()
        loadAssets()

        buildInterface()
    }

    private fun loadFonts() {

        bodyFont = try {
            Typeface.createFromAsset(
                assets,
                "fonts/Fredoka-VariableFont_wdth,wght.ttf"
            )
        } catch (_: Exception) {
            Typeface.create("sans-serif-rounded", Typeface.NORMAL)
        }

        headingFont = try {
            Typeface.createFromAsset(
                assets,
                "fonts/Hobeaux-Bold.otf"
            )
        } catch (_: Exception) {
            Typeface.create(bodyFont, Typeface.BOLD)
        }
    }

    private fun loadAssets() {

        panelBitmap = loadImage("images/panel.png")

        loadingBitmap = loadImage("images/loadbar.png")

        loadingBackgroundBitmap =
            loadImage("images/loadbar_bg.png")

        // Rotate the horizontal panel into a vertical panel.
        panelBitmap = panelBitmap?.let { bitmap ->

            val matrix = Matrix().apply {
                postRotate(90f)
            }

            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
        }
    }

    private fun loadImage(path: String): Bitmap? {
        return try {
            assets.open(path).use {
                BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) {
            null
        }
    }

    // --------------------------------------------------
    // MAIN INTERFACE
    // --------------------------------------------------

    private fun buildInterface() {

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    backgroundColor,
                    darkGreen
                )
            )
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            setPadding(
                dp(16),
                dp(20),
                dp(16),
                dp(28)
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
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // HEADER

        root.addView(
            makeText(
                "MSM MIDI TOOLS",
                29f,
                creamText,
                headingFont
            ).apply {
                gravity = Gravity.CENTER
            },
            matchWidth()
        )

        root.addView(
            makeText(
                "MIDI TO MONSTER COMPOSER",
                13f,
                goldColor,
                Typeface.BOLD
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(22))
            },
            matchWidth()
        )

        // MAIN VERTICAL PANEL

        val workshop = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(25),
                dp(26),
                dp(25),
                dp(26)
            )

            background = panelBitmap?.let {
                VerticalPanelDrawable(it)
            } ?: fallbackPanel()

            elevation = dp(5).toFloat()
        }

        root.addView(workshop, matchWidth())

        workshop.addView(
            makeText(
                "SONG WORKSHOP",
                22f,
                darkText,
                headingFont
            ).apply {
                gravity = Gravity.CENTER
            },
            matchWidth()
        )

        addDivider(workshop)

        // SONG SELECTION

        val fileCard = makeSection()

        fileCard.addView(
            makeText(
                "1. CHOOSE YOUR SONG",
                18f,
                darkText,
                headingFont
            ),
            matchWidth()
        )

        fileCard.addView(
            makeText(
                "Select a MIDI file from your device.",
                14f,
                darkText
            ).apply {
                setPadding(0, dp(8), 0, dp(13))
            },
            matchWidth()
        )

        fileCard.addView(
            makeButton("SELECT MIDI FILE") {
                openMidi.launch(
                    arrayOf(
                        "audio/midi",
                        "audio/x-midi",
                        "application/octet-stream",
                        "*/*"
                    )
                )
            },
            matchWidth(dp(58))
        )

        selectedFileText = makeText(
            "No song selected yet.",
            13f,
            darkText
        ).apply {
            gravity = Gravity.CENTER
            background = insetBackground()

            setPadding(
                dp(10),
                dp(13),
                dp(10),
                dp(13)
            )
        }

        fileCard.addView(
            selectedFileText,
            matchWidth().apply {
                topMargin = dp(10)
            }
        )

        workshop.addView(fileCard, matchWidth())

        addSpace(workshop, 15)

        // NOTE SETTINGS

        val settingsCard = makeSection()

        settingsCard.addView(
            makeText(
                "2. NOTE SETTINGS",
                18f,
                darkText,
                headingFont
            ),
            matchWidth()
        )

        settingsCard.addView(
            makeText(
                "Choose the shortest note length to preserve.",
                14f,
                darkText
            ).apply {
                setPadding(0, dp(8), 0, dp(12))
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

            background = insetBackground()

            setPadding(
                dp(12),
                dp(7),
                dp(12),
                dp(7)
            )
        }

        settingsCard.addView(
            noteLengthSpinner,
            matchWidth(dp(55))
        )

        workshop.addView(settingsCard, matchWidth())

        addSpace(workshop, 17)

        // CONVERT BUTTON

        convertButton = makeButton("CONVERT TO MSM") {
            convertMidi()
        }

        convertButton.textSize = 18f

        workshop.addView(
            convertButton,
            matchWidth(dp(65))
        )

        addSpace(workshop, 17)

        // CONVERSION PROGRESS

        val loadingCard = makeSection()

        loadingCard.addView(
            makeText(
                "CONVERSION PROGRESS",
                18f,
                darkText,
                headingFont
            ),
            matchWidth()
        )

        loadingCard.addView(
            makeText(
                "Preparing your monster composition...",
                13f,
                darkText
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(12))
            },
            matchWidth()
        )

        loadingContainer = FrameLayout(this).apply {
            visibility = View.GONE
        }

        loadingBackground = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true

            loadingBackgroundBitmap?.let {
                setImageBitmap(it)
            }
        }

        loadingContainer.addView(
            loadingBackground,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        loadingFill = ImageView(this).apply {

            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true

            loadingBitmap?.let { bitmap ->

                val drawable = ClipDrawable(
                    BitmapDrawable(resources, bitmap),
                    Gravity.LEFT,
                    ClipDrawable.HORIZONTAL
                )

                drawable.level = 0
                setImageDrawable(drawable)
            }
        }

        loadingContainer.addView(
            loadingFill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Calculate height from the original background ratio.
        // This prevents the loading artwork from being stretched.

        loadingContainer.post {

            val bitmap = loadingBackgroundBitmap

            if (bitmap != null && loadingContainer.width > 0) {

                val ratio = bitmap.height.toFloat() /
                    bitmap.width.toFloat()

                val correctHeight = (
                    loadingContainer.width * ratio
                ).toInt()

                loadingContainer.layoutParams =
                    loadingContainer.layoutParams.apply {
                        height = correctHeight
                    }
            }
        }

        loadingCard.addView(
            loadingContainer,
            matchWidth()
        )

        workshop.addView(loadingCard, matchWidth())

        addSpace(workshop, 17)

        // STATUS PANEL

        val statusCard = makeSection()

        statusCard.addView(
            makeText(
                "WORKSHOP STATUS",
                18f,
                darkText,
                headingFont
            ),
            matchWidth()
        )

        statusText = makeText(
            "Choose a MIDI song to get started.",
            14f,
            darkText
        ).apply {
            gravity = Gravity.CENTER
            background = insetBackground()

            setPadding(
                dp(10),
                dp(15),
                dp(10),
                dp(15)
            )
        }

        statusCard.addView(
            statusText,
            matchWidth()
        )

        workshop.addView(statusCard, matchWidth())

        root.addView(
            makeText(
                "MY SINGING MONSTERS COMPOSER TOOL",
                12f,
                creamText
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, 0)
            },
            matchWidth()
        )

        setContentView(container)
    }

    // --------------------------------------------------
    // VERTICAL PANEL DRAWABLE
    // Uses rotated panel.png with preserved borders.
    // --------------------------------------------------

    private inner class VerticalPanelDrawable(
        private val bitmap: Bitmap
    ) : Drawable() {

        private val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
        )

        private val interiorPaint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = parchmentColor
            alpha = 244
        }

        override fun draw(canvas: Canvas) {

            val b = bounds

            if (b.width() <= 0 || b.height() <= 0) return

            val w = bitmap.width
            val h = bitmap.height

            val edgeX = (w * 0.16f).toInt()
            val edgeY = (h * 0.10f).toInt()

            val srcX = intArrayOf(
                0, edgeX, w - edgeX
            )

            val srcY = intArrayOf(
                0, edgeY, h - edgeY
            )

            val borderX = minOf(
                dp(25).toFloat(),
                b.width() / 4f
            )

            val borderY = minOf(
                dp(25).toFloat(),
                b.height() / 4f
            )

            val dstX = floatArrayOf(
                b.left.toFloat(),
                b.left + borderX,
                b.right - borderX,
                b.right.toFloat()
            )

            val dstY = floatArrayOf(
                b.top.toFloat(),
                b.top + borderY,
                b.bottom - borderY,
                b.bottom.toFloat()
            )

            // Draw all nine sections of the vertical artwork.

            for (row in 0..2) {
                for (column in 0..2) {

                    val source = Rect(
                        srcX[column],
                        srcY[row],
                        if (column == 2) w else srcX[column + 1],
                        if (row == 2) h else srcY[row + 1]
                    )

                    val destination = RectF(
                        dstX[column],
                        dstY[row],
                        dstX[column + 1],
                        dstY[row + 1]
                    )

                    canvas.drawBitmap(
                        bitmap,
                        source,
                        destination,
                        paint
                    )
                }
            }

            // Translucent interior keeps the text readable.

            canvas.drawRoundRect(
                RectF(
                    b.left + dp(24).toFloat(),
                    b.top + dp(24).toFloat(),
                    b.right - dp(24).toFloat(),
                    b.bottom - dp(24).toFloat()
                ),
                dp(12).toFloat(),
                dp(12).toFloat(),
                interiorPaint
            )
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(
            colorFilter: android.graphics.ColorFilter?
        ) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated graphics API")
        override fun getOpacity(): Int {
            return PixelFormat.TRANSLUCENT
        }
    }

    // --------------------------------------------------
    // LOADING BAR ANIMATION
    // --------------------------------------------------

    private fun startLoadingAnimation() {

        loadingContainer.visibility = View.VISIBLE

        loadingAnimator?.cancel()

        val drawable = loadingFill.drawable as? ClipDrawable

        if (drawable == null) return

        drawable.level = 1000

        loadingAnimator = ValueAnimator.ofInt(
            1000,
            9500
        ).apply {

            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE

            addUpdateListener {

                drawable.level =
                    it.animatedValue as Int
            }

            start()
        }
    }

    private fun stopLoadingAnimation() {

        loadingAnimator?.cancel()
        loadingAnimator = null

        loadingFill.drawable?.let {
            if (it is ClipDrawable) {
                it.level = 10000
            }
        }
    }

    private fun hideLoadingAnimation() {

        loadingAnimator?.cancel()
        loadingAnimator = null

        loadingContainer.visibility = View.GONE

        loadingFill.drawable?.let {
            if (it is ClipDrawable) {
                it.level = 0
            }
        }
    }

    // --------------------------------------------------
    // CARVED STONE BUTTONS
    // --------------------------------------------------

    private fun makeButton(
        text: String,
        action: () -> Unit
    ): Button {

        return Button(this).apply {

            this.text = text
            textSize = 16f
            typeface = bodyFont
            setTextColor(Color.rgb(66, 55, 43))

            isAllCaps = false
            backgroundTintList = null

            background = stoneButtonBackground()

            elevation = dp(3).toFloat()

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

    private fun stoneButtonBackground():
        android.graphics.drawable.StateListDrawable {

        val states =
            android.graphics.drawable.StateListDrawable()

        val normal = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.rgb(220, 211, 190),
                Color.rgb(168, 158, 137),
                Color.rgb(133, 123, 105)
            )
        ).apply {
            cornerRadius = dp(13).toFloat()
            setStroke(dp(3), Color.rgb(91, 78, 60))
        }

        val pressed = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.rgb(130, 119, 100),
                Color.rgb(164, 153, 132),
                Color.rgb(185, 174, 151)
            )
        ).apply {
            cornerRadius = dp(13).toFloat()
            setStroke(dp(3), Color.rgb(78, 67, 52))
        }

        states.addState(
            intArrayOf(android.R.attr.state_pressed),
            pressed
        )

        states.addState(
            intArrayOf(),
            normal
        )

        return states
    }

    // --------------------------------------------------
    // CARD STYLING
    // --------------------------------------------------

    private fun makeSection(): LinearLayout {

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
                    Color.rgb(250, 237, 207),
                    parchmentColor
                )
            ).apply {
                cornerRadius = dp(15).toFloat()
                setStroke(
                    dp(2),
                    Color.rgb(185, 155, 105)
                )
            }

            elevation = dp(2).toFloat()
        }
    }

    private fun fallbackPanel(): Drawable {

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                parchmentColor,
                parchmentDark
            )
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(3), goldColor)
        }
    }

    private fun makeText(
        text: String,
        size: Float,
        color: Int,
        font: Typeface = bodyFont
    ): TextView {

        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = font
        }
    }

    private fun insetBackground(): GradientDrawable {

        return GradientDrawable().apply {
            setColor(parchmentDark)
            cornerRadius = dp(9).toFloat()
            setStroke(
                dp(1),
                Color.rgb(176, 144, 91)
            )
        }
    }

    private fun addDivider(parent: LinearLayout) {

        parent.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(
                        parchmentDark,
                        goldColor,
                        parchmentDark
                    )
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2)
            ).apply {
                setMargins(
                    dp(5),
                    dp(13),
                    dp(5),
                    dp(17)
                )
            }
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
        noteLengthSpinner.isEnabled = false

        statusText.text = "Reading your song..."

        startLoadingAnimation()

        val noteDivision = when (
            noteLengthSpinner.selectedItemPosition
        ) {
            1 -> 16
            2 -> 32
            else -> 8
        }

        Thread {

            try {

                val midiBytes = contentResolver
                    .openInputStream(midiUri)
                    ?.use { it.readBytes() }
                    ?: throw IOException(
                        "Could not open MIDI file."
                    )

                runOnUiThread {
                    statusText.text = "Reading MIDI tracks..."
                }

                val parsedMidi = MidiReader.read(midiBytes)

                runOnUiThread {
                    statusText.text =
                        "Converting ${parsedMidi.tracks.size} instrument tracks..."
                }

                val msmBytes = MsmConverter.convert(
                    context = this,
                    midi = parsedMidi,
                    minimumNote = noteDivision
                )

                runOnUiThread {

                    convertedMsm = msmBytes

                    stopLoadingAnimation()

                    statusText.text =
                        "Conversion complete!\n" +
                        "${parsedMidi.tracks.size} instrument tracks processed."

                    convertButton.isEnabled = true
                    noteLengthSpinner.isEnabled = true

                    saveMsm.launch("converted_song.msm")
                }

            } catch (e: Exception) {

                runOnUiThread {

                    convertedMsm = null

                    hideLoadingAnimation()

                    statusText.text =
                        "Conversion failed:\n" +
                        (e.message ?: "Unknown error")

                    convertButton.isEnabled = true
                    noteLengthSpinner.isEnabled = true
                }
            }

        }.start()
    }

    // --------------------------------------------------
    // SAVE MSM
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

    override fun onDestroy() {
        loadingAnimator?.cancel()
        loadingAnimator = null
        super.onDestroy()
    }
}
