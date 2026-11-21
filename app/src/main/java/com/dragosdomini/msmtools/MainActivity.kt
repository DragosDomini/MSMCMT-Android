
package com.dragosdomini.msmtools

import android.animation.ValueAnimator
import android.content.Intent
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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
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

    private lateinit var statusText: TextView
    private lateinit var selectedFileText: TextView
    private lateinit var convertButton: Button
    private lateinit var loadingContainer: FrameLayout
    private lateinit var loadingFill: ImageView
    private lateinit var loadingBackground: ImageView

    private var loadingAnimator: ValueAnimator? = null

    private var bodyFont: Typeface = Typeface.DEFAULT
    private var headingFont: Typeface = Typeface.DEFAULT_BOLD

    // Note length selection
    private val noteOptions = listOf(
        "1/8" to 8,
        "1/16" to 16,
        "1/32" to 32
    )

    private var selectedDivision = 8
    private val noteChips = mutableListOf<TextView>()
    private var chipsEnabled = true

    // Assets
    private var backgroundBitmap: Bitmap? = null
    private var buttonBaseBitmap: Bitmap? = null
    private var panelBitmap: Bitmap? = null
    private var loadingBitmap: Bitmap? = null
    private var loadingBackgroundBitmap: Bitmap? = null

    // Colors
    private val parchmentColor = Color.rgb(244, 226, 185)
    private val parchmentDark = Color.rgb(218, 194, 148)

    private val darkText = Color.rgb(69, 53, 39)
    private val errorText = Color.rgb(140, 40, 30)
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

                // Retain permission so the selected file remains
                // accessible after Android recreates the activity.
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some document providers do not offer
                    // persistable permissions.
                }

                selectedMidi = uri
                selectedName = displayName(uri)
                convertedMsm = null

                selectedFileText.text =
                    selectedName ?: "MIDI file selected"

                setStatus("Song selected. Ready to convert!")
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

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        savedInstanceState?.let {

            it.getString("midi_uri")?.let { uriString ->
                selectedMidi = Uri.parse(uriString)
            }

            selectedName = it.getString("midi_name")
            selectedDivision = it.getInt("division", 8)
                .coerceIn(8, 32)

            if (selectedDivision !in noteOptions.map { option -> option.second }) {
                selectedDivision = 8
            }
        }

        loadFonts()
        loadAssets()

        window.setBackgroundDrawable(
            backgroundBitmap?.let {
                BackgroundDrawable(it)
            } ?: ColorDrawable(Color.BLACK)
        )

        buildInterface()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString(
            "midi_uri",
            selectedMidi?.toString()
        )

        outState.putString(
            "midi_name",
            selectedName
        )

        outState.putInt(
            "division",
            selectedDivision
        )
    }

    private fun loadFonts() {

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

        headingFont = try {
            Typeface.createFromAsset(
                assets,
                "fonts/Hobeaux-Bold.otf"
            )
        } catch (_: Exception) {
            Typeface.create(
                bodyFont,
                Typeface.BOLD
            )
        }
    }

    private fun loadAssets() {

        backgroundBitmap = loadImage(
            "images/background.jpg"
        )

        buttonBaseBitmap = loadImage(
            "images/button_base.png"
        )

        panelBitmap = loadImage(
            "images/panel.png"
        )

        loadingBitmap = loadImage(
            "images/loadbar.png"
        )

        loadingBackgroundBitmap = loadImage(
            "images/loadbar_bg.png"
        )

        // Rotate only panel.png.
        // background.jpg has already been rotated externally.

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

    private fun displayName(uri: Uri): String {

        try {

            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->

                if (cursor.moveToFirst()) {

                    val index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                    )

                    if (index >= 0) {
                        return cursor.getString(index)
                    }
                }
            }

        } catch (_: Exception) {
        }

        return uri.lastPathSegment ?: "MIDI file selected"
    }

    // --------------------------------------------------
    // BACKGROUND DRAWABLE
    // --------------------------------------------------

    private inner class BackgroundDrawable(
        private val bitmap: Bitmap
    ) : Drawable() {

        private val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or
                Paint.FILTER_BITMAP_FLAG
        )

        override fun draw(canvas: Canvas) {

            val bounds = bounds

            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return
            }

            // Cover the entire screen without distorting
            // the original image proportions.

            val scale = maxOf(
                bounds.width().toFloat() / bitmap.width,
                bounds.height().toFloat() / bitmap.height
            )

            val width = bitmap.width * scale
            val height = bitmap.height * scale

            val left = bounds.centerX() - width / 2f
            val top = bounds.centerY() - height / 2f

            canvas.drawBitmap(
                bitmap,
                null,
                RectF(
                    left,
                    top,
                    left + width,
                    top + height
                ),
                paint
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
            return PixelFormat.OPAQUE
        }
    }

    // --------------------------------------------------
    // MAIN INTERFACE
    // --------------------------------------------------

    private fun buildInterface() {

        val container = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            // No green background.
            background = backgroundBitmap?.let {
                BackgroundDrawable(it)
            } ?: ColorDrawable(Color.BLACK)
        }

        val scrollView = ScrollView(this).apply {

            isFillViewport = true
            clipToPadding = false

            setBackgroundColor(Color.TRANSPARENT)
        }

        val root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            setBackgroundColor(Color.TRANSPARENT)

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

        // Limit content width on tablets.
        val contentWidth = minOf(
            resources.displayMetrics.widthPixels - dp(32),
            dp(560)
        ).coerceAtLeast(dp(280))

        // --------------------------------------------------
        // HEADER
        // --------------------------------------------------

        val header = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            setPadding(
                dp(16),
                dp(14),
                dp(16),
                dp(14)
            )

            background = GradientDrawable().apply {

                setColor(
                    Color.argb(150, 24, 16, 8)
                )

                cornerRadius = dp(16).toFloat()

                setStroke(
                    dp(2),
                    goldColor
                )
            }
        }

        header.addView(
            makeText(
                "MSM MIDI TOOLS",
                29f,
                creamText,
                headingFont
            ).apply {

                gravity = Gravity.CENTER

                setShadowLayer(
                    4f,
                    0f,
                    2f,
                    Color.argb(200, 0, 0, 0)
                )
            },
            matchWidth()
        )

        header.addView(
            makeText(
                "MIDI TO MONSTER COMPOSER",
                13f,
                goldColor,
                Typeface.create(
                    bodyFont,
                    Typeface.BOLD
                )
            ).apply {

                gravity = Gravity.CENTER

                setPadding(
                    0,
                    dp(4),
                    0,
                    0
                )
            },
            matchWidth()
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                contentWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        )

        // --------------------------------------------------
        // MAIN PANEL
        // --------------------------------------------------

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

        root.addView(
            workshop,
            LinearLayout.LayoutParams(
                contentWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

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

        // --------------------------------------------------
        // SONG SELECTION
        // --------------------------------------------------

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

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(13)
                )
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
            selectedName ?: "No song selected yet.",
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

        workshop.addView(
            fileCard,
            matchWidth()
        )

        addSpace(workshop, 15)

        // --------------------------------------------------
        // NOTE SETTINGS
        // --------------------------------------------------

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

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(12)
                )
            },
            matchWidth()
        )

        settingsCard.addView(
            buildNoteSelector(),
            matchWidth()
        )

        workshop.addView(
            settingsCard,
            matchWidth()
        )

        addSpace(workshop, 17)

        // --------------------------------------------------
        // CONVERT BUTTON
        // --------------------------------------------------

        convertButton = makeButton("CONVERT TO MSM") {
            convertMidi()
        }

        convertButton.textSize = 18f

        workshop.addView(
            convertButton,
            matchWidth(dp(65))
        )

        addSpace(workshop, 17)

        // --------------------------------------------------
        // CONVERSION PROGRESS
        // --------------------------------------------------

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

                setPadding(
                    0,
                    dp(9),
                    0,
                    dp(12)
                )
            },
            matchWidth()
        )

        // Calculate a fixed height using the image aspect ratio.
        // This avoids measuring a GONE loading bar at zero height.

        val loadingReference =
            loadingBackgroundBitmap ?: loadingBitmap

        val loadingWidth = (
            contentWidth - dp(78)
        ).coerceAtLeast(dp(120))

        val loadingHeight = if (
            loadingReference != null &&
            loadingReference.width > 0
        ) {

            (
                loadingWidth.toFloat() *
                    loadingReference.height.toFloat() /
                    loadingReference.width.toFloat()
            ).toInt().coerceAtLeast(dp(12))

        } else {
            dp(24)
        }

        loadingContainer = FrameLayout(this).apply {
            visibility = View.GONE
        }

        loadingBackground = ImageView(this).apply {

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
            matchWidth(loadingHeight)
        )

        workshop.addView(
            loadingCard,
            matchWidth()
        )

        addSpace(workshop, 17)

        // --------------------------------------------------
        // STATUS PANEL
        // --------------------------------------------------

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
            if (selectedMidi != null) {
                "Song selected. Ready to convert!"
            } else {
                "Choose a MIDI song to get started."
            },
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
            matchWidth().apply {
                topMargin = dp(8)
            }
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

                setPadding(
                    0,
                    dp(20),
                    0,
                    0
                )

                setShadowLayer(
                    3f,
                    0f,
                    1f,
                    Color.argb(200, 0, 0, 0)
                )
            },
            matchWidth()
        )

        setContentView(container)
    }

    // --------------------------------------------------
    // NOTE LENGTH CHIPS
    // --------------------------------------------------

    private fun buildNoteSelector(): LinearLayout {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        noteChips.clear()

        noteOptions.forEachIndexed { index, option ->

            val label = option.first
            val value = option.second

            val chip = makeText(
                label,
                16f,
                darkText,
                headingFont
            ).apply {

                gravity = Gravity.CENTER

                setOnClickListener {

                    if (chipsEnabled) {

                        selectedDivision = value

                        refreshChips()
                    }
                }
            }

            noteChips.add(chip)

            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    0,
                    dp(50),
                    1f
                ).apply {

                    if (index > 0) {
                        marginStart = dp(8)
                    }
                }
            )
        }

        refreshChips()

        return row
    }

    private fun refreshChips() {

        noteChips.forEachIndexed { index, chip ->

            val selected =
                noteOptions[index].second == selectedDivision

            chip.background = GradientDrawable().apply {

                cornerRadius = dp(11).toFloat()

                if (selected) {

                    setColor(goldColor)

                    setStroke(
                        dp(3),
                        Color.rgb(120, 82, 30)
                    )

                } else {

                    setColor(parchmentDark)

                    setStroke(
                        dp(1),
                        Color.rgb(176, 144, 91)
                    )
                }
            }

            chip.alpha = if (chipsEnabled) 1f else 0.6f
        }
    }

    // --------------------------------------------------
    // VERTICAL PANEL DRAWABLE
    // --------------------------------------------------

    private inner class VerticalPanelDrawable(
        private val bitmap: Bitmap
    ) : Drawable() {

        private val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or
                Paint.FILTER_BITMAP_FLAG
        )

        override fun draw(canvas: Canvas) {

            val b = bounds

            if (b.width() <= 0 || b.height() <= 0) {
                return
            }

            val w = bitmap.width
            val h = bitmap.height

            val edgeX = (w * 0.16f).toInt()
                .coerceIn(1, w / 2)

            val edgeY = (h * 0.10f).toInt()
                .coerceIn(1, h / 2)

            val srcX = intArrayOf(
                0,
                edgeX,
                w - edgeX
            )

            val srcY = intArrayOf(
                0,
                edgeY,
                h - edgeY
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

            // Nine-slice rendering:
            // Preserve the border artwork while stretching
            // the center to fit the vertical panel.

            for (row in 0..2) {

                for (column in 0..2) {

                    val source = Rect(
                        srcX[column],
                        srcY[row],
                        if (column == 2) {
                            w
                        } else {
                            srcX[column + 1]
                        },
                        if (row == 2) {
                            h
                        } else {
                            srcY[row + 1]
                        }
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

            // Important:
            // Do not paint a solid cream rectangle over the
            // center of panel.png. Keep the original artwork visible.
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
        loadingAnimator = null

        val drawable =
            loadingFill.drawable as? ClipDrawable

        if (drawable == null) {

            // Keep the progress area usable even when the
            // optional loading image is missing.
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

        (loadingFill.drawable as? ClipDrawable)?.level = 10000
    }

    private fun hideLoadingAnimation() {

        loadingAnimator?.cancel()
        loadingAnimator = null

        loadingContainer.visibility = View.GONE

        (loadingFill.drawable as? ClipDrawable)?.level = 0
    }

    // --------------------------------------------------
    // BUTTONS
    // --------------------------------------------------

    private fun makeButton(
        text: String,
        action: () -> Unit
    ): Button {

        return Button(this).apply {

            this.text = text

            textSize = 16f

            typeface = headingFont

            setTextColor(
                Color.rgb(66, 55, 43)
            )

            isAllCaps = false

            backgroundTintList = null

            // New button artwork.
            background = buttonBaseBitmap?.let { bitmap ->

                BitmapDrawable(
                    resources,
                    bitmap
                ).apply {

                    gravity = Gravity.FILL

                    isFilterBitmap = true
                }

            } ?: stoneButtonBackground()

            elevation = dp(3).toFloat()

            setPadding(
                dp(12),
                dp(5),
                dp(12),
                dp(5)
            )

            // Press animation.
            setOnTouchListener { view, event ->

                when (event.actionMasked) {

                    MotionEvent.ACTION_DOWN -> {

                        view.animate()
                            .scaleX(0.97f)
                            .scaleY(0.97f)
                            .setDuration(70)
                            .start()
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {

                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(110)
                            .start()
                    }
                }

                // Return false so the Button retains its
                // normal click and pressed-state behavior.
                false
            }

            setOnClickListener {

                if (isEnabled) {
                    action()
                }
            }
        }
    }

    private fun stoneButtonBackground(): StateListDrawable {

        val states = StateListDrawable()

        val normal = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.rgb(220, 211, 190),
                Color.rgb(168, 158, 137),
                Color.rgb(133, 123, 105)
            )
        ).apply {

            cornerRadius = dp(13).toFloat()

            setStroke(
                dp(3),
                Color.rgb(91, 78, 60)
            )
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

            setStroke(
                dp(3),
                Color.rgb(78, 67, 52)
            )
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

            setStroke(
                dp(3),
                goldColor
            )
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
    // UI HELPERS
    // --------------------------------------------------

    private fun setStatus(
        message: String,
        isError: Boolean = false
    ) {

        statusText.text = message

        statusText.setTextColor(
            if (isError) errorText else darkText
        )
    }

    private fun setBusy(busy: Boolean) {

        convertButton.isEnabled = !busy

        convertButton.alpha =
            if (busy) 0.6f else 1f

        chipsEnabled = !busy

        refreshChips()
    }

    private fun ui(block: () -> Unit) {

        runOnUiThread {

            if (!isFinishing && !isDestroyed) {
                block()
            }
        }
    }

    // --------------------------------------------------
    // MIDI CONVERSION
    // --------------------------------------------------

    private fun convertMidi() {

        val midiUri = selectedMidi

        if (midiUri == null) {

            setStatus(
                "Please select a MIDI file first.",
                true
            )

            return
        }

        setBusy(true)

        setStatus("Reading your song...")

        startLoadingAnimation()

        val noteDivision = selectedDivision

        Thread {

            try {

                val midiBytes = contentResolver
                    .openInputStream(midiUri)
                    ?.use { it.readBytes() }
                    ?: throw IOException(
                        "Could not open MIDI file."
                    )

                ui {
                    setStatus("Reading MIDI tracks...")
                }

                // Parse individual instrument tracks.
                val parsedMidi = MidiReader.read(midiBytes)

                ui {

                    setStatus(
                        "Converting ${parsedMidi.tracks.size} instrument tracks..."
                    )
                }

                // Preserve the existing converter API.
                val msmBytes = MsmConverter.convert(
                    context = this@MainActivity,
                    midi = parsedMidi,
                    minimumNote = noteDivision
                )

                ui {

                    convertedMsm = msmBytes

                    stopLoadingAnimation()

                    setStatus(
                        "Conversion complete!\n" +
                            "${parsedMidi.tracks.size} instrument tracks processed."
                    )

                    setBusy(false)

                    val baseName = (
                        selectedName ?: "converted_song"
                    )
                        .substringBeforeLast('.')
                        .ifBlank {
                            "converted_song"
                        }

                    saveMsm.launch(
                        "$baseName.msm"
                    )
                }

            } catch (e: Exception) {

                ui {

                    convertedMsm = null

                    hideLoadingAnimation()

                    setStatus(
                        "Conversion failed:\n" +
                            (e.message ?: "Unknown error"),
                        true
                    )

                    setBusy(false)
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

            setStatus(
                "There is no converted song to save.",
                true
            )

            return
        }

        try {

            contentResolver.openOutputStream(uri)?.use {

                it.write(data)
                it.flush()

            } ?: throw IOException(
                "Could not create output file."
            )

            setStatus(
                "Your MSM song was saved successfully!"
            )

        } catch (e: Exception) {

            setStatus(
                "Could not save the file:\n${e.message}",
                true
            )
        }
    }

    // --------------------------------------------------
    // CLEANUP
    // --------------------------------------------------

    override fun onDestroy() {

        loadingAnimator?.cancel()
        loadingAnimator = null

        super.onDestroy()
    }
}
