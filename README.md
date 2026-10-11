# Tidal Surger

A complete, production-ready **Paper 1.21.11** plugin adding a legendary ocean trident with two
abilities: **Tidal Surf** and **Tidal Core**.

```
F            ->  Tidal Surf   (10s cooldown)  ride a water wave forward
Shift + F    ->  Tidal Core   (20s cooldown)  slam a 10x10 water cube onto the target, shake the ground,
                                              launch the surrounding blocks and drown the victim for 3 hearts
```

While an ability is recharging a **blue BossBar at the top of the screen** drains with the remaining
cooldown and shows the seconds left.

The finished plugin jar is built to **`target/TidalSurger.jar`** (a ready-to-use copy also lives at
`dist/TidalSurger.jar`).

---

## 1. Requirements

| | |
|---|---|
| Server | Paper 1.21.11 (build 132 or newer) |
| Java | 21 (Paper 1.21.11 runs on Java 21) |
| Build tool | Maven 3.6+ (only needed if you rebuild from source) |
| Dependencies | none — the jar stands alone (no WorldGuard/ProtocolLib/Vault needed) |

---

## 2. Installation

1. Build the plugin (or use the prebuilt jar):
   ```bash
   mvn -B package
   ```
   That produces `target/TidalSurger.jar`.
2. Drop the jar into your server's `plugins/` folder:
   ```
   <server>/plugins/TidalSurger.jar
   ```
3. Restart the server (or `/reload confirm`).
4. Join and run `/ts give` (OP) to receive the Tidal Surger, then press **F**.

`plugins/TidalSurger/config.yml` is created on the first start with every value documented.

---

## 3. How the offhand keybind is detected

Minecraft never exposes raw key presses to server plugins, but the vanilla **offhand keybind (F) is
packet-visible**: pressing F makes the client send a "swap items with offhand" action, which Paper
delivers as `PlayerSwapHandItemsEvent` (documented, non-deprecated, cancellable in 1.21.11).

The plugin's own key-detection layer (`input/InputListener.java`) uses exactly that:

* **Offhand key** → `PlayerSwapHandItemsEvent` fires.
* **Shift + offhand key** → the same event, while `player.isSneaking()` is true. A quick Shift tap
  just before F also counts, using the configurable `input.sneak-grace-millis` window (350 ms), so
  the combination never feels unreliable.
* The event is **cancelled** by default (`input.cancel-vanilla-swap: true`), which turns F into a
  dedicated ability key while the trident is held instead of swapping the trident to the offhand.
* The trigger is only consumed when a **Tidal Surger** is held — every other item, and every
  ordinary trident, keeps its normal vanilla behaviour.

No client mod is required. If a player remapped their swap key, enable the documented server-side
fallback `input.right-click-fallback: true`, which maps the same two abilities to right-click and
sneak + right-click while holding the trident.

---

## 4. Abilities

### Tidal Surf — offhand key, 10 s cooldown
* Raises a flowing wave in front of the caster and carries them forward for exactly 5 s
  (`duration-ticks`, default 100).
* The wave leads: its crest and its real temporary water ride 2–4 blocks ahead of the surfer
  (`water-offset: 3`), so the ride reads as a bow wave with the surfer in its wake.
* Steerable: the carry direction follows the player's current yaw every tick.
* Blue water particles (`SPLASH`, `DOLPHIN`, `BUBBLE`, `FALLING_WATER`) form an animated crest,
  foam and a wake; a real temporary water trail is placed ahead of the surfer and restored as it fades.
* **No fall damage**: fall distance is reset every tick during the ride, and fall damage stays
  cancelled for `fall-immunity-ticks` (45 ticks ≈ 2.25 s) after the ride ends.

### Tidal Core — Shift + offhand key, 20 s cooldown
A 10x10 water cube that hovers over the target's head, slams into the ground on them and drowns them.

1. **Hover (2.5 s, `hover-ticks: 50`)** — ray-traces the crosshair (30 blocks by default: a living
   entity is preferred, otherwise the block face hit, otherwise a point at maximum range) and
   materialises the cube in the air `hover-height` blocks (4 by default) above the target's head.
   While it hangs there it keeps itself centred on a moving target (`hover-tracking`), so nobody
   can just jog out from under it. Drips fall from its underside and the victim is warned.
2. **Drop** — the whole cube falls, one block every `fall-step-ticks` (2), throwing spray off its
   leading edge, until its floor reaches `landing-offset` (2) blocks below the target's feet.
3. **Slam** — the instant the cube hits the ground the landing shockwave fires (`shockwave.*`): the
   ring of ground blocks around the impact is **launched into the air**, every entity in the blast
   radius (6 blocks by default) is thrown up and away and takes **one hit of slam damage (4 HP = 2
   hearts, `shockwave.damage`)** in the mace-smash damage type, and the ground **shakes** for 12
   ticks (expanding debris ring, rumble plus micro-shoves) — a Paladin-style ground slam. The block
   boost only happens where the slam actually hits solid ground: over open water there is nothing to
   launch and the impact stays a pure splash. The launched blocks are temporary debris that can
   never place itself (they are removed after `block-lifetime-ticks`) and launched entities get
   `fall-immunity-ticks` of fall-damage grace, so the slam is the only damage it deals.
4. **Contain + drown (5 s, `duration-ticks: 100`)** — the victims are sealed in and genuinely drown:
   with `drain-breath-bar: true` their **breath bar empties to zero**, bubbles burst off them and the
   drowning sound plays, and they take **exactly 3 hearts (6 HP, `total-damage: 6.0`)** of
   `minecraft:drown` damage spread over the five seconds in five 1.2 HP ticks. Vanilla's own
   drowning ticks are suppressed while the cube delivers the exact total, so water breathing,
   respiration and armour cannot change what the ability deals, and with `suppress-natural-regen:
   true` **natural hunger regeneration is paused while sealed — the three hearts actually stay
   lost** for the whole window (potions, golden apples and plugins can still heal).

* The victim can swim freely inside the cube but never through its boundary, and **cannot escape
  with ender pearls, chorus fruit, `/tp`, `/teleport` or another plugin's teleport** — every
  teleport out of the cube is refused and every movement packet that would cross the boundary is
  clamped back inside (backed up by a per-tick containment pass).
* **The water itself is inescapable**: anyone inside when it lands *or who enters it while it is
  active* is sealed (`trap-all-inside`) and death is the only release — dying removes the victim
  from the cube instead of dragging them back on respawn.
* The **cube's owner is completely unaffected**: they are never trapped, they can swim straight
  through the barrier to attack the victim, and they additionally receive water breathing and
  dolphin's grace while inside their own cube (`owner-water-breathing: true`).
* Visuals: dark-blue glowing edges (`SOUL_FIRE_FLAME` + `SCULK_SOUL`), aqua corner bursts and an
  inner swirling helix (`DOLPHIN`, `ELECTRIC_SPARK`), ambient water particles, a top splash ring,
  hover drips and a falling spray. Every particle is configurable.
* When the 5 seconds are up the cube collapses, the victims are released safely (no fall damage —
  anyone still off the ground is set down with a short slow-falling grace — no momentum, a message
  each) and **every changed block is restored byte-for-byte**.
* `height` is separate from `size`, so `size: 10` with `height: 4` gives a wide flat water slab
  instead of a cube if you prefer.

### Cooldown bar

* Use an ability and a **blue BossBar appears at the top of the screen** (`cooldown-bar.*`).
* Its fill is exactly the remaining fraction of the cooldown, so it **drains as the time runs out**,
  and its title shows the ability name and the remaining seconds (`<aqua>%ability% <dark_gray>|
  <white>%seconds%s` by default).
* When the ability becomes usable the bar flips to the `<green>ready` title for a moment
  (`ready-linger-ticks`) and then disappears.
* Each ability gets its own bar, so having both on cooldown shows both stacked at the top; colour
  (`color: BLUE`), style, titles and refresh rate are all configurable.
* Cooldowns are per player, per ability and persisted, so a relogging player gets their recharge
  bar back at the correct progress.

---

## 5. World safety

The plugin never permanently changes or destroys the world:

* Only **passable** blocks (air, water, plants, snow layers, …) are ever replaced. Solid terrain,
  containers and unbreakable blocks are left completely alone.
* Blocks holding tile-entity data (signs, chests, banners, …) are never replaced, because their
  contents cannot be captured by a block-data snapshot.
* Everything the plugin does replace is snapshotted first (`BlockData`) and restored with
  `applyPhysics = false`, so no block-update cascade, no water flow and no leftover water.
* On restore, a block is only reverted if it still holds the water the plugin placed, so edits made
  by players or other plugins meanwhile are never clobbered.
* `/tidalsurger cubes` lists active cubes; shutting the plugin down (`/reload`, stop, disable)
  restores every cube and every wave block immediately.

> A hard server crash (power loss, `kill -9`) cannot run the shutdown hook; a cube active at that
> instant would leave its water behind until the chunks are cleaned up. Graceful restarts are safe.

---

## 6. Commands

| Command | Permission | Description |
|---|---|---|
| `/tidalsurger give [player]` | `tidalsurger.command.give` | Gives the Tidal Surger (aliases `/ts`, `/tidalsurge`) |
| `/tidalsurger info` | — | Shows both ability cooldowns for the sender |
| `/tidalsurger cubes` | `tidalsurger.command.debug` | Lists active water cubes and their victims |
| `/tidalsurger reload` | `tidalsurger.command.reload` | Reloads `config.yml` and safely re-registers the recipe |

## 7. Permissions

| Node | Default | Description |
|---|---|---|
| `tidalsurger.use` | `true` | Base gate for activating any ability |
| `tidalsurger.ability.surf` | `true` | Allow Tidal Surf |
| `tidalsurger.ability.core` | `true` | Allow Tidal Core |
| `tidalsurger.bypass.cooldown` | `op` | Ignores both cooldowns (and the item cooldown overlay) |
| `tidalsurger.bypass.trap` | `op` | Cannot be trapped inside a water cube |
| `tidalsurger.command.give` | `op` | `/ts give` |
| `tidalsurger.command.reload` | `op` | `/ts reload` |
| `tidalsurger.command.debug` | `op` | `/ts cubes` |
| `tidalsurger.*` | `op` | Everything above |

---

## 8. Configuration

`plugins/TidalSurger/config.yml` (every key is created with a sensible default):

* **general** — `debug` console logging.
* **input** — `cancel-vanilla-swap`, `sneak-grace-millis`, `require-item-in-main-hand`,
  `right-click-fallback`, `show-item-cooldown`.
* **abilities.tidal-surf** — `enabled`, `permission`, `cooldown-seconds`, `duration-ticks`
  (100 = 5 s), `speed`, `lift`, `max-rise`, `max-fall`, `wave-width`, `water-offset` (blocks in
  front of the surfer the wave and its water ride), `place-water`, `water-fade-ticks`,
  `fall-immunity-ticks`, `sounds`.
* **abilities.tidal-core** — `enabled`, `permission`, `cooldown-seconds`, `size`, `height`,
  `hover-height`, `hover-ticks`, `landing-offset`, `hover-tracking`, `reanchor-threshold`,
  `reanchor-interval-ticks`, `fall-step-ticks`, `duration-ticks`, `total-damage`,
  `damage-interval-ticks`, `drain-breath-bar`, `suppress-natural-regen`, `damage-credit-owner`, `sounds`,
  `max-target-distance`, `trap-all-inside`, `trap-players`, `trap-mobs`, `block-ender-pearls`,
  `block-teleport-commands`, `owner-water-breathing`, `render-interval`, `edge-step`,
  `edge-particles`, `accent-particles`, `ambient-particles`, `skip-materials`, plus the landing slam
  block `shockwave` — `enabled`, `radius`, `block-count`, `block-launch`, `block-lifetime-ticks`,
  `damage`, `launch`, `knockback`, `shake`, `shake-ticks`, `fall-immunity-ticks`, `effects`.
* **cooldown-bar** — `enabled`, `color` (BLUE), `style`, `title`, `ready-title`,
  `ready-linger-ticks`, `update-interval-ticks`.
* An existing `config.yml` is **merged with new keys on startup**, so updating the plugin never
  leaves you on "Missing message" fallbacks — new settings simply appear in your file.
* **item** — display name, lore (MiniMessage), custom model data, glow, unbreakable flag,
  enchantment list and the shaped crafting recipe.
* **messages** — every message, fully MiniMessage formatted.

Cooldowns are per player, per ability, and persisted to `plugins/TidalSurger/cooldowns.yml` so
relogging cannot reset them.

---

## 9. Getting the trident in game

* `/ts give` (OP), or
* the default crafting recipe — prismarine shards + nautilus shells around a trident
  (configurable under `item.recipe`), or
* any loot/kit plugin — just use the item produced by `/ts give` as the template.

The trident is identified by a `PersistentDataContainer` tag (`tidalsurger:tidal_surger`) plus a
unique per-item id, **not** by its name, so a renamed or anvil-modified copy still works and no
ordinary trident can ever activate the abilities.

---

## 10. Project layout

```
pom.xml                                   Maven build (paper-api 1.21.11-R0.1-SNAPSHOT, Java 21)
src/main/resources/plugin.yml             plugin descriptor, commands, permissions
src/main/resources/config.yml             every default setting and message
src/main/java/com/tidalsurger/
  TidalSurgerPlugin.java                  plugin bootstrap / wiring
  config/PluginSettings.java              typed, immutable config snapshot (reload-safe)
  config/Messages.java                    MiniMessage messages with placeholders
  item/TidalSurgerItem.java               item factory, identity check, crafting recipe
  input/InputListener.java                offhand-key / Shift+offhand detection layer
  ability/AbilityType.java                the two abilities
  ability/AbilityService.java             permission + cooldown pipeline
  ability/CooldownStore.java              persisted per-player cooldowns
  ability/CooldownBars.java               the blue draining cooldown BossBar
  surf/TidalSurfManager.java              Tidal Surf (wave ride, trail, fall immunity)
  core/TidalCoreManager.java              Tidal Core (targeting, cube lifecycle)
  core/WaterCube.java                     the hovering/dropping water cube + drown damage
  core/CoreShockwave.java                 landing slam: launched blocks, blast, shaking ground
  listener/ProtectionListener.java        escape prevention + fall-damage immunity
  command/TidalSurgerCommand.java         /tidalsurger
  util/                                   MiniMessage text, block snapshots, block safety
```

## 11. Verification performed

* Built cleanly with Maven against `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` on Java 21.
* Loaded on a real Paper 1.21.11 (build 132) server — no warnings, errors or exceptions.
* End-to-end tests with a real client connection pressing the offhand key:
  * `F` → `PlayerSwapHandItemsEvent` → Tidal Surf starts, the vanilla swap is cancelled and the
    trident stays in the main hand.
  * `Shift + F` → Tidal Core starts and a `[x,y,z -> x,y,z]` cube is created around the target.
  * A second F press while riding reports `You are already riding the tide`, and the ability's own
    cooldowns are enforced per ability and per player.
  * The ride lasts **exactly 5.0 s** (100 ticks, measured 5007 ms end to end), the carry motion is
    delivered at **99 `entity_velocity` packets over the 100 ticks** with the configured 0.78 speed,
    and the wave's real water sits **2–4 blocks in front of the surfer** while riding and is fully
    restored (0 blocks left) after the ride fades.
  * The custom crafting recipe is registered (`/recipe give <player> tidalsurger:tidal_surger`
    unlocks it) and `/ts reload` can be run repeatedly without a duplicate-recipe error.
  * The victim is sealed (`trapped=1`), keeps getting pushed back at the boundary while sprinting
    and jumping into it, a `COMMAND` teleport (`/tp`) out of the cube is refused with
    `The tidal barrier refuses to let you pass.`, and the cube **owner moves freely through the
    boundary while the victim is trapped**.

### Cooldown bar

* Using Tidal Surf creates a **blue BossBar at the top of the screen**. While the 10 s cooldown ran
  its fill drained **0.73 → 0.49 → 0.34**, its title counted down (`Tidal Surf | 10.0s` at the
  start) and a second `F` was refused with `Tidal Surf | 7.3s remaining.`
* When the cooldown ended the bar flipped to `Tidal Surf ready` and disappeared 1.0 s later.
* Relogging in the middle of a cooldown rebuilt the bar from the persisted cooldown at the correct
  progress (measured fill 0.20 with `Tidal Surf | 1.9s` left).
* Tidal Core behaves the same way: `Tidal Core | 20.0s` on cast, drained 1.00 → 0.58 during the
  cooldown, and a second `Shift + F` was refused with `Tidal Core | 19.4s remaining.`

### Landing slam (Paladin-style ground shockwave)

* With the cube landing on stone the plugin logs `Tidal Core shockwave at 5.5,199.0,0.5
  (launched blocks: 32)`: **exactly 32 ground blocks** — the whole edge ring — are launched into the
  air as falling-block entities, each carrying the ground's own material (`mat=STONE`) and
  `vy=0.550`, all still valid one tick later.
* Every launched block is temporary: `diag debris` reported `n=0` afterwards and no placement ever
  went through (the only `EntityChangeBlockEvent` attempts were `cancelled=true`), with the
  world-safety test still byte-for-byte clean.
* The victim is hit once for **4.0 HP (2 hearts) of `minecraft:mace_smash`**, launched upwards
  (velocity events at `y=0.400`+) and then kept shoved around by the shaking ground for 12 ticks
  (repeated small velocity events for every entity in the radius).
* **Only on solid ground**: the same ability cast into a deep-water pool logged `launched blocks:
  0` — the scan finds no ground under the impact, so nothing boosts up and the impact stays a
  splash.

### Containment and drowning (re-verified after the slam)

* With a real second player as the victim, the cube reports `phase=contain trapped=1` and the
  victim is warned they are sealed.
* **Nothing can get out**: sprinting + jumping into the boundary produced **0.00 blocks of
  excursion** for the targeted victim, a second player teleported into the water after it landed
  was sealed too (`trapped=2`, also **0.00** excursion) and a `/tp` out was refused with `The
  tidal barrier refuses to let you pass.`
* Over the five contained seconds it deals **5 hits of 1.2 HP = exactly 6.00 HP = 3 hearts** of
  `minecraft:drown` damage. The victim's breath bar is **genuinely empty (`air=0/300`) on every
  hit**, and their pre-hit health falls exactly 16 → 14.8 → 13.6 → 12.4 → 11.2 — the 2 hearts of
  slam damage land first, then the three hearts of drowning — proving natural regeneration never
  heals any of it back while sealed; every natural-regen attempt is logged as cancelled. The
  victim is **5 hearts down (10.0 HP)** right after the window.
* The slam is the only other damage: exactly one `minecraft:mace_smash` hit of 4.0 HP and **no fall
  or other extra damage**. Launched entities get fall immunity, and the release sets victims down
  with a short slow-falling grace and **restores their breath bar**, so nothing drowns after the
  ability is over.
* **Death is a release**: with the victim killed inside, the cube drops them from its trapped list
  (still `phase=contain`), keeps running for anyone left inside, and the respawn is not dragged
  back into the water.
* The same containment works in open water: the cube sealed a swimmer inside a deep pool, drowned
  them for the full three hearts (5 hits) and released them alive with a restored breath bar.
* World-safety test on a real world, driving the **full hover → slam → contain** lifecycle: the
  cube changed 1000 blocks to water while hovering, dropped four blocks, preserved a solid stone
  block and the **text of a sign** inside the cube, and after the collapse every one of the 6069
  monitored blocks matched its original `BlockData` string exactly — zero mismatches, zero
  leftover water. The landing slam that fires during that test also left zero un-cancelled
  placements, and the same test passes with the slam enabled.
