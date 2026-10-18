package app.buddy.assistant;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Notification access, for the user's notification rules. A notification is read only when a switched-on rule watches
 * its app; everything else is ignored right away, and nothing is kept.
 */
public class NotificationWatcher extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null || pkg.equals(getPackageName()) || !Prefs.rulesOn(this) || !Rules.watches(this, pkg)) return;
            Notification n = sbn.getNotification();
            // progress, music and other ongoing ones aren't news; a group's summary repeats its children
            if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                    | Notification.FLAG_GROUP_SUMMARY)) != 0) return;
            Triggers.Note note = Triggers.Note.of(this, sbn);
            if (note == null || note.fromMe) return; // nothing to read, or the user's own message (e.g. a reply just sent)
            Rules.Rule rule = Rules.match(this, note);
            if (rule != null) Triggers.get(this).fire(rule, note);
        } catch (Exception e) {
            android.util.Log.w("Buddy", "notification rule failed", e);
        }
    }

    /** Has the user given Buddy notification access? */
    static boolean enabled(Context c) {
        return c.getSystemService(android.app.NotificationManager.class)
                .isNotificationListenerAccessGranted(new ComponentName(c, NotificationWatcher.class));
    }
}
