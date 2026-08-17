package app.morphe.extension.admobile;

import android.app.Activity;
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
 * <p>Built in code rather than from a layout so the patch adds no resources: it only has to declare
 * the activity in the manifest, and there are no ids to keep in step with the host app.
 */
public final class CredentialsActivity extends Activity {

    private static final int BACKGROUND = 0xFF121212;
    private static final int FOREGROUND = 0xFFE0E0E0;
    private static final int HINT = 0xFF9E9E9E;
    private static final int ACCENT = 0xFFF0C040;

    private static final String[][] FIELDS = {
            {Credentials.KEY_CLIENT_ID, "OAuth client id", "000000000000-xxxx.apps.googleusercontent.com"},
            {Credentials.KEY_CLIENT_SECRET, "OAuth client secret", "GOCSPX-…"},
            {Credentials.KEY_REFRESH_TOKEN, "Refresh token", "scope: admob.readonly"},
            {Credentials.KEY_PUBLISHER_ID, "AdMob publisher id", "pub-0000000000000000"},
            {Credentials.KEY_EMAIL, "Account email", "shown as the account name"},
            {Credentials.KEY_TIME_ZONE, "Report time zone", "UTC"},
            {Credentials.KEY_CURRENCY, "Report currency", "USD"},
    };

    private final EditText[] inputs = new EditText[FIELDS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Credentials.init(this);

        int padding = dp(16);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, padding, padding, padding);

        form.addView(title("AdMob credentials"));
        form.addView(caption(
                "Used to refresh the AdMob API token directly, instead of signing in with Google. "
                        + "Stored only on this device."));

        for (int i = 0; i < FIELDS.length; i++) {
            String[] field = FIELDS[i];

            form.addView(label(field[1]));

            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setHint(field[2]);
            input.setHintTextColor(HINT);
            input.setTextColor(FOREGROUND);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setText(Credentials.get(field[0]));
            form.addView(input);

            inputs[i] = input;
        }

        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save();
            }
        });

        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp(24);
        form.addView(save, saveParams);

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(BACKGROUND);
        root.addView(form);

        setContentView(root);
    }

    private void save() {
        for (int i = 0; i < FIELDS.length; i++) {
            Credentials.put(FIELDS[i][0], inputs[i].getText().toString());
        }

        boolean configured = Credentials.isConfigured();
        Toast.makeText(
                this,
                configured
                        ? "Saved. Restart AdMobile."
                        : "Saved, but the client id, secret, refresh token and publisher id are all required.",
                Toast.LENGTH_LONG).show();

        if (configured) finish();
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(ACCENT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        return view;
    }

    private TextView caption(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(HINT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setPadding(0, dp(8), 0, dp(16));
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(0, dp(12), 0, 0);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
