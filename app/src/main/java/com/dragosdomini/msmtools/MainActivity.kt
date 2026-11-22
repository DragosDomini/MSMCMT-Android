
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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException

class MainActivity : ComponentActivity() {

    // --------------------------------------------------
    // FILE STATE
    // --------------------------------------------------

    private var selectedMidi: Uri? = null
    private var selectedName: String? = null
    private var convertedMsm: ByteArray? = null

    private var isBusy = false

    // --------------------------------------------------
    // COMPOSITION STATE
    // --------------------------------------------------

    private var currentMidi: ParsedMidi? = null
    private var currentNoteDivision = 8

    private val monsterAssignments = mutableMapOf<Int, Int>()
    private val skippedParts = mutableSetOf<Int>()

    private var currentPartIndex = 0
    private var selectedMonsterId: Int? = null

    private var allMonsters: List<MonsterMapping.Monster> =
        emptyList()

    // --------------------------------------------------
    // UI
    // --------------------------------------------------

    private lateinit var statusText: TextView
    private lateinit var selectedFileText: TextView
    private lateinit var convertButton: Button

    private lateinit var loadingContainer: FrameLayout
    private lateinit var loadingFill: android.widget.ImageView

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
    private var buttonBitmap: Bitmap? = null
    private var panelBitmap: Bitmap? = null
    private var loadingBitmap: Bitmap? = null
    private var loadingBackgroundBitmap: Bitmap? = null

    // --------------------------------------------------
    // COLORS
    // --------------------------------------------------

    private val purpleDark = Color.rgb(29, 19, 39)
    private val purpleLight = Color.rgb(83, 57, 112)

    private val bronze = Color.rgb(169, 111, 64)
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
                    selectedName ?: "MIDI selected"

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

            selectedMidi = it.getString("midi_uri")?.let(Uri::parse)
            selectedName = it.getString("midi_name")
            selectedDivision = it.getInt("division", 8)
        }

        loadFonts()
        loadAssets()

        buildInterface()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString("midi_uri", selectedMidi?.toString())
        outState.putString("midi_name", selectedName)
        outState.putInt("division", selectedDivision)
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

        headingFont = Typeface.create(bodyFont, Typeface.BOLD)
    }

    private fun loadAssets() {

        backgroundBitmap = loadImage("images/background.jpg")
        buttonBitmap = loadImage("images/button_base.png")
        panelBitmap = loadImage("images/panel.png")
        loadingBitmap = loadImage("images/loadbar.png")
        loadingBackgroundBitmap = loadImage("images/loadbar_bg.png")

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
    // BACKGROUND
    // --------------------------------------------------

    private inner class BackgroundDrawable(
        private val bitmap: Bitmap
    ) : Drawable() {

        private val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
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

            canvas.drawBitmap(
                bitmap,
                null,
                RectF(
                    b.centerX() - width / 2f,
                    b.centerY() - height / 2f,
                    b.centerX() + width / 2f,
                    b.centerY() + height / 2f
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

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(20), dp(16), dp(30))
        }

        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val contentWidth = minOf(
            resources.displayMetrics.widthPixels - dp(32),
            dp(560)
        ).coerceAtLeast(dp(260))

        root.addView(
            makeText(
                "MSM MIDI TOOLS",
                29f,
                cream,
                headingFont
            ).apply {
                gravity = Gravity.CENTER
                setShadowLayer(5f, 0f, dp(2).toFloat(), Color.BLACK)
            },
            matchWidth()
        )

        root.addView(
            makeText(
                "MIDI TO MONSTER COMPOSER",
                13f,
                gold,
                headingFont
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(18))
            },
            matchWidth()
        )

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
            },
            matchWidth()
        )

        addDivider(workshop)

        val fileCard = makeSection()

        addSectionTitle(fileCard, "1. CHOOSE YOUR SONG")

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
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }

        fileCard.addView(
            selectedFileText,
            matchWidth().apply {
                topMargin = dp(10)
            }
        )

        workshop.addView(fileCard, matchWidth())

        addSpace(workshop, 16)

        val settingsCard = makeSection()

        addSectionTitle(settingsCard, "2. NOTE SETTINGS")

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

        convertButton = makeButton("CONVERT TO MSM") {
            convertMidi()
        }

        convertButton.textSize = 17f

        workshop.addView(
            convertButton,
            matchWidth(dp(65))
        )

        addSpace(workshop, 18)

        val loadingCard = makeSection()

        addSectionTitle(loadingCard, "CONVERSION PROGRESS")

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

        val loadingWidth = (contentWidth - dp(112)).coerceAtLeast(dp(110))

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

        val loadingBackground = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
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

        loadingFill = android.widget.ImageView(this).apply {

            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

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

        workshop.addView(loadingCard, matchWidth())

        addSpace(workshop, 16)

        val statusCard = makeSection()

        addSectionTitle(statusCard, "WORKSHOP STATUS")

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
            setPadding(dp(10), dp(14), dp(10), dp(14))
        }

        statusCard.addView(statusText, matchWidth())

        workshop.addView(statusCard, matchWidth())

        root.addView(
            makeText(
                "MY SINGING MONSTERS COMPOSER TOOL",
                11f,
                cream
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, 0)
            },
            matchWidth()
        )

        setContentView(container)
    }

    // --------------------------------------------------
    // NOTE SELECTOR
    // --------------------------------------------------

    private fun buildNoteSelector(): LinearLayout {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        noteChips.clear()

        noteOptions.forEachIndexed { index, option ->

            val chip = makeText(
                option.first,
                16f,
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
                    dp(50),
                    1f
                ).apply {
                    if (index > 0) marginStart = dp(8)
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
                    setColor(gold)
                    setStroke(dp(3), bronze)
                } else {
                    setColor(Color.rgb(65, 47, 79))
                    setStroke(dp(1), bronze)
                }
            }

            chip.alpha = if (chipsEnabled) 1f else 0.6f
        }
    }

    // --------------------------------------------------
    // ORNATE PANEL
    // --------------------------------------------------

    private inner class OrnatePanelDrawable(
        private val bitmap: Bitmap
    ) : Drawable() {

        private val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
        )

        private val interiorPaint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.rgb(48, 34, 61)
            alpha = 225
        }

        override fun draw(canvas: Canvas) {

            val b = bounds

            if (b.width() <= 0 || b.height() <= 0) return

            val w = bitmap.width
            val h = bitmap.height

            val edgeX = (w * 0.16f).toInt()
            val edgeY = (h * 0.10f).toInt()

            val srcX = intArrayOf(0, edgeX, w - edgeX)
            val srcY = intArrayOf(0, edgeY, h - edgeY)

            val borderX = minOf(dp(25).toFloat(), b.width() / 4f)
            val borderY = minOf(dp(25).toFloat(), b.height() / 4f)

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

            for (row in 0..2) {
                for (column in 0..2) {

                    val source = Rect(
                        srcX[column],
                        srcY[row],
                        if (column == 2) w else srcX[column + 1],
                        if (row == 2) h else srcY[row + 1]
                    )

                    canvas.drawBitmap(
                        bitmap,
                        source,
                        RectF(
                            dstX[column],
                            dstY[row],
                            dstX[column + 1],
                            dstY[row + 1]
                        ),
                        paint
                    )
                }
            }

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
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // --------------------------------------------------
    // CARD STYLING
    // --------------------------------------------------

    private fun makeSection(): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(dp(14), dp(15), dp(14), dp(15))

            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.rgb(65, 48, 78),
                    Color.rgb(42, 29, 55)
                )
            ).apply {
                cornerRadius = dp(15).toFloat()
                setStroke(dp(2), bronze)
            }

            elevation = dp(2).toFloat()
        }
    }

    private fun addSectionTitle(
        parent: LinearLayout,
        title: String
    ) {
        parent.addView(
            makeText(title, 17f, gold, headingFont),
            matchWidth()
        )
    }

    private fun fallbackPanel(): Drawable {

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(purpleLight, purpleDark)
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(3), gold)
        }
    }

    private fun insetBackground(): GradientDrawable {

        return GradientDrawable().apply {
            setColor(Color.rgb(54, 39, 68))
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), bronze)
        }
    }

    private fun addDivider(parent: LinearLayout) {

        parent.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(bronzeDark(), gold, bronzeDark())
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2)
            ).apply {
                setMargins(dp(5), dp(13), dp(5), dp(17))
            }
        )
    }

    private fun bronzeDark(): Int =
        Color.rgb(100, 62, 43)

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
            setTextColor(Color.rgb(255, 238, 204))

            isAllCaps = false
            backgroundTintList = null

            background = buttonBitmap?.let { bitmap ->
                BitmapDrawable(resources, bitmap).apply {
                    gravity = Gravity.FILL
                    isFilterBitmap = true
                }
            } ?: GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(purpleLight, purpleDark)
            ).apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), bronze)
            }

            elevation = dp(3).toFloat()

            setPadding(dp(12), dp(5), dp(12), dp(5))

            setOnClickListener {
                if (isEnabled) action()
            }
        }
    }

    // --------------------------------------------------
    // LOADING
    // --------------------------------------------------

    private fun startLoadingAnimation() {

        loadingContainer.visibility = View.VISIBLE

        loadingAnimator?.cancel()

        val drawable =
            loadingFill.drawable as? ClipDrawable ?: return

        drawable.level = 1000

        loadingAnimator = ValueAnimator.ofInt(1000, 9500).apply {

            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE

            addUpdateListener {
                drawable.level = it.animatedValue as Int
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
    // TEXT / LAYOUT HELPERS
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

        isBusy = busy

        convertButton.isEnabled = !busy
        convertButton.alpha = if (busy) 0.6f else 1f

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
    // MIDI READING
    // --------------------------------------------------

    private fun convertMidi() {

        if (isBusy) return

        val midiUri = selectedMidi

        if (midiUri == null) {
            setStatus("Please select a MIDI file first.", true)
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
                    ?: throw IOException("Could not open MIDI file.")

                val parsedMidi = MidiReader.read(midiBytes)

                if (parsedMidi.tracks.isEmpty()) {
                    throw IOException("No playable MIDI tracks found.")
                }

                val monsters = MonsterMapping.getAllMonsters(
                    this@MainActivity
                )

                if (monsters.isEmpty()) {
                    throw IOException("Monster database is empty.")
                }

                ui {

                    currentMidi = parsedMidi
                    currentNoteDivision = noteDivision

                    allMonsters = monsters

                    monsterAssignments.clear()
                    skippedParts.clear()

                    currentPartIndex = 0
                    selectedMonsterId = null

                    hideLoadingAnimation()
                    setBusy(false)

                    setStatus(
                        "Found ${parsedMidi.tracks.size} instrument parts.\n" +
                            "Begin assigning monsters."
                    )

                    showMonsterSelection()
                }

            } catch (e: Exception) {

                ui {
                    hideLoadingAnimation()
                    setBusy(false)

                    setStatus(
                        "MIDI reading failed:\n${e.message}",
                        true
                    )
                }
            }

        }.start()
    }

    // --------------------------------------------------
    // MONSTER ASSIGNMENT WORKFLOW
    // --------------------------------------------------

    private fun showMonsterSelection() {

        val midi = currentMidi ?: return

        if (isFinishing || isDestroyed) return

        if (currentPartIndex >= midi.tracks.size) {
            showCompositionReview()
            return
        }

        val track = midi.tracks[currentPartIndex]

        val suggestions = MonsterMapping.findMonsterSuggestions(
            context = this,
            program = track.program,
            bank = track.bank
        )

        val existingAssignment =
            monsterAssignments[currentPartIndex]

        val suggestedMonster =
            suggestions.firstOrNull()

        selectedMonsterId =
            existingAssignment ?: suggestedMonster?.id

        val recommendedIds = suggestions.map { it.id }.toSet()

        val orderedMonsters = (
            suggestions +
                allMonsters.filterNot {
                    it.id in recommendedIds
                }
            ).distinctBy { it.id }

        val instrumentName = getInstrumentName(track.program)

        val dialogLayout = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(dp(20), dp(8), dp(20), dp(8))

            setBackgroundColor(Color.rgb(48, 34, 61))
        }

        val progress = TextView(this).apply {

            text = "PART ${currentPartIndex + 1} OF ${midi.tracks.size}"

            textSize = 15f
            typeface = headingFont
            setTextColor(gold)

            gravity = Gravity.CENTER

            setPadding(0, dp(8), 0, dp(14))
        }

        dialogLayout.addView(progress, matchWidth())

        val progressBar = View(this).apply {

            background = GradientDrawable().apply {
                setColor(gold)
                cornerRadius = dp(5).toFloat()
            }
        }

        val progressBackground = FrameLayout(this).apply {

            background = GradientDrawable().apply {
                setColor(Color.rgb(75, 58, 87))
                cornerRadius = dp(5).toFloat()
            }

            addView(
                progressBar,
                FrameLayout.LayoutParams(
                    (
                        resources.displayMetrics.widthPixels *
                            0.7f *
                            (currentPartIndex + 1) /
                            midi.tracks.size
                        ).toInt().coerceAtLeast(dp(4)),
                    dp(8)
                )
            )
        }

        dialogLayout.addView(
            progressBackground,
            matchWidth(dp(8))
        )

        val description = TextView(this).apply {

            text = buildString {

                appendLine(track.name)
                appendLine()
                appendLine("Instrument: $instrumentName")
                appendLine("MIDI Program: ${track.program}")
                appendLine("MIDI Bank: ${track.bank}")
                appendLine("Notes: ${track.notes.size}")
                appendLine()

                if (suggestedMonster != null) {
                    appendLine(
                        "Suggested monster: ${suggestedMonster.name}"
                    )
                } else {
                    appendLine(
                        "No exact instrument match found."
                    )
                }

                append(
                    "Choose a monster for this instrument part."
                )
            }

            textSize = 14f
            typeface = bodyFont
            setTextColor(cream)
            setPadding(0, dp(15), 0, dp(12))
        }

        dialogLayout.addView(description, matchWidth())

        val selectedLabel = TextView(this).apply {

            textSize = 15f
            typeface = headingFont
            setTextColor(gold)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }

        fun updateSelectedLabel() {

            val selected = allMonsters.firstOrNull {
                it.id == selectedMonsterId
            }

            selectedLabel.text =
                if (selected != null) {
                    "Selected: ${selected.name}"
                } else {
                    "No monster selected"
                }
        }

        updateSelectedLabel()

        dialogLayout.addView(selectedLabel, matchWidth())

        val builder = AlertDialog.Builder(this)
            .setTitle("MONSTER ASSIGNMENT")
            .setView(dialogLayout)

            .setNegativeButton("CANCEL COMPOSITION") { dialog, _ ->
                dialog.dismiss()
                cancelComposition()
            }

            .setNeutralButton("SKIP PART") { dialog, _ ->

                skippedParts.add(currentPartIndex)
                monsterAssignments.remove(currentPartIndex)

                dialog.dismiss()

                currentPartIndex++
                showMonsterSelection()
            }

            .setPositiveButton("ACCEPT & NEXT", null)

        val dialog = builder.create()

        dialog.setOnShowListener {

            dialog.window?.setBackgroundDrawable(
                ColorDrawable(Color.rgb(48, 34, 61))
            )

            val positive = dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            )

            positive.setTextColor(gold)

            positive.setOnClickListener {

                val chosenId = selectedMonsterId

                if (chosenId == null) {

                    setStatus(
                        "Please select a monster first.",
                        true
                    )

                    return@setOnClickListener
                }

                monsterAssignments[currentPartIndex] = chosenId
                skippedParts.remove(currentPartIndex)

                dialog.dismiss()

                currentPartIndex++
                showMonsterSelection()
            }

            dialog.getButton(
                AlertDialog.BUTTON_NEGATIVE
            ).setTextColor(errorColor)

            dialog.getButton(
                AlertDialog.BUTTON_NEUTRAL
            ).setTextColor(creamMuted)
        }

        dialog.show()

        // Monster chooser.
        // A separate button allows selection without advancing.

        val chooseButton = Button(this).apply {

            text = "CHOOSE ANOTHER MONSTER"
            textSize = 13f
            typeface = headingFont
            setTextColor(cream)

            backgroundTintList = null

            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(purpleLight, purpleDark)
            ).apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), bronze)
            }

            setOnClickListener {

                val names = orderedMonsters.map { monster ->

                    val prefix =
                        if (monster.id in recommendedIds) {
                            "★ RECOMMENDED"
                        } else {
                            "MONSTER"
                        }

                    "$prefix  ${monster.name}"
                }.toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("CHOOSE A MONSTER")
                    .setItems(names) { _, which ->

                        selectedMonsterId =
                            orderedMonsters[which].id

                        updateSelectedLabel()
                    }
                    .setNegativeButton("BACK", null)
                    .show()
            }
        }

        dialogLayout.addView(
            chooseButton,
            matchWidth(dp(52))
        )

        dialogLayout.requestLayout()
    }

    // --------------------------------------------------
    // FINAL COMPOSITION REVIEW
    // --------------------------------------------------

    private fun showCompositionReview() {

        val midi = currentMidi ?: return

        val review = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(dp(18), dp(10), dp(18), dp(10))

            setBackgroundColor(Color.rgb(48, 34, 61))
        }

        review.addView(
            makeText(
                "COMPOSITION SUMMARY",
                20f,
                gold,
                headingFont
            ).apply {
                gravity = Gravity.CENTER
            },
            matchWidth()
        )

        review.addView(
            makeText(
                "Review your monster assignments before generating your song.",
                13f,
                cream,
            ).apply {
                setPadding(0, dp(10), 0, dp(12))
            },
            matchWidth()
        )

        midi.tracks.forEachIndexed { index, track ->

            val monsterId = monsterAssignments[index]

            val monster = allMonsters.firstOrNull {
                it.id == monsterId
            }

            val label = if (monster != null) {
                monster.name
            } else {
                "Skipped"
            }

            val row = TextView(this).apply {

                text = "${index + 1}. ${track.name}\n   → $label"

                textSize = 14f
                typeface = bodyFont
                setTextColor(cream)

                setPadding(dp(10), dp(9), dp(10), dp(9))

                background = GradientDrawable().apply {
                    setColor(Color.rgb(65, 48, 78))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), bronze)
                }

                setOnClickListener {

                    currentPartIndex = index
                    showMonsterSelection()
                }
            }

            review.addView(
                row,
                matchWidth().apply {
                    bottomMargin = dp(7)
                }
            )
        }

        review.addView(
            makeText(
                "Tap an assignment to change it.",
                12f,
                creamMuted
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            },
            matchWidth()
        )

        val scroll = ScrollView(this).apply {
            addView(review)
        }

        AlertDialog.Builder(this)
            .setTitle("FINAL REVIEW")
            .setView(scroll)
            .setNegativeButton("CANCEL") { dialog, _ ->
                dialog.dismiss()
                cancelComposition()
            }
            .setNeutralButton("BACK") { dialog, _ ->

                dialog.dismiss()

                currentPartIndex =
                    (midi.tracks.size - 1).coerceAtLeast(0)

                showMonsterSelection()
            }
            .setPositiveButton("GENERATE MSM") { dialog, _ ->

                dialog.dismiss()

                startConversionWithAssignments()
            }
            .show()
    }

    // --------------------------------------------------
    // CANCEL
    // --------------------------------------------------

    private fun cancelComposition() {

        monsterAssignments.clear()
        skippedParts.clear()

        currentMidi = null
        selectedMonsterId = null

        setStatus(
            "Monster selection cancelled.\nYour MIDI file is still selected."
        )
    }

    // --------------------------------------------------
    // GENERAL MIDI INSTRUMENT NAMES
    // --------------------------------------------------

    private fun getInstrumentName(program: Int): String {

        val names = arrayOf(
            "Acoustic Grand Piano", "Bright Piano",
            "Electric Grand Piano", "Honky-tonk Piano",
            "Electric Piano 1", "Electric Piano 2",
            "Harpsichord", "Clavinet", "Celesta",
            "Glockenspiel", "Music Box", "Vibraphone",
            "Marimba", "Xylophone", "Tubular Bells",
            "Dulcimer", "Drawbar Organ", "Percussive Organ",
            "Rock Organ", "Church Organ", "Reed Organ",
            "Accordion", "Harmonica", "Tango Accordion",
            "Acoustic Guitar Nylon", "Acoustic Guitar Steel",
            "Electric Guitar Jazz", "Electric Guitar Clean",
            "Electric Guitar Muted", "Overdriven Guitar",
            "Distortion Guitar", "Guitar Harmonics",
            "Acoustic Bass", "Electric Bass Finger",
            "Electric Bass Pick", "Fretless Bass",
            "Slap Bass 1", "Slap Bass 2",
            "Synth Bass 1", "Synth Bass 2",
            "Violin", "Viola", "Cello", "Contrabass",
            "Tremolo Strings", "Pizzicato Strings",
            "Orchestral Harp", "Timpani",
            "String Ensemble 1", "String Ensemble 2",
            "Synth Strings 1", "Synth Strings 2",
            "Choir Aahs", "Voice Oohs", "Synth Voice",
            "Orchestra Hit", "Trumpet", "Trombone",
            "Tuba", "Muted Trumpet", "French Horn",
            "Brass Section", "Synth Brass 1",
            "Synth Brass 2", "Soprano Sax", "Alto Sax",
            "Tenor Sax", "Baritone Sax", "Oboe",
            "English Horn", "Bassoon", "Clarinet",
            "Piccolo", "Flute", "Recorder", "Pan Flute",
            "Blown Bottle", "Shakuhachi", "Whistle",
            "Ocarina", "Lead 1 Square", "Lead 2 Sawtooth",
            "Lead 3 Calliope", "Lead 4 Chiff",
            "Lead 5 Charang", "Lead 6 Voice",
            "Lead 7 Fifths", "Lead 8 Bass Lead",
            "Pad 1 New Age", "Pad 2 Warm",
            "Pad 3 Polysynth", "Pad 4 Choir",
            "Pad 5 Bowed", "Pad 6 Metallic",
            "Pad 7 Halo", "Pad 8 Sweep",
            "FX 1 Rain", "FX 2 Soundtrack",
            "FX 3 Crystal", "FX 4 Atmosphere",
            "FX 5 Brightness", "FX 6 Goblins",
            "FX 7 Echoes", "FX 8 Sci-fi",
            "Sitar", "Banjo", "Shamisen", "Koto",
            "Kalimba", "Bag Pipe", "Fiddle", "Shanai",
            "Tinkle Bell", "Agogo", "Steel Drums",
            "Woodblock", "Taiko Drum", "Melodic Tom",
            "Synth Drum", "Reverse Cymbal",
            "Guitar Fret Noise", "Breath Noise",
            "Seashore", "Bird Tweet", "Telephone Ring",
            "Helicopter", "Applause", "Gunshot"
        )

        return names.getOrElse(program) {
            "Unknown Instrument ($program)"
        }
    }

    // --------------------------------------------------
    // CONVERSION
    // --------------------------------------------------

    private fun startConversionWithAssignments() {

        val midi = currentMidi ?: return

        if (isBusy) return

        if (monsterAssignments.size + skippedParts.size != midi.tracks.size) {

            setStatus(
                "Some instrument parts have not been reviewed.",
                true
            )

            return
        }

        if (monsterAssignments.isEmpty()) {

            setStatus(
                "No monsters were assigned. Nothing to convert.",
                true
            )

            return
        }

        setBusy(true)

        setStatus("Building your monster composition...")
        startLoadingAnimation()

        val assignments = monsterAssignments.toMap()
        val noteDivision = currentNoteDivision

        Thread {

            try {

                val msmBytes = MsmConverter.convert(
                    context = this@MainActivity,
                    midi = midi,
                    minimumNote = noteDivision,
                    monsterAssignments = assignments
                )

                if (msmBytes.isEmpty()) {
                    throw IOException(
                        "The converter returned an empty MSM file."
                    )
                }

                ui {

                    convertedMsm = msmBytes

                    stopLoadingAnimation()

                    setStatus(
                        "Conversion successful!\n\n" +
                            "Instrument parts: ${midi.tracks.size}\n" +
                            "Monsters assigned: ${assignments.size}\n" +
                            "Skipped parts: ${skippedParts.size}"
                    )

                    setBusy(false)

                    val baseName = (
                        selectedName ?: "converted_song"
                    ).substringBeforeLast('.')
                        .ifBlank { "converted_song" }

                    saveMsm.launch("$baseName.msm")
                }

            } catch (e: Exception) {

                ui {

                    convertedMsm = null

                    hideLoadingAnimation()

                    setStatus(
                        "Conversion failed:\n${e.message}",
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
            setStatus("There is no converted song to save.", true)
            return
        }

        try {

            contentResolver.openOutputStream(uri)?.use {
                it.write(data)
                it.flush()
            } ?: throw IOException("Could not create output file.")

            setStatus("Your MSM song was saved successfully!")

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
