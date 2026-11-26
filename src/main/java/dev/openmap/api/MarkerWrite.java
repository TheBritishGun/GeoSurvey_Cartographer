package dev.openmap.api;

// For REFUSED, reason is text a player can read.
// The reason is null for SAVED, STALE and NO_WORLD.
public record MarkerWrite(MarkerOutcome outcome, long revision, String reason) {
}
