package io.github.julienmerconsulting.legerix;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.util.HashMap;
import java.util.Map;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
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

    /**
     * Legerix's own space inside the jar. Everything Legerix ships lives
     * under it and nothing else in the jar belongs to Legerix: a consumer
     * that shades Legerix keeps its own natives under whatever generic
     * directory names it chooses, and the two cannot collide any more
     * (Legerix#21). Never read a native through the class loader: another
     * jar can expose the very same path.
     */
    private static final String PAYLOAD_ROOT = "META-INF/legerix";
    private static final String NATIVES_ROOT = PAYLOAD_ROOT + "/natives";
    private static final String TESSDATA_ROOT = PAYLOAD_ROOT + "/tessdata";
    private static final String IDENTITY_RESOURCE = PAYLOAD_ROOT + "/legerix.properties";

    /** Per-tier list of the files Legerix ships, written at build time. */
    static final String NATIVES_MANIFEST = "legerix-natives.txt";

    /** Marks an extraction directory as belonging to a live JVM. */
    private static final String LOCK_FILE = ".legerix-lock";

    /** Cached, idempotent extraction directory. */
    private static volatile Path extractionDir;
    private static volatile Path tessdataDir;
    private static volatile String detectedTier;
    private static volatile String legerixVersion;
    private static volatile String tesseractVersion;
    // Held open for the life of the JVM: another JVM that finds this lock
    // taken leaves our extraction directory alone.
    private static FileChannel lockChannel;
    private static FileLock ownLock;
    /** True while loadNatives() runs, to catch reentrance through a getter. */
    private static volatile boolean loading;
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
        loading = true;
        try {

        final OS os = OS.getCurrent();
        final Arch arch = Arch.getCurrent();
        final String tier = detectGlibcTier(os);
        detectedTier = tier;
        final String tierDir = NATIVES_ROOT + "/" + resourceDirFor(os, arch, tier);

        // ONE source, opened once: the container of Legerix.class. The
        // manifest, the natives and the language models are all read from it
        // with jar.getInputStream(entry), never re-resolved through the class
        // loader, which any other jar on the class path can answer.
        try (Payload payload = Payload.open()) {
            // Legerix's identity comes from its own payload too. The jar
            // manifest is the consumer's after shading, so
            // getImplementationVersion() cannot be trusted for the cache key
            // or the expected Tesseract version (Legerix#21).
            final Properties identity = payload.readProperties(IDENTITY_RESOURCE);
            legerixVersion = required(identity, "legerix.version", payload);
            tesseractVersion = required(identity, "tesseract.version", payload);

            final List<String> files = declaredNatives(payload, tierDir, os);

            // A brand new private directory per loader initialisation. The
            // old scheme reused <version>/<tier> and skipped any file already
            // there, so a directory could end up holding the union of what
            // several code sources contributed over time, which is exactly
            // what made the #20 investigation unresolvable. Nothing is ever
            // completed from another run here: what we extract is what this
            // payload holds, and nothing else.
            final Path versionRoot = cacheDir().resolve(legerixVersion);
            Files.createDirectories(versionRoot);
            final Path target = Files.createTempDirectory(versionRoot, resourceDirFor(os, arch, tier) + "-");
            claim(target);
            reapAbandonedExtractions(versionRoot, target);

            for (final String name : files) {
                payload.extract(tierDir + "/" + name, target.resolve(name));
            }

            // tessdata: bundled lightweight (tessdata_fast) language models
            // covering ~80% of the world population, from the same source,
            // into the same private directory. Consumers wanting other
            // languages drop additional *.traineddata files in
            // getTessdataPath().
            final Path tessdata = target.resolve("tessdata");
            Files.createDirectories(tessdata);
            for (final String lang : BUNDLED_LANGUAGES) {
                payload.extract(TESSDATA_ROOT + "/" + lang + ".traineddata",
                        tessdata.resolve(lang + ".traineddata"));
            }
            tessdataDir = tessdata;
            extractionDir = target;
        }

        final Path target = extractionDir;

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
        loaded = true;

        logger.log(Level.FINE, "Legerix natives loaded from {0} (tier={1})",
                new Object[]{target, tier});
        return target;

        } finally {
            loading = false;
        }
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
        ensureLoaded();
        return tessdataDir;
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
            // loadNatives() is synchronized on the class, and a class monitor
            // is reentrant: a getter called from inside loadNatives() before
            // the loaded flag is set would silently recurse instead of
            // blocking, each turn extracting one more private directory.
            // Fail with a name instead of hanging.
            if (loading) {
                throw new IllegalStateException("Legerix: a public getter was called from inside loadNatives() "
                        + "before it finished; internal code must read the fields, not the getters");
            }
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
        ensureLoaded();
        return tesseractVersion;
    }

    /**
     * Returns Legerix's own full version, e.g. {@code "5.5.2-2"}: the upstream
     * Tesseract version and the Legerix build suffix. Read from Legerix's own
     * payload, never from the jar manifest, which belongs to the consumer once
     * Legerix is shaded (Legerix#21). It names the extraction cache, so
     * bumping only the build suffix gives every consumer a fresh payload.
     *
     * @return the Legerix version this artifact was built as
     * @throws IllegalStateException if natives have not been loaded yet and
     *         the implicit {@link #loadNatives()} call fails
     */
    public static String getLegerixVersion() {
        ensureLoaded();
        return legerixVersion;
    }

    private static String required(final Properties identity, final String key, final Payload payload)
            throws IOException {
        final String v = identity.getProperty(key);
        if (v == null || v.trim().isEmpty() || v.contains("${")) {
            throw new IOException("Legerix: " + IDENTITY_RESOURCE + " in " + payload.source()
                    + " declares no usable " + key + " (found: " + v + "). This payload was not built by "
                    + "Legerix's own pom, or resource filtering did not run.");
        }
        return v.trim();
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

    /**
     * The single source Legerix reads its payload from: the container of
     * {@code Legerix.class}, a jar or an exploded directory. Every byte
     * Legerix extracts comes from here, read entry by entry. The class
     * loader is never used for the payload: another jar on the class path
     * can expose the very same resource path, and whoever comes first then
     * answers, which is the ambiguity Legerix#21 is about. If this source
     * cannot be resolved or opened, Legerix fails rather than looking
     * somewhere else to succeed anyway.
     */
    static final class Payload implements Closeable {
        private final Path codeSource;
        private final JarFile jar;

        private Payload(final Path codeSource, final JarFile jar) {
            this.codeSource = codeSource;
            this.jar = jar;
        }

        static Payload open() throws IOException {
            final java.security.CodeSource cs = Legerix.class.getProtectionDomain().getCodeSource();
            final URL location = cs == null ? null : cs.getLocation();
            if (location == null) {
                throw new IOException("Legerix: no code source for Legerix.class, so its own payload cannot be "
                        + "identified. Legerix will not fall back to the class path: another artifact could "
                        + "answer for its resources (Legerix#21).");
            }
            final Path path;
            try {
                path = Paths.get(location.toURI());
            } catch (final URISyntaxException | IllegalArgumentException e) {
                throw new IOException("Legerix: cannot resolve its own code source " + location
                        + " to a readable file or directory", e);
            }
            if (Files.isDirectory(path)) {
                return new Payload(path, null);
            }
            if (Files.isRegularFile(path)) {
                return new Payload(path, new JarFile(path.toFile()));
            }
            throw new IOException("Legerix: its own code source " + path + " is neither a jar nor a directory");
        }

        /**
         * A payload rooted at a directory, for tests that need to describe a
         * packaging Legerix must accept or refuse without building a jar.
         */
        static Payload ofDirectory(final Path root) {
            return new Payload(root, null);
        }

        /** Where this payload was read from, for error messages. */
        Path source() {
            return codeSource;
        }

        boolean has(final String resource) {
            if (jar == null) {
                return Files.isRegularFile(codeSource.resolve(resource));
            }
            final JarEntry e = jar.getJarEntry(resource);
            return e != null && !e.isDirectory();
        }

        InputStream open(final String resource) throws IOException {
            if (jar == null) {
                final Path f = codeSource.resolve(resource);
                if (!Files.isRegularFile(f)) {
                    throw new IOException("Legerix: " + resource + " is missing from " + codeSource);
                }
                return Files.newInputStream(f);
            }
            final JarEntry e = jar.getJarEntry(resource);
            if (e == null || e.isDirectory()) {
                throw new IOException("Legerix: " + resource + " is missing from " + codeSource);
            }
            return jar.getInputStream(e);
        }

        /** Immediate regular children of a directory of this payload. */
        Set<String> children(final String resourceDir) throws IOException {
            final Set<String> names = new LinkedHashSet<>();
            if (jar == null) {
                final Path dir = codeSource.resolve(resourceDir);
                if (!Files.isDirectory(dir)) {
                    return names;
                }
                try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                    for (final java.util.Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
                        final Path entry = it.next();
                        if (Files.isRegularFile(entry)) {
                            names.add(entry.getFileName().toString());
                        }
                    }
                }
                return names;
            }
            final String prefix = resourceDir + "/";
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                final String name = e.getName();
                if (!name.startsWith(prefix)) continue;
                final String tail = name.substring(prefix.length());
                if (tail.isEmpty() || tail.indexOf('/') >= 0) continue;
                names.add(tail);
            }
            return names;
        }

        List<String> readLines(final String resource) throws IOException {
            final List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(open(resource), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            }
            return lines;
        }

        Properties readProperties(final String resource) throws IOException {
            final Properties props = new Properties();
            try (InputStream in = open(resource)) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            return props;
        }

        void extract(final String resource, final Path target) throws IOException {
            try (InputStream in = open(resource)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public void close() throws IOException {
            if (jar != null) {
                jar.close();
            }
        }
    }

    /**
     * The files this payload declares for a tier, in manifest order, after
     * checking the contract. The manifest is not a hint: a packaging that is
     * incomplete or ambiguous is refused before anything is loaded, rather
     * than loading whatever happens to be there and hoping it is enough.
     *
     * <ul>
     *   <li>no manifest for the tier: error;</li>
     *   <li>a declared file absent from the payload: error;</li>
     *   <li>the canonical tesseract/leptonica pair not declared: error;</li>
     *   <li>a name that is not a plain file name, or declared twice: error;</li>
     *   <li>no fallback to the generic top-level directories of older
     *       layouts, ever.</li>
     * </ul>
     *
     * <p>A foreign native sitting elsewhere in the jar, a consumer's OpenCV
     * under its own {@code linux-x86-64/} for instance, is not an error and
     * not our business: it is simply never named here.
     */
    static List<String> declaredNatives(final Payload payload, final String tierDir, final OS os)
            throws IOException {
        final String manifestResource = tierDir + "/" + NATIVES_MANIFEST;
        if (!payload.has(manifestResource)) {
            throw new IOException("Legerix: no " + manifestResource + " in " + payload.source()
                    + ". This payload does not declare what it ships for this platform, so nothing is "
                    + "extracted: without the manifest there is no way to tell Legerix's files from a "
                    + "co-bundling consumer's (Legerix#21). Rebuild with scripts/write-natives-manifest.sh.");
        }
        final Set<String> present = payload.children(tierDir);
        final List<String> declared = new ArrayList<>();
        for (final String raw : payload.readLines(manifestResource)) {
            final String name = raw.trim();
            if (name.isEmpty() || name.startsWith("#")) continue;
            if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                    || name.startsWith(".") || name.equals(NATIVES_MANIFEST)) {
                throw new IOException("Legerix: " + manifestResource + " in " + payload.source()
                        + " declares an invalid entry: '" + name + "'. Entries are plain file names of that "
                        + "tier directory.");
            }
            if (declared.contains(name)) {
                throw new IOException("Legerix: " + manifestResource + " in " + payload.source()
                        + " declares '" + name + "' twice.");
            }
            if (!present.contains(name)) {
                throw new IOException("Legerix: " + manifestResource + " in " + payload.source()
                        + " declares '" + name + "' but " + tierDir + " does not contain it. The payload is "
                        + "incomplete; Legerix refuses to load a partial platform rather than fail later on a "
                        + "missing dependency.");
            }
            declared.add(name);
        }
        for (final String canonical : librariesFor(os)) {
            if (!declared.contains(canonical)) {
                throw new IOException("Legerix: " + manifestResource + " in " + payload.source()
                        + " does not declare " + canonical + ", which this platform cannot run without.");
            }
        }
        return declared;
    }

    /**
     * Marks an extraction directory as in use by this JVM, for the life of
     * the JVM. A later run finds the lock taken and leaves the directory
     * alone; when the JVM is gone the lock is released by the OS and the
     * directory becomes reapable. Nothing here deletes anything.
     */
    private static void claim(final Path dir) throws IOException {
        final Path lock = dir.resolve(LOCK_FILE);
        lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        ownLock = lockChannel.lock();
    }

    /**
     * Deletes extraction directories of this same Legerix version that no
     * live JVM holds. A directory whose lock cannot be taken belongs to a
     * running JVM and is left untouched; a deletion that fails, the normal
     * case on Windows where a loaded DLL stays mapped, is skipped silently
     * and retried by a later run. Best effort by design: what matters here
     * is never removing what someone may still be using, not always freeing
     * the disk.
     */
    private static void reapAbandonedExtractions(final Path versionRoot, final Path keep) {
        final List<Path> candidates = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(versionRoot)) {
            for (final java.util.Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
                final Path dir = it.next();
                if (Files.isDirectory(dir) && !dir.equals(keep) && Files.exists(dir.resolve(LOCK_FILE))) {
                    candidates.add(dir);
                }
            }
        } catch (final IOException e) {
            logger.log(Level.FINE, "Legerix: cannot list {0} to reap old extractions", versionRoot);
            return;
        }
        for (final Path dir : candidates) {
            boolean abandoned = false;
            try (FileChannel ch = FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.WRITE)) {
                final FileLock probe = ch.tryLock();
                if (probe != null) {
                    probe.release();
                    abandoned = true;
                }
            } catch (final IOException | RuntimeException e) {
                continue;
            }
            if (abandoned) {
                deleteQuietly(dir);
            }
        }
    }

    private static void deleteQuietly(final Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (final IOException ignored) {
                        // Locked native on Windows: leave it, a later run retries.
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path d, final IOException exc) {
                    try {
                        Files.deleteIfExists(d);
                    } catch (final IOException ignored) {
                        // Not empty because a file above could not be deleted.
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            logger.log(Level.FINE, "Legerix: could not reap {0}", dir);
        }
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
        // The field, never getTesseractVersion(): the public getter calls
        // ensureLoaded(), and at this point loadNatives() has not set the
        // loaded flag yet, so the getter would re-enter loadNatives() through
        // the reentrant class monitor and recurse until the disk fills.
        final String bundled = tesseractVersion;
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
