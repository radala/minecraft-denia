package xyz.apollodorus.mcgf.client.render;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import org.joml.Quaternionf;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

/** Waist-mounted cloth, hair tips and brooch worn in the domain form. */
final class DomainOutfitFeature extends FeatureRenderer<GirlfriendEntity, GirlfriendEntityModel> {
    private static final Identifier SHAPE = Identifier.of("mcgf", "models/entity/domain_outfit.json");
    private static final Identifier TEXTURE = Identifier.of("mcgf", "textures/entity/domain_outfit.png");
    private static final float RAD = (float) (Math.PI / 180);

    private enum Motion { FIXED, APRON, LEFT_TAIL, RIGHT_TAIL, LEFT_OVERLAY, RIGHT_OVERLAY, HAIR, HAIR_TIP }
    private record Box(float[] box, Map<String, float[]> faces) {}
    private record Group(Motion motion, float[] pivot, float[] rotationDeg, List<Box> boxes) {}
    private record Design(int width, int height, List<Group> groups) {}
    private record Piece(Group group, ModelPart mesh) {}
    private final List<Piece> pieces = new ArrayList<>();
    private final Group hairRoot;

    DomainOutfitFeature(FeatureRendererContext<GirlfriendEntity, GirlfriendEntityModel> context,
                        ResourceManager resources) {
        super(context);
        try (Reader file = resources.openAsReader(SHAPE)) {
            Design design = new Gson().fromJson(file, Design.class);
            hairRoot = design.groups().stream().filter(g -> g.motion() == Motion.HAIR).findFirst().orElse(null);
            for (Group group : design.groups()) {
                List<ModelPart.Cuboid> boxes = group.boxes().stream()
                        .map(box -> bake(box, design.width(), design.height())).toList();
                pieces.add(new Piece(group, new ModelPart(boxes, Map.of())));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not load " + SHAPE, ex);
        }
    }

    private static ModelPart.Cuboid bake(Box spec, int width, int height) {
        float[] b = spec.box();
        ModelPart.Cuboid cube = new ModelPart.Cuboid(0, 0, b[0], b[1], b[2], b[3], b[4], b[5],
                0, 0, 0, false, width, height, EnumSet.allOf(Direction.class));
        for (int i = 0; i < cube.sides.length; i++) {
            ModelPart.Quad face = cube.sides[i];
            var normal = face.direction;
            Direction direction = Direction.getFacing(normal.x(), normal.y(), normal.z());
            // A biped's model-space Y axis points from its head to its feet.
            String side = switch (direction) {
                case NORTH -> "front";
                case SOUTH -> "back";
                case WEST -> "right";
                case EAST -> "left";
                case DOWN -> "top";
                case UP -> "bottom";
            };
            float[] uv = spec.faces().get(side);
            cube.sides[i] = new ModelPart.Quad(face.vertices, uv[0], uv[1], uv[2], uv[3],
                    width, height, false, direction);
        }
        return cube;
    }

    private static float tailBend(float legPitch, float speed) {
        // Ease through neutral so the hem settles between steps without a sudden reversal.
        float back = Math.max(0, legPitch);
        return Math.min(.86f, .78f * back * back / (back + .08f)) + .035f * speed;
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider consumers, int light,
                       GirlfriendEntity entity, float limbAngle, float limbDistance, float tickDelta,
                       float age, float headYaw, float headPitch) {
        if (!entity.isFormTwo()) return;
        RenderLayer layer = RenderLayer.getEntityCutoutNoCull(TEXTURE);
        float alpha = 1;
        if (entity.isInvisible()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            if (entity.isInvisibleTo(client.player)) {
                if (!client.hasOutline(entity)) return;
                layer = RenderLayer.getOutline(TEXTURE);
            } else {
                layer = RenderLayer.getItemEntityTranslucentCull(TEXTURE);
                alpha = 0x26 / 255f;
            }
        }
        var vertices = consumers.getBuffer(layer);
        GirlfriendEntityModel pose = getContextModel();
        int overlay = LivingEntityRenderer.getOverlay(entity, 0);
        float speed = MathHelper.clamp(limbDistance, 0, 1);
        // The biped gait uses this phase for both ground travel and horizontal hovering.
        float stride = speed * (float) Math.sin(limbAngle * .6662f);
        float turn = entity.getOutfitTurn(tickDelta) * RAD;
        float lookYaw = pose.head.yaw - pose.body.yaw;
        float lookPitch = pose.head.pitch - pose.body.pitch;
        Quaternionf hairMotion = new Quaternionf().rotationZYX(
                MathHelper.clamp(-.085f * lookYaw + .025f * stride, -.13f, .13f),
                MathHelper.clamp(.22f * lookYaw - .12f * turn, -.29f, .29f),
                MathHelper.clamp(.14f * lookPitch + .04f * speed, -.07f, .16f));
        matrices.push();
        pose.body.rotate(matrices);
        for (Piece piece : pieces) {
            Group group = piece.group();
            float[] pivot = group.pivot(), rotation = group.rotationDeg();
            float pitch = rotation[0] * RAD;
            float yaw = rotation[1] * RAD;
            float roll = rotation[2] * RAD;
            matrices.push();
            switch (group.motion()) {
                case APRON -> pitch += MathHelper.clamp(Math.min(pose.leftLeg.pitch, pose.rightLeg.pitch) * .66f, -.74f, 0);
                case LEFT_TAIL, RIGHT_TAIL, LEFT_OVERLAY, RIGHT_OVERLAY -> {
                    boolean left = group.motion() == Motion.LEFT_TAIL || group.motion() == Motion.LEFT_OVERLAY;
                    pitch += tailBend(left ? pose.leftLeg.pitch : pose.rightLeg.pitch, speed);
                    if (group.motion() == Motion.LEFT_OVERLAY || group.motion() == Motion.RIGHT_OVERLAY) {
                        // Opposite phases give the short outer panels a slight delay on their return stroke.
                        float side = left ? -1 : 1;
                        pitch += .05f * speed + side * .042f * stride;
                        roll += side * (.025f * speed + .08f * Math.abs(turn));
                        yaw -= .13f * turn;
                    }
                }
                case HAIR, HAIR_TIP -> {
                    // Both groups have absolute rest poses in Blockbench. Inherit only the root's motion.
                    if (hairRoot != null) {
                        float[] root = hairRoot.pivot();
                        matrices.multiply(hairMotion, root[0] / 16, root[1] / 16, root[2] / 16);
                    }
                    if (group.motion() == Motion.HAIR_TIP) {
                        // Bend around the joint at the end of the upper lock. The free end trails a turn.
                        yaw += MathHelper.clamp(.06f * lookYaw - .45f * turn, -.19f, .19f);
                        roll += .52f * turn - .035f * stride;
                        pitch += MathHelper.clamp(.085f * lookPitch + .045f * speed, -.04f, .10f);
                    }
                }
                case FIXED -> { }
            }
            matrices.translate(pivot[0] / 16, pivot[1] / 16, pivot[2] / 16);
            matrices.multiply(new Quaternionf().rotationZYX(roll, yaw, pitch));
            piece.mesh().render(matrices, vertices, light, overlay, 1, 1, 1, alpha);
            matrices.pop();
        }
        matrices.pop();
    }
}
