package android.os;

/**
 * Enough of a Looper to answer "am I on the main thread".
 *
 * <p>The tests run on a plain JVM thread, which stands in for a background thread: nothing is the
 * main looper, so code guarded by that check takes the background branch. That is the branch worth
 * exercising, since it is where the network work happens.
 */
public final class Looper {

    private static final Looper MAIN = new Looper();

    private Looper() {
    }

    public static Looper getMainLooper() {
        return MAIN;
    }

    /** Null: no JVM test thread is the main looper. */
    public static Looper myLooper() {
        return null;
    }
}
