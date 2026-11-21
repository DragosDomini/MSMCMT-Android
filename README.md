# MSM MIDI Tools for Android

An Android application for converting Standard MIDI Files into **My Singing Monsters Composer (.msm)** compositions.

This project is an Android/Kotlin adaptation of the original [MSM MIDI Tools](https://github.com/ar0x65FF/msmctools) Python project.

## Features

- Select MIDI files directly from Android storage.
- Read individual MIDI tracks and instrument channels.
- Detect MIDI instrument program changes.
- Separate instrument parts instead of combining all notes into one monster.
- Automatically assign monsters based on MIDI instruments using `mdata.json`.
- Convert MIDI pitches and note timing into MSM song data.
- Choose minimum note lengths:
  - 1/8 note
  - 1/16 note
  - 1/32 note
- Generate and compress MSM data using LZ4.
- Save converted compositions directly to device storage.
- Custom Android interface inspired by My Singing Monsters Composer.

## Instrument and Monster Assignment

The MIDI reader processes individual tracks, channels, and instrument program changes to keep instrument parts separate.

The converter uses the monster database to match MIDI instruments to their corresponding monsters.

Examples:

| MIDI Instrument | Monster |
|---|---|
| Electric Guitar | Riff |
| Bass Guitar | Rare Riff |

Instrument assignments depend on the available mappings in `mdata.json`.

## MIDI Compatibility

Current supported functionality:

- MIDI Format 0 and Format 1.
- PPQ-based MIDI timing.
- MIDI instrument program changes.
- MIDI channel separation.
- Note extraction and timing conversion.
- Supported note lengths of 1/8, 1/16 and 1/32.

### Current Limitations

- SMPTE-based MIDI timing is not supported.
- Notes outside the supported pitch range cannot be converted.
- Instrument assignments require matching entries in `mdata.json`.
- The generated song currently uses the C major/A minor key signature setting.
- This Android application currently focuses on MIDI-to-MSM conversion.

## Building the Application

### Requirements

- Android Studio
- Android SDK Platform 35
- JDK 17

### Build with Gradle

Linux/macOS:

```bash
./gradlew :app:assembleDebug
