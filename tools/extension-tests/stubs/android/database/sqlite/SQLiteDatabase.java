package android.database.sqlite;

import java.util.ArrayList;
import java.util.List;

public final class SQLiteDatabase implements java.io.Closeable {
    public static final int OPEN_READWRITE = 0;
    public static final int ENABLE_WRITE_AHEAD_LOGGING = 8;

    /** Every statement any handle ran, so a test can assert on what reached the database. */
    public static final List<String> statements = new ArrayList<>();
    public static final List<Object[]> arguments = new ArrayList<>();
    public static boolean failOnOpen;

    public static SQLiteDatabase openDatabase(String path, Object factory, int flags) {
        if (failOnOpen) throw new RuntimeException("cannot open " + path);
        return new SQLiteDatabase();
    }

    public void execSQL(String statement, Object[] args) {
        statements.add(statement);
        arguments.add(args);
    }

    @Override
    public void close() {
    }
}
