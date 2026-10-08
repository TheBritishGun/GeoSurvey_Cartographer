package dev.openmap.client;

import dev.openmap.config.LandNavConfig;
import dev.sandpaper.api.settings.SettingsContributor;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CollectorAddressCheck {

    static final int PAUSE_TICKS = 13;

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectorMod.MOD_ID);

    static final int MAX_TICKING = 4;

    private static final CollectorAddressCheck[] ticking = new CollectorAddressCheck[MAX_TICKING];

    private static int tickingCount;

    private static boolean listening;

    private final LandNavConfig config;

    private final Component checkingLine;

    private final Component noAnswerLine;

    private final Component cleartextLine;

    private final Component notAnAddressLine;

    private final Set<String> answered = new HashSet<>();

    private String latest;

    private int quietTicks;

    private boolean settled;

    private String checking;

    private String silent;

    private SettingsContributor.Rules rules;

    private Screen shownOn;

    private Screen replaced;

    public CollectorAddressCheck(LandNavConfig config, Component checkingLine,
            Component noAnswerLine, Component cleartextLine, Component notAnAddressLine) {
        this.config = config;
        this.checkingLine = checkingLine;
        this.noAnswerLine = noAnswerLine;
        this.cleartextLine = cleartextLine;
        this.notAnAddressLine = notAnAddressLine;
        this.latest = trimmed(config.shareCollector);
        this.settled = true;
    }

    public boolean holds(String stored, String typed) {
        String address = trimmed(typed);
        boolean held = false;
        if (renamed(stored, address)) {
            boolean cleartext = ShareCommand.cleartextAwayFromHome(address);
            held = unconfirmed(address, cleartext);
        }
        return held;
    }

    // address: already trimmed.
    boolean awaitsAnswer(String stored, String address) {
        return renamed(stored, address) && !answered.contains(address);
    }

    public void typed(String typed) {
        mark(trimmed(typed));
    }

    public Component review(String typed) {
        String address = trimmed(typed);
        mark(address);
        return line(address);
    }

    public void trusted(String typed) {
        answered.add(trimmed(typed));
    }

    public void opened(SettingsContributor.Rules rules) {
        this.rules = rules;
    }

    public void opensOn(String typed) {
        mark(trimmed(typed));
        if (shownOn != null) {
            replaced = shownOn;
            shownOn = null;
        }
    }

    void tick() {
        if (quietTicks < PAUSE_TICKS) {
            quietTicks++;
        }
        if (!settled && checking == null && quietTicks >= PAUSE_TICKS) {
            settled = true;
            if (needsCheck(latest)) {
                ask(latest);
            }
        }
    }

    void heard(String address, boolean collector) {
        if (address.equals(checking)) {
            checking = null;
        }
        boolean current = address.equals(latest);
        if (current && collector) {
            answered.add(address);
        }
        if (current && !collector) {
            silent = address;
        }
        settled = false;
        if (current && rules != null) {
            rules.askAgain();
        }
    }

    static void addTicking(CollectorAddressCheck owner) {
        int vacant = -1;
        boolean present = false;
        for (int i = 0; i < ticking.length; i++) {
            CollectorAddressCheck slot = ticking[i];
            if (slot == owner) {
                present = true;
                break;
            }
            if (slot == null && vacant == -1) {
                vacant = i;
            }
        }
        if (!present) {
            if (vacant != -1) {
                ticking[vacant] = owner;
                tickingCount++;
            } else {
                replaceTicking(owner);
            }
        }
    }

    static void driveTicking(Screen now) {
        if (tickingCount == 0) {
            return;
        }
        for (CollectorAddressCheck open : ticking) {
            if (open != null) {
                try {
                    open.clientTick(now);
                } catch (RuntimeException failed) {
                    open.stopTicking();
                    LOGGER.warn("collector address check stopped", failed);
                }
            }
        }
    }

    private static boolean renamed(String stored, String address) {
        return !address.isEmpty() && !address.equals(trimmed(stored));
    }

    private boolean unconfirmed(String address, boolean cleartext) {
        return cleartext || !answered.contains(address);
    }

    private Component line(String address) {
        Component said;
        if (!address.isEmpty() && !ShareCommand.looksUsable(address)) {
            said = notAnAddressLine;
        } else if (ShareCommand.cleartextAwayFromHome(address)) {
            said = cleartextLine;
        } else if (!renamed(config.shareCollector, address) || answered.contains(address)) {
            said = null;
        } else if (address.equals(silent)) {
            said = noAnswerLine;
        } else if (rules != null) {
            said = checkingLine;
        } else {
            said = null;
        }
        return said;
    }

    private void mark(String address) {
        if (!address.equals(latest)) {
            latest = address;
            quietTicks = 0;
            settled = false;
            silent = null;
        }
        if (!settled || checking != null) {
            shownOn = currentScreen();
            startTicking();
        }
    }

    private boolean needsCheck(String address) {
        boolean changed = renamed(config.shareCollector, address);
        boolean cleartext = ShareCommand.cleartextAwayFromHome(address);
        boolean held = changed && unconfirmed(address, cleartext);
        return held && !cleartext && !address.equals(silent);
    }

    private void ask(String address) {
        checking = address;
        ShareSender sender = ShareSender.live();
        boolean postable = ShareCommand.looksUsable(address);
        if (sender != null && postable) {
            CollectorFinder.check(sender, address, this);
        } else {
            heard(address, false);
        }
    }

    private void clientTick(Screen now) {
        if (replaced != null && now != null && now != replaced) {
            shownOn = now;
            replaced = null;
        }
        if (now != null && now == shownOn) {
            tick();
        }
        if (settled && checking == null) {
            stopTicking();
        }
    }

    private void startTicking() {
        if (Minecraft.getInstance() != null) {
            addTicking(this);
            listen();
        }
    }

    private void stopTicking() {
        removeTicking(this);
    }

    private static void replaceTicking(CollectorAddressCheck owner) {
        Screen showing = currentScreen();
        boolean replacedSlot = false;
        for (int i = 0; i < ticking.length; i++) {
            CollectorAddressCheck held = ticking[i];
            if (held.replaced == null && held.shownOn != showing) {
                ticking[i] = owner;
                if (owner == null) {
                    tickingCount--;
                }
                replacedSlot = true;
                break;
            }
        }
        if (!replacedSlot) {
            LOGGER.warn("collector address check has no free ticking slot");
        }
    }

    private static void removeTicking(CollectorAddressCheck owner) {
        for (int i = 0; i < ticking.length; i++) {
            if (ticking[i] == owner) {
                ticking[i] = null;
                tickingCount--;
                break;
            }
        }
    }

    private static void listen() {
        if (!listening) {
            listening = true;
            ClientTickEvents.END_CLIENT_TICK.register(CollectorAddressCheck::onClientTick);
        }
    }

    private static void onClientTick(Minecraft client) {
        if (tickingCount == 0) {
            return;
        }
        driveTicking(client.gui == null ? null : client.gui.screen());
    }

    private static Screen currentScreen() {
        Minecraft client = Minecraft.getInstance();
        return client == null || client.gui == null ? null : client.gui.screen();
    }

    private static String trimmed(String text) {
        return text == null ? "" : text.trim();
    }
}
