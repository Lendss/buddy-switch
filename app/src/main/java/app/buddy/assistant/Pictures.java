package app.buddy.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures in chats (attachments, and the ones an agent's reply points to, see buddy-run's images): thumbnails in the
 * messages, and {@link ImageActivity} to see one whole.
 */
final class Pictures {
    private static final ExecutorService decoder = Executors.newSingleThreadExecutor();

    private Pictures() {
    }

    /**
     * A picture as a rounded thumbnail, at most maxW × maxH with its proportions, decoded off the main thread. Tapping
     * it runs then (or nothing) and opens the picture full screen. A picture no longer here is its name in a box.
     */
    static View thumb(Context c, String id, int maxW, int maxH, Runnable then) {
        File f = Attachments.file(c, id);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (f != null && f.isFile()) BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return missing(c, id);
        float s = Math.min(1f, Math.min((float) maxW / bounds.outWidth, (float) maxH / bounds.outHeight));
        final int w = Math.max(1, Math.round(bounds.outWidth * s)), h = Math.max(1, Math.round(bounds.outHeight * s));
        final ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_XY); // the view has the picture's proportions already
        iv.setBackground(Ui.rounded(c, Ui.CARD2, 14)); // until it's decoded
        iv.setClipToOutline(true);
        final int radius = Ui.dp(c, 14);
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        iv.setContentDescription(Attachments.name(id));
        iv.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        iv.setOnClickListener(v -> {
            if (then != null) then.run();
            open(c, id);
        });
        decoder.execute(() -> {
            Bitmap b = decode(f, w, h);
            if (b != null) iv.post(() -> iv.setImageBitmap(b));
        });
        return iv;
    }

    /** The picture decoded at least w × h (a power-of-two step down from its size), or null if it can't be. */
    static Bitmap decode(File f, int w, int h) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable e) { // OutOfMemoryError too: a picture too big is just not shown
            return null;
        }
    }

    private static View missing(Context c, String id) {
        LinearLayout chip = Ui.row(c);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPaddingRelative(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        chip.setBackground(Ui.rounded(c, Ui.CARD2, 14));
        chip.addView(Ui.icon(c, R.drawable.ms_image, Ui.MUTED, 20));
        TextView t = Ui.single(Ui.text(c, Attachments.name(id), 14, Ui.MUTED));
        t.setPaddingRelative(Ui.dp(c, 8), 0, 0, 0);
        chip.addView(t);
        chip.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return chip;
    }

    /**
     * Shows the picture full screen. From the bubble (not an activity) in a task of its own (the manifest's
     * taskAffinity), so closing it goes back to the app that was open, not to a chat screen left in Buddy's task.
     */
    static void open(Context c, String id) {
        Intent i = new Intent(c, ImageActivity.class).putExtra("id", id);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        c.startActivity(i);
    }
}
