**The Explorer has a voice.**

A bug fix, nothing else.

## Fixed

- **Bumping into an Explorer crashed the game.** MineColonies looks a citizen's voice lines up by the name of his job, and 0.2.0 gave the Explorer none, so the first line he had to say (the greeting when a player walks into him) threw a `NullPointerException` in `SoundUtils` and took the server tick down with it: "Ticking player" in singleplayer. The Explorer now speaks with the Courier's lines, as he wears the Courier's clothes. Nothing else changes, and worlds from 0.2.0 load as they were.

## Requires

The same as 0.2.0: Minecraft 1.21.1, NeoForge 21.1 and MineColonies 1.1.x.
