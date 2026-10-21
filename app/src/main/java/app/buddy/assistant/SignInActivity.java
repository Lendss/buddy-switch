package app.buddy.assistant;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static app.buddy.assistant.Ui.*;

/**
 * This fork's agent is Claude Code in Termux: this screen gets Termux ready for Buddy (installed, allowed to run
 * commands) and checks Claude Code there. Installing, signing in and updating happen in Termux itself, so every
 * "set up the agent" button in the app leads here.
 */
public class SignInActivity extends Screen implements Account.Listener {
    static final String TERMUX_URL = "https://f-droid.org/packages/com.termux/";
    /** The line Termux needs before it accepts commands from other apps (RUN_COMMAND). */
    static final String PROPS = "grep -qx 'allow-external-apps *= *true' ~/.termux/termux.properties 2>/dev/null"
            + " || echo 'allow-external-apps=true' >> ~/.termux/termux.properties; termux-reload-settings";
    private static final int ASK_RUN_COMMAND = 1;

    private LinearLayout body, bottom;
    private final Account acc = Account.CLAUDE; // the only agent in this fork

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout top = row(this);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.signin_back));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        root.addView(top, full());
        ScrollView scroll = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 24), dp(this, 8), dp(this, 24), dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        bottom = column(this);
        bottom.setPaddingRelative(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 16));
        root.addView(bottom, full());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        Account.addListener(this);
        acc.refresh(this);
        render();
    }

    @Override
    protected void onPause() {
        Account.removeListener(this);
        super.onPause();
    }

    @Override
    public void onAccountChanged() {
        if (isFinishing() || isDestroyed()) return;
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == ASK_RUN_COMMAND) acc.refresh(this);
        render();
    }

    /** What still stands between Buddy and Claude Code in Termux, in a line; null when it's ready. */
    static String problem(Context c) {
        if (!Termux.installed(c)) return c.getString(R.string.termux_missing_short);
        if (!Termux.allowed(c)) return c.getString(R.string.termux_allow_short);
        switch (Account.CLAUDE.step) {
            case SIGNED_IN:
                return null;
            case NEED_INSTALL:
                return c.getString(R.string.termux_claude_missing_short);
            case SIGNED_OUT:
                return c.getString(R.string.termux_signin_short);
            case ERROR:
                return Account.CLAUDE.detail;
            default:
                return c.getString(R.string.signin_checking);
        }
    }

    private void render() {
        body.removeAllViews();
        bottom.removeAllViews();
        if (!Termux.installed(this)) {
            header(R.drawable.ms_terminal, getString(R.string.termux_missing_title), getString(R.string.termux_missing_text), false);
            primary(getString(R.string.termux_get), v -> openUrl(TERMUX_URL));
            secondary(getString(R.string.termux_check_again), v -> render());
            return;
        }
        if (!Termux.allowed(this)) {
            header(R.drawable.ms_shield_person, getString(R.string.termux_allow_title), getString(R.string.termux_allow_text), false);
            propsNote();
            primary(getString(R.string.termux_allow), v -> requestPermissions(new String[]{Termux.PERMISSION}, ASK_RUN_COMMAND));
            // Android stops asking after the user refuses twice: then it's on the app's info page
            secondary(getString(R.string.setup_app_info), v -> startActivity(new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
            return;
        }
        switch (acc.step) {
            case NEED_INSTALL:
                header(R.drawable.ms_terminal, getString(R.string.termux_claude_missing_title),
                        getString(R.string.termux_claude_missing_text), false);
                primary(getString(R.string.termux_open), v -> openTermux());
                secondary(getString(R.string.termux_check_again), v -> acc.refresh(this));
                break;
            case SIGNED_OUT:
                header(R.drawable.ms_shield_person, getString(R.string.termux_signin_title), getString(R.string.termux_signin_text), false);
                primary(getString(R.string.termux_open), v -> openTermux());
                secondary(getString(R.string.termux_check_again), v -> acc.refresh(this));
                break;
            case SIGNED_IN:
                header(R.drawable.ms_check, getString(R.string.signin_signed_in), (acc.email.isEmpty()
                        ? getString(R.string.signin_agent_ready, acc.name()) : acc.email)
                        + (acc.plan.isEmpty() ? "" : " · " + acc.planLabel()), true);
                if (!acc.version.isEmpty()) note(getString(R.string.signin_version, acc.name(), acc.version));
                note(getString(R.string.termux_same_claude));
                primary(getString(R.string.signin_done), v -> finish());
                secondary(getString(R.string.termux_open), v -> openTermux());
                break;
            case ERROR:
                header(R.drawable.ms_error, getString(R.string.signin_error_title), acc.detail, false);
                // the usual cause: Termux turns away other apps' commands until allow-external-apps is set
                propsNote();
                primary(getString(R.string.signin_try_again), v -> {
                    acc.step = Account.Step.CHECKING;
                    acc.refresh(this);
                    render();
                });
                secondary(getString(R.string.termux_open), v -> openTermux());
                break;
            default: // checking
                header(R.drawable.ms_terminal, acc.name(), getString(R.string.signin_checking), false);
                spinnerRow();
                break;
        }
    }

    /** The termux.properties line, as a command to copy into Termux. */
    private void propsNote() {
        note(getString(R.string.termux_props_note));
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 8);
        body.addView(codeBlock(this, PROPS), p);
        Button copy = button(this, getString(R.string.setup_copy_command), false);
        copy.setOnClickListener(v -> {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                    android.content.ClipData.newPlainText("Termux", PROPS));
            Toast.makeText(this, R.string.setup_command_copied, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams cp = wrap();
        cp.topMargin = dp(this, 8);
        body.addView(copy, cp);
    }

    private void openTermux() {
        Intent i = getPackageManager().getLaunchIntentForPackage(Termux.PACKAGE);
        if (i != null) startActivity(i);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.signin_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ pieces

    private void header(int iconRes, String title, String sub, boolean ok) {
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(oval(ok ? Theme.withAlpha(OK, 0x2E) : ACCENT_SOFT));
        ic.addView(icon(this, iconRes, ok ? OK : ACCENT_TEXT, 28),
                new FrameLayout.LayoutParams(dp(this, 28), dp(this, 28), Gravity.CENTER));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(this, 56), dp(this, 56));
        ip.topMargin = dp(this, 8);
        body.addView(ic, ip);
        TextView t = title(this, title, 26);
        t.setPaddingRelative(0, dp(this, 18), 0, dp(this, 6));
        body.addView(t);
        body.addView(text(this, sub, 16, MUTED));
    }

    private void spinnerRow() {
        LinearLayout r = row(this);
        r.setPaddingRelative(0, dp(this, 20), 0, 0);
        r.addView(spinner(this, 22));
        body.addView(r, full());
    }

    private void note(String s) {
        TextView t = text(this, s, 14, MUTED);
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 14);
        body.addView(t, p);
    }

    private void primary(String label, View.OnClickListener l) {
        Button b = button(this, label, true);
        b.setOnClickListener(l);
        bottom.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52)));
    }

    private void secondary(String label, View.OnClickListener l) {
        Button b = button(this, label, false);
        b.setBackgroundResource(ripple(this, false));
        b.setTextColor(TEXT);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        p.topMargin = dp(this, 4);
        bottom.addView(b, p);
    }
}
