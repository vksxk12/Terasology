/*
 * Copyright 2013 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.world;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.CoreRegistry;
import org.terasology.engine.SimpleUri;
import org.terasology.math.Region3i;
import org.terasology.math.TeraMath;
import org.terasology.math.Vector3i;
import org.terasology.utilities.procedural.PerlinNoise;
import org.terasology.world.block.Block;
import org.terasology.world.block.management.BlockManager;
import org.terasology.world.chunks.Chunk;
import org.terasology.world.chunks.ChunkProvider;
import org.terasology.world.lighting.LightPropagator;
import org.terasology.world.lighting.LightingUtil;
import org.terasology.world.lighting.PropagationComparison;
import org.terasology.world.liquid.LiquidData;
import org.terasology.world.time.WorldTime;
import org.terasology.world.time.WorldTimeImpl;

import java.util.List;

/**
 * @author Immortius
 */
public class WorldProviderCoreImpl implements WorldProviderCore {
    private static final Logger logger = LoggerFactory.getLogger(WorldProviderCoreImpl.class);

    private String title;
    private String seed = "";
    private SimpleUri worldGenerator;

    private WorldBiomeProvider biomeProvider;
    private ChunkProvider chunkProvider;
    private WorldTime worldTime;

    private PerlinNoise fogNoise;

    private final List<WorldChangeListener> listeners = Lists.newArrayList();

    public WorldProviderCoreImpl(String title, String seed, long time, SimpleUri worldGenerator, ChunkProvider chunkProvider) {
        this.title = (title == null) ? seed : title;
        this.seed = seed;
        this.worldGenerator = worldGenerator;
        this.biomeProvider = new WorldBiomeProviderImpl(seed);
        this.chunkProvider = chunkProvider;
        this.fogNoise = new PerlinNoise(seed.hashCode() + 42 * 42);
        this.fogNoise.setOctaves(8);
        CoreRegistry.put(ChunkProvider.class, chunkProvider);
        this.worldTime = new WorldTimeImpl();
        worldTime.setMilliseconds(time);
    }

    public WorldProviderCoreImpl(WorldInfo info, ChunkProvider chunkProvider) {
        this(info.getTitle(), info.getSeed(), info.getTime(), info.getWorldGenerator(), chunkProvider);
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getSeed() {
        return seed;
    }

    @Override
    public WorldInfo getWorldInfo() {
        return new WorldInfo(title, seed, worldTime.getMilliseconds(), worldGenerator);
    }

    @Override
    public WorldBiomeProvider getBiomeProvider() {
        return biomeProvider;
    }

    @Override
    public void registerListener(WorldChangeListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    @Override
    public void unregisterListener(WorldChangeListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    @Override
    public ChunkView getLocalView(Vector3i chunkPos) {
        return chunkProvider.getLocalView(chunkPos);
    }

    @Override
    public ChunkView getWorldViewAround(Vector3i chunk) {
        return chunkProvider.getSubviewAroundChunk(chunk);
    }

    @Override
    public boolean isBlockRelevant(int x, int y, int z) {
        return chunkProvider.isChunkReady(TeraMath.calcChunkPos(x, y, z));
    }

    @Override
    public boolean setBlocks(BlockUpdate... updates) {
        // TODO: Implement
        return false;
    }

    @Override
    public boolean setBlocks(Iterable<BlockUpdate> updates) {
        // TODO: Implement
        return false;
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block type, Block oldType) {
        Vector3i blockPos = new Vector3i(x, y, z);
        ChunkView chunkView;

        if (oldType == type) {
            return true;
        }

        if (LightingUtil.compareLightingPropagation(type, oldType) != PropagationComparison.IDENTICAL || type.getLuminance() != oldType.getLuminance()) {
            chunkView = chunkProvider.getSubviewAroundBlock(blockPos, Chunk.MAX_LIGHT + 1);
        } else {
            chunkView = chunkProvider.getSubviewAroundBlock(blockPos, 1);
        }
        if (chunkView != null) {
            chunkView.lock();
            try {
                Block current = chunkView.getBlock(x, y, z);
                if (current != oldType) {
                    return false;
                }
                chunkView.setBlock(x, y, z, type);

                Region3i affected = new LightPropagator(chunkView).update(x, y, z, type, oldType);
                if (affected.isEmpty()) {
                    chunkView.setDirtyAround(blockPos);
                } else {
                    chunkView.setDirtyAround(affected);
                }

                notifyBlockChanged(x, y, z, type, oldType);

                return true;
            } finally {
                chunkView.unlock();
            }
        }
        return false;
    }

    @Override
    public void setBlockForced(int x, int y, int z, Block type) {
        Vector3i blockPos = new Vector3i(x, y, z);
        ChunkView chunkView = chunkProvider.getSubviewAroundBlock(blockPos, Chunk.MAX_LIGHT + 1);
        if (chunkView != null) {
            chunkView.lock();
            try {
                Block current = chunkView.getBlock(x, y, z);
                chunkView.setBlock(x, y, z, type);

                Region3i affected = new LightPropagator(chunkView).update(x, y, z, type, current);
                if (affected.isEmpty()) {
                    chunkView.setDirtyAround(blockPos);
                } else {
                    chunkView.setDirtyAround(affected);
                }

                notifyBlockChanged(x, y, z, type, current);
            } finally {
                chunkView.unlock();
            }
        }
    }

    private void notifyBlockChanged(int x, int y, int z, Block type, Block oldType) {
        Vector3i pos = new Vector3i(x, y, z);
        // TODO: Could use a read/write lock
        synchronized (listeners) {
            for (WorldChangeListener listener : listeners) {
                listener.onBlockChanged(pos, type, oldType);
            }
        }
    }

    @Override
    public boolean setLiquid(int x, int y, int z, LiquidData newState, LiquidData oldState) {
        Vector3i chunkPos = TeraMath.calcChunkPos(x, y, z);
        Chunk chunk = chunkProvider.getChunk(chunkPos);
        if (chunk != null) {
            chunk.lock();
            try {
                Vector3i blockPos = TeraMath.calcBlockPos(x, y, z);
                LiquidData liquidState = chunk.getLiquid(blockPos);
                if (liquidState.equals(oldState)) {
                    chunk.setLiquid(blockPos, newState);
                    return true;
                }
            } finally {
                chunk.unlock();
            }
        }
        return false;
    }

    @Override
    public LiquidData getLiquid(int x, int y, int z) {
        if (y >= Chunk.SIZE_Y || y < 0) {
            return new LiquidData();
        }

        Vector3i chunkPos = TeraMath.calcChunkPos(x, y, z);
        Chunk chunk = chunkProvider.getChunk(chunkPos);
        if (chunk != null) {
            Vector3i blockPos = TeraMath.calcBlockPos(x, y, z);
            return chunk.getLiquid(blockPos);
        }
        logger.warn("Attempted to access unavailable chunk via liquid data at {}, {}, {}", x, y, z);
        return new LiquidData();
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        if (y >= Chunk.SIZE_Y || y < 0) {
            // Happens if you are moving around above the world
            return BlockManager.getAir();
        }

        Vector3i chunkPos = TeraMath.calcChunkPos(x, y, z);
        Chunk chunk = chunkProvider.getChunk(chunkPos);
        if (chunk != null) {
            Vector3i blockPos = TeraMath.calcBlockPos(x, y, z);
            return chunk.getBlock(blockPos);
        }
        logger.warn("Attempted to access unavailable chunk via block at {}, {}, {}", x, y, z);
        return BlockManager.getAir();
    }

    @Override
    public byte getLight(int x, int y, int z) {
        if (y >= Chunk.SIZE_Y || y < 0) {
            logger.warn("Accessed light value outside of the height range");
            return 0;
        }

        Vector3i chunkPos = TeraMath.calcChunkPos(x, y, z);
        Chunk chunk = chunkProvider.getChunk(chunkPos);
        if (chunk != null) {
            Vector3i blockPos = TeraMath.calcBlockPos(x, y, z);
            return chunk.getLight(blockPos);
        }
        logger.warn("Attempted to access unavailable chunk via light at {}, {}, {}", x, y, z);
        return 0;
    }

    @Override
    public byte getSunlight(int x, int y, int z) {
        if (y >= Chunk.SIZE_Y || y < 0) {
            logger.warn("Accessed sunlight value outside of the height range");
            return 0;
        }

        Vector3i chunkPos = TeraMath.calcChunkPos(x, y, z);
        Chunk chunk = chunkProvider.getChunk(chunkPos);
        if (chunk != null) {
            Vector3i blockPos = TeraMath.calcBlockPos(x, y, z);
            return chunk.getSunlight(blockPos);
        }
        logger.warn("Attempted to access unavailable chunk via sunlight at {}, {}, {}", x, y, z);
        return 0;
    }

    @Override
    public byte getTotalLight(int x, int y, int z) {
        if (y >= Chunk.SIZE_Y || y < 0) {
            logger.warn("Accessed total light value outside of the height range");
            return 0;
        }

        Vector3i chunkPos = TeraMath.calcChunkPos(x, y, z);
        Chunk chunk = chunkProvider.getChunk(chunkPos);
        if (chunk != null) {
            Vector3i blockPos = TeraMath.calcBlockPos(x, y, z);
            return (byte) Math.max(chunk.getSunlight(blockPos), chunk.getLight(blockPos));
        }
        logger.warn("Attempted to access unavailable chunk via total light at {}, {}, {}", x, y, z);
        return 0;
    }

    @Override
    public void dispose() {
        chunkProvider.dispose();

    }

    @Override
    public WorldTime getTime() {
        return worldTime;
    }

    @Override
    public float getFog(float x, float y, float z) {
        return (float) TeraMath.clamp(TeraMath.fastAbs(fogNoise.fBm(getTime().getDays() * 0.1f, 0.01f, 0.01f) * 2.0f)) * biomeProvider.getFog(x, y, z);
    }
}
