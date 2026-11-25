
package com.dragosdomini.msmtools

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * MSM monster compatibility and assignment approval interface.
 *
 * Embed this component in the existing MainActivity.
 *
 * Existing MIDI import, assets, XML generation and
 * compression workflows remain in MainActivity.
 */
class MonsterCompatibilityPanel(
    private val context: Context,
    private val onAssignmentApproved: (
        monsterId: Int
    ) -> Unit
) {

    private var selectedMonsterId: Int = -1
    private var pendingMonsterId: Int? = null

    private var currentPitches: List<Int> = emptyList()

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var recommendations: LinearLayout

    private val accent = Color.rgb(116, 207, 173)
    private val dark = Color.rgb(35, 47, 49)
    private val panel = Color.rgb(48, 63, 65)
    private val light = Color.WHITE

    fun createView(): View {

        val scroll = ScrollView(context)

        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = rounded(dark, 22)
        }

        val title = TextView(context).apply {
            text = "MONSTER COMPATIBILITY"
            textSize = 21f
            setTextColor(light)
            typeface = Typeface.DEFAULT_BOLD
        }

        statusText = TextView(context).apply {
            textSize = 15f
            setTextColor(light)
            setPadding(0, 18, 0, 18)
        }

        recommendations = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(recommendations)

        scroll.addView(root)

        return scroll
    }

    /**
     * Call after MIDI analysis and initial monster selection.
     */
    fun analyze(
        monsterId: Int,
        pitches: List<Int>
    ) {

        selectedMonsterId = monsterId
        currentPitches = pitches.toList()

        refresh()
    }

    /**
     * Refresh selected monster compatibility and alternatives.
     */
    private fun refresh() {

        recommendations.removeAllViews()

        if (selectedMonsterId < 0 || currentPitches.isEmpty()) {
            statusText.text =
                "Import a MIDI file and select a monster."
            return
        }

        val selected = MonsterMapping.analyzeSelectedMonster(
            selectedMonsterId.toString(),
            currentPitches
        )

        if (selected == null) {
            statusText.text = "Selected monster was not found."
            return
        }

        statusText.text = buildString {
            append("Selected: ${selected.monster.name}\n")
            append("Compatibility: ${selected.score}%\n")
            append("Compatible notes: ")
            append("${selected.compatibleNotes}/${selected.totalNotes}\n")
            append("Outside range: ${selected.incompatibleNotes}\n")
            append("Octave adjustment: ")
            append("${selected.octaveShift} semitones\n\n")
            append(selected.explanation)
        }

        recommendations.addView(
            sectionTitle("ALTERNATIVE MONSTERS")
        )

        val alternatives = MonsterMapping.rankAlternatives(
            pitches = currentPitches,
            excludeMonsterId = selectedMonsterId.toString()
        )

        if (alternatives.isEmpty()) {
            recommendations.addView(
                message("No monsters have usable pitch data.")
            )
            return
        }

        alternatives.take(10).forEachIndexed { index, result ->

            recommendations.addView(
                createRecommendationCard(
                    rank = index + 1,
                    result = result
                )
            )
        }
    }

    private fun createRecommendationCard(
        rank: Int,
        result: MonsterCompatibility
    ): View {

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            background = rounded(panel, 16)
        }

        val title = TextView(context).apply {
            text = "#$rank  ${result.monster.name}"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(light)
        }

        val details = TextView(context).apply {
            textSize = 13f
            setTextColor(light)
            text = buildString {
                append("\nCompatibility: ${result.score}%\n")
                append("Notes accommodated: ")
                append("${result.compatibleNotes}/${result.totalNotes}\n")
                append("Octave shift: ${result.octaveShift}\n")
                append(result.explanation)
            }
        }

        val selectButton = Button(context).apply {

            text = if (result.accommodatesEntireTrack) {
                "Review monster assignment"
            } else {
                "Review partial match"
            }

            isEnabled = result.compatibleNotes > 0

            setOnClickListener {
                requestApproval(result)
            }
        }

        card.addView(title)
        card.addView(details)
        card.addView(selectButton)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 14
        }

        card.layoutParams = params

        return card
    }

    /**
     * Assignment changes only after explicit user approval.
     */
    private fun requestApproval(
        result: MonsterCompatibility
    ) {

        val proposedId = result.monster.id.toIntOrNull()

        if (proposedId == null) {
            Toast.makeText(
                context,
                "Invalid monster ID.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        pendingMonsterId = proposedId

        val warning = if (result.accommodatesEntireTrack) {
            "Every note fits this monster's pitch segments."
        } else {
            "This monster cannot accommodate every note. " +
                "${result.incompatibleNotes} notes remain incompatible."
        }

        AlertDialog.Builder(context)
            .setTitle("Confirm monster assignment?")
            .setMessage(
                "Current monster: ${
                    MonsterMapping.getMonster(
                        selectedMonsterId.toString()
                    )?.name ?: "Unknown"
                }\n\n" +
                "Proposed monster: ${result.monster.name}\n\n" +
                "Compatibility: ${result.score}%\n" +
                "Octave shift: ${result.octaveShift}\n\n" +
                warning +
                "\n\nYour current assignment will not change unless you approve."
            )
            .setNegativeButton("Keep current monster") { dialog, _ ->
                pendingMonsterId = null
                dialog.dismiss()
            }
            .setPositiveButton("Approve assignment") { dialog, _ ->

                val approved = pendingMonsterId

                if (approved != null) {

                    selectedMonsterId = approved

                    pendingMonsterId = null

                    onAssignmentApproved(approved)

                    refresh()

                    Toast.makeText(
                        context,
                        "Monster assignment updated.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                dialog.dismiss()
            }
            .show()
    }

    private fun sectionTitle(value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            setPadding(0, 18, 0, 12)
        }

    private fun message(value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 14f
            setTextColor(light)
            setPadding(0, 12, 0, 12)
        }

    private fun rounded(
        color: Int,
        radius: Float
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }
}
