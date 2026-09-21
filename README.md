# Mortar

A standalone Paper 1.21.11 plugin for target-lock ballistic mortars. Java 21 is required. It has no external runtime dependencies and does not modify web hosting or install a server resource pack.

## Playing

1. Obtain a mortar with `/mortar` (administrator) or have an administrator give you one.
2. Hold the mortar and **right-click** a visible block, fluid surface or entity to lock that position. The action bar shows coordinates, distance and approximate flight time.
3. Put a custom shell in your **offhand** to select it. Without a custom shell, plain TNT in your inventory/offhand supplies HE ammunition.
4. **Left-click** to launch. Each shell follows an arc that reaches the target from above. Locking an entity records its position at selection time; shells do not home in on moving entities.

Reload time defaults to 2.5 seconds. One shell/TNT is consumed per launch in survival. Creative players and players with `mortar.infiniteammo` do not consume ammunition. The same locked target can be fired on repeatedly; moving changes the newly calculated launch trajectory.

The arc is calculated for the exact per-tick gravity integration, with its apex approximately 64 blocks above the higher of the muzzle and target. This is a mathematical aim solution, not obstacle avoidance: a roof, cliff or entity in the trajectory can intercept the shell. The muzzle velocity is solved from target position and clearance, rather than constrained to a fixed weapon speed.

## Ammunition

| ID | Default behavior |
| --- | --- |
| `he` | Impact explosion, power 8 |
| `penetrating` | Moves 8 blocks beyond a block impact along the incoming direction, then explodes at power 8 |
| `depth_charge` | **THE DEPTH CHARGE**: blasts at depths 4/6/8/10, powers 9/8/7/6, four ticks apart |
| `airburst` | While descending, detonates when terrain/fluid is within 8 blocks below; power 7 |
| `cluster` | Deploys at 16 blocks above terrain, producing eight downward impact strikes in a radius of 5, power 1.8 each, two ticks apart |
| `incendiary_grenade` | Impact explosion, power 3, leaves a persistent vanilla fire patch |
| `smoke` | 20-second particle smoke cloud, radius 6, no explosion |
| `illumination` | Deploys around 12 blocks above terrain; glowing flare and five actual LIGHT blocks for 30 seconds |

Ordinary shells detonate on block, fluid or entity impact only. Airburst, cluster and illumination use explicit descending height triggers, not fuse timers. Depth-charge staging and cluster delays begin only after deployment/impact. A depth charge that hits an entity directly makes a single power-9 explosion; penetration applies to block hits only. Cluster strikes with no surface within their configured search distance do not explode in midair.

Penetration follows the impact direction, not necessarily vertical depth. It places the subsequent detonation inside terrain rather than simulating material resistance or drilling a tunnel. Explosion power and block damage follow Minecraft explosion mechanics and Bukkit/Paper explosion event cancellation. Defaults are destructive; administrators can set `block-damage: false` to retain entity damage without terrain destruction.

## Commands and permissions

```text
/mortar
/mortar give <player> [amount]
/mortar ammo <player> <munition-id> [amount]
/mortar cooldown <seconds>
/mortar reload
/mortar status
```

`/mortar cooldown 2.5` changes reload time immediately, persists it in `plugins/Mortar/config.yml`, and needs no restart. Allowed values are 0.05–60 seconds. `/mortar reload` reads the entire configuration; already airborne shells and active effects retain their original settings. Tab completion lists ammunition IDs and online players. Giving requires sufficient inventory space; a full inventory does not delete or drop gifts.

| Permission | Default | Purpose |
| --- | --- | --- |
| `mortar.use` | everyone | Target and fire |
| `mortar.admin` | operators | All `/mortar` commands |
| `mortar.infiniteammo` | operators | Fire without consuming shells/TNT |

## Installation

Build/download `Mortar-1.0.0.jar`, stop the Minecraft server cleanly, put the JAR into its `plugins` directory, and start it. The default configuration is created under `plugins/Mortar/`. Issue new items with `/mortar give` and `/mortar ammo`.

Mortar uses its own command, permissions and persistent item namespace. Existing worlds and other plugins are not modified by installation.

## Chunk loading and cleanup

Shells have **no artificial flight distance or lifetime limit** and continue above the world build ceiling. Their server-side simulation does not depend on what the shooter can render. Generated chunks along the swept flight segment are held temporarily; blast neighborhoods and delayed effects also hold generated chunks until finished. A shared reference count ensures that one shell or effect cannot release a chunk ticket still needed by another.

Target selection only traces currently loaded chunks. Flights and effects never deliberately generate new terrain: they stop if a required chunk or blast neighborhood is ungenerated/unavailable. Other termination conditions are the lower void, world border, externally removed projectile display, shooter death/disconnection/world change, world unload, plugin shutdown and internal errors. Activity caps reject new launches rather than expiring existing shells. These limits protect normal server operation; they are distinct from a flight range cutoff.

Flares place lights only in air, preserve the prior air block data, share overlapping light ownership, and hold the relevant chunks. They restore lights on expiry, owner cleanup, world unload and clean plugin shutdown, without overwriting solid replacements. **A hard process crash cannot run cleanup and may leave LIGHT blocks behind.** Avoid force-killing the server while flares are active. `/mortar status` reports active projectiles, effects, chunk tickets and lights.

## Optional resource pack

`resource-pack/` supplies 32×32 hard-edged pixel-art sprites for the mortar and three shell families, namespaced as `mortar`. It contains only mortar assets and vanilla item selectors. All eight shell types retain distinct names, with related shell types sharing their visual family.

The plugin uses string `custom_model_data` values. Without the pack, the mortar appears as a named blaze rod and shells as named firework stars, and all functionality still works. It does not force downloads or configure any hosting service.

To create the ZIP:

```sh
mkdir -p target
cd resource-pack
zip -r ../target/Mortar-resource-pack-1.0.0.zip pack.mcmeta assets
```

Upload the ZIP to your chosen host and configure Paper's standard `resource-pack` / `resource-pack-sha1` properties if desired; use `require-resource-pack=false` to keep it optional. SHA-1 can be calculated with `sha1sum target/Mortar-resource-pack-1.0.0.zip`. Do not overwrite an existing server pack URL without preserving its other assets.

When merging into another pack, copy `assets/mortar/` and **merge the `mortar:*` selector cases** into the existing `assets/minecraft/items/blaze_rod.json` and `firework_star.json`. Preserve the existing cases and fallback; replacing the whole selector file would hide another plugin's models. If the existing item uses a different selector shape, nest the Mortar selector around it and use the old model as its fallback. Pack format is 75.0 for Minecraft 1.21.11.

## Build and tests

```sh
mvn verify
```

Or with Docker, without installing Java or Maven on the host:

```sh
docker run --rm -v "$PWD:/workspace" -w /workspace \
  maven:3.9-eclipse-temurin-21 mvn verify
```

Tests simulate the exact discrete flight update across short/long distances, elevation changes, above-ceiling arcs and long flight times. Shared chunk lease tests verify overlapping projectile/effect ownership, rollback on unavailable terrain/errors, moving segment release and shutdown cleanup. Compilation verifies the Paper 1.21.11 API. In-game validation is still needed for visual orientation, sounds, live explosion/protection-plugin interactions, and light propagation in a real world.

The scope is the working handheld mortar feature set. Deployable tubes, propellant charges, spotting teams and dispersion were design proposals and are not implemented here.
