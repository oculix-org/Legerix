import io.github.julienmerconsulting.legerix.Legerix;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JAR-mode probe for Legerix#21, run against a packaged Legerix jar. It
 * exercises what no test running from {@code target/classes} can: the jar is
 * the code source, the payload is read from it entry by entry, and the
 * extraction lands in a private directory.
 *
 * <pre>
 *   java -cp "legerix.jar:jna.jar" scripts/JarModeProbe.java
 *   java -cp "decoy.jar:legerix.jar:jna.jar" scripts/JarModeProbe.java
 * </pre>
 *
 * Checks, in order:
 * <ol>
 *   <li>the extraction directory holds exactly what the tier manifest of the
 *       code source declares, plus the lock file and tessdata: a foreign
 *       native under a generic top-level directory of the same jar never
 *       reaches it;</li>
 *   <li>every extracted byte comes from the code source, not from whatever
 *       the class loader would have answered — printed and compared, so a
 *       decoy jar placed first on the class path is proven not to win;</li>
 *   <li>the directory is private to this run and claimed by a lock;</li>
 *   <li>the five bundled languages are there;</li>
 *   <li>a second call returns the same directory and extracts nothing new.</li>
 * </ol>
 * Exit 0 when all hold, 1 otherwise.
 */
public class JarModeProbe {

    private static final List<String> problems = new ArrayList<>();

    private static void check(final boolean ok, final String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) problems.add(what);
    }

    public static void main(String[] args) throws Exception {
        final Path codeSource = Paths.get(
                Legerix.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        System.out.println("code source    : " + codeSource);

        final Path dir = Legerix.loadNatives();
        final String tier = dir.getFileName().toString().replaceAll("-\\d+$", "");
        System.out.println("legerix version: " + Legerix.getLegerixVersion());
        System.out.println("tesseract      : " + Legerix.getTesseractVersion()
                + "  " + Legerix.getTesseractLibraryPath());
        System.out.println("extraction dir : " + dir);
        System.out.println("tier           : " + tier);

        final String tierDir = "META-INF/legerix/natives/" + tier;
        final TreeSet<String> manifest = new TreeSet<>();
        final TreeSet<String> extracted;
        try (JarFile jar = new JarFile(codeSource.toFile())) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    jar.getInputStream(jar.getJarEntry(tierDir + "/legerix-natives.txt")), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) manifest.add(line);
                }
            }

            try (Stream<Path> s = Files.list(dir)) {
                extracted = s.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> !n.equals(".legerix-lock"))
                        .collect(Collectors.toCollection(TreeSet::new));
            }

            System.out.println("manifest  (" + manifest.size() + ") : " + manifest);
            System.out.println("extracted (" + extracted.size() + ") : " + extracted);
            check(manifest.equals(extracted), "the cache holds exactly the manifest of the code source");

            // Every extracted byte comes from the code source. If Legerix had
            // gone back through the class loader, a jar earlier on the class
            // path exposing the same resource would have supplied these bytes.
            boolean sameBytes = true;
            for (final String name : manifest) {
                final JarEntry e = jar.getJarEntry(tierDir + "/" + name);
                try (InputStream in = jar.getInputStream(e)) {
                    if (!Arrays.equals(in.readAllBytes(), Files.readAllBytes(dir.resolve(name)))) {
                        sameBytes = false;
                        System.out.println("       differs from the code source: " + name);
                    }
                }
            }
            check(sameBytes, "every extracted file is byte-identical to the code source entry");

            // What the class loader would have answered, for the record: with
            // a decoy jar first on the class path this is the decoy's copy.
            final URL viaLoader = Legerix.class.getClassLoader().getResource(tierDir + "/legerix-natives.txt");
            System.out.println("class loader would have read: " + viaLoader);

            // A foreign native under a generic top-level directory of the very
            // same jar is not ours and must not be extracted.
            final List<String> foreign = new ArrayList<>();
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final String n = entries.nextElement().getName();
                if (!n.startsWith("META-INF/legerix/") && (n.endsWith(".so") || n.endsWith(".dll")
                        || n.endsWith(".dylib") || n.contains(".so."))) {
                    foreign.add(n);
                }
            }
            System.out.println("foreign natives in the jar, outside our space: " + foreign);
            boolean leaked = false;
            for (final String n : foreign) {
                final String base = n.substring(n.lastIndexOf('/') + 1);
                if (Files.exists(dir.resolve(base))) {
                    leaked = true;
                    System.out.println("       leaked into the cache: " + base);
                }
            }
            check(!leaked, "no native from outside META-INF/legerix/ reached the cache");
        }

        check(Files.exists(dir.resolve(".legerix-lock")), "the extraction directory is claimed by a lock");
        check(dir.getParent().getFileName().toString().equals(Legerix.getLegerixVersion()),
                "it lives under the Legerix version of the code source");
        check(!dir.getFileName().toString().equals(tier),
                "it is private to this run, not the shared tier directory");

        final Path tessdata = Legerix.getTessdataPath();
        boolean languages = tessdata.getParent().equals(dir);
        for (final String lang : Legerix.BUNDLED_LANGUAGES) {
            if (!Files.isRegularFile(tessdata.resolve(lang + ".traineddata"))) {
                languages = false;
                System.out.println("       missing language: " + lang);
            }
        }
        check(languages, "the five bundled languages are extracted inside it");

        final long before = Files.getLastModifiedTime(Legerix.getTesseractLibraryPath()).toMillis();
        final Path second = Legerix.loadNatives();
        check(second.equals(dir) && Files.getLastModifiedTime(Legerix.getTesseractLibraryPath()).toMillis() == before,
                "a second call returns the same directory and extracts nothing again");

        // --hold <seconds>: keep the JVM, and therefore the lock, alive so a
        // second JVM can be started against the same Legerix version and
        // shown to get its own directory while this one survives untouched.
        final int hold = Arrays.asList(args).indexOf("--hold");
        if (hold >= 0 && hold + 1 < args.length) {
            System.out.println("holding " + dir + " for " + args[hold + 1] + "s");
            System.out.flush();
            Thread.sleep(Long.parseLong(args[hold + 1]) * 1000L);
        }

        if (problems.isEmpty()) {
            System.out.println("OK: payload isolated, read from one source, extracted whole into a private directory");
            return;
        }
        System.out.println("FAIL: " + problems.size() + " check(s) failed");
        System.exit(1);
    }
}
