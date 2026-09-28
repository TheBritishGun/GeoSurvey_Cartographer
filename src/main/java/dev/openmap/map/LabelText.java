package dev.openmap.map;

import dev.openmap.json.JsonText;

public final class LabelText {

    public static final int UNBOUNDED_READ = Integer.MAX_VALUE;

    public static final char FORMATTING = '\u00A7';
    public static final char FIRST_PRINTABLE_ASCII = 0x20;
    public static final char FIRST_NON_PRINTABLE_ASCII = 0x7F;
    public static final char LAST_CONTROL_ASCII = 0x9F;
    public static final char SOFT_HYPHEN = 0x00AD;
    public static final char LEFT_TO_RIGHT_MARK = JsonText.LEFT_TO_RIGHT_MARK;
    public static final char RIGHT_TO_LEFT_MARK = JsonText.RIGHT_TO_LEFT_MARK;
    public static final char ZERO_WIDTH_SPACE = 0x200B;
    public static final char ZERO_WIDTH_NON_JOINER = 0x200C;
    public static final char ZERO_WIDTH_JOINER = 0x200D;
    public static final char LEFT_TO_RIGHT_EMBEDDING = JsonText.LEFT_TO_RIGHT_EMBEDDING;
    public static final char RIGHT_TO_LEFT_EMBEDDING = 0x202B;
    public static final char POP_DIRECTIONAL_FORMATTING = 0x202C;
    public static final char LEFT_TO_RIGHT_OVERRIDE = 0x202D;
    public static final char RIGHT_TO_LEFT_OVERRIDE = JsonText.RIGHT_TO_LEFT_OVERRIDE;
    public static final char LEFT_TO_RIGHT_ISOLATE = JsonText.LEFT_TO_RIGHT_ISOLATE;
    public static final char RIGHT_TO_LEFT_ISOLATE = 0x2067;
    public static final char FIRST_STRONG_ISOLATE = 0x2068;
    public static final char POP_DIRECTIONAL_ISOLATE = JsonText.POP_DIRECTIONAL_ISOLATE;
    public static final char LINE_SEPARATOR = JsonText.LINE_SEPARATOR;
    public static final char PARAGRAPH_SEPARATOR = JsonText.PARAGRAPH_SEPARATOR;
    public static final char WORD_JOINER = 0x2060;
    public static final char ARABIC_LETTER_MARK = JsonText.ARABIC_LETTER_MARK;
    public static final char ZERO_WIDTH_NO_BREAK_SPACE = 0xFEFF;
    private static final int SURROGATE_PAIR_WIDTH = 2;

    private static final ThreadLocal<StringBuilder> SCRATCH =
            ThreadLocal.withInitial(StringBuilder::new);

    private LabelText() {
    }

    public static String clean(String raw, int maxDrawn, int maxRead, boolean mark) {
        if (raw == null) {
            return "";
        }
        StringBuilder cleaned = null;
        int drawn = 0;
        int at = 0;
        int runStart = 0;
        final int len = raw.length();
        final int end = maxRead < len ? maxRead : len;
        while (at < end && drawn < maxDrawn) {
            char glyph = raw.charAt(at);
            if (glyph >= FIRST_PRINTABLE_ASCII && glyph < FIRST_NON_PRINTABLE_ASCII) {
                drawn++;
            } else if (opensPair(raw, at, glyph, len)) {
                if (at + 1 >= maxRead || drawn + 1 >= maxDrawn) {
                    break;
                }
                drawn += SURROGATE_PAIR_WIDTH;
                at++;
            } else {
                char kept = glyph == FORMATTING || isDirectional(glyph) ? 0
                        : isControl(glyph) ? ' '
                        : glyph;
                if (kept == glyph) {
                    drawn++;
                } else {
                    if (cleaned == null) {
                        cleaned = SCRATCH.get();
                        cleaned.setLength(0);
                        cleaned.ensureCapacity(bound(len, maxRead, maxDrawn));
                    }
                    cleaned.append(raw, runStart, at);
                    if (glyph == FORMATTING) {
                        at++;
                        if (at < len && opensPair(raw, at, raw.charAt(at), len)) {
                            at++;
                        }
                    }
                    if (kept != 0) {
                        cleaned.append(kept);
                        drawn++;
                    }
                    runStart = Math.min(at + 1, len);
                }
            }
            at++;
        }
        if (cleaned != null) {
            cleaned.append(raw, runStart, Math.min(at, len));
        }
        boolean more = at < len;
        String result;
        if (more && drawn == maxDrawn && end == len && !moreToDraw(raw, at, end)) {
            // Valid only when end == len: only then has moreToDraw scanned
            // the whole rest of the string.
            if (cleaned == null) {
                result = raw.substring(0, at);
            } else {
                result = cleaned.toString();
            }
        } else if (cleaned == null) {
            if (!more) {
                result = raw;
            } else {
                result = mark ? marked(raw, at) : raw.substring(0, at);
            }
        } else {
            result = more && mark ? marked(cleaned) : cleaned.toString();
        }
        return result;
    }

    // Whether a drawn character remains between at and end.
    private static boolean moreToDraw(String raw, int at, int end) {
        boolean more = false;
        while (at < end && !more) {
            char glyph = raw.charAt(at);
            if (glyph == FORMATTING) {
                at++;
                if (at < end && opensPair(raw, at, raw.charAt(at), end)) {
                    at++;
                }
                at++;
            } else if (isDirectional(glyph) || isControl(glyph)) {
                at++;
            } else {
                more = true;
            }
        }
        return more;
    }

    static int bound(int length, int maxRead, int maxDrawn) {
        return Math.min(Math.min(length, maxRead), maxDrawn) + 1;
    }

    private static String marked(String raw, int at) {
        if (at == 0) {
            return "";
        }
        int end = at - 1;
        if (end > 0 && Character.isLowSurrogate(raw.charAt(end))
                && Character.isHighSurrogate(raw.charAt(end - 1))) {
            end--;
        }
        StringBuilder cut = SCRATCH.get();
        cut.setLength(0);
        cut.append(raw, 0, end);
        return cut.append('\u2026').toString();
    }

    private static String marked(StringBuilder cut) {
        if (cut.isEmpty()) {
            return "";
        }
        int end = cut.length() - 1;
        if (end > 0 && Character.isLowSurrogate(cut.charAt(end))
                && Character.isHighSurrogate(cut.charAt(end - 1))) {
            end--;
        }
        cut.setLength(end);
        return cut.append('\u2026').toString();
    }

    private static boolean opensPair(String raw, int at, char glyph, int len) {
        return Character.isHighSurrogate(glyph)
                && at + 1 < len
                && Character.isLowSurrogate(raw.charAt(at + 1));
    }

    private static boolean isControl(char glyph) {
        return glyph < FIRST_PRINTABLE_ASCII
                || (glyph >= FIRST_NON_PRINTABLE_ASCII && glyph <= LAST_CONTROL_ASCII);
    }

    public static boolean isDirectional(char glyph) {
        if (glyph < SOFT_HYPHEN) {
            return false;
        }
        return switch (glyph) {
            case LEFT_TO_RIGHT_MARK, RIGHT_TO_LEFT_MARK,
                 ZERO_WIDTH_SPACE, ZERO_WIDTH_NON_JOINER, ZERO_WIDTH_JOINER,
                 LEFT_TO_RIGHT_EMBEDDING, RIGHT_TO_LEFT_EMBEDDING, POP_DIRECTIONAL_FORMATTING,
                 LEFT_TO_RIGHT_OVERRIDE, RIGHT_TO_LEFT_OVERRIDE,
                 LEFT_TO_RIGHT_ISOLATE, RIGHT_TO_LEFT_ISOLATE, FIRST_STRONG_ISOLATE,
                 POP_DIRECTIONAL_ISOLATE,
                 LINE_SEPARATOR, PARAGRAPH_SEPARATOR, WORD_JOINER,
                 SOFT_HYPHEN, ARABIC_LETTER_MARK,
                 ZERO_WIDTH_NO_BREAK_SPACE -> true;
            default -> false;
        };
    }
}
