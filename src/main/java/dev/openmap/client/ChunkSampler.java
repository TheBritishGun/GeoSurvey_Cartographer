package dev.openmap.client;

import dev.openmap.map.ChunkSample;
import dev.openmap.map.LandCover;
import dev.openmap.map.StructureProbe;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.concurrent.atomic.AtomicLong;

public final class ChunkSampler {

    private static final int MAX_VEGETATION_DEPTH = 32;

    private static final int CHUNK_BLOCK_SHIFT = 4;

    private static final int SECTION_COORDINATE_MASK = 15;

    private static final AtomicLong TAG_GENERATION = new AtomicLong();

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    static {
        ClientConfigurationConnectionEvents.COMPLETE
                .register((handler, client) -> tagsChanged());
    }

    public static void tagsChanged() {
        TAG_GENERATION.incrementAndGet();
    }

    private ChunkSampler() {
    }

    private static Scratch scratch() {
        Scratch scratch = SCRATCH.get();
        scratch.gets++;
        return scratch;
    }

    static Scratch newScratch() {
        return new Scratch();
    }

    static int scratchGetCount() {
        return SCRATCH.get().gets;
    }

    static int sectionResolutionCount() {
        return SCRATCH.get().sectionResolutions;
    }

    static int probeKindCallCount() {
        return SCRATCH.get().probeKindCalls;
    }

    static int groundLimitCount() {
        return SCRATCH.get().groundLimits;
    }

    static int treePartCallCount() {
        return SCRATCH.get().treePartCalls;
    }

    @FunctionalInterface
    interface Surface {
        int topBlockY(int localX, int localZ);
    }

    // At most limit columns starting at column from; returns the column to resume from.
    static int sample(ChunkSample out, BlockGetter blocks, int minY,
                      Surface surface, Scratch scratch, int from, int limit) {
        BlockPos.MutableBlockPos cursor = scratch.cursor;
        int baseX = out.chunkX << CHUNK_BLOCK_SHIFT;
        int end = Math.min(ChunkSample.COLUMNS, from + limit);
        int lz = from / ChunkSample.SIZE;
        int lx = from - lz * ChunkSample.SIZE;
        int worldZ = (out.chunkZ << CHUNK_BLOCK_SHIFT) + lz;
        for (int at = from; at < end; at++) {
            resetTagMemo(scratch);
            int surfaceY = Math.max(minY, surface.topBlockY(lx, lz));
            cursor.set(baseX + lx, surfaceY, worldZ);
            BlockState state = blockState(blocks, cursor, scratch);
            LandCover cover = LandCoverClassifier.classify(state, blocks, cursor);
            final int prefixLength;
            if (cover == LandCover.FOREST && isAmbiguousWood(state, scratch)) {
                int depth = Math.min(StructureProbe.DEPTH, surfaceY - minY + 1);
                if (looksBuilt(blocks, cursor, surfaceY, depth, state, scratch)) {
                    cover = LandCover.URBAN;
                }
                prefixLength = depth > 1 ? depth : 0;
            } else {
                prefixLength = 0;
            }
            out.set(lx, lz, groundHeight(blocks, cursor, surfaceY, minY, state, scratch,
                    prefixLength), cover);
            if (++lx == ChunkSample.SIZE) {
                lx = 0;
                lz++;
                worldZ++;
            }
        }
        return end;
    }

    static ChunkSample sample(int cx, int cz, BlockGetter blocks, int minY,
                              Surface surface) {
        return sample(cx, cz, blocks, minY, surface, scratch());
    }

    static ChunkSample sample(int cx, int cz, BlockGetter blocks, int minY,
                              Surface surface, Scratch scratch) {
        ChunkSample out = ChunkSample.forFullWriter(cx, cz);
        sample(out, blocks, minY, surface, scratch, 0, ChunkSample.COLUMNS);
        return out;
    }


    private static int groundHeight(BlockGetter blocks, BlockPos.MutableBlockPos cursor,
                                    int surfaceY, int minY, BlockState surfaceState,
                                    Scratch scratch, int prefixLength) {
        if (!standsOnGround(surfaceState, scratch)) {
            return surfaceY;
        }
        int limit = Math.max(minY, surfaceY - MAX_VEGETATION_DEPTH);
        scratch.groundLimits++;
        int y = surfaceY;
        int ground = ChunkSample.NO_HEIGHT;
        while (y > limit) {
            y--;
            cursor.setY(y);
            int offset = surfaceY - y;
            BlockState state = offset < prefixLength ? scratch.columnStates[offset]
                    : blockState(blocks, cursor, scratch);
            if (!standsOnGround(state, scratch)) {
                ground = y;
                break;
            }
        }
        return ground;
    }

    private static boolean looksBuilt(BlockGetter blocks, BlockPos.MutableBlockPos cursor,
                                      int surfaceY, int depth, BlockState surfaceState,
                                      Scratch scratch) {
        if (depth <= 1) {
            return false;
        }
        StructureProbe.Kind[] probeColumn = scratch.probeColumn;
        BlockState[] columnStates = scratch.columnStates;
        StructureProbe.Kind[] column =
                depth == StructureProbe.DEPTH ? probeColumn : clippedProbeColumn(scratch, depth);
        columnStates[0] = surfaceState;
        column[0] = probeKind(surfaceState, scratch);
        for (int i = 1; i < depth; i++) {
            cursor.setY(surfaceY - i);
            BlockState fetched = blockState(blocks, cursor, scratch);
            columnStates[i] = fetched;
            column[i] = probeKind(fetched, scratch);
        }
        return StructureProbe.isStructure(column);
    }

    private static StructureProbe.Kind[] clippedProbeColumn(Scratch scratch, int depth) {
        StructureProbe.Kind[] column = scratch.clippedProbeColumns[depth];
        if (column == null) {
            column = new StructureProbe.Kind[depth];
            scratch.clippedProbeColumns[depth] = column;
        }
        return column;
    }

    private static BlockState blockState(BlockGetter blocks, BlockPos.MutableBlockPos cursor,
                                         Scratch scratch) {
        if (blocks instanceof LevelChunk levelChunk && levelChunk.getLevel().isDebug()) {
            return blocks.getBlockState(cursor);
        }
        if (!(blocks instanceof ChunkAccess chunk)) {
            return blocks.getBlockState(cursor);
        }
        int y = cursor.getY();
        if (chunk.isOutsideBuildHeight(y)) {
            return Blocks.AIR.defaultBlockState();
        }
        int sectionIndex = chunk.getSectionIndex(y);
        if (scratch.chunk != chunk || scratch.sectionIndex != sectionIndex) {
            scratch.chunk = chunk;
            scratch.sectionIndex = sectionIndex;
            scratch.section = chunk.getSection(sectionIndex);
            scratch.sectionResolutions++;
        }
        LevelChunkSection section = scratch.section;
        return section == null || section.hasOnlyAir() ? Blocks.AIR.defaultBlockState()
                : section.getBlockState(cursor.getX() & SECTION_COORDINATE_MASK,
                y & SECTION_COORDINATE_MASK, cursor.getZ() & SECTION_COORDINATE_MASK);
    }

    private static boolean isAmbiguousWood(BlockState state, Scratch scratch) {
        prepareMemo(state, scratch);
        if (!scratch.memoAmbiguousKnown) {
            scratch.memoAmbiguousWood = LandCoverClassifier.isAmbiguousWood(state);
            scratch.memoAmbiguousKnown = true;
        }
        return scratch.memoAmbiguousWood;
    }

    private static StructureProbe.Kind probeKind(BlockState state, Scratch scratch) {
        prepareMemo(state, scratch);
        if (scratch.memoProbeKind == null) {
            scratch.memoProbeKind = LandCoverClassifier.probeKind(state);
            scratch.probeKindCalls++;
        }
        return scratch.memoProbeKind;
    }

    private static boolean standsOnGround(BlockState state, Scratch scratch) {
        prepareMemo(state, scratch);
        boolean passable = !state.blocksMotion() && state.getFluidState().isEmpty();
        if (!passable && !scratch.memoTreePartKnown) {
            scratch.memoTreePart = LandCoverClassifier.isTreePart(state);
            scratch.treePartCalls++;
            scratch.memoTreePartKnown = true;
        }
        return passable || scratch.memoTreePart;
    }

    private static void resetTagMemo(Scratch scratch) {
        long generation = TAG_GENERATION.get();
        if (scratch.tagGeneration != generation) {
            scratch.tagGeneration = generation;
            scratch.memoState = null;
            scratch.memoProbeKind = null;
            scratch.memoAmbiguousKnown = false;
            scratch.memoTreePartKnown = false;
        }
    }

    private static void prepareMemo(BlockState state, Scratch scratch) {
        if (scratch.memoState != state) {
            scratch.memoState = state;
            scratch.memoProbeKind = null;
            scratch.memoAmbiguousKnown = false;
            scratch.memoTreePartKnown = false;
        }
    }

    static final class Scratch {

        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final StructureProbe.Kind[] probeColumn =
                new StructureProbe.Kind[StructureProbe.DEPTH];
        private final StructureProbe.Kind[][] clippedProbeColumns =
                new StructureProbe.Kind[StructureProbe.DEPTH][];
        private final BlockState[] columnStates = new BlockState[StructureProbe.DEPTH];
        private ChunkAccess chunk;
        private LevelChunkSection section;
        private int sectionIndex = Integer.MIN_VALUE;
        private int sectionResolutions;
        private long tagGeneration = Long.MIN_VALUE;
        private BlockState memoState;
        private StructureProbe.Kind memoProbeKind;
        private boolean memoAmbiguousKnown;
        private boolean memoAmbiguousWood;
        private boolean memoTreePartKnown;
        private boolean memoTreePart;
        private int probeKindCalls;
        private int groundLimits;
        private int treePartCalls;
        private int gets;
    }
}
