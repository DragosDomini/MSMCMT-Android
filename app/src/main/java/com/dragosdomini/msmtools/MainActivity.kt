
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

    private var bodyFont = Typeface.DEFAULT
    private var headingFont = Typeface.DEFAULT_BOLD

    private val noteOptions = listOf(
        "1/8" to 8,
        "1/16" to 16,
        "1/32" to 32
    )

    private var selectedDivision = 8
    private val noteChips = mutableListOf<TextView>()
    private var chipsEnabled = true

    // --------------------------------------------------
    // ASSETS
    // --------------------------------------------------

    private var backgroundBitmap: Bitmap? = null
    private var buttonBaseBitmap: Bitmap? = null
    private var panelBitmap: Bitmap? = null
    private var loadingBitmap: Bitmap? = null
    private var loadingBackgroundBitmap: Bitmap? = null

    // MSM-inspired palette

    private val purpleDark = Color.rgb(29, 19, 39)
    private val purplePanel = Color.rgb(48, 34, 61)
    private val purpleLight = Color.rgb(83, 57, 112)

    private val bronze = Color.rgb(169, 111, 64)
    private val bronzeDark = Color.rgb(100, 62, 43)

    private val cream = Color.rgb(255, 238, 204)
    private val creamMuted = Color.rgb(225, 207, 174)

    private val gold = Color.rgb(238, 185, 85)
    private val errorColor = Color.rgb(255, 133, 115)

    // --------------------------------------------------
    // FILE PICKERS
    // --------------------------------------------------

    private val openMidi =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
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

        window.statusBarColor = Color.rgb(18, 12, 25)
        window.navigationBarColor = Color.rgb(18, 12, 25)

        savedInstanceState?.let {

            selectedMidi = it.getString("midi_uri")?.let {
                Uri.parse(it)
            }

            selectedName = it.getString("midi_name")
            selectedDivision = it.getInt("division", 8)

            if (selectedDivision !in noteOptions.map { it.second }) {
                selectedDivision = 8
            }
        }

        loadFonts()
        loadAssets()

        window.setBackgroundDrawable(
            backgroundBitmap?.let {
                BackgroundDrawable(it)
            } ?: ColorDrawable(purpleDark)
        )

        buildInterface()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString("midi_uri", selectedMidi?.toString())
        outState.putString("midi_name", selectedName)
        outState.putInt("division", selectedDivision)
    }

    // --------------------------------------------------
    // FONTS
    // --------------------------------------------------

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

        // Keep headings readable.
        headingFont = Typeface.create(
            bodyFont,
            Typeface.BOLD
        )
    }

    // --------------------------------------------------
    // LOAD ARTWORK
    // --------------------------------------------------

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

        // Rotate panel artwork into portrait orientation.

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
    // FULL SCREEN BACKGROUND
    // --------------------------------------------------

    private inner class BackgroundDrawable(
        private val bitmap: Bitmap
    ) : Drawable() {

        private val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or
                Paint.FILTER_BITMAP_FLAG
        )

        override fun draw(canvas: Canvas) {

            val b = bounds

            if (b.width() <= 0 || b.height() <= 0) return

            val scale = maxOf(
                b.width().toFloat() / bitmap.width,
                b.height().toFloat() / bitmap.height
            )

            val width = bitmap.width * scale
            val height = bitmap.height * scale

            val left = b.centerX() - width / 2f
            val top = b.centerY() - height / 2f

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
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    // --------------------------------------------------
    // MAIN INTERFACE
    // --------------------------------------------------

    private fun buildInterface() {

        val container = FrameLayout(this).apply {
            background = backgroundBitmap?.let {
                BackgroundDrawable(it)
            } ?: ColorDrawable(purpleDark)
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        val root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            setPadding(
                dp(16),
                dp(20),
                dp(16),
                dp(30)
            )

            setBackgroundColor(Color.TRANSPARENT)
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

        val contentWidth = minOf(
            resources.displayMetrics.widthPixels - dp(32),
            dp(560)
        ).coerceAtLeast(dp(260))

        // --------------------------------------------------
        // HEADER
        // --------------------------------------------------

        val header = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(16)
            )
        }

        header.addView(
            makeText(
                "MSM MIDI TOOLS",
                29f,
                cream,
                headingFont
            ).apply {

                gravity = Gravity.CENTER

                setShadowLayer(
                    5f,
                    0f,
                    dp(2).toFloat(),
                    Color.BLACK
                )
            },
            matchWidth()
        )

        header.addView(
            makeText(
                "MIDI TO MONSTER COMPOSER",
                13f,
                gold,
                bodyFont
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            },
            matchWidth()
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                contentWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // --------------------------------------------------
        // MAIN DECORATIVE PANEL
        // --------------------------------------------------

        val workshop = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(34),
                dp(36),
                dp(34),
                dp(36)
            )

            background = panelBitmap?.let {
                OrnatePanelDrawable(it)
            } ?: fallbackPanel()

            elevation = dp(6).toFloat()
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
                21f,
                cream,
                headingFont
            ).apply {
                gravity = Gravity.CENTER
                setShadowLayer(3f, 0f, 2f, Color.BLACK)
            },
            matchWidth()
        )

        addDivider(workshop)

        // --------------------------------------------------
        // SONG SELECTION
        // --------------------------------------------------

        val fileCard = makeSection()

        addSectionTitle(
            fileCard,
            "1. CHOOSE YOUR SONG"
        )

        fileCard.addView(
            makeText(
                "Select a MIDI file from your device.",
                13f,
                creamMuted
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
            selectedName ?: "No song selected yet.",
            13f,
            cream
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

        addSpace(workshop, 16)

        // --------------------------------------------------
        // NOTE SETTINGS
        // --------------------------------------------------

        val settingsCard = makeSection()

        addSectionTitle(
            settingsCard,
            "2. NOTE SETTINGS"
        )

        settingsCard.addView(
            makeText(
                "Choose the shortest note length to preserve.",
                13f,
                creamMuted
            ).apply {
                setPadding(0, dp(7), 0, dp(13))
            },
            matchWidth()
        )

        settingsCard.addView(
            buildNoteSelector(),
            matchWidth()
        )

        workshop.addView(settingsCard, matchWidth())

        addSpace(workshop, 18)

        // --------------------------------------------------
        // CONVERT BUTTON
        // --------------------------------------------------

        convertButton = makeButton("CONVERT TO MSM") {
            convertMidi()
        }

        convertButton.textSize = 17f

        workshop.addView(
            convertButton,
            matchWidth(dp(65))
        )

        addSpace(workshop, 18)

        // --------------------------------------------------
        // PROGRESS
        // --------------------------------------------------

        val loadingCard = makeSection()

        addSectionTitle(
            loadingCard,
            "CONVERSION PROGRESS"
        )

        loadingCard.addView(
            makeText(
                "Preparing your monster composition...",
                12f,
                creamMuted
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(13))
            },
            matchWidth()
        )

        val loadingReference =
            loadingBackgroundBitmap ?: loadingBitmap

        val loadingWidth = (
            contentWidth - dp(112)
        ).coerceAtLeast(dp(110))

        val loadingHeight = if (
            loadingReference != null &&
            loadingReference.width > 0
        ) {
            (
                loadingWidth.toFloat() *
                    loadingReference.height /
                    loadingReference.width
            ).toInt().coerceAtLeast(dp(12))
        } else {
            dp(24)
        }

        loadingContainer = FrameLayout(this).apply {
            visibility = View.GONE
        }

        loadingBackground = ImageView(this).apply {

            scaleType = ImageView.ScaleType.FIT_CENTER

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

        addSpace(workshop, 16)

        // --------------------------------------------------
        // STATUS
        // --------------------------------------------------

        val statusCard = makeSection()

        addSectionTitle(
            statusCard,
            "WORKSHOP STATUS"
        )

        statusText = makeText(
            if (selectedMidi != null) {
                "Song selected. Ready to convert!"
            } else {
                "Choose a MIDI song to get started."
            },
            13f,
            cream
        ).apply {

            gravity = Gravity.CENTER

            background = insetBackground()

            setPadding(
                dp(10),
                dp(14),
                dp(10),
                dp(14)
            )
        }

        statusCard.addView(
            statusText,
            matchWidth().apply {
                topMargin = dp(8)
            }
        )

        workshop.addView(statusCard, matchWidth())

        root.addView(
            makeText(
                "MY SINGING MONSTERS COMPOSER TOOL",
                11f,
                cream
            ).apply {

                gravity = Gravity.CENTER

                setPadding(0, dp(20), 0, 0)

                setShadowLayer(
                    3f,
                    0f,
                    1f,
                    Color.BLACK
                )
            },
            matchWidth()
        )

        setContentView(container)
    }

    // --------------------------------------------------
    // SECTION HELPERS
    // --------------------------------------------------

    private fun addSectionTitle(
        parent: LinearLayout,
        title: String
    ) {

        parent.addView(
            makeText(
                title,
                16f,
                gold,
                headingFont
            ),
            matchWidth()
        )
    }

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
                    Color.rgb(59, 43, 72),
                    Color.rgb(38, 27, 49)
                )
            ).apply {

                cornerRadius = dp(12).toFloat()

                setStroke(
                    dp(1),
                    bronze
                )
            }

            elevation = dp(2).toFloat()
        }
    }

    private fun insetBackground(): GradientDrawable {

        return GradientDrawable().apply {

            setColor(
                Color.rgb(65, 47, 77)
            )

            cornerRadius = dp(8).toFloat()

            setStroke(
                dp(1),
                bronze
            )
        }
    }

    private fun addDivider(parent: LinearLayout) {

        parent.addView(
            View(this).apply {

                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(
                        Color.TRANSPARENT,
                        bronze,
                        gold,
                        bronze,
                        Color.TRANSPARENT
                    )
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2)
            ).apply {

                setMargins(
                    dp(8),
                    dp(13),
                    dp(8),
                    dp(17)
                )
            }
        )
    }

    // --------------------------------------------------
    // NOTE LENGTH SELECTOR
    // --------------------------------------------------

    private fun buildNoteSelector(): LinearLayout {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        noteChips.clear()

        noteOptions.forEachIndexed { index, option ->

            val chip = makeText(
                option.first,
                15f,
                cream,
                headingFont
            ).apply {

                gravity = Gravity.CENTER

                setOnClickListener {

                    if (chipsEnabled) {

                        selectedDivision = option.second

                        refreshChips()
                    }
                }
            }

            noteChips.add(chip)

            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    0,
                    dp(46),
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

                cornerRadius = dp(9).toFloat()

                if (selected) {

                    setColor(purpleLight)

                    setStroke(
                        dp(2),
                        gold
                    )

                } else {

                    setColor(purpleDark)

                    setStroke(
                        dp(1),
                        bronzeDark
                    )
                }
            }

            chip.alpha = if (chipsEnabled) 1f else 0.5f
        }
    }

    // --------------------------------------------------
    // ORNATE PANEL DRAWABLE
    // --------------------------------------------------

    private inner class OrnatePanelDrawable(
        private val bitmap: Bitmap
    ) : Drawable() {

        private val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or
                Paint.FILTER_BITMAP_FLAG
        )

        override fun draw(canvas: Canvas) {

            val b = bounds

            if (b.width() <= 0 || b.height() <= 0) return

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
                dp(30).toFloat(),
                b.width() / 4f
            )

            val borderY = minOf(
                dp(30).toFloat(),
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

            // Nine-slice rendering preserves the corners
            // while allowing the panel to grow vertically.

            for (row in 0..2) {

                for (column in 0..2) {

                    val source = Rect(
                        srcX[column],
                        srcY[row],
                        if (column == 2) w
                        else srcX[column + 1],
                        if (row == 2) h
                        else srcY[row + 1]
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
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun fallbackPanel(): Drawable {

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                purplePanel,
                purpleDark
            )
        ).apply {

            cornerRadius = dp(18).toFloat()

            setStroke(
                dp(3),
                bronze
            )
        }
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

            setTextColor(cream)

            isAllCaps = false
            backgroundTintList = null

            background = buttonBaseBitmap?.let { bitmap ->

                BitmapDrawable(
                    resources,
                    bitmap
                ).apply {

                    gravity = Gravity.FILL
                    isFilterBitmap = true
                }

            } ?: fallbackButton()

            elevation = dp(3).toFloat()

            setPadding(
                dp(12),
                dp(5),
                dp(12),
                dp(5)
            )

            setShadowLayer(
                2f,
                0f,
                1f,
                Color.BLACK
            )

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

                false
            }

            setOnClickListener {
                if (isEnabled) action()
            }
        }
    }

    private fun fallbackButton(): Drawable {

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                purpleLight,
                purpleDark
            )
        ).apply {

            cornerRadius = dp(12).toFloat()

            setStroke(
                dp(2),
                bronze
            )
        }
    }

    // --------------------------------------------------
    // LOADING BAR
    // --------------------------------------------------

    private fun startLoadingAnimation() {

        loadingContainer.visibility = View.VISIBLE

        loadingAnimator?.cancel()
        loadingAnimator = null

        val drawable =
            loadingFill.drawable as? ClipDrawable ?: return

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

        (loadingFill.drawable as? ClipDrawable)?.level = 10000
    }

    private fun hideLoadingAnimation() {

        loadingAnimator?.cancel()
        loadingAnimator = null

        loadingContainer.visibility = View.GONE

        (loadingFill.drawable as? ClipDrawable)?.level = 0
    }

    // --------------------------------------------------
    // TEXT AND LAYOUT HELPERS
    // --------------------------------------------------

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

            includeFontPadding = true
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

    // --------------------------------------------------
    // STATUS
    // --------------------------------------------------

    private fun setStatus(
        message: String,
        isError: Boolean = false
    ) {

        statusText.text = message

        statusText.setTextColor(
            if (isError) errorColor else cream
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
                    ?.use {
                        it.readBytes()
                    }
                    ?: throw IOException(
                        "Could not open MIDI file."
                    )

                ui {
                    setStatus("Reading MIDI tracks...")
                }

                val parsedMidi = MidiReader.read(midiBytes)

                ui {

                    setStatus(
                        "Converting ${parsedMidi.tracks.size} instrument tracks..."
                    )
                }

                // Existing converter API remains unchanged.

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
