package dev.openmap.map;

public interface ChunkSource {

    int CHUNK_BLOCKS = 16;

    int CHUNK_SHIFT = Integer.numberOfTrailingZeros(CHUNK_BLOCKS);

    int CHUNK_MASK = CHUNK_BLOCKS - 1;

    ChunkSample get(int chunkX, int chunkZ);

    default LandCover coverAt(int blockX, int blockZ) {
        ChunkSample sample = get(blockX >> CHUNK_SHIFT, blockZ >> CHUNK_SHIFT);
        return sample == null ? LandCover.UNKNOWN : sample.cover(blockX & CHUNK_MASK,
                blockZ & CHUNK_MASK);
    }
}
