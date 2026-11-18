# Opal:BlockSolver

A standalone Android helper for Block Blast. It reads the board and the three tray blocks from your screen, shows the best moves on top of the game, and can play them for you. It never modifies the game's APK.

> This is an unofficial project and is not affiliated with the game's developer. Auto mode may break the game's rules, so use it at your own risk.

## Features

- **Suggest:** Shows the next moves on top of the game and leaves the playing to you.
- **Auto:** Plays the moves for you by dragging the blocks.
- **Combo-first solver:** Prefers plans that keep clearing rows and columns in a row.
- **Color theme support:** Reads the board without relying on fixed colors.
- **Calibration:** Lets you set the board area by hand if it isn't found.
- **No internet permission:** Everything runs on your phone.

## Requirements

- Android 8.0 or newer
- To build from source: JDK 17 and Android SDK 34

## Installation

### Install the APK

1. Download the APK from the [latest release](https://github.com/rogzarii/opal-blocksolver/releases/latest).
2. Open it on your phone and allow installs from that source if Android asks.

### Build from source

Set the Android SDK path with `ANDROID_HOME`, or add an `sdk.dir=...` line to `local.properties` in the project root.

Windows:

```bat
gradlew.bat assembleDebug
```

macOS or Linux:

```bash
./gradlew assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

With USB or wireless debugging on, you can install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-time setup

Open the app and grant the permissions in the **Setup** section:

1. **Display over apps:** Needed for the floating bar and move hints.
2. **Accessibility:** Lets Auto mode drag the blocks. Turn on Opal:BlockSolver in the list.
3. **Screen capture:** Requested when you tap **Start**. Choose **Entire screen**.

If the Accessibility switch is greyed out, go to **Settings > Apps > Opal:BlockSolver > ⋮ > Allow restricted settings**, then try again.

## Usage

1. Tap **Start** in the app and choose **Entire screen**.
2. Open the game.
3. Tap **Suggest** or **Auto** on the floating bar.

| Control | What it does |
|---|---|
| Logo | Collapses or expands the bar. Drag to move it |
| X | Closes the app completely |
| Suggest | Shows the moves |
| Auto | Plays the moves automatically |
| Stop | Stops the current mode |
| Calibrate | Sets the board and tray areas |

The **Auto speed** settings are on the main screen:

- **Drag time:** How long each drag takes. Raise it if blocks get missed.
- **Pause between moves:** The wait between moves.
- **Vertical offset:** How far above your finger the game shows a dragged block. The default is 2.3 cells. Adjust it if blocks land on the wrong row.

## Troubleshooting

- **Auto does nothing:** If the Accessibility row on the main screen doesn't say **On**, turn the permission off and on again.
- **It stopped working after an update:** Tap **Start** again. If needed, turn the Accessibility permission off and on.
- **"Can't read the blocks":** Open **Calibrate** on the floating bar, try **Auto-detect** or drag the corners by hand, then tap **Save**.

## How it works

1. `CaptureService` captures the screen at half resolution with MediaProjection.
2. `Recognizer` reads the 8x8 board and the three tray blocks.
3. `Solver` tries every block order and position, then picks the plan that keeps the combo going.
4. `Overlay` draws the plan on screen.
5. In Auto mode, `AutoPlayService` plays the moves with accessibility gestures, then checks the board against the expected result.

## Tests

```bash
./gradlew testDebugUnitTest
```

Real game screenshots are not stored in this repository. The screenshot test is skipped when `app/src/test/resources/screenshots` is missing, and all other tests run normally.
