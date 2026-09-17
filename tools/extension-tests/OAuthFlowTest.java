import app.morphe.extension.admobile.Credentials;
import app.morphe.extension.admobile.OAuthFlow;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The sign in state machine, and above all what the form does when it resumes.
 *
 * <p>A resume that starts work whose condition its own success does not clear is a loop, which is
 * exactly what shipped: the account lookup can fail on its own, the condition "tokens but no
 * account" then stays true, and every return to the foreground started another attempt.
 */
public final class OAuthFlowTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        onResumeRunsOnlyForACapturedCode();
        needsAccountIsTheRecoveryCondition();
        oneFlowAtATime();
        jsonReadsNestedAndFlatFields();
        queryParameterMatchesWholeKeysOnly();

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** The regression: the resume path must not re-enter work its own success cannot end. */
    private static void onResumeRunsOnlyForACapturedCode() throws Exception {
        signedInWithoutAccount();

        check("nothing runs on resume while only the account is missing",
                false, OAuthFlow.hasPendingCode());

        Credentials.put(Credentials.KEY_PENDING_CODE, "4/code");
        check("a captured code does run on resume", true, OAuthFlow.hasPendingCode());

        // Consumed by the attempt, whatever the attempt concludes, so it cannot repeat.
        Credentials.put(Credentials.KEY_PENDING_CODE, "");
        check("and is gone once used", false, OAuthFlow.hasPendingCode());
    }

    private static void needsAccountIsTheRecoveryCondition() throws Exception {
        fresh();
        check("nothing to recover on a fresh install", false, OAuthFlow.needsAccount());

        Credentials.put(Credentials.KEY_CLIENT_ID, "id");
        Credentials.put(Credentials.KEY_CLIENT_SECRET, "secret");
        check("a client alone is not a half finished sign in", false, OAuthFlow.needsAccount());

        Credentials.put(Credentials.KEY_REFRESH_TOKEN, "refresh");
        check("tokens without an account is", true, OAuthFlow.needsAccount());

        Credentials.put(Credentials.KEY_PUBLISHER_ID, "pub-1");
        check("and stops once the account is read back", false, OAuthFlow.needsAccount());

        Credentials.signOut();
        check("signing out is not a half finished sign in", false, OAuthFlow.needsAccount());
    }

    /** The form resumes more than once per sign in; only one flow may be in flight. */
    private static void oneFlowAtATime() throws Exception {
        fresh();

        Field running = OAuthFlow.class.getDeclaredField("running");
        running.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean flag =
                (java.util.concurrent.atomic.AtomicBoolean) running.get(null);

        check("idle to start", false, flag.get());

        check("the first caller takes the flow", true, flag.compareAndSet(false, true));
        check("a second caller is turned away", false, flag.compareAndSet(false, true));

        flag.set(false);
        check("and the flow is free again", true, flag.compareAndSet(false, true));
        flag.set(false);
    }

    private static void jsonReadsNestedAndFlatFields() throws Exception {
        Method jsonString = OAuthFlow.class.getDeclaredMethod("jsonString", String.class, String.class);
        jsonString.setAccessible(true);

        String token = "{\"access_token\":\"ya29.abc\",\"expires_in\":3599}";
        check("flat field", "ya29.abc", jsonString.invoke(null, token, "access_token"));
        check("absent field", null, jsonString.invoke(null, token, "refresh_token"));

        String accounts = "{\"account\":[{\"name\":\"accounts/pub-1\",\"publisherId\":\"pub-1\","
                + "\"currencyCode\":\"EUR\",\"reportingTimeZone\":\"Europe/Paris\"}]}";
        check("nested publisher", "pub-1", jsonString.invoke(null, accounts, "publisherId"));
        check("nested currency", "EUR", jsonString.invoke(null, accounts, "currencyCode"));

        String error = "{\"error\":\"invalid_grant\",\"error_description\":\"Bad Request\"}";
        check("error description", "Bad Request", jsonString.invoke(null, error, "error_description"));
    }

    private static void queryParameterMatchesWholeKeysOnly() throws Exception {
        Method queryParameter =
                OAuthFlow.class.getDeclaredMethod("queryParameter", String.class, String.class);
        queryParameter.setAccessible(true);

        String line = "GET /?state=xyz&code=4%2F0AY0e HTTP/1.1";
        check("reads the code", "4/0AY0e", queryParameter.invoke(null, line, "code"));

        String decoy = "GET /?scope_code=nope&code=real HTTP/1.1";
        check("a key merely ending in the name is not it",
                "real", queryParameter.invoke(null, decoy, "code"));

        String denied = "GET /?error=access_denied HTTP/1.1";
        check("no code when the user declined", null, queryParameter.invoke(null, denied, "code"));
    }

    // --- harness ---------------------------------------------------------------------------

    private static FakeContext fresh() throws Exception {
        Field context = Credentials.class.getDeclaredField("context");
        context.setAccessible(true);
        context.set(null, null);

        Field preferences = Credentials.class.getDeclaredField("preferences");
        preferences.setAccessible(true);
        preferences.set(null, null);

        FakeContext fake = new FakeContext();
        Credentials.attach(fake);
        return fake;
    }

    /** The state the shipped build got stuck in: tokens held, account never read back. */
    private static FakeContext signedInWithoutAccount() throws Exception {
        FakeContext context = fresh();

        Credentials.put(Credentials.KEY_CLIENT_ID, "id");
        Credentials.put(Credentials.KEY_CLIENT_SECRET, "secret");
        Credentials.put(Credentials.KEY_REFRESH_TOKEN, "refresh");
        Credentials.put(Credentials.KEY_ACCESS_TOKEN, "access");

        return context;
    }

    private static void check(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  ok    " + what);
        } else {
            failed++;
            System.out.println("  FAIL  " + what + ": expected <" + expected + "> got <" + actual + ">");
        }
    }
}
