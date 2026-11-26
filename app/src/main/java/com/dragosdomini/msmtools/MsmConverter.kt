
package com.cyndyrfyrestudios.msmcmt

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Python bridge for MIDI -> MSM conversion.
 *
 * MIDI parsing and the monster assignment interface remain in Kotlin.
 * The actual MSM conversion now runs in msmconverter.py.
 *
 * Python handles:
 * - Monster resolution
 * - Monster-specific pitch ranges
 * - Whole-track octave correction
 * - Per-note octave fallback
 * - Note mapping
 * - Note timing and quantization
 * - MSM XML generation
 * - LZ4 compression
 */
object MsmConverter {

    private const val TAG = "MsmPythonConverter"

    private const val PYTHON_MODULE = "msmconverter"
    private const val PYTHON_FUNCTION = "convert_midi_data_to_msm"

    private const val OUTPUT_FILE_NAME =
        "msmcmt_python_midi_to_msm.msm"

    /**
     * Convert parsed MIDI data into an MSM composition.
     *
     * The method signature is intentionally preserved so
     * MainActivity does not need to be changed.
     */
    @Synchronized
    @Throws(IOException::class)
    fun convert(
        context: Context,
        midi: ParsedMidi,
        minimumNote: Int,
        monsterAssignments: Map<Int, Int> = emptyMap()
    ): ByteArray {

        validateInput(
            midi,
            minimumNote,
            monsterAssignments
        )

        val appContext = context.applicationContext

        val payload = buildMidiPayload(
            midi,
            monsterAssignments
        )

        val outputFile = File(
            appContext.cacheDir,
            OUTPUT_FILE_NAME
        )

        if (outputFile.exists() && !outputFile.delete()) {
            throw IOException(
                "Unable to clear the previous Python MSM output file."
            )
        }

        try {

            // Start Chaquopy if another conversion has not
            // already started the Python runtime.
            if (!Python.isStarted()) {
                Python.start(
                    AndroidPlatform(appContext)
                )
            }

            val python = Python.getInstance()

            val converter = python.getModule(
                PYTHON_MODULE
            )

            Log.i(
                TAG,
                "Starting Python MIDI-to-MSM conversion."
            )

            // The Python function writes the compressed MSM
            // file into the application's cache directory.
            val result = converter.callAttr(
                PYTHON_FUNCTION,
                payload,
                outputFile.absolutePath,
                minimumNote,
                false
            )

            if (!outputFile.exists()) {
                throw IOException(
                    "Python did not create the MSM output file."
                )
            }

            if (outputFile.length() <= 0L) {
                throw IOException(
                    "Python created an empty MSM output file."
                )
            }

            // Keep Python's conversion report in Logcat.
            Log.i(
                TAG,
                "Python conversion report: ${result.toString()}"
            )

            val outputBytes = outputFile.readBytes()

            if (outputBytes.isEmpty()) {
                throw IOException(
                    "The generated MSM file contains no data."
                )
            }

            Log.i(
                TAG,
                "Python MIDI-to-MSM conversion completed. " +
                    "Output size: ${outputBytes.size} bytes."
            )

            return outputBytes

        } catch (e: IOException) {

            throw e

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Python MIDI-to-MSM conversion failed.",
                e
            )

            throw IOException(
                "Python MIDI-to-MSM conversion failed: ${e.message}",
                e
            )

        } finally {

            // The bytes have already been returned to MainActivity.
            // The temporary file is no longer needed.
            if (outputFile.exists()) {
                outputFile.delete()
            }
        }
    }

    // --------------------------------------------------
    // INPUT VALIDATION
    // --------------------------------------------------

    private fun validateInput(
        midi: ParsedMidi,
        minimumNote: Int,
        monsterAssignments: Map<Int, Int>
    ) {

        if (minimumNote !in listOf(8, 16, 32)) {
            throw IOException(
                "Invalid note division. Select 1/8, 1/16, or 1/32."
            )
        }

        if (midi.ticksPerBeat <= 0) {
            throw IOException(
                "Invalid MIDI ticks-per-beat value."
            )
        }

        if (midi.tempo !in 1..1000) {
            throw IOException(
                "Invalid MIDI tempo: ${midi.tempo}."
            )
        }

        if (midi.timeNumerator !in 1..12) {
            throw IOException(
                "Invalid MIDI time signature numerator."
            )
        }

        if (midi.timeDenominator !in listOf(2, 4, 8)) {
            throw IOException(
                "Invalid MIDI time signature denominator."
            )
        }

        if (midi.tracks.isEmpty()) {
            throw IOException(
                "The MIDI contains no instrument tracks."
            )
        }

        midi.tracks.forEachIndexed { index, track ->

            track.notes.forEach { note ->

                if (note.pitch !in 0..127) {
                    throw IOException(
                        "Invalid MIDI pitch in ${track.name}."
                    )
                }

                if (
                    note.startTick < 0 ||
                    note.endTick <= note.startTick
                ) {
                    throw IOException(
                        "Invalid note timing in ${track.name}."
                    )
                }
            }

            if (
                monsterAssignments.containsKey(index) &&
                track.notes.isEmpty()
            ) {
                throw IOException(
                    "Monster assignment refers to an empty MIDI part: $index."
                )
            }
        }

        monsterAssignments.keys.forEach { index ->

            if (index !in midi.tracks.indices) {
                throw IOException(
                    "Monster assignment refers to an invalid MIDI part: $index."
                )
            }
        }

        if (midi.tracks.none { it.notes.isNotEmpty() }) {
            throw IOException(
                "The MIDI contains no playable notes."
            )
        }
    }

    // --------------------------------------------------
    // JSON PAYLOAD
    // --------------------------------------------------

    /**
     * Serialize ParsedMidi into a format understood by Python.
     *
     * Track indices are preserved exactly. This is important
     * because the monster assignment map uses those indices.
     */
    private fun buildMidiPayload(
        midi: ParsedMidi,
        monsterAssignments: Map<Int, Int>
    ): String {

        val root = JSONObject()

        root.put(
            "ticks_per_beat",
            midi.ticksPerBeat
        )

        root.put(
            "tempo",
            midi.tempo
        )

        root.put(
            "time_numerator",
            midi.timeNumerator
        )

        root.put(
            "time_denominator",
            midi.timeDenominator
        )

        root.put(
            "song_name",
            midi.songName
        )

        val tracksArray = JSONArray()

        midi.tracks.forEachIndexed { index, track ->

            val trackObject = JSONObject()

            trackObject.put(
                "name",
                track.name
            )

            trackObject.put(
                "program",
                track.program
            )

            trackObject.put(
                "bank",
                track.bank
            )

            trackObject.put(
                "volume",
                track.volume.toDouble()
            )

            trackObject.put(
                "pos_x",
                track.posX ?: JSONObject.NULL
            )

            trackObject.put(
                "pos_y",
                track.posY ?: JSONObject.NULL
            )

            trackObject.put(
                "flip",
                track.flip ?: JSONObject.NULL
            )

            trackObject.put(
                "muted",
                track.muted
            )

            // Preserve the user's approved monster assignment.
            trackObject.put(
                "monster_id",
                monsterAssignments[index] ?: JSONObject.NULL
            )

            val notesArray = JSONArray()

            track.notes.forEach { note ->

                val noteObject = JSONObject()

                noteObject.put(
                    "pitch",
                    note.pitch
                )

                noteObject.put(
                    "start_tick",
                    note.startTick
                )

                noteObject.put(
                    "end_tick",
                    note.endTick
                )

                notesArray.put(noteObject)
            }

            trackObject.put(
                "notes",
                notesArray
            )

            tracksArray.put(trackObject)
        }

        root.put(
            "tracks",
            tracksArray
        )

        return root.toString()
    }
}
