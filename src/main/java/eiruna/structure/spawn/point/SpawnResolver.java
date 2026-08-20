package eiruna.structure.spawn.point;

import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.List;

public class SpawnResolver {

    private int searchAttempt;
    private BlockPos searchCenter;
    private BlockPos firstFound;
    private BlockPos firstViable;
    private double firstViableScore;
    private boolean searchingForStructure;
    private final String structureId;
    private final int maxAttempts;
    private int currentSearchRadius;
    private final int searchDistanceIncrement = 4000;
    private int minX = 0;
    private int maxX = 0;
    private int minZ = 0;
    private int maxZ = 0;
    private boolean searchStarted = false;
    private int failedSurroundingBiomes;
    private int failedPreferredBiome;
    private int failedAvoidedBiome;
    private int failedFlatTerrain;
    private long lastTickTime = System.currentTimeMillis();
    private int cooldown = 0;
    private long timeSpentSearching;
    private long timeSpentCalculatingStructureViability;
    private boolean catchingUp = false;

    public SpawnResolver(String structureId) {
        searchAttempt = 0;
        searchCenter = new BlockPos(0, 0, 0);
        firstFound = null;
        firstViable = null;
        searchingForStructure = true;
        this.structureId = structureId;
        maxAttempts = StructureSpawnPoint.CONFIG.max_search_attempts;
        currentSearchRadius = searchDistanceIncrement;
        timeSpentSearching = 0;
        timeSpentCalculatingStructureViability = 0;
        firstViableScore = -1;
        failedSurroundingBiomes = 0;
        failedPreferredBiome = 0;
        failedAvoidedBiome = 0;
        failedFlatTerrain = 0;
    }

    public boolean searchCompleted() {
        return !searchingForStructure;
    }

    public ArrayList<String> getDiagnostics() {
        if (searchAttempt == 0) return new ArrayList<>(); // nothing to report

        if (searchAttempt >= maxAttempts && firstFound == null) {
            return new ArrayList<>(List.of("=== Structure Spawn Point Search Report ===",
                    String.format("Attempts used: %d/%d", searchAttempt, maxAttempts),
                    "The structure could not be found, make sure the structure id is correct. If it is correct increase the max attempts so that the search covers a larger area.",
                    "==========================================="));
        }

        var attemptsCompleted = searchAttempt;

        var logs = new ArrayList<>(List.of("=== Structure Spawn Point Search Report ===",
                String.format("Attempts used: %d/%d", attemptsCompleted, maxAttempts),
                String.format("  Failed surrounding biome checks: %d", failedSurroundingBiomes),
                String.format("  Not in preferred biome: %d", failedPreferredBiome),
                String.format("  In avoided biome: %d", failedAvoidedBiome),
                String.format("  Failed the flat terrain check: %d", failedFlatTerrain)));

        // Tips based on failure patterns
        var surroundingBiomeFailRate = (double) failedSurroundingBiomes / attemptsCompleted;
        var preferredFailRate = (double) failedPreferredBiome / attemptsCompleted;
        var avoidedFailRate = (double) failedAvoidedBiome / attemptsCompleted;
        var flatTerrainFailRate = (double) failedFlatTerrain / attemptsCompleted;

        var anyTips = surroundingBiomeFailRate > 0.8 || preferredFailRate > 0.8 || avoidedFailRate > 0.3 || flatTerrainFailRate > 0.3;

        if (anyTips) {
            logs.add("--- Suggestions ---");
        }

        if (surroundingBiomeFailRate > 0.8) {
            logs.add(String.format("%d%% of candidates failed the surrounding biome check.  " +
                            "Consider reducing 'min_correct_biome_percentage' (currently %d).",
                    Math.round(surroundingBiomeFailRate * 100),
                    StructureSpawnPoint.CONFIG.min_preferred_area_percentage));
        }

        if (preferredFailRate > 0.8 && !StructureSpawnPoint.CONFIG.preferred_biomes.isEmpty()) {
            logs.add(String.format("%d%% of candidates were not in a preferred biome. " +
                            "The structure '%s' may rarely generate in your preferred biomes, " +
                            "or your preferred biome list may be too restrictive.",
                    Math.round(preferredFailRate * 100),
                    structureId));
        }

        if (avoidedFailRate > 0.3) {
            logs.add(String.format("%d%% of candidates were in an avoided biome. " +
                            "Consider removing some entries from 'avoided_biomes'.",
                    Math.round(avoidedFailRate * 100)));
        }


        if (flatTerrainFailRate > 0.3) {
            logs.add(String.format("%d%% of candidates failed the flat terrain check. " +
                            "Consider decreasing the 'terrain_flatness_check_radius'." +
                            "and/or increasing the 'max_terrain_height_difference'." +
                            "Set 'terrain_flatness_check_radius' to 0 to disable this check for structures that naturally generate in varied terrain",
                    Math.round(flatTerrainFailRate * 100)));
        }

        if (attemptsCompleted >= maxAttempts && anyTips) {
            logs.add(String.format("The search exhausted all %d attempts. " +
                            "Increase 'max_attempts' or relax the above constraints for better results.",
                    maxAttempts));
        }

        logs.add("===========================================");

        return logs;
    }

    public void searchForBestStructure(ServerWorld world) {
        if (!searchingForStructure) return;

        long now = System.currentTimeMillis();
        long tickDuration = now - lastTickTime;
        lastTickTime = now;

        if(cooldown > 0) {
            cooldown--;
            return;
        }

        if (tickDuration > 80) {
            StructureSpawnPoint.LOGGER.info(
                    "Server tick took {}ms, giving it some time to catch up.", tickDuration);
            cooldown = 5;
            return;
        }

        if (tickDuration < 10) {
            if (!catchingUp) {
                catchingUp = true;
                StructureSpawnPoint.LOGGER.info("Server is catching up, pausing search.");
            }
            cooldown = 5;
            return;
        }
        if (catchingUp) {
            catchingUp = false;
            StructureSpawnPoint.LOGGER.info("Server caught up, resuming search.");
        }

        searchAttempt++;

        StructureSpawnPoint.LOGGER.info(
                "Attempt {}: searching from x={}, y={}, z={} for {}",
                searchAttempt, searchCenter.getX(), searchCenter.getY(), searchCenter.getZ(), structureId
        );

        var startSearch = System.currentTimeMillis();
        var candidate = locateStructures(world, searchCenter);
        var endSearch = System.currentTimeMillis();
        timeSpentSearching += endSearch - startSearch;

        if (candidate == null) {
            processNullCandidate(world);
            return;
        }

        var startCalculations = System.currentTimeMillis();
        var candidateTopPos = getSurfacePos(world, candidate);

        StructureSpawnPoint.LOGGER.info(
                "Attempt {}: found candidate at x={}, y={}, z={}",
                searchAttempt, candidateTopPos.getX(), candidateTopPos.getY(), candidateTopPos.getZ()
        );

        var viabilityPercentage = getViabilityPercentage(world, candidate);
        var preferredBiome = isPreferredBiome(world, candidateTopPos);
        var avoidedBiome = isAvoidedBiome(world, candidateTopPos);
        var flatTerrain = isTerrainFlat(world, candidate);

        if (!preferredBiome) failedPreferredBiome++;
        if (avoidedBiome) failedAvoidedBiome++;
        if (!flatTerrain) failedFlatTerrain++;

        var endCalculations = System.currentTimeMillis();
        timeSpentCalculatingStructureViability += endCalculations - startCalculations;

        processCandidate(world, candidateTopPos, viabilityPercentage, preferredBiome, avoidedBiome, flatTerrain);
    }

    private static BlockPos getSurfacePos(ServerWorld world, BlockPos pos) {
        var chunkManager = world.getChunkManager();
        var chunkGenerator = chunkManager.getChunkGenerator();
        var noiseConfig = chunkManager.getNoiseConfig();

        int y = chunkGenerator.getHeight(
                pos.getX(),
                pos.getZ(),
                Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                world,
                noiseConfig
        );

        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    private BlockPos locateStructures(ServerWorld world, BlockPos searchCenter) {
        var locateStructureSearchRadius = 100;
        var chunkGenerator = world.getChunkManager().getChunkGenerator();

        var structureTag = TagKey.of(RegistryKeys.STRUCTURE,
                Identifier.of(structureId));

        var structureRegistry = world.getRegistryManager()
                .get(RegistryKeys.STRUCTURE);
        var structureEntryList = structureRegistry
                .getOrCreateEntryList(structureTag);

        var nearest = chunkGenerator.locateStructure(
                world,
                structureEntryList,
                searchCenter,
                locateStructureSearchRadius,
                false
        );

        if (nearest == null) {
            var entry = structureRegistry.getEntry(Identifier.of(structureId));
            if (entry.isPresent()) {
                StructureSpawnPoint.LOGGER.debug("No tag found for '{}', trying as direct structure ID.", structureId);
                RegistryEntryList<Structure> structureList = RegistryEntryList.of(entry.get());
                nearest = chunkGenerator.locateStructure(
                        world,
                        structureList,
                        searchCenter,
                        locateStructureSearchRadius,
                        false
                );
            } else {
                StructureSpawnPoint.LOGGER.warn(
                        "'{}' is neither a valid structure tag nor a valid structure ID.", structureId);
            }
        }

        return nearest != null ? nearest.getFirst() : null;
    }

    private void processCandidate(ServerWorld world, BlockPos candidate, int viabilityPercentage, boolean preferredBiome, boolean avoidedBiome, boolean flatTerrain) {
        if (firstFound == null) {
            firstFound = candidate;
        }

        var viableLocation = false;
        viableLocation = viabilityPercentage >= StructureSpawnPoint.CONFIG.min_preferred_area_percentage;

        if (viableLocation) {
            StructureSpawnPoint.LOGGER.info("Structure has enough land in the correct biomes around it to be viable.");
        } else {
            failedSurroundingBiomes++;
            StructureSpawnPoint.LOGGER.info("Structure does not have enough land in the correct biomes around it to be viable.");
        }

        if (viableLocation && preferredBiome && !avoidedBiome && flatTerrain) {
            StructureSpawnPoint.LOGGER.info("Found ideal structure at x={}, y={}, z={} after {} attempts.", candidate.getX(), candidate.getY(), candidate.getZ(), searchAttempt);
            saveAndFinish(world, candidate);
            return;
        }

        if (searchAttempt >= maxAttempts) {
            StructureSpawnPoint.LOGGER.warn("Ran out of search attempts before finding a perfect match.");
            finishSearch(world);
            return;
        }

        var score = getViabilityScore(viabilityPercentage, preferredBiome, avoidedBiome, flatTerrain);
        if (firstViable == null || score > firstViableScore) {
            firstViable = candidate;
            firstViableScore = score;
        }

        StructureSpawnPoint.LOGGER.info("Structure at x={}, y={}, z={} rejected, searching further.", candidate.getX(), candidate.getY(), candidate.getZ());

        calculateNewSearchCenter();
    }

    private void processNullCandidate(ServerWorld world) {
        if (searchAttempt >= maxAttempts) {
            StructureSpawnPoint.LOGGER.warn("Ran out of search attempts before finding a perfect match.");
            finishSearch(world);
            return;
        }

        StructureSpawnPoint.LOGGER.info("No structure found, searching further.");

        calculateNewSearchCenter();
    }

    private void calculateNewSearchCenter() {
        if (!searchStarted) {
            minX = -currentSearchRadius + searchDistanceIncrement;
            maxX = currentSearchRadius;
            minZ = -currentSearchRadius;
            maxZ = currentSearchRadius;

            searchStarted = true;
            searchCenter = new BlockPos(minX + searchDistanceIncrement, 0, minZ);
            return;
        }

        var previousSearchCenterX = searchCenter.getX();
        var previousSearchCenterZ = searchCenter.getZ();

        if (previousSearchCenterX == minX && previousSearchCenterZ == minZ) {
            StructureSpawnPoint.LOGGER.info("The search did a loop {} blocks from spawn without finding a perfect candidate. Increasing search radius.", currentSearchRadius);
            currentSearchRadius += currentSearchRadius;
            searchStarted = false;
            calculateNewSearchCenter();
            return;
        }
        if (previousSearchCenterX < maxX && previousSearchCenterZ == minZ) {
            searchCenter = new BlockPos(previousSearchCenterX + searchDistanceIncrement, 0, minZ);
            return;
        }
        if (previousSearchCenterZ < maxZ && previousSearchCenterX == maxX) {
            searchCenter = new BlockPos(maxX, 0, previousSearchCenterZ + searchDistanceIncrement);
            return;
        }
        if (previousSearchCenterX > minX && previousSearchCenterZ == maxZ) {
            searchCenter = new BlockPos(previousSearchCenterX - searchDistanceIncrement, 0, maxZ);
            return;
        }
        if (previousSearchCenterZ > minZ && previousSearchCenterX == minX) {
            searchCenter = new BlockPos(minX, 0, previousSearchCenterZ - searchDistanceIncrement);
        }
    }

    private void finishSearch(ServerWorld world) {
        if (firstViable != null) {
            StructureSpawnPoint.LOGGER.info("Using best viable candidate.");
            saveAndFinish(world, firstViable);
        } else if (firstFound != null) {
            StructureSpawnPoint.LOGGER.warn("Using nearest found structure as last resort.");
            saveAndFinish(world, firstFound);
        } else {
            StructureSpawnPoint.LOGGER.warn("No structure found. Players will use default spawn.");
            saveAndFinish(world, null);
        }
    }


    private void saveAndFinish(ServerWorld world, BlockPos pos) {
        var state = world.getPersistentStateManager()
                .getOrCreate(SpawnResolverPersistentState.TYPE, StructureSpawnPoint.MOD_ID + "_spawn_resolver");

        if (pos != null) {
            state.setStructurePosition(pos, structureId);
            world.setSpawnPos(pos, 0f);
            world.getPersistentStateManager().save();
            StructureSpawnPoint.LOGGER.info("Structure spawn point set to x={}, y={}, z={}.", pos.getX(), pos.getY(), pos.getZ());
        }
        searchingForStructure = false;

        var endtime = System.currentTimeMillis();
        var searchTime = endtime - StructureSpawnPoint.startTime;
        StructureSpawnPoint.LOGGER.info("The search took {} milliseconds.", searchTime);
        var seconds = searchTime / 1000;
        if (seconds > 0) {
            StructureSpawnPoint.LOGGER.info("Which is {} seconds.", seconds);
            var minutes = seconds / 60;
            if (minutes > 0) {
                StructureSpawnPoint.LOGGER.info("Or {} minutes.", minutes);
            }
        }
        StructureSpawnPoint.LOGGER.info("Out of this time {} milliseconds were spent locating the structures.", timeSpentSearching);
        StructureSpawnPoint.LOGGER.info("and {} milliseconds were spent doing viability calculations.", timeSpentCalculatingStructureViability);

        var diagnostics = getDiagnostics();
        for (String log : diagnostics) {
            StructureSpawnPoint.LOGGER.info(log);
        }
        state.setStructureResolverLogs(diagnostics);
        world.getPersistentStateManager().save();
    }

    private static double getViabilityScore(int viabilityPercentage, boolean preferredBiome, boolean avoidedBiome, boolean flatTerrain) {
        double score = 0;

        if (preferredBiome) {
            score += 100;
        }

        if (!avoidedBiome) {
            score += 25;
        }

        if (flatTerrain) {
            score += 50;
        }

        score += viabilityPercentage * 0.5;

        return score;
    }

    private static int getViabilityPercentage(ServerWorld world, BlockPos pos) {
        float acceptableBiome = 0;
        float preferredBiome = 0;
        float avoidedBiome = 0;
        float totalBlocksChecked = 0;

        int checkRadius = 192;
        int step = 32;

        var chunkGenerator = world.getChunkManager().getChunkGenerator();
        var noiseConfig = world.getChunkManager().getNoiseConfig();

        int posX = pos.getX();
        int posZ = pos.getZ();

        for (int x = -checkRadius; x <= checkRadius; x += step) {
            for (int z = -checkRadius; z <= checkRadius; z += step) {
                int worldX = posX + x;
                int worldZ = posZ + z;

                int y = chunkGenerator.getHeight(
                        worldX,
                        worldZ,
                        Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                        world,
                        noiseConfig
                );

                var biome = getBiome(world, new BlockPos(worldX, y, worldZ));

                var avoided = isAvoidedBiome(biome);
                var preferred = isPreferredBiome(biome);

                if(avoided) {
                    avoidedBiome++;
                }
                if(preferred) {
                    preferredBiome++;
                }
                if(!avoided && !preferred) {
                    acceptableBiome++;
                }
                totalBlocksChecked++;
            }
        }

        float score = acceptableBiome
                    + preferredBiome * 2
                    - avoidedBiome * 3;
        float maximumScore = totalBlocksChecked * 2;

        StructureSpawnPoint.LOGGER.info("Surrounding biome check got a score of {} which is {}%.", score, (score / maximumScore) * 100);
        return Math.max(0, Math.round((score / maximumScore) * 100));
    }

    private static boolean isPreferredBiome(RegistryEntry<Biome> biome) {
        var preferredBiomes = StructureSpawnPoint.CONFIG.preferred_biomes;
        if (preferredBiomes.isEmpty()) return true;

        return preferredBiomes.stream().anyMatch(entry -> {
            TagKey<Biome> tag = TagKey.of(RegistryKeys.BIOME,
                    Identifier.of(entry));
            return biome.isIn(tag) || biome.matchesId(Identifier.of(entry));
        });
    }

    private static boolean isPreferredBiome(ServerWorld world, BlockPos pos) {
        var preferredBiomes = StructureSpawnPoint.CONFIG.preferred_biomes;
        if (preferredBiomes.isEmpty()) return true;

        // Sample a cross pattern around the structure anchor
        List<BlockPos> samplePoints = List.of(
                pos,
                pos.add(32, 0, 0),
                pos.add(-32, 0, 0),
                pos.add(0, 0, 32),
                pos.add(0, 0, -32)
        );

        long preferredCount = samplePoints.stream()
                .map(p -> getBiome(world, p))
                .filter(biome -> preferredBiomes.stream().anyMatch(entry -> {
                    String cleanEntry = entry.startsWith("#") ? entry.substring(1) : entry;
                    TagKey<Biome> tag = TagKey.of(RegistryKeys.BIOME, Identifier.of(cleanEntry));
                    return biome.isIn(tag) || biome.matchesId(Identifier.of(cleanEntry));
                }))
                .count();

        boolean isPreferred = preferredCount >= 3; // majority of 5 points

        StructureSpawnPoint.LOGGER.info(
                "{}/5 sample points are in a preferred biome.", preferredCount);

        return isPreferred;
    }

    private static RegistryEntry<Biome> getBiome(ServerWorld world, BlockPos pos) {
        var chunkManager = world.getChunkManager();
        var chunkGenerator = chunkManager.getChunkGenerator();
        var noiseConfig = chunkManager.getNoiseConfig();
        var biomeSource = chunkGenerator.getBiomeSource();

        //Minecraft stores biomes at 4-block resolution, so we need to divide block coordinates by 4
        return biomeSource.getBiome(
                pos.getX() >> 2,  // divide by 4 using bit shift
                pos.getY() >> 2,
                pos.getZ() >> 2,
                noiseConfig.getMultiNoiseSampler());
    }

    private static boolean isAvoidedBiome(RegistryEntry<Biome> biome) {
        var avoidedBiomes = StructureSpawnPoint.CONFIG.avoided_biomes;
        if (avoidedBiomes.isEmpty()) return false;

        return avoidedBiomes.stream().anyMatch(entry -> {
            var tag = TagKey.of(RegistryKeys.BIOME,
                    Identifier.of(entry));
            return biome.isIn(tag) || biome.matchesId(Identifier.of(entry));
        });
    }

    private static boolean isAvoidedBiome(ServerWorld world, BlockPos pos) {
        var avoidedBiomes = StructureSpawnPoint.CONFIG.avoided_biomes;
        if (avoidedBiomes.isEmpty()) return false;

        List<BlockPos> samplePoints = List.of(
                pos,
                pos.add(32, 0, 0),
                pos.add(-32, 0, 0),
                pos.add(0, 0, 32),
                pos.add(0, 0, -32)
        );

        long avoidedCount = samplePoints.stream()
                .map(p -> getBiome(world, p))
                .filter(biome -> avoidedBiomes.stream().anyMatch(entry -> {
                    String cleanEntry = entry.startsWith("#") ? entry.substring(1) : entry;
                    TagKey<Biome> tag = TagKey.of(RegistryKeys.BIOME, Identifier.of(cleanEntry));
                    return biome.isIn(tag) || biome.matchesId(Identifier.of(cleanEntry));
                }))
                .count();

        boolean isAvoided = avoidedCount >= 3;

        StructureSpawnPoint.LOGGER.info(
                "{}/5 sample points are in an avoided biome.", avoidedCount);

        return isAvoided;
    }

    private static boolean isTerrainFlat(ServerWorld world, BlockPos pos) {
        int checkRadius = StructureSpawnPoint.CONFIG.terrain_flatness_check_radius;
        if (checkRadius <= 0) return true; // disabled

        int maxAllowedDifference = StructureSpawnPoint.CONFIG.max_terrain_height_difference;
        int step = 16;

        var chunkGenerator = world.getChunkManager().getChunkGenerator();
        var noiseConfig = world.getChunkManager().getNoiseConfig();

        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;

        for (int x = -checkRadius; x <= checkRadius; x += step) {
            for (int z = -checkRadius; z <= checkRadius; z += step) {
                int y = chunkGenerator.getHeight(
                        pos.getX() + x,
                        pos.getZ() + z,
                        Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                        world,
                        noiseConfig
                );
                minHeight = Math.min(minHeight, y);
                maxHeight = Math.max(maxHeight, y);
            }
        }

        int heightDifference = maxHeight - minHeight;

        StructureSpawnPoint.LOGGER.info(
                "Terrain check: min={}, max={}, difference={}, allowed={}",
                minHeight, maxHeight, heightDifference, maxAllowedDifference
        );

        return heightDifference <= maxAllowedDifference;
    }
}
