package dev.openmap.symbol;

public enum SymbolIcon {

    NONE("No icon", Glyphs.NONE_LOW, Glyphs.NONE_HIGH),
    WAYPOINT("Waypoint", Glyphs.WAYPOINT_LOW, Glyphs.WAYPOINT_HIGH),
    BUILDING("Building", Glyphs.BUILDING_LOW, Glyphs.BUILDING_HIGH),
    HEADQUARTERS("Headquarters", Glyphs.HEADQUARTERS_LOW, Glyphs.HEADQUARTERS_HIGH),
    SUPPLY("Supply point", Glyphs.SUPPLY_LOW, Glyphs.SUPPLY_HIGH),
    MEDICAL("Medical", Glyphs.MEDICAL_LOW, Glyphs.MEDICAL_HIGH),
    OBSERVATION("Observation post", Glyphs.OBSERVATION_LOW, Glyphs.OBSERVATION_HIGH),
    HAZARD("Hazard", Glyphs.HAZARD_LOW, Glyphs.HAZARD_HIGH),
    CAVE("Cave", Glyphs.CAVE_LOW, Glyphs.CAVE_HIGH),
    BRIDGE("Crossing", Glyphs.BRIDGE_LOW, Glyphs.BRIDGE_HIGH),
    CASUALTY("Casualty", Glyphs.CASUALTY_LOW, Glyphs.CASUALTY_HIGH),
    BASE("Base", Glyphs.BASE_LOW, Glyphs.BASE_HIGH),
    SETTLEMENT("Settlement", Glyphs.SETTLEMENT_LOW, Glyphs.SETTLEMENT_HIGH),
    FARM("Farm", Glyphs.FARM_LOW, Glyphs.FARM_HIGH),
    AIRFIELD("Airfield", Glyphs.AIRFIELD_LOW, Glyphs.AIRFIELD_HIGH),
    PORT("Port", Glyphs.PORT_LOW, Glyphs.PORT_HIGH),
    FUEL("Fuel", Glyphs.FUEL_LOW, Glyphs.FUEL_HIGH),
    AMMUNITION("Ammunition", Glyphs.AMMUNITION_LOW, Glyphs.AMMUNITION_HIGH),
    WORKSHOP("Workshop", Glyphs.WORKSHOP_LOW, Glyphs.WORKSHOP_HIGH),
    COMMS("Comms", Glyphs.COMMS_LOW, Glyphs.COMMS_HIGH),
    MINE("Mine", Glyphs.MINE_LOW, Glyphs.MINE_HIGH),
    CHECKPOINT("Checkpoint", Glyphs.CHECKPOINT_LOW, Glyphs.CHECKPOINT_HIGH),
    LANDING_ZONE("Landing zone", Glyphs.LANDING_ZONE_LOW, Glyphs.LANDING_ZONE_HIGH),
    WATER_POINT("Water point", Glyphs.WATER_POINT_LOW, Glyphs.WATER_POINT_HIGH),
    RALLY("Rally point", Glyphs.RALLY_LOW, Glyphs.RALLY_HIGH);

    public static final int SIZE = 9;

    public static final int WORDS = (SIZE * SIZE + 63) / 64;
    private static final int WORD_COUNT = 2;
    private static final SymbolIcon[] VALUES = values();

    private static final class Glyphs {

        private static final long NONE_LOW = 0x0000000000000000L;
        private static final long NONE_HIGH = 0x0000000000000000L;
        private static final long WAYPOINT_LOW = 0x0E0F87C3E0E00000L;
        private static final long WAYPOINT_HIGH = 0x0000000000000000L;
        private static final long BUILDING_LOW = 0x3F9048241209FC00L;
        private static final long BUILDING_HIGH = 0x0000000000000000L;
        private static final long HEADQUARTERS_LOW = 0x00804021F0887C00L;
        private static final long HEADQUARTERS_HIGH = 0x0000000000000001L;
        private static final long SUPPLY_LOW = 0x3F904827F209FC00L;
        private static final long SUPPLY_HIGH = 0x0000000000000000L;
        private static final long MEDICAL_LOW = 0x0E1FCFE7F0E07000L;
        private static final long MEDICAL_HIGH = 0x000000000000001CL;
        private static final long OBSERVATION_LOW = 0x20A73399CA08F800L;
        private static final long OBSERVATION_HIGH = 0x000000000000003EL;
        private static final long HAZARD_LOW = 0xF1F8E001C0E07000L;
        private static final long HAZARD_HIGH = 0x00000000000000E3L;
        private static final long CAVE_LOW = 0xC060301C1BF8F800L;
        private static final long CAVE_HIGH = 0x0000000000000080L;
        private static final long BRIDGE_LOW = 0x20904FE7F2090400L;
        private static final long BRIDGE_HIGH = 0x0000000000000000L;
        private static final long CASUALTY_LOW = 0x318D838363190400L;
        private static final long CASUALTY_HIGH = 0x0000000000000041L;
        private static final long BASE_LOW = 0x209FC021F0887C02L;
        private static final long BASE_HIGH = 0x000000000000007FL;
        private static final long SETTLEMENT_LOW = 0x1F07000553B88800L;
        private static final long SETTLEMENT_HIGH = 0x0000000000000022L;
        private static final long FARM_LOW = 0x2A974827F208F838L;
        private static final long FARM_HIGH = 0x000000000000007FL;
        private static final long AIRFIELD_LOW = 0x0E020107F0402000L;
        private static final long AIRFIELD_HIGH = 0x0000000000000000L;
        private static final long PORT_LOW = 0x24924103E0407010L;
        private static final long PORT_HIGH = 0x000000000000003EL;
        private static final long FUEL_LOW = 0x3F904FE413F8F800L;
        private static final long FUEL_HIGH = 0x000000000000003EL;
        private static final long AMMUNITION_LOW = 0x1F0F87C3E1F07010L;
        private static final long AMMUNITION_HIGH = 0x000000000000002AL;
        private static final long WORKSHOP_LOW = 0x701C0701C0701C06L;
        private static final long WORKSHOP_HIGH = 0x00000000000000C0L;
        private static final long COMMS_LOW = 0x0E02010081512501L;
        private static final long COMMS_HIGH = 0x000000000000003EL;
        private static final long MINE_LOW = 0x1507010141110400L;
        private static final long MINE_HIGH = 0x0000000000000008L;
        private static final long CHECKPOINT_LOW = 0x00804027F0080400L;
        private static final long CHECKPOINT_HIGH = 0x0000000000000007L;
        private static final long LANDING_ZONE_LOW = 0x20A8B45BED15047CL;
        private static final long LANDING_ZONE_HIGH = 0x000000000000003EL;
        private static final long WATER_POINT_LOW = 0x1F1FCFE7F1F07010L;
        private static final long WATER_POINT_HIGH = 0x000000000000001CL;
        private static final long RALLY_LOW = 0x1112539491105010L;
        private static final long RALLY_HIGH = 0x0000000000001014L;

        private Glyphs() {
        }
    }

    static {
        if (WORDS != WORD_COUNT) {
            throw new IllegalStateException("SymbolIcon stores 2 words and SIZE " + SIZE
                    + " needs " + WORDS);
        }
    }

    private final String label;
    private final long lowWord;
    private final long highWord;

    SymbolIcon(String label, long low, long high) {
        this.label = label;
        lowWord = low;
        highWord = high;
    }

    public String label() {
        return label;
    }

    public boolean at(int x, int y) {
        if ((x | y | (SIZE - 1 - x) | (SIZE - 1 - y)) < 0) {
            return false;
        }
        int index = y * SIZE + x;
        boolean firstWord = index < Long.SIZE;
        long glyphWord = firstWord ? lowWord : highWord;
        int bit = firstWord ? index : index - Long.SIZE;
        return ((glyphWord >>> bit) & 1L) != 0;
    }

    public long word(int index) {
        if ((index | (WORDS - 1 - index)) < 0) {
            throw new IndexOutOfBoundsException("word index " + index + " of " + WORDS);
        }
        return index == 0 ? lowWord : highWord;
    }

    public SymbolIcon next() {
        SymbolIcon[] values = VALUES;
        int step = ordinal() + 1;
        return values[step == values.length ? 0 : step];
    }
}
