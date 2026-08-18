package app.morphe.extension.admobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Form for the values in {@link Credentials}.
 *
 * <p>Opened by the app's own sign in button while no credentials are stored, so the setup is one
 * tap from the screen the user already lands on. Saving restarts the app, which is enough for the
 * account to be picked up.
 *
 * <p>Built in code rather than from a layout so the patch adds no resources: it only has to declare
 * the activity in the manifest, and there are no ids to keep in step with the host app.
 */
public final class CredentialsActivity extends Activity {

    private static final int BACKGROUND = 0xFF121212;
    private static final int FOREGROUND = 0xFFE0E0E0;
    private static final int MUTED = 0xFF9E9E9E;
    private static final int ACCENT = 0xFFF0C040;

    /** key, label, hint, required. */
    private static final String[][] FIELDS = {
            {Credentials.KEY_CLIENT_ID, "OAuth client id", "000000000000-xxxx.apps.googleusercontent.com", "1"},
            {Credentials.KEY_CLIENT_SECRET, "OAuth client secret", "GOCSPX-…", "1"},
            {Credentials.KEY_REFRESH_TOKEN, "Refresh token", "issued for the admob.readonly scope", "1"},
            {Credentials.KEY_PUBLISHER_ID, "AdMob publisher id", "pub-0000000000000000", "1"},
            {Credentials.KEY_EMAIL, "Account email", "shown as the account name", ""},
            {Credentials.KEY_TIME_ZONE, "Report time zone", "UTC", ""},
            {Credentials.KEY_CURRENCY, "Report currency", "USD", ""},
    };

    private final EditText[] inputs = new EditText[FIELDS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Credentials.init(this);

        int padding = dp(20);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, padding, padding, padding);

        form.addView(text("Connect your AdMob account", ACCENT, 22, Gravity.CENTER_HORIZONTAL, 0, 0));
        form.addView(text(
                "This build reads your AdMob reports with your own Google API credentials, so it "
                        + "does not need the Google sign in that a re-signed app cannot complete.",
                MUTED, 13, Gravity.NO_GRAVITY, dp(10), 0));
        form.addView(text(
                "Create a Desktop OAuth client in Google Cloud with the AdMob API enabled, then "
                        + "authorise it once for the admob.readonly scope to get a refresh token.",
                MUTED, 13, Gravity.NO_GRAVITY, dp(8), dp(4)));

        boolean optionalHeadingAdded = false;
        for (int i = 0; i < FIELDS.length; i++) {
            String[] field = FIELDS[i];
            boolean required = !field[3].isEmpty();

            if (!required && !optionalHeadingAdded) {
                form.addView(text("Optional", ACCENT, 15, Gravity.NO_GRAVITY, dp(28), 0));
                optionalHeadingAdded = true;
            } else if (required && i == 0) {
                form.addView(text("Required", ACCENT, 15, Gravity.NO_GRAVITY, dp(24), 0));
            }

            form.addView(text(field[1], Color.WHITE, 13, Gravity.NO_GRAVITY, dp(14), 0));

            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setHint(field[2]);
            input.setHintTextColor(MUTED);
            input.setTextColor(FOREGROUND);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setText(Credentials.get(field[0]));
            form.addView(input);

            inputs[i] = input;
        }

        Button save = new Button(this);
        save.setText("Save and start");
        save.setAllCaps(false);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save();
            }
        });

        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp(28);
        form.addView(save, saveParams);

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(BACKGROUND);
        root.addView(form);

        setContentView(root);
    }

    private void save() {
        StringBuilder missing = new StringBuilder();

        for (int i = 0; i < FIELDS.length; i++) {
            String value = inputs[i].getText().toString().trim();
            Credentials.put(FIELDS[i][0], value);

            if (!FIELDS[i][3].isEmpty() && value.isEmpty()) {
                if (missing.length() > 0) missing.append(", ");
                missing.append(FIELDS[i][1]);
            }
        }

        if (missing.length() > 0) {
            Toast.makeText(this, "Still needed: " + missing, Toast.LENGTH_LONG).show();
            return;
        }

        restartApp();
    }

    /** The account is read while the app starts, so it has to go through its launch again. */
    private void restartApp() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());

        if (launch == null) {
            Toast.makeText(this, "Saved. Restart AdMobile.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(launch);
        finish();
    }

    private TextView text(String content, int color, int sizeSp, int gravity, int topDp, int bottomDp) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (gravity != Gravity.NO_GRAVITY) view.setGravity(gravity);
        view.setPadding(0, topDp, 0, bottomDp);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
