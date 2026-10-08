package dev.openmap.api;

// For REFUSED, the reason is a text a player can read.
// The reason is null for SAVED, STALE and NO_WORLD.
public record MarkerWrite(MarkerOutcome outcome, long revision, String reason) {
}
