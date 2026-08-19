package app.morphe.extension.admobile;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
 * <p>Built in code rather than from a layout so the patch adds no resources, and painted entirely
 * from the Material 3 colour roles of the app's own theme, which the patch puts on this activity.
 * That way it follows the app in light and dark, and picks up the dynamic palette on Android 12
 * and above, without shipping colours of its own.
 */
public final class CredentialsActivity extends Activity {

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

    private int surface;
    private int onSurface;
    private int onSurfaceVariant;
    private int surfaceVariant;
    private int primary;
    private int onPrimary;

    private TextView signIn;
    private LinearLayout manualSection;
    private TextView manualToggle;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Credentials.init(this);
        resolvePalette();

        int padding = dp(24);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        // The extra top inset keeps the title clear of the status bar, which the activity draws
        // under because the app's theme is edge to edge.
        form.setPadding(padding, padding + statusBarHeight(), padding, padding);

        form.addView(headline("Connect your AdMob account"));
        form.addView(body(
                "This build reads your reports with Google API credentials of its own, so it does "
                        + "not need the Google sign in that a re-signed app cannot complete."));

        boolean bundledClient = Credentials.hasClient();

        signIn = filledButton("Sign in with Google", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startSignIn();
            }
        });
        form.addView(signIn, marginTop(dp(28)));

        status = body("");
        status.setVisibility(View.GONE);
        form.addView(status);

        if (!bundledClient) {
            form.addView(body(
                    "No OAuth client is built into this patch. Fill the client id and secret in "
                            + "below first, then sign in — or paste a refresh token directly."));
        }

        manualToggle = body("Enter the values manually");
        manualToggle.setTextColor(primary);
        manualToggle.setGravity(Gravity.CENTER_HORIZONTAL);
        manualToggle.setPadding(0, dp(24), 0, dp(4));
        manualToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean showing = manualSection.getVisibility() == View.VISIBLE;
                manualSection.setVisibility(showing ? View.GONE : View.VISIBLE);
                manualToggle.setText(showing ? "Enter the values manually" : "Hide the fields");
            }
        });
        form.addView(manualToggle);

        TextView diagnose = body("Run a connection check");
        diagnose.setTextColor(primary);
        diagnose.setGravity(Gravity.CENTER_HORIZONTAL);
        diagnose.setPadding(0, dp(16), 0, 0);
        diagnose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                status.setText("Checking…");
                status.setVisibility(View.VISIBLE);
                OAuthFlow.diagnose(CredentialsActivity.this, signInCallback());
            }
        });
        form.addView(diagnose);

        manualSection = buildManualSection();
        manualSection.setVisibility(bundledClient ? View.GONE : View.VISIBLE);
        form.addView(manualSection);

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(surface);
        root.addView(form);

        setContentView(root);
    }

    /**
     * The browser holds the foreground while the flow runs, so the result is picked up here rather
     * than relying on the callback reaching a form the system may have torn down meanwhile.
     */
    @Override
    protected void onResume() {
        super.onResume();

        if (Credentials.isConfigured()) {
            restartApp();
            return;
        }

        String last = Credentials.get(Credentials.KEY_LAST_STATUS);
        status.setText(last);
        status.setVisibility(last.isEmpty() ? View.GONE : View.VISIBLE);

        // The browser has handed the code back and the app is in the foreground again, which is
        // exactly when the exchange can reach the network.
        if (OAuthFlow.hasPendingCode()) {
            signIn.setEnabled(false);
            signIn.setText("Finishing…");
            OAuthFlow.completePending(this, signInCallback());
            return;
        }

        signIn.setEnabled(true);
        signIn.setText("Sign in with Google");
    }

    private OAuthFlow.Callback signInCallback() {
        return new OAuthFlow.Callback() {
            @Override
            public void onFinished(boolean success, String message) {
                signIn.setEnabled(true);
                signIn.setText("Sign in with Google");

                status.setText(message);
                status.setVisibility(View.VISIBLE);

                if (success) restartApp();
            }
        };
    }

    private LinearLayout buildManualSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        boolean optionalHeadingAdded = false;
        for (int i = 0; i < FIELDS.length; i++) {
            String[] field = FIELDS[i];
            boolean required = !field[3].isEmpty();

            if (!required && !optionalHeadingAdded) {
                TextView optional = body("Optional");
                optional.setTextColor(primary);
                optional.setPadding(0, dp(24), 0, 0);
                section.addView(optional);
                optionalHeadingAdded = true;
            }

            TextView label = body(field[1]);
            label.setTextColor(onSurface);
            label.setPadding(0, dp(16), 0, dp(6));
            section.addView(label);

            section.addView(inputs[i] = filledField(field[2], Credentials.get(field[0])));
        }

        section.addView(filledButton("Save and start", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save();
            }
        }), marginTop(dp(28)));

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
                signInCallback());
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

    // Material 3 surfaces, painted from the theme the patch puts on this activity.

    private void resolvePalette() {
        surface = themeColor("colorSurface", 0xFF121212);
        onSurface = themeColor("colorOnSurface", Color.WHITE);
        onSurfaceVariant = themeColor("colorOnSurfaceVariant", 0xFF9E9E9E);
        surfaceVariant = themeColor("colorSurfaceVariant", 0xFF1E1E1E);
        primary = themeColor("colorPrimary", 0xFFF0C040);
        onPrimary = themeColor("colorOnPrimary", 0xFF121212);
    }

    /**
     * Material's colour roles are attributes of the host app's resources, so they are looked up by
     * name: the extension is compiled on its own and has no R class for them.
     */
    private int themeColor(String attributeName, int fallback) {
        int identifier = getResources().getIdentifier(attributeName, "attr", getPackageName());
        if (identifier == 0) return fallback;

        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(identifier, value, true)) return fallback;

        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }

        try {
            return getResources().getColor(value.resourceId, getTheme());
        } catch (Exception exception) {
            return fallback;
        }
    }

    private TextView headline(String content) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(onSurface);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        return view;
    }

    private TextView body(String content) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(onSurfaceVariant);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(10), 0, 0);
        return view;
    }

    /** A filled button: fully rounded, primary container, label in the matching on-primary role. */
    private TextView filledButton(String label, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(onPrimary);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(24), dp(14), dp(24), dp(14));
        button.setClickable(true);
        button.setOnClickListener(listener);

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary);
        shape.setCornerRadius(dp(20));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(withAlpha(onPrimary, 0x33)), shape, null));

        return button;
    }

    /** A filled text field: surface variant behind, rounded top corners, as Material 3 draws them. */
    private EditText filledField(String hint, String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setHintTextColor(withAlpha(onSurfaceVariant, 0x99));
        input.setTextColor(onSurface);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(value);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surfaceVariant);
        float radius = dp(8);
        shape.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        input.setBackground(shape);

        return input;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private LinearLayout.LayoutParams marginTop(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = margin;
        return params;
    }

    private int statusBarHeight() {
        int identifier = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return identifier > 0 ? getResources().getDimensionPixelSize(identifier) : dp(24);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
