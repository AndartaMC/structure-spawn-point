package eiruna.structure.spawn.point;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class SpawnResolverConfig {
    private static final String CONFIG_FILE = StructureSpawnPoint.MOD_ID + ".json";

    private static final Gson GSON =
            new GsonBuilder()
                    .setPrettyPrinting()
                    .create();

    public String target_structure = "";
    public int max_search_attempts = 10;
    public int min_preferred_area_percentage = 60;
    public List<String> avoided_biomes = new ArrayList<>(List.of(
            "minecraft:is_badlands",
            "minecraft:is_beach",
            "minecraft:is_jungle",
            "minecraft:is_mountain",
            "minecraft:is_ocean",
            "minecraft:is_deep_ocean",
            "minecraft:swamp"
    ));
    public List<String> preferred_biomes = new ArrayList<>(List.of(
            "minecraft:is_forest",
            "minecraft:plains",
            "minecraft:meadow"
    ));
    public int spawn_proximity_radius = 128;
    public int kill_nearby_hostiles_radius = 64;
    public int teleport_delay_ticks = 100;
    public int terrain_flatness_check_radius = 48;
    public int max_terrain_height_difference = 16;
    public boolean send_popup_messages = true;
    public boolean send_chat_messages = true;
    public String structure_found_message = "Structure found";
    public String welcome_message = "Welcome home.";
    public String search_in_progress_title = "Searching...";
    public String search_in_progress_subtitle = "The server may be slow. Please wait.";
    public String failure_message = "No structure found.";
    public String teleport_warning_message_override = "";

    private transient boolean isValid = true;
    private transient boolean isDirty = false;

    public static SpawnResolverConfig load(String worldName) {
        Path path;
        if(worldName.isBlank()) {
            path = FabricLoader.getInstance()
                            .getConfigDir()
                            .resolve(CONFIG_FILE);
        }
        else {
            var worldConfigFile = Path.of(StructureSpawnPoint.MOD_ID, worldName + ".json");
            path = FabricLoader.getInstance()
                    .getConfigDir()
                    .resolve(worldConfigFile);
            if (!Files.exists(path)) return null;
        }

        SpawnResolverConfig config = null;

        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    config = GSON.fromJson(
                            reader,
                            SpawnResolverConfig.class
                    );
                }
                catch(Exception ignored) {}
            } else {
                config = new SpawnResolverConfig();

                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(config, writer);
                }
                catch(Exception ignored) {}
            }
        } catch(Exception ignored) {}
        if(config == null) {
            StructureSpawnPoint.LOGGER.warn("Config: Failed to load config, using defaults.");
            config = new SpawnResolverConfig();
            config.isDirty = true;
        }

        config.fixNulls();
        config.validate();

        if (config.isDirty) {
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(config, writer);
            } catch (Exception e) {
                StructureSpawnPoint.LOGGER.warn("Config: Failed to save corrected config.", e);
            }
        }

        return config;
    }

    private void fixNulls() {
        if(avoided_biomes == null){
            avoided_biomes = new ArrayList<>();
        }
        if(preferred_biomes == null){
            preferred_biomes = new ArrayList<>();
        }
    }

    public boolean isValid(){
        return isValid;
    }

    private void validate() {
        if (target_structure == null || target_structure.isBlank()) {
            StructureSpawnPoint.LOGGER.error(
                    "Config: target_structure is not set. The mod will not function."
            );
            isValid = false;
        } else if (!target_structure.contains(":") || target_structure.split(":").length != 2) {
            StructureSpawnPoint.LOGGER.error(
                    "Config: target_structure '{}' is not a valid identifier (expected format: 'namespace:path').",
                    target_structure
            );
            isValid = false;
        }
        else {
            target_structure = target_structure.replace("#", "").trim();
        }

        for (String biome: avoided_biomes){
            if(preferred_biomes.contains(biome)){
                StructureSpawnPoint.LOGGER.warn(
                        "Config: The biome {} is in both the preferred and avoided lists. The search will be unable to find a perfect match.", biome
                );
            }
        }

        max_search_attempts = clampInt("max_attempts", max_search_attempts, 1, 50);
        min_preferred_area_percentage = clampInt("min_land_coverage_percentage", min_preferred_area_percentage, 0, 100);
        spawn_proximity_radius = clampInt("spawn_proximity_radius", spawn_proximity_radius, 0, 500);
        kill_nearby_hostiles_radius = clampInt("kill_nearby_hostiles_radius", kill_nearby_hostiles_radius, 0, 500);
        terrain_flatness_check_radius = clampInt("terrain_flatness_check_radius", terrain_flatness_check_radius, 0, 128);
        max_terrain_height_difference = clampInt("max_terrain_height_difference", max_terrain_height_difference, 0, 100);

        avoided_biomes = validateBiomeList("avoided_biomes", avoided_biomes);
        preferred_biomes = validateBiomeList("preferred_biomes", preferred_biomes);
    }

    private List<String> validateBiomeList(String listName, List<String> biomes) {
        biomes.removeIf(biome -> {
            if (biome == null || biome.isBlank()) {
                StructureSpawnPoint.LOGGER.warn("Config: Empty entry in {} removed.", listName);
                return true;
            }
            if (!biome.contains(":") || biome.split(":").length != 2) {
                StructureSpawnPoint.LOGGER.warn(
                        "Config: '{}' in {} is not a valid identifier, removing.",
                        biome, listName
                );
                return true;
            }
            return false;
        });
        return biomes.stream().map(b -> b.replace("#", "").trim()).toList();
    }

    private int clampInt(String fieldName, int value, int min, int max) {
        if (value < min || value > max) {
            StructureSpawnPoint.LOGGER.warn(
                    "Config: {} value {} is out of range [{}, {}], clamping to nearest valid value.",
                    fieldName, value, min, max
            );
            isDirty = true;
            return Math.clamp(value, min, max);
        }
        return value;
    }
}