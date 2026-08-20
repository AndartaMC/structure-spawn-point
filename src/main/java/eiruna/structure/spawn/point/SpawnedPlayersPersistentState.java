package eiruna.structure.spawn.point;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentState;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SpawnedPlayersPersistentState extends PersistentState {
    private Set<UUID> spawnedPlayers = new HashSet<>();

    public boolean hasSpawned(UUID playerUuid) {
        return spawnedPlayers.contains(playerUuid);
    }

    public void markSpawned(UUID playerUuid) {
        spawnedPlayers.add(playerUuid);
        markDirty();
    }

    public void invalidate() {
        spawnedPlayers = new HashSet<>();
        markDirty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        NbtList list = new NbtList();
        spawnedPlayers.forEach(uuid -> {
            NbtCompound entry = new NbtCompound();
            entry.putUuid("uuid", uuid);
            list.add(entry);
        });
        nbt.put("spawnedPlayers", list);
        return nbt;
    }

    public static SpawnedPlayersPersistentState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        SpawnedPlayersPersistentState state = new SpawnedPlayersPersistentState();
        if(nbt.contains("spawnedPlayers")) {
            NbtList list = nbt.getList("spawnedPlayers", NbtElement.COMPOUND_TYPE);
            list.forEach(el -> state.spawnedPlayers.add(((NbtCompound) el).getUuid("uuid")));
        }
        return state;
    }

    public static Type<SpawnedPlayersPersistentState> TYPE = new Type<>(
            SpawnedPlayersPersistentState::new,
            SpawnedPlayersPersistentState::fromNbt,
            null
    );
}