package app.buddy.assistant;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static app.buddy.assistant.Ui.*;

/**
 * Updates of this fork come from upstream Buddy's releases, brought over by Claude: the check compares upstream's
 * latest release with the version this build is based on (BuildInfo.UPSTREAM), and Update opens a chat where Claude
 * reviews every upstream version since then (UPSTREAM.md in the repo), says what it would take and skip, and after
 * the user's decisions ports them, builds and installs. Used by Check for updates, the daily check behind the
 * "update available" notification (Settings → Update notices), and the update_buddy tool.
 */
public final class Updates extends BroadcastReceiver {
    /** Upstream Buddy, where new versions come from. */
    static final String UPSTREAM_REPO = "ghorbelhamdi/buddy-android";
    private static final String API = "https://api.github.com/repos/" + UPSTREAM_REPO + "/releases?per_page=30";
    private static final String CHANNEL = "updates", ACTION_MUTE = "app.buddy.assistant.MUTE_UPDATES";
    private static final int ID = 3;
    private static final long DAY = 24 * 3600_000L;

    /** Upstream's releases newer than the one this build is based on, newest first. */
    static final class Release {
        /** The newest, and every newer one ("2.2.8", "2.2.6"…). */
        final String version;
        final List<String> versions;
        /** Their release notes, each under its version. */
        final String notes;

        Release(List<String> versions, String notes) {
            this.versions = versions;
            this.version = versions.isEmpty() ? BuildInfo.UPSTREAM : versions.get(0);
            this.notes = notes;
        }

        boolean newer() {
            return !versions.isEmpty();
        }
    }

    /** Asks GitHub (network: call off the main thread). */
    static Release latest() throws Exception {
        JSONArray all = new JSONArray(get(API));
        List<String> versions = new ArrayList<>();
        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < all.length(); i++) {
            JSONObject rel = all.getJSONObject(i);
            if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue;
            String v = rel.optString("tag_name").replaceFirst("^v", "");
            if (compare(v, BuildInfo.UPSTREAM) <= 0) continue;
            versions.add(v);
            String n = rel.optString("body").trim();
            if (n.length() > 1500) n = n.substring(0, 1500) + "…";
            notes.append("## ").append(v).append("\n\n").append(n).append("\n\n");
        }
        versions.sort((a, b) -> compare(b, a));
        return new Release(versions, notes.toString().trim());
    }

    // ------------------------------------------------------------ the update chat

    /** The chat bringing over version (Prefs), if it's still there. */
    static Sessions.Session chat(Context c, String version) {
        String[] u = Prefs.updateChat(c);
        if (u == null || !u[0].equals(version)) return null;
        return ChatHub.get(c).sessions.find(u[1]);
    }

    /** Opens the update chat for r, starting it (Claude's review) the first time. */
    static void openChat(Activity a, Release r) {
        Sessions.Session s = chat(a, r.version);
        if (s == null) {
            ChatHub hub = ChatHub.get(a);
            s = hub.create("claude");
            s.title = a.getString(R.string.update_chat_title, r.version);
            s.access = "full"; // git, the build and the install need the full tools
            Prefs.setUpdateChat(a, r.version, s.id);
            String err = hub.sendAs(s, a.getString(R.string.update_chat_first, r.version),
                    "[BUDDY_UPDATE] Upstream Buddy (github.com/" + UPSTREAM_REPO + ") has released "
                            + android.text.TextUtils.join(", ", r.versions) + "; this build is Buddy SW " + BuildInfo.VERSION
                            + ", based on upstream " + BuildInfo.UPSTREAM + ". Follow ~/buddy-switch/UPSTREAM.md: review "
                            + "every upstream version after " + BuildInfo.UPSTREAM + " up to " + r.version + " and report "
                            + "what's new, what you'd take, adapt or skip and why. Don't change anything yet: wait for "
                            + "the user's decisions. Answer in the user's language (the one they write to you in).");
            if (err != null) Toast.makeText(a, err, Toast.LENGTH_LONG).show();
        }
        cancel(a);
        a.startActivity(new Intent(a, ChatActivity.class).putExtra("id", s.id));
    }

    /** One line on the update chat for Settings, or null: Claude is working on it, or waits for the user. */
    static String chatStatus(Context c) {
        String[] u = Prefs.updateChat(c);
        if (u == null) return null;
        Sessions.Session s = ChatHub.get(c).sessions.find(u[1]);
        if (s == null || compare(u[0], BuildInfo.UPSTREAM) <= 0) return null; // gone, or installed
        return c.getString(s.running ? R.string.update_chat_working : R.string.update_chat_waiting, u[0]);
    }

    // ------------------------------------------------------------ daily check

    /** At most once a day (if Update notices is on): notify once per new upstream version. */
    static void maybeCheck(Context c) {
        if (!Prefs.updateNotices(c)) return;
        long now = System.currentTimeMillis();
        if (now - Prefs.updateCheckedAt(c) < DAY) return;
        new Thread(() -> {
            try {
                Release r = latest();
                Prefs.setUpdateCheckedAt(c, now);
                if (r.newer() && !r.version.equals(Prefs.updateNotified(c))) {
                    notify(c, r);
                    Prefs.setUpdateNotified(c, r.version);
                }
            } catch (Exception ignored) {
                // offline: try again at the next check
            }
        }).start();
    }

    private static void notify(Context c, Release r) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 30,
                new Intent(c, MainActivity.class).putExtra("update", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent mute = PendingIntent.getBroadcast(c, 31,
                new Intent(c, Updates.class).setAction(ACTION_MUTE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = c.getString(R.string.update_notification_text, BuildInfo.UPSTREAM);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(R.string.update_available_title, r.version))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.update_action), open).build())
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.update_mute), mute).build())
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(ID, n);
        } catch (SecurityException ignored) {
            // notifications not allowed
        }
    }

    static void cancel(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!ACTION_MUTE.equals(intent.getAction())) return;
        Prefs.setUpdateNotices(c, false);
        cancel(c);
        Toast.makeText(c, R.string.update_muted, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ the sheet

    /** "Buddy X is out": upstream's notes, and Update (or the update chat, once started). Checks GitHub first. */
    static void showSheet(Activity a) {
        Handler main = new Handler(Looper.getMainLooper());
        Toast checking = Toast.makeText(a, R.string.update_checking, Toast.LENGTH_SHORT);
        checking.show();
        new Thread(() -> {
            Release r;
            try {
                r = latest();
            } catch (Exception e) {
                main.post(() -> Toast.makeText(a, R.string.update_check_failed, Toast.LENGTH_LONG).show());
                return;
            }
            main.post(() -> {
                checking.cancel();
                if (a.isFinishing() || a.isDestroyed()) return;
                if (!r.newer()) {
                    Toast.makeText(a, a.getString(R.string.update_up_to_date, BuildInfo.UPSTREAM), Toast.LENGTH_LONG).show();
                    cancel(a);
                    return;
                }
                sheet(a, r);
            });
        }).start();
    }

    private static void sheet(Activity a, Release r) {
        LinearLayout c = column(a);
        c.addView(title(a, a.getString(R.string.update_available_title, r.version), 18));
        TextView sub = text(a, a.getString(R.string.update_sheet_text, BuildInfo.VERSION, BuildInfo.UPSTREAM), 13, MUTED);
        sub.setPaddingRelative(0, dp(a, 4), 0, dp(a, 12));
        c.addView(sub);
        if (!r.notes.isEmpty()) {
            ScrollView sv = new ScrollView(a);
            View notes = Markdown.render(a, r.notes, null, cs -> {
                TextView t = text(a, "", 14, TEXT);
                t.setText(cs);
                return t;
            });
            notes.setPaddingRelative(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
            sv.addView(notes);
            sv.setBackground(rounded(a, BG, 12));
            c.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    Math.min(dp(a, 280), (int) (a.getResources().getDisplayMetrics().heightPixels * 0.4f))));
        }
        boolean started = chat(a, r.version) != null;
        LinearLayout btns = row(a);
        Button later = button(a, a.getString(R.string.update_later), false);
        Button update = button(a, a.getString(started ? R.string.update_open_chat : R.string.update_action), true);
        btns.addView(later, new LinearLayout.LayoutParams(0, dp(a, 44), 1));
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(0, dp(a, 44), 1);
        up.setMarginStart(dp(a, 10));
        btns.addView(update, up);
        LinearLayout.LayoutParams bp = full();
        bp.topMargin = dp(a, 14);
        c.addView(btns, bp);
        Dialog d = bottomSheet(a, c);
        later.setOnClickListener(v -> d.dismiss());
        update.setOnClickListener(v -> {
            d.dismiss();
            openChat(a, r);
        });
        d.show();
    }

    // ------------------------------------------------------------ helpers

    private static String get(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(20000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(in, b);
            return b.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    static void copy(InputStream in, OutputStream o) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
    }

    /** "2.1.10" vs "2.1.9": compares number by number. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
