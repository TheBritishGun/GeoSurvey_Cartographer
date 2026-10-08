package dev.openmap.mixin;

import dev.openmap.client.ClaimSplashHub;
import dev.openmap.map.TabName;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public abstract class ClientPacketListenerTitleMixin {

    @Inject(method = "setTitleText", at = @At("TAIL"))
    private void openmap$noteServerTitle(ClientboundSetTitleTextPacket packet,
                                         CallbackInfo ci) {
        ClaimSplashHub.serverTitle(TabName.bounded(packet.text().getString(TabName.MAX_FILTER_CHARS + 1)));
    }

    @Inject(method = "setSubtitleText", at = @At("TAIL"))
    private void openmap$noteServerSubtitle(ClientboundSetSubtitleTextPacket packet,
                                            CallbackInfo ci) {
        ClaimSplashHub.serverSubtitle(TabName.bounded(packet.text().getString(TabName.MAX_FILTER_CHARS + 1)));
    }
}
