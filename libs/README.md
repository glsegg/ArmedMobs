# libs/ - where the TaCZ jar goes

This directory is where the build looks for **TaCZ (Timeless and Classics Zero)**, which this mod
compiles against and requires at runtime but **never bundles and never redistributes**.

Nothing in here is committed (see `.gitignore`).

## What to put here

Drop the TaCZ jar in with exactly this name:

```
libs/tacz-1.20.1-1.1.8-hotfix.jar
```

A different build is fine too - change `tacz_jar=` in `gradle.properties` to the new file name.

## Why the jar is not committed

It is ~57 MB of somebody else's mod. `mods.toml` declares a hard `tacz` dependency instead, so
players install TaCZ themselves; our shipped jar only contains our own code plus the bundled
GeckoLib.

## Why the build deobfuscates it (`fg.deobf`)

A released mod jar carries **SRG** member names (`ItemStack.m_41720_()`), while the ForgeGradle
dev workspace uses **official** (Mojang) names. The raw jar therefore *compiles* against fine -
class names are identical in both mapping sets - but would die with `NoSuchMethodError` at dev
runtime. `fg.deobf(files(...))` produces a dev-mapped copy that both the compiler and `runServer`
use. Production players load the real, untouched, SRG-named jar, which is exactly what Forge
expects there.
