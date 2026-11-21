MSM MIDI Tools for Android

An Android application for converting Standard MIDI Files into My Singing Monsters Composer (.msm) compositions.

This project is an Android/Kotlin adaptation of the MIDI-to-MSM workflow from the original My Singing Monsters Composer MIDI Tools project. It brings the conversion process into an Android app, with a native file picker, note-length settings, instrument/monster mapping, and an export workflow.

«Important: Back up your existing My Singing Monsters Composer songs before importing or testing converted files. Compatibility can vary depending on the MIDI arrangement and game format.»

Features

- Select MIDI files directly from Android storage.
- Read MIDI Format 0 and Format 1 files.
- Parse individual MIDI tracks and instrument channels.
- Detect MIDI instrument program changes.
- Separate instrument parts instead of combining every note into one monster.
- Automatically match MIDI instruments to monsters using the bundled "mdata.json" database.
- Convert MIDI pitches and note timing into MSM song data.
- Choose minimum note lengths:
  - 1/8
  - 1/16
  - 1/32
- Generate and compress MSM data using LZ4.
- Save converted compositions directly to device storage.
- Custom Android interface inspired by My Singing Monsters Composer.

Instrument and Monster Assignment

The MIDI reader does not simply combine every note in a file into one part.

Instead, it processes MIDI channels and instrument program changes, creating separate parts for each channel/instrument/bank combination containing notes.

The converter then checks the bundled monster database ("mdata.json") to find the corresponding monster.

For example:

MIDI instrument| Intended monster
Electric guitar| Riff
Bass guitar| Rare Riff

These are examples of the intended instrument-to-monster matching. The actual assignment depends on the program and bank values available in the monster database.

If no matching monster exists, conversion reports an error rather than silently assigning an unrelated monster.

MIDI Compatibility

Current supported features and limitations:

- MIDI Format 0 and Format 1.
- PPQ-based MIDI timing.
- MIDI note pitches from 36–96, with pitch remapping into the Composer range.
- MIDI program changes and bank selection.
- MIDI channel separation.
- Note lengths of 1/8, 1/16 and 1/32.
- Tempo and time-signature metadata.

Current limitations

- SMPTE-based MIDI timing is not supported.
- Notes outside the supported pitch range cannot be converted.
- Notes shorter than the selected minimum note division may cause conversion errors.
- Instrument assignment requires a matching program/bank entry in "mdata.json".
- The generated song currently uses the C major/A minor key signature setting.
- The Android application currently focuses on MIDI-to-MSM conversion. The original Python project's MSM-to-MIDI functionality is not included in this Android application.

Building the Android Application

Requirements

- Android Studio
- Android SDK Platform 35
- JDK 17
- Gradle wrapper included in the repository

Build using Gradle

From the repository root:

./gradlew :app:assembleDebug

On Windows:

gradlew.bat :app:assembleDebug

The generated APK will be located at:

app/build/outputs/apk/debug/app-debug.apk

You can also open the repository in Android Studio and run the application on a compatible Android device or emulator.

Project Structure

app/
  src/main/
    java/com/dragosdomini/msmtools/
      MainActivity.kt
      MidiReader.kt
      MsmConverter.kt

    assets/
      mdata.json
      images/
      fonts/

Main Components

- MainActivity.kt — Android interface, MIDI file selection, conversion controls and file export.
- MidiReader.kt — MIDI parsing, channel processing, note extraction and instrument separation.
- MsmConverter.kt — Monster assignment, note conversion, MSM structure generation and LZ4 compression.
- mdata.json — Monster information and MIDI instrument mapping.

Credits and Original Project

This Android application builds upon the original MIDI-to-MSM conversion project:

Original project:
https://github.com/ar0x65FF/msmctools

Original author:
"ar0x65FF" (https://github.com/ar0x65FF)

Credit is retained for the original Python converter's format research, MIDI-to-MSM conversion approach, note mapping and song structure.

The Android application, Kotlin MIDI reader, Android interface and Android integration are maintained in this repository.

Please refer to the original repository for its licensing and usage terms. This README does not replace or change the original project's license.

Disclaimer

My Singing Monsters and My Singing Monsters Composer are properties of their respective owners.

This is an independent community project and is not affiliated with or endorsed by Big Blue Bubble or the game's publishers.

Always maintain backups of your compositions before importing converted files.
