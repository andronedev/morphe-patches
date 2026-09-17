import android.database.sqlite.SQLiteDatabase;

import app.morphe.extension.admobile.Credentials;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

/** Exercises the extension's storage, its answers to the app's reads, and the seeding guard. */
public final class CredentialsTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        storesAndReadsBack();
        isConfiguredNeedsEveryPiece();
        answersOnlyItsOwnDataStoreKeys();
        answersOnlyItsOwnLegacyKeys();
        answersNothingWhileUnconfigured();
        currencySymbolFollowsTheAccount();
        mirrorsTokenWrites();
        signOutKeepsTheClient();
        seedsOnceAndAgainWhenTheAccountChanges();
        seedsAgainWhenTheDatabaseIsGone();
        signInIntentPointsAtTheForm();
        aFreshTokenIsServedAsIs();
        anAgedTokenIsNotServedBlindly();

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // --- cases -----------------------------------------------------------------------------

    private static void storesAndReadsBack() throws Exception {
        FakeContext context = fresh();

        Credentials.put(Credentials.KEY_CLIENT_ID, "  id-with-spaces  ");

        check("put trims", "id-with-spaces", Credentials.get(Credentials.KEY_CLIENT_ID));
        check("get of an unset key is empty", "", Credentials.get("never-set"));
        check("put reaches the preferences", "id-with-spaces", context.stored.get("client_id"));
    }

    private static void isConfiguredNeedsEveryPiece() throws Exception {
        fresh();
        check("unconfigured to start", false, Credentials.isConfigured());

        Credentials.put(Credentials.KEY_CLIENT_ID, "id");
        check("client id alone is not a client", false, Credentials.hasClient());

        Credentials.put(Credentials.KEY_CLIENT_SECRET, "secret");
        check("id and secret make a client", true, Credentials.hasClient());
        check("a client alone is not configured", false, Credentials.isConfigured());

        Credentials.put(Credentials.KEY_REFRESH_TOKEN, "refresh");
        check("no publisher, not configured", false, Credentials.isConfigured());

        Credentials.put(Credentials.KEY_PUBLISHER_ID, "pub-1");
        check("configured", true, Credentials.isConfigured());
    }

    private static void answersOnlyItsOwnDataStoreKeys() throws Exception {
        configured();

        check("client secret", "secret", Credentials.forDataStoreKey("web_client_secret"));
        check("publisher id", "pub-1", Credentials.forDataStoreKey("user_pub_id"));
        check("refresh token by prefix", "refresh", Credentials.forDataStoreKey("token_refresh_pub-1"));
        check("access token by prefix", "access", Credentials.forDataStoreKey("token_access_pub-1"));
        check("currency symbol", "€", Credentials.forDataStoreKey("config_currency_code"));

        check("an unrelated key falls through", null, Credentials.forDataStoreKey("config_app_theme"));
        check("a null name falls through", null, Credentials.forDataStoreKey(null));
    }

    private static void answersOnlyItsOwnLegacyKeys() throws Exception {
        configured();

        check("legacy refresh token", "refresh", Credentials.forLegacyKey("user_token_refresh"));
        check("legacy access token", "access", Credentials.forLegacyKey("user_token_access"));
        check("an unrelated legacy key falls through", null, Credentials.forLegacyKey("user_name"));
    }

    private static void answersNothingWhileUnconfigured() throws Exception {
        fresh();

        check("no secret while unconfigured", null, Credentials.forDataStoreKey("web_client_secret"));
        check("no token while unconfigured", null, Credentials.forLegacyKey("user_token_access"));
        check("no symbol while unconfigured", null, Credentials.forDataStoreKey("config_currency_code"));
    }

    private static void currencySymbolFollowsTheAccount() throws Exception {
        fresh();
        check("defaults to dollars", "$", Credentials.currencySymbol());

        Credentials.put(Credentials.KEY_CURRENCY, "EUR");
        check("euro", "€", Credentials.currencySymbol());

        Credentials.put(Credentials.KEY_CURRENCY, "GBP");
        check("pound", "£", Credentials.currencySymbol());

        Credentials.put(Credentials.KEY_CURRENCY, "ZZZ");
        check("an unknown code is shown as itself", "ZZZ", Credentials.currencySymbol());
    }

    private static void mirrorsTokenWrites() throws Exception {
        configured();

        Credentials.observeWrite("user_token_access", "newer-access");
        check("legacy access write mirrored", "newer-access", Credentials.get(Credentials.KEY_ACCESS_TOKEN));

        Credentials.observeWrite("token_access_pub-1", "newest-access");
        check("datastore access write mirrored", "newest-access", Credentials.get(Credentials.KEY_ACCESS_TOKEN));

        Credentials.observeWrite("token_refresh_pub-1", "newer-refresh");
        check("refresh write mirrored", "newer-refresh", Credentials.get(Credentials.KEY_REFRESH_TOKEN));

        Credentials.observeWrite("user_token_access", "");
        check("an empty write is ignored", "newest-access", Credentials.get(Credentials.KEY_ACCESS_TOKEN));

        Credentials.observeWrite("unrelated", "value");
        check("an unrelated write is ignored", "newest-access", Credentials.get(Credentials.KEY_ACCESS_TOKEN));
    }

    private static void signOutKeepsTheClient() throws Exception {
        configured();

        Credentials.signOut();

        check("client id kept", "id", Credentials.get(Credentials.KEY_CLIENT_ID));
        check("client secret kept", "secret", Credentials.get(Credentials.KEY_CLIENT_SECRET));
        check("refresh token cleared", "", Credentials.get(Credentials.KEY_REFRESH_TOKEN));
        check("access token cleared", "", Credentials.get(Credentials.KEY_ACCESS_TOKEN));
        check("publisher cleared", "", Credentials.get(Credentials.KEY_PUBLISHER_ID));
        check("no longer configured", false, Credentials.isConfigured());
    }

    private static void seedsOnceAndAgainWhenTheAccountChanges() throws Exception {
        FakeContext context = configured();
        context.database = existingDatabase();
        SQLiteDatabase.statements.clear();

        Credentials.seedAccount();
        check("seeds the account", 1, SQLiteDatabase.statements.size());
        check("inserts into users", true, SQLiteDatabase.statements.get(0).contains("INTO users"));
        check("writes the publisher id", "pub-1", SQLiteDatabase.arguments.get(0)[0]);
        check("writes the currency", "EUR", SQLiteDatabase.arguments.get(0)[7]);

        Credentials.seedAccount();
        check("an unchanged account is not written again", 1, SQLiteDatabase.statements.size());

        Credentials.put(Credentials.KEY_CURRENCY, "USD");
        Credentials.seedAccount();
        check("a changed account is written again", 2, SQLiteDatabase.statements.size());
    }

    private static void seedsAgainWhenTheDatabaseIsGone() throws Exception {
        FakeContext context = configured();
        context.database = existingDatabase();
        SQLiteDatabase.statements.clear();

        Credentials.seedAccount();
        check("seeded", 1, SQLiteDatabase.statements.size());

        // Clearing the app's data takes the database with it while the preferences say it is there.
        context.database = new File("/nonexistent/main.db");
        Credentials.seedAccount();

        context.database = existingDatabase();
        Credentials.seedAccount();
        check("seeds again after the database went away", 2, SQLiteDatabase.statements.size());
    }

    private static void signInIntentPointsAtTheForm() throws Exception {
        fresh();

        android.content.Intent intent = Credentials.signInIntent();
        check("targets the app", "io.stark.admob", intent.packageName);
        check("targets the form",
                "app.morphe.extension.admobile.CredentialsActivity", intent.className);
    }

    /** A token obtained moments ago is handed straight back, with no network call. */
    private static void aFreshTokenIsServedAsIs() throws Exception {
        configured();
        Credentials.storeAccessToken("fresh-token");

        check("a fresh token is served", "fresh-token", Credentials.accessToken());
        check("and its age was recorded", true,
                !Credentials.get(Credentials.KEY_ACCESS_TOKEN_AT).isEmpty());
    }

    /**
     * The bug the widgets exposed: an access token lasts an hour, and whatever was left behind was
     * served forever. Anything running on its own schedule sent an expired token and got nothing
     * back, which reads on screen as zero earnings.
     */
    private static void anAgedTokenIsNotServedBlindly() throws Exception {
        FakeContext context = configured();
        Credentials.storeAccessToken("stale-token");

        // Backdate it past the hour Google grants.
        context.stored.put(Credentials.KEY_ACCESS_TOKEN_AT,
                Long.toString(System.currentTimeMillis() - 2 * 60 * 60 * 1000L));

        // No network here, so the refresh cannot succeed; what matters is that the staleness is
        // noticed rather than the value being handed out as though it were good.
        check("an aged token is recognised as such", true, isAged());
    }

    private static boolean isAged() throws Exception {
        java.lang.reflect.Method obtainedAt = Credentials.class.getDeclaredMethod("obtainedAt");
        obtainedAt.setAccessible(true);
        long at = (Long) obtainedAt.invoke(null);
        return System.currentTimeMillis() - at >= 55 * 60 * 1000L;
    }

    // --- harness ---------------------------------------------------------------------------

    /** A context with nothing stored, and an extension rebound to it. */
    private static FakeContext fresh() throws Exception {
        Field context = Credentials.class.getDeclaredField("context");
        context.setAccessible(true);
        context.set(null, null);

        Field preferences = Credentials.class.getDeclaredField("preferences");
        preferences.setAccessible(true);
        preferences.set(null, null);

        SQLiteDatabase.failOnOpen = false;

        FakeContext fake = new FakeContext();
        Credentials.attach(fake);
        return fake;
    }

    private static FakeContext configured() throws Exception {
        FakeContext context = fresh();

        Credentials.put(Credentials.KEY_CLIENT_ID, "id");
        Credentials.put(Credentials.KEY_CLIENT_SECRET, "secret");
        Credentials.put(Credentials.KEY_REFRESH_TOKEN, "refresh");
        Credentials.put(Credentials.KEY_ACCESS_TOKEN, "access");
        Credentials.put(Credentials.KEY_PUBLISHER_ID, "pub-1");
        Credentials.put(Credentials.KEY_CURRENCY, "EUR");
        Credentials.put(Credentials.KEY_TIME_ZONE, "Europe/Paris");

        return context;
    }

    private static File existingDatabase() throws Exception {
        File file = Files.createTempFile("main", ".db").toFile();
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
