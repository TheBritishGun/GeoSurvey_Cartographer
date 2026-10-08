package dev.openmap.client;

import dev.openmap.share.WorldAsk;
import dev.sandpaper.core.Tick;
import dev.openmap.share.WorldPrint;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

// Reads bedrock from loaded chunks only.
public final class WorldHandshake {

    private WorldHandshake() {
    }

    public static final class Progress {

        private static final byte[] EMPTY_BITS = new byte[0];

        private final WorldPrint.Region region;
        private final long[] prints;
        private final int wide;
        private final int fromChunkX;
        private final int fromChunkZ;

        private int nextChunk;
        private int held;
        private int read;
        private final int columns;
        private int obfuscated;

        private int columnInChunk;
        private byte[] bits = EMPTY_BITS;
        private BiomeBytes biomes;
        private int chunkObfuscated;
        private final int columnBudget;
        private int bottom;
        private boolean bottomKnown;
        private BlockPos.MutableBlockPos at;

        Progress(WorldPrint.Region region) {
            this(region, Integer.MAX_VALUE);
        }

        Progress(WorldPrint.Region region, int columnBudget) {
            this.region = region;
            this.columnBudget = columnBudget;
            this.fromChunkX = WorldPrint.chunkOf(region.minX());
            this.fromChunkZ = WorldPrint.chunkOf(region.minZ());
            this.wide = WorldPrint.chunkOf(region.maxX()) - fromChunkX + 1;
            int tall = WorldPrint.chunkOf(region.maxZ()) - fromChunkZ + 1;
            this.prints = new long[wide * tall];
            this.columns = region.columns();
        }

        public WorldPrint.Region region() {
            return region;
        }

        public boolean done() {
            return nextChunk >= prints.length;
        }

        // Valid when done() is true.
        public WorldPrint.Reading reading() {
            return new WorldPrint.Reading(
                    WorldPrint.signature(prints, held, region.minX(), region.minZ()),
                    read, columns, obfuscated);
        }
    }

    public static Progress begin(WorldPrint.Region region) {
        return region == null || !WorldAsk.readable(region)
                || !WorldAsk.probesBedrock(region.dimension()) ? null : new Progress(region);
    }

    // Separates biome keys in a chunk's digest.
    private static final char SEPARATOR = (char) 10;

    private static final int BIOME_REACH = 2;

    private static final int CHUNK_BLOCK_SHIFT = 4;

    private static final int CHUNK_BLOCK_MASK = 15;

    private static final int CHUNK_LAST_OFFSET = 15;

    private static final int ESTIMATED_BIOME_KEY_BYTES = 20;

    private static final int FLOOR_BIT = 0x10;

    private static final class BiomeBytes {

        private static final int FIRST_NON_ASCII_CHAR = 0x80;

        private static final int GROWTH_FACTOR = 2;

        private byte[] bytes;
        private int length;

        private BiomeBytes(int capacity) {
            bytes = new byte[Math.max(1, capacity)];
        }

        private void reset() {
            length = 0;
        }

        private BiomeBytes append(String text) {
            int len = text.length();
            grow(length + len);
            for (int i = 0; i < len; i++) {
                char c = text.charAt(i);
                if (c >= FIRST_NON_ASCII_CHAR) {
                    byte[] rest = text.substring(i).getBytes(StandardCharsets.UTF_8);
                    grow(length + rest.length);
                    System.arraycopy(rest, 0, bytes, length, rest.length);
                    length += rest.length;
                    break;
                }
                bytes[length++] = (byte) c;
            }
            return this;
        }

        private BiomeBytes append(char value) {
            grow(length + 1);
            bytes[length++] = (byte) value;
            return this;
        }

        private void grow(int required) {
            if (required <= bytes.length) {
                return;
            }
            int capacity = bytes.length;
            while (capacity < required && capacity <= Integer.MAX_VALUE / GROWTH_FACTOR) {
                capacity *= GROWTH_FACTOR;
            }
            bytes = Arrays.copyOf(bytes, Math.max(capacity, required));
        }
    }

    // Tick thread only.
    // Returns true when done.
    public static boolean step(Level level, Progress progress) {
        if (progress == null || progress.done()) {
            return true;
        }
        if (level == null) {
            return false;
        }
        if (!progress.bottomKnown) {
            progress.bottom = level.getMinY();
            progress.bottomKnown = true;
        }
        int bottom = progress.bottom;
        WorldPrint.Region region = progress.region;
        boolean withBiome = region.withBiome();
        boolean floored = WorldAsk.probesBedrock(region.dimension());

        int n = progress.nextChunk;
        int row = WorldPrint.walkZ(n, progress.wide);
        int cz = progress.fromChunkZ + row;
        int cx = progress.fromChunkX + (n - row * progress.wide);

        int fromX = Math.max(region.minX(), cx << CHUNK_BLOCK_SHIFT);
        int toX = Math.min(region.maxX(), (cx << CHUNK_BLOCK_SHIFT) + CHUNK_LAST_OFFSET);
        int fromZ = Math.max(region.minZ(), cz << CHUNK_BLOCK_SHIFT);
        int toZ = Math.min(region.maxZ(), (cz << CHUNK_BLOCK_SHIFT) + CHUNK_LAST_OFFSET);
        int here = (toX - fromX + 1) * (toZ - fromZ + 1);
        int width = toX - fromX + 1;

        // false: never load.
        ChunkAccess chunk =
                level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {

            progress.read -= progress.columnInChunk;
            progress.obfuscated -= progress.chunkObfuscated;
            progress.columnInChunk = 0;
            progress.chunkObfuscated = 0;
            progress.nextChunk++;
        } else if (progress.columnInChunk == 0 && withBiome
                && !biomeSampleHeld(level.getChunkSource(), cx, cz, fromX, toX,
                        fromZ, toZ)) {
            progress.nextChunk++;
        } else {
            BlockPos.MutableBlockPos at = progress.at;
            if (at == null) {
                at = new BlockPos.MutableBlockPos();
                progress.at = at;
            }

            if (progress.columnInChunk == 0) {
                if (progress.bits.length != here) {
                    progress.bits = new byte[here];
                }

                if (withBiome) {
                    if (progress.biomes == null) {
                        progress.biomes = new BiomeBytes(here * ESTIMATED_BIOME_KEY_BYTES);
                    } else {
                        progress.biomes.reset();
                    }
                }
            }

            int sectionIndex = chunk.getSectionIndex(bottom);
            LevelChunkSection section = chunk.getSection(sectionIndex);
            boolean oneSection = section != null
                    && ((bottom ^ (bottom + WorldPrint.VARIABLE_LAYERS)) >> CHUNK_BLOCK_SHIFT) == 0;
            int ly = bottom & CHUNK_BLOCK_MASK;

            int budget = progress.columnBudget;
            int done = 0;
            Holder<Biome> lastBiome = null;
            String lastBiomeName = null;
            while (progress.columnInChunk < here && done < budget) {
                int col = progress.columnInChunk;
                int x = fromX + col % width;
                int z = fromZ + col / width;

                boolean floor;
                int packed = 0;
                if (oneSection) {
                    floor = section.getBlockState(x & CHUNK_BLOCK_MASK, ly, z & CHUNK_BLOCK_MASK).is(Blocks.BEDROCK);
                    for (int layer = 1; layer <= WorldPrint.VARIABLE_LAYERS; layer++) {
                        if (section.getBlockState(x & CHUNK_BLOCK_MASK, (ly + layer) & CHUNK_BLOCK_MASK,
                                z & CHUNK_BLOCK_MASK).is(Blocks.BEDROCK)) {
                            packed |= 1 << (layer - 1);
                        }
                    }
                } else {

                    at.set(x, bottom, z);
                    floor = chunk.getBlockState(at).is(Blocks.BEDROCK);
                    for (int layer = 1; layer <= WorldPrint.VARIABLE_LAYERS; layer++) {
                        at.setY(bottom + layer);
                        if (chunk.getBlockState(at).is(Blocks.BEDROCK)) {
                            packed |= 1 << (layer - 1);
                        }
                    }
                }
                progress.bits[col] = (byte) (packed | (floor ? FLOOR_BIT : 0x00));
                if (progress.biomes != null) {

                    at.set(x, bottom, z);
                    Holder<Biome> biome = level.getBiome(at);
                    if (biome != lastBiome) {
                        var key = biome.unwrapKey();
                        lastBiomeName = key.isPresent()
                                ? key.get().identifier().toString() : "?";
                        lastBiome = biome;
                    }
                    progress.biomes.append(lastBiomeName).append(SEPARATOR);
                }
                progress.columnInChunk++;
                progress.read++;
                if (!floor && floored) {

                    progress.chunkObfuscated++;
                    progress.obfuscated++;
                }
                done++;
            }

            if (progress.columnInChunk >= here) {
                progress.prints[progress.held++] = WorldPrint.digest(
                        progress.bits, progress.biomes == null ? null : progress.biomes.bytes,
                        progress.biomes == null ? 0 : progress.biomes.length, cx, cz);
                progress.columnInChunk = 0;
                progress.chunkObfuscated = 0;
                progress.nextChunk++;
            }
        }

        return progress.done();
    }

    private static boolean biomeSampleHeld(ChunkSource source,
            int centreX, int centreZ, int fromX, int toX, int fromZ, int toZ) {
        int cxFrom = WorldPrint.chunkOf(fromX - BIOME_REACH);
        int cxTo = WorldPrint.chunkOf(toX + BIOME_REACH);
        int czFrom = WorldPrint.chunkOf(fromZ - BIOME_REACH);
        int czTo = WorldPrint.chunkOf(toZ + BIOME_REACH);
        boolean held = true;
        for (int cx = cxFrom; held && cx <= cxTo; cx++) {
            for (int cz = czFrom; held && cz <= czTo; cz++) {
                if (cx == centreX && cz == centreZ) {
                    continue;
                }
                if (source.getChunk(cx, cz, ChunkStatus.FULL, false) == null) {
                    held = false;
                }
            }
        }
        return held;
    }
}
