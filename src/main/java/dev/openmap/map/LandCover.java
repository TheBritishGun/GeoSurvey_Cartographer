package dev.openmap.map;

public enum LandCover {

    UNKNOWN(0, Colours.UNKNOWN, 0),

    OPEN(1, Colours.OPEN, Page.OVERWORLD.bit),

    FOREST(Codes.FOREST, Colours.FOREST, Page.OVERWORLD.bit),

    SCRUB(Codes.SCRUB, Colours.SCRUB, Page.OVERWORLD.bit),

    WATER(Codes.WATER, Colours.WATER, Page.OVERWORLD.bit),

    MARSH(Codes.MARSH, Colours.MARSH, Page.OVERWORLD.bit),

    SAND(Codes.SAND, Colours.SAND, Page.OVERWORLD.bit),

    ROCK(Codes.ROCK, Colours.ROCK, Page.ANYWHERE.bit),

    SNOW(Codes.SNOW, Colours.SNOW, Page.OVERWORLD.bit),

    URBAN(Codes.URBAN, Colours.URBAN, Page.ANYWHERE.bit),

    PATH(Codes.PATH, Colours.PATH, Page.ANYWHERE.bit),

    VOLCANIC(Codes.VOLCANIC, Colours.VOLCANIC, Page.NETHER.bit),

    PORTAL(Codes.PORTAL, Colours.PORTAL, Page.ANYWHERE.bit),

    MYCELIUM(Codes.MYCELIUM, Colours.MYCELIUM, Page.OVERWORLD.bit),

    TERRACOTTA(Codes.TERRACOTTA, Colours.TERRACOTTA, Page.OVERWORLD.bit),

    // Matched by block, not colour.
    BEDROCK(Codes.BEDROCK, Colours.BEDROCK, Page.OVERWORLD.bit | Page.NETHER.bit),

    // Matched by block, not colour.
    END_STONE(Codes.END_STONE, Colours.END_STONE, Page.END.bit),

    // The crimson forest's ground; its canopy stays FOREST.
    // Matched by block, not colour.
    CRIMSON(Codes.CRIMSON, Colours.CRIMSON, Page.NETHER.bit),

    // An overworld cave biome. Matched by block, not colour.
    SULFUR(Codes.SULFUR, Colours.SULFUR, Page.OVERWORLD.bit);

    // Append new covers only; the ordinal is stored in region files.
    private final int code;

    private final int colour;

    private final int pages;

    LandCover(int code, int colour, int pages) {
        this.code = code;
        this.colour = colour;
        this.pages = pages;
    }

    // A dimension's legend prints the covers that walking there can produce.
    public enum Page {

        OVERWORLD(1),

        NETHER(PageBits.NETHER),

        END(PageBits.END),

        ANYWHERE(OVERWORLD.bit | NETHER.bit | END.bit);

        private final int bit;

        Page(int bit) {
            this.bit = bit;
        }

        // Null or unrecognised gets ANYWHERE.
        public static Page of(String dimensionId) {
            if (dimensionId == null) {
                return ANYWHERE;
            }
            if (dimensionId.equalsIgnoreCase("minecraft:the_nether")) {
                return NETHER;
            }
            if (dimensionId.equalsIgnoreCase("minecraft:the_end")) {
                return END;
            }
            if (dimensionId.equalsIgnoreCase("minecraft:overworld")) {
                return OVERWORLD;
            }
            return ANYWHERE;
        }
    }

    private static final class PageBits {

        private static final int NETHER = 2;

        private static final int END = 4;
    }

    private static final class Codes {

        private static final int FOREST = 2;

        private static final int SCRUB = 3;

        private static final int WATER = 4;

        private static final int MARSH = 5;

        private static final int SAND = 6;

        private static final int ROCK = 7;

        private static final int SNOW = 8;

        private static final int URBAN = 9;

        private static final int PATH = 10;

        private static final int VOLCANIC = 11;

        private static final int PORTAL = 12;

        private static final int MYCELIUM = 13;

        private static final int TERRACOTTA = 14;

        private static final int BEDROCK = 15;

        private static final int END_STONE = 16;

        private static final int CRIMSON = 17;

        private static final int SULFUR = 18;
    }

    private static final class Colours {

        private static final int UNKNOWN = 0x00000000;

        private static final int OPEN = 0xFFF2EDE0;

        private static final int FOREST = 0xFFA9C69A;

        private static final int SCRUB = 0xFFCBDCBA;

        private static final int WATER = 0xFFA6C6DF;

        private static final int MARSH = 0xFFB6CFC9;

        private static final int SAND = 0xFFEDE0BC;

        private static final int ROCK = 0xFFD8D4CC;

        private static final int SNOW = 0xFFFAFAFA;

        private static final int URBAN = 0xFFD9D2C9;

        private static final int PATH = 0xFFC9A87C;

        private static final int VOLCANIC = 0xFFD8A08A;

        private static final int PORTAL = 0xFF9A4FD0;

        private static final int MYCELIUM = 0xFFBFA8C6;

        private static final int TERRACOTTA = 0xFFB86A42;

        private static final int BEDROCK = 0xFF8A8A8F;

        private static final int END_STONE = 0xFFE0D486;

        private static final int CRIMSON = 0xFFC1566E;

        private static final int SULFUR = 0xFFC4C93C;
    }

    public int code() {
        return code;
    }

    public int colour() {
        return colour;
    }

    public boolean isKnown() {
        return this != UNKNOWN;
    }

    public boolean onPage(Page page) {
        return (pages & page.bit) != 0;
    }

    public static final int BUILDING_OUTLINE = 0xFF2B2B2B;

    private static final LandCover[] BY_ORDINAL = values();

    private static final int COUNT = BY_ORDINAL.length;

    private static final LandCover[] BY_CODE = codeTable();

    public static LandCover byOrdinal(int ordinal) {
        return ordinal < 0 || ordinal >= BY_ORDINAL.length ? UNKNOWN : BY_ORDINAL[ordinal];
    }

    public static LandCover byCode(int code) {
        return code < 0 || code >= BY_CODE.length || BY_CODE[code] == null
                ? UNKNOWN
                : BY_CODE[code];
    }

    public static int count() {
        return COUNT;
    }

    private static LandCover[] codeTable() {
        int highest = UNKNOWN.code;
        for (LandCover cover : BY_ORDINAL) {
            if (cover.code > highest) {
                highest = cover.code;
            }
        }
        LandCover[] table = new LandCover[highest + 1];
        for (LandCover cover : BY_ORDINAL) {
            table[cover.code] = cover;
        }
        return table;
    }
}
