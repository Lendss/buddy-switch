package app.buddy.assistant;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/**
 * Joy-Con (any gamepad) in a chat: the D-pad and left stick move between the controls (the app bar and the
 * composer; Android does that), the right stick scrolls the conversation, L / R scroll a page, + sends, ZR stops
 * the agent. A and B are Android's own: press and Back.
 */
final class Pad {
    private Pad() {
    }

    /**
     * Keeps the D-pad out of the conversation: with a gamepad, focus would otherwise stop on every message (they
     * are selectable). Only while not in touch mode, so long-pressing a message to select text keeps working.
     */
    static void keepFocusOut(final ScrollView scroll) {
        scroll.setFocusable(false);
        final Runnable apply = () -> scroll.setDescendantFocusability(scroll.isInTouchMode()
                ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        apply.run();
        scroll.getViewTreeObserver().addOnTouchModeChangeListener(inTouch -> apply.run());
    }

    /**
     * The bubble's panel is an overlay, and Android keeps an overlay in touch mode whatever is pressed (only the
     * focused app's windows leave it): there its buttons can't take focus, so the D-pad would only reach the
     * messages. While the D-pad is in use, the controls take focus in touch mode too and the conversation is kept
     * out; a touch on the screen undoes that (or a first tap on a button would only focus it).
     */
    static void overlayFocus(ViewGroup root, ScrollView conversation, boolean on) {
        conversation.setDescendantFocusability(on ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        mark(root, conversation, on);
    }

    interface Active {
        boolean active();
    }

    /**
     * While the D-pad is in use (active), the view that takes focus gets an accent ring, so it's clear where the
     * D-pad is; {@link #unring} takes the rings off when the screen is touched again. Scrolling areas and big
     * containers pass the focus on to their first control.
     */
    static void ringFocus(final View root, final Active pad) {
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (now == null || !pad.active()) return;
            if (!ringable(now, root)) {
                // a scrolling area or a whole panel took focus (a new screen, a sheet): Android would grey it all
                // over; the focus goes to the first control in it instead, once it's laid out
                now.setDefaultFocusHighlightEnabled(false);
                if (now instanceof ViewGroup) passOn((ViewGroup) now, 5);
                return;
            }
            if (now.getForeground() == null) now.setForeground(new Ring(now));
        });
    }

    private static void passOn(final ViewGroup g, final int tries) {
        g.post(() -> {
            if (!g.isFocused()) return;
            View first = g.isLaidOut() ? android.view.FocusFinder.getInstance().findNextFocus(g, null, View.FOCUS_DOWN) : null;
            if (first != null) first.requestFocus();
            else if (tries > 0) passOn(g, tries - 1);
        });
    }

    /** {@link #ringFocus} for a window (a screen or a sheet): rings while the D-pad is used, none after a touch. */
    static void watch(final View decor) {
        ringFocus(decor, () -> !decor.isInTouchMode());
        decor.getViewTreeObserver().addOnTouchModeChangeListener(touch -> {
            if (touch) unring(decor);
        });
    }

    /** A control, not a scrolling area or a whole panel (a ring around those looks like the window is selected). */
    private static boolean ringable(View v, View root) {
        if (v instanceof ScrollView || v instanceof android.widget.HorizontalScrollView
                || v instanceof android.widget.AbsListView) return false;
        if (!v.isLaidOut()) return !(v instanceof ViewGroup) || v.isClickable();
        return (long) v.getWidth() * v.getHeight() < (long) root.getWidth() * root.getHeight() / 2;
    }

    static void unring(View v) {
        if (v.getForeground() instanceof Ring) v.setForeground(null);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) unring(g.getChildAt(i));
        }
    }

    /** An accent outline shown while the view has focus. */
    private static final class Ring extends android.graphics.drawable.StateListDrawable {
        Ring(View v) {
            float d = v.getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(22 * d);
            g.setStroke(Math.round(2 * d), Ui.ACCENT);
            addState(new int[]{android.R.attr.state_focused}, g);
        }
    }

    private static void mark(View v, ScrollView skip, boolean on) {
        if (v == skip || v.getVisibility() != View.VISIBLE) return;
        if (on && v.isFocusable() && !v.isFocusableInTouchMode()) {
            v.setFocusableInTouchMode(true);
            v.setTag(R.id.pad_touch_focus, Boolean.TRUE);
        } else if (!on && v.getTag(R.id.pad_touch_focus) != null) {
            v.setFocusableInTouchMode(false);
            v.setTag(R.id.pad_touch_focus, null);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) mark(g.getChildAt(i), skip, on);
        }
    }

    static boolean inside(View v, ViewGroup parent) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    /** A D-pad move (from the D-pad or the left stick). */
    static boolean isMove(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }

    /** The chat's buttons for {@link #key}. */
    interface Actions {
        void send();

        void stop();
    }

    /** Handles L, R, + and ZR; true if the key was one of them (its down and its up are both used). */
    static boolean key(KeyEvent e, ScrollView scroll, Actions a) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, up = e.getAction() == KeyEvent.ACTION_UP;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down) scroll.pageScroll(code == KeyEvent.KEYCODE_BUTTON_L1 ? View.FOCUS_UP : View.FOCUS_DOWN);
                return true;
            case KeyEvent.KEYCODE_BUTTON_START: // + (its fallback would press the focused button)
                if (up) a.send();
                return true;
            case KeyEvent.KEYCODE_BUTTON_R2:
                if (up) a.stop();
                return true;
            default:
                return false;
        }
    }

    private static final float DEAD_ZONE = 0.2f;

    /**
     * The right stick scrolls the conversation, faster the further it's pushed, for as long as it's held. Never
     * takes the event: the left stick's moves still reach Android, which turns them into D-pad presses.
     */
    static void motion(MotionEvent e, final ScrollView scroll) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getAction() != MotionEvent.ACTION_MOVE) return;
        float y = e.getAxisValue(MotionEvent.AXIS_RZ);
        Scroller s = (Scroller) scroll.getTag(R.id.pad_scroller);
        if (s == null) scroll.setTag(R.id.pad_scroller, s = new Scroller(scroll));
        s.set(Math.abs(y) < DEAD_ZONE ? 0 : y);
    }

    /** Scrolls every frame while the stick is held. */
    private static final class Scroller implements Runnable {
        private final ScrollView scroll;
        private float speed;
        private boolean running;

        Scroller(ScrollView scroll) {
            this.scroll = scroll;
        }

        void set(float v) {
            speed = v;
            if (v != 0 && !running) {
                running = true;
                scroll.postOnAnimation(this);
            }
        }

        @Override
        public void run() {
            if (speed == 0 || !scroll.isAttachedToWindow()) {
                running = false;
                return;
            }
            float max = 28 * scroll.getResources().getDisplayMetrics().density; // per frame, at full tilt
            scroll.scrollBy(0, Math.round(speed * Math.abs(speed) * max));
            scroll.postOnAnimation(this);
        }
    }
}
