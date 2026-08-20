package eiruna.structure.spawn.point;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;

public class SpawnResolverPersistentState extends PersistentState {
    public static final String SPAWN_STRUCTURE_X = "spawnStructureX";
    public static final String SPAWN_STRUCTURE_Y = "spawnStructureY";
    public static final String SPAWN_STRUCTURE_Z = "spawnStructureZ";
    public static final String SPAWN_STRUCTURE_ID = "spawnStructureId";
    public static final String HAS_CLEARED_MOBS = "hasClearedMobs";
    public static final String STRUCTURE_RESOLVER_LOGS = "structureResolverLogs";
    private BlockPos structurePosition = null;
    private String structureId = "";
    private boolean hasClearedMobs = false;
    private ArrayList<String> structureResolverLogs = new ArrayList<>();

    public boolean hasPosition() {
        return structurePosition != null;
    }

    public String getStructureId() {
        return structureId;
    }

    public BlockPos getStructurePosition() {
        return structurePosition;
    }

    public void setStructurePosition(BlockPos position, String structureId) {
        structurePosition = position;
        this.structureId = structureId;
        markDirty();
    }

    public void setHasClearedMobs(boolean hasClearedMobs) {
        this.hasClearedMobs = hasClearedMobs;
        markDirty();
    }

    public boolean getHasClearedMobs() {
        return hasClearedMobs;
    }

    public void setStructureResolverLogs(ArrayList<String> logs) {
        structureResolverLogs = logs;
        markDirty();
    }

    public ArrayList<String> getStructureResolverLogs() {
        return structureResolverLogs;
    }

    public void invalidate() {
        structurePosition = null;
        structureId = "";
        hasClearedMobs = false;
        structureResolverLogs.clear();
        markDirty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        nbt.putString(SPAWN_STRUCTURE_ID, structureId);
        nbt.putBoolean(HAS_CLEARED_MOBS, hasClearedMobs);
        if(structurePosition != null){
            nbt.putInt(SPAWN_STRUCTURE_X, structurePosition.getX());
            nbt.putInt(SPAWN_STRUCTURE_Y, structurePosition.getY());
            nbt.putInt(SPAWN_STRUCTURE_Z, structurePosition.getZ());
        }
        if(!structureResolverLogs.isEmpty()) {
            NbtList list = new NbtList();
            structureResolverLogs.forEach(log -> {
                NbtCompound entry = new NbtCompound();
                entry.putString("log", log);
                list.add(entry);
            });
            nbt.put(STRUCTURE_RESOLVER_LOGS, list);
        }

        return nbt;
    }

    public static SpawnResolverPersistentState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        SpawnResolverPersistentState state = new SpawnResolverPersistentState();
        state.structureId = nbt.getString(SPAWN_STRUCTURE_ID);
        state.hasClearedMobs = nbt.getBoolean(HAS_CLEARED_MOBS);
        if (nbt.contains(SPAWN_STRUCTURE_X)) {
            var x = nbt.getInt(SPAWN_STRUCTURE_X);
            var y = nbt.getInt(SPAWN_STRUCTURE_Y);
            var z = nbt.getInt(SPAWN_STRUCTURE_Z);
            state.structurePosition = new BlockPos(x, y, z);
        }

        if (nbt.contains(STRUCTURE_RESOLVER_LOGS)) {
            NbtList list = nbt.getList(STRUCTURE_RESOLVER_LOGS, NbtElement.COMPOUND_TYPE);
            list.forEach(el -> state.structureResolverLogs.add(((NbtCompound) el).getString("log")));
        }

        return state;
    }

    public static Type<SpawnResolverPersistentState> TYPE = new Type<>(
            SpawnResolverPersistentState::new,
            SpawnResolverPersistentState::fromNbt,
            null
    );
}