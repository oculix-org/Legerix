import io.github.julienmerconsulting.legerix.Legerix;

/**
 * macOS probe for the "no Homebrew codecs" message. Run on a Mac from which
 * the Homebrew image codecs were removed, with a packaged Legerix jar on the
 * class path:
 *
 * <pre>
 *   brew uninstall --ignore-dependencies --force jpeg-turbo libpng libtiff webp zstd xz libdeflate
 *   java -cp target/legerix-probe.jar:jna.jar scripts/MacNoHomebrewProbe.java
 * </pre>
 *
 * Expected: loadNatives() fails with an UnsatisfiedLinkError whose message
 * carries the "brew install" instruction before the raw dyld text. Exit 0 in
 * that case. If the natives load anyway, the codecs are still reachable on
 * this machine and the message path was not exercised: exit 3, so the job
 * says so instead of pretending.
 */
public class MacNoHomebrewProbe {

    /**
     * The sentence a user has to type, spelled out rather than read back from
     * {@code Legerix.MACOS_HOMEBREW_FORMULAE}. A probe that imports its own
     * expectation from the class under test keeps passing when that value is
     * wrong: it asserts that the message contains whatever the class currently
     * says, not what a user needs. The in-package unit test may read the
     * constant; this one checks what a real Mac shows a real person.
     */
    private static final String EXPECTED =
            "brew install jpeg-turbo libpng libtiff webp zstd xz libdeflate";

    public static void main(String[] args) throws Exception {
        try {
            Legerix.loadNatives();
        } catch (UnsatisfiedLinkError e) {
            System.out.println("UnsatisfiedLinkError as expected:");
            System.out.println(e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains(EXPECTED)) {
                System.out.println("OK: the failure names Homebrew and the formulae to install");
                return;
            }
            System.out.println("FAIL: the failure does not carry the Homebrew instruction");
            System.exit(1);
        }
        System.out.println("NOT EXERCISED: natives loaded although the Homebrew codecs were removed; "
                + "the codecs are still reachable on this machine");
        System.exit(3);
    }
}
