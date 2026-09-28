package dev.openmap.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.sandpaper.client.SandpaperConfigScreen;

public final class CollectorModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SandpaperConfigScreen::create;
    }
}
