
"""
Unified My Singing Monsters converter.

Android uses this module for both conversion directions.

MIDI -> MSM:
    convert_midi_data_to_msm()
    Receives parsed MIDI data from the Android MIDI reader.
    Handles monster assignments, pitch ranges, octave correction,
    XML generation, and LZ4 compression in Python.

MSM -> MIDI:
    convert_msm_to_midi()
    Uses the existing MSM-to-MIDI backend.

The older file-based MIDI converter remains available for compatibility.
Do not delete mid2msm.py or msm2mid.py yet.
"""

import importlib
import json
import math
import os
import threading
import xml.etree.ElementTree as ET

import lz4.block


# --------------------------------------------------
# FILE SETTINGS
# --------------------------------------------------

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MONSTER_DATA_PATH = os.path.join(BASE_DIR, "mdata.json")

_CONVERSION_LOCK = threading.RLock()

_midi_to_msm_engine = None
_msm_to_midi_engine = None


# --------------------------------------------------
# MSM NOTE MAPPING
# --------------------------------------------------

# These are the notes the MSM Composer format stores.
# Sounding MIDI pitches are converted to these values
# using each monster's pitch_segments data.

MIDI_TO_MSM_NOTE = {
    58: 31,
    59: 28,
    60: 32,
    61: 33,
    62: 36,
    63: 37,
    64: 40,
    65: 44,
    66: 45,
    67: 48,
    68: 49,
    69: 52,
    70: 53,
    71: 56,
    72: 60,
    73: 61,
    74: 64,
    75: 65,
    76: 68,
    77: 72,
    78: 73,
    79: 76,
    80: 77,
    81: 80,
    82: 81,
    83: 84,
    84: 85,
}

DEFAULT_PITCH_SEGMENTS = [[58, 84, 0]]

# Try the smallest whole-track octave shift first.
# Negative shifts win ties, matching the existing project logic.
OCTAVE_SHIFTS = sorted(
    range(-144, 145, 12),
    key=lambda shift: (abs(shift), shift)
)


# --------------------------------------------------
# LOAD CONVERSION ENGINES
# --------------------------------------------------

def _load_midi_to_msm_engine():
    global _midi_to_msm_engine

    if _midi_to_msm_engine is None:
        try:
            _midi_to_msm_engine = importlib.import_module("mid2msm")
        except Exception as exc:
            raise RuntimeError(
                "Could not load the legacy MIDI-to-MSM converter: {}".format(
                    exc
                )
            )

    return _midi_to_msm_engine


def _load_msm_to_midi_engine():
    global _msm_to_midi_engine

    if _msm_to_midi_engine is None:
        try:
            _msm_to_midi_engine = importlib.import_module("msm2mid")
        except Exception as exc:
            raise RuntimeError(
                "Could not load the MSM-to-MIDI converter: {}".format(exc)
            )

    return _msm_to_midi_engine


# --------------------------------------------------
# MONSTER DATABASE
# --------------------------------------------------

def load_monster_data():
    """
    Load and validate the monster database.
    """

    if not os.path.isfile(MONSTER_DATA_PATH):
        raise FileNotFoundError(
            "Monster database not found: {}".format(MONSTER_DATA_PATH)
        )

    try:
        with open(MONSTER_DATA_PATH, "r", encoding="utf-8") as file:
            data = json.load(file)

    except Exception as exc:
        raise ValueError(
            "Could not read the monster database: {}".format(exc)
        )

    if not isinstance(data, dict):
        raise ValueError("The monster database has an invalid format.")

    return data


def _get_monster_segments(monster):
    """
    Return validated sounding-pitch segments for a monster.

    Segment format:
        [lowest sounding pitch, highest sounding pitch, offset]

    Written MSM pitch:
        sounding pitch - offset

    Written pitches must be supported by MIDI_TO_MSM_NOTE.
    """

    segments = monster.get(
        "pitch_segments",
        DEFAULT_PITCH_SEGMENTS
    )

    if not isinstance(segments, list) or not segments:
        raise ValueError(
            "Invalid pitch range data for {}.".format(
                monster.get("name", "unknown monster")
            )
        )

    validated = []

    for segment in segments:

        if not isinstance(segment, (list, tuple)) or len(segment) != 3:
            raise ValueError(
                "Invalid pitch segment for {}.".format(
                    monster.get("name", "unknown monster")
                )
            )

        low, high, offset = (
            int(segment[0]),
            int(segment[1]),
            int(segment[2])
        )

        if low < 0 or high > 127 or high < low:
            raise ValueError(
                "Invalid pitch segment bounds for {}.".format(
                    monster.get("name", "unknown monster")
                )
            )

        written_low = low - offset
        written_high = high - offset

        if (
            written_low not in MIDI_TO_MSM_NOTE
            or written_high not in MIDI_TO_MSM_NOTE
        ):
            raise ValueError(
                "Pitch segment for {} does not map to valid MSM notes."
                .format(monster.get("name", "unknown monster"))
            )

        validated.append([low, high, offset])

    return validated


# --------------------------------------------------
# MONSTER RESOLUTION
# --------------------------------------------------

def _normalize_bank(bank):
    """
    Android stores bank as (MSB << 7) | LSB.
    The monster database stores the MSB.
    """

    bank = int(bank)

    if bank > 127:
        return bank >> 7

    return bank


def _resolve_monster(track, monster_data):
    """
    Resolve the approved monster assignment first.

    If no assignment was supplied, use exact MIDI
    program and bank matching.
    """

    assigned_id = track.get("monster_id")

    if assigned_id is not None:
        monster_id = str(int(assigned_id))

        monster = monster_data.get(monster_id)

        if monster is None:
            raise ValueError(
                "Selected monster ID {} does not exist.".format(
                    monster_id
                )
            )

        return monster_id, monster

    program = int(track.get("program", -1))
    bank = _normalize_bank(track.get("bank", 0))

    for monster_id, monster in monster_data.items():

        if (
            int(monster.get("midi_program", -1)) == program
            and int(monster.get("midi_bank_select_msb", 0)) == bank
        ):
            return monster_id, monster

    raise ValueError(
        "No exact monster mapping was found for {}.\n\n"
        "MIDI program: {}\n"
        "MIDI bank: {}\n\n"
        "Select a monster manually before exporting.".format(
            track.get("name", "unnamed track"),
            program,
            bank
        )
    )


# --------------------------------------------------
# PITCH CONVERSION
# --------------------------------------------------

def _written_pitch(sounding_pitch, segments):
    """
    Convert one sounding MIDI pitch into its stored MSM pitch.

    The first matching segment wins.
    """

    for low, high, offset in segments:

        if low <= sounding_pitch <= high:

            written = sounding_pitch - offset

            if written in MIDI_TO_MSM_NOTE:
                return written

    return None


def _find_whole_track_shift(pitches, segments):
    """
    Find one octave shift that makes every note playable.

    Using one shift preserves the original melodic intervals.
    """

    for shift in OCTAVE_SHIFTS:

        converted = []

        for pitch in pitches:

            written = _written_pitch(
                pitch + shift,
                segments
            )

            if written is None:
                converted = []
                break

            converted.append(written)

        if len(converted) == len(pitches):
            return shift, converted

    return None, None


def _find_single_note_shift(pitch, segments):
    """
    Find the smallest octave shift for one note.

    This is the fallback when no single octave shift can
    accommodate the entire track.

    It preserves each note's pitch class, but different
    notes may receive different octave shifts.
    """

    for shift in OCTAVE_SHIFTS:

        written = _written_pitch(
            pitch + shift,
            segments
        )

        if written is not None:
            return shift, written

    return None, None


def _convert_track_pitches(track, monster):
    """
    Convert every note in a track using its assigned monster.

    Strategy:
      1. Prefer one octave shift for the whole track.
      2. If impossible, fold notes individually by octave.
      3. Fail clearly if any note cannot be represented.
    """

    notes = track.get("notes", [])

    if not notes:
        raise ValueError(
            "Track {} contains no notes.".format(
                track.get("name", "unnamed track")
            )
        )

    segments = _get_monster_segments(monster)

    original_pitches = [
        int(note["pitch"])
        for note in notes
    ]

    if any(pitch < 0 or pitch > 127 for pitch in original_pitches):
        raise ValueError(
            "Track {} contains an invalid MIDI pitch.".format(
                track.get("name", "unnamed track")
            )
        )

    whole_shift, written_pitches = _find_whole_track_shift(
        original_pitches,
        segments
    )

    per_note_fallback = False
    applied_shifts = []

    if whole_shift is not None:

        applied_shifts = [
            whole_shift
            for _ in original_pitches
        ]

    else:

        per_note_fallback = True
        written_pitches = []

        for pitch in original_pitches:

            shift, written = _find_single_note_shift(
                pitch,
                segments
            )

            if written is None:
                raise ValueError(
                    "Pitch {} in track {} cannot fit the range of {}. "
                    "Choose another monster or transpose the part.".format(
                        pitch,
                        track.get("name", "unnamed track"),
                        monster.get("name", "unknown monster")
                    )
                )

            applied_shifts.append(shift)
            written_pitches.append(written)

    converted_notes = [
        MIDI_TO_MSM_NOTE[pitch]
        for pitch in written_pitches
    ]

    return {
        "notes": converted_notes,
        "whole_track_shift": whole_shift,
        "applied_shifts": applied_shifts,
        "per_note_fallback": per_note_fallback,
        "original_minimum": min(original_pitches),
        "original_maximum": max(original_pitches),
        "adjusted_minimum": min(
            pitch + shift
            for pitch, shift in zip(original_pitches, applied_shifts)
        ),
        "adjusted_maximum": max(
            pitch + shift
            for pitch, shift in zip(original_pitches, applied_shifts)
        ),
    }


# --------------------------------------------------
# MIDI DATA VALIDATION
# --------------------------------------------------

def _validate_midi_payload(payload, minimum_note):
    if not isinstance(payload, dict):
        raise ValueError("The MIDI conversion data is invalid.")

    if minimum_note not in (8, 16, 32):
        raise ValueError(
            "Minimum note must be 8, 16, or 32."
        )

    ticks_per_beat = int(payload.get("ticks_per_beat", 0))
    tempo = int(payload.get("tempo", 0))
    numerator = int(payload.get("time_numerator", 0))
    denominator = int(payload.get("time_denominator", 0))

    if ticks_per_beat <= 0:
        raise ValueError("Invalid MIDI ticks-per-beat value.")

    if tempo not in range(1, 1001):
        raise ValueError("Invalid MIDI tempo: {}.".format(tempo))

    if numerator not in range(1, 13):
        raise ValueError("Invalid MIDI time signature numerator.")

    if denominator not in (2, 4, 8):
        raise ValueError("Invalid MIDI time signature denominator.")

    tracks = payload.get("tracks")

    if not isinstance(tracks, list) or not tracks:
        raise ValueError("The MIDI contains no instrument tracks.")

    for track_index, track in enumerate(tracks):

        if not isinstance(track, dict):
            raise ValueError(
                "Invalid MIDI track data at index {}.".format(track_index)
            )

        notes = track.get("notes", [])

        if not isinstance(notes, list):
            raise ValueError(
                "Invalid note list in track {}.".format(track_index)
            )

        for note in notes:

            pitch = int(note.get("pitch", -1))
            start = int(note.get("start_tick", -1))
            end = int(note.get("end_tick", -1))

            if pitch not in range(128):
                raise ValueError(
                    "Invalid MIDI pitch in track {}.".format(
                        track.get("name", track_index)
                    )
                )

            if start < 0 or end <= start:
                raise ValueError(
                    "Invalid note timing in track {}.".format(
                        track.get("name", track_index)
                    )
                )

    return {
        "ticks_per_beat": ticks_per_beat,
        "tempo": tempo,
        "time_numerator": numerator,
        "time_denominator": denominator,
        "song_name": str(payload.get("song_name", "Song")),
        "tracks": tracks,
    }


# --------------------------------------------------
# NOTE TIMING
# --------------------------------------------------

def _round_positive(value):
    """
    Match Kotlin roundToInt() for non-negative values.
    """

    return int(math.floor(float(value) + 0.5))


def _calculate_note_division(ticks_per_beat, minimum_note):
    division = ticks_per_beat / (minimum_note / 4.0)

    return max(1, _round_positive(division))


def _quantize_position(ticks, note_division):
    ticks = int(ticks)

    if ticks < 0:
        raise ValueError("Negative note position detected.")

    return _round_positive(ticks / note_division)


def _quantize_duration(start_tick, end_tick, note_division):
    duration = int(end_tick) - int(start_tick)

    if duration <= 0:
        raise ValueError("Invalid note duration.")

    # Keep short notes instead of silently discarding them.
    return max(
        1,
        _round_positive(duration / note_division)
    )


# --------------------------------------------------
# XML HELPERS
# --------------------------------------------------

def _add(parent, tag, key, value):
    return ET.SubElement(
        parent,
        tag,
        {
            "key": str(key),
            "value": str(value)
        }
    )


def _add_array(parent, key, values):
    element = ET.SubElement(
        parent,
        "INTARRAY",
        {"key": key}
    )

    element.text = "".join(
        "{},".format(int(value))
        for value in values
    )

    return element


def _format_volume(value):
    value = max(0.0, min(1.0, float(value)))
    return "{:.6f}".format(value)


# --------------------------------------------------
# MSM XML GENERATION
# --------------------------------------------------

def _build_msm_xml(midi_data, minimum_note, monster_data):
    """
    Build the MSM SFSOBJECT XML structure.

    This replaces the old Kotlin XML generation.
    """

    ticks_per_beat = midi_data["ticks_per_beat"]
    note_division = _calculate_note_division(
        ticks_per_beat,
        minimum_note
    )

    root = ET.Element(
        "SFSOBJECT",
        {"key": "root"}
    )

    islands = ET.SubElement(
        root,
        "SFSARRAY",
        {"key": "islands"}
    )

    island = ET.SubElement(
        islands,
        "SFSOBJECT",
        {"key": ""}
    )

    _add(island, "INT", "island", 11)

    monsters_array = ET.SubElement(
        island,
        "SFSARRAY",
        {"key": "monsters"}
    )

    song_name = ET.SubElement(
        island,
        "STRING",
        {"key": "name", "value": midi_data["song_name"]}
    )

    song = ET.SubElement(
        island,
        "SFSOBJECT",
        {"key": "song"}
    )

    _add(song, "INT", "key_sig", 0)
    _add(song, "INT", "tempo", midi_data["tempo"])
    _add(song, "INT", "time_denom", midi_data["time_denominator"])
    _add(song, "INT", "time_numerator", midi_data["time_numerator"])

    track_assignments = ET.SubElement(
        song,
        "SFSARRAY",
        {"key": "tracks"}
    )

    _add(song, "LONG", "user", "1 0")
    _add(island, "LONG", "user", "1 0")
    _add(island, "LONG", "user_island_id", "1 0")

    tracks_array = ET.SubElement(
        root,
        "SFSARRAY",
        {"key": "tracks"}
    )

    reports = []
    converted_count = 0

    for index, track in enumerate(midi_data["tracks"]):

        notes = track.get("notes", [])

        if not notes:
            continue

        monster_id, monster = _resolve_monster(
            track,
            monster_data
        )

        pitch_result = _convert_track_pitches(
            track,
            monster
        )

        user_monster_id = index + 1
        user_track_id = index + 2

        x = (
            monster.get("pos_x", 0)
            if track.get("pos_x") is None
            else int(track["pos_x"])
        )

        y = (
            monster.get("pos_y", 0)
            if track.get("pos_y") is None
            else int(track["pos_y"])
        )

        flip = (
            monster.get("flip", 0)
            if track.get("flip") is None
            else int(track["flip"])
        )

        muted = int(track.get("muted", 0))
        volume = _format_volume(track.get("volume", 1.0))

        # ------------------------------------------
        # MONSTER ENTRY
        # ------------------------------------------

        monster_object = ET.SubElement(
            monsters_array,
            "SFSOBJECT",
            {"key": ""}
        )

        _add(monster_object, "INT", "flip", flip)
        _add(monster_object, "INT", "monster", monster_id)
        _add(monster_object, "INT", "pos_x", x)
        _add(monster_object, "INT", "pos_y", y)
        _add(monster_object, "INT", "muted", muted)

        _add(
            monster_object,
            "LONG",
            "user_monster_id",
            "{} 0".format(user_monster_id)
        )

        _add(
            monster_object,
            "DOUBLE",
            "volume",
            volume
        )

        # ------------------------------------------
        # MONSTER-TO-TRACK ASSIGNMENT
        # ------------------------------------------

        assignment = ET.SubElement(
            track_assignments,
            "SFSOBJECT",
            {"key": ""}
        )

        _add(
            assignment,
            "LONG",
            "monster",
            "{} 0".format(user_monster_id)
        )

        _add(
            assignment,
            "LONG",
            "track",
            "{} 0".format(user_track_id)
        )

        # ------------------------------------------
        # NOTE TIMING
        # ------------------------------------------

        note_lengths = []
        note_times = []

        for note in notes:

            start = _quantize_position(
                note["start_tick"],
                note_division
            )

            length = _quantize_duration(
                note["start_tick"],
                note["end_tick"],
                note_division
            )

            note_times.append(start)
            note_lengths.append(length)

        # ------------------------------------------
        # TRACK ENTRY
        # ------------------------------------------

        track_object = ET.SubElement(
            tracks_array,
            "SFSOBJECT",
            {"key": ""}
        )

        _add(track_object, "INT", "format", "2 0")

        _add_array(
            track_object,
            "lengths",
            note_lengths
        )

        ET.SubElement(
            track_object,
            "STRING",
            {
                "key": "name",
                "value": ""
            }
        )

        _add_array(
            track_object,
            "notes",
            pitch_result["notes"]
        )

        _add_array(
            track_object,
            "times",
            note_times
        )

        _add(
            track_object,
            "LONG",
            "user",
            "1 0"
        )

        _add(
            track_object,
            "LONG",
            "user_track_id",
            "{} 0".format(user_track_id)
        )

        converted_count += 1

        reports.append({
            "track_index": index,
            "track_name": str(track.get("name", "Track {}".format(index + 1))),
            "monster_id": int(monster_id),
            "monster_name": str(monster.get("name", "Unknown")),
            "original_minimum_pitch": pitch_result["original_minimum"],
            "original_maximum_pitch": pitch_result["original_maximum"],
            "adjusted_minimum_pitch": pitch_result["adjusted_minimum"],
            "adjusted_maximum_pitch": pitch_result["adjusted_maximum"],
            "whole_track_shift": pitch_result["whole_track_shift"],
            "per_note_fallback": pitch_result["per_note_fallback"],
            "per_note_shifts": pitch_result["applied_shifts"],
        })

    if converted_count == 0:
        raise ValueError(
            "No tracks were converted. The MIDI contains no playable notes."
        )

    # Reject assignments referring to missing or empty tracks.
    for index, track in enumerate(midi_data["tracks"]):

        if track.get("monster_id") is not None:
            if not track.get("notes"):
                raise ValueError(
                    "Monster assignment refers to an empty MIDI track: {}."
                    .format(index)
                )

    ET.SubElement(
        root,
        "INT",
        {
            "key": "versionCode",
            "value": "3"
        }
    )

    # ElementTree builds the same SFS structure while safely
    # escaping song names and other XML attribute values.
    ET.indent(root, space="\t", level=0)

    xml_body = ET.tostring(
        root,
        encoding="utf-8",
        short_empty_elements=True
    )

    xml_data = b'<?xml version="1.0"?>\n' + xml_body

    return xml_data, reports


# --------------------------------------------------
# PYTHON MIDI DATA -> MSM
# --------------------------------------------------

def convert_midi_data_to_msm(
    midi_json,
    output_path,
    minimum_note=8,
    write_xml=False
):
    """
    Convert Android's parsed MIDI data into an MSM file.

    Args:
        midi_json:
            JSON string containing the parsed MIDI information,
            including each track's notes and approved monster ID.

        output_path:
            Destination MSM file path.

        minimum_note:
            Note division: 8, 16, or 32.

        write_xml:
            Also write an uncompressed XML sidecar file.

    Returns:
        JSON string containing conversion details and track reports.
    """

    if not output_path:
        raise ValueError("An output MSM path is required.")

    try:
        payload = (
            json.loads(midi_json)
            if isinstance(midi_json, str)
            else midi_json
        )
    except Exception as exc:
        raise ValueError(
            "Could not read the Android MIDI conversion data: {}".format(
                exc
            )
        )

    midi_data = _validate_midi_payload(
        payload,
        int(minimum_note)
    )

    os.makedirs(
        os.path.dirname(os.path.abspath(output_path)),
        exist_ok=True
    )

    with _CONVERSION_LOCK:

        monster_data = load_monster_data()

        xml_data, reports = _build_msm_xml(
            midi_data,
            int(minimum_note),
            monster_data
        )

        if write_xml:
            xml_path = (
                os.path.splitext(output_path)[0] + ".xml"
            )

            with open(xml_path, "wb") as file:
                file.write(xml_data)

        compressed_data = lz4.block.compress(
            xml_data + b"\0",
            compression=9,
            mode="high_compression",
            store_size=False
        )

        with open(output_path, "wb") as file:
            file.write(compressed_data)

        if not os.path.isfile(output_path):
            raise RuntimeError(
                "Python did not create the MSM output file."
            )

        if os.path.getsize(output_path) == 0:
            raise RuntimeError(
                "Python generated an empty MSM file."
            )

    warnings = []

    for report in reports:

        if report["per_note_fallback"]:
            warnings.append(
                "Track '{}': no single octave shift could fit every note. "
                "Some notes were folded individually by octave, so the "
                "intervals between notes may change.".format(
                    report["track_name"]
                )
            )

    return json.dumps({
        "success": True,
        "output_path": output_path,
        "converted_tracks": len(reports),
        "warnings": warnings,
        "tracks": reports
    })


# --------------------------------------------------
# LEGACY FILE-BASED MIDI -> MSM
# --------------------------------------------------

def convert_midi_to_msm(
    input_path,
    output_path,
    tempo=None,
    division=None,
    min_note=None,
    write_xml=False
):
    """
    Legacy file-based MIDI-to-MSM conversion.

    The Android app now uses convert_midi_data_to_msm()
    so its approved monster assignments and parsed tracks
    are preserved.
    """

    if not input_path or not os.path.isfile(input_path):
        raise FileNotFoundError(
            "MIDI file not found: {}".format(input_path)
        )

    if not output_path:
        raise ValueError("An output MSM path is required.")

    if tempo is not None and tempo <= 0:
        raise ValueError("Tempo must be greater than zero.")

    if division is not None and division <= 0:
        raise ValueError("Division must be greater than zero.")

    if min_note is not None and min_note not in (8, 16, 32):
        raise ValueError("Minimum note must be 8, 16, or 32.")

    os.makedirs(
        os.path.dirname(os.path.abspath(output_path)),
        exist_ok=True
    )

    with _CONVERSION_LOCK:

        engine = _load_midi_to_msm_engine()

        result = engine.convert_midi_to_msm(
            input_path,
            output_path,
            tempo,
            division,
            min_note,
            write_xml
        )

    if not os.path.isfile(output_path):
        raise RuntimeError(
            "MIDI-to-MSM conversion did not create an output file."
        )

    if os.path.getsize(output_path) == 0:
        raise RuntimeError(
            "The generated MSM file is empty."
        )

    return result or output_path


# --------------------------------------------------
# MSM -> MIDI
# --------------------------------------------------

def convert_msm_to_midi(
    input_path,
    output_path,
    min_note=8,
    division=None,
    skip_time_signature=False,
    tempo=None
):
    """
    Convert an MSM composition into a MIDI file.
    """

    if not input_path or not os.path.isfile(input_path):
        raise FileNotFoundError(
            "MSM file not found: {}".format(input_path)
        )

    if not output_path:
        raise ValueError("An output MIDI path is required.")

    if min_note not in (8, 16, 32):
        raise ValueError("Minimum note must be 8, 16, or 32.")

    if division is not None and division <= 0:
        raise ValueError("Division must be greater than zero.")

    if tempo is not None and tempo <= 0:
        raise ValueError("Tempo must be greater than zero.")

    os.makedirs(
        os.path.dirname(os.path.abspath(output_path)),
        exist_ok=True
    )

    with _CONVERSION_LOCK:

        engine = _load_msm_to_midi_engine()

        result = engine.convert_msm_to_midi(
            input_path,
            output_path,
            min_note,
            division,
            skip_time_signature,
            tempo
        )

    if not os.path.isfile(output_path):
        raise RuntimeError(
            "MSM-to-MIDI conversion did not create a MIDI file."
        )

    if os.path.getsize(output_path) == 0:
        raise RuntimeError(
            "The generated MIDI file is empty."
        )

    return result or output_path


# --------------------------------------------------
# CONVERTER INFORMATION
# --------------------------------------------------

def get_converter_info():
    return {
        "name": "MSM Unified Converter",
        "version": "2.0.0",
        "midi_to_msm": True,
        "parsed_midi_to_msm": True,
        "msm_to_midi": True,
        "monster_database": os.path.isfile(MONSTER_DATA_PATH),
        "architecture": "Python conversion engine with Android MIDI data bridge"
    }
