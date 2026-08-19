package android.app;

import android.content.Intent;

public class Activity extends android.content.Context {
    public void startActivity(Intent intent) { }
    public void runOnUiThread(Runnable action) { action.run(); }

    @Override public android.content.Context getApplicationContext() { return this; }
    @Override public android.content.SharedPreferences getSharedPreferences(String n, int m) { return null; }
    @Override public java.io.File getDatabasePath(String n) { return new java.io.File(n); }
    @Override public String getPackageName() { return "io.stark.admob"; }
}
