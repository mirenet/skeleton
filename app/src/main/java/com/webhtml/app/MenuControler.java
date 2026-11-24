package com.webhtml.app;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

public class MenuControler {

    // Long-press delay in milliseconds (1500 ms = 1.5 s)
    private static final long HOLD_DELAY_MS = 1000L;

    // Promenite na 1, 2, 3 ili bilo koji tačan broj prstiju koji mora biti spušten
    private static final int REQUIRED_FINGERS = 2;

    // Maksimalno dozvoljeno pomeranje prsta dok se čeka delay (u pikselima)
    private static final float MOVE_SLOP_PX = 25.0f;

    private final WebView overlayWebView;
    private final Handler holdHandler = new Handler(Looper.getMainLooper());

    private boolean holdScheduled = false;
    private boolean menuTriggered = false;
    private boolean consumeUntilRelease = false;
    private boolean longClickGuardAttached = false;
    private float[] initialX;
    private float[] initialY;

    private final Runnable holdRunnable = new Runnable() {
        @Override
        public void run() {
            holdScheduled = false;
            if (isMenuVisible()) {
                return;
            }
            menuTriggered = true;
            consumeUntilRelease = true;
            cancelUnderlyingTouches();
            clearSelectionOnUnderlyingWebViews();
            toggleMenu();
        }
    };

    public MenuControler(WebView overlayWebView) {
        this.overlayWebView = overlayWebView;
        ensureLongClickGuard();
    }

    public boolean handleTouchEvent(MotionEvent event) {
        ensureLongClickGuard();

        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        if (consumeUntilRelease) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                consumeUntilRelease = false;
                menuTriggered = false;
            }
            return true;
        }

        if (isMenuVisible()) {
            cancelHold();
            menuTriggered = false;
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                menuTriggered = false;
                if (pointerCount == REQUIRED_FINGERS) {
                    recordInitialPointers(event);
                    scheduleHold();
                } else {
                    // Ako je spušteno manje ili više prstiju od REQUIRED_FINGERS, otkazujemo čekanje
                    cancelHold();
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (menuTriggered) {
                    return true;
                }
                if (holdScheduled) {
                    if (pointerCount != REQUIRED_FINGERS || hasMovedBeyondSlop(event)) {
                        cancelHold();
                    }
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (menuTriggered) {
                    return true;
                }
                // Cim se podigne jedan od prstiju, broj aktivnih prstiju postaje (pointerCount - 1)
                if (holdScheduled && (pointerCount - 1) != REQUIRED_FINGERS) {
                    cancelHold();
                }
                break;

            case MotionEvent.ACTION_UP:
                cancelHold();
                if (menuTriggered) {
                    menuTriggered = false;
                    return true;
                }
                menuTriggered = false;
                break;

            case MotionEvent.ACTION_CANCEL:
                cancelHold();
                if (menuTriggered) {
                    menuTriggered = false;
                    return true;
                }
                break;
        }

        return false;
    }

    private void ensureLongClickGuard() {
        if (longClickGuardAttached || overlayWebView == null) return;
        View root = overlayWebView.getRootView();
        if (root instanceof ViewGroup) {
            attachLongClickGuardRecursive((ViewGroup) root);
            longClickGuardAttached = true;
        }
    }

    private void attachLongClickGuardRecursive(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof WebView && child != overlayWebView) {
                WebView target = (WebView) child;
                target.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        WebView.HitTestResult hit = target.getHitTestResult();
                        if (hit != null) {
                            int type = hit.getType();
                            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                                type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                                return true;
                            }
                        }
                        return false;
                    }
                });
            } else if (child instanceof ViewGroup) {
                attachLongClickGuardRecursive((ViewGroup) child);
            }
        }
    }

    public void cancelGesture() {
        cancelHold();
        menuTriggered = false;
        consumeUntilRelease = false;
    }

    private void scheduleHold() {
        holdHandler.removeCallbacks(holdRunnable);
        holdScheduled = true;
        holdHandler.postDelayed(holdRunnable, HOLD_DELAY_MS);
    }

    private void cancelHold() {
        holdScheduled = false;
        holdHandler.removeCallbacks(holdRunnable);
    }

    private void recordInitialPointers(MotionEvent event) {
        int count = event.getPointerCount();
        initialX = new float[count];
        initialY = new float[count];
        for (int i = 0; i < count; i++) {
            initialX[i] = event.getX(i);
            initialY[i] = event.getY(i);
        }
    }

    private boolean hasMovedBeyondSlop(MotionEvent event) {
        if (initialX == null || initialY == null || event.getPointerCount() != initialX.length) {
            return true;
        }
        for (int i = 0; i < initialX.length; i++) {
            float dx = Math.abs(event.getX(i) - initialX[i]);
            float dy = Math.abs(event.getY(i) - initialY[i]);
            if (dx > MOVE_SLOP_PX || dy > MOVE_SLOP_PX) {
                return true;
            }
        }
        return false;
    }

    private void cancelUnderlyingTouches() {
        if (overlayWebView == null) return;
        View root = overlayWebView.getRootView();
        if (root == null) return;

        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        try {
            root.dispatchTouchEvent(cancelEvent);
        } finally {
            cancelEvent.recycle();
        }
    }

    private void clearSelectionOnUnderlyingWebViews() {
        if (overlayWebView == null) return;
        View root = overlayWebView.getRootView();
        if (root instanceof ViewGroup) {
            clearSelectionRecursive((ViewGroup) root);
        }
    }

    private void clearSelectionRecursive(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof WebView && child != overlayWebView) {
                WebView target = (WebView) child;
                target.clearFocus();
                if (target.getSettings().getJavaScriptEnabled()) {
                    target.evaluateJavascript(
                        "(function(){try{var s=window.getSelection();if(s){s.removeAllRanges();}}catch(e){}})();",
                        null
                    );
                }
            } else if (child instanceof ViewGroup) {
                clearSelectionRecursive((ViewGroup) child);
            }
        }
    }

    public void toggleMenu() {
        cancelHold();
        if (overlayWebView != null) {
            if (overlayWebView.getVisibility() == View.VISIBLE) {
                overlayWebView.setVisibility(View.GONE);
            } else {
                overlayWebView.setVisibility(View.VISIBLE);
            }
        }
    }

    public void closeMenu() {
        cancelGesture();
        if (overlayWebView != null) {
            overlayWebView.setVisibility(View.GONE);
        }
    }

    public boolean isMenuVisible() {
        return overlayWebView != null && overlayWebView.getVisibility() == View.VISIBLE;
    }
}
