package dev.openmap.share;

import dev.openmap.map.ChunkSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Whether one MapCard describes the ground this client has surveyed.
public final class CardCheck {

    // What one card came to.
    public enum Verdict {

        MATCHED("This map card describes the ground here.", true),

        NEAR("The ground here is close to this map card. It has changed too much."
                + " Ask the operator to publish a new card.", false),

        MISMATCHED("This map card describes other ground. It is for another server,"
                + " or somebody replaced this world.", false),

        NOT_SURVEYED("Walk to the spawn point this map card names. The map holds no"
                + " record of that ground.", false),

        TOO_OLD("Walk to the spawn point this map card names. That record is too old"
                + " to compare.", false),

        NO_MAP("Join a server. A map card needs ground you have walked.", false);

        private final String reason;

        private final boolean usable;

        Verdict(String reason, boolean usable) {
            this.reason = reason;
            this.usable = usable;
        }

        // One line for a player.
        public String reason() {
            return reason;
        }

        // Whether the address in the card may be asked. Only MATCHED.
        public boolean usable() {
            return usable;
        }
    }

    // card is never null.
    public record Result(MapCard card, Verdict verdict, SpawnPrint.Agreement agreement) {

        public boolean usable() {
            return verdict.usable();
        }
    }

    private CardCheck() {
    }

    // Checks one card against stored ground; ground is null when there is none.
    public static Result of(MapCard card, ChunkSource ground) {
        if (card == null) {
            return null;
        }
        Result result;
        if (ground == null) {
            result = new Result(card, Verdict.NO_MAP, SpawnPrint.Agreement.NONE);
        } else {
            SpawnPrint.Ground measured =
                    SpawnPrint.measure(ground, card.anchorX(), card.anchorZ());
            if (!measured.isReadable()) {
                Verdict verdict = measured.fault() == SpawnPrint.Fault.TOO_OLD
                        ? Verdict.TOO_OLD : Verdict.NOT_SURVEYED;
                result = new Result(card, verdict, SpawnPrint.Agreement.NONE);
            } else {

        // In the card's frame, never in one derived here.
                SpawnPrint print = card.print();
                SpawnPrint mine = SpawnPrint.of(measured, print.base());
                SpawnPrint.Agreement agreement = print.agreementWith(mine);
                Verdict verdict = (agreement.matches()
                        && reliefAgreeing(print, mine) >= MIN_RELIEF_AGREEING)
                        ? Verdict.MATCHED
                        : (agreement.near() ? Verdict.NEAR : Verdict.MISMATCHED);
                result = new Result(card, verdict, agreement);
            }
        }
        return result;
    }

    private static final int MIN_RELIEF_AGREEING = 4;

    private static final int MAX_EXPECTED_ORIGINS = 32;

    private static int reliefAgreeing(SpawnPrint card, SpawnPrint mine) {
        int plateau = card.plateauStep();
        int agreeing = 0;
        for (int cell = 0; cell < SpawnPrint.PANEL
                && agreeing < MIN_RELIEF_AGREEING; cell++) {
            int step = card.levelOf(cell);
            if (step != plateau && step == mine.levelOf(cell)) {
                agreeing++;
            }
        }
        return agreeing;
    }

    // The addresses a caller may ask, in card order, without repeats.
    public static List<String> usableOrigins(List<Result> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        if (results.size() == 1) {
            Result result = results.getFirst();
            return result == null || !result.usable() ? List.of()
                    : List.of(result.card().origin());
        }
        Set<String> out = null;
        for (Result result : results) {
            if (result != null && result.usable()) {
                if (out == null) {
                    out = LinkedHashSet.newLinkedHashSet(
                            Math.min(results.size(), MAX_EXPECTED_ORIGINS));
                }
                out.add(result.card().origin());
            }
        }
        return out == null ? List.of() : List.copyOf(out);
    }
}
