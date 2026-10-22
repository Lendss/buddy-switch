package app.buddy.assistant;

import android.app.Activity;
import android.os.Bundle;

/**
 * Base for Buddy's screens: applies the theme and rebuilds when the appearance settings change. With a Joy-Con, the
 * focused control is ringed (see Pad).
 */
abstract class Screen extends Activity {
    private String themeKey;

    @Override
    protected void onCreate(Bundle state) {
        Theme.apply(this);
        themeKey = Theme.key();
        super.onCreate(state);
        final android.view.View decor = getWindow().getDecorView();
        Pad.ringFocus(decor, () -> !decor.isInTouchMode());
        decor.getViewTreeObserver().addOnTouchModeChangeListener(touch -> {
            if (touch) Pad.unring(decor);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Theme.load(this);
        if (!Theme.key().equals(themeKey)) recreate();
    }

    /** Re-apply the theme in place (after a change made on this screen). */
    protected void rethemed() {
        Theme.apply(this);
        themeKey = Theme.key();
    }
}
