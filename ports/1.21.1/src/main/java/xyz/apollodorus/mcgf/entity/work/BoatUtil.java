package xyz.apollodorus.mcgf.entity.work;

import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.math.Box;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.Comparator;
import java.util.List;

/** Boarding / leaving boats — shared by the command and the LLM tool. */
public final class BoatUtil {
    private BoatUtil() {}

    /** Board the nearest boat in the search area that will accept her. */
    public static boolean boardNearest(GirlfriendEntity gf, double radius) {
        if (gf.hasVehicle()) return true; // already aboard something
        Box box = gf.getBoundingBox().expand(radius);
        List<BoatEntity> boats = gf.getEntityWorld()
            .getEntitiesByClass(BoatEntity.class, box, b -> b.isAlive());
        if (boats.isEmpty()) return false;
        boats.sort(Comparator.comparingDouble(gf::squaredDistanceTo));
        gf.getNavigation().stop();
        for (BoatEntity boat : boats) {
            if (gf.startRiding(boat)) return true;
        }
        return false;
    }

    /** Get off whatever she's riding. Returns true if she actually was riding something. */
    public static boolean disembark(GirlfriendEntity gf) {
        if (!gf.hasVehicle()) return false;
        gf.stopRiding();
        return true;
    }
}
