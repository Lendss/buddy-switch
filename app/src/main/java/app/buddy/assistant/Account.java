package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * An agent and its account. This fork runs only Termux's own Claude Code: Buddy checks that it is installed and
 * signed in; installing, updating and signing in happen in the Termux terminal. Codex and OpenCode stay as names
 * (chats remember their brain) but are never ready.
 */
final class Account {
    enum Step { CHECKING, NEED_INSTALL, INSTALLING, SIGNED_OUT, WAITING_BROWSER, FINISHING, SIGNED_IN, ERROR }

    interface Listener {
        void onAccountChanged();
    }

    static final Account CLAUDE = new Account("claude");
    static final Account CODEX = new Account("codex");
    static final Account OPENCODE = new Account("opencode");
    static final Account[] ALL = {CLAUDE}; // this fork offers only Claude Code (Termux's)

    static Account of(String agent) {
        return "codex".equals(agent) ? CODEX : "opencode".equals(agent) ? OPENCODE : CLAUDE;
    }

    /** The agent's display name: "Claude Code", "Codex" or "OpenCode". */
    static String nameOf(String agent) {
        return of(agent).name();
    }

    /** Its command-line name, shown in mono labels: "claude-code", "codex" or "opencode". */
    static String cliOf(String agent) {
        return "codex".equals(agent) ? "codex" : "opencode".equals(agent) ? "opencode" : "claude-code";
    }

    final String agent;
    volatile Step step = Step.CHECKING;
    volatile String detail = "", email = "", plan = "";
    /** The installed agent's version ("2.1.40"), or "" if unknown. */
    volatile String version = "";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Context app;
    private static final List<Listener> listeners = new ArrayList<>();

    private Account(String agent) {
        this.agent = agent;
    }

    boolean claude() {
        return "claude".equals(agent);
    }

    boolean opencode() {
        return "opencode".equals(agent);
    }

    String name() {
        return claude() ? "Claude Code" : opencode() ? "OpenCode" : "Codex";
    }

    /** The plan to show: OpenCode's "free models" marker in the user's language, anything else as it is. */
    String planLabel() {
        return "free models".equals(plan) ? BuddyApp.str(R.string.onboarding_agent_free_models) : plan;
    }

    /** One line on what the agent runs on, for pickers. */
    String tagline() {
        return BuddyApp.str(claude() ? R.string.account_tagline_claude : opencode() ? R.string.account_tagline_opencode
                : R.string.account_tagline_codex);
    }

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void set(Step s, String d) {
        step = s;
        detail = d == null ? "" : d;
        Context a = app;
        // remembered, so screens can show progress right away before the next check finishes
        if (a != null && (s == Step.NEED_INSTALL || s == Step.SIGNED_OUT || s == Step.SIGNED_IN)) {
            Prefs.setAgentState(a, agent, s.name());
        }
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onAccountChanged();
        });
    }

    boolean busy() {
        return step == Step.INSTALLING || step == Step.WAITING_BROWSER || step == Step.FINISHING;
    }

    boolean ready() {
        return step == Step.SIGNED_IN;
    }

    /** Works out where things stand: installed? signed in? */
    void refresh(Context c) {
        app = c.getApplicationContext();
        if (busy()) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                // this fork runs only Termux's own Claude Code: Codex and OpenCode aren't offered
                if (!Termux.ready(app) || !claude()) {
                    set(Step.NEED_INSTALL, "");
                    return;
                }
                String out = Termux.exec(app, "command -v claude >/dev/null || { echo NOTINSTALLED; exit 0; }; "
                        + "claude auth status 2>/dev/null; echo; echo VER=$(claude --version 2>/dev/null)", 60);
                if (out.contains("NOTINSTALLED")) {
                    set(Step.NEED_INSTALL, "");
                    return;
                }
                version = ver(out);
                int from = out.indexOf('{'), to = out.lastIndexOf('}');
                if (from < 0 || to < from) { // no status: a slow phone hit the timeout, or claude failed
                    if (out.contains("[timed out")) throw new Exception("it took too long, please try again.");
                    set(Step.SIGNED_OUT, "");
                    return;
                }
                JSONObject j = new JSONObject(out.substring(from, to + 1));
                if (j.optBoolean("loggedIn")) {
                    email = j.optString("email", j.optString("emailAddress", ""));
                    plan = j.optString("subscriptionType", "");
                    set(Step.SIGNED_IN, "");
                } else {
                    set(Step.SIGNED_OUT, "");
                }
            } catch (Exception e) {
                set(Step.ERROR, app.getString(R.string.account_check_failed, name(), e.getMessage()));
            }
        }).start();
    }

    private static String ver(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("VER=\\D*(\\d+\\.\\d+\\.\\d+)").matcher(out);
        return m.find() ? m.group(1) : "";
    }
}
