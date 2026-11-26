#!/usr/bin/env python3
"""
add_full_pitch_range.py - per-monster pitch ranges for MSMCMT-Android.

The converter forces every track into MIDI 58..84 (Bb3..C6). In the
standalone My Singing Monsters Composer app, many monsters play lower or
higher than that. This script patches the project so MIDI notes are treated
as real (sounding) pitches and written according to each monster's range.

Files changed:
    mdata.json
    app/src/main/assets/mdata.json
    app/src/main/java/com/dragosdomini/msmtools/MsmConverter.kt
    mid2msm.py
    msm2mid.py

Usage (Python 3.8+, no extra packages):
    python add_full_pitch_range.py path/to/MSMCMT-Android --dry-run
    python add_full_pitch_range.py path/to/MSMCMT-Android

Running it twice is safe. After editing RANGES below, run it again to update
the two mdata.json files. If a source file no longer matches what the script
expects, nothing is written.
"""

import argparse
import json
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Monster ranges
#
# Source: https://mysingingmonsters.fandom.com/wiki/Monsters/Composer
#
# Each segment is [lowest, highest, offset]:
#   lowest/highest  sounding MIDI notes (middle C = C4 = 60)
#   offset          sounding note minus the note written in the .msm file
#                   (written notes are always 58..84)
# The offsets are worked out from the wiki ranges. Earlier segments win when
# two of them cover the same note.
#
# Monsters not listed here keep the old behaviour: [58, 84, 0].
# ---------------------------------------------------------------------------

HIGH = [[58, 84, 0]]          # Bb3..C6
MID = [[46, 72, -12]]         # Bb2..C5
LOW = [[34, 60, -24]]         # Bb1..C4
BASS = [[22, 48, -36]]        # Bb0..C3

RANGES = {
    # Bb3..C6
    "200": ("Tweedle", HIGH),
    "215": ("Reedling", HIGH),
    "253": ("Rare Deedge", HIGH),
    "270": ("Jeeode", HIGH),
    "281": ("Fung Pray", HIGH),
    # Bb2..C5
    "203": ("Toe Jammer", MID),
    "211": ("Furcorn", MID),
    "212": ("Fwog", MID),
    "214": ("Maw", MID),
    "219": ("PomPom", MID),
    "223": ("Bowgart", MID),
    "226": ("Quarrister", MID),
    "229": ("Entbrat", MID),
    "250": ("Dipsters", MID),
    "263": ("Rare Bowgart", MID),
    "267": ("Ghazt", MID),
    "271": ("Humbug", MID),
    "274": ("Sox", MID),
    "276": ("Arackulele", MID),
    "279": ("Bellowfish", MID),
    # Bb1..C4
    "201": ("Potbelly", LOW),
    "204": ("Mammott", LOW),
    "207": ("Quibble", LOW),
    "217": ("Thumpies", LOW),
    "228": ("Riff", LOW),
    "257": ("Rare Quibble", LOW),
    "273": ("Nebulob", LOW),
    "275": ("Jellbilly", LOW),
    "33": ("Yool", LOW),
    # Bb0..C3
    "256": ("Rare Riff", BASS),
    # Shorter ranges
    "205": ("Dandidoo", [[58, 72, 0]]),
    "32": ("Punkleton", [[46, 60, -12]]),
    # The lower and upper halves of the staff sit in different octaves
    "52": ("Hoola", [[58, 70, 0], [60, 72, -12]]),
    "206": ("Cybop", [[46, 59, -12], [48, 60, -24]]),
    "268": ("Grumpyre", [[46, 58, -12], [47, 60, -24]]),
    # The wiki says "B3-C4" for the upper half; read here as a typo for B2-C4
    "34": ("Schmoochle", [[46, 58, -12], [47, 60, -24]]),
    "210": ("Oaktopus", [[34, 47, -24], [34, 48, -36]]),
    "278": ("Kazilleon", [[34, 47, -24], [35, 47, -36]]),
    # Wiki: Bb5..Db8, and the notes do not line up with the staff. Approximate.
    "216": ("Spunge", [[82, 108, 24]]),
}

KOTLIN = "app/src/main/java/com/dragosdomini/msmtools/MsmConverter.kt"
JSON_FILES = ["mdata.json", "app/src/main/assets/mdata.json"]

# ---------------------------------------------------------------------------
# Source edits: (old text, new text)
# ---------------------------------------------------------------------------

KOTLIN_EDITS = [
    # data classes
    ('''        val program: Int,
        val bank: Int
    )

    data class PitchAdjustment(''',
     '''        val program: Int,
        val bank: Int,
        val pitchSegments: List<PitchSegment> = defaultSegments
    )

    /**
     * Sounding MIDI pitches low..high are written in the MSM file
     * as (pitch - offset).
     */
    data class PitchSegment(
        val low: Int,
        val high: Int,
        val offset: Int
    )

    private val defaultSegments = listOf(PitchSegment(58, 84, 0))

    data class PitchAdjustment('''),

    ('''        val pitches: List<Int>,
        val cost: Int''',
     '''        val pitches: List<Int>,
        val writtenPitches: List<Int>,
        val cost: Int'''),

    # analysis report
    ('''findBestOctaveSolution(track.notes)

            val originalPitches''',
     '''findBestOctaveSolution(
                track.notes,
                monster.pitchSegments,
                monster.name
            )

            val originalPitches'''),

    # database loading
    ('''                    program = program,
                    bank = bank
                )''',
     '''                    program = program,
                    bank = bank,
                    pitchSegments = parsePitchSegments(entry, name)
                )'''),

    ('''    // --------------------------------------------------
    // BANK NORMALIZATION''',
     '''    private fun parsePitchSegments(
        entry: JSONObject,
        name: String
    ): List<PitchSegment> {

        if (!entry.has("pitch_segments")) return defaultSegments

        val invalid = "Invalid pitch_segments for $name in mdata.json."

        val array = entry.optJSONArray("pitch_segments")
            ?: throw IOException(invalid)

        val segments = (0 until array.length()).map { index ->

            val values = array.optJSONArray(index)

            if (values == null || values.length() != 3) {
                throw IOException(invalid)
            }

            val segment = PitchSegment(
                low = values.optInt(0, -1),
                high = values.optInt(1, -1),
                offset = values.optInt(2, 999)
            )

            if (segment.low !in 0..127 ||
                segment.high !in segment.low..127 ||
                (segment.low - segment.offset) !in playablePitches ||
                (segment.high - segment.offset) !in playablePitches
            ) {
                throw IOException(invalid)
            }

            segment
        }

        if (segments.isEmpty()) throw IOException(invalid)

        return segments
    }

    // --------------------------------------------------
    // BANK NORMALIZATION'''),

    # octave correction
    ('''     * The supported conversion pitches are 58..84.
     *
     * This is a Composer pitch-range correction,
     * NOT a verified monster-specific pitch range.
     */''',
     '''     * Every shifted note must be inside the monster's range
     * (pitch_segments in mdata.json).
     */'''),

    ('''    private fun findBestOctaveSolution(
        notes: List<MidiNote>
    ): OctaveSolution {''',
     '''    private fun writtenPitch(
        pitch: Int,
        segments: List<PitchSegment>
    ): Int? {

        return segments
            .firstOrNull { pitch in it.low..it.high }
            ?.let { pitch - it.offset }
    }

    private fun findBestOctaveSolution(
        notes: List<MidiNote>,
        segments: List<PitchSegment>,
        monsterName: String
    ): OctaveSolution {'''),

    ('''            // Reject octave shifts that place any note
            // outside the available Composer pitch map.

            if (adjusted.any { it !in playablePitches }) {
                continue
            }
''',
     '''            // Reject octave shifts that place any note
            // outside this monster's range.

            val written = adjusted.mapNotNull {
                writtenPitch(it, segments)
            }

            if (written.size != adjusted.size) {
                continue
            }
'''),

    ('''                pitches = adjusted,
                cost = cost''',
     '''                pitches = adjusted,
                writtenPitches = written,
                cost = cost'''),

    ('''        return best ?: throw IOException(
            "The notes in this track cannot fit inside the " +
                "available Composer pitch range using octave correction. " +
                "Manual musical transposition is required."
        )''',
     '''        return best ?: throw IOException(
            "The notes in this track cannot fit inside the range of " +
                "$monsterName (MIDI ${segments.minOf { it.low }}.." +
                "${segments.maxOf { it.high }}) using octave correction. " +
                "Choose another monster or transpose the part."
        )'''),

    ('''    fun getPitchAdjustments(
        notes: List<MidiNote>
    ): List<PitchAdjustment> {

        val solution = findBestOctaveSolution(notes)
''',
     '''    fun getPitchAdjustments(
        notes: List<MidiNote>,
        monster: Monster? = null
    ): List<PitchAdjustment> {

        val solution = findBestOctaveSolution(
            notes,
            monster?.pitchSegments ?: defaultSegments,
            monster?.name ?: "the default monster"
        )
'''),

    ('''                msmNote = noteMap[pitch]
                    ?: throw''',
     '''                msmNote = noteMap[solution.writtenPitches[index]]
                    ?: throw'''),

    # XML generation
    ('''            val pitchSolution = findBestOctaveSolution(
                track.notes
            )''',
     '''            val pitchSolution = findBestOctaveSolution(
                track.notes,
                monster.pitchSegments,
                monster.name
            )'''),

    ('''                val pitch = pitchSolution.pitches[noteIndex]
''',
     '''                val pitch = pitchSolution.writtenPitches[noteIndex]
'''),
]

MID2MSM_EDITS = [
    ('''MonstersList = []
TracksList = []
''',
     '''MonstersList = []
TracksList = []

DEFAULT_PITCH_SEGMENTS = [[58, 84, 0]]
OCTAVE_SHIFTS = sorted(range(-144, 145, 12), key=lambda s: (abs(s), s))

def written_pitch(pitch, segments):
    for low, high, offset in segments:
        if low <= pitch <= high:
            return pitch - offset
    return None

def find_shift(pitches, segments):
    """Smallest octave shift that puts every pitch in range, else None."""
    for shift in OCTAVE_SHIFTS:
        if all(written_pitch(p + shift, segments) is not None for p in pitches):
            return shift
    return None
'''),

    # The monster must be known before its notes can be converted.
    ('''            for l,key in enumerate(allnotesdata):
                note = key['note']
                if note <= 57 and note >= 36:
                    note = note + 24
                elif note <= 96 and note >= 85:
                    note = note - 12
                elif note >= 58 and note <= 84:
                    note = note
                else:
                    print("Key out of range Track {} key {}".format(i,note))
                    sys.exit(1)
''',
     '''            for key in monster_data:
                if(monster_data[key]['midi_program'] == midi_program and monster_data[key]['midi_bank_select_msb'] == midi_bank_select):
                    tmpMonster.monster_id = int(key)
                    break

            monster = monster_data[str(tmpMonster.monster_id)]
            segments = monster.get('pitch_segments', DEFAULT_PITCH_SEGMENTS)
            track_shift = find_shift([n['note'] for n in allnotesdata], segments)
            if track_shift is None:
                print("Track {} is wider than the range of {}: moving notes by octave one at a time".format(i, monster['name']))

            for l,key in enumerate(allnotesdata):
                note = key['note']
                shift = track_shift if track_shift is not None else find_shift([note], segments)
                if shift is None:
                    print("Key out of range Track {} key {}".format(i,note))
                    sys.exit(1)
                note = written_pitch(note + shift, segments)
'''),

    # Remove the old, later monster lookup.
    (re.compile(
        r"\n[ \t]*\n            for key in monster_data:\n"
        r"                if\(monster_data\[key\]\['midi_program'\] == midi_program"
        r"[^\n]*\n"
        r"                    tmpMonster\.monster_id = int\(key\)[ \t]+\n"
        r"                    break\n"),
     "\n"),
]

MSM2MID_EDITS = [
    ('''# Class
''',
     '''def sounding_pitch(written, monster):
    """Convert a note written in the MSM file to the pitch the monster plays."""
    segments = monster.get('pitch_segments', [[58, 84, 0]])
    for low, high, offset in segments:
        if low - offset <= written <= high - offset:
            return written + offset
    return written + segments[0][2]

# Class
'''),
    ('''pitch=msm_to_midi_note[xnote], ''',
     '''pitch=sounding_pitch(msm_to_midi_note[xnote], monsters_data[mlist.monster_id]), '''),
]

SOURCES = [
    (KOTLIN, "parsePitchSegments", KOTLIN_EDITS),
    ("mid2msm.py", "DEFAULT_PITCH_SEGMENTS", MID2MSM_EDITS),
    ("msm2mid.py", "def sounding_pitch", MSM2MID_EDITS),
]


class PatchError(Exception):
    pass


def read(path):
    """Return (text with LF endings, original newline style)."""
    if not path.is_file():
        raise PatchError(f"missing file: {path}")
    raw = path.read_bytes().decode("utf-8")
    newline = "\r\n" if "\r\n" in raw else "\n"
    return raw.replace("\r\n", "\n"), newline


def patch_source(name, text, marker, edits):
    if marker in text:
        return text
    for number, (old, new) in enumerate(edits, 1):
        if isinstance(old, str):
            count = text.count(old)
            result = text.replace(old, new)
        else:
            result, count = old.subn(lambda _: new, text)
        if count != 1:
            raise PatchError(
                f"{name}: edit {number} expected 1 match, found {count}. "
                "The file differs from the version this script was written for."
            )
        text = result
    return text


def patch_json(name, text):
    try:
        data = json.loads(text)
    except json.JSONDecodeError as error:
        raise PatchError(f"{name}: invalid JSON ({error})")

    for monster_id, (monster_name, segments) in RANGES.items():
        entry = data.get(monster_id)
        if entry is None or entry.get("name") != monster_name:
            print(f"  warning: {name}: no '{monster_name}' with ID "
                  f"{monster_id}, skipped")
            continue
        entry["pitch_segments"] = segments

    output = json.dumps(data, indent=4, ensure_ascii=False)
    # Keep each segment list on one line.
    output = re.sub(
        r'"pitch_segments": (\[\s*\[.*?\]\s*\])',
        lambda m: '"pitch_segments": ' + json.dumps(json.loads(m.group(1))),
        output,
        flags=re.S,
    )
    return output + ("\n" if text.endswith("\n") else "")


def main():
    parser = argparse.ArgumentParser(
        description="Add per-monster pitch ranges to MSMCMT-Android.")
    parser.add_argument("repo", nargs="?", default=".",
                        help="project folder (default: current folder)")
    parser.add_argument("--dry-run", action="store_true",
                        help="show what would change without writing")
    args = parser.parse_args()
    repo = Path(args.repo)

    changes = []
    try:
        # Prepare every file first so a failure leaves the project untouched.
        for name, marker, edits in SOURCES:
            text, newline = read(repo / name)
            new = patch_source(name, text, marker, edits)
            changes.append((name, text, new, newline))
        for name in JSON_FILES:
            text, newline = read(repo / name)
            changes.append((name, text, patch_json(name, text), newline))
    except PatchError as error:
        print(f"Error: {error}\nNothing was changed.", file=sys.stderr)
        return 1

    for name, old, new, newline in changes:
        if old == new:
            print(f"up to date    {name}")
            continue
        if args.dry_run:
            print(f"would update  {name}")
            continue
        (repo / name).write_bytes(new.replace("\n", newline).encode("utf-8"))
        print(f"updated       {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
