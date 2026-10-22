package app.buddy.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/**
 * Base for Buddy's screens: applies the theme and rebuilds when the appearance settings change. With a Joy-Con, the
 * focused control is ringed (see Pad).
 */
abstract class Screen extends Activity {
    private String themeKey;
    /** Buddy's screens on display now: while there is one, the bubble keeps out of the way (Bubble.applyEnabled). */
    static int shown;

    @Override
    protected void onCreate(Bundle state) {
        Theme.apply(this);
        themeKey = Theme.key();
        super.onCreate(state);
        Pad.watch(getWindow().getDecorView());
    }

    @Override
    protected void onStart() {
        super.onStart();
        shown++;
        bubbleChanged();
    }

    @Override
    protected void onStop() {
        shown = Math.max(0, shown - 1);
        bubbleChanged();
        super.onStop();
    }

    private static void bubbleChanged() {
        BuddyService s = BuddyService.get();
        Bubble b = s == null ? null : s.bubble();
        if (b != null) b.applyEnabled();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setVisibility(View.VISIBLE);
        Theme.load(this);
        if (!Theme.key().equals(themeKey)) recreate();
    }

    /**
     * Leaving this screen, its content is hidden first (the window background stays, the same colour as the next
     * screen's). On this device a window taken away while something on it animates (a spinner, a pulse) sometimes
     * lost the buffer it was drawing into, and the whole screen stalled for 4 s (HWUI's dequeueBuffer timing out);
     * hidden, nothing draws any more.
     */
    private void quiet() {
        getWindow().getDecorView().setVisibility(View.INVISIBLE);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        quiet();
        try {
            super.startActivity(intent, options);
        } catch (RuntimeException e) {
            getWindow().getDecorView().setVisibility(View.VISIBLE);
            throw e;
        }
    }

    @Override
    public void finish() {
        quiet();
        super.finish();
    }

    /** Re-apply the theme in place (after a change made on this screen). */
    protected void rethemed() {
        Theme.apply(this);
        themeKey = Theme.key();
    }
}
