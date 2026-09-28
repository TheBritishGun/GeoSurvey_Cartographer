package dev.openmap.mixin;

import dev.openmap.client.ChunkSampler;
import dev.openmap.client.LandCoverClassifier;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Tells ChunkSampler and LandCoverClassifier when tags change while still in
// PLAY, such as a server reload. Runs at TAIL, after vanilla applies the update.
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTagsMixin {

    @Inject(method = "handleUpdateTags", at = @At("TAIL"))
    private void landnav$tagsUpdatedInPlay(CallbackInfo ci) {
        ChunkSampler.tagsChanged();
        LandCoverClassifier.tagsChanged();
    }
}
