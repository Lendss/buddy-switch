package app.buddy.assistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

import static app.buddy.assistant.Ui.*;

/**
 * A picture from a chat (see {@link Pictures}) on its own, full screen: pinch or double-tap to zoom, drag to move it;
 * a tap shows or hides the bar with Share and Close.
 */
public class ImageActivity extends Screen {
    private Bitmap bitmap;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final String id = getIntent().getStringExtra("id");
        final File f = Attachments.file(this, id);
        if (f == null || !f.isFile()) {
            finish();
            return;
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        final ZoomView image = new ZoomView(this);
        image.setContentDescription(Attachments.name(id));
        root.addView(image, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        final LinearLayout bar = row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 16));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x00000000}));
        ImageButton close = iconButton(this, R.drawable.ms_close, getString(R.string.bubble_close), Color.WHITE);
        close.setOnClickListener(v -> finish());
        bar.addView(close);
        TextView name = single(text(this, Attachments.name(id), 15, Color.WHITE));
        name.setPaddingRelative(dp(this, 4), 0, dp(this, 4), 0);
        bar.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ImageButton share = iconButton(this, R.drawable.ms_share, getString(R.string.image_share), Color.WHITE);
        share.setOnClickListener(v -> share(id));
        bar.addView(share);
        root.addView(bar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        image.onTap = () -> bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        setContentView(root);

        // the whole screen for the picture; the bars come back with a swipe from the edge
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.hide(WindowInsets.Type.systemBars());
            insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        // at about the screen's size: enough to zoom in a little, and never a texture too big to draw
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int w = Math.min(4096, dm.widthPixels), h = Math.min(4096, dm.heightPixels);
        new Thread(() -> {
            final Bitmap b = Pictures.decode(f, w, h);
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    if (b != null) b.recycle();
                    return;
                }
                if (b == null) {
                    android.widget.Toast.makeText(this, getString(R.string.attach_cant_open, Attachments.name(id)),
                            android.widget.Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                bitmap = b;
                image.setImageBitmap(b);
            });
        }, "image").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bitmap != null) bitmap.recycle(); // big, and nothing else holds it
        bitmap = null;
    }

    private void share(String id) {
        Uri u = FilesProvider.attachmentUri(id);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(getContentResolver().getType(u))
                .putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, null));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, R.string.chat_no_app_for_action, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** An ImageView that zooms (pinch, double tap) and moves a zoomed picture (drag); the picture stays on screen. */
    private static final class ZoomView extends ImageView {
        private static final float MAX = 6f;
        private final Matrix m = new Matrix();
        /** How far it's zoomed in, from 1 (the whole picture fits the screen). */
        private float zoom = 1f;
        private final ScaleGestureDetector pinch;
        private final GestureDetector gestures;
        Runnable onTap;

        ZoomView(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (zoom <= 1f) return false;
                    m.postTranslate(-dx, -dy);
                    keepOnScreen();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (zoom > 1.05f) fit();
                    else zoomBy(2.5f, e.getX(), e.getY());
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (onTap != null) onTap.run();
                    return true;
                }
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            pinch.onTouchEvent(e);
            gestures.onTouchEvent(e);
            return true;
        }

        @Override
        public void setImageBitmap(Bitmap b) {
            super.setImageBitmap(b);
            fit();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            fit();
        }

        /** The whole picture, as large as the screen allows, centred. */
        private void fit() {
            Drawable d = getDrawable();
            if (d == null || getWidth() == 0 || getHeight() == 0 || d.getIntrinsicWidth() <= 0) return;
            float s = Math.min((float) getWidth() / d.getIntrinsicWidth(), (float) getHeight() / d.getIntrinsicHeight());
            zoom = 1f;
            m.setScale(s, s);
            m.postTranslate((getWidth() - d.getIntrinsicWidth() * s) / 2f, (getHeight() - d.getIntrinsicHeight() * s) / 2f);
            setImageMatrix(m);
        }

        private void zoomBy(float by, float x, float y) {
            if (getDrawable() == null) return;
            float to = Math.max(1f, Math.min(MAX, zoom * by));
            by = to / zoom;
            zoom = to;
            m.postScale(by, by, x, y);
            keepOnScreen();
        }

        /** Centred along a side where it's smaller than the screen; elsewhere no gap between its edges and the screen's. */
        private void keepOnScreen() {
            Drawable d = getDrawable();
            if (d == null) return;
            RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            m.mapRect(r);
            float dx = 0, dy = 0;
            if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
            else if (r.left > 0) dx = -r.left;
            else if (r.right < getWidth()) dx = getWidth() - r.right;
            if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
            else if (r.top > 0) dy = -r.top;
            else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
            m.postTranslate(dx, dy);
            setImageMatrix(m);
        }
    }
}
