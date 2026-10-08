package dev.openmap.claim;

import dev.openmap.json.JsonObject;

public final class GreetingReportBody {

    private GreetingReportBody() {
    }

    // All parameters may be null.
    public static String json(String world, String id, String label,
                              GreetingLearner.Note greeting, GreetingLearner.Note farewell) {
        JsonObject root = new JsonObject();
        root.addProperty("world", world == null ? "" : world);
        root.addProperty("id", id == null ? "" : id);
        root.addProperty("label", label == null ? "" : label);
        if (greeting != null) {
            String title = greeting.title();
            String subtitle = greeting.subtitle();
            root.add("greeting", block(title, subtitle));
        }
        if (farewell != null) {
            String title = farewell.title();
            String subtitle = farewell.subtitle();
            root.add("farewell", block(title, subtitle));
        }
        return root.toString();
    }

    private static JsonObject block(String title, String subtitle) {
        JsonObject block = new JsonObject();
        block.addProperty("title", title == null ? "" : title);
        if (subtitle != null && !subtitle.isEmpty()) {
            block.addProperty("subtitle", subtitle);
        }
        return block;
    }
}
