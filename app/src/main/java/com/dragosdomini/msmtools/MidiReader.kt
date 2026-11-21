
package com.dragosdomini.msmtools

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.ArrayDeque

data class MidiNote(
    val pitch: Int,
    val startTick: Long,
    val endTick: Long
)

data class MidiTrackData(
    val name: String,
    val program: Int,
    val bank: Int,
    val volume: Float,
    val posX: Int?,
    val posY: Int?,
    val flip: Int?,
    val muted: Int,
    val notes: List<MidiNote>
)

data class ParsedMidi(
    val ticksPerBeat: Int,
    val tempo: Int,
    val timeNumerator: Int,
    val timeDenominator: Int,
    val songName: String,
    val tracks: List<MidiTrackData>
)

object MidiReader {

    private data class RawEvent(
        val status: Int,
        val data: ByteArray,
        val tick: Long
    )

    private data class InstrumentKey(
        val channel: Int,
        val program: Int,
        val bank: Int
    )

    private data class OpenNote(
        val startTick: Long,
        val program: Int,
        val bank: Int
    )

    private data class ChannelState(
        var program: Int = 0,
        var bank: Int = 0,
        var volume: Float = 1f,
        var posX: Int? = null,
        var posY: Int? = null,
        var flip: Int? = null,
        var muted: Int = 0
    )

    private data class PartBuilder(
        val key: InstrumentKey,
        val notes: MutableList<MidiNote> = mutableListOf(),
        var volume: Float = 1f,
        var posX: Int? = null,
        var posY: Int? = null,
        var flip: Int? = null,
        var muted: Int = 0
    )

    private data class TrackResult(
        val name: String,
        val parts: List<MidiTrackData>,
        val lastTick: Long
    )

    private data class Metadata(
        val tempo: Int = 120,
        val numerator: Int = 4,
        val denominator: Int = 4,
        val songName: String = ""
    )

    // --------------------------------------------------
    // MAIN MIDI READER
    // --------------------------------------------------

    fun read(bytes: ByteArray): ParsedMidi {

        if (bytes.size < 14) {
            throw IOException("MIDI file is too small.")
        }

        val input = DataInputStream(ByteArrayInputStream(bytes))

        if (readAscii(input, 4) != "MThd") {
            throw IOException("This is not a valid MIDI file.")
        }

        val headerLength = input.readInt()

        if (headerLength < 6 || headerLength > input.available() + 6) {
            throw IOException("Invalid MIDI header.")
        }

        val format = input.readUnsignedShort()
        val trackCount = input.readUnsignedShort()
        val division = input.readUnsignedShort()

        if (format != 0 && format != 1) {
            throw IOException(
                "Only MIDI Format 0 and Format 1 are supported."
            )
        }

        if (trackCount <= 0) {
            throw IOException("The MIDI file contains no tracks.")
        }

        if ((division and 0x8000) != 0 || division == 0) {
            throw IOException("Unsupported MIDI timing division.")
        }

        if (headerLength > 6) {
            input.skipBytes(headerLength - 6)
        }

        val parsedTracks = mutableListOf<MidiTrackData>()

        var songName = "Song"
        var tempo = 120
        var numerator = 4
        var denominator = 4

        repeat(trackCount) { trackIndex ->

            if (input.available() < 8) {
                throw IOException("Unexpected end of MIDI file.")
            }

            val chunkName = readAscii(input, 4)

            if (chunkName != "MTrk") {
                throw IOException("Invalid MIDI track chunk.")
            }

            val chunkLength = input.readInt()

            if (chunkLength < 0 || chunkLength > input.available()) {
                throw IOException("Invalid MIDI track length.")
            }

            val trackBytes = ByteArray(chunkLength)
            input.readFully(trackBytes)

            // Track zero can contain both metadata and actual notes.
            // Never discard it automatically.

            val result = parseTrack(trackBytes, trackIndex)

            if (trackIndex == 0) {

                val metadata = parseMetadata(trackBytes)

                tempo = metadata.tempo
                numerator = metadata.numerator
                denominator = metadata.denominator

                if (metadata.songName.isNotBlank()) {
                    songName = metadata.songName
                }
            }

            if (result.name.isNotBlank() &&
                trackIndex == 0 &&
                songName == "Song"
            ) {
                songName = result.name
            }

            parsedTracks.addAll(result.parts)
        }

        if (parsedTracks.isEmpty()) {
            throw IOException(
                "No playable MIDI notes were found. " +
                "Check that the MIDI file contains note events."
            )
        }

        return ParsedMidi(
            ticksPerBeat = division,
            tempo = tempo,
            timeNumerator = numerator,
            timeDenominator = denominator,
            songName = songName,
            tracks = parsedTracks
        )
    }

    // --------------------------------------------------
    // TRACK PARSING
    // --------------------------------------------------

    private fun parseTrack(
        bytes: ByteArray,
        trackIndex: Int
    ): TrackResult {

        val events = readEvents(bytes)

        var trackName = "Track ${trackIndex + 1}"

        val channels = mutableMapOf<Int, ChannelState>()

        // Each MIDI channel and instrument combination gets
        // its own independent part.

        val parts = linkedMapOf<InstrumentKey, PartBuilder>()

        // Notes are paired by channel AND pitch.
        // Each note remembers its instrument when it starts.

        val openNotes =
            mutableMapOf<Pair<Int, Int>, ArrayDeque<OpenNote>>()

        var lastTick = 0L

        for (event in events) {

            lastTick = maxOf(lastTick, event.tick)

            if (event.status == 0xFF) {

                if (event.data.isNotEmpty() &&
                    (event.data[0].toInt() and 0xFF) == 0x03
                ) {

                    val name = String(
                        event.data.copyOfRange(
                            1,
                            event.data.size
                        ),
                        Charsets.UTF_8
                    ).trim()

                    if (name.isNotBlank()) {
                        trackName = name
                    }
                }

                continue
            }

            val command = event.status and 0xF0
            val channel = event.status and 0x0F

            val state = channels.getOrPut(channel) {
                ChannelState()
            }

            when (command) {

                // --------------------------------------
                // NOTE ON
                // --------------------------------------

                0x90 -> {

                    if (event.data.size < 2) continue

                    val pitch =
                        event.data[0].toInt() and 0xFF

                    val velocity =
                        event.data[1].toInt() and 0xFF

                    if (velocity == 0) {

                        closeNote(
                            openNotes,
                            parts,
                            channel,
                            pitch,
                            event.tick
                        )

                    } else {

                        val key = InstrumentKey(
                            channel = channel,
                            program = state.program,
                            bank = state.bank
                        )

                        val part = parts.getOrPut(key) {
                            PartBuilder(
                                key = key,
                                volume = state.volume,
                                posX = state.posX,
                                posY = state.posY,
                                flip = state.flip,
                                muted = state.muted
                            )
                        }

                        val noteKey = channel to pitch

                        openNotes.getOrPut(noteKey) {
                            ArrayDeque()
                        }.addLast(
                            OpenNote(
                                startTick = event.tick,
                                program = state.program,
                                bank = state.bank
                            )
                        )
                    }
                }

                // --------------------------------------
                // NOTE OFF
                // --------------------------------------

                0x80 -> {

                    if (event.data.isNotEmpty()) {

                        val pitch =
                            event.data[0].toInt() and 0xFF

                        closeNote(
                            openNotes,
                            parts,
                            channel,
                            pitch,
                            event.tick
                        )
                    }
                }

                // --------------------------------------
                // CONTROL CHANGE
                // --------------------------------------

                0xB0 -> {

                    if (event.data.size < 2) continue

                    val controller =
                        event.data[0].toInt() and 0xFF

                    val value =
                        event.data[1].toInt() and 0xFF

                    when (controller) {

                        // Bank Select MSB
                        0 -> state.bank = value

                        // Volume
                        7 -> state.volume = value / 127f

                        // Position X
                        102 -> state.posX = value

                        // Position Y
                        103 -> state.posY = value

                        // Flip
                        104 -> state.flip =
                            if (value == 0) 0 else 1

                        // Muted
                        105 -> state.muted =
                            if (value == 0) 0 else 1
                    }
                }

                // --------------------------------------
                // PROGRAM CHANGE
                // --------------------------------------

                0xC0 -> {

                    if (event.data.isNotEmpty()) {

                        state.program =
                            event.data[0].toInt() and 0xFF
                    }
                }
            }
        }

        // Close unfinished notes at the end of the track.
        // This prevents accidental zero-length notes.

        for ((noteKey, starts) in openNotes) {

            val channel = noteKey.first
            val pitch = noteKey.second

            while (starts.isNotEmpty()) {

                val open = starts.removeFirst()

                val key = InstrumentKey(
                    channel = channel,
                    program = open.program,
                    bank = open.bank
                )

                val part = parts.getOrPut(key) {
                    PartBuilder(key)
                }

                if (lastTick > open.startTick) {

                    part.notes.add(
                        MidiNote(
                            pitch = pitch,
                            startTick = open.startTick,
                            endTick = lastTick
                        )
                    )
                }
            }
        }

        val outputParts = mutableListOf<MidiTrackData>()

        for ((key, part) in parts) {

            if (part.notes.isEmpty()) continue

            val channelName =
                if (parts.count { it.key.channel == key.channel } > 1 ||
                    channels.size > 1
                ) {
                    "Channel ${key.channel + 1}"
                } else {
                    ""
                }

            val instrumentName =
                getInstrumentName(key.program)

            val finalName = listOf(
                trackName,
                channelName,
                instrumentName
            ).filter { it.isNotBlank() }
                .joinToString(" - ")

            outputParts.add(
                MidiTrackData(
                    name = finalName,
                    program = key.program,
                    bank = key.bank,
                    volume = part.volume,
                    posX = part.posX,
                    posY = part.posY,
                    flip = part.flip,
                    muted = part.muted,
                    notes = part.notes.sortedBy {
                        it.startTick
                    }
                )
            )
        }

        return TrackResult(
            name = trackName,
            parts = outputParts,
            lastTick = lastTick
        )
    }

    // --------------------------------------------------
    // NOTE CLOSING
    // --------------------------------------------------

    private fun closeNote(
        openNotes: MutableMap<Pair<Int, Int>, ArrayDeque<OpenNote>>,
        parts: MutableMap<InstrumentKey, PartBuilder>,
        channel: Int,
        pitch: Int,
        endTick: Long
    ) {

        val noteKey = channel to pitch
        val starts = openNotes[noteKey] ?: return

        if (starts.isEmpty()) return

        val open = starts.removeFirst()

        val key = InstrumentKey(
            channel = channel,
            program = open.program,
            bank = open.bank
        )

        val part = parts.getOrPut(key) {
            PartBuilder(key)
        }

        if (endTick > open.startTick) {

            part.notes.add(
                MidiNote(
                    pitch = pitch,
                    startTick = open.startTick,
                    endTick = endTick
                )
            )
        }

        if (starts.isEmpty()) {
            openNotes.remove(noteKey)
        }
    }

    // --------------------------------------------------
    // INSTRUMENT NAMES
    // --------------------------------------------------

    private fun getInstrumentName(program: Int): String {

        val names = arrayOf(
            "Acoustic Grand Piano",
            "Bright Piano",
            "Electric Grand Piano",
            "Honky-tonk Piano",
            "Electric Piano 1",
            "Electric Piano 2",
            "Harpsichord",
            "Clavinet",
            "Celesta",
            "Glockenspiel",
            "Music Box",
            "Vibraphone",
            "Marimba",
            "Xylophone",
            "Tubular Bells",
            "Dulcimer",
            "Drawbar Organ",
            "Percussive Organ",
            "Rock Organ",
            "Church Organ",
            "Reed Organ",
            "Accordion",
            "Harmonica",
            "Tango Accordion",
            "Acoustic Guitar Nylon",
            "Acoustic Guitar Steel",
            "Electric Guitar Jazz",
            "Electric Guitar Clean",
            "Electric Guitar Muted",
            "Overdriven Guitar",
            "Distortion Guitar",
            "Guitar Harmonics",
            "Acoustic Bass",
            "Electric Bass Finger",
            "Electric Bass Pick",
            "Fretless Bass",
            "Slap Bass 1",
            "Slap Bass 2",
            "Synth Bass 1",
            "Synth Bass 2",
            "Violin",
            "Viola",
            "Cello",
            "Contrabass",
            "Tremolo Strings",
            "Pizzicato Strings",
            "Orchestral Harp",
            "Timpani",
            "String Ensemble 1",
            "String Ensemble 2",
            "Synth Strings 1",
            "Synth Strings 2",
            "Choir Aahs",
            "Voice Oohs",
            "Synth Voice",
            "Orchestra Hit",
            "Trumpet",
            "Trombone",
            "Tuba",
            "Muted Trumpet",
            "French Horn",
            "Brass Section",
            "Synth Brass 1",
            "Synth Brass 2",
            "Soprano Sax",
            "Alto Sax",
            "Tenor Sax",
            "Baritone Sax",
            "Oboe",
            "English Horn",
            "Bassoon",
            "Clarinet",
            "Piccolo",
            "Flute",
            "Recorder",
            "Pan Flute",
            "Blown Bottle",
            "Shakuhachi",
            "Whistle",
            "Ocarina",
            "Lead 1 Square",
            "Lead 2 Sawtooth",
            "Lead 3 Calliope",
            "Lead 4 Chiff",
            "Lead 5 Charang",
            "Lead 6 Voice",
            "Lead 7 Fifths",
            "Lead 8 Bass Lead",
            "Pad 1 New Age",
            "Pad 2 Warm",
            "Pad 3 Polysynth",
            "Pad 4 Choir",
            "Pad 5 Bowed",
            "Pad 6 Metallic",
            "Pad 7 Halo",
            "Pad 8 Sweep",
            "FX 1 Rain",
            "FX 2 Soundtrack",
            "FX 3 Crystal",
            "FX 4 Atmosphere",
            "FX 5 Brightness",
            "FX 6 Goblins",
            "FX 7 Echoes",
            "FX 8 Sci-fi",
            "Sitar",
            "Banjo",
            "Shamisen",
            "Koto",
            "Kalimba",
            "Bag Pipe",
            "Fiddle",
            "Shanai",
            "Tinkle Bell",
            "Agogo",
            "Steel Drums",
            "Woodblock",
            "Taiko Drum",
            "Melodic Tom",
            "Synth Drum",
            "Reverse Cymbal",
            "Guitar Fret Noise",
            "Breath Noise",
            "Seashore",
            "Bird Tweet",
            "Telephone Ring",
            "Helicopter",
            "Applause",
            "Gunshot"
        )

        return names.getOrElse(program) {
            "Program $program"
        }
    }

    // --------------------------------------------------
    // MIDI METADATA
    // --------------------------------------------------

    private fun parseMetadata(bytes: ByteArray): Metadata {

        val events = readEvents(bytes)

        var tempo = 120
        var numerator = 4
        var denominator = 4
        var songName = ""

        for (event in events) {

            if (event.status != 0xFF || event.data.isEmpty()) {
                continue
            }

            when (event.data[0].toInt() and 0xFF) {

                // Track / sequence name
                0x03, 0x02 -> {

                    val name = String(
                        event.data.copyOfRange(
                            1,
                            event.data.size
                        ),
                        Charsets.UTF_8
                    ).trim()

                    if (name.isNotBlank()) {
                        songName = name
                    }
                }

                // Tempo
                0x51 -> {

                    if (event.data.size >= 4) {

                        val micros =
                            ((event.data[1].toInt() and 0xFF) shl 16) or
                            ((event.data[2].toInt() and 0xFF) shl 8) or
                            (event.data[3].toInt() and 0xFF)

                        if (micros > 0) {
                            tempo =
                                (60_000_000.0 / micros).toInt()
                        }
                    }
                }

                // Time signature
                0x58 -> {

                    if (event.data.size >= 3) {

                        numerator =
                            event.data[1].toInt() and 0xFF

                        val power =
                            event.data[2].toInt() and 0xFF

                        if (power in 0..7) {
                            denominator = 1 shl power
                        }
                    }
                }
            }
        }

        return Metadata(
            tempo = tempo,
            numerator = numerator,
            denominator = denominator,
            songName = songName
        )
    }

    // --------------------------------------------------
    // MIDI EVENT READER
    // --------------------------------------------------

    private fun readEvents(bytes: ByteArray): List<RawEvent> {

        val input = DataInputStream(
            ByteArrayInputStream(bytes)
        )

        val events = mutableListOf<RawEvent>()

        var tick = 0L
        var runningStatus = 0

        while (input.available() > 0) {

            tick += readVariableLength(input)

            if (input.available() <= 0) break

            val first = input.readUnsignedByte()

            var status: Int
            var firstData: Int? = null

            if (first < 0x80) {

                if (runningStatus == 0) {
                    throw IOException(
                        "Invalid MIDI running status."
                    )
                }

                status = runningStatus
                firstData = first

            } else {
                status = first
            }

            // ------------------------------------------
            // META EVENTS
            // ------------------------------------------

            if (status == 0xFF) {

                runningStatus = 0

                if (input.available() < 1) {
                    throw IOException("Invalid MIDI meta event.")
                }

                val type = input.readUnsignedByte()
                val length = readVariableLength(input)

                if (length > input.available()) {
                    throw IOException("Invalid MIDI meta event length.")
                }

                val data = ByteArray(length.toInt())
                input.readFully(data)

                events.add(
                    RawEvent(
                        status = 0xFF,
                        data = byteArrayOf(type.toByte()) + data,
                        tick = tick
                    )
                )

                if (type == 0x2F) break

                continue
            }

            // ------------------------------------------
            // SYSEX EVENTS
            // ------------------------------------------

            if (status == 0xF0 || status == 0xF7) {

                runningStatus = 0

                val length = readVariableLength(input)

                if (length > input.available()) {
                    throw IOException("Invalid MIDI system event.")
                }

                input.skipBytes(length.toInt())
                continue
            }

            if (status >= 0xF0) {

                runningStatus = 0

                throw IOException(
                    "Unsupported MIDI system event."
                )
            }

            // ------------------------------------------
            // CHANNEL EVENTS
            // ------------------------------------------

            runningStatus = status

            val command = status and 0xF0

            val dataLength = when (command) {

                0xC0, 0xD0 -> 1

                0x80, 0x90, 0xA0,
                0xB0, 0xE0 -> 2

                else -> throw IOException(
                    "Unknown MIDI channel event."
                )
            }

            val data = ByteArray(dataLength)

            if (firstData != null) {

                data[0] = firstData.toByte()

                for (i in 1 until dataLength) {

                    if (input.available() <= 0) {
                        throw IOException(
                            "Unexpected end of MIDI event."
                        )
                    }

                    data[i] = input.readByte()
                }

            } else {

                if (input.available() < dataLength) {
                    throw IOException(
                        "Incomplete MIDI channel event."
                    )
                }

                input.readFully(data)
            }

            events.add(
                RawEvent(
                    status = status,
                    data = data,
                    tick = tick
                )
            )
        }

        return events
    }

    // --------------------------------------------------
    // VARIABLE LENGTH VALUES
    // --------------------------------------------------

    private fun readVariableLength(
        input: DataInputStream
    ): Long {

        var value = 0L
        var count = 0

        while (true) {

            if (input.available() <= 0) {
                throw IOException(
                    "Unexpected end of MIDI variable-length value."
                )
            }

            val byte = input.readUnsignedByte()

            value = (value shl 7) or
                (byte and 0x7F).toLong()

            count++

            if (byte and 0x80 == 0) break

            if (count >= 4) {
                throw IOException(
                    "Invalid MIDI variable-length value."
                )
            }
        }

        return value
    }

    // --------------------------------------------------
    // ASCII READER
    // --------------------------------------------------

    private fun readAscii(
        input: DataInputStream,
        length: Int
    ): String {

        val bytes = ByteArray(length)
        input.readFully(bytes)

        return String(
            bytes,
            Charsets.US_ASCII
        )
    }
}
