package xyz.apollodorus.mcgf.client.render;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;

/**
 * 达妮娅's body model.
 *
 * <p>IMPORTANT: this is a plain {@link BipedEntityModel}, NOT a {@link PlayerEntityModel}.
 * When she was rendered as a player model (with a {@code PlayerEntityRenderState}) the
 * vanilla player-skin pipeline overrode her texture with the default Steve skin. A plain
 * biped paired with our own {@link GirlfriendRenderState} is treated like any other mob,
 * so her configured skin actually binds.
 *
 * <p>We still want Alex-style 3px ("thin") arms, so we reuse only the slim PLAYER model
 * <i>geometry</i> ({@code thinArms = true}), including the attached jacket, sleeves and
 * trousers; rendering goes through the biped path.
 */
public class GirlfriendEntityModel extends BipedEntityModel<GirlfriendRenderState> {
    public GirlfriendEntityModel(ModelPart root) {
        super(root);
    }

    public static TexturedModelData getTexturedModelData() {
        // thinArms = true → 3px (Alex/slim) arms with the standard 64x64 skin UVs.
        return TexturedModelData.of(PlayerEntityModel.getTexturedModelData(new Dilation(0.0f), true), 64, 64);
    }
}
