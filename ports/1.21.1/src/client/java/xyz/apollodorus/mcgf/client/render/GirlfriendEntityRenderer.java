package xyz.apollodorus.mcgf.client.render;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.client.MCGirlfriendClient;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

public class GirlfriendEntityRenderer extends BipedEntityRenderer<GirlfriendEntity, GirlfriendEntityModel> {
    public GirlfriendEntityRenderer(EntityRendererFactory.Context context) {
        super(context, new GirlfriendEntityModel(context.getPart(ModEntityModelLayers.GIRLFRIEND)), 0.5f);
        addFeature(new DomainOutfitFeature(this, context.getResourceManager()));
    }

    @Override
    public Identifier getTexture(GirlfriendEntity entity) {
        return entity.isFormTwo() ? MCGirlfriendClient.SKIN2 : MCGirlfriendClient.SKIN;
    }
}
