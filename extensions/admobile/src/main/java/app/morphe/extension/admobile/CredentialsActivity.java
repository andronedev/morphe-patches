package app.morphe.extension.admobile;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
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
 * Sign in screen for the patched app, laid out as the two steps it really is: name the Google API
 * client once, then connect an account with it.
 *
 * <p>Reached from the app's own sign in and add account buttons, both of which the patch redirects
 * here, so it stays available once signed in, which is where disconnecting lives. The app's own
 * sign out cannot work on its own: the account it would forget is served from {@link Credentials}
 * rather than from its database.
 *
 * <p>The client id and secret are properties of the build, not of whoever is signed in, so they
 * survive disconnecting and come back prefilled.
 *
 * <p>Built in code rather than from a layout so the patch adds no resources, and painted from the
 * Material 3 colour roles of the app's own theme, which the patch puts on this activity.
 */
public final class CredentialsActivity extends Activity {

    private int surface;
    private int onSurface;
    private int onSurfaceVariant;
    private int surfaceVariant;
    private int primary;
    private int onPrimary;

    private EditText clientId;
    private EditText clientSecret;
    private TextView action;
    private TextView status;
    private TextView disconnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Credentials.attach(this);
        resolvePalette();

        int padding = dp(24);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        // The extra top inset keeps the title clear of the status bar, which the activity draws
        // under because the app's theme is edge to edge.
        form.setPadding(padding, padding + statusBarHeight(), padding, padding);

        form.addView(headline("Connect your AdMob account"));
        form.addView(body("This build reads your reports with your own Google API credentials, so "
                + "it does not need the Google sign in that a re-signed app cannot complete."));

        // Step 1: the client, entered once and kept.
        form.addView(step("Step 1", "Your Google API client"));
        form.addView(body("These two values come from a Google Cloud project of your own. It takes "
                + "a few minutes, once."));

        final LinearLayout guide = buildGuide();
        guide.setVisibility(View.GONE);

        final TextView guideToggle = link("How do I get these?");
        guideToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean showing = guide.getVisibility() == View.VISIBLE;
                guide.setVisibility(showing ? View.GONE : View.VISIBLE);
                guideToggle.setText(showing ? "How do I get these?" : "Hide the steps");
            }
        });
        form.addView(guideToggle);
        form.addView(guide);

        form.addView(label("OAuth client id"));
        clientId = field("000000000000-xxxx.apps.googleusercontent.com",
                Credentials.get(Credentials.KEY_CLIENT_ID));
        form.addView(clientId);

        form.addView(label("OAuth client secret"));
        clientSecret = field("GOCSPX-…", Credentials.get(Credentials.KEY_CLIENT_SECRET));
        form.addView(clientSecret);

        // Step 2: the account.
        form.addView(step("Step 2", "Connect your account"));
        form.addView(body("The browser opens on Google's consent screen. Everything else, "
                + "publisher id included, is read back automatically."));

        action = filledButton(actionLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startSignIn();
            }
        });
        form.addView(action, marginTop(dp(16)));

        status = body("");
        status.setVisibility(View.GONE);
        form.addView(status);

        TextView check = link("Run a connection check");
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                persistClient();
                status.setText("Checking…");
                status.setVisibility(View.VISIBLE);
                OAuthFlow.diagnose(CredentialsActivity.this, signInCallback());
            }
        });
        form.addView(check);

        disconnect = link("Disconnect this account");
        disconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Credentials.signOut();
                Toast.makeText(CredentialsActivity.this, "Disconnected.", Toast.LENGTH_SHORT).show();
                restartApp();
            }
        });
        form.addView(disconnect);

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

        // The code came back and the app is in the foreground again, which is exactly when the
        // exchange can reach the network. Only a captured code runs on its own: it is consumed by
        // the attempt, so this cannot repeat. Anything else is driven by the button.
        if (OAuthFlow.hasPendingCode()) {
            action.setEnabled(false);
            action.setText("Finishing…");
            OAuthFlow.completePending(this, signInCallback());
            return;
        }

        boolean connected = Credentials.isConfigured();

        action.setEnabled(true);
        action.setText(actionLabel());
        disconnect.setVisibility(connected ? View.VISIBLE : View.GONE);

        String last = Credentials.get(Credentials.KEY_LAST_STATUS);
        if (connected && last.isEmpty()) {
            last = "Connected as " + Credentials.get(Credentials.KEY_PUBLISHER_ID);
        }

        status.setText(last);
        status.setVisibility(last.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The console is only usable on a desktop browser, so the steps are worded to be followed there. */
    private static final String[] GUIDE = {
            "Open console.cloud.google.com and create a project.",
            "APIs & Services → Library → Enable the AdMob API, then the AdSense "
                    + "Management API, which fills the payments card.",
            "APIs & Services → OAuth consent screen → External → add your own Google address "
                    + "under Test users.",
            "Credentials → Create credentials → OAuth client ID → Application type: Desktop app.",
            "Copy the client id and the client secret it shows into the two fields below.",
    };

    private LinearLayout buildGuide() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(4), dp(8), 0, 0);

        for (int i = 0; i < GUIDE.length; i++) {
            section.addView(guideStep(i + 1, GUIDE[i]));
        }

        TextView open = link("Open the Google Cloud Console");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://console.cloud.google.com/apis/credentials")));
                } catch (Exception exception) {
                    Toast.makeText(CredentialsActivity.this, "No browser found.",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        section.addView(open);

        TextView note = body("Keep the consent screen in Testing: it is enough for your own "
                + "account, but Google then expires the connection every seven days.");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        section.addView(note);

        return section;
    }

    /** One numbered row: the badge keeps the sequence readable at a glance on a narrow screen. */
    private LinearLayout guideStep(int number, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);

        TextView badge = new TextView(this);
        badge.setText(String.valueOf(number));
        badge.setTextColor(onPrimary);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setGravity(Gravity.CENTER);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(primary);
        badge.setBackground(circle);

        int size = dp(22);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(size, size);
        badgeParams.rightMargin = dp(12);
        row.addView(badge, badgeParams);

        TextView content = new TextView(this);
        content.setText(text);
        content.setTextColor(onSurfaceVariant);
        content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        content.setLineSpacing(dp(3), 1f);
        row.addView(content);

        return row;
    }

    private static String actionLabel() {
        if (OAuthFlow.needsAccount()) return "Finish connecting";
        return Credentials.isConfigured() ? "Reconnect" : "Sign in with Google";
    }

    private OAuthFlow.Callback signInCallback() {
        return new OAuthFlow.Callback() {
            @Override
            public void onFinished(boolean success, String message) {
                action.setEnabled(true);
                action.setText(actionLabel());

                status.setText(message);
                status.setVisibility(View.VISIBLE);

                if (success) restartApp();
            }
        };
    }

    private void startSignIn() {
        persistClient();

        if (!Credentials.hasClient()) {
            Toast.makeText(this, "Fill in step 1 first.", Toast.LENGTH_LONG).show();
            return;
        }

        // The tokens are already held and only the account is missing, so there is nothing for
        // Google to consent to a second time.
        if (OAuthFlow.needsAccount()) {
            action.setEnabled(false);
            action.setText("Finishing…");
            OAuthFlow.retryAccount(this, signInCallback());
            return;
        }

        action.setEnabled(false);
        action.setText("Waiting for the browser…");

        OAuthFlow.start(this, Credentials.effectiveClientId(), Credentials.effectiveClientSecret(),
                signInCallback());
    }

    private void persistClient() {
        Credentials.put(Credentials.KEY_CLIENT_ID, clientId.getText().toString());
        Credentials.put(Credentials.KEY_CLIENT_SECRET, clientSecret.getText().toString());
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

        // Clearing the task is not enough: the account, the pro flag and the view models all live
        // in memory, so the app would carry the old session into the new screen. Ending the process
        // makes the restart real, and Android brings the launch activity back in a fresh one.
        Runtime.getRuntime().exit(0);
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

        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
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

    private TextView step(String number, String title) {
        TextView view = new TextView(this);
        view.setText(number + " · " + title);
        view.setTextColor(primary);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setPadding(0, dp(32), 0, 0);
        return view;
    }

    private TextView body(String content) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(onSurfaceVariant);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(8), 0, 0);
        return view;
    }

    private TextView label(String content) {
        TextView view = body(content);
        view.setTextColor(onSurface);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(0, dp(16), 0, dp(6));
        return view;
    }

    private TextView link(String content) {
        TextView view = body(content);
        view.setTextColor(primary);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(0, dp(20), 0, 0);
        view.setClickable(true);
        return view;
    }

    /** A filled button: fully rounded, primary container, label in the matching on-primary role. */
    private TextView filledButton(String text, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(text);
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
    private EditText field(String hint, String value) {
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
