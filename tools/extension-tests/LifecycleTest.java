import app.morphe.extension.admobile.Credentials;
import app.morphe.extension.admobile.OAuthFlow;

import java.lang.reflect.Field;
import java.io.File;
import java.nio.file.Files;

/**
 * A sign in from end to end, across the process restart it finishes with.
 *
 * <p>Every bug this file guards against escaped a harness that treated storage as reliable. It is
 * not: the app restarts itself by killing its own process, and a queued preference write does not
 * survive that. A sign in that appears to succeed and leaves nothing behind is indistinguishable,
 * from the screen, from one that never ran, which is why it could be retried forever.
 */
public final class LifecycleTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        writesSurviveTheRestart();
        aSignInSurvivesTheRestart();
        theRecoveryPathTerminates();
        signOutSurvivesTheRestart();
        theSeedIsRewrittenAfterAWipe();

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** The bug itself, in one case. */
    private static void writesSurviveTheRestart() throws Exception {
        FakeContext context = attach(new FakeContext());

        Credentials.put(Credentials.KEY_PUBLISHER_ID, "pub-1");
        context.killProcess();

        check("a value written just before the restart is still there",
                "pub-1", Credentials.get(Credentials.KEY_PUBLISHER_ID));
        check("nothing was left queued", 0, context.applyCalls);
    }

    /** The whole flow: tokens, then the account, then the restart the form ends with. */
    private static void aSignInSurvivesTheRestart() throws Exception {
        FakeContext context = attach(new FakeContext());
        context.database = temporaryDatabase();

        // Step 1, entered by hand.
        Credentials.put(Credentials.KEY_CLIENT_ID, "id");
        Credentials.put(Credentials.KEY_CLIENT_SECRET, "secret");

        // Step 2, what the exchange stores.
        Credentials.put(Credentials.KEY_PENDING_VERIFIER, "verifier");
        Credentials.put(Credentials.KEY_PENDING_CODE, "4/code");
        Credentials.put(Credentials.KEY_REFRESH_TOKEN, "refresh");
        Credentials.put(Credentials.KEY_ACCESS_TOKEN, "access");
        Credentials.clear(Credentials.KEY_PENDING_CODE, Credentials.KEY_PENDING_VERIFIER,
                Credentials.KEY_PENDING_REDIRECT);

        // What the account lookup stores.
        Credentials.put(Credentials.KEY_PUBLISHER_ID, "pub-1");
        Credentials.put(Credentials.KEY_CURRENCY, "EUR");
        Credentials.put(Credentials.KEY_TIME_ZONE, "Europe/Paris");
        Credentials.seedAccount();

        context.killProcess();
        reattach(context);

        check("configured after the restart", true, Credentials.isConfigured());
        check("publisher kept", "pub-1", Credentials.publisherId());
        check("currency kept", "EUR", Credentials.currency());
        check("time zone kept", "Europe/Paris", Credentials.timeZone());
        check("the code is not replayed", false, OAuthFlow.hasPendingCode());
        check("nothing left to recover", false, OAuthFlow.needsAccount());
        check("the app is served the symbol", "€",
                Credentials.forDataStoreKey("config_currency_code"));
    }

    /**
     * The state the shipped build got stuck in, and the proof that finishing it ends it.
     *
     * <p>Recovering the account writes the publisher id and then restarts. If that write is lost,
     * the condition that triggered the recovery is true again on the way back up, and the screen
     * offers the same button forever.
     */
    private static void theRecoveryPathTerminates() throws Exception {
        FakeContext context = attach(new FakeContext());

        Credentials.put(Credentials.KEY_CLIENT_ID, "id");
        Credentials.put(Credentials.KEY_CLIENT_SECRET, "secret");
        Credentials.put(Credentials.KEY_REFRESH_TOKEN, "refresh");

        check("there is something to recover", true, OAuthFlow.needsAccount());
        check("but the form does not start it on its own", false, OAuthFlow.hasPendingCode());

        // What retryAccount stores when the lookup answers.
        Credentials.put(Credentials.KEY_PUBLISHER_ID, "pub-1");

        context.killProcess();
        reattach(context);

        check("recovery does not come back after the restart", false, OAuthFlow.needsAccount());
        check("configured", true, Credentials.isConfigured());
    }

    private static void signOutSurvivesTheRestart() throws Exception {
        FakeContext context = attach(new FakeContext());
        context.database = temporaryDatabase();

        Credentials.put(Credentials.KEY_CLIENT_ID, "id");
        Credentials.put(Credentials.KEY_CLIENT_SECRET, "secret");
        Credentials.put(Credentials.KEY_REFRESH_TOKEN, "refresh");
        Credentials.put(Credentials.KEY_PUBLISHER_ID, "pub-1");
        Credentials.seedAccount();

        Credentials.signOut();
        context.killProcess();
        reattach(context);

        check("still signed out after the restart", false, Credentials.isConfigured());
        check("the client is still there to sign back in with", true, Credentials.hasClient());
        check("no half finished sign in was left behind", false, OAuthFlow.needsAccount());
    }

    private static void theSeedIsRewrittenAfterAWipe() throws Exception {
        FakeContext context = attach(new FakeContext());
        context.database = temporaryDatabase();

        Credentials.put(Credentials.KEY_CLIENT_ID, "id");
        Credentials.put(Credentials.KEY_CLIENT_SECRET, "secret");
        Credentials.put(Credentials.KEY_REFRESH_TOKEN, "refresh");
        Credentials.put(Credentials.KEY_PUBLISHER_ID, "pub-1");

        android.database.sqlite.SQLiteDatabase.statements.clear();
        Credentials.seedAccount();
        check("seeded", 1, android.database.sqlite.SQLiteDatabase.statements.size());

        context.killProcess();
        reattach(context);
        Credentials.seedAccount();
        check("not rewritten on the next launch", 1,
                android.database.sqlite.SQLiteDatabase.statements.size());

        // The user clears the app's data: the database goes, the preferences stay.
        context.database = new File("/nonexistent/main.db");
        Credentials.seedAccount();
        context.database = temporaryDatabase();
        Credentials.seedAccount();

        check("rewritten once the database is back", 2,
                android.database.sqlite.SQLiteDatabase.statements.size());
    }

    // --- harness ---------------------------------------------------------------------------

    private static FakeContext attach(FakeContext context) throws Exception {
        detach();
        Credentials.attach(context);
        return context;
    }

    /** The same storage seen by a fresh process, as after the restart. */
    private static void reattach(FakeContext context) throws Exception {
        detach();
        Credentials.attach(context);
    }

    private static void detach() throws Exception {
        Field context = Credentials.class.getDeclaredField("context");
        context.setAccessible(true);
        context.set(null, null);

        Field preferences = Credentials.class.getDeclaredField("preferences");
        preferences.setAccessible(true);
        preferences.set(null, null);
    }

    private static java.io.File temporaryDatabase() throws Exception {
        java.io.File file = Files.createTempFile("main", ".db").toFile();
        file.deleteOnExit();
        return file;
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
