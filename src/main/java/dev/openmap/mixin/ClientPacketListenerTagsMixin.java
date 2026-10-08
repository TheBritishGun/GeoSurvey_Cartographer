package dev.openmap.mixin;

import dev.openmap.client.ChunkSampler;
import dev.openmap.client.LandCoverClassifier;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Notifies ChunkSampler and LandCoverClassifier when tags update in PLAY.
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTagsMixin {

    @Inject(method = "handleUpdateTags", at = @At("TAIL"))
    private void landnav$tagsUpdatedInPlay(CallbackInfo ci) {
        ChunkSampler.tagsChanged();
        LandCoverClassifier.tagsChanged();
    }
}
