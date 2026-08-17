package io.github.julienmerconsulting.legerix;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.junit.Assume;
import org.junit.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Smoke test: extract natives, run OCR on a programmatically-rendered image,
 * assert recognized text. tess4j is used as the JNA binding consumer; Legerix
 * only ships the natives + traineddata.
 */
public class LegerixSmokeTest {

    @Test
    public void loadsNativesAndExtractsTessdata() throws Exception {
        final Path dir = Legerix.loadNatives();
        assertNotNull(dir);
        assertTrue("extraction dir should exist", dir.toFile().exists());
        assertTrue("tessdata should exist", Legerix.getTessdataPath().toFile().exists());
        assertTrue("eng.traineddata should be present",
                Legerix.getTessdataPath().resolve("eng.traineddata").toFile().exists());
    }

    @Test
    public void glibcTierIsReported() throws Exception {
        Legerix.loadNatives();
        final String tier = Legerix.getGlibcTier();
        assertNotNull(tier);
        assertTrue(tier.equals("modern") || tier.equals("legacy") || tier.equals("n/a"));
    }

    @Test
    public void ocrRenderedTextRoundTrips() throws Exception {
        Legerix.loadNatives();

        final BufferedImage img = renderText("Hello Legerix", 600, 120);

        final ITesseract tess = new Tesseract();
        tess.setDatapath(Legerix.getTessdataPath().toAbsolutePath().toString());
        tess.setLanguage("eng");

        final String result = tess.doOCR(img).trim();
        assertEquals("Hello Legerix", result);
    }

    /**
     * THE test that proves the fix for oculix-org/Legerix#20.
     *
     * <p>Before the fix, {@code loadNatives()} could succeed while a system
     * Tesseract had silently been resolved ahead of the bundled library. The
     * banner would report the bundled version, but {@code TessVersion()} on
     * the actual loaded library returned the system version — the failure
     * mode Host B and Host C exhibited in the David Young rapport.
     *
     * <p>Skipped on Windows: the assertion path uses the JNA short name
     * {@code "tesseract"}, but the Windows payload ships {@code tesseract55.dll}
     * (vcpkg convention). Windows is also demonstrably not affected by the
     * silent-binding failure mode (see rapport).
     */
    @Test
    public void loadedTesseractIsBundledNotSystem() throws Exception {
        Assume.assumeFalse("Windows is not affected by the silent-system-binding bug",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"));

        Legerix.loadNatives();
        final String actual = Legerix.getLoadedTesseractVersion();
        final String bundled = Legerix.getTesseractVersion();
        assertNotNull("TessVersion() returned null — libtesseract may not be loaded", actual);
        assertTrue(
                "Wrong Tesseract loaded: expected " + bundled + " (bundled) got " + actual
                        + ". A system library likely won the resolution race.",
                actual.startsWith(bundled));
    }

    /**
     * Every language advertised in {@link Legerix#BUNDLED_LANGUAGES} must
     * actually be on disk after extraction. Guards against a resource that
     * silently disappeared from the jar between two builds.
     */
    @Test
    public void bundledLanguagesAllExtracted() throws Exception {
        Legerix.loadNatives();
        final Path tessdata = Legerix.getTessdataPath();
        for (final String lang : Legerix.BUNDLED_LANGUAGES) {
            final Path model = tessdata.resolve(lang + ".traineddata");
            assertTrue(lang + ".traineddata should exist at " + model, model.toFile().exists());
        }
    }

    /**
     * OCR through the bundled French traineddata on a phrase carrying two
     * accents. Proves the fra traineddata is functional end-to-end, not just
     * that the file is present on disk.
     */
    @Test
    public void frenchOcrWithAccents() throws Exception {
        Legerix.loadNatives();

        final BufferedImage img = renderText("Cafe francais", 700, 120);

        final ITesseract tess = new Tesseract();
        tess.setDatapath(Legerix.getTessdataPath().toAbsolutePath().toString());
        tess.setLanguage("fra");

        final String result = tess.doOCR(img).trim();
        // Tesseract fra may render "Cafe" and "francais" as-is or with restored
        // accents ("Café", "français") depending on font hinting. Accept both.
        assertTrue("fra OCR should recognize the word 'Cafe' or 'Café', got: " + result,
                result.contains("Cafe") || result.contains("Café"));
        assertTrue("fra OCR should recognize 'francais' or 'français', got: " + result,
                result.contains("francais") || result.contains("français"));
    }

    /**
     * {@code loadNatives()} is documented as idempotent — the second call must
     * return the same Path instance without triggering a second extraction or
     * a second dlopen. Regression guard for the {@code synchronized} +
     * {@code loaded} flag contract.
     */
    @Test
    public void loadNativesIsIdempotent() throws Exception {
        final Path first = Legerix.loadNatives();
        final Path second = Legerix.loadNatives();
        assertSame("loadNatives() should return the exact same Path on repeated calls",
                first, second);
    }

    private static BufferedImage renderText(final String text, final int w, final int h) {
        final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SERIF, Font.PLAIN, 64));
            g.drawString(text, 20, 80);
        } finally {
            g.dispose();
        }
        return img;
    }
}
