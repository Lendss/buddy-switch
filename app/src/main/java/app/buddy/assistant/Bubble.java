package app.buddy.assistant;

import android.animation.ValueAnimator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Build;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

/** The floating Buddy bubble and its mini chat panel, drawn as an accessibility overlay. */
final class Bubble implements ChatHub.Listener {
    interface ConfirmCallback {
        /** change: what the user wants changed first (approved is then false), or null. */
        void onAnswer(boolean approved, String change);
    }


    private final BuddyService svc;
    private final WindowManager wm;
    private final Vibrator vibrator;

    private FrameLayout root;
    private WindowManager.LayoutParams lp;

    // collapsed
    private LinearLayout collapsed;
    private ImageView face;
    private TextView badge, pill;
    private ValueAnimator breathing;
    private RingView ring;
    private boolean noteListening;
    // expanded
    private LinearLayout panel, messages, confirmCard;
    private ScrollView scroll;
    private TextView header, confirmText;
    private EditText input;

    private boolean expanded, running, passThrough, showingList;
    private int imeTop;
    private ConfirmCallback pendingConfirm;
    private ChatHub hub;
    private Sessions sessions;
    private Sessions.Session viewingSes;
    private ScrollView listScroll;
    private LinearLayout sessionList;

    Bubble(BuddyService svc) {
        this.svc = svc;
        this.wm = svc.getSystemService(WindowManager.class);
        this.vibrator = svc.getSystemService(Vibrator.class);
    }

    // ------------------------------------------------------------- layout

    void show() {
        root = new FrameLayout(svc) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (expanded && (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)) {
                    if (e.getAction() == KeyEvent.ACTION_UP) onBack();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        buildCollapsed();
        buildPanel();
        followKeyboard();
        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE && expanded && pendingConfirm == null) {
                // An edge swipe for Back also lands outside the panel; wait briefly so Back can cancel this.
                root.removeCallbacks(outsideCollapse);
                root.postDelayed(outsideCollapse, 350);
            }
            return false;
        });
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 0, PixelFormat.TRANSLUCENT);
        root.addView(collapsed);
        applyCollapsedParams();
        createArcs();
        createDismissTarget();
        wm.addView(root, lp);
        hub = ChatHub.get(svc);
        radial = new RadialMenu(svc, wm);
        sessions = hub.sessions;
        hub.addListener(this);
        renderCurrent();
        applyEnabled();
    }

    void remove() {
        if (arcs != null) {
            try {
                wm.removeView(arcs);
            } catch (Exception ignored) {
            }
        }
        if (dismissWindow != null) {
            try {
                wm.removeView(dismissWindow);
            } catch (Exception ignored) {
            }
        }
        if (hub != null) hub.removeListener(this);
        if (breathing != null) breathing.cancel();
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
    }

    private void buildCollapsed() {
        collapsed = new LinearLayout(svc);
        collapsed.setOrientation(LinearLayout.HORIZONTAL);
        collapsed.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout bubbleFrame = new FrameLayout(svc);
        face = new ImageView(svc);
        face.setImageResource(R.drawable.bubble_face);
        face.setScaleType(ImageView.ScaleType.FIT_CENTER);
        face.setBackground(null);
        // shadow follows Buddy's rounded speech-bubble body (drawable spans 2..54 of 56 units wide, 3..42 tall)
        face.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                float u = v.getWidth() / 56f;
                o.setRoundRect(Math.round(2 * u), Math.round(3 * u), Math.round(54 * u), Math.round(42 * u), 13 * u);
            }
        });
        face.setElevation(dp(5));
        face.setContentDescription("Buddy");
        // room around the face for the working ring
        bubbleFrame.setPadding(dp(4), dp(4), dp(4), dp(4));
        bubbleFrame.setClipToPadding(false);
        bubbleFrame.setClipChildren(false);
        FrameLayout faceBox = new FrameLayout(svc);
        faceBox.setClipChildren(false);
        ring = new RingView(svc);
        ring.setVisibility(View.GONE);
        faceBox.addView(ring, new FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER));
        faceBox.addView(face, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
        holdRing = new HoldRing(svc);
        faceBox.addView(holdRing, new FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER));
        // 4. screen readers: "double-tap and hold for actions"
        face.setContentDescription("Buddy. Tap to open, hold for actions");
        face.setLongClickable(true);
        face.setOnLongClickListener(v -> { // TalkBack's double-tap-and-hold (touches go to DragListener)
            openMenu();
            return true;
        });
        face.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, "show actions"));
            }
        });
        LinearLayout column = new LinearLayout(svc);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        column.addView(faceBox, new LinearLayout.LayoutParams(dp(74), dp(74)));
        // Stop, Add instruction, Stop voice… are in the hold menu (RadialMenu), not chips under the bubble
        bubbleFrame.addView(column);

        badge = new TextView(svc);
        badge.setBackground(circle(0xFF34C17B, 0xFFFFF8F1, 2));
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP | Gravity.END);
        bubbleFrame.addView(badge, blp);
        collapsed.addView(bubbleFrame);

        pill = new TextView(svc);
        pill.setTextColor(Ui.TEXT);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pill.setTypeface(Ui.sans(svc, 500));
        pill.setMaxWidth(dp(230));
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setPadding(dp(12), dp(8), dp(12), dp(8));
        pill.setBackground(rounded(Ui.BG, 18, Ui.ACCENT, 1));
        pill.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP;
        plp.topMargin = dp(22); // level with the face's centre, not the stop button below it
        plp.leftMargin = dp(6);
        plp.rightMargin = dp(6);
        collapsed.addView(pill, plp);
        pill.setOnClickListener(v -> expand());

        face.setOnTouchListener(new DragListener());
    }

    private void buildPanel() {
        panel = new LinearLayout(svc);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.setBackground(rounded(Ui.BG, 24, Ui.OUTLINE, 1));
        panel.setElevation(dp(10));

        LinearLayout top = new LinearLayout(svc);
        top.setGravity(Gravity.CENTER_VERTICAL);
        header = new TextView(svc);
        header.setText("Buddy");
        header.setOnClickListener(v -> toggleSessionList());
        header.setTextColor(Ui.TEXT);
        header.setTypeface(Ui.sans(svc, 600));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        header.setSingleLine(true);
        header.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(header, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // Stop (only while it works), then the chat tools in one capsule, then close
        headerStop = toolIcon(R.drawable.ic_stop, "Stop", Ui.ERR, v -> stop());
        headerStop.setBackground(rounded(Theme.withAlpha(Ui.ERR, 0x26), 18, 0, 0));
        LinearLayout.LayoutParams hsp = new LinearLayout.LayoutParams(dp(36), dp(36));
        hsp.rightMargin = dp(6);
        headerStop.setLayoutParams(hsp);
        headerStop.setVisibility(View.GONE);
        top.addView(headerStop);
        LinearLayout tools = new LinearLayout(svc);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(2), dp(2), dp(2), dp(2));
        tools.setBackground(rounded(Ui.CARD, 20, Ui.OUTLINE, 1));
        tools.addView(toolIcon(R.drawable.ms_forum, "Chats", Ui.MUTED, v -> toggleSessionList()));
        tools.addView(toolIcon(R.drawable.ms_add, "New chat", Ui.MUTED, v -> newChat(Prefs.backend(svc))));
        top.addView(tools);
        android.widget.ImageButton close = toolIcon(R.drawable.ms_close, "Close", Ui.MUTED, v -> collapse());
        close.setBackground(rounded(Ui.CARD, 18, Ui.OUTLINE, 1));
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(dp(36), dp(36));
        clp2.leftMargin = dp(6);
        close.setLayoutParams(clp2);
        top.addView(close);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(4);
        tlp.bottomMargin = dp(4);
        panel.addView(top, tlp);

        scroll = new ScrollView(svc);
        // Tapping the conversation hides the keyboard.
        scroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN && keyboardVisible()) hideKeyboard();
            return false;
        });
        messages = new LinearLayout(svc);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(messages);
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        listScroll = new ScrollView(svc);
        sessionList = new LinearLayout(svc);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        sessionList.setPadding(0, dp(8), 0, dp(8));
        listScroll.addView(sessionList);
        listScroll.setVisibility(View.GONE);
        panel.addView(listScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        confirmCard = new LinearLayout(svc);
        confirmCard.setOrientation(LinearLayout.VERTICAL);
        confirmCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        confirmCard.setBackground(rounded(Ui.CARD, 18, Theme.withAlpha(Ui.ACCENT, 0x73), 1));
        TextView ct = new TextView(svc);
        ct.setText("NEEDS YOUR OK");
        ct.setLetterSpacing(0.06f);
        ct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ct.setTextColor(Ui.ACCENT_TEXT);
        ct.setTypeface(Ui.sans(svc, 600));
        confirmCard.addView(ct);
        confirmText = new TextView(svc);
        confirmText.setTextColor(Ui.TEXT);
        confirmText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        confirmText.setPadding(0, dp(6), 0, dp(10));
        ScrollView cs = new ScrollView(svc);
        cs.addView(confirmText);
        confirmCard.addView(cs, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(svc);
        row.setGravity(Gravity.END);
        Button deny = actionButton("Deny", Ui.ERR_SOFT);
        deny.setTextColor(Ui.ERR);
        deny.setOnClickListener(v -> answerConfirm(false));
        // Change: the user types or says what to change, and the agent gets that back instead of a yes/no
        Button change = actionButton("Change", Ui.CARD);
        change.setTextColor(Ui.TEXT);
        change.setOnClickListener(v -> startChange());
        Button approve = actionButton("Approve", Ui.ACCENT);
        approve.setOnClickListener(v -> answerConfirm(true));
        row.addView(deny);
        LinearLayout.LayoutParams changeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        changeLp.leftMargin = dp(8);
        row.addView(change, changeLp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(8);
        row.addView(approve, alp);
        confirmCard.addView(row);
        confirmCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        panel.addView(confirmCard, clp);

        // composer: one pill with the text field, a mic and a round send button (like the chat screen)
        LinearLayout inputRow = new LinearLayout(svc);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(14), dp(3), dp(4), dp(3));
        inputRow.setBackground(rounded(Ui.CARD, 24, Ui.OUTLINE, 1));
        input = new EditText(svc);
        input.setHint("Ask Buddy…");
        input.setHintTextColor(Ui.MUTED);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTypeface(Ui.sans(svc, 400));
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(0, dp(10), 0, dp(10));
        input.setBackground(null);
        input.setOnFocusChangeListener((v, has) -> refit());
        root.getViewTreeObserver().addOnWindowFocusChangeListener(has -> refit());
        inputRow.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        mic = new ImageButton(svc);
        mic.setImageResource(R.drawable.ms_mic);
        mic.setContentDescription("Talk");
        mic.setScaleType(ImageView.ScaleType.CENTER);
        mic.setOnClickListener(v -> toggleMic());
        inputRow.addView(mic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        mic.setBackground(null); // idle look; micIdle() needs the current chat, which isn't loaded yet
        mic.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.MUTED));
        final ImageButton send = new ImageButton(svc);
        send.setImageResource(R.drawable.ms_arrow_upward);
        send.setContentDescription("Send");
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setOnClickListener(v -> send());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.leftMargin = dp(4);
        inputRow.addView(send, slp);
        Runnable paintSend = () -> {
            boolean has = input.getText().toString().trim().length() > 0;
            send.setBackground(circle(has ? Ui.ACCENT : Ui.CARD2, 0, 0));
            send.setImageTintList(android.content.res.ColorStateList.valueOf(has ? Ui.ON_ACCENT : Ui.MUTED));
        };
        paintSend.run();
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                paintSend.run();
            }
        });
        panel.addView(inputRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        composer = inputRow;
        voiceBar = new VoiceBar(svc, new VoiceBar.Actions() {
            @Override
            public void cancel() {
                Voice.stopListening();
            }

            @Override
            public void send() {
                Voice.finishListening();
            }
        });
        voiceBar.setVisibility(View.GONE);
        panel.addView(voiceBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout composer;
    private VoiceBar voiceBar;

    private void showVoiceBar(boolean on) {
        if (on) voiceBar.begin(null);
        voiceBar.setVisibility(on ? View.VISIBLE : View.GONE);
        composer.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void applyCollapsedParams() {
        Rect screen = screen();
        int[] pos = Prefs.bubblePos(svc);
        boolean right = pos[0] != 0;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        // Anchor to the nearest edge so the status pill grows toward the middle of the screen.
        lp.gravity = Gravity.TOP | (right ? Gravity.END : Gravity.START);
        collapsed.setLayoutDirection(right ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        lp.x = dp(6);
        lp.y = Math.max(dp(40), Math.min(pos[1], screen.height() - dp(120)));
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | (passThrough ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
    }

    /** Called when windows change; keeps the expanded panel above the on-screen keyboard. */
    /**
     * Keep the panel above the keyboard frame by frame: the window gets the keyboard's height as it
     * slides (insets animation), so the panel shrinks with it instead of jumping afterwards.
     */
    private void followKeyboard() {
        root.setWindowInsetsAnimationCallback(new android.view.WindowInsetsAnimation.Callback(
                android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {
            @Override
            public android.view.WindowInsets onProgress(android.view.WindowInsets insets,
                                                         java.util.List<android.view.WindowInsetsAnimation> running) {
                applyIme(insets);
                return insets;
            }
        });
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyIme(insets);
            return insets;
        });
    }

    /** True once the window has reported the keyboard itself; then the slower accessibility path is ignored. */
    private boolean imeInsetsWork;

    private void applyIme(android.view.WindowInsets insets) {
        int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        if (ime > 0) imeInsetsWork = true;
        if (!expanded || !imeInsetsWork) return;
        int pad = ime > 0 ? ime + dp(8) : 0;
        if (root.getPaddingBottom() != pad) {
            root.setPadding(0, 0, 0, pad);
            scrollToEnd();
        }
    }

    void onKeyboardTop(int top) {
        if (imeInsetsWork) return; // the window follows the keyboard itself (see followKeyboard)
        if (top == imeTop) return;
        imeTop = top;
        if (expanded && !passThrough) {
            applyExpandedParams();
            try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
            scrollToEnd();
        }
    }

    /** Resize the open panel (Buddy's keyboard came or went). */
    private void refit() {
        if (!expanded || passThrough) return;
        int h = lp.height;
        applyExpandedParams();
        if (lp.height == h) return;
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {
        }
    }

    private void applyExpandedParams() {
        Rect screen = screen();
        lp.width = screen.width() - dp(20);
        // make room for the keyboard only when it's for Buddy's own text field (not another app's, e.g. while
        // the agent types in Messenger: the panel would shrink to half the screen for nothing)
        boolean ours = input != null && input.hasFocus() && root.hasWindowFocus();
        int bottom = imeTop > 0 && !imeInsetsWork && ours ? imeTop : screen.height() - dp(24);
        lp.height = Math.max(dp(220), bottom - dp(8) - dp(10));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = 0;
        lp.y = dp(8); // just under the status bar, so no strip of the app behind shows above the panel
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | (passThrough ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        // Buddy moves the panel itself (followKeyboard), so the system must not resize the window too
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
    }

    void expand() {
        if (expanded) return;
        expanded = true;
        root.setVisibility(View.VISIBLE);
        badge.setVisibility(View.GONE);
        root.removeAllViews();
        root.addView(panel);
        applyExpandedParams();
        Voice.prepare(svc); // so the mic is quick
        wm.updateViewLayout(root, lp);
        boolean right = Prefs.bubblePos(svc)[0] != 0;
        panel.setPivotX(right ? lp.width : 0);
        panel.setPivotY(0);
        panel.setScaleX(0.85f);
        panel.setScaleY(0.85f);
        panel.setAlpha(0f);
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(new OvershootInterpolator(0.9f)).setDuration(260).start();
        scrollToEnd();
        root.post(this::registerBack);
        updateViewing();
        if (pendingConfirm == null && !sessions.current.running && !showingList) {
            input.requestFocus();
            input.postDelayed(() -> {
                InputMethodManager imm = svc.getSystemService(InputMethodManager.class);
                imm.showSoftInput(input, 0);
            }, 150);
        }
    }

    void collapse() {
        if (!expanded) return;
        if (Voice.listening()) Voice.stopListening();
        expanded = false;
        updateViewing();
        unregisterBack();
        hideKeyboard();
        root.removeAllViews();
        root.setPadding(0, 0, 0, 0);
        root.addView(collapsed);
        applyCollapsedParams();
        wm.updateViewLayout(root, lp);
        applyEnabled();
        popIn();
        if (themeDirty) restyle();
    }

    private boolean themeDirty;

    /** Appearance changed: rebuild the panel now, or on the next collapse if it's open. */
    void applyTheme() {
        themeDirty = true;
        if (!expanded) restyle();
    }

    private void restyle() {
        themeDirty = false;
        if (Voice.listening()) Voice.stopListening();
        CharSequence draft = input.getText();
        buildPanel();
        input.setText(draft);
        pill.setTextColor(Ui.TEXT);
        pill.setTypeface(Ui.sans(svc, 500));
        pill.setBackground(rounded(Ui.BG, 18, Ui.ACCENT, 1));
        renderedFor = null;
        if (showingList) renderSessionList();
        renderCurrent();
    }

    /** Recents or Home opened (the launcher came to the front): fold the panel back into the bubble. */
    void onLauncherShown() {
        if (expanded && pendingConfirm == null) collapse();
    }

    /** The bubble can be turned off in the app; it then only appears for approval cards. */
    void applyEnabled() {
        if (locked && expanded && pendingConfirm == null) collapse();
        boolean show = !locked && (Prefs.bubbleEnabled(svc) || expanded || pendingConfirm != null || controlling);
        boolean wasHidden = root.getVisibility() != View.VISIBLE;
        root.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && wasHidden && !expanded) popIn();
        if (!show) showDismissTarget(false);
    }

    private boolean locked, controlling;

    /** An agent is operating the phone: show the bubble (even if hidden) in its "in control" state. */
    void setControlling(boolean on) {
        if (controlling == on) return;
        controlling = on;
        applyEnabled();
        refreshStatus();
    }

    /** Hide everything on the lock screen; the service calls this on screen off / unlock. */
    void setLocked(boolean on) {
        if (locked == on) return;
        locked = on;
        applyEnabled();
    }

    // ------------------------------------------------- drag-down to dismiss

    // A roomy window (so the grow animation never clips) holding a circle with a drawn ✕.
    // It is added before the bubble's window, so the bubble always stays on top of it.
    private FrameLayout dismissWindow;
    private ImageView dismissCircle;
    private boolean overTarget;

    // Full-screen, untouchable layer behind the target and bubble for the Tesla-coil effect.
    private ArcView arcs;

    private void createArcs() {
        arcs = new ArcView(svc);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        arcs.setVisibility(View.GONE);
        wm.addView(arcs, p);
    }

    private void feedArcs() {
        if (arcs == null || dismissCircle == null) return;
        arcs.setVisibility(View.VISIBLE);
        int[] t = targetCenter();
        arcs.update(lp.x + faceOffX, lp.y + lpToScreenY + faceOffY, t[0], t[1], overTarget);
    }

    /** Let the arcs die down, then hide the layer. */
    private void endArcs(boolean explode) {
        if (arcs == null) return;
        if (explode) arcs.explode();
        else arcs.stop();
        arcs.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (arcs.idle()) arcs.setVisibility(View.GONE);
                else arcs.postDelayed(this, 300);
            }
        }, 300);
    }

    /** The dismiss target drawn as an electrode: dark core and cyan rim; white-blue when Buddy is on it. */
    private GradientDrawable electrode(boolean hot) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setGradientRadius(dp(hot ? 34 : 32));
        d.setColors(hot ? new int[]{0xFFFFFFFF, 0xFFBDF4FF, 0xFF4F8BFF}
                : new int[]{0xFF0B1424, 0xFF13284A, 0xFF2F7DD1});
        d.setStroke(dp(hot ? 2 : 1), hot ? 0xFFE8FBFF : 0xCC7DF9FF);
        return d;
    }

    private void createDismissTarget() {
        dismissWindow = new FrameLayout(svc);
        dismissCircle = new ImageView(svc);
        dismissCircle.setImageResource(R.drawable.ic_dismiss);
        dismissCircle.setScaleType(ImageView.ScaleType.CENTER);
        dismissCircle.setBackground(electrode(false));
        dismissCircle.setElevation(dp(4));
        dismissWindow.addView(dismissCircle, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(110), dp(110),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = dp(40);
        dismissWindow.setVisibility(View.GONE);
        wm.addView(dismissWindow, p);
    }

    private void showDismissTarget(boolean show) {
        if (dismissWindow == null) return;
        if (show && dismissWindow.getVisibility() == View.VISIBLE) return;
        if (show) {
            overTarget = false;
            dismissCircle.setScaleX(1f);
            dismissCircle.setScaleY(1f);
            dismissWindow.setAlpha(0f);
            dismissWindow.setVisibility(View.VISIBLE);
            dismissWindow.animate().alpha(1f).setDuration(120).start();
            dismissCircle.setBackground(electrode(false));
            dismissCircle.setColorFilter(0xFFFFFFFF);
            dismissCircle.setTranslationY(dp(70));
            dismissCircle.setScaleX(0.6f);
            dismissCircle.setScaleY(0.6f);
            dismissCircle.animate().translationY(0f).scaleX(1f).scaleY(1f)
                    .setInterpolator(new OvershootInterpolator(2.2f)).setDuration(320).start();
        } else {
            dismissWindow.animate().cancel();
            dismissCircle.animate().cancel();
            dismissWindow.setAlpha(0f);
            dismissWindow.setVisibility(View.GONE);
            dismissCircle.setTranslationY(0f);
        }
    }

    /** Centre of the target, measured on screen (accounts for nav bar and insets). */
    private int[] targetCenter() {
        int[] loc = new int[2];
        dismissCircle.getLocationOnScreen(loc);
        return new int[]{loc[0] + dismissCircle.getWidth() / 2, loc[1] + dismissCircle.getHeight() / 2};
    }

    /** Offset from the window position (lp.x, lp.y) to the face's centre on screen, measured at drag start. */
    private int faceOffX, faceOffY, lpToScreenY;

    /** Is the bubble's top-left (x,y) near the target? Highlights it and returns true when close. */
    private boolean overDismiss(int x, int y) {
        if (dismissWindow == null || dismissWindow.getVisibility() != View.VISIBLE) return false;
        int[] t = targetCenter();
        boolean over = Math.hypot(x + faceOffX - t[0], y + lpToScreenY + faceOffY - t[1]) < dp(90);
        if (over != overTarget) {
            overTarget = over;
            dismissCircle.animate().scaleX(over ? 1.28f : 1f).scaleY(over ? 1.28f : 1f)
                    .setInterpolator(new OvershootInterpolator(3f)).setDuration(220).start();
            dismissCircle.setBackground(electrode(over));
            dismissCircle.setColorFilter(over ? 0xFF16305C : 0xFFFFFFFF); // dark ✕ on white-blue, light ✕ on the electrode
            face.setImageResource(over ? R.drawable.bubble_face_brace : R.drawable.bubble_face_fired);
            if (over) vibrate(15);
        }
        return over;
    }

    private void dismissToNotification() {
        vibrate(40);
        final View head = (View) face.getParent();
        // spin and shrink into the target, which pops and drops away
        head.animate().scaleX(0f).scaleY(0f).rotation(180f).alpha(0f)
                .setInterpolator(new AccelerateInterpolator(1.6f)).setDuration(240)
                .withEndAction(() -> {
                    Prefs.setBubbleEnabled(svc, false);
                    HiddenNotice.show(svc);
                    applyCollapsedParams();
                    wm.updateViewLayout(root, lp);
                    applyEnabled();
                    head.setScaleX(1f);
                    head.setScaleY(1f);
                    head.setRotation(0f);
                    head.setAlpha(1f);
                    setWorking(running || controlling, true);
                }).start();
        dismissCircle.animate().scaleX(1.45f).scaleY(1.45f).setDuration(110)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> dismissCircle.animate().scaleX(0f).scaleY(0f).translationY(dp(60))
                        .setInterpolator(new AccelerateInterpolator(1.8f)).setDuration(200)
                        .withEndAction(() -> showDismissTarget(false)).start())
                .start();
    }

    // ------------------------------------------------------------ motion

    private ValueAnimator windowAnim;

    /** Animate the bubble window to (x, y) in window coordinates. */
    private void animateWindowTo(int x, int y, long ms, android.animation.TimeInterpolator in, Runnable end) {
        if (windowAnim != null) windowAnim.cancel();
        final int x0 = lp.x, y0 = lp.y;
        windowAnim = ValueAnimator.ofFloat(0f, 1f);
        windowAnim.setDuration(ms);
        windowAnim.setInterpolator(in);
        windowAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            lp.x = Math.round(x0 + (x - x0) * f);
            lp.y = Math.round(y0 + (y - y0) * f);
            try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
        });
        windowAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && end != null) end.run();
            }
        });
        windowAnim.start();
    }

    /** Pop the bubble in from nothing (after unhiding, or closing the panel). */
    private void popIn() {
        View head = (View) face.getParent();
        head.setScaleX(0.2f);
        head.setScaleY(0.2f);
        head.setAlpha(0f);
        head.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f)
                .setInterpolator(new OvershootInterpolator(2.6f)).setDuration(380).start();
    }

    /*
     * Android 16 (targetSdk 36) delivers Back through OnBackInvokedDispatcher instead of
     * KEYCODE_BACK, so the panel registers a callback while it is open. Back first closes
     * the keyboard, then denies a pending approval, then collapses the panel.
     */
    private final Runnable outsideCollapse = () -> {
        if (expanded && pendingConfirm == null) collapse();
    };
    private OnBackInvokedCallback backCallback;
    private OnBackInvokedDispatcher backDispatcher;

    private void registerBack() {
        // Android 13+ only; older versions deliver KEYCODE_BACK to dispatchKeyEvent.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (!expanded || backDispatcher != null) return;
        if (backCallback == null) {
            backCallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ? new OnBackAnimationCallback() {
                        @Override
                        public void onBackStarted(BackEvent e) {
                            root.removeCallbacks(outsideCollapse); // it's a back gesture, not a tap outside
                        }

                        @Override
                        public void onBackInvoked() {
                            onBack();
                        }
                    }
                    : (OnBackInvokedCallback) this::onBack;
        }
        backDispatcher = root.findOnBackInvokedDispatcher();
        if (backDispatcher != null)
            // Overlay priority so Buddy sees every Back (including gesture start) and closes the keyboard itself.
            backDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback);
    }

    private void unregisterBack() {
        if (backDispatcher != null) backDispatcher.unregisterOnBackInvokedCallback(backCallback);
        backDispatcher = null;
    }

    private boolean keyboardVisible() {
        if (imeTop > 0) return true;
        WindowInsets insets = root.getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }

    private void onBack() {
        root.removeCallbacks(outsideCollapse);
        if (keyboardVisible()) hideKeyboard();
        else if (changing) endChange();
        else if (pendingConfirm != null) answerConfirm(false);
        else collapse();
    }

    private void hideKeyboard() {
        InputMethodManager imm = svc.getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    /** Make the overlay invisible and untouchable while a gesture or screenshot runs. */
    void setPassThrough(boolean on) {
        if (passThrough == on) return;
        passThrough = on;
        root.setAlpha(on ? 0f : 1f);
        if (on) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ actions

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (changing && pendingConfirm != null) {
            input.setText("");
            answerChange(text);
            return;
        }
        String err = hub.send(sessions.current, text);
        if (err != null) {
            toast(err);
            return;
        }
        input.setText("");
        collapse();
    }

    private ImageButton mic;
    private RadialMenu radial;
    private HoldRing holdRing;
    private boolean wasWorking;

    /** 2. A ring that fills while the bubble is held: the menu opens when it's full. */
    private final class HoldRing extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float progress;
        private ValueAnimator anim;

        HoldRing(android.content.Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeWidth(dp(4));
        }

        void begin(long ms) {
            cancel();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setStartDelay(90); // a quick tap shows nothing
            anim.setDuration(ms - 90);
            anim.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void cancel() {
            if (anim != null) anim.cancel();
            progress = 0;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            if (progress <= 0) return;
            float in = dp(3);
            android.graphics.RectF r = new android.graphics.RectF(in, in, getWidth() - in, getHeight() - in);
            p.setColor(Theme.withAlpha(Ui.ACCENT, 0x40));
            c.drawArc(r, 0, 360, false, p);
            p.setColor(Ui.ACCENT);
            c.drawArc(r, -90, 360 * progress, false, p);
        }
    }

    /** 1. The first few times a task starts: the hold menu peeks out ("Hold me") and slides back. */
    private void maybePeek() {
        if (expanded || Prefs.menuHinted(svc) || Prefs.peekCount(svc) >= 3 || radial.isOpen() || root.getVisibility() != View.VISIBLE) return;
        Prefs.addPeek(svc);
        face.postDelayed(() -> {
            if (expanded || radial.isOpen()) return;
            int[] loc = new int[2];
            face.getLocationOnScreen(loc);
            int cx = loc[0] + face.getWidth() / 2, cy = loc[1] + face.getHeight() / 2;
            java.util.List<RadialMenu.Action> acts = new java.util.ArrayList<>();
            acts.add(new RadialMenu.Action(R.drawable.ic_stop, "Stop", true, () -> { }));
            acts.add(new RadialMenu.Action(R.drawable.ms_mic, "Add", false, () -> { }));
            acts.add(new RadialMenu.Action(R.drawable.ms_volume_off, "Voice", false, () -> { }));
            radial.peek(cx, cy, cx > screen().width() / 2, acts);
        }, 900);
    }

    /** 3. Tapped (not held) while it talks or works: say, a few times, that holding gives Stop. */
    private void maybeTip() {
        if (Prefs.menuHinted(svc) || Prefs.tipCount(svc) >= 3) return;
        boolean speaking = Voice.isSpeaking(), working = running || controlling;
        if (!speaking && !working) return;
        Prefs.addTip(svc);
        toast(speaking ? "Tip: hold the bubble to stop the voice" : "Tip: hold the bubble to stop the task or add an instruction");
    }

    /** Hold the bubble: what can be done right now, fanned out around it. */
    private void openMenu() {
        vibrate(30);
        Prefs.setMenuHinted(svc);
        java.util.List<RadialMenu.Action> acts = new java.util.ArrayList<>();
        boolean working = running || controlling;
        if (working) acts.add(new RadialMenu.Action(R.drawable.ic_stop, "Stop task", true, () -> {
            if (controlling) svc.takeBackControl();
            else {
                hub.stopAll();
                toast("Stopping…");
            }
        }));
        if (hub.runningCount() > 0) acts.add(new RadialMenu.Action(R.drawable.ms_mic, "Add instruction", false, this::dictateNote));
        if (Voice.isSpeaking()) acts.add(new RadialMenu.Action(R.drawable.ms_volume_off, "Stop voice", false, Voice::stopSpeaking));
        if (hub.runningCount() == 0) acts.add(new RadialMenu.Action(R.drawable.ms_mic, "Talk", false, () -> {
            expand();
            face.post(this::toggleMic);
        }));
        acts.add(new RadialMenu.Action(R.drawable.ms_forum, "Open chat", false, this::expand));
        acts.add(new RadialMenu.Action(R.drawable.ms_close, "Hide", false, this::dismissToNotification));
        int[] loc = new int[2];
        face.getLocationOnScreen(loc);
        int cx = loc[0] + face.getWidth() / 2, cy = loc[1] + face.getHeight() / 2;
        ((View) face.getParent()).animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).start();
        radial.open(cx, cy, cx > screen().width() / 2, acts,
                () -> ((View) face.getParent()).animate().scaleX(1f).scaleY(1f).setDuration(160).start());
    }

    /** The bubble's 🎙 while a task runs: say something, it goes to the working agent (ChatHub.addNote). */
    private void dictateNote() {
        if (noteListening) {
            Voice.finishListening();
            return;
        }
        final Sessions.Session ses = hub.latestRunning();
        if (ses == null) {
            toast("Nothing is running in Buddy's chats.");
            return;
        }
        if (!Voice.micAllowed(svc)) {
            toast("Allow the microphone in Buddy, then tap the mic again.");
            svc.startActivity(new android.content.Intent(svc, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("askMic", true));
            return;
        }
        String err = Voice.listen(svc, new Voice.Listener() {
            @Override
            public void onPartial(String text) {
                if (!text.isEmpty()) noteStatus("“" + text + "”");
            }

            @Override
            public void onFinal(String text) {
                noteDone();
                String e = hub.addNote(ses, text);
                toast(e != null ? e : "Got it: " + ses.brainName() + " will take it into account");
            }

            @Override
            public void onEnd(String message) {
                noteDone();
                if (message != null) toast(message);
            }

            @Override
            public void onStatus(String status) {
                noteStatus(status == null ? "Listening… tap the bubble when done" : status);
            }
        });
        if (err != null) {
            toast(err);
            return;
        }
        noteListening = true;
        noteStatus("Listening… tap the bubble when done");
    }

    private void noteStatus(String s) {
        pill.setText(s);
        pill.setVisibility(View.VISIBLE);
    }

    private void noteDone() {
        noteListening = false;
        refreshStatus();
    }

    /** Talk in the bubble: tap, speak, it sends when you stop. Tap again to send right away. */
    private void toggleMic() {
        if (Voice.listening()) {
            Voice.finishListening();
            return;
        }
        if (!Voice.micAllowed(svc)) {
            // only an activity can ask for the microphone permission
            toast("Allow the microphone in Buddy, then tap the mic again.");
            svc.startActivity(new android.content.Intent(svc, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("askMic", true));
            collapse();
            return;
        }
        final String before = input.getText().toString().trim();
        hideKeyboard();
        showVoiceBar(true);
        String err = Voice.listen(svc, new Voice.Listener() {
            @Override
            public void onPartial(String text) {
                voiceBar.setHeard(text);
            }

            @Override
            public void onFinal(String text) {
                input.setText(before.isEmpty() ? text : before + " " + text);
                micIdle();
                send();
            }

            @Override
            public void onEnd(String message) {
                micIdle();
                if (message != null) toast(message);
            }

            @Override
            public void onLevel(float level) {
                voiceBar.level(level);
            }

            @Override
            public void onStatus(String status) {
                voiceBar.setState(status);
            }
        });
        if (err != null) {
            showVoiceBar(false);
            toast(err);
        }
    }

    private void micIdle() {
        if (voiceBar != null) showVoiceBar(false);
        mic.setBackground(null);
        mic.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.MUTED));
        input.setHint(changing ? "What should change?" : "Ask " + sessions.current.brainName() + "…");
    }

    private void stop() {
        if (!sessions.current.running) toast("Nothing is running in this chat.");
        else hub.stop(sessions.current);
    }

    private void newChat(String backend) {
        hub.create(backend);
        showingList = false;
        renderCurrent();
    }

    private void deleteChat(Sessions.Session ses) {
        hub.delete(ses);
        renderCurrent();
    }

    // ------------------------------------------------------------ sessions

    private void toggleSessionList() {
        showingList = !showingList;
        if (showingList) {
            hideKeyboard();
            renderSessionList();
        }
        listScroll.setVisibility(showingList ? View.VISIBLE : View.GONE);
        scroll.setVisibility(showingList ? View.GONE : View.VISIBLE);
        updateViewing();
        updateHeader();
    }

    private void renderSessionList() {
        sessionList.removeAllViews();
        LinearLayout add = new LinearLayout(svc);
        // only agents that are set up (installed and signed in) get a "+" button
        for (Account a : Account.ALL) {
            if (SetupState.signedIn(svc, a.agent)) add.addView(smallButton("+ " + a.name(), v -> newChat(a.agent)));
        }
        sessionList.addView(add);
        for (final Sessions.Session ses : sessions.all) {
            LinearLayout row = new LinearLayout(svc);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(6), dp(10));
            row.setBackground(rounded(ses == sessions.current ? Ui.USER : Ui.CARD, 14, 0, 0));
            LinearLayout texts = new LinearLayout(svc);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(svc);
            t.setText((ses.unread ? "● " : "") + ses.label());
            t.setTextColor(Ui.TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(t);
            TextView sub = new TextView(svc);
            sub.setText(ses.brainName() + (ses.running ? " · working: " + ses.status
                    : " · " + android.text.format.DateUtils.getRelativeTimeSpanString(ses.updated)));
            sub.setTextColor(ses.running ? Ui.ACCENT : Ui.MUTED);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(sub);
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(panelIcon(R.drawable.ms_close, "Delete chat", v -> deleteChat(ses)));
            row.setOnClickListener(v -> {
                hub.select(ses);
                showingList = false;
                renderCurrent();
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(8);
            sessionList.addView(row, rp);
        }
    }

    /** Show the current chat's history in the panel. */
    private void renderCurrent() {
        listScroll.setVisibility(showingList ? View.VISIBLE : View.GONE);
        scroll.setVisibility(showingList ? View.GONE : View.VISIBLE);
        messages.removeAllViews();
        renderedFor = sessions.current;
        Sessions.Session ses = sessions.current;
        if (ses.messages.isEmpty()) {
            showMessage(Kind.STEP, "New " + ses.brainName() + " chat. Ask me to do something on your phone.");
        }
        for (Sessions.Msg m : ses.messages) showMessage(m.kind, m.label());
        input.setHint("Ask " + ses.brainName() + "…");
        updateViewing();
        refreshStatus();
        scrollToEnd();
    }

    private Sessions.Session renderedFor;

    /** Tell the hub which chat the open panel is showing, so its replies aren't marked unread. */
    private void updateViewing() {
        Sessions.Session now = expanded && !showingList ? sessions.current : null;
        if (now == viewingSes) return;
        if (viewingSes != null) hub.setViewing(viewingSes, false);
        viewingSes = now;
        if (now != null) hub.setViewing(now, true);
    }

    private void updateHeader() {
        Sessions.Session ses = sessions.current;
        if (headerStop != null) headerStop.setVisibility(ses.running || controlling ? View.VISIBLE : View.GONE);
        String h = showingList ? "Chats" : ses.label() + " ▾";
        if (!showingList && ses.running && !ses.status.isEmpty()) h = ses.status;
        header.setText(h);
    }

    // ------------------------------------------------------- hub listener

    @Override
    public void onChatsChanged() {
        if (renderedFor != sessions.current && !showingList) {
            renderCurrent();
            return;
        }
        refreshStatus();
        if (showingList) renderSessionList();
    }

    @Override
    public void onMessage(Sessions.Session ses, Sessions.Msg msg) {
        if (ses == sessions.current && renderedFor == ses) showMessage(msg.kind, msg.label());
        if (msg.kind == Kind.BUDDY && !(expanded && ses == sessions.current && !showingList)) vibrate(30);
    }

    /** The approval being asked right now (shown inline in the chat screen too), or null. */
    String pendingConfirmText() {
        return pendingConfirm == null ? null : confirmText.getText().toString();
    }

    /** Answer from the chat screen's inline approval card. */
    void answerFromApp(boolean approved) {
        if (pendingConfirm != null) answerConfirm(approved);
    }

    /** "Change" from the chat screen's inline approval card. */
    void changeFromApp(String change) {
        if (pendingConfirm != null) answerChange(change);
    }

    /** The user tapped Change: the composer (typing or the mic) now answers the card with what to change. */
    private boolean changing;

    private void startChange() {
        if (pendingConfirm == null) return;
        changing = true;
        input.setHint("What should change?");
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = svc.getSystemService(InputMethodManager.class);
            imm.showSoftInput(input, 0);
        }, 150);
    }

    private void endChange() {
        changing = false;
        input.setHint("Ask " + sessions.current.brainName() + "…");
        hideKeyboard();
    }

    private void answerChange(String change) {
        ConfirmCallback cb = pendingConfirm;
        pendingConfirm = null;
        endChange();
        confirmCard.setVisibility(View.GONE);
        // OpenCode chats show the answer on the approval step itself (it arrives after the answer)
        if (!"opencode".equals(sessions.current.backend)) {
            hub.note(sessions.current, Kind.STEP, "✎ Change requested: " + clip(change, 80));
        }
        if (cb != null) cb.onAnswer(false, change);
        refreshStatus();
        collapse();
        hub.changed();
    }

    void askConfirm(String summary, ConfirmCallback cb) {
        if (pendingConfirm != null) pendingConfirm.onAnswer(false, null);
        if (changing) endChange();
        pendingConfirm = cb;
        root.setVisibility(View.VISIBLE);
        confirmText.setText(summary);
        confirmCard.setVisibility(View.VISIBLE);
        refreshStatus();
        vibrate(60);
        expand();
        hub.changed();
    }

    /** An approval card is waiting for the user (the agent must not tap anything meanwhile). */
    boolean confirming() {
        return pendingConfirm != null;
    }

    void cancelConfirm() {
        pendingConfirm = null;
        if (changing) endChange();
        confirmCard.setVisibility(View.GONE);
        refreshStatus();
        hub.changed();
    }

    private void answerConfirm(boolean approved) {
        ConfirmCallback cb = pendingConfirm;
        pendingConfirm = null;
        if (changing) endChange();
        confirmCard.setVisibility(View.GONE);
        if (!"opencode".equals(sessions.current.backend)) {
            hub.note(sessions.current, Kind.STEP, approved ? "✓ Approved: " + clip(confirmText.getText().toString(), 80) : "✗ Denied");
        }
        if (cb != null) cb.onAnswer(approved, null);
        refreshStatus();
        collapse();
        hub.changed();
    }

    // ------------------------------------------------------ claude events

    void onToolActivity(String tool) {
        hub.onToolActivity();
    }

    // --------------------------------------------------------------- views

    enum Kind { USER, BUDDY, STEP, ERROR }

    /** External status (the notify tool): shown on the current chat until its next step. */
    void setStatus(String text) {
        if (text != null && !text.isEmpty()) sessions.current.status = text;
        refreshStatus();
    }

    /** Pill, face, badge and header from the hub's state. */
    private void refreshStatus() {
        running = hub.runningCount() > 0;
        boolean working = running || controlling;
        if (working && !wasWorking) maybePeek();
        wasWorking = working;
        String text = pendingConfirm != null ? "Waiting for your approval" : hub.pillText();
        if (controlling && pendingConfirm == null) {
            text = "Using your phone" + (text == null || text.isEmpty() ? "" : " · " + text);
        }
        if ((controlling || hub.runningCount() > 0) && pendingConfirm == null && !Prefs.menuHinted(svc)) {
            text = (text == null ? "" : text + " · ") + "hold the bubble for Stop and more";
        }
        if (!noteListening) { // while dictating a note the pill shows what's heard
            pill.setText(text == null ? "" : text);
            pill.setVisibility(text == null ? View.GONE : View.VISIBLE);
        }
        // in control: a solid accent pill, so it's clear not to touch the phone
        pill.setTextColor(controlling ? Ui.ON_ACCENT : Ui.TEXT);
        pill.setBackground(rounded(controlling ? Ui.ACCENT : Ui.BG, 18, Ui.ACCENT, 1));
        badge.setVisibility(!expanded && hub.anyUnread() ? View.VISIBLE : View.GONE);
        setWorking(running || controlling);
        updateHeader();
        if (!expanded) {
            try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
        }
    }

    /** Thinking face plus a gentle pulse while Claude is working; smiling face when idle. */
    private void setWorking(boolean on) {
        setWorking(on, false);
    }

    private boolean faceDragging() {
        return dismissWindow != null && dismissWindow.getVisibility() == View.VISIBLE;
    }

    private void setWorking(boolean on, boolean force) {
        ring.setSpinning(on);
        // keep the fired-up / bracing face while the bubble is being dragged
        if (force || !faceDragging()) face.setImageResource(on ? R.drawable.bubble_face_thinking : R.drawable.bubble_face);
        if (on && breathing == null) {
            breathing = ValueAnimator.ofFloat(1f, 0.9f);
            breathing.setDuration(750);
            breathing.setRepeatMode(ValueAnimator.REVERSE);
            breathing.setRepeatCount(ValueAnimator.INFINITE);
            breathing.addUpdateListener(a -> {
                float s = (float) a.getAnimatedValue();
                face.setScaleX(s);
                face.setScaleY(s);
            });
            breathing.start();
        } else if (!on && breathing != null) {
            breathing.cancel();
            breathing = null;
            face.setScaleX(1f);
            face.setScaleY(1f);
        }
    }

    private void showMessage(Kind kind, String text) {
        if (text == null) return;
        TextView tv = new TextView(svc);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, kind == Kind.STEP ? 12 : 15);
        tv.setTypeface(kind == Kind.STEP ? Ui.mono(svc, 400) : Ui.sans(svc, 400));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(kind == Kind.STEP ? 2 : 6);
        switch (kind) {
            case USER:
                tv.setTextColor(Ui.TEXT);
                tv.setBackground(Ui.corners(svc, Ui.USER, 18, 18, 6, 18));
                tv.setPadding(dp(12), dp(8), dp(12), dp(8));
                p.gravity = Gravity.END;
                p.leftMargin = dp(40);
                break;
            case BUDDY:
                tv.setTextColor(Ui.TEXT);
                tv.setBackground(Ui.corners(svc, Ui.CARD, 18, 18, 18, 6));
                tv.setPadding(dp(12), dp(8), dp(12), dp(8));
                p.rightMargin = dp(24);
                break;
            case STEP:
                tv.setTextColor(Ui.MUTED);
                tv.setPadding(dp(4), 0, dp(4), 0);
                tv.setText("· " + text);
                break;
            case ERROR:
                tv.setTextColor(Ui.ERR);
                tv.setPadding(dp(4), dp(4), dp(4), dp(4));
                tv.setText("⚠ " + text);
                break;
        }
        tv.setOnLongClickListener(v -> {
            ClipboardManager cm = svc.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("Buddy", text));
            toast("Copied");
            return true;
        });
        messages.addView(tv, p);
        while (messages.getChildCount() > Sessions.MAX_MESSAGES) messages.removeViewAt(0);
        scrollToEnd();
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private android.widget.ImageButton headerStop;

    /** A small header icon (20dp glyph in a 36dp target), in the app's muted style. */
    private android.widget.ImageButton toolIcon(int icon, String description, int tint, View.OnClickListener l) {
        android.widget.ImageButton b = Ui.iconButton(svc, icon, description, tint);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        b.setOnClickListener(l);
        return b;
    }

    private android.widget.ImageButton panelIcon(int icon, String description, View.OnClickListener l) {
        android.widget.ImageButton b = Ui.iconButton(svc, icon, description, Ui.MUTED);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        b.setOnClickListener(l);
        return b;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(svc);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Ui.MUTED);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setBackground(rounded(Ui.CARD, 12, 0, 0));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.leftMargin = dp(6);
        b.setLayoutParams(p);
        return b;
    }

    private Button actionButton(String label, int color) {
        Button b = new Button(svc);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setTypeface(Ui.sans(svc, 600));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setBackground(rounded(color, 16, 0, 0));
        return b;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private GradientDrawable circle(int fill, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private Rect screen() {
        return wm.getCurrentWindowMetrics().getBounds();
    }

    private int dp(int v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }

    private static String clip(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private void vibrate(long ms) {
        try {
            if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(svc, s, Toast.LENGTH_SHORT).show();
    }

    /** Drags the collapsed bubble; a tap without movement expands it. */
    private final class DragListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
        private float downX, downY;
        private int startX, startY;
        private boolean dragging, menu;
        private final Runnable hold = () -> {
            menu = true;
            holdRing.cancel();
            openMenu();
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    menu = false;
                    if (radial.isOpen()) radial.close(true);
                    face.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                    holdRing.begin(ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (menu) { // held: slide onto an action
                        radial.hover(e.getRawX(), e.getRawY());
                        return true;
                    }
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        face.removeCallbacks(hold);
                        holdRing.cancel();
                        dragging = true;
                        // Switch to left-anchored coordinates for free dragging.
                        int[] loc = new int[2];
                        face.getLocationOnScreen(loc);
                        lp.gravity = Gravity.TOP | Gravity.START;
                        collapsed.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                        startX = loc[0];
                        // where the face's centre is relative to the window position, in screen pixels
                        int[] rootLoc = new int[2];
                        root.getLocationOnScreen(rootLoc);
                        faceOffX = loc[0] - rootLoc[0] + face.getWidth() / 2;
                        faceOffY = loc[1] - rootLoc[1] + face.getHeight() / 2;
                        startX = rootLoc[0];
                        lpToScreenY = rootLoc[1] - startY; // window y vs screen y (status bar, insets)
                        if (windowAnim != null) windowAnim.cancel();
                        face.setImageResource(R.drawable.bubble_face_fired);
                        ((View) face.getParent()).animate().scaleX(1.14f).scaleY(1.14f)
                                .setInterpolator(new OvershootInterpolator(3f)).setDuration(220).start();
                    }
                    if (dragging) {
                        int x = startX + (int) dx, y = startY + (int) dy;
                        showDismissTarget(true);
                        boolean wasOver = overTarget;
                        if (overDismiss(x, y)) {
                            // elastic snap onto the target, like chat heads
                            if (!wasOver) {
                                int[] t = targetCenter();
                                animateWindowTo(t[0] - faceOffX, t[1] - faceOffY - lpToScreenY, 200,
                                        new OvershootInterpolator(2.4f), null);
                            }
                            feedArcs();
                            return true;
                        }
                        if (wasOver && windowAnim != null) windowAnim.cancel();
                        lp.x = x;
                        lp.y = y;
                        wm.updateViewLayout(root, lp);
                        feedArcs();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    face.removeCallbacks(hold);
                    holdRing.cancel();
                    if (menu) {
                        radial.release();
                        return true;
                    }
                    if (noteListening && !dragging) { // dictating an instruction: a tap ends it
                        Voice.finishListening();
                        return true;
                    }
                    if (dragging) {
                        ((View) face.getParent()).animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                        if (overTarget) {
                            endArcs(true);
                            dismissToNotification();
                            return true;
                        }
                        endArcs(false);
                        showDismissTarget(false);
                        setWorking(running || controlling, true);
                        // fling to the nearest edge with a little bounce
                        final boolean right = lp.x + faceOffX > screen().width() / 2;
                        int edgeX = right ? screen().width() - root.getWidth() - dp(6) : dp(6);
                        final int y = Math.max(dp(40), Math.min(lp.y, screen().height() - dp(160)));
                        animateWindowTo(edgeX, y, 340, new OvershootInterpolator(1.4f), () -> {
                            Prefs.saveBubblePos(svc, right ? 1 : 0, lp.y);
                            applyCollapsedParams();
                            wm.updateViewLayout(root, lp);
                        });
                    } else {
                        maybeTip();
                        expand();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    face.removeCallbacks(hold);
                    holdRing.cancel();
                    if (menu) return true;
                    endArcs(false);
                    showDismissTarget(false);
                    setWorking(running || controlling, true);
                    return true;
                default:
                    return false;
            }
        }
    }
}
