package xyz.apollodorus.mcgf.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

@Mixin(LivingEntity.class)
abstract class OwnerDamageMixin {
    // The Fabric API shipped for 1.21 predates AFTER_DAMAGE. Its later implementation
    // also observes this tail, after shields have changed amount and early exits returned.
    @Inject(method = "damage", at = @At("TAIL"))
    private void mcgf$defendOwner(DamageSource source, float amount, CallbackInfoReturnable<Boolean> result) {
        if (!((Object) this instanceof ServerPlayerEntity player) || !player.isAlive() || amount <= 0) return;
        LivingEntity attacker = source.getAttacker() instanceof LivingEntity living ? living : null;
        for (GirlfriendEntity gf : GirlfriendEntity.ACTIVE) {
            if (gf.isAlive() && !gf.isRemoved() && gf.isOwner(player) && gf.getWorld() == player.getWorld()
                    && gf.squaredDistanceTo(player) <= 32.0 * 32.0) {
                gf.markOwnerAttacked(attacker);
            }
        }
    }
}
