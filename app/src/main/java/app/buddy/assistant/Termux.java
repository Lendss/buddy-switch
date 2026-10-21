package app.buddy.assistant;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Termux (com.termux) runs the agent in this fork, instead of Buddy's built-in Linux: Claude Code is the one installed
 * in Termux, with its sign-in, settings, MCP servers and memory. Commands go through Termux's RUN_COMMAND service, so
 * they run as Termux itself and everything they write stays Termux's (root would leave files Termux can't read). That
 * needs allow-external-apps=true in ~/.termux/termux.properties and the com.termux.permission.RUN_COMMAND permission.
 */
final class Termux {
    static final String PACKAGE = "com.termux";
    static final String PREFIX = "/data/data/com.termux/files/usr";
    static final String HOME = "/data/data/com.termux/files/home";
    static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String EXTRA = "com.termux.RUN_COMMAND_";

    /** What a command did: its exit code and output, and Termux's own error ("" if none) if it couldn't run it. */
    static final class Result {
        final int exitCode;
        final String stdout, stderr, error;

        Result(int exitCode, String stdout, String stderr, String error) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.error = error;
        }
    }

    interface Callback {
        void done(Result r);
    }

    private static final AtomicInteger ids = new AtomicInteger();
    private static Handler results;

    private Termux() {
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Buddy may run commands in Termux (the RUN_COMMAND permission is granted). */
    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean ready(Context c) {
        return installed(c) && allowed(c);
    }

    /**
     * Starts {@code executable args…} in Termux in the background (no terminal session), with {@code stdin} (or none)
     * as its input. {@code done} (or none) is called on a background thread once it has finished.
     */
    static void start(Context c, String executable, String[] args, String stdin, Callback done) {
        Context app = c.getApplicationContext();
        Intent i = new Intent("com.termux.RUN_COMMAND").setClassName(PACKAGE, PACKAGE + ".app.RunCommandService")
                .putExtra(EXTRA + "PATH", executable)
                .putExtra(EXTRA + "ARGUMENTS", args)
                .putExtra(EXTRA + "WORKDIR", HOME)
                .putExtra(EXTRA + "BACKGROUND", true)
                .putExtra(EXTRA + "COMMAND_LABEL", app.getString(R.string.app_name));
        if (stdin != null) i.putExtra(EXTRA + "STDIN", stdin);
        if (done != null) i.putExtra(EXTRA + "PENDING_INTENT", resultIntent(app, done));
        // Termux's service goes to the foreground right away, so this also works while Buddy is in the background
        app.startForegroundService(i);
    }

    /** Runs a shell command in Termux and waits for it: its output (stdout, then stderr) and exit code, like Linux.exec. */
    static String exec(Context c, String shell, int timeoutSec) throws Exception {
        return exec(c, shell, null, timeoutSec);
    }

    /** {@link #exec(Context, String, int)} with {@code stdin} as the command's input. */
    static String exec(Context c, String shell, String stdin, int timeoutSec) throws Exception {
        final Result[] out = new Result[1];
        final CountDownLatch finished = new CountDownLatch(1);
        start(c, PREFIX + "/bin/timeout", new String[]{String.valueOf(timeoutSec), PREFIX + "/bin/bash", "-c", shell},
                stdin, r -> {
                    out[0] = r;
                    finished.countDown();
                });
        // timeout ends the command itself; this only covers Termux not answering at all
        if (!finished.await(timeoutSec + 30L, TimeUnit.SECONDS)) return "\n[timed out after " + timeoutSec + "s]";
        Result r = out[0];
        if (!r.error.isEmpty()) throw new Exception(r.error);
        String text = r.stdout + (r.stdout.isEmpty() || r.stderr.isEmpty() ? "" : "\n") + r.stderr;
        return text + (r.exitCode == 124 ? "\n[timed out after " + timeoutSec + "s]" : "\n[exit " + r.exitCode + "]");
    }

    /** A one-shot PendingIntent that Termux sends its result bundle to; it calls {@code done} with it. */
    private static synchronized PendingIntent resultIntent(final Context app, final Callback done) {
        if (results == null) {
            HandlerThread t = new HandlerThread("termux-results");
            t.start();
            results = new Handler(t.getLooper());
        }
        int id = ids.incrementAndGet();
        String action = app.getPackageName() + ".TERMUX_RESULT_" + id;
        // Not exported: the PendingIntent is Buddy's own, so Termux sending it still reaches this receiver.
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                app.unregisterReceiver(this);
                Bundle b = intent.getBundleExtra("result");
                if (b == null) {
                    done.done(new Result(-1, "", "", "Termux sent no result"));
                    return;
                }
                // "err" is -1 when Termux ran the command; otherwise errmsg says why it couldn't
                String error = b.getInt("err", -1) == -1 ? "" : text(b, "errmsg");
                done.done(new Result(b.getInt("exitCode", -1), text(b, "stdout"), text(b, "stderr"),
                        error.isEmpty() && b.getInt("err", -1) != -1 ? "Termux couldn't run the command" : error));
            }
        }, new IntentFilter(action), null, results, Context.RECEIVER_NOT_EXPORTED);
        return PendingIntent.getBroadcast(app, id, new Intent(action).setPackage(app.getPackageName()),
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
    }

    private static String text(Bundle b, String key) {
        String s = b.getString(key);
        return s == null ? "" : s;
    }
}
