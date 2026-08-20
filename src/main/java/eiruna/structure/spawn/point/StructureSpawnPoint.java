package eiruna.structure.spawn.point;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class StructureSpawnPoint implements ModInitializer {
	public static final String MOD_ID = "structure-spawn-point";
	public static final PrefixLogger LOGGER = new PrefixLogger(LoggerFactory.getLogger(MOD_ID), "[StructureSpawnPoint] ");
	public static SpawnResolverConfig CONFIG;

	public static long startTime;
	private SpawnResolver spawnResolver = null;
	private PlayerTeleporter playerTeleporter = null;
	private static ServerWorld overworld;
	private static MinecraftServer minecraftServer;
	private static boolean searchDisabled = false;
	public static final Set<UUID> pendingMobClear = new HashSet<>();

	@Override
	public void onInitialize() {
		CONFIG = SpawnResolverConfig.load("");

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			spawnResolver = null;
			playerTeleporter = null;
			minecraftServer = server;
			overworld = server.getOverworld();
			searchDisabled = false;

			if (!minecraftServer.getSaveProperties().getGeneratorOptions().shouldGenerateStructures()) {
				LOGGER.info("Structure generation is disabled. Skipping search.");
				searchDisabled = true;
				return;
			}

			loadConfiguration();
			if(!CONFIG.isValid()) {
				searchDisabled = true;
				return;
			}
			String configuredStructure = CONFIG.target_structure;

			invalidateIfConfigChanged(configuredStructure);
			initializeSearch();
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			pendingMobClear.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if(searchDisabled) {
				return;
			}
			if(overworld == null) {
				overworld = server.getOverworld();
			}

			if(playerTeleporter != null) {
				playerTeleporter.executeTeleport();
				playerTeleporter.processPendingChunkClears();
				if (spawnResolver != null && !spawnResolver.searchCompleted()) {
					playerTeleporter.sendSearchProgressMessage();
				}
			}

			if (!StructureSpawnPoint.pendingMobClear.isEmpty()) {
				clearMobsAtSpawn();
			}

			if(spawnResolver == null) {
				return;
			}

			SpawnResolverPersistentState spawnResolverState = getSpawnResolverPersistentState();

			if (spawnResolver.searchCompleted()) {
				if (spawnResolverState.hasPosition()) {
					BlockPos structurePosition = spawnResolverState.getStructurePosition();
					overworld.setSpawnPos(structurePosition, 0f);
					if(!playerTeleporter.isReadyToTeleport()) {
						playerTeleporter.setReadyToTeleport(true);
					}
				}
				else {
					playerTeleporter.teleportLocationNotFound();
				}
				spawnResolver = null;
				return;
			}
			spawnResolver.searchForBestStructure(overworld);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if(searchDisabled) {
				return;
			}
			if(!CONFIG.isValid()) return;
			LOGGER.info("Player joining server");

			ServerPlayerEntity player = handler.player;

			SpawnedPlayersPersistentState playerState = getSpawnedPlayersPersistentState();

			if (!playerState.hasSpawned(player.getUuid())) {
				playerTeleporter.add(player);
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
					CommandManager.literal("structurespawnpoint")
							.then(CommandManager.literal("status")
									.requires(source -> source.hasPermissionLevel(2)) // op only
									.executes(context -> {
										if(searchDisabled) {
											context.getSource().sendFeedback(
													() -> Text.literal("Structure generation is disabled. Nothing to report."), false
											);
											return 1;
										}
										if(spawnResolver != null) {
											var logs = spawnResolver.getDiagnostics();
											context.getSource().sendFeedback(
													() -> Text.literal("""
                                                            SEARCH IN PROGRESS:
                                                            """), false
											);
											if(!logs.isEmpty()) {
												for(String log: logs) {
													context.getSource().sendFeedback(
															() -> Text.literal(log), false
													);
												}
											}
										}
										else {
											var resolverState = getSpawnResolverPersistentState();
											var logs = resolverState.getStructureResolverLogs();
											if(!logs.isEmpty()) {
												context.getSource().sendFeedback(
														() -> Text.literal("""
                                                            SEARCH COMPLETED:
                                                            """), false
												);
												if (resolverState.hasPosition()) {
													BlockPos pos = resolverState.getStructurePosition();
													context.getSource().sendFeedback(() -> Text.literal(
															"Structure: " + resolverState.getStructureId() + "\n" +
																	"Position: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
													), false);
												}
												for(String log: logs) {
													context.getSource().sendFeedback(
															() -> Text.literal(log), false
													);
												}
											}
											else {
												context.getSource().sendFeedback(
														() -> Text.literal("Unable to retrieve the status report."), false
												);
											}
										}

										return 1; // return 1 = success, 0 = failure
									})
							)
							.then(CommandManager.literal("reset")
									.requires(source -> source.hasPermissionLevel(2))
									.executes(context -> {
										if(searchDisabled) {
											context.getSource().sendFeedback(
													() -> Text.literal("Structure generation is disabled. Skipping search."), false
											);
											return 1;
										}
										loadConfiguration();
										if(!CONFIG.isValid()) {
											context.getSource().sendFeedback(
													() -> Text.literal("The structure spawn point configuration is invalid, the structure resetting is cancelled."), false
											);
											return 1;
										}
										invalidate();
										initializeSearch();
										for (ServerPlayerEntity player : context.getSource().getServer().getPlayerManager().getPlayerList()) {
											playerTeleporter.add(player);
										}
										context.getSource().sendFeedback(
												() -> Text.literal("The structure spawn point has been reset."), false
										);
										return 1;
									})
							)
			);
		});
	}

	private static void loadConfiguration() {
		String worldName = minecraftServer.getSaveProperties().getLevelName();
		SpawnResolverConfig worldConfig = SpawnResolverConfig.load(worldName);
		CONFIG = worldConfig != null ? worldConfig : SpawnResolverConfig.load("");
	}

	private void initializeSearch() {
		SpawnResolverPersistentState spawnResolverState = getSpawnResolverPersistentState();
		String configuredStructure = CONFIG.target_structure;

		if (!spawnResolverState.hasPosition()) {
			startTime = System.currentTimeMillis();

			playerTeleporter = new PlayerTeleporter(overworld);
			spawnResolver = new SpawnResolver(configuredStructure);

			LOGGER.info("Initializing search.");
		}
		else {
			playerTeleporter = new PlayerTeleporter(overworld, spawnResolverState.hasPosition());

			LOGGER.info("Search has already completed. Checking for players to be teleported.");
		}
	}

	public static SpawnedPlayersPersistentState getSpawnedPlayersPersistentState() {
		return overworld.getPersistentStateManager()
				.getOrCreate(SpawnedPlayersPersistentState.TYPE, MOD_ID + "_spawned_players");
	}

	public static SpawnResolverPersistentState getSpawnResolverPersistentState() {
		return overworld.getPersistentStateManager()
				.getOrCreate(SpawnResolverPersistentState.TYPE, MOD_ID + "_spawn_resolver");
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	private void invalidateIfConfigChanged(String configuredStructure) {
		String savedStructureId = getSpawnResolverPersistentState().getStructureId();
		if (!savedStructureId.isEmpty() && !savedStructureId.equals(configuredStructure)) {
			LOGGER.info("Configured structure changed from {} to {}, re-searching.",
					savedStructureId, configuredStructure);
			invalidate();
		}
	}

	private void invalidate() {
		spawnResolver = null;
		playerTeleporter = null;
		getSpawnResolverPersistentState().invalidate();
		getSpawnedPlayersPersistentState().invalidate();
		overworld.getPersistentStateManager().save();
		pendingMobClear.clear();
	}

	private void clearMobsAtSpawn() {
		Set<UUID> cleared = new HashSet<>();

		for (UUID uuid : StructureSpawnPoint.pendingMobClear) {
			ServerPlayerEntity player = minecraftServer.getPlayerManager().getPlayer(uuid);
			if (player == null) {
				if (playerTeleporter != null) {
					LOGGER.info("Player disconnected before mobs were cleared, attempting to clear them anyway.");
					playerTeleporter.clearMobsAtSpawn();
					cleared.add(uuid);
				}
				continue;
			}

			ChunkPos chunkPos = new ChunkPos(player.getBlockPos());
			if (overworld.isChunkLoaded(chunkPos.x, chunkPos.z)) {
				LOGGER.info("Chunks are loaded. Clearing mobs.");
				playerTeleporter.clearMobsAtSpawn();
				cleared.add(uuid);
			}
			// Otherwise wait another tick
		}

		StructureSpawnPoint.pendingMobClear.removeAll(cleared);
	}
}
