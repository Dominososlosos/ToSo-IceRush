package sk.toso.icerush.mixin;

import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sk.toso.icerush.IceRushMod;

/**
 * Blocks only the player's explicit sneak-to-dismount command while they are
 * in their assigned ICE RUSH boat. All other stopRiding calls remain vanilla,
 * so elimination, teleporting, remounting and round cleanup still work.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onClientCommand", at = @At("HEAD"), cancellable = true)
    private void icerush$keepActiveRiderSeated(ClientCommandC2SPacket packet, CallbackInfo callback) {
        if (packet.getMode() == ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY
            && IceRushMod.shouldPreventDismount(player)) {
            callback.cancel();
        }
    }
}
