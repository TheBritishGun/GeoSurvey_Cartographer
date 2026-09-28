package dev.openmap.mixin;

import dev.openmap.client.ChunkCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Gives ChunkCapture.shareServer the redirect target of a server transfer;
// ServerData.ip does not carry it. Cleared on an ordinary disconnect.
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplTransferMixin {

    @Shadow
    protected boolean isTransferring;

    @Shadow
    protected ServerData serverData;

    @Inject(method = "handleTransfer", at = @At("HEAD"))
    private void openmap$recordTransferTarget(ClientboundTransferPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            ChunkCapture.noteTransferTarget(packet.host(), packet.port(), this.serverData);
        }
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void openmap$noteDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        ChunkCapture.noteCommonListenerDisconnect(this.isTransferring);
    }
}
