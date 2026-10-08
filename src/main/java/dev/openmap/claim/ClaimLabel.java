package dev.openmap.claim;

import dev.openmap.map.LabelText;

public record ClaimLabel(String name, String owner) {

    private static final String OWNED_BY_PHRASE = "owned by";

    private static final String TRAILING_BY = " by ";

    private static final int MIN_PLAYER_NAME_CHARS = 3;

    private static final int MAX_PLAYER_NAME_CHARS = 16;

    private static final int MAX_LABEL_CHARS = 64;

    private static final int MAX_LABEL_SCAN = MAX_LABEL_CHARS * 3;

    public ClaimLabel {
        name = name == null ? "" : name.trim();
        owner = owner == null ? "" : owner.trim();
    }

    public static ClaimLabel split(String label) {
        String text = label == null ? "" : label.trim();

        int ownedByAt = findOwnedByPhrase(text);
        if (ownedByAt >= 0) {
            int ownerStart = skipTrimmable(text, ownedByAt + OWNED_BY_PHRASE.length());
            int ownerEnd = skipTrimmableBack(text, text.length());
            if (ownerStart < ownerEnd) {
                return bounded(text.substring(0, backOverOwnedBySeparator(text, ownedByAt)),
                        text.substring(ownerStart, ownerEnd));
            }
        }

        int by = lastTrailingBy(text);
        if (by >= 0) {
            int ownerStart = skipTrimmable(text, by + TRAILING_BY.length());
            int ownerEnd = skipTrimmableBack(text, text.length());
            if (isPlayerName(text, ownerStart, ownerEnd)) {
                return bounded(text.substring(0, by), text.substring(ownerStart, ownerEnd));
            }
        }

        return bounded(text, "");
    }

    public boolean hasOwner() {
        return !owner.isEmpty();
    }

    public String title() {
        return "Entering " + name;
    }

    public String subtitle() {
        return hasOwner() ? "Owned by " + owner : "";
    }

    private static ClaimLabel bounded(String name, String owner) {
        return new ClaimLabel(cleanLabelPart(name), cleanLabelPart(owner));
    }

    private static String cleanLabelPart(String part) {
        return part.isEmpty() ? part : LabelText.clean(part, MAX_LABEL_CHARS, MAX_LABEL_SCAN, true);
    }

    private static int lastTrailingBy(String text) {
        return text.lastIndexOf(TRAILING_BY);
    }

    private static int findOwnedByPhrase(String text) {
        int limit = text.length() - OWNED_BY_PHRASE.length();
        int found = -1;
        for (int at = 0; at <= limit; at++) {
            if (matchesOwnedByPhraseAt(text, at)
                    && isWordBoundary(text, at)
                    && isWordBoundary(text, at + OWNED_BY_PHRASE.length())) {
                found = at;
                break;
            }
        }
        return found;
    }

    private static boolean matchesOwnedByPhraseAt(String text, int at) {
        boolean matches = true;
        for (int i = 0; i < OWNED_BY_PHRASE.length(); i++) {
            if (asciiFold(text.charAt(at + i)) != OWNED_BY_PHRASE.charAt(i)) {
                matches = false;
                break;
            }
        }
        return matches;
    }

    private static char asciiFold(char glyph) {
        return glyph >= 'A' && glyph <= 'Z' ? (char) (glyph + ('a' - 'A')) : glyph;
    }

    private static boolean isWordBoundary(String text, int at) {
        boolean before = at > 0 && isWordChar(text.charAt(at - 1));
        boolean after = at < text.length() && isWordChar(text.charAt(at));
        return before != after;
    }

    private static boolean isWordChar(char glyph) {
        return (glyph >= 'a' && glyph <= 'z') || (glyph >= 'A' && glyph <= 'Z')
                || (glyph >= '0' && glyph <= '9') || glyph == '_';
    }

    private static int backOverOwnedBySeparator(String text, int phraseAt) {
        int at = skipRegexSpaceBack(text, phraseAt);
        if (at > 0 && text.charAt(at - 1) == '-') {
            at = skipRegexSpaceBack(text, at - 1);
        }
        return at;
    }

    private static int skipRegexSpaceBack(String text, int at) {
        while (at > 0 && isRegexSpace(text.charAt(at - 1))) {
            at--;
        }
        return at;
    }

    private static boolean isRegexSpace(char glyph) {
        return glyph == ' ' || glyph == '\t' || glyph == '\n' || glyph == '\u000B'
                || glyph == '\f' || glyph == '\r';
    }

    private static int skipTrimmable(String text, int from) {
        int at = from;
        while (at < text.length() && text.charAt(at) <= ' ') {
            at++;
        }
        return at;
    }

    private static int skipTrimmableBack(String text, int to) {
        int at = to;
        while (at > 0 && text.charAt(at - 1) <= ' ') {
            at--;
        }
        return at;
    }

    private static boolean isPlayerName(String text, int start, int end) {
        int length = end - start;
        if (length < MIN_PLAYER_NAME_CHARS || length > MAX_PLAYER_NAME_CHARS) {
            return false;
        }
        boolean allWordChars = true;
        for (int at = start; at < end; at++) {
            if (!isWordChar(text.charAt(at))) {
                allWordChars = false;
                break;
            }
        }
        return allWordChars;
    }
}
