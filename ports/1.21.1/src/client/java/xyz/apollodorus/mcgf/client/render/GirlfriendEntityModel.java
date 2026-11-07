package xyz.apollodorus.mcgf.client.render;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Arm;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

/** In 1.21 the jacket and sleeves are separate model parts; the slim player model poses them. */
public class GirlfriendEntityModel extends PlayerEntityModel<GirlfriendEntity> {
    public GirlfriendEntityModel(ModelPart root) {
        super(root, true);
    }

    public static TexturedModelData getTexturedModelData() {
        return TexturedModelData.of(PlayerEntityModel.getTexturedModelData(new Dilation(0), true), 64, 64);
    }

    @Override
    public void setAngles(GirlfriendEntity entity, float limbAngle, float limbDistance, float age,
                          float headYaw, float headPitch) {
        rightArmPose = armPose(entity, Arm.RIGHT);
        leftArmPose = armPose(entity, Arm.LEFT);
        sneaking = entity.isInSneakingPose();
        super.setAngles(entity, limbAngle, limbDistance, age, headYaw, headPitch);
    }

    private static ArmPose armPose(GirlfriendEntity entity, Arm arm) {
        ItemStack stack = entity.getMainArm() == arm ? entity.getMainHandStack() : entity.getOffHandStack();
        if (stack.isOf(Items.FISHING_ROD)) return ArmPose.ITEM;
        if (entity.isAttacking()) {
            if (stack.isOf(Items.BOW)) return ArmPose.BOW_AND_ARROW;
            if (stack.isOf(Items.CROSSBOW)) return ArmPose.CROSSBOW_HOLD;
        }
        return ArmPose.EMPTY;
    }
}
