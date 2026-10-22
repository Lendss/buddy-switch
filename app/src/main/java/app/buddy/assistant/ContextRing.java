package app.buddy.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** How full a chat's context is, as a ring: grey, then amber from half full, red from 80 %. */
final class ContextRing extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float part;

    ContextRing(Context c) {
        super(c);
        float w = 2.5f * c.getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{track, fill}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        track.setColor(Ui.CARD2);
    }

    /** part: 0 to 1. */
    void set(float part) {
        this.part = Math.max(0, Math.min(1, part));
        fill.setColor(colour(this.part));
        invalidate();
    }

    static int colour(float part) {
        return part >= 0.8f ? Ui.ERR : part >= 0.5f ? 0xFFE0A33A : Ui.MUTED;
    }

    @Override
    protected void onDraw(Canvas c) {
        float in = track.getStrokeWidth() / 2;
        box.set(in, in, getWidth() - in, getHeight() - in);
        c.drawArc(box, 0, 360, false, track);
        if (part > 0) c.drawArc(box, -90, Math.max(6, 360 * part), false, fill);
    }
}
