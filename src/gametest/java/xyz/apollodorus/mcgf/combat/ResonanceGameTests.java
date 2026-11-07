package xyz.apollodorus.mcgf.combat;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.GameTestPlayers;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;

public final class ResonanceGameTests {
    @GameTest(maxTicks = 280)
    public void firstFormWaveSparesItsOwnerBesideTheTarget(TestContext test) {
        var world = test.getWorld();
        var owner = GameTestPlayers.survivalPlayer(test, "WaveOwner");

        test.setBlockState(new BlockPos(2, 1, 2), Blocks.STONE);
        test.setBlockState(new BlockPos(2, 1, 5), Blocks.STONE);
        test.setBlockState(new BlockPos(3, 1, 5), Blocks.STONE);
        var caster = test.spawnMob(GirlfriendEntities.GIRLFRIEND, 2, 2, 2);
        caster.setAiDisabled(true);
        caster.setOwnerUuid(owner.getUuid());
        Vec3d source = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(2, 2, 2)));
        Vec3d targetPos = source.add(0, 0, 3);
        Vec3d ownerPos = targetPos.add(1.4, 0, 0);

        // Allow the production skill's 200-tick cooldown to elapse in a fresh world.
        test.runAtTick(230, () -> {
            var zombie = test.spawnMob(EntityType.ZOMBIE, 2, 2, 5);
            zombie.setAiDisabled(true);
            zombie.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            caster.refreshPositionAndAngles(source.x, source.y, source.z, 0, 0);
            zombie.setPosition(targetPos);
            owner.refreshPositionAndAngles(ownerPos.x, ownerPos.y, ownerPos.z, 180, 0);
            owner.setHealth(owner.getMaxHealth());
            owner.setVelocity(Vec3d.ZERO);
            caster.setTarget(zombie);
            test.assertTrue(caster.isOwner(owner) && !caster.isFormTwo() && !caster.isTeammate(owner),
                    "A first-form companion must recognize her owner without a scoreboard team");
            test.assertTrue(zombie.isAlive() && zombie.getHealth() == zombie.getMaxHealth()
                    && world.getOtherEntities(zombie, zombie.getBoundingBox().expand(2.5)).contains(owner),
                    "The owner and a healthy zombie must both be in the wave's path");
            test.assertTrue(DaniyaCombatEngine.tryCastResonanceSkill(world, caster, zombie), "Resonance did not cast");
            test.waitAndRun(5, () -> {
                test.assertTrue(zombie.isAlive() && zombie.getHealth() < zombie.getMaxHealth()
                        && zombie.hasStatusEffect(StatusEffects.SLOWNESS),
                        "The zombie in the first wave must still take damage and be slowed");
                test.assertTrue(owner.getHealth() == owner.getMaxHealth() && !owner.hasStatusEffect(StatusEffects.SLOWNESS),
                        "The same wave must spare the owner's health and movement speed");
                test.assertTrue(owner.getEntityPos().subtract(ownerPos).horizontalLengthSquared() < 0.0001,
                        "The first wave must not knock the owner back");
                world.getServer().getPlayerManager().remove(owner);
                test.complete();
            });
        });
    }
}
