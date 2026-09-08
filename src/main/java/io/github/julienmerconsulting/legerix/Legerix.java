package io.github.julienmerconsulting.legerix;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.util.HashMap;
import java.util.Map;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
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
    // The two files loadNatives() System.load()s, by absolute path. Published
    // through getTesseractLibraryPath() / getLeptonicaLibraryPath() so that a
    // consumer binding by absolute path (Octachorix) never has to list the
    // extraction directory and guess which file is which.
    private static volatile Path tesseractLibraryPath;
    private static volatile Path leptonicaLibraryPath;
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
     * <p>On Windows, every DLL shipped under {@code win32-x86-64/} is
     * extracted, not just the canonical pair: vcpkg-built tesseract has
     * transitive runtime dependencies on libpng, libtiff, libjpeg-turbo,
     * libwebp, openjp2, zlib, libcurl, libarchive, etc., and a single missing
     * one triggers {@link UnsatisfiedLinkError} at load time.
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
     * @throws IllegalStateException if tess4j is on the classpath — see
     *         {@link #refuseTess4j()}: Legerix does not work behind tess4j and
     *         refuses to pretend it does
     */
    public static synchronized Path loadNatives() throws IOException {
        if (loaded) {
            return extractionDir;
        }

        refuseTess4j();

        final OS os = OS.getCurrent();
        final Arch arch = Arch.getCurrent();
        final String tier = detectGlibcTier(os);
        detectedTier = tier;
        final String resourceDir = resourceDirFor(os, arch, tier);

        final Path target = cacheDir().resolve(cacheVersion()).resolve(resourceDir);
        Files.createDirectories(target);

        for (final String lib : librariesFor(os)) {
            extractIfMissing(resourceDir + "/" + lib, target.resolve(lib));
        }

        // Extract every other regular file under the platform's resource dir.
        // On Linux/macOS this is a no-op (only the canonical pair lives there).
        // On Windows it picks up the ~10 transitive vcpkg DLLs (libpng,
        // libtiff, libjpeg-turbo, libwebp, openjp2, zlib, libcurl,
        // libarchive, ...) that tesseract.dll needs at runtime.
        extractAllFromResourceDir(resourceDir, target);

        // tessdata: bundled lightweight (tessdata_fast) language models covering
        // ~80% of the world population. Consumers wanting other languages can
        // drop additional *.traineddata files alongside these in the same
        // cache directory (see getTessdataPath()).
        final Path tessdataDir = cacheDir().resolve(cacheVersion()).resolve("tessdata");
        Files.createDirectories(tessdataDir);
        for (final String lang : BUNDLED_LANGUAGES) {
            extractIfMissing("tessdata/" + lang + ".traineddata",
                    tessdataDir.resolve(lang + ".traineddata"));
        }

        // Best-effort hints for a consumer that would still resolve
        // tesseract/leptonica by SHORT NAME through JNA. The supported
        // contract is not this: it is getTesseractLibraryPath() and
        // getLeptonicaLibraryPath(), absolute paths, which Octachorix binds
        // without any lookup. tess4j, the short-name consumer these hints
        // were written for, is refused above. They stay for any other code
        // calling Native.load("tesseract", ...), and they are hints only.
        //
        // How JNA 5.14.0 really resolves a short name, as measured and
        // corrected by David Young on Legerix#20 (§3 of his report of
        // 2026-09-04), replacing the mechanism this comment used to cite:
        //
        //   1. Two exact-name attempts first: "libtesseract.so" (unversioned)
        //      in each search directory, jna.library.path included. If the
        //      file exists and dlopen succeeds, resolution ends there and no
        //      ranking ever happens. Directory order was never the problem:
        //      tess4j APPENDS to jna.library.path, ours stays first.
        //
        //   2. Only if both exact-name attempts fail, matchLibrary() runs as a
        //      catch-block fallback. Its filter ANDs isVersionedName(), so an
        //      unversioned file is not in its pool at all, and among the
        //      versioned candidates of ALL directories the highest parsed
        //      version wins: a system libtesseract.so.5.0.3 beats our
        //      libtesseract.so.5 whatever directory comes first.
        //
        // Our August reading, "an unversioned .so loses because no-version
        // parses lowest", was wrong: it never competes, it is excluded. What
        // actually failed in August was step 1 itself: the exact-name attempt
        // FOUND the unversioned symlink of the -8 payload and dlopen failed,
        // because that payload's RUNPATH was the corrupted literal 'RIGIN' and
        // the sibling leptonica was unreachable. A load failure read as a
        // ranking loss. With the $ORIGIN RUNPATH fixed, an unversioned alias
        // present in the extraction directory does resolve at step 1; when it
        // is absent, which depends on the publish channel, step 2 hands the
        // consumer the system library. Hence the getters above: name the
        // file, do not look it up.
        //
        // NEITHER mechanism affects Legerix.loadNatives() itself: the
        // System.load() calls below use absolute paths and bypass all short-
        // name resolution, and assertBundledTesseract() below uses
        // NativeLibrary.getInstance(absolute) for the same reason.
        final String ours = target.toAbsolutePath().toString();
        NativeLibrary.addSearchPath("tesseract", ours);
        NativeLibrary.addSearchPath("leptonica", ours);
        NativeLibrary.addSearchPath("lept", ours);
        final String existing = System.getProperty("jna.library.path", "");
        System.setProperty("jna.library.path",
                existing.isEmpty() ? ours : ours + java.io.File.pathSeparator + existing);

        // On Windows, the vcpkg-built leptonica/tesseract DLLs are shims that
        // import sibling DLLs (libleptonica1870.dll, libpng16.dll, ...) from
        // the same directory. System.load() uses the default Win32 DLL search
        // path which does not include the directory of the loaded DLL, so the
        // sibling imports fail with "Can't find dependent libraries". Adding
        // our extract dir to the search path via SetDllDirectoryW fixes that.
        if (os == OS.WINDOWS) {
            final int rc = WinKernel32.INSTANCE.SetDllDirectoryW(new WString(ours)) ? 1 : 0;
            if (rc == 0) {
                logger.log(Level.WARNING, "SetDllDirectoryW({0}) failed; "
                        + "Windows native loading may fail with UnsatisfiedLinkError", ours);
            }
        }

        // Load OUR bundled files by absolute path, in dependency order.
        // Absolute path bypasses short-name resolution entirely — no system
        // library can shadow ours. Order matters: leptonica first, so that
        // tesseract's NEEDED libleptonica.so.6 is already mapped when the
        // dynamic linker walks tesseract's dependencies (which sidesteps
        // any RUNPATH corruption that libtool may have produced at build
        // time and any LC_ID_DYLIB pointing at the CI runner on macOS).
        final Path leptonica = target.resolve(leptonicaFileName(os)).toAbsolutePath();
        final Path tesseract = target.resolve(tesseractFileName(os)).toAbsolutePath();
        loadBundledLib(leptonica.toString(), os);
        loadBundledLib(tesseract.toString(), os);

        // Verify what actually loaded matches what we shipped, by calling
        // TessVersion() on the exact file we just System.load'd — not via
        // short-name JNA resolution which David Young measured (Legerix#20)
        // to be structurally unable to prefer our copy over a higher-parsed-
        // version system library. Passing the absolute path to
        // NativeLibrary.getInstance() bypasses matchLibrary entirely and
        // guarantees the handle points at our extracted binary.
        assertBundledTesseract(tesseract.toString());

        leptonicaLibraryPath = leptonica;
        tesseractLibraryPath = tesseract;
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
        return cacheDir().resolve(cacheVersion()).resolve("tessdata");
    }

    /**
     * Returns the absolute path of the bundled Tesseract shared library that
     * {@link #loadNatives()} loaded: {@code tesseract55.dll} on Windows,
     * {@code libtesseract.so.5} on Linux, {@code libtesseract.5.dylib} on
     * macOS, inside the extraction directory of this JVM's tier. Triggers
     * {@link #loadNatives()} if it has not been called yet.
     *
     * <p>This is the file to hand to a binding that loads by absolute path
     * (Octachorix). Do not list the extraction directory and pick a file by
     * name pattern: the names differ per platform, aliases may or may not be
     * present depending on the publish channel, and Legerix already knows
     * exactly which file it loaded.
     *
     * @return the absolute path of the loaded {@code libtesseract}
     * @throws IllegalStateException if natives have not been loaded yet and
     *         the implicit {@link #loadNatives()} call fails
     */
    public static Path getTesseractLibraryPath() {
        ensureLoaded();
        return tesseractLibraryPath;
    }

    /**
     * Returns the absolute path of the bundled Leptonica shared library that
     * {@link #loadNatives()} loaded, the dependency of the file returned by
     * {@link #getTesseractLibraryPath()}. Same contract, same rationale.
     *
     * @return the absolute path of the loaded {@code libleptonica}
     * @throws IllegalStateException if natives have not been loaded yet and
     *         the implicit {@link #loadNatives()} call fails
     */
    public static Path getLeptonicaLibraryPath() {
        ensureLoaded();
        return leptonicaLibraryPath;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            try {
                loadNatives();
            } catch (final IOException e) {
                throw new IllegalStateException("loadNatives() failed", e);
            }
        }
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

    // Development-time fallback for cacheVersion() when running from
    // target/classes (no jar manifest). MUST be kept in sync with pom.xml's
    // <version> so that mvn test picks up new natives after every bump.
    // David Young measured a real user-facing bug on this in Legerix#20:
    // on upgrade 5.5.0-8 -> 5.5.0-9 the cache was keyed on the bare
    // "5.5.0" (getTesseractVersion() strips the build suffix), so
    // extractIfMissing early-returned and users kept the old natives.
    private static final String DEV_CACHE_VERSION = "5.5.2-1";

    // Full Legerix Maven version (e.g. "5.5.0-3"), used as cache key so that
    // bumping only the Legerix build suffix invalidates stale extracted DLLs.
    // Under mvn test the class loads from target/classes with no manifest, so
    // getImplementationVersion() returns null — we fall back to DEV_CACHE_VERSION
    // which includes the suffix, not to getTesseractVersion() which strips it.
    private static String cacheVersion() {
        final String v = Legerix.class.getPackage().getImplementationVersion();
        return v != null ? v : DEV_CACHE_VERSION;
    }

    /**
     * Refuses to run when tess4j is on the classpath. No opt-out.
     *
     * <p>tess4j resolves {@code libtesseract} by short name through JNA. JNA
     * pools every candidate found on the search path and keeps the highest
     * parsed version, so on any host with a system Tesseract (apt, yum,
     * brew) the system library wins over the one Legerix just extracted,
     * and its {@code NEEDED liblept.so.5} drags in a second Leptonica with a
     * different {@code Pix} layout: SIGSEGV in {@code pixDestroy} the first
     * time a Pix crosses the two (Legerix#20). Legerix cannot fix this from
     * its side of the fence, and pretending to work is worse than refusing.
     *
     * <p>The supported way to consume Legerix is a binding that loads the
     * extracted files by absolute path and nothing else:
     * <a href="https://github.com/oculix-org/Octachorix">Octachorix</a>
     * ({@code io.github.oculix-org:octachorix}), a Tesseract C API binding
     * with no short-name lookup, no search path, no fallback, a session per
     * thread, text + geometry + confidences in one pass. Point its
     * {@code Scribe.builder()} at {@link #loadNatives()}'s directory and
     * {@link #getTessdataPath()}.
     */
    private static void refuseTess4j() {
        final String[] probes = {
            "net.sourceforge.tess4j.Tesseract",
            "net.sourceforge.tess4j.Tesseract1",
            "net.sourceforge.tess4j.TessAPI",
        };
        final ClassLoader[] loaders = {
            Thread.currentThread().getContextClassLoader(),
            Legerix.class.getClassLoader(),
        };
        for (final String probe : probes) {
            for (final ClassLoader loader : loaders) {
                if (loader == null) continue;
                try {
                    Class.forName(probe, false, loader);
                } catch (ClassNotFoundException | LinkageError e) {
                    continue;
                }
                throw new IllegalStateException(
                    "Legerix: tess4j is on the classpath (" + probe + ").\n"
                    + "  tess4j resolves libtesseract by short name and binds the SYSTEM Tesseract\n"
                    + "  instead of the bundled one on any host that has one (apt, yum, brew), then\n"
                    + "  crashes on the first Pix that crosses two Leptonicas (Legerix#20).\n"
                    + "  Legerix refuses to run behind it.\n"
                    + "\n"
                    + "  Use Octachorix instead: io.github.oculix-org:octachorix\n"
                    + "  https://github.com/oculix-org/Octachorix\n"
                    + "  A Tesseract C API binding that loads libtesseract and libleptonica by absolute\n"
                    + "  path only (no short-name lookup, no search path, no fallback), keeps one\n"
                    + "  session per thread, and returns text, boxes and confidences in a single pass.\n"
                    + "  Point Scribe.builder() at Legerix.loadNatives() and Legerix.getTessdataPath(),\n"
                    + "  and remove tess4j from your dependencies.");
            }
        }
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

    private static void extractIfMissing(final String resource, final Path target) throws IOException {
        if (Files.exists(target)) return;
        try (InputStream in = Legerix.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Resource not found in classpath: " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Per-tier list of the files Legerix ships, written at build time. */
    static final String NATIVES_MANIFEST = "legerix-natives.txt";

    /**
     * Extract the natives Legerix ships for a tier into {@code target},
     * idempotent (skips files already on disk).
     *
     * <p>In JAR mode the jar that contains {@code Legerix.class} is read, and
     * that jar is not necessarily Legerix's own: a consumer that shades
     * Legerix (OculiX's fat jars do) may carry other natives under the very
     * same tier directory, OpenCV for one. Extracting "everything under the
     * tier" therefore copied a consumer's files into Legerix's cache and
     * presented them as bundled (Legerix#21, measured byte for byte by David
     * Young on macOS and Linux). Only the files named in the tier's
     * {@link #NATIVES_MANIFEST}, written by the build, are extracted now; a
     * jar without a manifest gets nothing beyond the canonical pair that
     * {@link #librariesFor} already extracted, and says so.
     *
     * <p>Exploded-classpath mode ({@code target/classes} in dev/test) is
     * never shaded, so it keeps extracting the directory as is.
     */
    private static void extractAllFromResourceDir(final String resourceDir, final Path target) throws IOException {
        final URL location = Legerix.class.getProtectionDomain().getCodeSource().getLocation();
        if (location == null) {
            // No code source (some custom classloaders): librariesFor() already
            // handled the canonical names, nothing more we can do.
            return;
        }
        final Path codeSourcePath;
        try {
            codeSourcePath = Paths.get(location.toURI());
        } catch (final URISyntaxException e) {
            throw new IOException("Cannot resolve code source URL: " + location, e);
        }
        if (Files.isDirectory(codeSourcePath)) {
            // Exploded classpath (dev/test from target/classes).
            final Path dir = codeSourcePath.resolve(resourceDir);
            if (!Files.isDirectory(dir)) return;
            try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                final java.util.Iterator<Path> it = stream.iterator();
                while (it.hasNext()) {
                    final Path entry = it.next();
                    if (Files.isRegularFile(entry)) {
                        final Path out = target.resolve(entry.getFileName());
                        if (!Files.exists(out)) {
                            Files.copy(entry, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
            return;
        }
        // Packaged JAR, possibly somebody else's: extract only what the
        // manifest names, among the entries actually present under the tier.
        try (JarFile jar = new JarFile(codeSourcePath.toFile())) {
            final String prefix = resourceDir + "/";
            final java.util.List<String> present = new java.util.ArrayList<>();
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final JarEntry je = entries.nextElement();
                if (je.isDirectory()) continue;
                final String name = je.getName();
                if (!name.startsWith(prefix)) continue;
                final String tail = name.substring(prefix.length());
                // Only immediate children, no nested subdirs.
                if (tail.isEmpty() || tail.contains("/")) continue;
                present.add(tail);
            }
            final JarEntry manifestEntry = jar.getJarEntry(prefix + NATIVES_MANIFEST);
            String manifest = null;
            if (manifestEntry != null) {
                try (InputStream in = jar.getInputStream(manifestEntry)) {
                    manifest = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            final java.util.List<String> wanted = filesToExtract(present, manifest);
            if (manifest == null) {
                logger.log(Level.WARNING, "No {0} under {1} in {2}: only the canonical tesseract/leptonica pair "
                        + "is extracted; other natives Legerix may ship for this tier are left in the jar",
                        new Object[]{NATIVES_MANIFEST, resourceDir, codeSourcePath});
            }
            for (final String tail : wanted) {
                extractIfMissing(prefix + tail, target.resolve(tail));
            }
        }
    }

    /**
     * The files to extract for a tier, given the entries present under the
     * tier directory of the jar and the content of that tier's
     * {@link #NATIVES_MANIFEST}: the manifest's entries, in manifest order,
     * restricted to those actually present. A {@code null} manifest yields
     * nothing: without it there is no way to tell Legerix's files from a
     * co-bundling consumer's (Legerix#21). Blank lines and lines starting
     * with {@code #} in the manifest are ignored.
     */
    static java.util.List<String> filesToExtract(final java.util.Collection<String> present, final String manifest) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        if (manifest == null) {
            return out;
        }
        final java.util.Set<String> available = new java.util.HashSet<>(present);
        for (final String raw : manifest.split("\\R")) {
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.equals(NATIVES_MANIFEST)) continue;
            if (available.contains(line) && !out.contains(line)) {
                out.add(line);
            }
        }
        return out;
    }

    // dlopen(3) flags used to force RTLD_GLOBAL on Linux — see loadBundledLib.
    private static final int RTLD_LAZY_LINUX   = 0x1;
    private static final int RTLD_GLOBAL_LINUX = 0x100;

    /**
     * Load a bundled native library by absolute path. On Linux, force RTLD_GLOBAL
     * via JNA so our symbols satisfy the DT_NEEDED of libraries the OS loads
     * later (typically the system libtesseract that tess4j resolves via
     * short-name lookup). Without RTLD_GLOBAL, System.load() defaults to
     * RTLD_LOCAL on Linux → our libleptonica is invisible → the dynamic linker
     * loads a SECOND libleptonica to satisfy the system libtesseract's NEEDED →
     * two Leptonicas coexist with incompatible Pix struct layouts → SIGSEGV in
     * pixDestroy when a Pix crosses between the two (measured on Ubuntu 24.04
     * apt tesseract-ocr, hs_err_pid libleptonica.so.6+0x152f9a pixDestroy+0x1a,
     * verify-natives-matrix CI run 32595060133 — the "symbol-interposition
     * hazard" David Young flagged in Legerix#20).
     *
     * On macOS: install_name_tool + unversioned symlinks (build.yml commit
     * 00bad35) already handle the interposition path; keep the plain
     * System.load which uses dyld's flat namespace semantics.
     *
     * On Windows: tess4j's LoadLibs extracts DLLs into %TEMP%\tess4j\ and
     * binds by absolute path; no short-name resolution → no interposition
     * risk. Keep System.load.
     */
    private static void loadBundledLib(final String absolutePath, final OS os) {
        if (os == OS.LINUX) {
            final Map<String, Object> opts = new HashMap<>();
            opts.put(Library.OPTION_OPEN_FLAGS, RTLD_LAZY_LINUX | RTLD_GLOBAL_LINUX);
            NativeLibrary.getInstance(absolutePath, opts);
        } else {
            try {
                System.load(absolutePath);
            } catch (final UnsatisfiedLinkError e) {
                final String advice = os == OS.OSX ? macOsHomebrewAdvice(e.getMessage()) : null;
                if (advice == null) {
                    throw e;
                }
                final UnsatisfiedLinkError explained = new UnsatisfiedLinkError(advice + "\nOriginal dyld error: " + e.getMessage());
                explained.initCause(e);
                throw explained;
            }
        }
    }

    /** Homebrew formulae the macOS tiers' Leptonica and Tesseract are linked against. */
    static final String MACOS_HOMEBREW_FORMULAE = "jpeg-turbo libpng libtiff webp zstd xz libdeflate";

    /**
     * On macOS the bundled Leptonica references its image codecs by the
     * absolute Homebrew paths they were linked against, {@code /opt/homebrew}
     * on Apple Silicon and {@code /usr/local} on Intel. On a Mac without those
     * formulae dyld reports {@code Library not loaded: <that path>} and the
     * raw text tells the user nothing about what to do. This turns that
     * failure into an instruction. Returns {@code null} for any other error,
     * which is then rethrown untouched.
     */
    static String macOsHomebrewAdvice(final String dyldMessage) {
        if (dyldMessage == null) {
            return null;
        }
        final boolean homebrewPath = dyldMessage.contains("/opt/homebrew/") || dyldMessage.contains("/usr/local/opt/")
                || dyldMessage.contains("/usr/local/lib/");
        final boolean notLoaded = dyldMessage.contains("Library not loaded") || dyldMessage.contains("image not found")
                || dyldMessage.contains("no such file");
        if (!homebrewPath || !notLoaded) {
            return null;
        }
        return "Legerix on macOS needs Homebrew's image codecs, which this Mac does not have. "
                + "Install Homebrew (https://brew.sh) and run:\n"
                + "    brew install " + MACOS_HOMEBREW_FORMULAE + "\n"
                + "then start again. The bundled Tesseract and Leptonica are linked against these formulae.";
    }

    // Minimal JNA binding to Win32 SetDllDirectoryW. Only loaded/initialized
    // on Windows; classloading is lazy so the Native.load call here does not
    // execute on Linux/macOS.
    private interface WinKernel32 extends StdCallLibrary {
        WinKernel32 INSTANCE = Native.load("kernel32", WinKernel32.class);
        boolean SetDllDirectoryW(WString lpPathName);
    }

    /**
     * Read {@code TessVersion()} directly on the extracted bundled libtesseract
     * at {@code absoluteTesseractPath} — not via JNA short-name resolution,
     * which David Young measured in Legerix#20 to be structurally unable to
     * prefer our copy over a higher-parsed-version system library. Passing an
     * absolute path (a string containing a path separator) to
     * {@link NativeLibrary#getInstance(String)} makes JNA call {@code dlopen}
     * (or {@code LoadLibraryW}) on that file directly, bypassing
     * {@code matchLibrary} entirely.
     *
     * <p>Package-private so tests can prove path-identity without going
     * through reflection.
     */
    static String getLoadedTesseractVersion(final String absoluteTesseractPath) {
        final NativeLibrary lib = NativeLibrary.getInstance(absoluteTesseractPath);
        return lib.getFunction("TessVersion").invokeString(new Object[0], false);
    }

    /**
     * Fail loudly at startup if the upstream Tesseract version reported by
     * the file we just extracted does not match this artifact's declared
     * version at the MAJOR.MINOR level.
     *
     * <p>Path identity is already guaranteed by the caller: we called
     * {@link #getLoadedTesseractVersion(String)} with the absolute path we
     * ourselves extracted, so the version we read describes our own binary.
     * What remains to check is upstream MAJOR.MINOR alignment — the payload
     * per platform can legitimately differ at the patch level (Windows vcpkg
     * ships 5.5.2, Linux/macOS from-source ship 5.5.0), so an exact-string
     * match would false-positive on Windows even against a correct build.
     */
    private static void assertBundledTesseract(final String absoluteTesseractPath) {
        final String actual = getLoadedTesseractVersion(absoluteTesseractPath);
        final String bundled = getTesseractVersion();
        final String actualMM = majorMinor(actual);
        final String bundledMM = majorMinor(bundled);
        if (actualMM == null || !actualMM.equals(bundledMM)) {
            throw new IllegalStateException(
                    "Legerix loaded the wrong Tesseract: expected " + bundled
                            + " (bundled, MAJOR.MINOR " + bundledMM + ") but TessVersion() reports "
                            + actual + " on " + absoluteTesseractPath
                            + ". The extracted file may be corrupt or a different upstream MAJOR.MINOR than declared.");
        }
    }

    /** Extract MAJOR.MINOR from a version string. {@code "5.5.2"} → {@code "5.5"}. */
    private static String majorMinor(final String v) {
        if (v == null) return null;
        final int firstDot = v.indexOf('.');
        if (firstDot < 0) return v;
        final int secondDot = v.indexOf('.', firstDot + 1);
        return secondDot < 0 ? v : v.substring(0, secondDot);
    }
}
