package io.github.julienmerconsulting.legerix;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.junit.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
