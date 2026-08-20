# Structure Spawn Point

A Fabric mod that finds a structure in the world and sets it as the world spawn point. Players are automatically teleported there on their first join.

Designed for modpack authors who want players to start inside a specific structure — a fort, a village, a dungeon, or anything else — without manual setup or per-world configuration.

---

## How it works

On server start, the mod searches the world for the configured structure. The search spirals outward from world center, evaluating each candidate against your biome preferences and terrain requirements. When a suitable location is found, the world spawn is set there and any players already waiting are teleported. Players who join later are teleported on join.

The search runs on the server thread with cooldowns between attempts. Depending on how strict your configuration is, it can take anywhere from a few seconds to a few minutes. A player message can be configured to inform players that any lag is temporary.

---

## Getting started

1. Install the mod in your Fabric 1.21.1 modpack alongside Fabric API.
2. Start the server or launch singleplayer once — the mod generates a default config at `config/structure-spawn-point.json`.
3. Set `target_structure` to the structure you want players to spawn in.
4. Restart the server. The search runs automatically on every fresh world.

For world-specific configuration, create a file at `config/structure-spawn-point/<world-name>.json`. World-specific configs take priority over the global config and use the same fields.

---

## Finding your structure ID

Use the `/locate structure` command in game to find structures near you. The ID shown (e.g. `nova_structures:stray_fort`) is what you put in `target_structure`. The mod supports both specific structure IDs and structure tags — just enter either format and the mod tries both automatically. The `#` prefix used in commands is optional and stripped automatically.

```json
"target_structure": "nova_structures:stray_fort"
```

Examples of valid values:
- `nova_structures:stray_fort` — a specific structure added by a mod
- `minecraft:village_plains` — a specific vanilla village variant (plains only)
- `minecraft:village` — any village variant, resolved via structure tag

---

## Structure location

These settings control how the mod searches for and evaluates candidate structures.

**`preferred_biomes`** *(default: forest, plains, meadow)*
Biomes the structure should ideally be in. Use `namespace:biome_id` for a specific biome or `namespace:biome_tag` for a category. The `#` prefix is optional. Leave empty to accept any biome.
```json
"preferred_biomes": ["minecraft:is_forest", "minecraft:plains", "minecraft:meadow"]
```

**`avoided_biomes`** *(default: badlands, beach, jungle, mountain, ocean, swamp)*
Biomes to avoid. Same format as `preferred_biomes`. A candidate where the majority of sample points fall in an avoided biome is rejected.
```json
"avoided_biomes": ["minecraft:is_ocean", "minecraft:is_badlands"]
```

**`min_preferred_area_percentage`** *(default: 60, range: 0–100)*
The minimum quality score for the area surrounding the structure (384×384 blocks). The score is based on how much of the surrounding area is in preferred or neutral biomes, with preferred biomes counting double and avoided biomes penalizing heavily. Set to 0 to skip the area check and only consider the structure's immediate biome.

**`terrain_flatness_check_radius`** *(default: 48, range: 0–128)*
Radius in blocks to check for terrain flatness around the structure. The mod measures the height difference between the highest and lowest surface blocks in this area and rejects candidates that exceed `max_terrain_height_difference`. Set to 0 to disable the terrain check.

**`max_terrain_height_difference`** *(default: 16, range: 0–100)*
Maximum allowed height difference in blocks within the terrain check radius. 16 accepts gentle hills; lower values require flatter ground.

---

## Player teleportation and messages

These settings control how and when players are teleported, and what messages they see.

**`teleport_delay_ticks`** *(default: 100)*
How long to wait before teleporting a player after they join, in ticks (20 ticks = 1 second). A warning is shown during the delay. Set to 0 for an instant teleport with no warning.

**`spawn_proximity_radius`** *(default: 128)*
Players already within this many blocks of the structure skip the teleport and receive the welcome message instead. This handles the common case where a player naturally spawns at world spawn after the structure has already been found.

**`kill_nearby_hostiles_radius`** *(default: 64)*
Radius in blocks around the structure to clear hostile mobs when the first player arrives. Mobs are only cleared once per world. Set to 0 to disable.

**`send_popup_messages`** *(default: true)*
Enables or disables all popup messages (title screen). Individual messages can also be disabled by leaving them empty.

**`send_chat_messages`** *(default: true)*
Enables or disables all chat messages. Individual messages can also be disabled by leaving them empty.

**`structure_found_message`** *(default: "Structure found")*
Popup title shown when a player is about to be teleported.

**`welcome_message`** *(default: "Welcome home.")*
Chat message shown after a successful teleport.

**`search_in_progress_title`** *(default: "Searching...")*
Popup title shown periodically to players who join while the search is still running.

**`search_in_progress_subtitle`** *(default: "The server may be slow. Please wait.")*
Popup subtitle shown alongside `search_in_progress_title`.

**`failure_message`** *(default: "No structure found.")*
Chat message shown to players if the search completes without finding any candidate.

**`teleport_warning_message_override`** *(default: "")*
Overrides the automatic "Teleporting in X seconds..." countdown subtitle. Useful for translating the message or changing its wording. Leave empty to use the default countdown.

Leaving any message field empty disables that specific message entirely.

---

## Advanced and performance options

**`max_search_attempts`** *(default: 10, range: 1–50)*
How many candidate locations to evaluate before giving up and using the best found so far. Each attempt takes roughly 1–4 seconds depending on the structure type. The search spirals outward from world center so increasing this covers a larger area. If the search consistently exhausts all attempts, increase this value or relax biome/terrain constraints — the diagnostic report will tell you which checks are failing most.

The search is intentionally run on the server thread and will cause some server lag while active. This is expected behavior and temporary. Players who join during the search are informed via the `search_in_progress_title` and `search_in_progress_subtitle` messages. The server becomes fully responsive again as soon as a suitable location is found.

---

## Commands

All commands require operator level 2.

**`/structurespawnpoint status`**
Shows the current search status, the found position and structure ID, and a diagnostic report with actionable suggestions if the search struggled to find a good match.

**`/structurespawnpoint reset`**
Clears the saved spawn point and triggers a new search. All online players are notified and will be teleported when the new location is found. The config is reloaded on reset so config changes take effect without a full server restart.

---

## Diagnostics

When a search completes, a diagnostic report is logged to the console and saved. It is accessible at any time via `/structurespawnpoint status`. The report shows how many candidates failed each check and includes specific suggestions when a pattern is detected:

- **High surrounding area failure rate** → reduce `min_preferred_area_percentage`
- **High preferred biome failure rate** → your preferred biomes may be too restrictive for this structure type, or the structure rarely generates in those biomes
- **High avoided biome failure rate** → too many biomes are being avoided; consider removing some entries
- **Structure not found at all** → verify the structure ID is correct; increase `max_search_attempts` to cover a larger area

---

## Compatibility

- **Terrain overhaul mods** (tested with Terralith) — fully compatible
- **Structure placement mods** (tested with Improved Village Placement) — fully compatible
- **Other spawn point mods** — compatible; Structure Spawn Point will typically override others since its validation checks take longer to complete, causing it to set the world spawn last
- **Replay mods** (tested with Flashback) — fully compatible; observer joins during replays do not trigger teleportation
- **Nether and End dimensions** — not supported; the mod searches the overworld only

---

## Notes

- Players may briefly appear to fall through the world during teleportation while destination chunks load. This is a visual artifact caused by the client receiving the teleport before chunk data arrives, and causes no damage.
- Underwater structures are supported but will spawn the player underwater. Pair with a water breathing mod or disable `kill_nearby_hostiles_radius` if using an ocean structure.
- The search only runs on fresh worlds. Once a spawn point is saved it persists across server restarts. Use `/structurespawnpoint reset` to force a new search.
- Singleplayer and dedicated server are both supported.