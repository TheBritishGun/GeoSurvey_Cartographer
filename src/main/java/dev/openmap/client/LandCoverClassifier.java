package dev.openmap.client;

import dev.openmap.map.LandCover;
import dev.openmap.map.StructureProbe;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;

public final class LandCoverClassifier {

    private LandCoverClassifier() {
    }

    public static LandCover classify(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.isAir()) {
            return LandCover.UNKNOWN;
        }
        if (isWater(state) && !isWalkableWaterloggedTopSlab(state, level, pos)) {
            return LandCover.WATER;
        }
        int early = earlyVerdict(state);
        int verdictOrdinal = early >>> 1;
        LandCover cover;
        if (verdictOrdinal != 0) {
            cover = LandCover.byOrdinal(verdictOrdinal - 1);
        } else {
            cover = colourOf(state, level, pos, (early & 1) != 0);
        }
        return cover;
    }

    private static LandCover colourOf(BlockState state, BlockGetter level, BlockPos pos,
                                      boolean identitySnow) {
        MapColor material = state.getMapColor(level, pos);
        if (material == null || material == MapColor.NONE) {
            return LandCover.URBAN;
        }
        LandCover byColour = fromMaterial(material);
        if (byColour == LandCover.SNOW && !identitySnow) {
            byColour = LandCover.URBAN;
        }
        return byColour == LandCover.URBAN && !state.blocksMotion()
                ? LandCover.SCRUB : byColour;
    }

    private static int earlyVerdict(BlockState state) {
        EARLY_CACHE_GET_COUNT.incrementAndGet();
        Block block = state.getBlock();
        Integer known = EARLY.get(block);
        int packed;
        if (known != null) {
            packed = known;
        } else {
            int identity = identityFlags(block);
            LandCover verdict;
            if ((identity & IDENTITY_PORTAL) != 0) {
                verdict = LandCover.PORTAL;
            } else if ((identity & IDENTITY_DIRT_PATH) != 0) {
                verdict = LandCover.PATH;
            } else if ((identity & IDENTITY_BEDROCK) != 0) {
                verdict = LandCover.BEDROCK;
            } else if ((identity & IDENTITY_END_STONE) != 0) {
                verdict = LandCover.END_STONE;
            } else if ((identity & IDENTITY_CRIMSON_FOREST) != 0) {
                verdict = LandCover.CRIMSON;
            } else if ((identity & IDENTITY_SULFUR_CAVE) != 0) {
                verdict = LandCover.SULFUR;
            } else if ((identity & IDENTITY_MYCELIUM) != 0) {
                verdict = LandCover.MYCELIUM;
            } else if ((identity & IDENTITY_MUD) != 0) {
                verdict = LandCover.OPEN;
            } else if ((identity & IDENTITY_WATER_PLANT) != 0) {
                verdict = LandCover.WATER;
            } else if ((identity & IDENTITY_NETHER_VINE) != 0) {
                verdict = LandCover.VOLCANIC;
            } else if ((identity & IDENTITY_GROUND_COVERING_PLANT) != 0) {
                verdict = LandCover.SCRUB;
            } else {
                verdict = verdictFromNames(identity);
            }
            packed = ((verdict == null ? 0 : verdict.ordinal() + 1) << 1)
                    | ((identity & IDENTITY_SNOW) != 0 ? 1 : 0);
            if (identityIsCached(block)) {
                EARLY.put(block, packed);
            }
        }
        return packed;
    }

    private static LandCover verdictFromNames(int identity) {
        byte names = nameBits(identity);
        LandCover verdict;
        if ((identity & (IDENTITY_TAG_LEAVES | IDENTITY_TAG_LOGS)) != 0
                || looksLikeFoliage(names)
                || (identity & (IDENTITY_TREE_VINE | IDENTITY_FUNGAL_CANOPY)) != 0) {
            verdict = LandCover.FOREST;
        } else if ((identity & (IDENTITY_TAG_PLANKS | IDENTITY_TAG_WOODEN_STAIRS
                | IDENTITY_TAG_WOODEN_SLABS)) != 0) {
            verdict = LandCover.URBAN;
        } else if ((identity & IDENTITY_TAG_TERRACOTTA) != 0
                || looksLikeTerracotta(names)) {
            verdict = LandCover.TERRACOTTA;
        } else if ((identity & IDENTITY_RED_SAND) != 0) {
            verdict = LandCover.SAND;
        } else if (looksLikeGraniteOrDiorite(names)) {
            verdict = LandCover.ROCK;
        } else if (builtUnderNetherColour(names)) {
            verdict = LandCover.URBAN;
        } else {
            verdict = null;
        }
        return verdict;
    }

    static byte nameBits(int identity) {
        return (byte) ((identity & NAME_MASK) >>> NAME_SHIFT);
    }

    private static final java.util.Map<Block, Integer> EARLY =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isWater(BlockState state) {
        var fluid = state.getFluidState().getType();
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    private static boolean isWalkableWaterloggedTopSlab(BlockState state, BlockGetter level,
                                                        BlockPos pos) {
        return state.hasProperty(SlabBlock.TYPE)
                && state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM
                && (level == null || pos == null || !isWater(level.getBlockState(pos.above())));
    }

    public static LandCover fromMaterial(MapColor material) {
        if (material == null || material == MapColor.NONE) {
            return LandCover.UNKNOWN;
        }
        return MATERIAL_COVER.getOrDefault(material, LandCover.URBAN);
    }

    public static boolean isAmbiguousWood(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || looksLikeFoliage(state);
    }

    private static boolean isAmbiguousWood(BlockState state, byte names, boolean leaves) {
        return state.is(BlockTags.LOGS) || leaves
                || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || looksLikeFoliage(names);
    }

    public static StructureProbe.Kind probeKind(BlockState state) {
        StructureProbe.Kind kind;
        if (state.isAir()) {
            kind = StructureProbe.Kind.AIR;
        } else {
            int identity = identityFlags(state.getBlock());
            if (isFurniture(state, identity)) {
                kind = StructureProbe.Kind.FURNITURE;
            } else {
                byte names = nameBits(identity);
                boolean leaves = state.is(BlockTags.LEAVES);
                if (isCanopy(identity, names, leaves)) {
                    kind = StructureProbe.Kind.CANOPY;
                } else if (isAmbiguousWood(state, names, leaves)) {
                    kind = StructureProbe.Kind.WOOD;
                } else if (isNaturalGround(state, identity)) {
                    kind = StructureProbe.Kind.NATURAL;
                } else if (!state.getFluidState().isEmpty()) {
                    kind = StructureProbe.Kind.OTHER;
                } else {
                    kind = state.blocksMotion()
                            ? StructureProbe.Kind.WORKED : StructureProbe.Kind.OTHER;
                }
            }
        }
        return kind;
    }

    private static boolean isFurniture(BlockState state, int identity) {
        return state.is(BlockTags.BEDS)
                || state.is(BlockTags.DOORS)
                || state.is(BlockTags.ANVIL)
                || (identity & IDENTITY_FURNITURE) != 0;
    }

    private static boolean isFurnitureBlock(Block block) {
        return block == Blocks.CRAFTING_TABLE
                || block == Blocks.CHEST
                || block == Blocks.TRAPPED_CHEST
                || block == Blocks.ENDER_CHEST
                || block == Blocks.BARREL
                || block == Blocks.FURNACE
                || block == Blocks.BLAST_FURNACE
                || block == Blocks.SMOKER
                || block == Blocks.BOOKSHELF
                || block == Blocks.ENCHANTING_TABLE
                || block == Blocks.BREWING_STAND
                || block == Blocks.CARTOGRAPHY_TABLE
                || block == Blocks.SMITHING_TABLE
                || block == Blocks.FLETCHING_TABLE
                || block == Blocks.LOOM
                || block == Blocks.GRINDSTONE
                || block == Blocks.STONECUTTER
                || block == Blocks.LECTERN
                || block == Blocks.COMPOSTER;
    }

    private static boolean isNaturalGround(BlockState state, int identity) {
        boolean natural;
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.TERRACOTTA)) {
            natural = true;
        } else if (isOre(state, identity)) {
            natural = true;
        } else {
            if ((identity & (IDENTITY_CAVE_FURNITURE | IDENTITY_BEDROCK
                    | IDENTITY_SULFUR_CAVE | IDENTITY_CRIMSON_FOREST
                    | IDENTITY_WARPED_NYLIUM | IDENTITY_RED_SAND | IDENTITY_MYCELIUM
                    | IDENTITY_SNOW | IDENTITY_UNTAGGED_NATURAL)) != 0) {
                natural = true;
            } else {
                MapColor material = state.getMapColor(null, null);
                natural = material == MapColor.GRASS || material == MapColor.PODZOL;
            }
        }
        return natural;
    }

    static boolean isTreeVine(Block block) {
        return block == Blocks.VINE || block == Blocks.MANGROVE_ROOTS;
    }

    static boolean isNetherVine(Block block) {
        return block == Blocks.WEEPING_VINES || block == Blocks.WEEPING_VINES_PLANT
                || block == Blocks.TWISTING_VINES
                || block == Blocks.TWISTING_VINES_PLANT;
    }

    static boolean isGroundCoveringPlant(Block block) {
        return block == Blocks.GLOW_LICHEN || block == Blocks.CAVE_VINES
                || block == Blocks.CAVE_VINES_PLANT;
    }

    static boolean isFungalCanopy(Block block) {
        return block == Blocks.BROWN_MUSHROOM_BLOCK
                || block == Blocks.RED_MUSHROOM_BLOCK
                || block == Blocks.MUSHROOM_STEM
                || block == Blocks.NETHER_WART_BLOCK
                || block == Blocks.WARPED_WART_BLOCK
                || block == Blocks.SHROOMLIGHT;
    }

    // End stone and the brick family cut from it.
    static boolean isEndStone(Block block) {
        return block == Blocks.END_STONE || block == Blocks.END_STONE_BRICKS
                || block == Blocks.END_STONE_BRICK_SLAB
                || block == Blocks.END_STONE_BRICK_STAIRS
                || block == Blocks.END_STONE_BRICK_WALL;
    }

    // The crimson forest floor and the trunks standing on it.
    static boolean isCrimsonForest(Block block) {
        return block == Blocks.CRIMSON_NYLIUM
                || block == Blocks.CRIMSON_STEM
                || block == Blocks.STRIPPED_CRIMSON_STEM
                || block == Blocks.CRIMSON_HYPHAE
                || block == Blocks.STRIPPED_CRIMSON_HYPHAE
                || block == Blocks.CRIMSON_ROOTS
                || block == Blocks.CRIMSON_FUNGUS;
    }

    // What a sulfur cave is cut out of: sulfur, potent sulfur, the spikes, and cinnabar.
    static boolean isSulfurCave(Block block) {
        return block == Blocks.SULFUR || block == Blocks.POTENT_SULFUR
                || block == Blocks.SULFUR_SPIKE || block == Blocks.CINNABAR;
    }

    static boolean looksLikeTerracotta(byte names) {
        return (names & NAME_TERRACOTTA) != 0;
    }

    static boolean looksLikeGraniteOrDiorite(byte names) {
        return (names & NAME_GRANITE_DIORITE) != 0;
    }

    static boolean builtUnderNetherColour(byte names) {
        return (names & NAME_BUILT_UNDER_NETHER_COLOUR) != 0;
    }

    // Cover that grew rather than being placed.
    static boolean isTreePart(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                || looksLikeFoliage(state)
                || (identityFlags(state.getBlock()) & IDENTITY_FUNGAL_CANOPY) != 0;
    }

    private static boolean isCanopy(int identity, byte names, boolean leaves) {
        return leaves || looksLikeLeaves(names)
                || (identity & IDENTITY_FUNGAL_CANOPY) != 0;
    }

    // Backs up the tag before the server has synced it.
    private static boolean looksLikeLeaves(byte names) {
        return (names & NAME_LEAVES) != 0;
    }

    private static boolean looksLikeFoliage(BlockState state) {
        return (nameFlags(state) & NAME_FOLIAGE) != 0;
    }

    private static boolean looksLikeFoliage(byte names) {
        return (names & NAME_FOLIAGE) != 0;
    }

    // One bit per name test, so one map entry answers all of them.
    private static final byte NAME_LEAVES = 1;

    private static final byte NAME_FOLIAGE = 2;

    private static final byte NAME_TERRACOTTA = 4;

    private static final byte NAME_GRANITE_DIORITE = 8;

    private static final byte NAME_BUILT_UNDER_NETHER_COLOUR = 32;

    private static final int NAME_SHIFT = 25;

    private static final int NAME_MASK = (NAME_LEAVES | NAME_FOLIAGE | NAME_TERRACOTTA
            | NAME_GRANITE_DIORITE | NAME_BUILT_UNDER_NETHER_COLOUR) << NAME_SHIFT;

    // The name tests above, worked out once per block and then kept.
    private static byte nameFlags(BlockState state) {
        NAME_FLAGS_CALL_COUNT.incrementAndGet();
        return nameBits(identityFlags(state.getBlock()));
    }

    private static final int IDENTITY_BEDROCK = 1;

    private static final int IDENTITY_MYCELIUM = 1 << 1;

    private static final int IDENTITY_WATER_PLANT = 1 << 2;

    private static final int IDENTITY_PORTAL = 1 << 3;

    private static final int IDENTITY_DIRT_PATH = 1 << 4;

    private static final int IDENTITY_END_STONE = 1 << 5;

    private static final int IDENTITY_CRIMSON_FOREST = 1 << 6;

    private static final int IDENTITY_SULFUR_CAVE = 1 << 7;

    private static final int IDENTITY_NETHER_VINE = 1 << 8;

    private static final int IDENTITY_GROUND_COVERING_PLANT = 1 << 9;

    private static final int IDENTITY_TREE_VINE = 1 << 10;

    private static final int IDENTITY_FUNGAL_CANOPY = 1 << 11;

    private static final int IDENTITY_RED_SAND = 1 << 12;

    private static final int IDENTITY_CAVE_FURNITURE = 1 << 13;

    private static final int IDENTITY_ORE = 1 << 14;

    private static final int IDENTITY_FURNITURE = 1 << 15;

    private static final int IDENTITY_WARPED_NYLIUM = 1 << 16;

    private static final int IDENTITY_SNOW = 1 << 17;

    private static final int IDENTITY_MUD = 1 << 18;

    private static final int IDENTITY_TAG_LEAVES = 1 << 19;

    private static final int IDENTITY_TAG_LOGS = 1 << 20;

    private static final int IDENTITY_TAG_PLANKS = 1 << 21;

    private static final int IDENTITY_TAG_WOODEN_STAIRS = 1 << 22;

    private static final int IDENTITY_TAG_WOODEN_SLABS = 1 << 23;

    private static final int IDENTITY_TAG_TERRACOTTA = 1 << 24;

    // Outside the name-flag bit range (NAME_MASK).
    private static final int IDENTITY_UNTAGGED_NATURAL = 1 << 29;

    private static final AtomicInteger TAG_TEST_COUNT = new AtomicInteger();

    private static final AtomicInteger NAME_FLAGS_CALL_COUNT = new AtomicInteger();

    private static final AtomicInteger IDENTITY_COMPUTE_COUNT = new AtomicInteger();

    private static final AtomicInteger IDENTITY_GET_COUNT = new AtomicInteger();

    private static final AtomicInteger EARLY_CACHE_GET_COUNT = new AtomicInteger();

    static int tagTests() {
        return TAG_TEST_COUNT.get();
    }

    static int nameFlagsCalls() {
        return NAME_FLAGS_CALL_COUNT.get();
    }

    static int identityComputes() {
        return IDENTITY_COMPUTE_COUNT.get();
    }

    static int identityGets() {
        return IDENTITY_GET_COUNT.get();
    }

    static int earlyCacheGets() {
        return EARLY_CACHE_GET_COUNT.get();
    }

    static int cachedFlagEntryCount() {
        AtomicIntegerArray table = IDENTITY;
        int cached = 0;
        int n = table.length();
        for (int i = 0; i < n; i++) {
            if (table.get(i) != IDENTITY_UNSET) {
                cached++;
            }
        }
        return cached;
    }

    static int identityFlagsOf(Block block) {
        return identityFlags(block);
    }

    static int legacyTableSize() {
        return LEGACY.size();
    }

    private static boolean isInTag(BlockState state, TagKey<Block> tag) {
        TAG_TEST_COUNT.incrementAndGet();
        return state.is(tag);
    }

    private static int identityFlags(Block block) {
        IDENTITY_GET_COUNT.incrementAndGet();
        int id = identitySlot(block);
        int known = readIdentityFlags(id);
        int flags;
        if (known != IDENTITY_UNSET) {
            flags = known;
        } else {
            IDENTITY_COMPUTE_COUNT.incrementAndGet();
            BlockState state = block.defaultBlockState();
            flags = verdictBlockFlags(block) | probeBlockFlags(block) | tagFlags(state);
            var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            if (key != null) {
                flags |= pathFlags(key.getPath());
                storeIdentityFlags(id, flags);
            }
        }
        return flags;
    }

    private static int verdictBlockFlags(Block block) {
        int flags = 0;
        if (block == Blocks.BEDROCK) {
            flags |= IDENTITY_BEDROCK;
        }
        if (block == Blocks.MYCELIUM) {
            flags |= IDENTITY_MYCELIUM;
        }
        if (block == Blocks.MUD) {
            flags |= IDENTITY_MUD;
        }
        if (block == Blocks.LILY_PAD || block == Blocks.FROGSPAWN) {
            flags |= IDENTITY_WATER_PLANT;
        }
        if (block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
                || block == Blocks.END_GATEWAY) {
            flags |= IDENTITY_PORTAL;
        }
        if (block == Blocks.DIRT_PATH) {
            flags |= IDENTITY_DIRT_PATH;
        }
        if (isEndStone(block)) {
            flags |= IDENTITY_END_STONE;
        }
        if (isCrimsonForest(block)) {
            flags |= IDENTITY_CRIMSON_FOREST;
        }
        if (isSulfurCave(block)) {
            flags |= IDENTITY_SULFUR_CAVE;
        }
        if (isNetherVine(block)) {
            flags |= IDENTITY_NETHER_VINE;
        }
        if (isGroundCoveringPlant(block)) {
            flags |= IDENTITY_GROUND_COVERING_PLANT;
        }
        if (isTreeVine(block)) {
            flags |= IDENTITY_TREE_VINE;
        }
        if (isFungalCanopy(block)) {
            flags |= IDENTITY_FUNGAL_CANOPY;
        }
        return flags;
    }

    private static int probeBlockFlags(Block block) {
        int flags = 0;
        if (block == Blocks.RED_SAND || block == Blocks.RED_SANDSTONE
                || block == Blocks.SANDSTONE) {
            flags |= IDENTITY_RED_SAND;
        }
        if (isCaveFurniture(block)) {
            flags |= IDENTITY_CAVE_FURNITURE;
        }
        if (isOreBlock(block)) {
            flags |= IDENTITY_ORE;
        }
        if (isFurnitureBlock(block)) {
            flags |= IDENTITY_FURNITURE;
        }
        if (block == Blocks.WARPED_NYLIUM) {
            flags |= IDENTITY_WARPED_NYLIUM;
        }
        if (block == Blocks.MELON || block == Blocks.PUMPKIN || block == Blocks.BAMBOO
                || block == Blocks.MOSSY_COBBLESTONE) {
            flags |= IDENTITY_UNTAGGED_NATURAL;
        }
        if (block == Blocks.SNOW || block == Blocks.SNOW_BLOCK
                || block == Blocks.POWDER_SNOW) {
            flags |= IDENTITY_SNOW;
        }
        return flags;
    }

    private static int tagFlags(BlockState state) {
        int flags = 0;
        if (isInTag(state, BlockTags.LEAVES)) {
            flags |= IDENTITY_TAG_LEAVES;
        }
        if (isInTag(state, BlockTags.LOGS)) {
            flags |= IDENTITY_TAG_LOGS;
        }
        if (isInTag(state, BlockTags.PLANKS)) {
            flags |= IDENTITY_TAG_PLANKS;
        }
        if (isInTag(state, BlockTags.WOODEN_STAIRS)) {
            flags |= IDENTITY_TAG_WOODEN_STAIRS;
        }
        if (isInTag(state, BlockTags.WOODEN_SLABS)) {
            flags |= IDENTITY_TAG_WOODEN_SLABS;
        }
        if (isInTag(state, BlockTags.TERRACOTTA)) {
            flags |= IDENTITY_TAG_TERRACOTTA;
        }
        return flags;
    }

    private static int pathFlags(String path) {
        int flags = 0;
        if (path.endsWith("_leaves")) {
            flags |= (NAME_LEAVES | NAME_FOLIAGE) << NAME_SHIFT;
        } else if (path.endsWith("_log") || path.endsWith("_wood")) {
            flags |= NAME_FOLIAGE << NAME_SHIFT;
        }
        if (!path.endsWith("_glazed_terracotta")
                && (path.equals("terracotta") || path.endsWith("_terracotta"))) {
            flags |= NAME_TERRACOTTA << NAME_SHIFT;
        }
        if (path.contains("granite") || path.contains("diorite")) {
            flags |= NAME_GRANITE_DIORITE << NAME_SHIFT;
        }
        if ((path.contains("copper") || path.contains("lightning_rod")
                || path.equals("tnt") || path.equals("redstone_block"))
                && !path.endsWith("_ore")) {
            flags |= NAME_BUILT_UNDER_NETHER_COLOUR << NAME_SHIFT;
        }
        return flags;
    }

    private static final int IDENTITY_UNSET = -1;

    private static final int IDENTITY_SLOTS = 4096;

    private static final int IDENTITY_TABLE_GROWTH_FACTOR = 2;

    private static volatile AtomicIntegerArray IDENTITY = freshIdentityTable(IDENTITY_SLOTS);

    private static int identitySlot(Block block) {
        int id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(block);
        return id == 0 && block != Blocks.AIR ? -1 : id;
    }

    private static AtomicIntegerArray freshIdentityTable(int slots) {
        AtomicIntegerArray table = new AtomicIntegerArray(slots);
        for (int i = 0; i < slots; i++) {
            table.set(i, IDENTITY_UNSET);
        }
        return table;
    }

    private static int readIdentityFlags(int id) {
        AtomicIntegerArray table = IDENTITY;
        return id >= 0 && id < table.length() ? table.get(id) : IDENTITY_UNSET;
    }

    private static void storeIdentityFlags(int id, int flags) {
        if (id >= 0) {
            identityTable(id).set(id, flags);
        }
    }

    private static boolean identityIsCached(Block block) {
        return readIdentityFlags(identitySlot(block)) != IDENTITY_UNSET;
    }

    private static AtomicIntegerArray identityTable(int id) {
        AtomicIntegerArray table = IDENTITY;
        return id < table.length() ? table : growIdentityTable(id);
    }

    private static synchronized AtomicIntegerArray growIdentityTable(int id) {
        AtomicIntegerArray table = IDENTITY;
        if (id < table.length()) {
            return table;
        }
        AtomicIntegerArray grown = freshIdentityTable(Math.max(id + 1,
                table.length() * IDENTITY_TABLE_GROWTH_FACTOR));
        int n = table.length();
        for (int i = 0; i < n; i++) {
            grown.set(i, table.get(i));
        }
        IDENTITY = grown;
        return grown;
    }

    private static synchronized void forgetIdentityFlags() {
        AtomicIntegerArray table = IDENTITY;
        int n = table.length();
        for (int i = 0; i < n; i++) {
            table.set(i, IDENTITY_UNSET);
        }
    }

    static {
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents.COMPLETE
                .register((handler, client) -> tagsChanged());
    }

    // Forgets every cached identity flag and early-verdict entry; the next lookup
    // recomputes from live tags.
    public static void tagsChanged() {
        forgetIdentityFlags();
        EARLY.clear();
    }

    private static boolean isOre(BlockState state, int identity) {
        if (state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES)) {
            return true;
        }
        return (identity & IDENTITY_ORE) != 0;
    }

    private static boolean isOreBlock(Block block) {
        return block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE
                || block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE
                || block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE
                || block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE
                || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE
                || block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE
                || block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE
                || block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE
                || block == Blocks.NETHER_GOLD_ORE || block == Blocks.NETHER_QUARTZ_ORE;
    }

    private static boolean isCaveFurniture(Block block) {
        return block == Blocks.GRAVEL || block == Blocks.CLAY
                || block == Blocks.CALCITE || block == Blocks.SMOOTH_BASALT
                || block == Blocks.DRIPSTONE_BLOCK || block == Blocks.POINTED_DRIPSTONE
                || block == Blocks.MOSS_BLOCK || block == Blocks.MOSS_CARPET
                || block == Blocks.AMETHYST_BLOCK || block == Blocks.BUDDING_AMETHYST
                || block == Blocks.AMETHYST_CLUSTER
                || block == Blocks.OBSIDIAN || block == Blocks.MAGMA_BLOCK
                || block == Blocks.SCULK || block == Blocks.SCULK_VEIN
                || block == Blocks.SCULK_CATALYST || block == Blocks.SCULK_SENSOR
                || block == Blocks.SCULK_SHRIEKER
                || block == Blocks.GLOWSTONE || block == Blocks.ANCIENT_DEBRIS
                || block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL
                || block == Blocks.END_STONE || block == Blocks.MUD
                || block == Blocks.ICE || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE || block == Blocks.POWDER_SNOW;
    }

    public static LandCover fromLegacyColour(int rgb) {
        return LEGACY.getOrDefault(rgb & RGB_MASK, LandCover.UNKNOWN);
    }

    private static final java.util.Map<MapColor, LandCover> MATERIAL_COVER =
            buildMaterialCover();

    private static java.util.Map<MapColor, LandCover> buildMaterialCover() {
        java.util.Map<MapColor, LandCover> table = new java.util.IdentityHashMap<>();
        table.put(MapColor.NONE, LandCover.UNKNOWN);
        table.put(MapColor.WATER, LandCover.WATER);
        table.put(MapColor.GRASS, LandCover.OPEN);
        table.put(MapColor.DIRT, LandCover.OPEN);
        table.put(MapColor.PODZOL, LandCover.OPEN);
        table.put(MapColor.PLANT, LandCover.SCRUB);
        table.put(MapColor.COLOR_GREEN, LandCover.SCRUB);
        table.put(MapColor.SAND, LandCover.SAND);
        table.put(MapColor.TERRACOTTA_WHITE, LandCover.SAND);
        table.put(MapColor.TERRACOTTA_YELLOW, LandCover.SAND);
        table.put(MapColor.SNOW, LandCover.SNOW);
        table.put(MapColor.ICE, LandCover.WATER);
        table.put(MapColor.STONE, LandCover.ROCK);
        table.put(MapColor.DEEPSLATE, LandCover.ROCK);
        table.put(MapColor.CLAY, LandCover.ROCK);
        table.put(MapColor.RAW_IRON, LandCover.ROCK);
        table.put(MapColor.TERRACOTTA_ORANGE, LandCover.ROCK);
        table.put(MapColor.TERRACOTTA_RED, LandCover.ROCK);
        table.put(MapColor.TERRACOTTA_BROWN, LandCover.ROCK);
        table.put(MapColor.TERRACOTTA_LIGHT_GRAY, LandCover.ROCK);
        table.put(MapColor.TERRACOTTA_GRAY, LandCover.ROCK);
        table.put(MapColor.CRIMSON_NYLIUM, LandCover.CRIMSON);
        table.put(MapColor.CRIMSON_HYPHAE, LandCover.CRIMSON);
        table.put(MapColor.FIRE, LandCover.VOLCANIC);
        table.put(MapColor.NETHER, LandCover.VOLCANIC);
        table.put(MapColor.CRIMSON_STEM, LandCover.VOLCANIC);
        table.put(MapColor.WARPED_NYLIUM, LandCover.VOLCANIC);
        table.put(MapColor.WARPED_STEM, LandCover.VOLCANIC);
        table.put(MapColor.WARPED_HYPHAE, LandCover.VOLCANIC);
        table.put(MapColor.WARPED_WART_BLOCK, LandCover.VOLCANIC);
        return table;
    }

    private static final int MAP_COLOR_ID_COUNT = 64;

    private static final int RGB_MASK = 0xFFFFFF;

    private static final Int2ObjectOpenHashMap<LandCover> LEGACY = buildLegacyTable();

    private static Int2ObjectOpenHashMap<LandCover> buildLegacyTable() {
        MapColor.Brightness[] brightnesses = MapColor.Brightness.values();
        Int2ObjectOpenHashMap<LandCover> table =
                new Int2ObjectOpenHashMap<>(MAP_COLOR_ID_COUNT * brightnesses.length);
        for (int id = 0; id < MAP_COLOR_ID_COUNT; id++) {
            MapColor material;
            try {
                material = MapColor.byId(id);
            } catch (RuntimeException outOfRange) {
                material = null;
            }
            if (material != null && material != MapColor.NONE) {
                for (MapColor.Brightness brightness : brightnesses) {
                    table.putIfAbsent(material.calculateARGBColor(brightness) & RGB_MASK,
                            fromMaterial(material));
                }
            }
        }
        return table;
    }
}
