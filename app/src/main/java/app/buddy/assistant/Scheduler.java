package app.buddy.assistant;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Messages an agent scheduled for its chat (the schedule tool): reminders, later checks, repeating ones. Android's
 * alarm wakes Buddy at the time and the message goes into the chat as if it were sent then, so the agent runs again;
 * between times nothing runs. Kept in Buddy's settings, re-armed after a reboot or an update.
 */
final class Scheduler {
    /** A chat still answering gets its scheduled message this much later. */
    private static final long BUSY_RETRY_MS = 60_000;
    /** Shortest repeat. */
    static final long MIN_EVERY_MS = 60_000;

    static final class Job {
        String id, chat, text, label;
        long at, every; // every = 0: once

        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("chat", chat).put("text", text).put("label", label)
                    .put("at", at).put("every", every);
        }

        static Job of(JSONObject o) {
            Job j = new Job();
            j.id = o.optString("id");
            j.chat = o.optString("chat");
            j.text = o.optString("text");
            j.label = o.optString("label");
            j.at = o.optLong("at");
            j.every = o.optLong("every");
            return j;
        }
    }

    private Scheduler() {
    }

    static synchronized List<Job> all(Context c) {
        List<Job> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.schedule(c));
            for (int i = 0; i < a.length(); i++) out.add(Job.of(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Job> jobs) {
        JSONArray a = new JSONArray();
        try {
            for (Job j : jobs) a.put(j.json());
        } catch (Exception ignored) {
        }
        Prefs.setSchedule(c, a.toString());
    }

    static List<Job> forChat(Context c, String chat) {
        List<Job> out = new ArrayList<>();
        for (Job j : all(c)) if (j.chat.equals(chat)) out.add(j);
        out.sort((x, y) -> Long.compare(x.at, y.at));
        return out;
    }

    static synchronized Job add(Context c, String chat, String text, String label, long at, long every) {
        Job j = new Job();
        j.id = UUID.randomUUID().toString().substring(0, 8);
        j.chat = chat;
        j.text = text;
        j.label = label == null || label.trim().isEmpty() ? text : label.trim();
        j.at = at;
        j.every = every;
        List<Job> jobs = all(c);
        jobs.add(j);
        save(c, jobs);
        arm(c, j);
        changed();
        return j;
    }

    static synchronized boolean cancel(Context c, String id) {
        List<Job> jobs = all(c);
        boolean found = jobs.removeIf(j -> j.id.equals(id));
        if (found) {
            save(c, jobs);
            alarms(c).cancel(intent(c, id));
            changed();
        }
        return found;
    }

    /** A chat was deleted: its jobs go too. */
    static synchronized void cancelChat(Context c, String chat) {
        for (Job j : forChat(c, chat)) cancel(c, j.id);
    }

    /** After a reboot, an update or Buddy's start: every job's alarm again (a missed one fires right away). */
    static synchronized void armAll(Context c) {
        for (Job j : all(c)) arm(c, j);
    }

    private static AlarmManager alarms(Context c) {
        return c.getSystemService(AlarmManager.class);
    }

    private static PendingIntent intent(Context c, String id) {
        Intent i = new Intent(c, Receiver.class).setAction(Receiver.FIRE).putExtra("id", id);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void arm(Context c, Job j) {
        AlarmManager am = alarms(c);
        long at = Math.max(j.at, System.currentTimeMillis() + 1000);
        // to the minute when Android allows exact alarms (Settings › Alarms & reminders), else within a few minutes
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c, j.id));
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c, j.id));
        }
    }

    /** The alarm went off: the message goes into its chat (on the main thread). */
    private static void fire(Context c, String id) {
        Job j = null;
        synchronized (Scheduler.class) {
            for (Job x : all(c)) if (x.id.equals(id)) j = x;
        }
        if (j == null) return;
        ChatHub hub = ChatHub.get(c);
        Sessions.Session ses = hub.sessions.find(j.chat);
        if (ses == null) {
            cancel(c, j.id);
            return;
        }
        if (ses.running) { // still answering: a bit later
            synchronized (Scheduler.class) {
                List<Job> jobs = all(c);
                for (Job x : jobs) if (x.id.equals(id)) x.at = System.currentTimeMillis() + BUSY_RETRY_MS;
                save(c, jobs);
                for (Job x : jobs) if (x.id.equals(id)) arm(c, x);
            }
            return;
        }
        synchronized (Scheduler.class) {
            List<Job> jobs = all(c);
            if (j.every > 0) {
                long now = System.currentTimeMillis();
                for (Job x : jobs) {
                    if (!x.id.equals(id)) continue;
                    while (x.at <= now) x.at += x.every; // missed ones (phone off) don't pile up
                    arm(c, x);
                }
            } else {
                jobs.removeIf(x -> x.id.equals(id));
            }
            save(c, jobs);
        }
        hub.sendScheduled(ses, j.text, j.every > 0);
        changed();
    }

    private static final String CHANNEL = "scheduled";

    /** The reply to a scheduled message, when its chat isn't open: tap to open the chat. */
    static void notifyReply(Context c, Sessions.Session ses, String reply) {
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        nm.createNotificationChannel(new android.app.NotificationChannel(CHANNEL, c.getString(R.string.scheduled_channel_name),
                android.app.NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = PendingIntent.getActivity(c, ses.id.hashCode(),
                new Intent(c, ChatActivity.class).putExtra("id", ses.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = Ui.linkText(reply);
        android.app.Notification n = new android.app.Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("⏰ " + ses.label())
                .setContentText(text)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(CHANNEL, ses.id.hashCode(), n);
        } catch (SecurityException ignored) {
            // notifications not allowed
        }
    }

    private static final Handler main = new Handler(Looper.getMainLooper());

    private static void changed() {
        main.post(() -> {
            ChatHub h = ChatHub.peek();
            if (h != null) h.scheduleChanged();
        });
    }

    /** Alarms, and the reboot / update that cleared them. */
    public static final class Receiver extends BroadcastReceiver {
        static final String FIRE = "app.buddy.assistant.SCHEDULED";

        @Override
        public void onReceive(Context context, Intent intent) {
            final Context c = context.getApplicationContext();
            if (FIRE.equals(intent.getAction())) {
                final String id = intent.getStringExtra("id");
                if (id != null) main.post(() -> fire(c, id));
            } else {
                armAll(c);
            }
        }
    }
}
