import io.github.julienmerconsulting.legerix.Legerix;

/**
 * The other half of {@link JarModeProbe}: a packaging that is incomplete or
 * ambiguous must be refused before anything is loaded, even when an earlier
 * run left a cache holding exactly the missing file. Run against a jar the
 * caller has damaged on purpose, one damage per run:
 *
 * <pre>
 *   zip -d broken.jar 'META-INF/legerix/natives/&lt;tier&gt;/legerix-natives.txt'
 *   java -cp "broken.jar:jna.jar" scripts/StrictPackagingProbe.java "no legerix-natives.txt"
 * </pre>
 *
 * The argument is a fragment the failure message must contain. Exit 0 when
 * loadNatives() fails with it, 1 when it fails differently, 2 when it
 * succeeds, which would mean Legerix loaded a payload it cannot vouch for.
 */
public class StrictPackagingProbe {

    public static void main(String[] args) {
        final String expected = args.length > 0 ? args[0] : "";
        try {
            final java.nio.file.Path dir = Legerix.loadNatives();
            System.out.println("FAIL: loadNatives() succeeded on a damaged payload and extracted to " + dir);
            System.exit(2);
        } catch (final Exception e) {
            final String message = String.valueOf(e.getMessage());
            System.out.println(e.getClass().getSimpleName() + ": " + message);
            if (message.contains(expected)) {
                System.out.println("OK: refused before loading, and the message says why");
                return;
            }
            System.out.println("FAIL: expected the message to contain: " + expected);
            System.exit(1);
        }
    }
}
