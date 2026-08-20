package eiruna.structure.spawn.point;

import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.TeleportTarget;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

public class PlayerTeleporter {
    private boolean readyToTeleport;
    private final ArrayList<ServerPlayerEntity> playerTeleportQueue = new ArrayList<>();
    private final ArrayList<DelayedTask> delayQueue = new ArrayList<>();
    private final ServerWorld world;
    private boolean hasClearedMobs;
    private boolean teleportLocationNotFound;
    private long lastTickTime = System.currentTimeMillis();
    private int cooldown = 0;
    private final Queue<ChunkPos> chunksToClear = new LinkedList<>();
    private final int chunksPerTick = 4; // tune this
    private long lastSearchMessageMs = 0;

    public PlayerTeleporter(ServerWorld world) {
        this.world = world;
        this.hasClearedMobs = StructureSpawnPoint.getSpawnResolverPersistentState().getHasClearedMobs();
        teleportLocationNotFound = false;
    }

    public PlayerTeleporter(ServerWorld world, boolean readyToTeleport) {
        this.world = world;
        this.readyToTeleport = readyToTeleport;
        this.hasClearedMobs = StructureSpawnPoint.getSpawnResolverPersistentState().getHasClearedMobs();
        teleportLocationNotFound = false;
    }

    public void setReadyToTeleport(boolean readyToTeleport) {
        this.readyToTeleport = readyToTeleport;
    }

    public boolean isReadyToTeleport() {
        return readyToTeleport;
    }

    public void add(ServerPlayerEntity player) {
        if(teleportLocationNotFound) {
            StructureSpawnPoint.LOGGER.info("Player joined, but no structure could been found.");
            Message.send(player, StructureSpawnPoint.CONFIG.failure_message);
            StructureSpawnPoint.getSpawnedPlayersPersistentState().markSpawned(player.getUuid());
            world.getPersistentStateManager().save();
            return;
        }
        if(!readyToTeleport) {
            StructureSpawnPoint.LOGGER.info("Player joined before structure was found. Waiting for structure search to be completed...");
            playerTeleportQueue.add(player);
            Message.send(player, StructureSpawnPoint.CONFIG.search_in_progress_title,
                    StructureSpawnPoint.CONFIG.search_in_progress_subtitle);
        }
        else {
            StructureSpawnPoint.LOGGER.info("Player joined after structure was found. Queueing teleport...");
            setUpPlayerTeleport(player);
        }
    }

    public void sendSearchProgressMessage() {
        long now = System.currentTimeMillis();
        if (now - lastSearchMessageMs < 30000) return; // every 30 seconds
        lastSearchMessageMs = now;

        for (ServerPlayerEntity player : playerTeleportQueue) {
            Message.send(player, StructureSpawnPoint.CONFIG.search_in_progress_title,
                    StructureSpawnPoint.CONFIG.search_in_progress_subtitle);
        }
    }

    public void executeTeleport() {
        long now = System.currentTimeMillis();
        long tickDuration = now - lastTickTime;
        lastTickTime = now;

        if(!readyToTeleport) {
            return;
        }

        if(playerTeleportQueue.isEmpty() && delayQueue.isEmpty()) {
            return;
        }

        if(cooldown > 0) {
            cooldown--;
            return;
        }

        //only log the first time the tick duration spikes
        if (tickDuration > 60) {
            StructureSpawnPoint.LOGGER.info(
                    "Server tick took {}ms, giving it some time to catch up.", tickDuration);
        }

        //but also wait for the rapidfire ticks while the server is attempting to catch up
        if (tickDuration > 60 || tickDuration < 15) {
            cooldown = 5;
            return;
        }

        if(!playerTeleportQueue.isEmpty()){
            StructureSpawnPoint.LOGGER.info("Structure found. Starting to teleport waiting players...");
            while(!playerTeleportQueue.isEmpty()) {
                setUpPlayerTeleport(playerTeleportQueue.removeFirst());
            }
        }
        if(!delayQueue.isEmpty()){
            var toRemove = new ArrayList<DelayedTask>();
            for(DelayedTask delay: delayQueue){
                if(delay.isReady()){
                    delay.execute();
                    toRemove.add(delay);
                }
            }
            for(DelayedTask delay: toRemove){
                delayQueue.remove(delay);
            }
        }
    }

    public void teleportLocationNotFound() {
        teleportLocationNotFound = true;
        var playerState = StructureSpawnPoint.getSpawnedPlayersPersistentState();

        for(ServerPlayerEntity player: playerTeleportQueue) {
            Message.send(player, StructureSpawnPoint.CONFIG.failure_message);
            playerState.markSpawned(player.getUuid());
            world.getPersistentStateManager().save();
        }

        playerTeleportQueue.clear();
        delayQueue.clear();
    }

    private void setUpPlayerTeleport(ServerPlayerEntity player) {
        var playerState = StructureSpawnPoint.getSpawnedPlayersPersistentState();

        boolean alreadyAtStructure = player.getBlockPos().isWithinDistance(world.getSpawnPos(), StructureSpawnPoint.CONFIG.spawn_proximity_radius);

        if (!alreadyAtStructure) {
            teleportToStructureOrScheduleDelay(player);
        } else {
            Message.send(player, StructureSpawnPoint.CONFIG.welcome_message);
            playerState.markSpawned(player.getUuid());
            world.getPersistentStateManager().save();
            if (!hasClearedMobs) {
                StructureSpawnPoint.LOGGER.info("Player already at structure, scheduling mob clear.");
                StructureSpawnPoint.pendingMobClear.add(player.getUuid());
            }
        }
    }

    private void teleportToStructureOrScheduleDelay(ServerPlayerEntity player)
    {
        StructureSpawnPoint.LOGGER.info("Teleporting to new spawn location.");

        int configuredTeleportDelay = StructureSpawnPoint.CONFIG.teleport_delay_ticks;
        if(configuredTeleportDelay > 0) {
            sendTeleportWarningMessage(player, configuredTeleportDelay);
            // Note: no deduplication check on the queue. A player who disconnects
            // during a search and rejoins before the teleport delay expires could
            // theoretically be added twice, resulting in a duplicate welcome message
            // and a second near-identical teleport. Accepted as a known edge case
            // given the negligible impact and low probability.
            delayQueue.add(new DelayedTask(configuredTeleportDelay, () -> doPlayerTeleport(player)));
            return;
        }
        else {
            Message.send(player, StructureSpawnPoint.CONFIG.structure_found_message);
        }

        doPlayerTeleport(player);
    }

    private void sendTeleportWarningMessage(ServerPlayerEntity player, int configuredTeleportDelay) {
        String teleportMessage = StructureSpawnPoint.CONFIG.teleport_warning_message_override;
        if(teleportMessage.isBlank()) {
            teleportMessage = String.format("Teleporting in %s seconds...",
                    Math.round(configuredTeleportDelay / world.getTickManager().getTickRate()));
        }

        Message.send(player, StructureSpawnPoint.CONFIG.structure_found_message, teleportMessage);
    }

    private void doPlayerTeleport(ServerPlayerEntity player ) {
        if (!player.isDisconnected()) {
            BlockPos structurePos = world.getSpawnPos();
            BlockPos surfacePos = world.getTopPosition(
                    Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(structurePos.getX(), 0, structurePos.getZ())
            ).up(1);

            if (!hasClearedMobs) {
                StructureSpawnPoint.LOGGER.info("Mobs will be cleared as soon as the chunks are loaded.");
                StructureSpawnPoint.pendingMobClear.add(player.getUuid());
            }

            TeleportTarget target = new TeleportTarget(
                    world,
                    Vec3d.ofCenter(surfacePos),
                    Vec3d.ZERO,
                    player.getYaw(),
                    player.getPitch(),
                    entity -> {
                        var radius = StructureSpawnPoint.CONFIG.kill_nearby_hostiles_radius;
                        if(radius > 0) {
                            //clear only max one chunk to start with to prevent teleport lag
                            var structurePosition = world.getSpawnPos();
                            ChunkPos center = new ChunkPos(structurePosition);
                            clearMobsInChunk(center, structurePosition, radius);
                        }
                        Message.send(player, StructureSpawnPoint.CONFIG.welcome_message);
                    }
            );

            player.teleportTo(target);
            StructureSpawnPoint.getSpawnedPlayersPersistentState().markSpawned(player.getUuid());
            world.getPersistentStateManager().save();
        }
    }

    public void clearMobsAtSpawn() {
        hasClearedMobs = true;
        StructureSpawnPoint.getSpawnResolverPersistentState().setHasClearedMobs(true);

        var structurePosition = world.getSpawnPos();
        int radius = StructureSpawnPoint.CONFIG.kill_nearby_hostiles_radius;
        int chunkRadius = (radius >> 4) + 1;

        ChunkPos center = new ChunkPos(structurePosition);
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                chunksToClear.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
    }

    public void processPendingChunkClears() {
        if (chunksToClear.isEmpty()) return;

        var structurePosition = world.getSpawnPos();
        int radius = StructureSpawnPoint.CONFIG.kill_nearby_hostiles_radius;

        int processed = 0;
        while (!chunksToClear.isEmpty() && processed < chunksPerTick) {
            ChunkPos chunkPos = chunksToClear.poll();
            world.getChunk(chunkPos.x, chunkPos.z); // load chunk

            // Clear mobs in this chunk only
            clearMobsInChunk(chunkPos, structurePosition, radius);

            processed++;
        }

        if (chunksToClear.isEmpty()) {
            StructureSpawnPoint.LOGGER.info("Mob clearing complete.");
        }
    }

    private void clearMobsInChunk(ChunkPos chunkPos, BlockPos structurePosition, int radius) {
        Box chunkBox = new Box(
                chunkPos.getStartX(), structurePosition.getY() - 64,
                chunkPos.getStartZ(),
                chunkPos.getEndX(), structurePosition.getY() + 300,
                chunkPos.getEndZ()
        );

        world.getEntitiesByClass(HostileEntity.class, chunkBox, mob -> {
            BlockPos mobPos = mob.getBlockPos();
            return mobPos.isWithinDistance(structurePosition, radius);
        }).forEach(HostileEntity::discard);
    }
}