package dev.openmap.api;

// The failure is null when the merge landed.
public record ImportResult(int written, int keptNewer, String failure) {
}
