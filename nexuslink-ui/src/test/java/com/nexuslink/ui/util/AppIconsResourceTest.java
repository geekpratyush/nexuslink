package com.nexuslink.ui.util;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The application icon is loaded by name at runtime, so a renamed or missing PNG would only show up
 * as a window with the default JavaFX icon. This checks the resources themselves — no JavaFX
 * toolkit needed, so it runs in a headless build.
 */
class AppIconsResourceTest {

    private static final int[] SIZES = {16, 32, 48, 64, 128, 256, 512};

    @Test
    void everyIconSizeIsOnTheClasspath() {
        for (int size : SIZES) {
            String path = "/com/nexuslink/ui/images/icon-" + size + ".png";
            try (InputStream in = getClass().getResourceAsStream(path)) {
                assertNotNull(in, path + " is missing — regenerate it from images/logo.svg");
                byte[] header = in.readNBytes(8);
                assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, header,
                        path + " is not a PNG");
            } catch (Exception e) {
                fail("could not read " + path + ": " + e.getMessage());
            }
        }
    }

    @Test
    void theSourceMarkShipsBesideThePngs() {
        try (InputStream in = getClass().getResourceAsStream("/com/nexuslink/ui/images/logo.svg")) {
            assertNotNull(in, "logo.svg is the source the PNGs are rendered from");
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }
}
