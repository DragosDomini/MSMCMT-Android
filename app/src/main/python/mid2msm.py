
import os
import sys
import xml.etree.ElementTree as ET
import mido
import json
import argparse
import lz4.block


# --------------------------------------------------
# FILE PATHS AND MONSTER DATA
# --------------------------------------------------

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

MONSTER_DATA_PATH = os.path.join(BASE_DIR, "mdata.json")

with open(MONSTER_DATA_PATH, "r", encoding="utf-8") as monster_data_file:
    monster_data = json.load(monster_data_file)

out_xml_msm_filename = None
midi_input_filename = None


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


# --------------------------------------------------
# MIDI TO MSM NOTE MAPPING
# --------------------------------------------------

midi_to_msm_note = {
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


# --------------------------------------------------
# GLOBAL CONVERSION DATA
# --------------------------------------------------

MonstersList = []
TracksList = []

SongSetting1 = SongSettings(
    4,
    4,
    120,
    "Song"
)


# --------------------------------------------------
# MSM XML STRUCTURE
# --------------------------------------------------

root = ET.Element(
    "SFSOBJECT",
    key="root"
)

monsters_and_tracks_settings_array = ET.SubElement(
    root,
    "SFSARRAY",
    key="islands"
)

monsters_and_tracks_settings_object = ET.SubElement(
    monsters_and_tracks_settings_array,
    "SFSOBJECT",
    key=""
)

ET.SubElement(
    monsters_and_tracks_settings_object,
    "INT",
    key="island",
    value="11"
)

monsters_settings_array = ET.SubElement(
    monsters_and_tracks_settings_object,
    "SFSARRAY",
    key="monsters"
)

song_title = ET.SubElement(
    monsters_and_tracks_settings_object,
    "STRING",
    key="name",
    value=""
)

song_settings = ET.SubElement(
    monsters_and_tracks_settings_object,
    "SFSOBJECT",
    key="song"
)

song_key_sig = ET.SubElement(
    song_settings,
    "INT",
    key="key_sig",
    value="0"
)

song_tempo = ET.SubElement(
    song_settings,
    "INT",
    key="tempo",
    value="120"
)

song_time_denom = ET.SubElement(
    song_settings,
    "INT",
    key="time_denom",
    value="4"
)

song_time_numerator = ET.SubElement(
    song_settings,
    "INT",
    key="time_numerator",
    value="4"
)

tracks_assign = ET.SubElement(
    song_settings,
    "SFSARRAY",
    key="tracks"
)

ET.SubElement(
    song_settings,
    "LONG",
    key="user",
    value="1 0"
)

ET.SubElement(
    monsters_and_tracks_settings_object,
    "LONG",
    key="user",
    value="1 0"
)

ET.SubElement(
    monsters_and_tracks_settings_object,
    "LONG",
    key="user_island_id",
    value="1 0"
)

tracks_list = ET.SubElement(
    root,
    "SFSARRAY",
    key="tracks"
)

ET.SubElement(
    root,
    "INT",
    key="versionCode",
    value="3"
)


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
# MIDI TEMPO AND TIME SIGNATURE
# --------------------------------------------------

def readMidiSettings(midi, g_tempo=None):

    """
    Read tempo and time signature from the MIDI file.

    MIDI tempo is stored as microseconds per beat.
    MSM expects BPM.

    MSM supports one song tempo, so the earliest
    tempo event is used if the MIDI contains changes.
    """

    tempo_events = []
    signature_events = []
    track_names = []

    for track_index, track in enumerate(midi.tracks):

        absolute_time = 0

        for msg in track:

            absolute_time += msg.time

            if msg.type == "set_tempo":
                tempo_events.append(
                    (absolute_time, msg.tempo)
                )

            elif msg.type == "time_signature":
                signature_events.append(
                    (
                        absolute_time,
                        msg.numerator,
                        msg.denominator
                    )
                )

            elif msg.type == "track_name":
                if msg.name and not track_names:
                    track_names.append(msg.name)

    # Use the earliest tempo event from any track.
    if g_tempo is not None:

        if g_tempo <= 0:
            raise ValueError("Tempo must be greater than zero.")

        SongSetting1.tempobpm = int(round(g_tempo))

    elif tempo_events:

        tempo_events.sort(key=lambda item: item[0])

        first_tempo = tempo_events[0][1]

        bpm = mido.tempo2bpm(first_tempo)

        SongSetting1.tempobpm = int(round(bpm))

    else:

        # Standard MIDI default tempo is 120 BPM.
        SongSetting1.tempobpm = 120

    # Use the earliest time signature.
    if signature_events:

        signature_events.sort(key=lambda item: item[0])

        _, numerator, denominator = signature_events[0]

        SongSetting1.time_numerator = numerator
        SongSetting1.time_denominator = denominator

    if track_names:
        SongSetting1.song_name = track_names[0]

    print(
        "MIDI tempo: {} BPM".format(
            SongSetting1.tempobpm
        )
    )

    print(
        "MIDI time signature: {}/{}".format(
            SongSetting1.time_numerator,
            SongSetting1.time_denominator
        )
    )


# --------------------------------------------------
# MIDI FILE PARSING
# --------------------------------------------------

def testMidiFile(
    g_tempo=None,
    note_division=None,
    min_note=None
):

    global midi_input_filename

    try:

        midi = mido.MidiFile(
            midi_input_filename
        )

    except Exception as exc:

        raise ValueError(
            "Invalid MIDI file: " + str(exc)
        )

    print("PPQ: " + str(midi.ticks_per_beat))

    # --------------------------------------------------
    # READ GLOBAL MIDI SETTINGS
    # --------------------------------------------------

    readMidiSettings(
        midi,
        g_tempo
    )

    # --------------------------------------------------
    # CALCULATE NOTE DIVISION
    # --------------------------------------------------

    if note_division is not None:

        if note_division <= 0:
            raise ValueError(
                "MIDI note division must be greater than zero."
            )

        actual_note_division = note_division

    elif min_note == 8:

        actual_note_division = midi.ticks_per_beat / 2

    elif min_note == 16:

        actual_note_division = midi.ticks_per_beat / 4

    elif min_note == 32:

        actual_note_division = midi.ticks_per_beat / 8

    else:

        actual_note_division = midi.ticks_per_beat / 2

    actual_note_division = int(actual_note_division)

    if actual_note_division <= 0:
        raise ValueError(
            "Calculated note division is invalid."
        )

    print(
        "MIDI note division: {}".format(
            actual_note_division
        )
    )

    # --------------------------------------------------
    # READ INDIVIDUAL INSTRUMENT TRACKS
    # --------------------------------------------------

    for i, track in enumerate(midi.tracks):

        # Track 0 is normally the MIDI metadata track.
        if i == 0:
            continue

        tmpMonster = MonsterSetting(
            200,
            1,
            i,
            i + 1,
            None,
            None,
            None,
            0
        )

        absolute_time = 0

        allnotesdata = []

        midi_program = 0
        midi_bank_select = 0

        # --------------------------------------------------
        # READ TRACK EVENTS
        # --------------------------------------------------

        for msg in track:

            # Every MIDI event contributes to the absolute
            # timeline, including events we do not otherwise use.
            absolute_time += msg.time

            if msg.type == "control_change":

                if msg.control == 7:

                    tmpMonster.volume = float(
                        "{:.6f}".format(
                            translateVolume(
                                msg.value,
                                0,
                                127,
                                0.0,
                                1.0
                            )
                        )
                    )

                elif msg.control == 102:

                    tmpMonster.pos_x = msg.value

                elif msg.control == 103:

                    tmpMonster.pos_y = msg.value

                elif msg.control == 104:

                    tmpMonster.flip = (
                        0 if msg.value == 0 else 1
                    )

                elif msg.control == 105:

                    tmpMonster.muted = (
                        0 if msg.value == 0 else 1
                    )

                elif msg.control == 0:

                    midi_bank_select = msg.value

            elif msg.type == "program_change":

                midi_program = msg.program

            # --------------------------------------------------
            # NOTE ON
            # --------------------------------------------------

            elif msg.type == "note_on" and msg.velocity > 0:

                notedata = {
                    "note": msg.note,
                    "channel": msg.channel,
                    "delta_start": absolute_time,
                    "delta_stop": None
                }

                allnotesdata.append(notedata)

            # --------------------------------------------------
            # NOTE OFF
            # --------------------------------------------------

            elif (
                msg.type == "note_off"
                or (
                    msg.type == "note_on"
                    and msg.velocity == 0
                )
            ):

                for key in allnotesdata:

                    if (
                        key["note"] == msg.note
                        and key["channel"] == msg.channel
                        and key["delta_stop"] is None
                    ):

                        key["delta_stop"] = absolute_time
                        break

        # --------------------------------------------------
        # CONVERT NOTES
        # --------------------------------------------------

        note_num = []
        note_pos = []
        note_len = []

        for key in allnotesdata:

            if key["delta_stop"] is None:
                print(
                    "Ignoring unfinished note on track {}".format(i)
                )
                continue

            note = key["note"]

            if 36 <= note <= 57:

                note += 24

            elif 85 <= note <= 96:

                note -= 12

            elif 58 <= note <= 84:

                pass

            else:

                print(
                    "Key out of range Track {} key {}".format(
                        i,
                        note
                    )
                )

                continue

            duration = (
                key["delta_stop"] - key["delta_start"]
            )

            if duration <= 0:
                continue

            note_position = int(
                key["delta_start"] / actual_note_division
            )

            note_length = int(
                duration / actual_note_division
            )

            if note_length <= 0:

                print(
                    "Detected note shorter than the selected "
                    "MSM note division on track {}. Skipping.".format(i)
                )

                continue

            if note not in midi_to_msm_note:
                continue

            note_num.append(
                midi_to_msm_note[note]
            )

            note_pos.append(
                note_position
            )

            note_len.append(
                note_length
            )

        # --------------------------------------------------
        # MATCH MIDI INSTRUMENT TO MSM MONSTER
        # --------------------------------------------------

        matched_monster = None

        for key in monster_data:

            if (
                monster_data[key]["midi_program"] == midi_program
                and monster_data[key]["midi_bank_select_msb"] == midi_bank_select
            ):

                matched_monster = int(key)
                break

        if matched_monster is None:

            raise ValueError(
                "No MSM monster matches MIDI program {} "
                "and bank select {} on track {}.".format(
                    midi_program,
                    midi_bank_select,
                    i
                )
            )

        tmpMonster.monster_id = matched_monster

        tmpTrackData = TrackData(
            note_len,
            note_num,
            note_pos,
            i,
            i + 1,
            ""
        )

        print(
            "Track {} instrument: {}, id: {}, notes: {}".format(
                i,
                monster_data[str(tmpMonster.monster_id)]["name"],
                tmpMonster.monster_id,
                len(note_num)
            )
        )

        TracksList.append(tmpTrackData)
        MonstersList.append(tmpMonster)

    if not TracksList:
        raise ValueError(
            "No instrument tracks containing notes were found in the MIDI file."
        )


# --------------------------------------------------
# WRITE MSM XML
# --------------------------------------------------

def WriteToXmlFile():

    # Write the actual MIDI tempo into the MSM song.
    song_tempo.set(
        "value",
        str(int(SongSetting1.tempobpm))
    )

    song_time_numerator.set(
        "value",
        str(SongSetting1.time_numerator)
    )

    song_time_denom.set(
        "value",
        str(SongSetting1.time_denominator)
    )

    song_title.set(
        "value",
        str(SongSetting1.song_name)
    )

    # --------------------------------------------------
    # WRITE MONSTERS
    # --------------------------------------------------

    for monster in MonstersList:

        tmp_x = ET.SubElement(
            monsters_settings_array,
            "SFSOBJECT",
            key=""
        )

        if monster.flip is None:

            flip = monster_data[
                str(monster.monster_id)
            ]["flip"]

        else:

            flip = monster.flip

        ET.SubElement(
            tmp_x,
            "INT",
            key="flip",
            value=str(flip)
        )

        ET.SubElement(
            tmp_x,
            "INT",
            key="monster",
            value=str(monster.monster_id)
        )

        if monster.pos_x is None:

            pos_x = monster_data[
                str(monster.monster_id)
            ]["pos_x"]

        else:

            pos_x = monster.pos_x

        ET.SubElement(
            tmp_x,
            "INT",
            key="pos_x",
            value=str(pos_x)
        )

        if monster.pos_y is None:

            pos_y = monster_data[
                str(monster.monster_id)
            ]["pos_y"]

        else:

            pos_y = monster.pos_y

        ET.SubElement(
            tmp_x,
            "INT",
            key="pos_y",
            value=str(pos_y)
        )

        ET.SubElement(
            tmp_x,
            "INT",
            key="muted",
            value=str(monster.muted)
        )

        ET.SubElement(
            tmp_x,
            "LONG",
            key="user_monster_id",
            value=str(monster.user_monster_id) + " 0"
        )

        ET.SubElement(
            tmp_x,
            "DOUBLE",
            key="volume",
            value=str(monster.volume)
        )

        tmp_y = ET.SubElement(
            tracks_assign,
            "SFSOBJECT",
            key=""
        )

        ET.SubElement(
            tmp_y,
            "LONG",
            key="monster",
            value=str(monster.user_monster_id) + " 0"
        )

        ET.SubElement(
            tmp_y,
            "LONG",
            key="track",
            value=str(monster.dst_user_track_id) + " 0"
        )

    # --------------------------------------------------
    # WRITE TRACKS
    # --------------------------------------------------

    for tracks in TracksList:

        tmp_x = ET.SubElement(
            tracks_list,
            "SFSOBJECT",
            key=""
        )

        ET.SubElement(
            tmp_x,
            "INT",
            key="format",
            value="2 0"
        )

        # Note lengths
        tmp_array_lengths = ET.SubElement(
            tmp_x,
            "INTARRAY",
            key="lengths"
        )

        tmp_array_lengths.text = "".join(
            str(length) + ","
            for length in tracks.lengths
        )

        ET.SubElement(
            tmp_x,
            "STRING",
            key="name",
            value=str(tracks.name)
        )

        # Notes
        tmp_notes_arr = ET.SubElement(
            tmp_x,
            "INTARRAY",
            key="notes"
        )

        tmp_notes_arr.text = "".join(
            str(note) + ","
            for note in tracks.notes
        )

        # Note positions
        tmp_times_arr = ET.SubElement(
            tmp_x,
            "INTARRAY",
            key="times"
        )

        tmp_times_arr.text = "".join(
            str(time) + ","
            for time in tracks.times
        )

        ET.SubElement(
            tmp_x,
            "LONG",
            key="user",
            value="1 0"
        )

        ET.SubElement(
            tmp_x,
            "LONG",
            key="user_track_id",
            value=str(tracks.dst_user_track_id) + " 0"
        )


# --------------------------------------------------
# COMPRESS MSM FILE
# --------------------------------------------------

def compressFile(
    data,
    write_xml=False
):

    if out_xml_msm_filename is None:
        raise ValueError("No output MSM filename was specified.")

    if write_xml:

        print("Writing XML file...")

        xml_filename = (
            os.path.splitext(out_xml_msm_filename)[0] + ".xml"
        )

        with open(xml_filename, "wb") as f:
            f.write(data)

    print("Compressing MSM file...")

    compressed_data = lz4.block.compress(
        data + b"\0",
        compression=9,
        mode="high_compression",
        store_size=False
    )

    with open(out_xml_msm_filename, "wb") as f:
        f.write(compressed_data)

    print(
        "MSM export complete: {}".format(
            out_xml_msm_filename
        )
    )


# --------------------------------------------------
# CONVERSION ENTRY POINT
# --------------------------------------------------

def convert_midi_to_msm(
    input_path,
    output_path,
    tempo=None,
    division=None,
    min_note=None,
    write_xml=False
):

    global midi_input_filename
    global out_xml_msm_filename
    global MonstersList
    global TracksList
    global SongSetting1
    global root
    global monsters_and_tracks_settings_array
    global monsters_and_tracks_settings_object
    global monsters_settings_array
    global song_title
    global song_settings
    global song_key_sig
    global song_tempo
    global song_time_denom
    global song_time_numerator
    global tracks_assign
    global tracks_list

    if not os.path.isfile(input_path):
        raise FileNotFoundError(
            "MIDI file not found: " + input_path
        )

    if tempo is not None and tempo <= 0:
        raise ValueError("Tempo must be greater than zero.")

    if division is not None and division <= 0:
        raise ValueError("Division must be greater than zero.")

    # Reset conversion data.
    MonstersList = []
    TracksList = []

    SongSetting1 = SongSettings(
        4,
        4,
        120,
        "Song"
    )

    # Rebuild XML structure for each conversion.
    root = ET.Element(
        "SFSOBJECT",
        key="root"
    )

    monsters_and_tracks_settings_array = ET.SubElement(
        root,
        "SFSARRAY",
        key="islands"
    )

    monsters_and_tracks_settings_object = ET.SubElement(
        monsters_and_tracks_settings_array,
        "SFSOBJECT",
        key=""
    )

    ET.SubElement(
        monsters_and_tracks_settings_object,
        "INT",
        key="island",
        value="11"
    )

    monsters_settings_array = ET.SubElement(
        monsters_and_tracks_settings_object,
        "SFSARRAY",
        key="monsters"
    )

    song_title = ET.SubElement(
        monsters_and_tracks_settings_object,
        "STRING",
        key="name",
        value=""
    )

    song_settings = ET.SubElement(
        monsters_and_tracks_settings_object,
        "SFSOBJECT",
        key="song"
    )

    song_key_sig = ET.SubElement(
        song_settings,
        "INT",
        key="key_sig",
        value="0"
    )

    song_tempo = ET.SubElement(
        song_settings,
        "INT",
        key="tempo",
        value="120"
    )

    song_time_denom = ET.SubElement(
        song_settings,
        "INT",
        key="time_denom",
        value="4"
    )

    song_time_numerator = ET.SubElement(
        song_settings,
        "INT",
        key="time_numerator",
        value="4"
    )

    tracks_assign = ET.SubElement(
        song_settings,
        "SFSARRAY",
        key="tracks"
    )

    ET.SubElement(
        song_settings,
        "LONG",
        key="user",
        value="1 0"
    )

    ET.SubElement(
        monsters_and_tracks_settings_object,
        "LONG",
        key="user",
        value="1 0"
    )

    ET.SubElement(
        monsters_and_tracks_settings_object,
        "LONG",
        key="user_island_id",
        value="1 0"
    )

    tracks_list = ET.SubElement(
        root,
        "SFSARRAY",
        key="tracks"
    )

    ET.SubElement(
        root,
        "INT",
        key="versionCode",
        value="3"
    )

    midi_input_filename = input_path
    out_xml_msm_filename = output_path

    print("Parsing MIDI file...")

    testMidiFile(
        tempo,
        division,
        min_note
    )

    print(
        "Using MSM tempo: {} BPM".format(
            SongSetting1.tempobpm
        )
    )

    print("Writing XML/MSM file...")

    WriteToXmlFile()

    ET.indent(
        root,
        space="\t",
        level=0
    )

    b_xml = ET.tostring(
        root,
        short_empty_elements=True
    )

    xml_header = bytearray()

    xml_header.extend(
        map(
            ord,
            '<?xml version="1.0"?>\n'
        )
    )

    compressFile(
        bytes(xml_header) + b_xml,
        write_xml
    )

    if not os.path.isfile(output_path):
        raise RuntimeError(
            "MSM conversion did not create an output file."
        )

    if os.path.getsize(output_path) == 0:
        raise RuntimeError(
            "The generated MSM file is empty."
        )

    return output_path


# --------------------------------------------------
# COMMAND LINE INTERFACE
# --------------------------------------------------

def main():

    parser = argparse.ArgumentParser(
        prog="mid2msm",
        description="Convert MIDI file into MSM file"
    )

    parser.add_argument(
        "--out",
        metavar="OUTFILE",
        type=str,
        help="Specify output file"
    )

    parser.add_argument(
        "--division",
        type=int,
        help="MIDI division time"
    )

    parser.add_argument(
        "--tempo",
        type=int,
        help="Overwrite MIDI tempo in the MSM file"
    )

    parser.add_argument(
        "--min-note",
        type=int,
        choices=[8, 16, 32],
        help="Minimum note division (8, 16, or 32)"
    )

    parser.add_argument(
        "--write-xml",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Write an additional XML file"
    )

    parser.add_argument(
        "MIDIFILE",
        type=str
    )

    args = parser.parse_args()

    if args.out is None:

        output_path = (
            os.path.splitext(args.MIDIFILE)[0] + ".msm"
        )

    else:

        output_path = args.out

    convert_midi_to_msm(
        args.MIDIFILE,
        output_path,
        args.tempo,
        args.division,
        args.min_note,
        args.write_xml
    )


if __name__ == "__main__":
    main()
