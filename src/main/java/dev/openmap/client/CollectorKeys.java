package dev.openmap.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class CollectorKeys {

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath(CollectorMod.MOD_ID, "main"));

    private CollectorKeys() {
    }
}
