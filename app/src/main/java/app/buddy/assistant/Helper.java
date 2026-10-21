package app.buddy.assistant;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs Buddy's helper script (assets/linux/buddy-run) in Termux (see {@link Termux}). The helper streams the
 * agent's progress back to the app over the local server (POST /event), like before. Buddy can't write into
 * Termux's home, so the script goes along with each run, its instructions (system prompt, App permissions)
 * written into it.
 */
final class Helper {
    private static final String[][] FILES = {
            // asset, path inside Linux, mode
            {"linux/buddy-run", "/root/bin/buddy-run", "700"},
            {"linux/codex-remote", "/root/bin/codex-remote", "700"}, // the Codex session (CodexRemote)
            {"linux/profile.sh", "/etc/profile.d/buddy.sh", "644"},
            {"linux/arecord", "/usr/local/bin/arecord", "755"},
            {"linux/asound-cards", "/etc/buddy/asound-cards", "644"},
            {"linux/arecord", "/usr/local/bin/rec", "755"}, // SoX's recorder, same arguments that matter (-r, -c)
            {"linux/system-prompt.md", "/root/.buddy/system-prompt.md", "600"},
            {"linux/AGENTS.md", "/root/.buddy/work/AGENTS.md", "600"},
            {"linux/android-app/SKILL.md", "/root/.buddy/work/.claude/skills/android-app/SKILL.md", "600"},
            {"linux/android-app/build-apk.sh", "/root/.buddy/work/.claude/skills/android-app/build-apk.sh", "700"},
            {"linux/android-app/setup-toolchain.sh", "/root/.buddy/work/.claude/skills/android-app/setup-toolchain.sh", "700"},
    };
    private static final ExecutorService pool = Executors.newCachedThreadPool();
    private static int syncedFor = -1;

    private Helper() {
    }

    /** Whether chats can run: Termux is there and lets Buddy run commands (Claude Code's own readiness: Account). */
    static boolean ready(Context c) {
        return Termux.ready(c);
    }

    /** Runs `buddy-run <token> args…` in Termux in the background. Throws with a readable message if Termux isn't ready. */
    static void run(Context c, String... args) {
        final Context app = c.getApplicationContext();
        if (!Termux.ready(app)) {
            throw new IllegalStateException(app.getString(R.string.helper_termux_missing)); // shown to the user
        }
        // bash -c <script> buddy-run <token> args…: the script sees the token as $1, like upstream's buddy-run
        final String[] cmd = new String[args.length + 4];
        cmd[0] = "-c";
        cmd[2] = "buddy-run";
        cmd[3] = Prefs.token(app);
        System.arraycopy(args, 0, cmd, 4, args.length);
        pool.execute(() -> {
            try {
                cmd[1] = script(app);
                // the helper reports through the local server; its result only matters when Termux couldn't run it
                Termux.start(app, Termux.PREFIX + "/bin/bash", cmd, null, r -> {
                    if (!r.error.isEmpty()) failed(args, r.error);
                    else if (r.exitCode != 0) android.util.Log.w("Buddy", "helper exit " + r.exitCode + ": " + r.stderr);
                });
            } catch (Exception e) {
                failed(args, e.getMessage());
            }
        });
    }

    /** Termux couldn't run the helper: an ask shows the error in its chat (nothing else would end it). */
    private static void failed(String[] args, String why) {
        android.util.Log.w("Buddy", "helper failed: " + why);
        BuddyService s = BuddyService.get();
        if (s == null || args.length < 2 || !"ask".equals(args[0])) return;
        try {
            s.onEvent(new org.json.JSONObject().put("type", "buddy_error").put("buddy_session", args[1])
                    .put("text", "Termux: " + why));
            s.onEvent(new org.json.JSONObject().put("type", "buddy_done").put("buddy_session", args[1]));
        } catch (org.json.JSONException ignored) {
        }
    }

    private static String helperScript, systemPrompt;

    /** buddy-run with Buddy's instructions in front of it, as shell variables ($BUDDY_SYSTEM, $BUDDY_PERMS). */
    private static String script(Context c) throws IOException {
        synchronized (Helper.class) {
            if (helperScript == null) {
                helperScript = asset(c, "linux/buddy-run");
                systemPrompt = asset(c, "linux/system-prompt.md");
            }
        }
        return heredoc("BUDDY_SYSTEM", systemPrompt) + heredoc("BUDDY_PERMS", permissions(c)) + helperScript;
    }

    /** A shell line setting {@code name} to {@code text} exactly (a quoted here-document: nothing in it is expanded). */
    static String heredoc(String name, String text) {
        String end = "BUDDY_EOF";
        while (("\n" + text + "\n").contains("\n" + end + "\n")) end += "_";
        return name + "=$(cat <<'" + end + "'\n" + text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + end + "\n)\n";
    }

    private static String asset(Context c, String path) throws IOException {
        try (InputStream in = c.getAssets().open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** The user's App permissions choices, which buddy-run appends to the agent's instructions ("" if none). */
    static String permissions(Context c) {
        StringBuilder trusted = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : Prefs.appSafetyAll(c).entrySet()) {
            if ("trust".equals(e.getValue())) trusted.append("- ").append(appName(c, e.getKey())).append(" (").append(e.getKey()).append(")\n");
        }
        String text = "";
        if (trusted.length() > 0) text += "Apps the user set to Trust:\n" + trusted;
        if (Prefs.autonomousDefault(c)) {
            text += (text.isEmpty() ? "" : "\n") + "The user also trusts every app they haven't set, except messaging, "
                    + "social, calls, email, shopping, payment and banking apps.\n";
        }
        StringBuilder never = new StringBuilder();
        for (Guard.Rule r : Guard.rules(c, "allow")) never.append("- “").append(r.label).append("” in ")
                .append(r.pkg == null ? "all apps" : appName(c, r.pkg)).append("\n");
        if (never.length() > 0) {
            text += (text.isEmpty() ? "" : "\n") + "Buttons the user marked \"Never ask\" (tap them without confirming "
                    + "when the task calls for it):\n" + never;
        }
        return text;
    }

    private static String appName(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Copies the helper files from the APK into Linux once per app version. */
    static synchronized void sync(Context c) throws IOException {
        if (syncedFor == BuildInfo.VERSION_CODE && new File(Linux.root(c), "root/bin/buddy-run").exists()) return;
        File root = Linux.root(c);
        for (String[] f : FILES) {
            File out = new File(root, f[1].substring(1));
            out.getParentFile().mkdirs();
            try (InputStream in = c.getAssets().open(f[0]); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            try {
                Os.chmod(out.getPath(), Integer.parseInt(f[2], 8));
            } catch (Exception ignored) {
            }
        }
        new File(root, "root/.buddy/sessions").mkdirs();
        syncedFor = BuildInfo.VERSION_CODE;
    }
}
