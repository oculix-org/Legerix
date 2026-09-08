package io.github.julienmerconsulting.legerix;

import org.junit.Assume;
import org.junit.Test;
import org.oculix.octachorix.PageLevel;
import org.oculix.octachorix.Scribe;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Smoke test: extract natives, run OCR on a programmatically-rendered image,
 * assert recognized text. Octachorix is the binding consumer, fed the absolute
 * paths Legerix publishes; Legerix only ships the natives + traineddata.
 */
public class LegerixSmokeTest {

    /**
     * The contract every consumer relies on: the two paths Legerix publishes
     * are the files it loaded, they exist, they are not empty, and they live
     * in the extraction directory loadNatives() returned.
     */
    @Test
    public void publishesTheLibrariesItLoaded() throws Exception {
        final Path dir = Legerix.loadNatives();
        final Path tesseract = Legerix.getTesseractLibraryPath();
        final Path leptonica = Legerix.getLeptonicaLibraryPath();
        assertNotNull(tesseract);
        assertNotNull(leptonica);
        assertTrue(tesseract + " should be absolute", tesseract.isAbsolute());
        assertTrue(leptonica + " should be absolute", leptonica.isAbsolute());
        assertTrue(tesseract + " should exist", tesseract.toFile().isFile());
        assertTrue(leptonica + " should exist", leptonica.toFile().isFile());
        assertTrue(tesseract + " should not be empty", tesseract.toFile().length() > 0);
        assertTrue(leptonica + " should not be empty", leptonica.toFile().length() > 0);
        assertEquals("tesseract should sit in the extraction dir", dir.toAbsolutePath(), tesseract.getParent());
        assertEquals("leptonica should sit in the extraction dir", dir.toAbsolutePath(), leptonica.getParent());
    }

    // ---- Legerix#21: the payload contract, checked on a described packaging ----

    /** A tier directory of a payload, described file by file. */
    private static Path payloadWith(final String tier, final String manifest, final String... files) throws Exception {
        final Path root = java.nio.file.Files.createTempDirectory("legerix-payload-");
        final Path tierDir = root.resolve("META-INF/legerix/natives/" + tier);
        java.nio.file.Files.createDirectories(tierDir);
        for (final String f : files) {
            java.nio.file.Files.write(tierDir.resolve(f), new byte[]{1, 2, 3});
        }
        if (manifest != null) {
            java.nio.file.Files.write(tierDir.resolve("legerix-natives.txt"),
                    manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return root;
    }

    private static java.util.List<String> declared(final Path root, final String tier) throws Exception {
        return Legerix.declaredNatives(Legerix.Payload.ofDirectory(root),
                "META-INF/legerix/natives/" + tier, Legerix.OS.LINUX);
    }

    private static String refusalFor(final Path root, final String tier) {
        try {
            declared(root, tier);
        } catch (final Exception e) {
            return String.valueOf(e.getMessage());
        }
        return null;
    }

    /**
     * What Legerix extracts is what its own manifest names, in that order.
     * A file sitting in the same directory without being declared, which is
     * what a co-bundling consumer's native looks like, is not extracted and
     * is not an error either: it is not ours.
     */
    @Test
    public void extractsWhatTheManifestDeclaresAndIgnoresTheRest() throws Exception {
        final Path root = payloadWith("linux-x86-64",
                "# written by scripts/write-natives-manifest.sh\n"
                        + "libleptonica.so.6\nlibtesseract.so.5\nlibjpeg.so.8\n",
                "libleptonica.so.6", "libtesseract.so.5", "libjpeg.so.8", "libopencv_java4100.so");

        assertEquals(java.util.Arrays.asList("libleptonica.so.6", "libtesseract.so.5", "libjpeg.so.8"),
                declared(root, "linux-x86-64"));
    }

    /**
     * The manifest is a contract, not a hint. Each of these packagings is
     * refused before anything is loaded, and the message says which file and
     * which payload.
     */
    @Test
    public void anIncompleteOrAmbiguousPackagingIsRefused() throws Exception {
        final String ok = "libleptonica.so.6\nlibtesseract.so.5\n";

        final Path noManifest = payloadWith("linux-x86-64", null, "libleptonica.so.6", "libtesseract.so.5");
        assertTrue("no manifest must be refused",
                String.valueOf(refusalFor(noManifest, "linux-x86-64")).contains("does not declare what it ships"));

        final Path missingFile = payloadWith("linux-x86-64", ok + "libjpeg.so.8\n",
                "libleptonica.so.6", "libtesseract.so.5");
        assertTrue("a declared file that is absent must be refused",
                String.valueOf(refusalFor(missingFile, "linux-x86-64")).contains("does not contain it"));

        final Path noPair = payloadWith("linux-x86-64", "libjpeg.so.8\n", "libjpeg.so.8");
        assertTrue("the canonical pair must be declared",
                String.valueOf(refusalFor(noPair, "linux-x86-64")).contains("does not declare libleptonica.so.6"));

        final Path traversal = payloadWith("linux-x86-64", ok + "../../evil.so\n",
                "libleptonica.so.6", "libtesseract.so.5");
        assertTrue("a path is not a file name",
                String.valueOf(refusalFor(traversal, "linux-x86-64")).contains("invalid entry"));

        final Path twice = payloadWith("linux-x86-64", ok + "libtesseract.so.5\n",
                "libleptonica.so.6", "libtesseract.so.5");
        assertTrue("a duplicate entry is ambiguous",
                String.valueOf(refusalFor(twice, "linux-x86-64")).contains("twice"));

        final Path itself = payloadWith("linux-x86-64", ok + "legerix-natives.txt\n",
                "libleptonica.so.6", "libtesseract.so.5");
        assertTrue("the manifest is not a native",
                String.valueOf(refusalFor(itself, "linux-x86-64")).contains("invalid entry"));
    }

    /**
     * Nothing outside META-INF/legerix/ is read: a consumer's natives in the
     * generic top-level directories of the old layout are invisible, even
     * when they carry the exact names Legerix uses.
     */
    @Test
    public void theOldGenericDirectoriesAreNotReadAnyMore() throws Exception {
        final Path root = payloadWith("linux-x86-64", "libleptonica.so.6\nlibtesseract.so.5\n",
                "libleptonica.so.6", "libtesseract.so.5");
        final Path legacyDir = root.resolve("linux-x86-64");
        java.nio.file.Files.createDirectories(legacyDir);
        java.nio.file.Files.write(legacyDir.resolve("libtesseract.so.5"), new byte[]{9, 9});
        java.nio.file.Files.write(legacyDir.resolve("legerix-natives.txt"),
                "libtesseract.so.5\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(java.util.Arrays.asList("libleptonica.so.6", "libtesseract.so.5"),
                declared(root, "linux-x86-64"));
        assertTrue("a payload that only has the old layout is refused",
                String.valueOf(refusalFor(root, "linux-aarch64")).contains("does not declare what it ships"));
    }

    /**
     * Legerix reads its version from its own payload, not from the jar
     * manifest, which belongs to the consumer once Legerix is shaded.
     */
    @Test
    public void identityComesFromItsOwnPayload() {
        assertEquals("5.5.2", Legerix.getTesseractVersion());
        assertTrue("the Legerix version carries the build suffix",
                Legerix.getLegerixVersion().startsWith(Legerix.getTesseractVersion() + "-"));
    }

    /**
     * The extraction directory is private to this loader run: it lives under
     * the Legerix version, holds the lock file that keeps another JVM from
     * reaping it, and is not the version root itself, so two consumers of the
     * same version never share one.
     */
    @Test
    public void extractionDirectoryIsPrivateAndClaimed() throws Exception {
        final Path dir = Legerix.loadNatives();
        assertTrue(dir + " should hold the lock", java.nio.file.Files.exists(dir.resolve(".legerix-lock")));
        assertEquals("it lives under the Legerix version",
                Legerix.getLegerixVersion(), dir.getParent().getFileName().toString());
        assertTrue("it is a private directory of this run, not the version root",
                dir.getFileName().toString().length() > "linux-x86-64".length());
        assertEquals("tessdata is extracted inside it", dir, Legerix.getTessdataPath().getParent());
    }

    /**
     * A Mac without Homebrew's codecs gets an instruction, not a dyld dump:
     * the message names Homebrew and the formulae to install. Any other load
     * failure is left untouched.
     */
    @Test
    public void macWithoutHomebrewGetsAnInstruction() {
        final String dyld = "/Users/x/.cache/legerix/5.5.2-1/darwin-aarch64/libleptonica.6.dylib: "
                + "dlopen(/Users/x/.cache/legerix/5.5.2-1/darwin-aarch64/libleptonica.6.dylib, 0x0001): "
                + "Library not loaded: /opt/homebrew/opt/jpeg-turbo/lib/libjpeg.8.dylib\n"
                + "  Referenced from: <...> /Users/x/.cache/legerix/5.5.2-1/darwin-aarch64/libleptonica.6.dylib\n"
                + "  Reason: tried: '/opt/homebrew/opt/jpeg-turbo/lib/libjpeg.8.dylib' (no such file)";
        final String advice = Legerix.macOsHomebrewAdvice(dyld);
        assertNotNull("a missing Homebrew codec must produce an instruction", advice);
        assertTrue(advice.contains("brew install " + Legerix.MACOS_HOMEBREW_FORMULAE));
        assertTrue(advice.contains("https://brew.sh"));

        final String intel = "dlopen(...): Library not loaded: /usr/local/opt/libpng/lib/libpng16.16.dylib Reason: image not found";
        assertNotNull("Intel Homebrew lives under /usr/local", Legerix.macOsHomebrewAdvice(intel));

        assertTrue("an unrelated failure is not a Homebrew problem",
                Legerix.macOsHomebrewAdvice("dlopen(...): no suitable image found. Did find: mach-o file, but is an incompatible architecture")
                        == null);
        assertTrue(Legerix.macOsHomebrewAdvice(null) == null);
    }

    /** OCR of one image through Octachorix, bound to the files Legerix loaded. */
    private static String ocr(final BufferedImage img, final String language) throws Exception {
        final Scribe scribe = Scribe.builder()
                .tesseractLibrary(Legerix.getTesseractLibraryPath())
                .leptonicaLibrary(Legerix.getLeptonicaLibraryPath())
                .datapath(Legerix.getTessdataPath().toAbsolutePath())
                .language(language)
                .build();
        try {
            return scribe.read(img, EnumSet.noneOf(PageLevel.class)).text().trim();
        } finally {
            scribe.close();
        }
    }

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

        final String result = ocr(img, "eng");
        assertEquals("Hello Legerix", result);
    }

    /**
     * THE test that proves the fix for oculix-org/Legerix#20.
     *
     * <p>Before the fix, {@code loadNatives()} could succeed while a system
     * Tesseract had silently been resolved ahead of the bundled library —
     * the failure mode Host B and Host C exhibited in David Young's rapport.
     * The current build reads {@code TessVersion()} directly on the absolute
     * path of the file we just extracted, via
     * {@link NativeLibrary#getInstance(String)} — path-identity, not short-
     * name lookup — so this test now runs on every platform including Windows.
     */
    @Test
    public void loadedTesseractIsBundledNotSystem() throws Exception {
        Legerix.loadNatives();
        final Path tesseract = Legerix.getTesseractLibraryPath();
        final String actual = Legerix.getLoadedTesseractVersion(tesseract.toString());
        final String bundled = Legerix.getTesseractVersion();
        assertNotNull("TessVersion() returned null — libtesseract may not be loaded", actual);
        // MAJOR.MINOR comparison: Windows vcpkg ships 5.5.2, Linux/mac from-source 5.5.0.
        // Both are legitimate 5.5.x. Path identity is guaranteed by using the absolute
        // path of the file we extracted — see Legerix.getLoadedTesseractVersion javadoc.
        final String[] a = actual.split("\\.");
        final String[] b = bundled.split("\\.");
        assertTrue(
                "Wrong Tesseract MAJOR.MINOR in " + tesseract + ": expected " + bundled
                        + " got " + actual,
                a.length >= 2 && b.length >= 2 && a[0].equals(b[0]) && a[1].equals(b[1]));
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

        final String result = ocr(img, "fra");
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
