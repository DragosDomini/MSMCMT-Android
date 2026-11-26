
import os
import xml.etree.ElementTree as ET
import sys
import miditoolkit.midi.containers
import miditoolkit.midi.parser
import miditoolkit.midi.utils
import miditoolkit.pianoroll
import mido
import json
import math
import argparse
import lz4.block
import miditoolkit


# --------------------------------------------------
# GLOBAL DATA
# --------------------------------------------------

d_data = b""

MONSTER_DATA_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "mdata.json"
)

with open(MONSTER_DATA_PATH, "r", encoding="utf-8") as monsters_data_file:
    monsters_data = json.load(monsters_data_file)


# --------------------------------------------------
# MSM NOTE TO MIDI NOTE MAPPING
# --------------------------------------------------

msm_to_midi_note = {
    31: 58,
    28: 59,
    32: 60,
    33: 61,
    36: 62,
    37: 63,
    40: 64,
    42: 68,
    44: 65,
    45: 66,
    48: 67,
    49: 68,
    52: 69,
    53: 70,
    56: 71,
    60: 72,
    61: 73,
    64: 74,
    65: 75,
    68: 76,
    72: 77,
    73: 78,
    76: 79,
    77: 80,
    80: 81,
    81: 82,
    84: 83,
    85: 84,
}


# --------------------------------------------------
# DATA CLASSES
# --------------------------------------------------

class SongSettings:

    def __init__(
        self,
        time_numerator,
        time_denominator,
        tempobpm,
        song_name
    ):
        self.time_numerator = time_numerator
        self.time_denominator = time_denominator
        self.tempobpm = tempobpm
        self.song_name = song_name


class MonsterSetting:

    def __init__(
        self,
        monster_id,
        volume,
        user_monster_id,
        dst_user_track_id,
        pos_x,
        pos_y,
        flip,
        muted
    ):
        self.monster_id = monster_id
        self.volume = volume
        self.user_monster_id = user_monster_id
        self.dst_user_track_id = dst_user_track_id
        self.pos_x = pos_x
        self.pos_y = pos_y
        self.flip = flip
        self.muted = muted


class TrackData:

    def __init__(
        self,
        lengths,
        notes,
        times,
        src_user_monster_id,
        dst_user_track_id,
        name
    ):
        self.lengths = lengths
        self.notes = notes
        self.times = times
        self.src_user_monster_id = src_user_monster_id
        self.dst_user_track_id = dst_user_track_id
        self.name = name


SongSettings1 = SongSettings(4, 4, 120, "Song")
MonstersList = []
TracksList = []


# --------------------------------------------------
# VOLUME CONVERSION
# --------------------------------------------------

def translateVolume(
    value,
    leftMin,
    leftMax,
    rightMin,
    rightMax
):
    leftSpan = leftMax - leftMin
    rightSpan = rightMax - rightMin

    valueScaled = float(value - leftMin) / float(leftSpan)

    return rightMin + (valueScaled * rightSpan)


# --------------------------------------------------
# MIDI CONVERSION
# --------------------------------------------------

def writeMidiFile(
    filename,
    min_note,
    ntime_division
):

    target_filename = filename[:len(filename) - 4] + ".mid"

    print("Creating MIDI File: " + target_filename)

    midi = mido.MidiFile(ticks_per_beat=960)

    meta_track = mido.MidiTrack()
    midi.tracks.append(meta_track)

    meta_track.append(
        mido.MetaMessage(
            'track_name',
            name=SongSettings1.song_name,
            time=0
        )
    )

    meta_track.append(
        mido.MetaMessage(
            'time_signature',
            numerator=SongSettings1.time_numerator,
            denominator=SongSettings1.time_denominator,
            time=0
        )
    )

    meta_track.append(
        mido.MetaMessage(
            'set_tempo',
            tempo=mido.bpm2tempo(SongSettings1.tempobpm),
            time=0
        )
    )

    if ntime_division is None:

        if min_note == 8:
            note_division = int(midi.ticks_per_beat / 2)

        elif min_note == 16:
            note_division = int(midi.ticks_per_beat / 4)

        elif min_note == 32:
            note_division = int(midi.ticks_per_beat / 8)

        else:
            raise ValueError("Minimum note must be 8, 16, or 32.")

    else:
        note_division = ntime_division

    print("MIDI: note division " + str(note_division))

    for i, mlist in enumerate(MonstersList):

        print(
            "Track {}: {}".format(
                i,
                monsters_data[mlist.monster_id]['name']
            )
        )

        tmp_track = mido.MidiTrack()
        midi.tracks.append(tmp_track)

        tmp_track.append(
            mido.Message(
                'program_change',
                program=monsters_data[mlist.monster_id]['midi_program']
            )
        )

        tmp_track.append(
            mido.Message(
                'control_change',
                control=0,
                value=monsters_data[mlist.monster_id]['midi_bank_select_msb']
            )
        )

        tmp_track.append(
            mido.MetaMessage(
                'track_name',
                name=monsters_data[mlist.monster_id]['name']
            )
        )

        tmp_track.append(
            mido.Message(
                'control_change',
                control=102,
                value=mlist.pos_x
            )
        )

        tmp_track.append(
            mido.Message(
                'control_change',
                control=103,
                value=mlist.pos_y
            )
        )

        tmp_track.append(
            mido.Message(
                'control_change',
                control=104,
                value=mlist.flip
            )
        )

        tmp_track.append(
            mido.Message(
                'control_change',
                control=105,
                value=mlist.muted
            )
        )

        tmp_track.append(
            mido.Message(
                'control_change',
                control=7,
                value=int(
                    translateVolume(
                        mlist.volume,
                        float(0),
                        float(1),
                        0,
                        127
                    )
                )
            )
        )

        for ttdata in TracksList:

            if mlist.dst_user_track_id == ttdata.dst_user_track_id:

                for xnote, xlen, xpos in zip(
                    ttdata.notes,
                    ttdata.lengths,
                    ttdata.times
                ):
                    delta_note_start = xpos * note_division
                    delta_note_stop = (
                        xpos * note_division
                    ) + (
                        xlen * note_division
                    )

                break

    midi.save(target_filename)


def writeMidiFile2(
    filename,
    min_note,
    ntime_division,
    outfile
):

    if outfile is None:
        target_filename = filename[:len(filename) - 4] + ".mid"
    else:
        target_filename = outfile

    print("Creating MIDI File: " + target_filename)

    midi = miditoolkit.midi.parser.MidiFile()

    if ntime_division is None:

        if min_note == 8:
            note_division = int(midi.ticks_per_beat / 2)

        elif min_note == 16:
            note_division = int(midi.ticks_per_beat / 4)

        elif min_note == 32:
            note_division = int(midi.ticks_per_beat / 8)

        else:
            raise ValueError("Minimum note must be 8, 16, or 32.")

    else:
        note_division = ntime_division

    print("MIDI: note division " + str(note_division))

    # Each monster becomes its own MIDI instrument track.
    for i, mlist in enumerate(MonstersList):

        monster = monsters_data[mlist.monster_id]

        print(
            "Track {}: {}".format(
                i,
                monster['name']
            )
        )

        track = miditoolkit.midi.containers.Instrument(
            program=monster['midi_program'],
            is_drum=False,
            name=monster['name']
        )

        track.control_changes.append(
            miditoolkit.midi.containers.ControlChange(
                number=102,
                value=mlist.pos_x,
                time=0
            )
        )

        track.control_changes.append(
            miditoolkit.midi.containers.ControlChange(
                number=103,
                value=mlist.pos_y,
                time=0
            )
        )

        track.control_changes.append(
            miditoolkit.midi.containers.ControlChange(
                number=104,
                value=mlist.flip,
                time=0
            )
        )

        track.control_changes.append(
            miditoolkit.midi.containers.ControlChange(
                number=105,
                value=mlist.muted,
                time=0
            )
        )

        track.control_changes.append(
            miditoolkit.midi.containers.ControlChange(
                number=7,
                value=int(
                    translateVolume(
                        mlist.volume,
                        float(0),
                        float(1),
                        0,
                        127
                    )
                ),
                time=0
            )
        )

        track.control_changes.append(
            miditoolkit.midi.containers.ControlChange(
                number=0,
                value=monster['midi_bank_select_msb'],
                time=0
            )
        )

        midi.instruments.append(track)

        for ttdata in TracksList:

            if mlist.dst_user_track_id == ttdata.dst_user_track_id:

                for xnote, xlen, xpos in zip(
                    ttdata.notes,
                    ttdata.lengths,
                    ttdata.times
                ):

                    delta_note_start = xpos * note_division

                    delta_note_stop = (
                        xpos * note_division
                    ) + (
                        xlen * note_division
                    )

                    try:

                        note = miditoolkit.midi.containers.Note(
                            velocity=100,
                            pitch=msm_to_midi_note[xnote],
                            start=delta_note_start,
                            end=delta_note_stop
                        )

                        track.notes.append(note)

                    except KeyError as e:

                        print(
                            "Detected key out of range {} at Track {}: {}. "
                            "Skipping Note".format(
                                e,
                                i,
                                monster['name']
                            )
                        )

                break

    midi.dump(target_filename)


# --------------------------------------------------
# MSM XML PARSING
# --------------------------------------------------

def testXmlFile(
    root,
    skip_time_signature,
    tempo
):

    print("Reading XML Data")

    if skip_time_signature is False:

        SongSettings1.time_numerator = int(
            root.find(".//INT[@key='time_numerator']").get("value")
        )

        SongSettings1.time_denominator = int(
            root.find(".//INT[@key='time_denom']").get("value")
        )

    if tempo is None:

        SongSettings1.tempobpm = int(
            root.find(".//INT[@key='tempo']").get("value")
        )

    else:
        SongSettings1.tempobpm = tempo

    SongSettings1.song_name = root.find(
        ".//STRING[@key='name']"
    ).get("value")

    monstersarray = root.find(
        ".//SFSARRAY[@key='monsters']"
    )

    monsterstrackassign = root.find(
        ".//SFSOBJECT[@key='song'].//SFSARRAY[@key='tracks']"
    )

    if monstersarray is None:
        raise ValueError("The MSM file does not contain a monster array.")

    if monsterstrackassign is None:
        raise ValueError("The MSM file does not contain track assignments.")

    for child in monstersarray:

        tmpMonster = MonsterSetting(
            200,
            1.0,
            0,
            0,
            0,
            0,
            0,
            0
        )

        tmpMonster.flip = int(
            child.find(".//INT[@key='flip']").get("value")
        )

        tmpMonster.monster_id = child.find(
            ".//INT[@key='monster']"
        ).get("value")

        tmpMonster.muted = int(
            child.find(".//INT[@key='muted']").get("value")
        )

        tmpMonster.pos_x = int(
            child.find(".//INT[@key='pos_x']").get("value")
        )

        tmpMonster.pos_y = int(
            child.find(".//INT[@key='pos_y']").get("value")
        )

        tmpMonster.user_monster_id = int(
            child.find(".//LONG[@key='user_monster_id']")
            .get("value")[0:2]
        )

        tmpMonster.volume = float(
            child.find(".//DOUBLE[@key='volume']").get("value")
        )

        for child2 in monsterstrackassign.findall(".//SFSOBJECT"):

            if tmpMonster.user_monster_id == int(
                child2.find(".//LONG[@key='monster']")
                .get("value")[0:2]
            ):

                tmpMonster.dst_user_track_id = int(
                    child2.find(".//LONG[@key='track']")
                    .get("value")[0:2]
                )

                break

        MonstersList.append(tmpMonster)

    trackdata = root[1]

    for child in trackdata:

        tmpLen = []
        tmpNote = []
        tmpPos = []

        len_element = child.find(
            ".//INTARRAY[@key='lengths']"
        )

        note_element = child.find(
            ".//INTARRAY[@key='notes']"
        )

        pos_element = child.find(
            ".//INTARRAY[@key='times']"
        )

        len_str = len_element.text if len_element is not None else None
        note_str = note_element.text if note_element is not None else None
        pos_str = pos_element.text if pos_element is not None else None

        if len_str:
            tmpLen = list(
                map(int, len_str.rstrip(",").split(","))
            )

        if note_str:
            tmpNote = list(
                map(int, note_str.rstrip(",").split(","))
            )

        if pos_str:
            tmpPos = list(
                map(int, pos_str.rstrip(",").split(","))
            )

        if not (
            len(tmpLen) == len(tmpNote) == len(tmpPos)
        ):
            raise ValueError(
                "MSM track contains mismatched note, length, and timing arrays."
            )

        dst_user_track_id = int(
            child.find(".//LONG[@key='user_track_id']")
            .get("value")[0:2]
        )

        tmpTrack = TrackData(
            tmpLen,
            tmpNote,
            tmpPos,
            0,
            dst_user_track_id,
            ""
        )

        TracksList.append(tmpTrack)


# --------------------------------------------------
# MSM DECOMPRESSION
# --------------------------------------------------

def decompress_data(
    filename,
    write_xml=None
):

    global d_data

    print("Decompressing file " + filename + "...")

    with open(filename, 'rb') as f:
        rawdata = f.read()

    if not rawdata:
        raise ValueError("The selected MSM file is empty.")

    # The original format stores a trailing null byte.
    # Try increasing uncompressed sizes, but do not loop forever.
    max_extra_size = max(1024 * 1024, len(rawdata) * 20)

    decompressed_data = None

    for extra_size in range(0, max_extra_size + 1):

        try:
            decompressed_data = lz4.block.decompress(
                rawdata,
                uncompressed_size=len(rawdata) + extra_size
            )

            if decompressed_data is not None:
                break

        except Exception:
            continue

    if decompressed_data is None:
        raise ValueError(
            "Unable to decompress this MSM file. "
            "It may be unsupported or corrupted."
        )

    if decompressed_data.endswith(b"\x00"):
        d_data = decompressed_data[:-1]
    else:
        d_data = decompressed_data

    if write_xml is True:

        print("Writing XML File")

        dest_file = os.path.splitext(filename)[0] + ".xml"

        with open(dest_file, 'wb') as f:
            f.write(d_data)


# --------------------------------------------------
# ANDROID-CALLABLE CONVERSION FUNCTION
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
    Convert an MSM composition to MIDI.

    This function is designed to be called from Android
    using Chaquopy.
    """

    global MonstersList
    global TracksList
    global SongSettings1
    global d_data

    if min_note not in (8, 16, 32):
        raise ValueError(
            "Minimum note must be 8, 16, or 32."
        )

    if not os.path.isfile(input_path):
        raise FileNotFoundError(
            "MSM file not found: " + input_path
        )

    if tempo is not None and tempo <= 0:
        raise ValueError("Tempo must be greater than zero.")

    if division is not None and division <= 0:
        raise ValueError("Division must be greater than zero.")

    # Reset state before each conversion.
    MonstersList = []
    TracksList = []
    SongSettings1 = SongSettings(
        4,
        4,
        120,
        "Song"
    )
    d_data = b""

    # Decompress the MSM file.
    decompress_data(input_path, False)

    if not d_data:
        raise ValueError(
            "Unable to decompress the MSM composition."
        )

    # Parse the XML.
    try:
        root = ET.fromstring(d_data.decode("utf-8"))

    except Exception as exc:
        raise ValueError(
            "Invalid MSM composition XML: " + str(exc)
        )

    # Extract monsters, tracks, tempo, and song settings.
    testXmlFile(
        root,
        skip_time_signature,
        tempo
    )

    if not MonstersList:
        raise ValueError(
            "No monsters were found in this MSM composition."
        )

    if not TracksList:
        raise ValueError(
            "No tracks were found in this MSM composition."
        )

    # Generate the MIDI file.
    writeMidiFile2(
        input_path,
        min_note,
        division,
        output_path
    )

    if not os.path.isfile(output_path):
        raise RuntimeError(
            "MIDI conversion did not create an output file."
        )

    if os.path.getsize(output_path) == 0:
        raise RuntimeError(
            "The generated MIDI file is empty."
        )

    return output_path


# --------------------------------------------------
# COMMAND-LINE SUPPORT
# --------------------------------------------------

def main():

    parser = argparse.ArgumentParser(
        prog="msm2mid",
        description="Convert MSM file into MIDI file"
    )

    parser.add_argument(
        '--write-xml',
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Write XML File"
    )

    parser.add_argument(
        '--skip-time-signature',
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Skip tempo signature (default 4/4)"
    )

    parser.add_argument(
        '--tempo',
        metavar=120,
        type=int,
        help='Overwrite tempo in target MIDI file'
    )

    parser.add_argument(
        '--division',
        type=int,
        help="MIDI Note division"
    )

    parser.add_argument(
        '--min-note',
        type=int,
        default=8,
        help="1/X (8/16/32)"
    )

    parser.add_argument(
        '--outfile',
        type=str
    )

    parser.add_argument(
        'MSMFILE',
        type=str
    )

    args = parser.parse_args()

    output_path = args.outfile

    if output_path is None:
        output_path = (
            os.path.splitext(args.MSMFILE)[0] + ".mid"
        )

    if args.write_xml:
        decompress_data(
            args.MSMFILE,
            True
        )

    convert_msm_to_midi(
        args.MSMFILE,
        output_path,
        args.min_note,
        args.division,
        args.skip_time_signature,
        args.tempo
    )


if __name__ == "__main__":
    main()
