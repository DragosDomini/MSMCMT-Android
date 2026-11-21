
package com.dragosdomini.msmtools

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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

    private var loadingAnimator: ValueAnimator? = null

    private var bodyFont: Typeface = Typeface.create(
        "sans-serif-rounded",
        Typeface.NORMAL
    )

    private var panelBitmap: Bitmap? = null
    private var loadingBitmap: Bitmap? = null
    private var loadingBackgroundBitmap: Bitmap? = null

    private val backgroundColor = Color.rgb(48, 76, 48)
    private val darkGreen = Color.rgb(34, 55, 37)
    private val lightGreen = Color.rgb(112, 151, 76)

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

        bodyFont = try {
            Typeface.createFromAsset(
                assets,
                "fonts/Fredoka-VariableFont_wdth,wght.ttf"
            )
        } catch (_: Exception) {
            Typeface.create(
                "sans-serif-rounded",
                Typeface.NORMAL
            )
        }

        panelBitmap = loadImage("images/panel.png")
        loadingBitmap = loadImage("images/loadbar.png")
        loadingBackgroundBitmap =
            loadImage("images/loadbar_bg.png")

        buildInterface()
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
                dp(20),
                dp(25),
                dp(20),
                dp(30)
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
                Typeface.BOLD
            ).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.025f
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
                setPadding(0, dp(7), 0, dp(25))
            },
            matchWidth()
        )

        // MAIN WORKSHOP

        val workshop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(15),
                dp(17),
                dp(15),
                dp(17)
            )

            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    parchmentColor,
                    parchmentDark
                )
            ).apply {
                cornerRadius = dp(19).toFloat()
                setStroke(dp(2), goldColor)
            }

            elevation = dp(6).toFloat()
        }

        root.addView(workshop, matchWidth())

        workshop.addView(
            makeText(
                "SONG WORKSHOP",
                21f,
                darkText,
                Typeface.BOLD
            ).apply {
                gravity = Gravity.CENTER
            },
            matchWidth()
        )

        addDivider(workshop)

        // MIDI FILE SECTION

        val fileCard = makeSection()

        fileCard.addView(
            makeText(
                "1. CHOOSE YOUR SONG",
                17f,
                darkText,
                Typeface.BOLD
            ),
            matchWidth()
        )

        fileCard.addView(
            makeText(
                "Select a MIDI file from your device.",
                14f,
                darkText
            ).apply {
                setPadding(0, dp(7), 0, dp(13))
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
                dp(12),
                dp(10),
                dp(12)
            )
        }

        fileCard.addView(
            selectedFileText,
            matchWidth().apply {
                topMargin = dp(10)
            }
        )

        workshop.addView(fileCard, matchWidth())

        addSpace(workshop, 14)

        // NOTE SETTINGS

        val settingsCard = makeSection()

        settingsCard.addView(
            makeText(
                "2. NOTE SETTINGS",
                17f,
                darkText,
                Typeface.BOLD
            ),
            matchWidth()
        )

        settingsCard.addView(
            makeText(
                "Choose the shortest note length to preserve.",
                14f,
                darkText
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

            background = insetBackground()
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        settingsCard.addView(
            noteLengthSpinner,
            matchWidth(dp(52))
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

        // LOADING BAR SECTION

        val loadingCard = makeSection()

        loadingCard.addView(
            makeText(
                "CONVERSION PROGRESS",
                17f,
                darkText,
                Typeface.BOLD
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
                setPadding(0, dp(8), 0, dp(10))
            },
            matchWidth()
        )

        loadingContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(8).toFloat()
            }

            visibility = View.GONE
        }

        // Background artwork

        val loadingBackground = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY

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

        // Animated fill artwork

        loadingFill = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY

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

        loadingCard.addView(
            loadingContainer,
            matchWidth(dp(32))
        )

        workshop.addView(
            loadingCard,
            matchWidth()
        )

        addSpace(workshop, 17)

        // STATUS PANEL

        val statusCard = makeSection()

        statusCard.addView(
            makeText(
                "WORKSHOP STATUS",
                17f,
                darkText,
                Typeface.BOLD
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

        workshop.addView(
            statusCard,
            matchWidth()
        )

        root.addView(
            makeText(
                "MY SINGING MONSTERS COMPOSER TOOL",
                12f,
                creamText
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(22), 0, 0)
            },
            matchWidth()
        )

        setContentView(container)
    }

    // --------------------------------------------------
    // ANIMATED LOADING BAR
    // --------------------------------------------------

    private fun startLoadingAnimation() {

        loadingContainer.visibility = View.VISIBLE

        loadingAnimator?.cancel()

        val drawable = loadingFill.drawable as? ClipDrawable

        if (drawable == null) {
            return
        }

        drawable.level = 1000

        loadingAnimator = ValueAnimator.ofInt(
            1000,
            9500
        ).apply {

            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE

            addUpdateListener { animator ->

                drawable.level =
                    animator.animatedValue as Int
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
    // BUTTON ARTWORK
    // --------------------------------------------------

    private fun makeButton(
        text: String,
        action: () -> Unit
    ): Button {

        return Button(this).apply {

            this.text = text
            textSize = 16f
            typeface = bodyFont
            setTextColor(Color.WHITE)
            isAllCaps = false

            background = panelBitmap?.let {
                NineSlicePanelDrawable(it)
            } ?: GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(lightGreen, darkGreen)
            ).apply {
                cornerRadius = dp(12).toFloat()
            }

            elevation = dp(4).toFloat()

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

    // --------------------------------------------------
    // NINE-SLICE PANEL DRAWABLE
    // --------------------------------------------------

    private class NineSlicePanelDrawable(
        private val bitmap: Bitmap
    ) : Drawable() {

        private val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
        )

        private val edge = minOf(
            bitmap.width,
            bitmap.height
        ) / 6

        override fun draw(canvas: Canvas) {

            val bounds = bounds

            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return
            }

            val w = bitmap.width
            val h = bitmap.height
            val e = edge

            val src = arrayOf(
                Rect(0, 0, e, e),
                Rect(e, 0, w - e, e),
                Rect(w - e, 0, w, e),

                Rect(0, e, e, h - e),
                Rect(e, e, w - e, h - e),
                Rect(w - e, e, w, h - e),

                Rect(0, h - e, e, h),
                Rect(e, h - e, w - e, h),
                Rect(w - e, h - e, w, h)
            )

            val dx = minOf(e.toFloat(), bounds.width() / 3f)
            val dy = minOf(e.toFloat(), bounds.height() / 3f)

            val x = floatArrayOf(
                bounds.left.toFloat(),
                bounds.left + dx,
                bounds.right - dx,
                bounds.right.toFloat()
            )

            val y = floatArrayOf(
                bounds.top.toFloat(),
                bounds.top + dy,
                bounds.bottom - dy,
                bounds.bottom.toFloat()
            )

            var index = 0

            for (row in 0..2) {
                for (column in 0..2) {

                    canvas.drawBitmap(
                        bitmap,
                        src[index],
                        RectF(
                            x[column],
                            y[row],
                            x[column + 1],
                            y[row + 1]
                        ),
                        paint
                    )

                    index++
                }
            }
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
    // UI HELPERS
    // --------------------------------------------------

    private fun makeSection(): LinearLayout {

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
                    Color.rgb(250, 237, 207),
                    parchmentColor
                )
            ).apply {
                cornerRadius = dp(13).toFloat()
                setStroke(
                    dp(1),
                    Color.rgb(185, 155, 105)
                )
            }
        }
    }

    private fun makeText(
        text: String,
        size: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView {

        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(bodyFont, style)
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
                    dp(12),
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
    // Runs in a background thread.
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
                    statusText.text =
                        "Reading MIDI tracks..."
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

    override fun onDestroy() {
        loadingAnimator?.cancel()
        loadingAnimator = null
        super.onDestroy()
    }
}
