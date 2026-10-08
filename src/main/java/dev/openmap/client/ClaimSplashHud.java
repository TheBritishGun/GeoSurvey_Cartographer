package dev.openmap.client;

import dev.openmap.claim.ClaimSplash;
import dev.openmap.claim.SplashFade;
import dev.openmap.claim.SplashQueue;
import dev.sandpaper.client.font.SandpaperFonts;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

public final class ClaimSplashHud {

    private static final double NANOS_PER_TICK = 50_000_000.0;

    private static final long CHECK_PERIOD_NANOS = ClaimSplash.DEFAULT_CHECK_PERIOD_MILLIS * 1_000_000L;

    private static final long SPENT_NANOS =
            Math.max(SplashFade.TOTAL_TICKS * (long) NANOS_PER_TICK, CHECK_PERIOD_NANOS);

    private static volatile SplashQueue.State queue = SplashQueue.State.EMPTY;

    private static ClaimSplash.Text cachedText;

    private static Component cachedTitle;

    private static Component cachedSubtitle;

    private ClaimSplashHud() {
    }

    static void offer(List<ClaimSplash.Text> drawn) {
        queue = SplashQueue.offer(queue, drawn);
    }

    static void reset() {
        queue = SplashQueue.State.EMPTY;
    }

    static void render(GuiGraphicsExtractor gfx, DeltaTracker delta) {
        SplashQueue.State state = queue;
        if (state == SplashQueue.State.EMPTY) {
            return;
        }
        long nowNanos = System.nanoTime();
        SplashQueue.State frame = frameState(state, nowNanos);
        if (frame != state) {
            queue = frame;
        }
        int alpha = alphaAt(frame, nowNanos);
        if (alpha > 0) {
            ClaimSplash.Text showing = frame.showing();
            updateTextCache(showing);
            Minecraft client = Minecraft.getInstance();
            Font font = SandpaperFonts.lettering(client.font);
            int colour = ARGB.white(alpha);

            gfx.nextStratum();
            var pose = gfx.pose();
            pose.pushMatrix();

            pose.translate((float) halvedGuiSizeFor(gfx.guiWidth()),
                    (float) halvedGuiSizeFor(gfx.guiHeight()));

            Component title = cachedTitle;
            int titleWidth = font.width(title);
            pose.pushMatrix();
            pose.scale(4f, 4f);
            gfx.textWithBackdrop(font, title, centredTextOffsetFor(titleWidth), -10, titleWidth,
                    colour);
            pose.popMatrix();

            if (cachedSubtitle != null) {
                Component subtitle = cachedSubtitle;
                int subtitleWidth = font.width(subtitle);
                pose.pushMatrix();
                pose.scale(2f, 2f);
                gfx.textWithBackdrop(font, subtitle, centredTextOffsetFor(subtitleWidth), 5,
                        subtitleWidth, colour);
                pose.popMatrix();
            }

            pose.popMatrix();
        }
    }

    static SplashQueue.State frameState(SplashQueue.State state, long nowNanos) {
        final SplashQueue.State frame;
        if (!state.pending().isEmpty()) {
            frame = SplashQueue.advance(state, nowNanos, CHECK_PERIOD_NANOS);
        } else if (nowNanos - state.showingSinceNanos() >= SPENT_NANOS) {
            frame = SplashQueue.State.EMPTY;
        } else {
            frame = state;
        }
        return frame;
    }

    static int alphaAt(SplashQueue.State frame, long nowNanos) {
        return frame.showing() == null ? 0
                : SplashFade.alpha((double) (nowNanos - frame.showingSinceNanos()) / NANOS_PER_TICK);
    }

    private static void updateTextCache(ClaimSplash.Text showing) {
        if (showing != cachedText) {
            cachedTitle = Component.literal(showing.title());
            cachedSubtitle = showing.subtitle().isEmpty() ? null : Component.literal(showing.subtitle());
            cachedText = showing;
        }
    }

    static int halvedGuiSizeFor(int guiSize) {
        return guiSize < 0 ? guiSize / 2 : guiSize >> 1;
    }

    static int centredTextOffsetFor(int textWidth) {
        return textWidth < 0 ? -textWidth / 2 : -(textWidth >> 1);
    }

    static Component cachedTitleFor(ClaimSplash.Text showing) {
        updateTextCache(showing);
        return cachedTitle;
    }

    static Component cachedSubtitleFor(ClaimSplash.Text showing) {
        updateTextCache(showing);
        return cachedSubtitle;
    }
}


