
"""
Unified My Singing Monsters converter.

This file provides one entry point for both conversion directions.
The existing conversion engines are temporarily kept as backends
while their logic is gradually moved into this file.

Do not delete mid2msm.py or msm2mid.py yet.
"""

import importlib
import os
import threading


# --------------------------------------------------
# FILE SETTINGS
# --------------------------------------------------

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MONSTER_DATA_PATH = os.path.join(BASE_DIR, "mdata.json")

# The old converters use shared variables internally.
# This lock prevents two conversions from interfering
# with each other.
_CONVERSION_LOCK = threading.RLock()

_midi_to_msm_engine = None
_msm_to_midi_engine = None


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
                "Could not load the MIDI-to-MSM converter: {}".format(exc)
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
    Load the monster database.

    Returns:
        dict: Monster information from mdata.json.
    """

    if not os.path.isfile(MONSTER_DATA_PATH):
        raise FileNotFoundError(
            "Monster database not found: {}".format(MONSTER_DATA_PATH)
        )

    import json

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


# --------------------------------------------------
# MIDI TO MSM
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
    Convert a MIDI file into an MSM composition.

    Args:
        input_path: Path to the MIDI file.
        output_path: Path for the generated MSM file.
        tempo: Optional tempo override.
        division: Optional note timing division.
        min_note: Optional minimum note setting (8, 16, or 32).
        write_xml: Whether to also write an XML file.

    Returns:
        str: Path to the generated MSM file.
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
# MSM TO MIDI
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

    Args:
        input_path: Path to the MSM file.
        output_path: Path for the generated MIDI file.
        min_note: Minimum note setting (8, 16, or 32).
        division: Optional MIDI timing division.
        skip_time_signature: Use the default time signature.
        tempo: Optional tempo override.

    Returns:
        str: Path to the generated MIDI file.
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
            "MSM-to-MIDI conversion did not create an output file."
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
    """
    Return basic information about the unified converter.
    """

    return {
        "name": "MSM Unified Converter",
        "version": "1.0.0",
        "midi_to_msm": True,
        "msm_to_midi": True,
        "monster_database": os.path.isfile(MONSTER_DATA_PATH),
        "architecture": "unified entry point with temporary backends"
    }
