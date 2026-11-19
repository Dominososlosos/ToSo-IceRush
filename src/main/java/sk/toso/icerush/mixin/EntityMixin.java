package sk.toso.icerush.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sk.toso.icerush.IceRushMod;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "stopRiding", at = @At("HEAD"), cancellable = true)
    private void icerush$keepActivePlayerInBoat(CallbackInfo callback) {
        if ((Object) this instanceof ServerPlayerEntity player
            && IceRushMod.shouldPreventDismount(player)) {
            callback.cancel();
        }
    }
}
