package xyz.apollodorus.mcgf.entity;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class KeepsakeGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void backpackAndGearSurviveSavingCaptureAndDeath(TestContext test) {
        GirlfriendEntity gf = test.spawnEntity(GirlfriendEntities.GIRLFRIEND, 2, 2, 2);
        ServerPlayerEntity owner = test.createMockCreativeServerPlayerInWorld();
        owner.setPosition(gf.getPos());
        gf.setOwnerUuid(owner.getUuid());
        ItemStack keepsake = new ItemStack(Items.DIAMOND_PICKAXE);
        keepsake.set(DataComponentTypes.CUSTOM_NAME, Text.literal("一起挖到的第一颗钻石"));
        keepsake.setDamage(37);
        keepsake.addEnchantment(test.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT)
            .entryOf(Enchantments.UNBREAKING), 2);
        ItemStack box = new ItemStack(Items.BLUE_SHULKER_BOX);
        box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(List.of(ItemStack.EMPTY, keepsake.copy())));
        gf.getInventory().setStack(26, box.copy());
        gf.equipStack(EquipmentSlot.MAINHAND, keepsake.copy());

        var view = new NbtCompound();
        gf.writeCustomDataToNbt(view);
        GirlfriendEntity loaded = test.spawnEntity(GirlfriendEntities.GIRLFRIEND, 3, 2, 2);
        loaded.readCustomDataFromNbt(view);
        verifyKeepsakes(test, loaded, box, keepsake);
        gf.discard();

        test.assertTrue(ReviveService.capture(owner, loaded), "Capture should persist her inventory");
        DownedManager.load(test.getWorld().getServer());
        GirlfriendEntity released = ReviveService.release(test.getWorld(), owner);
        test.assertTrue(released != null, "Release should work after reloading the bond store");
        verifyKeepsakes(test, released, box, keepsake);
        test.assertTrue(released.damage(test.getWorld().getDamageSources().outOfWorld(), Float.MAX_VALUE) && released.isDead(),
            "A real lethal hit must take the companion down");
        test.assertTrue(released.getEquippedStack(EquipmentSlot.MAINHAND).isEmpty(), "Saved equipment must not also drop");
        DownedManager.load(test.getWorld().getServer());
        GirlfriendEntity revived = ReviveService.revive(test.getWorld(), owner);
        test.assertTrue(revived != null, "Death snapshot should survive store reload");
        verifyKeepsakes(test, revived, box, keepsake);
        revived.discard();
        test.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "denia_domain")
    public void domainSwitchesAppearanceAndRevertsOnCapture(TestContext test) {
        GirlfriendEntity gf = test.spawnEntity(GirlfriendEntities.GIRLFRIEND, 2, 2, 2);
        var owner = test.createMockCreativeServerPlayerInWorld();
        gf.setOwnerUuid(owner.getUuid());
        test.assertFalse(gf.isFormTwo(), "New companion starts in the first form");
        test.assertTrue(gf.tryDeployDomain() && gf.isFormTwo(), "Deploying the domain enables the second form");
        test.assertTrue(ReviveService.capture(owner, gf), "Capture the active companion");
        var released = ReviveService.release(test.getWorld(), owner);
        test.assertTrue(released != null && !released.isFormTwo(), "The released companion must return to the first form");
        released.discard();
        test.complete();
    }

    private static void verifyKeepsakes(TestContext test, GirlfriendEntity gf, ItemStack box, ItemStack tool) {
        test.assertTrue(gf.getInventory().getStack(0).isEmpty(), "Empty backpack slots must stay empty");
        test.assertTrue(ItemStack.areEqual(gf.getInventory().getStack(26), box), "Shulker box and its nested items must stay in slot 26");
        test.assertTrue(ItemStack.areEqual(gf.getEquippedStack(EquipmentSlot.MAINHAND), tool), "Equipped tool lost its name, enchantment or damage");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void legacyWorldAndCharmRemainReadable(TestContext test) {
        var view = new NbtCompound();
        view.putString("Inv", "minecraft:apple 4;minecraft:iron_ingot 2");
        GirlfriendEntity gf = test.spawnEntity(GirlfriendEntities.GIRLFRIEND, 2, 2, 2);
        gf.readCustomDataFromNbt(view);
        test.assertTrue(gf.countItem(Items.APPLE) == 4 && gf.countItem(Items.IRON_INGOT) == 2, "Legacy world inventory failed to load");

        ServerPlayerEntity owner = test.createMockCreativeServerPlayerInWorld();
        owner.setPosition(gf.getPos());
        var snapshot = new DownedManager.Snapshot();
        snapshot.inventory = "minecraft:bread 3";
        snapshot.equipment = Map.of("offhand", "minecraft:shield 1");
        test.assertTrue(DownedManager.setStored(owner.getUuid(), snapshot), "Legacy snapshot must persist");
        GirlfriendEntity released = ReviveService.release(test.getWorld(), owner);
        test.assertTrue(released != null && released.countItem(Items.BREAD) == 3, "Legacy charm inventory failed to load");
        test.assertTrue(released.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.SHIELD), "Legacy equipped shield lost");
        gf.discard();
        released.discard();

        var incomplete = new DownedManager.Snapshot();
        incomplete.belongings = "{}";
        test.assertTrue(DownedManager.setStored(owner.getUuid(), incomplete), "Test snapshot should persist");
        test.assertTrue(ReviveService.release(test.getWorld(), owner) == null && DownedManager.isStored(owner.getUuid()),
            "An unreadable snapshot must stay in the charm");
        DownedManager.clearStored(owner.getUuid());
        var missingItem = new DownedManager.Snapshot();
        missingItem.belongings = "{Backpack:{Items:[{Slot:4b,id:'missingmod:keepsake',count:1}]},Equipment:{}}";
        test.assertTrue(DownedManager.setStored(owner.getUuid(), missingItem), "Save the missing-item snapshot");
        test.assertTrue(ReviveService.release(test.getWorld(), owner) == null && DownedManager.isStored(owner.getUuid()),
            "A missing item must preserve the snapshot for recovery");
        DownedManager.clearStored(owner.getUuid());
        test.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void storedCharmsStayWithTheirWorld(TestContext test) throws IOException {
        Path root = Files.createTempDirectory("denia-bond-test");
        Path first = root.resolve("first");
        Path second = root.resolve("second");
        Path legacy = root.resolve("legacy.json");
        UUID owner = UUID.randomUUID();
        String record = "{\"stored\":{\"" + owner + "\":{\"inventory\":\"minecraft:bread 3\"}}}";
        try {
            Files.createDirectories(first);
            Files.createFile(first.resolve("level.dat"));
            Files.writeString(legacy, record);
            DownedManager.load(first, legacy);
            test.assertTrue(DownedManager.isStored(owner) && Files.notExists(legacy), "The only original world should inherit the old snapshot once");

            Files.createDirectories(second);
            Files.createFile(second.resolve("level.dat"));
            Files.writeString(legacy, record);
            DownedManager.load(second, legacy);
            test.assertTrue(!DownedManager.isStored(owner) && Files.exists(legacy), "An unrelated world must not inherit ambiguous legacy records");
            DownedManager.load(first, legacy);
            test.assertTrue(DownedManager.isStored(owner), "Switching worlds must preserve the original companion");
        } finally {
            DownedManager.load(test.getWorld().getServer());
            Files.deleteIfExists(legacy);
            Files.deleteIfExists(first.resolve("mcgf-downed.json"));
            Files.deleteIfExists(first.resolve("level.dat"));
            Files.deleteIfExists(second.resolve("level.dat"));
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
            Files.deleteIfExists(root);
        }
        test.complete();
    }
}
