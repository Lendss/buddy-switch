package app.buddy.assistant;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static app.buddy.assistant.Ui.*;

/** Settings: setup checklist, appearance, agents and experience. */
public class SetupActivity extends Screen implements Account.Listener, RemoteSession.Listener {
    private ScrollView scroll;
    private LinearLayout list;
    /** Setup card open state; null = automatic (open until all steps are done). */
    private Boolean setupOpen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    private void build() {
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        scroll = new ScrollView(this);
        list = column(this);
        list.setPaddingRelative(0, dp(this, 12), 0, dp(this, 24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottomNav(this, NAV_SETTINGS, ChatHub.get(this).runningCount()), full());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        Account.addListener(this);
        RemoteSession.addListener(this);
        Account.CLAUDE.refresh(this);
        Account.CODEX.refresh(this);
        Account.OPENCODE.refresh(this);
        render();
    }

    @Override
    protected void onPause() {
        Account.removeListener(this);
        RemoteSession.removeListener(this);
        super.onPause();
    }

    @Override
    public void onRemoteChanged() {
        if (!isFinishing() && !isDestroyed()) render();
    }

    @Override
    public void onAccountChanged() {
        if (!isFinishing() && !isDestroyed()) render();
    }

    /** Appearance changed on this screen: restyle everything in place, keeping the scroll position. */
    private void themeChanged() {
        Theme.changed(this);
        rethemed();
        int y = scroll.getScrollY();
        build();
        render();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private static final class Step {
        String title, body, action, action2;
        Boolean done;
        View.OnClickListener l, l2;
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        list.removeAllViews();

        LinearLayout head = column(this);
        head.setPaddingRelative(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 4));
        head.addView(title(this, getString(R.string.setup_settings_title), 28));
        TextView sub = text(this, getString(R.string.setup_settings_subtitle), 14, MUTED);
        sub.setPaddingRelative(0, dp(this, 4), 0, 0);
        head.addView(sub);
        list.addView(head, full());

        // ---------------------------------------------------------------- setup
        boolean a11y = SetupState.accessibilityEnabled(this);
        String backend = Prefs.backend(this);
        Account acc = Account.of(backend);
        Step[] steps = new Step[5];

        String a11yText = getString(R.string.setup_step_a11y_body);
        if (!a11y && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            a11yText += "\n\n" + getString(R.string.setup_step_a11y_restricted);
        }
        steps[0] = step(getString(R.string.setup_step_a11y_title), a11yText, a11y,
                a11y ? null : getString(R.string.setup_open_settings), v -> openAccessibilitySettings(this),
                a11y || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ? null : getString(R.string.setup_app_info),
                v -> openAppInfo(getPackageName()));

        boolean installed = SetupState.installed(this, backend);
        steps[1] = step(getString(R.string.setup_step_install_title, acc.name()),
                installed ? getString(R.string.setup_step_install_done, acc.name())
                        : acc.step == Account.Step.INSTALLING ? acc.detail
                        : getString(acc.claude() ? R.string.setup_step_install_claude : acc.opencode()
                        ? R.string.setup_step_install_opencode : R.string.setup_step_install_codex, acc.name()),
                installed, installed || acc.step == Account.Step.INSTALLING ? null : getString(R.string.setup_install),
                v -> openAgent(backend), null, null);

        boolean signed = SetupState.signedIn(this, backend);
        steps[2] = step(getString(R.string.setup_step_signin_title), acc.opencode()
                        ? getString(signed ? R.string.setup_step_signin_opencode_done : R.string.setup_step_signin_opencode)
                        : signed ? (acc.email.isEmpty() ? getString(R.string.setup_step_signin_done)
                        : getString(R.string.setup_step_signin_done_email, acc.email))
                        : getString(acc.claude() ? R.string.setup_step_signin_claude : R.string.setup_step_signin_codex),
                installed ? signed : null, installed && !signed ? getString(R.string.setup_sign_in) : null,
                v -> openAgent(backend), null, null);

        PowerManager pm = getSystemService(PowerManager.class);
        boolean buddyBg = pm.isIgnoringBatteryOptimizations(getPackageName());
        steps[3] = step(getString(R.string.setup_step_background_title), getString(buddyBg
                        ? R.string.setup_step_background_done : R.string.setup_step_background_body),
                buddyBg, buddyBg ? null : getString(R.string.setup_allow), v -> startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))),
                null, null);

        boolean replied = Prefs.hadReply(this);
        steps[4] = step(getString(R.string.setup_step_try_title), getString(replied ? R.string.setup_step_try_done
                        : a11y && signed ? R.string.setup_step_try_body : R.string.setup_step_try_wait),
                replied ? Boolean.TRUE : null, null, null, null, null);
        setupCard(steps);

        // ----------------------------------------------------------- appearance
        list.addView(sectionLabel(this, getString(R.string.setup_appearance)));
        LinearLayout ap = card();
        ap.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        ap.addView(fieldLabel(getString(R.string.setup_theme_mode), null));
        ap.addView(segmented(this, Theme.modes(this), Prefs.themeMode(this), false, v -> {
            Prefs.setThemeMode(this, v);
            themeChanged();
        }), topGap(8));
        ap.addView(fieldLabel(getString(R.string.setup_accent), Theme.hex(Prefs.accent(this))), topGap(18));
        ap.addView(swatches(), topGap(10));
        ap.addView(fieldLabel(getString(R.string.setup_surface), null), topGap(18));
        ap.addView(segmented(this, Theme.surfaces(this), Prefs.surface(this), false, v -> {
            Prefs.setSurface(this, v);
            themeChanged();
        }), topGap(8));
        TextView sn = text(this, getString(R.string.setup_surface_note), 13, MUTED);
        ap.addView(sn, topGap(8));
        addCard(ap);

        // ---------------------------------------------------------------- agent
        list.addView(sectionLabel(this, getString(R.string.setup_agent)));
        LinearLayout ag = card();
        ag.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        ag.addView(title(this, getString(R.string.setup_default_brain), 15));
        TextView brainInfo = text(this, getString(R.string.setup_default_brain_info), 13, MUTED);
        brainInfo.setPaddingRelative(0, dp(this, 2), 0, 0);
        ag.addView(brainInfo);
        ag.addView(segmented(this, new String[][]{{"claude", "Claude Code"}, {"codex", "Codex"}, {"opencode", "OpenCode"}},
                backend, false, v -> {
                    Prefs.setBackend(this, v);
                    Toast.makeText(this, getString(R.string.setup_default_brain_set, Account.nameOf(v)),
                            Toast.LENGTH_LONG).show();
                    render();
                }, notReady(), v -> {
                    Toast.makeText(this, getString(R.string.setup_set_up_first, Account.nameOf(v)), Toast.LENGTH_SHORT).show();
                    openAgent(v);
                }), topGap(12));
        for (Account a : Account.ALL) ag.addView(agentRow(a.agent), topGap(a == Account.CLAUDE ? 12 : 4));
        addCard(ag);

        // ---------------------------------------------------------- phone tools
        list.addView(sectionLabel(this, getString(R.string.setup_phone_tools)));
        LinearLayout mcp = card();
        mcp.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        mcp.addView(text(this, getString(R.string.setup_phone_tools_info), 14, MUTED));
        mcp.addView(segmented(this, new String[][]{{"claude", "claude"}, {"codex", "codex"}, {"opencode", "opencode"}},
                mcpAgent, true, v -> {
                    mcpAgent = v;
                    render();
                }), topGap(12));
        String token = Prefs.token(this);
        String url = "http://127.0.0.1:" + McpServer.PORT + "/mcp";
        final String cmd = "codex".equals(mcpAgent)
                // Codex reads the bearer token from an environment variable, so save it in ~/.bashrc too.
                ? "codex mcp add phone --url " + url + " --bearer-token-env-var BUDDY_TOKEN"
                + " && echo 'export BUDDY_TOKEN=" + token + "' >> ~/.bashrc && export BUDDY_TOKEN=" + token
                : "opencode".equals(mcpAgent)
                // OpenCode reads extra config from OPENCODE_CONFIG_CONTENT; keep it for every shell
                ? "echo 'export OPENCODE_CONFIG_CONTENT='\\''{\"mcp\":{\"phone\":{\"type\":\"remote\",\"url\":\"" + url
                + "\",\"oauth\":false,\"timeout\":310000,\"headers\":{\"Authorization\":\"Bearer " + token
                + "\"}}}}'\\''' >> ~/.bashrc && . ~/.bashrc"
                : "claude mcp add --transport http -s user phone " + url
                + " --header \"Authorization: Bearer " + token + "\"";
        // the token is masked on screen so screenshots never leak it
        mcp.addView(codeBlock(this, cmd.replace(token, token.substring(0, 4) + "••••••••")), topGap(12));
        Button copy = button(this, getString(R.string.setup_copy_command), false);
        copy.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ms_content_copy, 0, 0, 0);
        copy.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(TEXT));
        copy.setCompoundDrawablePadding(dp(this, 8));
        copy.setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("Buddy MCP", cmd));
            Toast.makeText(this, R.string.setup_command_copied, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams cpp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
        cpp.topMargin = dp(this, 12);
        mcp.addView(copy, cpp);
        addCard(mcp);

        // ---------------------------------------------------------------- voice
        list.addView(sectionLabel(this, getString(R.string.setup_voice)));
        LinearLayout vc = card();
        vc.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        vc.addView(fieldLabel(getString(R.string.setup_voice_input), null));
        String engine = Prefs.voiceEngine(this);
        // Claude's dictation needs Claude Code signed in, ChatGPT's voice needs Codex: otherwise greyed out
        java.util.Set<String> noVoice = new java.util.HashSet<>();
        if (!SetupState.signedIn(this, "claude")) noVoice.add("claude");
        if (!SetupState.signedIn(this, "codex")) noVoice.add("chatgpt");
        vc.addView(segmented(this, new String[][]{{"phone", getString(R.string.setup_voice_phone)}, {"claude", "Claude"},
                        {"chatgpt", "ChatGPT"}},
                engine, false, v -> {
                    Prefs.setVoiceEngine(this, v);
                    render();
                }, noVoice, this::needSignIn), topGap(8));
        String engineNote = getString("claude".equals(engine) ? R.string.setup_voice_input_claude
                : "chatgpt".equals(engine) ? R.string.setup_voice_input_chatgpt : R.string.setup_voice_input_phone);
        vc.addView(text(this, engineNote, 13, MUTED), topGap(8));
        vc.addView(fieldLabel(getString(R.string.setup_voice_output), null), topGap(16));
        String speech = Prefs.speechEngine(this);
        vc.addView(segmented(this, new String[][]{{"phone", getString(R.string.setup_voice_phone)}, {"chatgpt", "ChatGPT"}},
                speech, false, v -> {
            Prefs.setSpeechEngine(this, v);
            render();
        }, noVoice, this::needSignIn), topGap(8));
        vc.addView(text(this, getString("chatgpt".equals(speech) ? R.string.setup_voice_output_chatgpt
                : R.string.setup_voice_output_phone), 13, MUTED), topGap(8));
        if ("chatgpt".equals(speech)) {
            LinearLayout voices = segmented(this, CodexSpeech.VOICES, Prefs.speechVoice(this), false, v -> {
                Prefs.setSpeechVoice(this, v);
                render();
                Voice.stopSpeaking();
                Voice.speak(this, getString(R.string.setup_voice_sample));
            });
            vc.addView(voices, topGap(8));
        }
        vc.addView(fieldLabel(getString(R.string.setup_languages), Voice.languageName(Prefs.voiceLang(this))), topGap(16));
        TextView lh = text(this, getString(R.string.setup_languages_hint), 13, MUTED);
        vc.addView(lh, topGap(4));
        java.util.List<String> chosen = new java.util.ArrayList<>(java.util.Arrays.asList(Prefs.voiceLangs(this).split(",")));
        LinearLayout chipRow = null;
        int inRow = 0;
        for (String[] lang : Voice.LANGUAGES) {
            if (chipRow == null || inRow == 3) {
                chipRow = row(this);
                vc.addView(chipRow, topGap(8));
                inRow = 0;
            }
            boolean on = chosen.contains(lang[0]);
            boolean def = lang[0].equals(Prefs.voiceLang(this));
            TextView chip = text(this, (def ? "★ " : "") + lang[1], 13, on ? ACCENT_TEXT : TEXT);
            chip.setTypeface(sans(this, on ? 600 : 400));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setPaddingRelative(dp(this, 8), dp(this, 8), dp(this, 8), dp(this, 8));
            chip.setBackground(on ? outlined(this, ACCENT_SOFT, 10, Theme.withAlpha(ACCENT, 0x66), 1)
                    : outlined(this, 0x00000000, 10, OUTLINE, 1));
            chip.setOnClickListener(v -> {
                java.util.List<String> cur = new java.util.ArrayList<>(java.util.Arrays.asList(Prefs.voiceLangs(this).split(",")));
                if (cur.contains(lang[0])) {
                    if (cur.size() == 1) return; // keep at least one
                    cur.remove(lang[0]);
                } else {
                    cur.add(lang[0]);
                }
                Prefs.setVoiceLangs(this, android.text.TextUtils.join(",", cur));
                render();
            });
            chip.setOnLongClickListener(v -> {
                java.util.List<String> cur = new java.util.ArrayList<>(java.util.Arrays.asList(Prefs.voiceLangs(this).split(",")));
                if (!cur.contains(lang[0])) cur.add(0, lang[0]);
                Prefs.setVoiceLangs(this, android.text.TextUtils.join(",", cur));
                Prefs.setVoiceLang(this, lang[0]);
                render();
                return true;
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (inRow > 0) cp.setMarginStart(dp(this, 6));
            chipRow.addView(chip, cp);
            inRow++;
        }
        while (chipRow != null && inRow++ < 3) chipRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        addCard(vc);

        // ------------------------------------------------------- remote control
        list.addView(sectionLabel(this, getString(R.string.setup_remote_control)));
        LinearLayout rcc = card();
        boolean claudeIn = SetupState.signedIn(this, "claude");
        rcc.addView(switchRow(this, getString(R.string.setup_remote_session), getString(R.string.setup_remote_session_info),
                Prefs.remoteSession(this), on -> {
            if (on && !claudeIn) {
                Toast.makeText(this, R.string.setup_remote_sign_in_first, Toast.LENGTH_LONG).show();
                render();
                return;
            }
            Prefs.setRemoteSession(this, on);
            if (on) RemoteSession.ensure(this);
            else RemoteSession.stop();
            render();
        }), full());
        if (Prefs.remoteSession(this)) {
            LinearLayout st = row(this);
            st.setPaddingRelative(dp(this, 16), 0, dp(this, 16), dp(this, 14));
            RemoteSession.State rs = RemoteSession.state;
            st.addView(dot(this, rs == RemoteSession.State.ACTIVE ? OK : rs == RemoteSession.State.ERROR ? ERR : ACCENT, 8));
            TextView stt = monoText(this, rs == RemoteSession.State.ACTIVE
                    ? getString(R.string.setup_remote_active, Prefs.remoteName(this))
                    : rs == RemoteSession.State.ERROR ? RemoteSession.detail
                    : getString(rs == RemoteSession.State.STARTING ? R.string.setup_remote_starting
                    : R.string.setup_remote_stopped), 12,
                    rs == RemoteSession.State.ERROR ? ERR : MUTED);
            stt.setPaddingRelative(dp(this, 8), 0, 0, 0);
            st.addView(stt, weight1());
            rcc.addView(st, full());
            if (rs == RemoteSession.State.ACTIVE && RemoteSession.url != null) {
                Button open = button(this, getString(R.string.setup_remote_open), false);
                open.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RemoteSession.url)));
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.setup_remote_open_failed, Toast.LENGTH_SHORT).show();
                    }
                });
                LinearLayout.LayoutParams op = wrap();
                op.setMargins(dp(this, 16), 0, dp(this, 16), dp(this, 14));
                rcc.addView(open, op);
            }
        }
        addCard(rcc);

        // chats from the Termux days: their conversations stayed in Termux, so agents can't continue them
        final java.util.List<Sessions.Session> old = termuxChats();
        if (!old.isEmpty()) {
            LinearLayout oc = card();
            LinearLayout r = listRow(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 12), dp(this, 14));
            LinearLayout t = column(this);
            t.addView(title(this, getString(R.string.setup_old_chats_title), 15));
            t.addView(text(this, getResources().getQuantityString(R.plurals.setup_old_chats_body, old.size(), old.size()),
                    13, MUTED));
            r.addView(t, weight1());
            r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
            r.setOnClickListener(v -> confirmSheet(this,
                    getResources().getQuantityString(R.plurals.setup_old_chats_remove, old.size(), old.size()),
                    getString(R.string.setup_old_chats_remove_body), getString(R.string.setup_remove), true, () -> {
                        ChatHub hub = ChatHub.get(this);
                        for (Sessions.Session s : old) hub.delete(s);
                        Toast.makeText(this, R.string.setup_removed, Toast.LENGTH_SHORT).show();
                        render();
                    }));
            oc.addView(r, full());
            addCard(oc);
        }

        // ----------------------------------------------------------- experience
        list.addView(sectionLabel(this, getString(R.string.setup_experience)));
        LinearLayout ex = card();
        ex.addView(switchRow(this, getString(R.string.setup_chat_bubble), getString(R.string.setup_chat_bubble_info),
                Prefs.bubbleEnabled(this), on -> {
            Prefs.setBubbleEnabled(this, on);
            if (on) HiddenNotice.cancel(this);
            BuddyService svc = BuddyService.get();
            if (svc != null) svc.applyBubbleSetting();
        }), full());
        ex.addView(divider(), lineParams());
        ex.addView(switchRow(this, getString(R.string.setup_tap_to_talk), getString(R.string.setup_tap_to_talk_info),
                Prefs.tapToTalk(this), on -> Prefs.setTapToTalk(this, on)), full());
        ex.addView(divider(), lineParams());
        ex.addView(switchRow(this, getString(R.string.setup_read_aloud), getString(R.string.setup_read_aloud_info),
                Prefs.speakReplies(this), on -> {
                    Prefs.setSpeakReplies(this, on);
                    if (!on) Voice.stopSpeaking();
                }), full());
        ex.addView(divider(), lineParams());
        if (!Flavor.PLAY) { // the Play build is updated by Play
            ex.addView(switchRow(this, getString(R.string.setup_update_notices), getString(R.string.setup_update_notices_info),
                    Prefs.updateNotices(this), on -> {
                Prefs.setUpdateNotices(this, on);
                if (!on) Updates.cancel(this);
                else Prefs.setUpdateNotified(this, ""); // notify again about a version muted before
            }), full());
            ex.addView(divider(), lineParams());
            LinearLayout upd = listRow(this);
            upd.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout ut = column(this);
            ut.addView(title(this, getString(R.string.setup_check_updates), 15));
            ut.addView(text(this, getString(R.string.setup_check_updates_info, BuildInfo.VERSION), 13, MUTED), topGap(2));
            upd.addView(ut, weight1());
            upd.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 20));
            upd.setOnClickListener(v -> Updates.showSheet(this));
            ex.addView(upd, full());
            ex.addView(divider(), lineParams());
        }
        LinearLayout perms = listRow(this);
        perms.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout pt = column(this);
        pt.addView(title(this, getString(R.string.setup_app_permissions), 15));
        pt.addView(text(this, getString(R.string.setup_app_permissions_info), 13, MUTED), topGap(2));
        perms.addView(pt, weight1());
        perms.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 20));
        perms.setOnClickListener(v -> startActivity(new android.content.Intent(this, AppPermissionsActivity.class)));
        ex.addView(perms, full());
        ex.addView(divider(), lineParams());
        final boolean dev = Prefs.devMode(this);
        if (!Flavor.PLAY) { // building and installing apps isn't allowed on Play
            ex.addView(protect(switchRow(this, getString(R.string.setup_dev_mode), getString(R.string.setup_dev_mode_info),
                    dev, on -> {
                Prefs.setDevMode(this, on);
                render();
            })), full());
            ex.addView(divider(), lineParams());
        }
        ex.addView(protect(switchRow(this, getString(R.string.setup_agents_tap), getString(R.string.setup_agents_tap_info),
                Prefs.agentsTapBuddy(this), on -> {
            Prefs.setAgentsTapBuddy(this, on);
            render();
        })), full());
        if (dev) {
            LinearLayout warn = row(this);
            warn.setGravity(Gravity.TOP);
            warn.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            warn.setBackground(rounded(this, ERR_SOFT, 12));
            warn.addView(icon(this, R.drawable.ms_warning, ERR, 18));
            TextView wt = text(this, getString(R.string.setup_dev_mode_warning), 13, ERR);
            wt.setPaddingRelative(dp(this, 10), 0, 0, 0);
            warn.addView(wt, weight1());
            LinearLayout.LayoutParams wp = full();
            wp.setMargins(dp(this, 12), 0, dp(this, 12), dp(this, 12));
            ex.addView(warn, wp);
        }
        addCard(ex);

        TextView ver = monoText(this, "buddy " + BuildInfo.VERSION + " · " + getPackageName(), 12, MUTED);
        ver.setGravity(Gravity.CENTER);
        ver.setPaddingRelative(0, dp(this, 24), 0, 0);
        list.addView(ver, full());
        TextView lic = text(this, getString(R.string.setup_licences), 13, ACCENT_TEXT);
        lic.setGravity(Gravity.CENTER);
        lic.setPaddingRelative(0, dp(this, 8), 0, dp(this, 4));
        lic.setOnClickListener(v -> licences());
        list.addView(lic, full());
        TextView priv = text(this, getString(R.string.setup_privacy_policy), 13, ACCENT_TEXT);
        priv.setGravity(Gravity.CENTER);
        priv.setPaddingRelative(0, dp(this, 4), 0, dp(this, 8));
        priv.setOnClickListener(v -> openUrl(Ui.PRIVACY_URL));
        list.addView(priv, full());
    }

    // ------------------------------------------------------------- setup card

    private Step step(String title, String body, Boolean done, String action, View.OnClickListener l,
                      String action2, View.OnClickListener l2) {
        Step s = new Step();
        s.title = title;
        s.body = body;
        s.done = done;
        s.action = action;
        s.l = l;
        s.action2 = action2;
        s.l2 = l2;
        return s;
    }

    private void setupCard(Step[] steps) {
        int done = 0, next = -1;
        for (int i = 0; i < steps.length; i++) {
            if (Boolean.TRUE.equals(steps[i].done)) done++;
            else if (next < 0) next = i;
        }
        boolean complete = next < 0;
        boolean open = setupOpen != null ? setupOpen : !complete;

        LinearLayout c = card();
        c.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        LinearLayout head = row(this);
        head.addView(title(this, getString(R.string.setup_card_title), 17));
        TextView n = monoText(this, done + "/5", 12, complete ? OK : ACCENT_TEXT);
        n.setTypeface(mono(this, 600));
        n.setPaddingRelative(dp(this, 8), dp(this, 2), 0, 0);
        head.addView(n, weight1());
        head.addView(icon(this, open ? R.drawable.ms_expand_less : R.drawable.ms_expand_more, MUTED, 22));
        head.setOnClickListener(v -> {
            setupOpen = !open;
            render();
        });
        c.addView(head, full());

        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(rounded(this, CARD2, 3));
        View fill = new View(this);
        fill.setBackground(rounded(this, complete ? OK : ACCENT, 3));
        bar.addView(fill, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        final int doneF = done;
        bar.post(() -> {
            FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            fp.width = bar.getWidth() * doneF / 5;
            fill.setLayoutParams(fp);
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 6));
        bp.topMargin = dp(this, 10);
        c.addView(bar, bp);

        if (!complete) {
            TextView guide = text(this, getString(R.string.setup_open_guide), 14, ACCENT_TEXT);
            guide.setTypeface(sans(this, 600));
            guide.setPaddingRelative(0, dp(this, 12), 0, 0);
            guide.setOnClickListener(v -> startActivity(new Intent(this, OnboardingActivity.class)));
            c.addView(guide);
        }
        if (!open) {
            TextView s = text(this, complete ? getString(R.string.setup_all_set)
                    : getString(R.string.setup_next_step, steps[next].title), 13, MUTED);
            s.setPaddingRelative(0, dp(this, 10), 0, 0);
            c.addView(single(s));
        } else {
            for (int i = 0; i < steps.length; i++) {
                Step st = steps[i];
                boolean isDone = Boolean.TRUE.equals(st.done), isNext = i == next;
                LinearLayout r = row(this);
                r.setGravity(Gravity.TOP);
                r.setPaddingRelative(0, dp(this, 14), 0, 0);
                FrameLayout circle = new FrameLayout(this);
                if (isDone) {
                    circle.setBackground(oval(OK));
                    ImageView ck = icon(this, R.drawable.ms_check, 0xFFFFFFFF, 16);
                    circle.addView(ck, new FrameLayout.LayoutParams(dp(this, 16), dp(this, 16), Gravity.CENTER));
                } else {
                    circle.setBackground(outlined(this, 0x00000000, 12, isNext ? ACCENT : CARD2, 2));
                    TextView num = monoText(this, String.valueOf(i + 1), 12, isNext ? ACCENT_TEXT : MUTED);
                    num.setTypeface(mono(this, 600));
                    num.setGravity(Gravity.CENTER);
                    circle.addView(num, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
                }
                r.addView(circle, new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));
                LinearLayout col = column(this);
                col.setPaddingRelative(dp(this, 12), dp(this, 2), 0, 0);
                TextView t = title(this, st.title, 15);
                if (!isDone && !isNext) t.setTextColor(MUTED);
                col.addView(t);
                if (isNext) {
                    TextView b = text(this, st.body, 14, MUTED);
                    b.setPaddingRelative(0, dp(this, 4), 0, 0);
                    col.addView(b);
                    if (st.action != null || st.action2 != null) {
                        LinearLayout btns = row(this);
                        if (st.action != null) {
                            Button a = button(this, st.action, true);
                            a.setOnClickListener(st.l);
                            btns.addView(a, wrap());
                        }
                        if (st.action2 != null) {
                            Button a2 = button(this, st.action2, false);
                            a2.setOnClickListener(st.l2);
                            LinearLayout.LayoutParams a2p = wrap();
                            if (st.action != null) {
                                btns.setOrientation(LinearLayout.VERTICAL); // two buttons side by side don't fit
                                btns.setGravity(Gravity.START);
                                a2p.topMargin = dp(this, 8);
                            }
                            btns.addView(a2, a2p);
                        }
                        LinearLayout.LayoutParams bt = full();
                        bt.topMargin = dp(this, 10);
                        col.addView(btns, bt);
                    }
                }
                r.addView(col, weight1());
                c.addView(r, full());
            }
        }
        LinearLayout.LayoutParams p = full();
        p.setMargins(dp(this, 16), dp(this, 14), dp(this, 16), 0);
        list.addView(c, p);
    }

    // ----------------------------------------------------------- appearance

    private View fieldLabel(String s, String right) {
        LinearLayout r = row(this);
        r.addView(title(this, s, 15), weight1());
        if (right != null) r.addView(monoText(this, right, 12, MUTED));
        return r;
    }

    private View swatches() {
        LinearLayout r = row(this);
        int current = Prefs.accent(this);
        boolean preset = false;
        for (String[] a : Theme.accents(this)) {
            int col = Theme.parse(a[0], 0xFFD97757);
            boolean on = col == current;
            preset |= on;
            r.addView(swatch(col, on, 0, a[1], v -> {
                Prefs.setAccent(this, col);
                themeChanged();
            }), new LinearLayout.LayoutParams(0, dp(this, 40), 1));
        }
        r.addView(swatch(preset ? CARD2 : current, !preset, preset ? R.drawable.ms_add : 0,
                getString(R.string.setup_custom_colour), v -> customAccent()), new LinearLayout.LayoutParams(0, dp(this, 40), 1));
        return r;
    }

    private View swatch(int color, boolean on, int iconRes, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setContentDescription(desc);
        FrameLayout ring = new FrameLayout(this);
        if (on) ring.setBackground(outlined(this, 0x00000000, 20, TEXT, 2));
        View dotV = new View(this);
        dotV.setBackground(oval(color));
        ring.addView(dotV, new FrameLayout.LayoutParams(dp(this, 30), dp(this, 30), Gravity.CENTER));
        if (iconRes != 0) {
            ImageView ic = icon(this, iconRes, MUTED, 18);
            ring.addView(ic, new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER));
        }
        f.addView(ring, new FrameLayout.LayoutParams(dp(this, 40), dp(this, 40), Gravity.CENTER));
        f.setOnClickListener(l);
        return f;
    }

    private void customAccent() {
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.setup_custom_accent_title), 18));
        TextView s = text(this, getString(R.string.setup_custom_accent_hint), 13, MUTED);
        s.setPaddingRelative(0, dp(this, 2), 0, dp(this, 12));
        c.addView(s);
        LinearLayout r = row(this);
        final View preview = new View(this);
        preview.setBackground(oval(Prefs.accent(this)));
        r.addView(preview, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        final EditText et = new EditText(this);
        et.setText(Theme.hex(Prefs.accent(this)));
        et.setTextColor(TEXT);
        et.setTypeface(mono(this, 400));
        et.setTextSize(15);
        et.setSingleLine(true);
        et.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        LinearLayout.LayoutParams ep = weight1();
        ep.setMarginStart(dp(this, 12));
        r.addView(et, ep);
        Button apply = button(this, getString(R.string.setup_apply), true);
        LinearLayout.LayoutParams app = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
        app.setMarginStart(dp(this, 8));
        r.addView(apply, app);
        c.addView(r, full());
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String h = e.toString().trim();
                boolean ok = h.replace("#", "").length() == 6;
                int col = ok ? Theme.parse(h, 0) : 0;
                ok &= col != 0;
                preview.setBackground(oval(ok ? col : CARD2));
                apply.setEnabled(ok);
                apply.setAlpha(ok ? 1f : 0.4f);
            }
        });
        final Dialog d = bottomSheet(this, c);
        apply.setOnClickListener(v -> {
            int col = Theme.parse(et.getText().toString().trim(), 0);
            if (col == 0) return;
            d.dismiss();
            Prefs.setAccent(this, col);
            themeChanged();
        });
        d.show();
    }

    // ---------------------------------------------------------------- helpers

    private LinearLayout card() {
        LinearLayout c = column(this);
        c.setBackground(rounded(this, CARD, 16));
        return c;
    }

    private void addCard(View c) {
        LinearLayout.LayoutParams p = full();
        p.setMargins(dp(this, 16), dp(this, 6), dp(this, 16), 0);
        list.addView(c, p);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        return v;
    }

    private LinearLayout.LayoutParams lineParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1));
        p.leftMargin = p.rightMargin = dp(this, 16);
        return p;
    }

    private LinearLayout.LayoutParams topGap(int dpv) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, dpv);
        return p;
    }

    /** Chats with messages but no conversation folder in Buddy's Linux: they ran in Termux. */
    private java.util.List<Sessions.Session> termuxChats() {
        java.util.List<Sessions.Session> out = new java.util.ArrayList<>();
        if (!Linux.installed(this)) return out;
        java.io.File dir = new java.io.File(Linux.root(this), "root/.buddy/sessions");
        for (Sessions.Session s : ChatHub.get(this).sessions.all) {
            if (!s.messages.isEmpty() && !s.running && !new java.io.File(dir, s.id).isDirectory()) out.add(s);
        }
        return out;
    }

    /** The bundled components (third_party/ in the repo, packed into the APK) and their licence texts. */
    private void licences() {
        StringBuilder sb = new StringBuilder();
        for (String f : new String[]{"README.md", "proot-LICENSE.txt", "talloc-LICENSE.txt", "libandroid-shmem-LICENSE.txt"}) {
            try (java.io.InputStream in = getAssets().open("third_party/" + f)) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                if (sb.length() > 0) sb.append("\n\n──────── ").append(f).append(" ────────\n\n");
                sb.append(o.toString("UTF-8"));
            } catch (Exception ignored) {
            }
        }
        ScrollView sv = new ScrollView(this);
        TextView t = monoText(this, sb.toString(), 11, TEXT);
        t.setTextIsSelectable(true);
        sv.addView(t);
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.setup_licences), 18));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.6f));
        sp.topMargin = dp(this, 10);
        c.addView(sv, sp);
        bottomSheet(this, c).show();
    }

    private void openAgent(String agent) {
        startActivity(new Intent(this, SignInActivity.class).putExtra("agent", agent));
    }

    /** Which agent the "phone tools in your own sessions" command is for. */
    private String mcpAgent = "claude";

    /** Agents that can't answer chats yet (not installed or not signed in): greyed out in pickers. */
    private java.util.Set<String> notReady() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (Account a : Account.ALL) if (!SetupState.signedIn(this, a.agent)) s.add(a.agent);
        return s;
    }

    /** A greyed-out voice option was tapped: say which agent it needs, and offer to set it up. */
    private void needSignIn(String option) {
        String agent = "claude".equals(option) ? "claude" : "codex";
        confirmSheet(this, getString(R.string.setup_sign_in_first, Account.nameOf(agent)),
                getString("claude".equals(option) ? R.string.setup_needs_claude : R.string.setup_needs_chatgpt,
                        Account.nameOf(agent)),
                getString(R.string.setup_set_up_agent, Account.nameOf(agent)), false, () -> openAgent(agent));
    }

    /** An agent with its state (not installed / signed out / signed in); tap to install or sign in. */
    private View agentRow(String agent) {
        Account a = Account.of(agent);
        String st = Prefs.agentState(this, agent);
        boolean busy = a.step == Account.Step.INSTALLING;
        String state = busy ? getString(R.string.setup_agent_installing)
                : !Linux.installed(this) || st == null || "NEED_INSTALL".equals(st) ? getString(R.string.setup_agent_not_installed)
                : a.opencode() ? (a.plan.isEmpty() ? getString(R.string.setup_agent_ready)
                : getString(R.string.setup_agent_ready_plan, a.planLabel()))
                : "SIGNED_IN".equals(st) ? (a.email.isEmpty() ? getString(R.string.setup_agent_signed_in)
                : getString(R.string.setup_agent_signed_in_email, a.email))
                : getString(R.string.setup_agent_not_signed_in);
        LinearLayout r = row(this);
        r.setPaddingRelative(0, dp(this, 10), 0, dp(this, 10));
        r.setBackgroundResource(ripple(this, false));
        r.addView(agentTile(this, agent));
        LinearLayout t = column(this);
        t.setPaddingRelative(dp(this, 12), 0, dp(this, 8), 0);
        t.addView(title(this, a.name(), 15));
        t.addView(single(monoText(this, state, 12, "SIGNED_IN".equals(st) && !busy ? OK : MUTED)));
        r.addView(t, weight1());
        r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
        r.setOnClickListener(v -> openAgent(agent));
        return r;
    }

    private void openAppInfo(String pkg) {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}
