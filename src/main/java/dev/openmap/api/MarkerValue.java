package dev.openmap.api;

// The affiliation and the icon are constant names; shared may be null.
public record MarkerValue(String name, int x, int z, int colour, String affiliation, String icon, String shared) {
}
