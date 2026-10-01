# Oritech Smart Splitter

A backport of Oritech's **Smart Splitter** to Minecraft **1.21.1** / NeoForge **21.1.200+**.

The Smart Splitter is a block that Oritech 2.0.0 introduced for Minecraft 26.1.2. It does not exist
in Oritech's 1.21.1 releases, so this mod ports it: the same block, the same three distribution
modes and the same side-learning behaviour, rebuilt against the 1.21.1 APIs.

| | |
| --- | --- |
| Mod id | `oritechsplitter` |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.200 or newer |
| Requires | [Oritech](https://modrinth.com/mod/oritech) 1.2.0+ (1.21.1 build) |
| License | MIT |

## What it does

The Smart Splitter is a small item buffer that distributes items evenly between its horizontal
outputs. It stores one stack at a time, so it cannot mix different item types.

It talks to the standard NeoForge item handler capability and is **not** tied to Oritech's item
pipes: Oritech pipes, vanilla hoppers and other mods' transfer systems can all interact with it.

### Automatic side configuration

The splitter learns how each horizontal side is used:

* a side becomes an **input** after an insertion through it is committed;
* a side becomes an **output** as soon as a pipe inspects it for extraction or tries to extract
  through it — even when the splitter is empty or the extraction is only simulated;
* unused sides stay **closed**.

Items can also be inserted from above or below, but only horizontal sides can be outputs.

Right-click a horizontal face to toggle it between output and closed. Sneak-right-click the block to
cycle the distribution mode; the selected mode is shown in chat.

### Distribution modes

**Strict** — items are reserved as evenly as possible for every configured output, and a pipe can
only take the share reserved for its side. Remainder items rotate between outputs over subsequent
insertions. This guarantees an even split, but items reserved for a blocked output stay in the
splitter.

**Overflow** — items start with the same even reservations as Strict. If an output makes no progress
for about one second, its reserved items become available to the other active outputs. Use this when
an even split is preferred but keeping items moving matters more.

**Round Robin** — only one output may extract at a time, and after a committed extraction the next
configured output gets its turn. Simulated or rolled-back extractions do not consume a turn, and the
amount moved is decided by the extracting pipe.

## Recipe

```
 p
pfp
 p
```

`p` = `oritech:item_pipe`, `f` = `oritech:item_filter_block` (the 1.21.1 id of Oritech's item filter).

## Building

```bash
./gradlew build              # jar in build/libs/
```

The build needs **JDK 21**. Either put it on `PATH` / in `JAVA_HOME`, or register a local
installation in `~/.gradle/gradle.properties`:

```properties
org.gradle.java.installations.paths=/path/to/jdk-21
```

### A note on mirrors

This project was developed on a network that cannot reach some upstream hosts directly. Two
adjustments are baked in; both are safe to change if your network differs.

**Gradle distribution.** `gradle/wrapper/gradle-wrapper.properties` points at a Gradle mirror because
`services.gradle.org` answers small requests but stalls on the distribution itself. The
`distributionSha256Sum` is Gradle's official checksum and matches the mirror file byte for byte, so
switching `distributionUrl` back to
`https://services.gradle.org/distributions/gradle-9.2.1-bin.zip` is verified and safe.

**NeoForge Maven.** `maven.neoforged.net` is unreachable from some networks (the connection is
reset). If your build cannot reach it, add an init script at
`~/.gradle/init.d/neoforged-mirror.gradle` that rewrites that host to a reachable mirror:

```groovy
def rewriteToMirror = { repo ->
    if (repo instanceof org.gradle.api.artifacts.repositories.MavenArtifactRepository) {
        def url = repo.url?.toString()
        if (url?.contains('maven.neoforged.net')) {
            repo.setUrl(url.replaceFirst('https?://maven\\.neoforged\\.net', 'https://neoforged.forgecdn.net'))
        }
    }
}
beforeSettings { settings ->
    settings.pluginManagement.repositories.all(rewriteToMirror)
    settings.dependencyResolutionManagement.repositories.all(rewriteToMirror)
}
projectsLoaded { g ->
    g.rootProject.allprojects { project ->
        project.buildscript.repositories.all(rewriteToMirror)
        project.repositories.all(rewriteToMirror)
    }
}
```

## Testing

The distribution logic is covered by 16 game tests:

```bash
./gradlew syncTestModsFromMaven   # stage Oritech + Architectury + GeckoLib into run/mods
./gradlew runGameTestServer       # run the tests headlessly
```

They cover the even split, the reservation bookkeeping, the simulate/commit split, side learning,
all three modes, the one-stack rule and the block drops. One of them drives the splitter through
Oritech's own item API rather than the raw handler, so the contract with Oritech's pipes is covered
too.

For a playable client you need Oritech's full mod set, including its client-only dependencies. If you
already have a launcher instance with Oritech installed, stage it instead:

```bash
./gradlew syncTestMods                                     # everything, for runClient
./gradlew syncTestModsServer                               # server safe subset, for runServer
./gradlew syncTestMods -PtestModsDir=/path/to/mods         # use another mods folder
./gradlew runClient
```

`run/mods` is git-ignored scratch space; all three tasks are `Sync`, so it always ends up matching
exactly what you asked for.

## Porting notes

Upstream is written against the newer NeoForge transfer API (`ResourceHandler<ItemResource>`,
`TransactionContext`, `SnapshotJournal`). None of that exists on 1.21.1, so the behaviour was
re-expressed with the 1.21.1 standard item API, `IItemHandler`:

| Upstream (26.1) | Here (1.21.1) |
| --- | --- |
| `ResourceHandler<ItemResource>` per side | `IItemHandler` per side, exposed through `Capabilities.ItemHandler.BLOCK` |
| `TransactionContext` + `SnapshotJournal` rollback | `simulate == true` captures a `SplitterSnapshot` and reverts it before returning |
| Probe for new outputs in `getResource` | Probe in `extractItem` — see below |
| `SimpleInventoryStorage` (one slot) | a single `ItemStack` field with the same capacity and validity rules |
| `ValueInput` / `ValueOutput` persistence | `CompoundTag` + `HolderLookup.Provider` (`loadAdditional` / `saveAdditional`) |
| `TooltipProvider#addToTooltip` | `Block#appendHoverText` |
| `useWithoutItem`, `getDrops` | unchanged — same signatures on 1.21.1 |

### Why the output probe is in `extractItem`, not `getStackInSlot`

Upstream learns that a side is an output from its *read* path (`ResourceHandler#getResource`).
That does not carry over to 1.21.1, where `IItemHandler#getStackInSlot` is a generic read that an
**inserting** pipe also performs. Oritech's item pipe checks whether a destination was empty before
it inserts:

```java
boolean wasEmptyStorage = IntStream.range(0, targetStorage.getSlotCount())
        .allMatch(slot -> targetStorage.getStackInSlot(slot).isEmpty());   // destination read
int inserted = targetStorage.insert(stackToMove.copyWithCount(remainingToMove), false);
```

Probing on that read marked the receiving side as an output, and an output refuses items, so the
insert that followed always failed and the splitter never filled up. Because the probe also performs
a real `setBlock`, running it on every read flooded the server with world updates.

The probe therefore lives in `extractItem`, which is the genuine 1.21.1 extraction path
(`extractFromSlot(..., true)` and `extract(..., false)` both route there). Upstream's rule that a
*purely simulated* extraction still teaches the splitter survives, because the probe runs before the
simulate check.

One behavioural difference follows: a side is no longer learned while the splitter is **empty**,
since a pipe skips empty slots before it ever probes. The side is learned as soon as the first item
arrives, so the splitter still converges on the right configuration.

Two deliberate differences from upstream:

* **`noOcclusion()`** is set on the block. The model is an open frame, but the default full-cube
  collision shape would make neighbouring blocks cull the faces they share with the splitter and
  leave visible holes. Upstream targets a newer Minecraft version where this does not bite.
* **The item model** points at the block's base frame rather than composing the base plus four
  rotated output chutes the way upstream's `items/` model does. 1.21.1 has no composite model
  loader, so the four-chute icon is not reproducible without hand-merging rotated geometry. This is
  a cosmetic difference in the inventory icon only; the placed block renders all four sides.

### Model format: element rotations are baked, not converted

This one bites hard if you port assets from a newer Minecraft, so it is worth spelling out.

1.21.1 accepts only a **single-axis** element rotation whose angle is one of `0`, `+/-22.5` or
`+/-45`:

```json
"rotation": { "origin": [8, 8, 0.5], "axis": "y", "angle": -45, "rescale": false }
```

Upstream's models use the newer multi-axis form `{"x": 0, "y": -90, "z": 0, "origin": [...]}` and
rely on quarter turns. Handed to 1.21.1 unchanged they fail with

```
JsonSyntaxException: Missing axis, expected to find a string
    at BlockElement$Deserializer.getAxis
```

and the block renders as the purple/black missing model. Note that `format_version: 1.21.11` is
silently ignored by 1.21.1 — nothing warns you that the model is in a newer dialect.

Because every rotation upstream uses is a quarter turn about a single axis, a rotated box is still
axis-aligned, so
[`tools/import_upstream_assets.py`](tools/import_upstream_assets.py) applies the rotation to the box
corners and collapses the result back to a `from`/`to` pair, dropping the `rotation` field
entirely. Face UVs move to whichever face the rotated normal points at.

One approximation remains: when a face's normal is unchanged by the rotation (an up/down face under
a Y rotation, or a north/south face under a Z rotation) the texture should also spin within the
face. 1.21.1 has no per-face UV rotation, so a 180 degree case is handled by flipping the UV
rectangle and a 90 degree case is left alone — those are small sliver faces on the chute caps.

The recipe, loot table and advancement follow the 1.21.1 data layout (`recipe/`, `loot_table/`,
`advancement/`, singular) rather than upstream's newer one, and the recipe uses
`oritech:item_filter_block`, which is what Oritech 1.21.1 calls its item filter.

### Provenance

The block models and textures were imported from `oritech-2.0.0-exp6.jar` (Minecraft 26.1.2) with
[`tools/import_upstream_assets.py`](tools/import_upstream_assets.py), which strips the newer model
format fields, bakes element rotations, retargets textures into this mod's namespace and emits the
multipart blockstate. The empty game test structure is generated by
[`tools/generate_empty_gametest_structure.py`](tools/generate_empty_gametest_structure.py).

## Credits

Oritech and the Smart Splitter are by **Rearth** — <https://github.com/Rearth/Oritech>. Oritech is
released under CC0-1.0, so this port is permitted; the attribution is given voluntarily. The block
design, models and textures originate from that project.

## License

MIT — see [`LICENSE`](LICENSE). Third-party attribution is in [`NOTICE.md`](NOTICE.md).
