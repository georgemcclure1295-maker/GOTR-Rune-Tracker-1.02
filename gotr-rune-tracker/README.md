# GOTR Rune Tracker

A RuneLite plugin that tracks runes crafted during Guardians of the Rift.

## Features

- Automatically starts tracking when the GOTR game interface appears.
- Automatically pauses when the player leaves the game.
- Tracks runes crafted during the current game.
- Tracks total runes crafted during the session.
- Counts completed games from the Rift subdued game message.
- Shows average runes per completed game.
- Ignores rune withdrawals and other inventory changes outside the active minigame.

## Tracked runes

Air, Mind, Water, Earth, Fire, Body, Cosmic, Chaos, Nature, Law, Death, and Blood runes.

## Development

Run the development client on Windows with:

```powershell
.\gradlew.bat run
```

On macOS or Linux:

```bash
./gradlew run
```

## Bug reports

Please open a GitHub issue and include:

- What happened.
- What you expected to happen.
- Steps to reproduce the problem.
- Any screenshots or console errors.
- Your RuneLite version.

## License

This project is licensed under the BSD 2-Clause License.
