package dev.openmap.share;

import dev.openmap.map.ChunkSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Whether a MapCard describes the ground this client surveyed.
public final class CardCheck {

    public enum Verdict {

        MATCHED("This map card describes the ground here.", true),

        NEAR("This map card nearly matches, but the ground changed too much."
                + " Ask the operator to publish a new card.", false),

        MISMATCHED("This map card is for another server,"
                + " or a replaced world.", false),

        NOT_SURVEYED("Walk to this map card's spawn point. The map has no"
                + " record of it.", false),

        TOO_OLD("Walk to this map card's spawn point. The record"
                + " there is too old.", false),

        NO_MAP("Join a server. A map card needs ground you walked.", false);

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

        // Whether the card's address may be asked.
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

    // ground is null when there is none.
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

        // In the card's frame, not our own.
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

    // The usable addresses, in card order, without repeats.
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
