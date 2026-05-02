package io.github.julienmerconsulting.legerix;

import com.sun.jna.NativeLibrary;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loader for the Tesseract + Leptonica natives bundled in this artifact.
 * Modeled after Apertix's {@code nu.pattern.OpenCV} loader.
 *
 * <p>Resources are laid out by JNA convention {@code <os>-<arch>/} on the
 * classpath. On Linux a glibc tier picker selects between the modern build
 * (built on Ubuntu 24.04, glibc &ge; 2.38) and the legacy build (built on
 * manylinux_2_28, glibc &ge; 2.28) at runtime.
 */
public final class Legerix {

    private static final Logger logger = Logger.getLogger(Legerix.class.getName());

    /**
     * Operating system family detected at runtime, matched against the
     * {@code os.name} system property.
     */
    public enum OS {
        /** Any Linux distribution (matches {@code os.name} = {@code Linux}). */
        LINUX("^[Ll]inux$"),
        /** Apple macOS (matches {@code os.name} = {@code Mac OS X}). */
        OSX("^[Mm]ac OS X$"),
        /** Microsoft Windows (matches any {@code os.name} starting with {@code Windows}). */
        WINDOWS("^[Ww]indows.*");

        private final Set<Pattern> patterns;

        OS(final String... patterns) {
            this.patterns = new HashSet<>();
            for (final String p : patterns) {
                this.patterns.add(Pattern.compile(p));
            }
        }

        boolean matches(final String osName) {
            for (final Pattern p : patterns) {
                if (p.matcher(osName).matches()) return true;
            }
            return false;
        }

        /**
         * Detects the current OS by matching the {@code os.name} system property
         * against the patterns of each enum value.
         *
         * @return the {@link OS} value matching the running JVM
         * @throws UnsupportedOperationException if {@code os.name} matches none
         *         of the supported families (Linux, macOS, Windows)
         */
        public static OS getCurrent() {
            final String osName = System.getProperty("os.name");
            for (final OS os : values()) {
                if (os.matches(osName)) return os;
            }
            throw new UnsupportedOperationException("Unsupported OS: " + osName);
        }
    }

    /**
     * CPU architecture detected at runtime, matched against the
     * {@code os.arch} system property.
     */
    public enum Arch {
        /** 64-bit Intel/AMD (matches {@code amd64} or {@code x86_64}). */
        X86_64("amd64", "x86_64"),
        /** 64-bit ARM (matches {@code aarch64} or {@code arm64}). */
        AARCH64("aarch64", "arm64");

        private final Set<String> ids;

        Arch(final String... ids) {
            this.ids = new HashSet<>(Arrays.asList(ids));
        }

        /**
         * Detects the current CPU architecture by reading the {@code os.arch}
         * system property.
         *
         * @return the {@link Arch} value matching the running JVM
         * @throws UnsupportedOperationException if {@code os.arch} is neither
         *         {@code amd64}/{@code x86_64} nor {@code aarch64}/{@code arm64}
         */
        public static Arch getCurrent() {
            final String osArch = System.getProperty("os.arch");
            for (final Arch a : values()) {
                if (a.ids.contains(osArch)) return a;
            }
            throw new UnsupportedOperationException("Unsupported arch: " + osArch);
        }
    }

    /**
     * Lightweight Tesseract {@code tessdata_fast} language models bundled in
     * the artifact. Covers approximately 80% of the world population by
     * primary spoken language: English, French, Spanish, Simplified Chinese
     * and Hindi. Consumers wanting other languages should drop additional
     * {@code *.traineddata} files alongside these in {@link #getTessdataPath()}.
     */
    public static final List<String> BUNDLED_LANGUAGES =
            Collections.unmodifiableList(Arrays.asList("eng", "fra", "spa", "chi_sim", "hin"));

    /** Cached, idempotent extraction directory. */
    private static volatile Path extractionDir;
    private static volatile String detectedTier;
    private static volatile boolean loaded;

    private Legerix() {}

    /**
     * Extracts the bundled Tesseract + Leptonica native libraries to a per-user
     * cache directory and loads them into the current JVM. Idempotent: a second
     * call returns the cached directory without re-extracting or re-loading.
     *
     * <p>On Linux, the appropriate glibc tier (modern vs legacy) is selected
     * automatically by inspecting {@code ldd --version}. On macOS and Windows
     * the only available variant is used.
     *
     * <p>The lightweight {@code tessdata_fast} language models shipped in the
     * artifact (see {@link #BUNDLED_LANGUAGES}) are also extracted alongside
     * the natives so that {@link #getTessdataPath()} can be passed directly
     * to a Tesseract API.
     *
     * @return the absolute path to the directory where the natives have been
     *         extracted (already loaded into the JVM)
     * @throws IOException if the cache directory cannot be created, a bundled
     *         resource is missing from the classpath, or extraction to disk
     *         fails (e.g. disk full, permission denied)
     */
    public static synchronized Path loadNatives() throws IOException {
        if (loaded) {
            return extractionDir;
        }

        final OS os = OS.getCurrent();
        final Arch arch = Arch.getCurrent();
        final String tier = detectGlibcTier(os);
        detectedTier = tier;
        final String resourceDir = resourceDirFor(os, arch, tier);

        final Path target = cacheDir().resolve(getTesseractVersion()).resolve(resourceDir);
        Files.createDirectories(target);

        for (final String lib : librariesFor(os)) {
            extractIfMissing(resourceDir + "/" + lib, target.resolve(lib));
        }

        // Windows: vcpkg links Tesseract dynamically against ~10 codec /
        // utility DLLs (libpng, tiff, jpeg62, libwebp, openjp2, zlib, gif,
        // libcurl, libarchive, …) that the loader resolves at runtime from
        // the directory containing tesseract55.dll. Extract every file
        // shipped under the resource directory, not just the two top-level
        // libraries hard-coded in librariesFor(WINDOWS).
        if (os == OS.WINDOWS) {
            extractAllFromResourceDir(resourceDir, target);
        }

        // tessdata: bundled lightweight (tessdata_fast) language models covering
        // ~80% of the world population. Consumers wanting other languages can
        // drop additional *.traineddata files alongside these in the same
        // cache directory (see getTessdataPath()).
        final Path tessdataDir = cacheDir().resolve(getTesseractVersion()).resolve("tessdata");
        Files.createDirectories(tessdataDir);
        for (final String lang : BUNDLED_LANGUAGES) {
            extractIfMissing("tessdata/" + lang + ".traineddata",
                    tessdataDir.resolve(lang + ".traineddata"));
        }

        // Make JNA find OUR libs by name, ahead of tess4j's bundled copies.
        // tess4j's static initializer extracts its own (older) leptonica to
        // /tmp/tess4j/ and prepends that path to jna.library.path. JNA's
        // per-library addSearchPath is consulted BEFORE jna.library.path, so
        // our path wins as long as the file name matches the libname regex
        // (libtesseract.so.5 matches lib<name>\.so(\.\d+)*).
        final String ours = target.toAbsolutePath().toString();
        NativeLibrary.addSearchPath("tesseract", ours);
        NativeLibrary.addSearchPath("leptonica", ours);
        NativeLibrary.addSearchPath("lept", ours);

        // Load via JNA's NativeLibrary.getInstance() instead of System.load().
        // This matters because:
        //   1. JNA loads with RTLD_GLOBAL on Linux/macOS, so leptonica's
        //      symbols (e.g. pixFindBaselinesGen, introduced in 1.85) become
        //      globally visible. Otherwise tess4j later loads its own older
        //      leptonica RTLD_GLOBAL and that one shadows ours, breaking
        //      tesseract 5.5+ which calls those new functions.
        //   2. JNA caches the NativeLibrary by name. When tess4j subsequently
        //      calls Native.loadLibrary("tesseract"), it gets OUR cached
        //      handle instead of triggering its own classpath extraction.
        //
        // Order matters: leptonica first (tesseract.so DT_NEEDED depends on
        // libleptonica.so.6, and we want OUR copy registered globally before
        // the dynamic linker resolves that dep).
        loadViaJna("leptonica", target, leptonicaFileName(os));
        loadViaJna("tesseract", target, tesseractFileName(os));

        extractionDir = target;
        loaded = true;

        logger.log(Level.FINE, "Legerix natives loaded from {0} (tier={1})",
                new Object[]{target, tier});
        return target;
    }

    /**
     * Returns the path to the extracted {@code tessdata} folder, suitable for
     * passing to a Tesseract instance. Triggers {@link #loadNatives()} if it
     * has not been called yet.
     *
     * @return the absolute path to the {@code tessdata} directory containing
     *         the bundled {@code *.traineddata} files (see {@link #BUNDLED_LANGUAGES})
     * @throws IllegalStateException if natives have not been loaded yet and
     *         the implicit {@link #loadNatives()} call fails (the underlying
     *         {@link IOException} is wrapped as cause)
     */
    public static Path getTessdataPath() {
        if (!loaded) {
            try {
                loadNatives();
            } catch (final IOException e) {
                throw new IllegalStateException("loadNatives() failed", e);
            }
        }
        return cacheDir().resolve(getTesseractVersion()).resolve("tessdata");
    }

    /**
     * Returns the glibc tier selected for this JVM run. Useful for diagnostics
     * (which native variant got loaded).
     *
     * @return {@code "modern"} on Linux with glibc &ge; 2.38, {@code "legacy"}
     *         on older Linux (or when glibc detection failed),
     *         {@code "n/a"} on non-Linux platforms
     */
    public static String getGlibcTier() {
        if (detectedTier != null) return detectedTier;
        return detectGlibcTier(OS.getCurrent());
    }

    /**
     * Returns the upstream Tesseract version embedded in this artifact, parsed
     * from the JAR's {@code Implementation-Version} manifest entry. The legerix
     * build suffix (e.g. {@code -1} in {@code 5.5.0-1}) is stripped.
     *
     * @return the Tesseract version string in {@code MAJOR.MINOR.PATCH} form
     *         (e.g. {@code "5.5.0"}); falls back to a hardcoded default when
     *         the manifest cannot be read (e.g. running from an exploded
     *         classpath without manifest)
     */
    public static String getTesseractVersion() {
        final String v = Legerix.class.getPackage().getImplementationVersion();
        if (v != null) {
            // Strip the build suffix (e.g. "5.5.0-1" -> "5.5.0").
            final int dash = v.indexOf('-');
            return dash > 0 ? v.substring(0, dash) : v;
        }
        return "5.5.0";
    }

    // -- internals ----------------------------------------------------------

    private static String resourceDirFor(final OS os, final Arch arch, final String tier) {
        switch (os) {
            case LINUX:
                final String base = "linux-" + (arch == Arch.X86_64 ? "x86-64" : "aarch64");
                return "legacy".equals(tier) ? base + "-legacy" : base;
            case OSX:
                return arch == Arch.X86_64 ? "darwin" : "darwin-aarch64";
            case WINDOWS:
                return "win32-x86-64";
            default:
                throw new UnsupportedOperationException("Unsupported OS: " + os);
        }
    }

    private static List<String> librariesFor(final OS os) {
        switch (os) {
            case LINUX:
                return Arrays.asList("libleptonica.so.6", "libtesseract.so.5");
            case OSX:
                return Arrays.asList("libleptonica.6.dylib", "libtesseract.5.dylib");
            case WINDOWS:
                // Leptonica DLL on Windows is named leptonica-<version>.dll
                // by vcpkg, where <version> tracks whatever leptonica vcpkg
                // ships at build time. We don't pin it here because vcpkg
                // controls that version independently.
                return Arrays.asList("leptonica-1.87.0.dll", "tesseract55.dll");
            default:
                throw new UnsupportedOperationException("Unsupported OS: " + os);
        }
    }

    private static String tesseractFileName(final OS os) {
        return librariesFor(os).get(1);
    }

    private static String leptonicaFileName(final OS os) {
        return librariesFor(os).get(0);
    }

    private static String detectGlibcTier(final OS os) {
        if (os != OS.LINUX) return "n/a";
        final String version = readGlibcVersion();
        if (version == null) {
            logger.log(Level.FINE, "Could not detect glibc version, defaulting to legacy tier");
            return "legacy";
        }
        return compareVersion(version, "2.38") >= 0 ? "modern" : "legacy";
    }

    private static String readGlibcVersion() {
        // Try `ldd --version` first (no JNI needed, works on all Linux distros).
        try {
            final Process p = new ProcessBuilder("ldd", "--version").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                final String line = r.readLine();
                p.waitFor();
                if (line != null) {
                    final Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)\\s*$").matcher(line);
                    if (m.find()) return m.group(1);
                }
            }
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.log(Level.FINE, "ldd --version failed", e);
        }
        return null;
    }

    private static int compareVersion(final String a, final String b) {
        final String[] pa = a.split("\\.");
        final String[] pb = b.split("\\.");
        final int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            final int ai = i < pa.length ? parseIntSafe(pa[i]) : 0;
            final int bi = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static int parseIntSafe(final String s) {
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    private static Path cacheDir() {
        final String home = System.getProperty("user.home");
        final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            final String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                return Paths.get(localAppData, "legerix");
            }
        }
        return Paths.get(home, ".cache", "legerix");
    }

    /**
     * Extract every file directly under {@code resourceDir/} from the
     * containing JAR into {@code target}. Used on Windows to ship the full
     * vcpkg DLL closure (codecs + transitive deps) without having to
     * enumerate them by name.
     *
     * <p>Falls back to a no-op if the classpath entry is a plain directory
     * (exploded build, IDE) since in that case files are loaded directly
     * by {@link com.sun.jna.NativeLibrary} from the classpath search path
     * and don't need extracting.
     */
    private static void extractAllFromResourceDir(final String resourceDir, final Path target)
            throws IOException {
        final URL marker = Legerix.class.getClassLoader().getResource(resourceDir + "/");
        if (marker == null) return;
        if (!"jar".equals(marker.getProtocol())) {
            // Exploded classpath: nothing to do (files already on disk).
            return;
        }
        final JarURLConnection conn = (JarURLConnection) marker.openConnection();
        conn.setUseCaches(false);
        try (JarFile jar = conn.getJarFile()) {
            final String prefix = resourceDir + "/";
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final JarEntry e = entries.nextElement();
                final String name = e.getName();
                if (e.isDirectory() || !name.startsWith(prefix)) continue;
                final String tail = name.substring(prefix.length());
                if (tail.isEmpty() || tail.indexOf('/') >= 0) continue;
                extractIfMissing(name, target.resolve(tail));
            }
        }
    }

    private static void extractIfMissing(final String resource, final Path target) throws IOException {
        if (Files.exists(target)) return;
        try (InputStream in = Legerix.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Resource not found in classpath: " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Load a library via JNA (RTLD_GLOBAL on Linux/macOS) so symbols are
     * visible to the rest of the process and JNA caches the handle by name.
     * If JNA can't resolve the name (e.g. because the on-disk file uses a
     * versioned name JNA's regex doesn't match), fall back to a direct
     * dlopen-by-path via System.load so we still load *something*.
     */
    private static void loadViaJna(final String jnaName, final Path dir, final String fileName) {
        try {
            NativeLibrary.getInstance(jnaName);
            return;
        } catch (final UnsatisfiedLinkError e) {
            logger.log(Level.FINE, "JNA could not resolve \"" + jnaName + "\" by name, "
                    + "falling back to System.load on the absolute file path", e);
        }
        final Path p = dir.resolve(fileName);
        try {
            System.load(p.toAbsolutePath().toString());
        } catch (final UnsatisfiedLinkError e) {
            logger.log(Level.SEVERE, "Failed to load native library " + p, e);
            throw e;
        }
    }
}
