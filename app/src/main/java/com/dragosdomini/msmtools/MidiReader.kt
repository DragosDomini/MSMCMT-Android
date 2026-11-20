
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

    private data class TrackMetadata(
        val tempo: Int?,
        val numerator: Int?,
        val denominator: Int?,
        val name: String
    )

    private data class TrackResult(
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

    fun read(bytes: ByteArray): ParsedMidi {
        val input = DataInputStream(ByteArrayInputStream(bytes))

        if (readAscii(input, 4) != "MThd") {
            throw IOException("This is not a valid MIDI file.")
        }

        val headerLength = input.readInt()

        if (headerLength < 6 || headerLength > bytes.size) {
            throw IOException("Invalid MIDI header.")
        }

        val format = input.readUnsignedShort()
        val trackCount = input.readUnsignedShort()
        val division = input.readUnsignedShort()

        if (format != 0 && format != 1) {
            throw IOException("Only MIDI Format 0 and Format 1 are supported.")
        }

        if (format == 0 && trackCount != 1) {
            throw IOException("Invalid Format 0 MIDI: expected one track.")
        }

        if (division and 0x8000 != 0) {
            throw IOException("SMPTE MIDI timing is not supported.")
        }

        if (division == 0) {
            throw IOException("Invalid MIDI timing division.")
        }

        if (headerLength > 6) {
            input.skipBytes(headerLength - 6)
        }

        var tempo = 120
        var numerator = 4
        var denominator = 4
        var songName = "Song"

        val parsedTracks = mutableListOf<MidiTrackData>()

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

            val events = readEvents(trackBytes)
            val metadata = parseMetadata(events)

            if (trackIndex == 0 && metadata.name.isNotBlank()) {
                songName = metadata.name
            } else if (songName == "Song" && metadata.name.isNotBlank()) {
                songName = metadata.name
            }

            if (metadata.tempo != null) {
                tempo = metadata.tempo
            }

            if (metadata.numerator != null) {
                numerator = metadata.numerator
            }

            if (metadata.denominator != null) {
                denominator = metadata.denominator
            }

            // Split each physical MIDI track by channel.
            // This also handles Format 0 files containing several instruments.
            val channelGroups = linkedMapOf<Int, MutableList<RawEvent>>()

            for (event in events) {
                if (event.status == 0xFF) {
                    continue
                }

                if (event.status >= 0xF0) {
                    continue
                }

                val channel = event.status and 0x0F

                channelGroups.getOrPut(channel) {
                    mutableListOf()
                }.add(event)
            }

            for ((channel, channelEvents) in channelGroups) {
                val result = parseChannel(
                    events = channelEvents,
                    trackName = metadata.name,
                    trackIndex = trackIndex,
                    channel = channel
                )

                // Only create an instrument track when it actually has notes.
                if (result.notes.isNotEmpty()) {
                    parsedTracks.add(
                        MidiTrackData(
                            name = result.name,
                            program = result.program,
                            bank = result.bank,
                            volume = result.volume,
                            posX = result.posX,
                            posY = result.posY,
                            flip = result.flip,
                            muted = result.muted,
                            notes = result.notes
                        )
                    )
                }
            }
        }

        if (parsedTracks.isEmpty()) {
            throw IOException(
                "No MIDI notes were found. Make sure the MIDI file contains playable notes."
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

    private fun parseMetadata(
        events: List<RawEvent>
    ): TrackMetadata {

        var tempo: Int? = null
        var numerator: Int? = null
        var denominator: Int? = null
        var name = ""

        for (event in events) {
            if (event.status != 0xFF || event.data.isEmpty()) {
                continue
            }

            when (event.data[0].toInt() and 0xFF) {
                0x03 -> {
                    val eventName = String(
                        event.data.copyOfRange(1, event.data.size),
                        Charsets.UTF_8
                    )

                    if (eventName.isNotBlank()) {
                        name = eventName
                    }
                }

                0x51 -> {
                    if (event.data.size >= 4) {
                        val micros =
                            ((event.data[1].toInt() and 0xFF) shl 16) or
                            ((event.data[2].toInt() and 0xFF) shl 8) or
                            (event.data[3].toInt() and 0xFF)

                        if (micros > 0) {
                            tempo = (60_000_000.0 / micros).toInt()
                        }
                    }
                }

                0x58 -> {
                    if (event.data.size >= 3) {
                        val newNumerator =
                            event.data[1].toInt() and 0xFF

                        val power =
                            event.data[2].toInt() and 0xFF

                        if (power <= 10) {
                            numerator = newNumerator
                            denominator = 1 shl power
                        }
                    }
                }
            }
        }

        return TrackMetadata(
            tempo = tempo,
            numerator = numerator,
            denominator = denominator,
            name = name
        )
    }

    private fun parseChannel(
        events: List<RawEvent>,
        trackName: String,
        trackIndex: Int,
        channel: Int
    ): TrackResult {

        var program = 0
        var bank = 0
        var volume = 1f
        var posX: Int? = null
        var posY: Int? = null
        var flip: Int? = null
        var muted = 0

        val openNotes = mutableMapOf<Int, ArrayDeque<Long>>()
        val notes = mutableListOf<MidiNote>()

        for (event in events) {
            val command = event.status and 0xF0

            when (command) {
                0x90 -> {
                    if (event.data.size < 2) continue

                    val pitch = event.data[0].toInt() and 0xFF
                    val velocity = event.data[1].toInt() and 0xFF

                    if (velocity == 0) {
                        closeNote(
                            openNotes,
                            notes,
                            pitch,
                            event.tick
                        )
                    } else {
                        openNotes.getOrPut(pitch) {
                            ArrayDeque()
                        }.addLast(event.tick)
                    }
                }

                0x80 -> {
                    if (event.data.isNotEmpty()) {
                        val pitch = event.data[0].toInt() and 0xFF

                        closeNote(
                            openNotes,
                            notes,
                            pitch,
                            event.tick
                        )
                    }
                }

                0xB0 -> {
                    if (event.data.size < 2) continue

                    val controller =
                        event.data[0].toInt() and 0xFF

                    val value =
                        event.data[1].toInt() and 0xFF

                    when (controller) {
                        0 -> bank = value
                        7 -> volume = value / 127f
                        102 -> posX = value
                        103 -> posY = value
                        104 -> flip = if (value == 0) 0 else 1
                        105 -> muted = if (value == 0) 0 else 1
                    }
                }

                0xC0 -> {
                    if (event.data.isNotEmpty()) {
                        program =
                            event.data[0].toInt() and 0xFF
                    }
                }
            }
        }

        // Close any notes that were left open at the end of the track.
        for ((pitch, starts) in openNotes) {
            while (starts.isNotEmpty()) {
                val start = starts.removeFirst()

                notes.add(
                    MidiNote(
                        pitch = pitch,
                        startTick = start,
                        endTick = start
                    )
                )
            }
        }

        val displayName = if (trackName.isNotBlank()) {
            "$trackName - Channel ${channel + 1}"
        } else {
            "Track ${trackIndex + 1} - Channel ${channel + 1}"
        }

        return TrackResult(
            name = displayName,
            program = program,
            bank = bank,
            volume = volume,
            posX = posX,
            posY = posY,
            flip = flip,
            muted = muted,
            notes = notes.sortedBy { it.startTick }
        )
    }

    private fun closeNote(
        openNotes: MutableMap<Int, ArrayDeque<Long>>,
        notes: MutableList<MidiNote>,
        pitch: Int,
        endTick: Long
    ) {
        val starts = openNotes[pitch] ?: return

        if (starts.isNotEmpty()) {
            val start = starts.removeFirst()

            notes.add(
                MidiNote(
                    pitch = pitch,
                    startTick = start,
                    endTick = endTick
                )
            )
        }
    }

    private fun readEvents(bytes: ByteArray): List<RawEvent> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val events = mutableListOf<RawEvent>()

        var tick = 0L
        var runningStatus = 0

        while (input.available() > 0) {
            tick += readVariableLength(input)

            val first = input.readUnsignedByte()

            val status: Int
            var firstData: Int? = null

            if (first < 0x80) {
                if (runningStatus == 0) {
                    throw IOException("Invalid MIDI running status.")
                }

                status = runningStatus
                firstData = first
            } else {
                status = first
            }

            if (status == 0xFF) {
                runningStatus = 0

                val type = input.readUnsignedByte()
                val length = readVariableLength(input)

                if (length > input.available()) {
                    throw IOException("Invalid MIDI meta event.")
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

                if (type == 0x2F) {
                    break
                }

                continue
            }

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
                throw IOException("Unsupported MIDI system event.")
            }

            runningStatus = status

            val command = status and 0xF0

            val dataLength = when (command) {
                0xC0, 0xD0 -> 1
                0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2
                else -> throw IOException("Unknown MIDI event.")
            }

            val data = ByteArray(dataLength)

            if (firstData != null) {
                data[0] = firstData.toByte()

                for (i in 1 until dataLength) {
                    data[i] = input.readByte()
                }
            } else {
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

    private fun readVariableLength(
        input: DataInputStream
    ): Long {
        var value = 0L
        var count = 0

        while (true) {
            val byte = input.readUnsignedByte()

            value = (value shl 7) or (byte and 0x7F).toLong()
            count++

            if (byte and 0x80 == 0) {
                break
            }

            if (count >= 4) {
                throw IOException("Invalid MIDI variable-length value.")
            }
        }

        return value
    }

    private fun readAscii(
        input: DataInputStream,
        length: Int
    ): String {
        val bytes = ByteArray(length)
        input.readFully(bytes)

        return String(bytes, Charsets.US_ASCII)
    }
}
