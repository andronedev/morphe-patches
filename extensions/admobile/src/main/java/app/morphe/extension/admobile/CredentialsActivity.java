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
 * Sign in screen for the patched app.
 *
 * <p>Opened by the app's own sign in button while no credentials are stored, so setup is one tap
 * from the screen the user already lands on. When the patch was built with an OAuth client, the
 * whole flow is a single button: the browser handles the consent and everything else, publisher id
 * included, is read back from Google. The manual fields stay available for builds without a
 * bundled client, or to paste a token obtained elsewhere.
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

    private Button signIn;
    private LinearLayout manualSection;
    private TextView manualToggle;

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
                "This build reads your reports with Google API credentials of its own, so it does "
                        + "not need the Google sign in that a re-signed app cannot complete.",
                MUTED, 13, Gravity.NO_GRAVITY, dp(10), 0));

        boolean bundledClient = Credentials.hasClient();

        signIn = new Button(this);
        signIn.setText("Sign in with Google");
        signIn.setAllCaps(false);
        signIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startSignIn();
            }
        });
        form.addView(signIn, marginTop(dp(24)));

        if (!bundledClient) {
            form.addView(text(
                    "No OAuth client is built into this patch. Fill the client id and secret in "
                            + "below first, then sign in — or paste a refresh token directly.",
                    MUTED, 12, Gravity.NO_GRAVITY, dp(10), 0));
        }

        manualToggle = text("Enter the values manually", ACCENT, 13, Gravity.CENTER_HORIZONTAL,
                dp(20), dp(4));
        manualToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean showing = manualSection.getVisibility() == View.VISIBLE;
                manualSection.setVisibility(showing ? View.GONE : View.VISIBLE);
                manualToggle.setText(showing ? "Enter the values manually" : "Hide the fields");
            }
        });
        form.addView(manualToggle);

        manualSection = buildManualSection();
        manualSection.setVisibility(bundledClient ? View.GONE : View.VISIBLE);
        form.addView(manualSection);

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(BACKGROUND);
        root.addView(form);

        setContentView(root);
    }

    private LinearLayout buildManualSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        boolean optionalHeadingAdded = false;
        for (int i = 0; i < FIELDS.length; i++) {
            String[] field = FIELDS[i];
            boolean required = !field[3].isEmpty();

            if (!required && !optionalHeadingAdded) {
                section.addView(text("Optional", ACCENT, 15, Gravity.NO_GRAVITY, dp(24), 0));
                optionalHeadingAdded = true;
            }

            section.addView(text(field[1], Color.WHITE, 13, Gravity.NO_GRAVITY, dp(14), 0));

            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setHint(field[2]);
            input.setHintTextColor(MUTED);
            input.setTextColor(FOREGROUND);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setText(Credentials.get(field[0]));
            section.addView(input);

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
        section.addView(save, marginTop(dp(24)));

        return section;
    }

    /** Persists whatever is typed first, so a client entered by hand is available to the flow. */
    private void startSignIn() {
        if (manualSection.getVisibility() == View.VISIBLE) persist();

        if (!Credentials.hasClient()) {
            Toast.makeText(this, "An OAuth client id and secret are needed first.",
                    Toast.LENGTH_LONG).show();
            manualSection.setVisibility(View.VISIBLE);
            return;
        }

        signIn.setEnabled(false);
        signIn.setText("Waiting for the browser…");

        OAuthFlow.start(this, Credentials.effectiveClientId(), Credentials.effectiveClientSecret(),
                new OAuthFlow.Callback() {
                    @Override
                    public void onFinished(boolean success, String message) {
                        signIn.setEnabled(true);
                        signIn.setText("Sign in with Google");

                        Toast.makeText(CredentialsActivity.this, message, Toast.LENGTH_LONG).show();
                        if (success) restartApp();
                    }
                });
    }

    private void persist() {
        for (int i = 0; i < FIELDS.length; i++) {
            Credentials.put(FIELDS[i][0], inputs[i].getText().toString());
        }
    }

    private void save() {
        persist();

        StringBuilder missing = new StringBuilder();
        for (String[] field : FIELDS) {
            if (field[3].isEmpty() || !Credentials.get(field[0]).isEmpty()) continue;

            if (missing.length() > 0) missing.append(", ");
            missing.append(field[1]);
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

    private LinearLayout.LayoutParams marginTop(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = margin;
        return params;
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
