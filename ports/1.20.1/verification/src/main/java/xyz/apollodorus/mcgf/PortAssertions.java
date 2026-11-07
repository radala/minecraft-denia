package xyz.apollodorus.mcgf;

import java.util.Objects;
import net.minecraft.test.TestContext;

public final class PortAssertions {
    private PortAssertions() {}

    public static void assertEquals(TestContext test, Object expected, Object actual, String message) {
        test.assertTrue(Objects.equals(expected, actual), message + " (expected " + expected + ", got " + actual + ")");
    }
}
