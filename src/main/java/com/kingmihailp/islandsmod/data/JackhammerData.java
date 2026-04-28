package com.kingmihailp.islandsmod.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

public class JackhammerData extends SavedData {

    private static final String NAME = "jackhammer_hits";
    private final Map<Long, Integer> hits = new HashMap<>();

    private JackhammerData() {}

    public static JackhammerData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(JackhammerData::new, JackhammerData::load, null),
                NAME);
    }

    private static JackhammerData load(CompoundTag tag, HolderLookup.Provider registries) {
        JackhammerData data = new JackhammerData();
        long[] keys   = tag.getLongArray("keys");
        int[]  values = tag.getIntArray("values");
        for (int i = 0; i < Math.min(keys.length, values.length); i++) {
            data.hits.put(keys[i], values[i]);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long[] keys   = new long[hits.size()];
        int[]  values = new int[hits.size()];
        int i = 0;
        for (Map.Entry<Long, Integer> e : hits.entrySet()) {
            keys[i]   = e.getKey();
            values[i] = e.getValue();
            i++;
        }
        tag.putLongArray("keys", keys);
        tag.putIntArray("values", values);
        return tag;
    }

    public int addHit(BlockPos pos) {
        long key = pos.asLong();
        int count = hits.getOrDefault(key, 0) + 1;
        hits.put(key, count);
        setDirty();
        return count;
    }

    public void remove(BlockPos pos) {
        hits.remove(pos.asLong());
        setDirty();
    }
}
