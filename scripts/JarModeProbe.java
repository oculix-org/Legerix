import io.github.julienmerconsulting.legerix.Legerix;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JAR-mode extraction probe for Legerix#21. Run against a packaged Legerix jar
 * on the class path, ideally a copy into which a foreign native was injected
 * under the current tier (what a co-bundling consumer's fat jar looks like):
 *
 * <pre>
 *   java -cp target/legerix-shaded-probe.jar scripts/JarModeProbe.java
 * </pre>
 *
 * It calls loadNatives(), then compares the files in the extraction directory
 * with the tier's legerix-natives.txt read from the jar. Exit 0 only when the
 * two sets are identical: everything the manifest names was extracted, and
 * nothing else, so the injected foreign native never reached the cache. The
 * unit test covers the selection function; this covers the real path, jar,
 * manifest, cache and all, which no exploded-classpath test can.
 */
public class JarModeProbe {

    public static void main(String[] args) throws Exception {
        final Path dir = Legerix.loadNatives();
        final String tier = dir.getFileName().toString();
        System.out.println("extraction dir : " + dir);
        System.out.println("tesseract      : " + Legerix.getTesseractLibraryPath());
        System.out.println("leptonica      : " + Legerix.getLeptonicaLibraryPath());

        final TreeSet<String> manifest = new TreeSet<>();
        try (InputStream in = Legerix.class.getResourceAsStream("/" + tier + "/legerix-natives.txt")) {
            if (in == null) {
                System.out.println("FAIL: no legerix-natives.txt for tier " + tier + " in the jar");
                System.exit(2);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) manifest.add(line);
                }
            }
        }

        final TreeSet<String> extracted;
        try (Stream<Path> s = Files.list(dir)) {
            extracted = s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        System.out.println("manifest  (" + manifest.size() + ") : " + manifest);
        System.out.println("extracted (" + extracted.size() + ") : " + extracted);

        final TreeSet<String> missing = new TreeSet<>(manifest);
        missing.removeAll(extracted);
        final TreeSet<String> foreign = new TreeSet<>(extracted);
        foreign.removeAll(manifest);

        if (!missing.isEmpty()) {
            System.out.println("FAIL: named in the manifest but not extracted: " + missing);
        }
        if (!foreign.isEmpty()) {
            System.out.println("FAIL: extracted but not in the manifest (a co-bundler's file leaked into the cache): " + foreign);
        }
        if (!missing.isEmpty() || !foreign.isEmpty()) {
            System.exit(1);
        }
        System.out.println("OK: the cache holds exactly the manifest, nothing more, nothing less");
    }
}
