package xyz.apollodorus.mcgf.verify;

import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;

public final class OwnerDamageTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void onlyTheInjuredPlayersCompanionRushes(TestContext test) {
        var owner = test.createMockCreativeServerPlayerInWorld();
        var own = test.spawnEntity(GirlfriendEntities.GIRLFRIEND, 1, 2, 1);
        var other = test.spawnEntity(GirlfriendEntities.GIRLFRIEND, 2, 2, 1);
        owner.refreshPositionAndAngles(own.getX(), own.getY(), own.getZ(), 0, 0);
        own.setOwnerUuid(owner.getUuid());
        other.setOwnerUuid(UUID.randomUUID());
        test.waitAndRun(2, () -> {
            owner.setInvulnerable(true);
            owner.damage(owner.getDamageSources().generic(), 1);
            test.assertFalse(own.isRushingToOwner(), "Rejected damage triggered the owner's companion");
            owner.setInvulnerable(false);
            float health = owner.getHealth();
            // New players have spawn immunity; vanilla void damage legitimately bypasses it.
            test.assertTrue(owner.damage(owner.getDamageSources().outOfWorld(), 1), "Fixture did not inflict damage");
            test.assertTrue(owner.getHealth() < health, "Owner must actually lose health");
            test.assertTrue(own.isRushingToOwner(), "Owner's companion ignored actual damage");
            test.assertFalse(other.isRushingToOwner(), "Another player's companion reacted to this damage");
            own.discard();
            other.discard();
            test.complete();
        });
    }
}
