package com.chess.syncoverlay;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.UUID;

public class OverlayService extends Service {
    public static boolean isRunning = false;
    public static final String ACTION_APPLY_PRESET = "com.chess.syncoverlay.APPLY_PRESET";

    private WindowManager wm;
    private FrameLayout controlShell;
    private LinearLayout controlBarContent;
    private RookIconView rookCircleView;
    private GradientDrawable shellBgDrawable;
    private WindowManager.LayoutParams controlParams;

    private LinearLayout boardRoot;
    private WindowManager.LayoutParams boardParams;
    private WebView webView;

    // 独立底置“自定义局面编辑坞”窗口（完全不遮挡 8x8 棋盘任意一格）
    private LinearLayout editorDockRoot;
    private WindowManager.LayoutParams editorDockParams;
    private boolean isEditorDockAttached = false;
    private boolean isCustomEditing = false;

    private TextView btnToolMove;
    private TextView btnToolErase;
    private TextView btnColorWhite;
    private TextView btnColorBlack;
    private TextView btnEditReset;
    private TextView[] btnPieceSlots = new TextView[6];
    private final String[] pieceTypes = {"K", "Q", "R", "B", "N", "P"};
    private final String[] pieceLabels = {"王", "后", "车", "象", "马", "兵"};
    private TextView btnNextTurn;

    private String curEditMode = "move"; // "move", "erase", "place"
    private String curEditColor = "w";   // "w", "b"
    private String curEditPiece = "P";
    private boolean nextTurnIsAi = true;

    private LinearLayout slidersPanel;
    private LinearLayout savePanel;
    private EditText etSaveName;

    private TextView tvStatus;
    private TextView tvMove;
    private TextView btnToggleMode;
    private TextView btnCustomToggle;
    private TextView btnAdjustToggle;
    private TextView btnSaveToggle;

    private SeekBar sbSize;
    private SeekBar sbBoardAlpha;
    private SeekBar sbPieceAlpha;
    private TextView tvSizeVal;
    private TextView tvBoardAlphaVal;
    private TextView tvPieceAlphaVal;

    private boolean isPassThrough = false;
    private boolean isSlidersOpen = false;
    private boolean isSaveOpen = false;
    private boolean isMinimized = false;
    private boolean isMorphing = false;

    private long passThroughEnabledAtMs = 0L;
    private int outsideTapCount = 0;

    private int screenWidthPx;
    private int screenHeightPx;
    private int minBoardPx;
    private int boardSizePx;
    private int boardAlphaPercent = 42;
    private int pieceAlphaPercent = 92;
    private int circleSizePx;

    private int expandedCtrlX;
    private int expandedCtrlY;
    private int collapsedBubbleX = -1;
    private int collapsedBubbleY = -1;

    private ValueAnimator activeSnapAnimator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable autoSwitchRunnable;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private GradientDrawable makeDarkBtnBg(String fillHex, String strokeHex, float cornerDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fillHex));
        gd.setCornerRadius(dp(cornerDp));
        if (strokeHex != null) {
            gd.setStroke(Math.max(1, dp(1)), Color.parseColor(strokeHex));
        }
        return gd;
    }

    private GradientDrawable makeEmeraldGradientBg(float cornerDp, String strokeHex) {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Color.parseColor("#E6022C22"),
                        Color.parseColor("#D9065F46"),
                        Color.parseColor("#E6047857")
                }
        );
        gd.setCornerRadius(dp(cornerDp));
        gd.setStroke(Math.max(1, dp(1)), Color.parseColor(strokeHex));
        return gd;
    }

    private void attachSpringAnimation(View v) {
        v.setOnTouchListener((view, event) -> {
            int act = event.getAction();
            if (act == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.90f).scaleY(0.90f).setDuration(80)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(new OvershootInterpolator(1.7f)).start();
            }
            return false;
        });
    }

    private TextView makeTopTextBtn(String text, float textSp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        tv.setPadding(0, 0, 0, 0);
        tv.setBackground(makeDarkBtnBg("#D909090B", "#73FFFFFF", 8));
        attachSpringAnimation(tv);
        return tv;
    }

    private View makeTopVectorBtn(final int iconType) {
        View v = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            private final RectF arcRect = new RectF();

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                p.setColor(Color.WHITE);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);

                if (iconType == 1) {
                    float r = dp(6.4f);
                    float tip = dp(2.2f);
                    p.setStrokeWidth(dp(1.55f));
                    canvas.drawLine(cx - r, cy, cx + r, cy, p);
                    canvas.drawLine(cx, cy - r, cx, cy + r, p);
                    path.reset();
                    path.moveTo(cx - tip, cy - r + tip);
                    path.lineTo(cx, cy - r);
                    path.lineTo(cx + tip, cy - r + tip);

                    path.moveTo(cx - tip, cy + r - tip);
                    path.lineTo(cx, cy + r);
                    path.lineTo(cx + tip, cy + r - tip);

                    path.moveTo(cx - r + tip, cy - tip);
                    path.lineTo(cx - r, cy);
                    path.lineTo(cx - r + tip, cy + tip);

                    path.moveTo(cx + r - tip, cy - tip);
                    path.lineTo(cx + r, cy);
                    path.lineTo(cx + r - tip, cy + tip);
                    canvas.drawPath(path, p);
                } else if (iconType == 2) {
                    float radius = dp(4.2f);
                    float leftX = cx - dp(5.0f);
                    float rightX = cx + dp(1.5f);
                    float topY = cy - dp(3.4f);
                    float botY = cy + dp(5.0f);
                    p.setStrokeWidth(dp(1.6f));

                    path.reset();
                    path.moveTo(leftX, topY);
                    path.lineTo(rightX, topY);
                    arcRect.set(rightX - radius, topY, rightX + radius, topY + 2f * radius);
                    path.arcTo(arcRect, -90f, 180f, false);
                    path.lineTo(cx - dp(2.4f), botY);
                    canvas.drawPath(path, p);

                    float tip = dp(2.4f);
                    path.reset();
                    path.moveTo(leftX + tip, topY - tip);
                    path.lineTo(leftX, topY);
                    path.lineTo(leftX + tip, topY + tip);
                    canvas.drawPath(path, p);
                } else if (iconType == 3) {
                    float halfW = dp(5.4f);
                    p.setStrokeWidth(dp(1.75f));
                    canvas.drawLine(cx - halfW, cy, cx + halfW, cy, p);
                }
            }
        };
        v.setBackground(makeDarkBtnBg("#D909090B", "#73FFFFFF", 8));
        attachSpringAnimation(v);
        return v;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_APPLY_PRESET.equals(intent.getAction())) {
            applyPresetFromStore();
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidthPx = dm.widthPixels;
        screenHeightPx = dm.heightPixels;
        minBoardPx = dp(200);
        circleSizePx = dp(50);

        PresetStore.Preset active = PresetStore.getActive(this);
        boardSizePx = Math.max(minBoardPx, Math.min(screenWidthPx, active.size));
        boardAlphaPercent = active.boardAlpha;
        pieceAlphaPercent = active.pieceAlpha;

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int controlWidth = Math.max(boardSizePx, dp(332));
        expandedCtrlX = active.x;
        expandedCtrlY = Math.max(dp(30), active.y - dp(90));

        controlParams = new WindowManager.LayoutParams(
                controlWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        controlParams.gravity = Gravity.TOP | Gravity.START;
        controlParams.x = expandedCtrlX;
        controlParams.y = expandedCtrlY;

        controlShell = new FrameLayout(this);
        shellBgDrawable = makeDarkBtnBg("#E6070709", "#80FFFFFF", 12);
        controlShell.setBackground(shellBgDrawable);

        controlBarContent = new LinearLayout(this);
        controlBarContent.setOrientation(LinearLayout.VERTICAL);
        controlBarContent.setPadding(dp(6), dp(8), dp(6), dp(8));
        LayoutTransition lt = new LayoutTransition();
        lt.setDuration(220);
        controlBarContent.setLayoutTransition(lt);

        // 第一排：拖移 | 悔棋 | 自定义 | 调节 | 保存 | 换边 | 收起
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        View btnDrag = makeTopVectorBtn(1);
        View btnUndo = makeTopVectorBtn(2);
        btnCustomToggle = makeTopTextBtn("自定义", 11.5f);
        btnAdjustToggle = makeTopTextBtn("调节", 11.5f);
        btnSaveToggle = makeTopTextBtn("保存", 11.5f);
        TextView btnSide = makeTopTextBtn("换边", 11.5f);
        View btnMinimize = makeTopVectorBtn(3);

        int btnHeightPx = dp(36);
        LinearLayout.LayoutParams lpSym = new LinearLayout.LayoutParams(0, btnHeightPx, 0.78f);
        lpSym.setMargins(dp(2), 0, dp(2), 0);

        LinearLayout.LayoutParams lpCustom = new LinearLayout.LayoutParams(0, btnHeightPx, 1.32f);
        lpCustom.setMargins(dp(2), 0, dp(2), 0);

        LinearLayout.LayoutParams lpTxt = new LinearLayout.LayoutParams(0, btnHeightPx, 1.00f);
        lpTxt.setMargins(dp(2), 0, dp(2), 0);

        row1.addView(btnDrag, lpSym);
        row1.addView(btnUndo, lpSym);
        row1.addView(btnCustomToggle, lpCustom);
        row1.addView(btnAdjustToggle, lpTxt);
        row1.addView(btnSaveToggle, lpTxt);
        row1.addView(btnSide, lpTxt);
        row1.addView(btnMinimize, lpSym);

        // 第二排：状态提示 | 走法渐变徽章 | 穿透中/录入中切换键
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(6);

        tvStatus = new TextView(this);
        tvStatus.setText("选择执棋");
        tvStatus.setTextColor(Color.WHITE);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvStatus.setSingleLine(true);
        tvStatus.setIncludeFontPadding(false);
        row2.addView(tvStatus, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        tvMove = new TextView(this);
        tvMove.setText("走法: --");
        tvMove.setTextColor(Color.WHITE);
        tvMove.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvMove.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvMove.setSingleLine(true);
        tvMove.setIncludeFontPadding(false);
        tvMove.setPadding(dp(10), dp(6), dp(10), dp(6));
        tvMove.setBackground(makeEmeraldGradientBg(8, "#996EE7B7"));
        LinearLayout.LayoutParams lpMove = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpMove.setMargins(dp(6), 0, dp(6), 0);
        row2.addView(tvMove, lpMove);

        btnToggleMode = new TextView(this);
        btnToggleMode.setText("录入中");
        btnToggleMode.setTextColor(Color.WHITE);
        btnToggleMode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btnToggleMode.setTypeface(Typeface.DEFAULT_BOLD);
        btnToggleMode.setSingleLine(true);
        btnToggleMode.setIncludeFontPadding(false);
        btnToggleMode.setPadding(dp(12), dp(6), dp(12), dp(6));
        btnToggleMode.setBackground(makeDarkBtnBg("#D909090B", "#88FFFFFF", 8));
        attachSpringAnimation(btnToggleMode);
        row2.addView(btnToggleMode);

        buildSlidersPanel();
        buildSavePanel();
        buildCustomEditorDock(overlayType);

        controlBarContent.addView(row1);
        controlBarContent.addView(row2, row2Lp);
        controlBarContent.addView(slidersPanel);
        controlBarContent.addView(savePanel);

        rookCircleView = new RookIconView(this);
        rookCircleView.setVisibility(View.GONE);
        rookCircleView.setAlpha(0f);
        rookCircleView.setScaleX(0.55f);
        rookCircleView.setScaleY(0.55f);

        controlShell.addView(controlBarContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        controlShell.addView(rookCircleView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        boardParams = new WindowManager.LayoutParams(
                boardSizePx,
                boardSizePx,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        boardParams.gravity = Gravity.TOP | Gravity.START;
        boardParams.x = active.x;
        boardParams.y = active.y;
        boardParams.alpha = 1.0f;

        boardRoot = new LinearLayout(this);
        boardRoot.setOrientation(LinearLayout.VERTICAL);
        boardRoot.setBackgroundColor(Color.TRANSPARENT);

        boardRoot.setOnTouchListener((v, event) -> {
            if (isPassThrough && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                long elapsed = SystemClock.uptimeMillis() - passThroughEnabledAtMs;
                if (elapsed < 350L) {
                    return false;
                }
                outsideTapCount++;
                if (outsideTapCount == 1) {
                    scheduleAutoReturnToInput(4800L, "已选棋，落子后切回...");
                } else {
                    scheduleAutoReturnToInput(1800L, "已落子，即将切回录入...");
                }
            }
            return false;
        });

        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                syncOpacityToWeb();
            }
        });
        webView.loadUrl("file:///android_asset/index.html");

        boardRoot.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        View.OnTouchListener dragListener = new View.OnTouchListener() {
            private int initBoardX, initBoardY;
            private int initCtrlX, initCtrlY;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isMinimized || isMorphing) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(80).start();
                        initBoardX = boardParams.x;
                        initBoardY = boardParams.y;
                        initCtrlX = controlParams.x;
                        initCtrlY = controlParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX);
                        int dy = (int) (event.getRawY() - touchY);
                        boardParams.x = initBoardX + dx;
                        boardParams.y = initBoardY + dy;
                        controlParams.x = initCtrlX + dx;
                        controlParams.y = initCtrlY + dy;
                        expandedCtrlX = controlParams.x;
                        expandedCtrlY = controlParams.y;
                        wm.updateViewLayout(controlShell, controlParams);
                        wm.updateViewLayout(boardRoot, boardParams);
                        updateEditorDockPosition();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                                .setInterpolator(new OvershootInterpolator(1.7f)).start();
                        return true;
                }
                return false;
            }
        };
        btnDrag.setOnTouchListener(dragListener);

        rookCircleView.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float downRawX, downRawY;
            private boolean moved;
            private VelocityTracker velocityTracker;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!isMinimized || isMorphing) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (activeSnapAnimator != null && activeSnapAnimator.isRunning()) {
                            activeSnapAnimator.cancel();
                        }
                        if (velocityTracker == null) {
                            velocityTracker = VelocityTracker.obtain();
                        } else {
                            velocityTracker.clear();
                        }
                        velocityTracker.addMovement(event);

                        initX = controlParams.x;
                        initY = controlParams.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        moved = false;
                        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(85)
                                .setInterpolator(new DecelerateInterpolator()).start();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (velocityTracker != null) {
                            velocityTracker.addMovement(event);
                        }
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.hypot(dx, dy) > dp(6)) {
                            moved = true;
                        }
                        if (moved) {
                            controlParams.x = initX + (int) dx;
                            controlParams.y = initY + (int) dy;
                            wm.updateViewLayout(controlShell, controlParams);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float vx = 0f, vy = 0f;
                        if (velocityTracker != null) {
                            velocityTracker.addMovement(event);
                            velocityTracker.computeCurrentVelocity(1000, dp(3200));
                            vx = velocityTracker.getXVelocity();
                            vy = velocityTracker.getYVelocity();
                            velocityTracker.recycle();
                            velocityTracker = null;
                        }
                        v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                                .setInterpolator(new OvershootInterpolator(1.7f)).start();

                        if (!moved && event.getAction() == MotionEvent.ACTION_UP) {
                            expandFromCircle();
                        } else if (moved) {
                            snapCircleToEdgeWithInertia(vx, vy);
                        }
                        return true;
                }
                return false;
            }
        });

        btnUndo.setOnClickListener(v -> {
            if (isCustomEditing) {
                exitCustomEditMode(true);
            }
            cancelAutoReturn();
            setBoardPassThrough(false);
            webView.evaluateJavascript("undoTurn()", null);
        });

        btnCustomToggle.setOnClickListener(v -> {
            if (isCustomEditing) {
                exitCustomEditMode(true);
            } else {
                enterCustomEditMode();
            }
        });

        btnAdjustToggle.setOnClickListener(v -> toggleSlidersPanel());
        btnSaveToggle.setOnClickListener(v -> toggleSavePanel());

        btnSide.setOnClickListener(v -> {
            if (isCustomEditing) {
                exitCustomEditMode(true);
            }
            cancelAutoReturn();
            setBoardPassThrough(false);
            webView.evaluateJavascript("openPicker()", null);
        });

        btnMinimize.setOnClickListener(v -> {
            if (isCustomEditing) {
                exitCustomEditMode(true);
            }
            collapseToCircle();
        });

        btnToggleMode.setOnClickListener(v -> {
            if (isCustomEditing) {
                Toast.makeText(this, "请先完成或取消自定义编辑", Toast.LENGTH_SHORT).show();
                return;
            }
            cancelAutoReturn();
            setBoardPassThrough(!isPassThrough);
        });

        wm.addView(boardRoot, boardParams);
        wm.addView(controlShell, controlParams);
    }

    // ===== 构建独立底置“自定义局面编辑坞”（完全不遮挡棋盘）=====
    private void buildCustomEditorDock(int overlayType) {
        editorDockParams = new WindowManager.LayoutParams(
                Math.max(boardSizePx, dp(332)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        editorDockParams.gravity = Gravity.TOP | Gravity.START;

        editorDockRoot = new LinearLayout(this);
        editorDockRoot.setOrientation(LinearLayout.VERTICAL);
        editorDockRoot.setBackground(makeDarkBtnBg("#EC070709", "#80FFFFFF", 12));
        editorDockRoot.setPadding(dp(8), dp(8), dp(8), dp(8));

        // 第一排：移动 | 擦除 | 白棋 | 黑棋 | 还原
        LinearLayout rTools = new LinearLayout(this);
        rTools.setOrientation(LinearLayout.HORIZONTAL);
        rTools.setGravity(Gravity.CENTER_VERTICAL);

        btnToolMove = makeTopTextBtn("移动", 12f);
        btnToolErase = makeTopTextBtn("擦除", 12f);
        btnColorWhite = makeTopTextBtn("白棋", 12f);
        btnColorBlack = makeTopTextBtn("黑棋", 12f);
        btnEditReset = makeTopTextBtn("还原", 12f);

        int h1 = dp(32);
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(0, h1, 1f);
        lpT.setMargins(dp(2), 0, dp(2), 0);
        rTools.addView(btnToolMove, lpT);
        rTools.addView(btnToolErase, lpT);
        rTools.addView(btnColorWhite, lpT);
        rTools.addView(btnColorBlack, lpT);
        rTools.addView(btnEditReset, lpT);

        btnToolMove.setOnClickListener(v -> {
            curEditMode = "move";
            syncCustomEditorVisuals();
        });
        btnToolErase.setOnClickListener(v -> {
            curEditMode = "erase";
            syncCustomEditorVisuals();
        });
        btnColorWhite.setOnClickListener(v -> {
            curEditColor = "w";
            syncCustomEditorVisuals();
        });
        btnColorBlack.setOnClickListener(v -> {
            curEditColor = "b";
            syncCustomEditorVisuals();
        });
        btnEditReset.setOnClickListener(v -> {
            webView.evaluateJavascript("resetCustomEdit()", null);
        });

        // 第二排：6种棋子放置键（王 后 车 象 马 兵）
        LinearLayout rPieces = new LinearLayout(this);
        rPieces.setOrientation(LinearLayout.HORIZONTAL);
        rPieces.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpRow2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpRow2.topMargin = dp(6);

        int h2 = dp(34);
        for (int i = 0; i < 6; i++) {
            final int idx = i;
            TextView tv = makeTopTextBtn(pieceLabels[i], 13f);
            LinearLayout.LayoutParams lpP = new LinearLayout.LayoutParams(0, h2, 1f);
            lpP.setMargins(dp(2), 0, dp(2), 0);
            tv.setOnClickListener(v -> {
                curEditMode = "place";
                curEditPiece = pieceTypes[idx];
                syncCustomEditorVisuals();
            });
            btnPieceSlots[i] = tv;
            rPieces.addView(tv, lpP);
        }

        // 第三排：确认后行动方切换 | 取消 | 确定（重算）
        LinearLayout rConfirm = new LinearLayout(this);
        rConfirm.setOrientation(LinearLayout.HORIZONTAL);
        rConfirm.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpRow3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpRow3.topMargin = dp(7);

        btnNextTurn = makeTopTextBtn("确认后: 己方走", 12f);
        TextView btnCancel = makeTopTextBtn("取消", 12.5f);
        TextView btnConfirm = makeTopTextBtn("确定", 12.5f);
        btnConfirm.setBackground(makeEmeraldGradientBg(8, "#996EE7B7"));

        int h3 = dp(35);
        LinearLayout.LayoutParams lpTurn = new LinearLayout.LayoutParams(0, h3, 1.75f);
        lpTurn.setMargins(dp(2), 0, dp(3), 0);
        LinearLayout.LayoutParams lpCancel = new LinearLayout.LayoutParams(0, h3, 0.90f);
        lpCancel.setMargins(dp(2), 0, dp(2), 0);
        LinearLayout.LayoutParams lpOk = new LinearLayout.LayoutParams(0, h3, 1.15f);
        lpOk.setMargins(dp(2), 0, dp(2), 0);

        btnNextTurn.setOnClickListener(v -> {
            nextTurnIsAi = !nextTurnIsAi;
            updateNextTurnButtonStyle();
        });
        btnCancel.setOnClickListener(v -> exitCustomEditMode(true));
        btnConfirm.setOnClickListener(v -> {
            webView.evaluateJavascript("confirmCustomEdit(" + nextTurnIsAi + ")", null);
        });

        rConfirm.addView(btnNextTurn, lpTurn);
        rConfirm.addView(btnCancel, lpCancel);
        rConfirm.addView(btnConfirm, lpOk);

        editorDockRoot.addView(rTools);
        editorDockRoot.addView(rPieces, lpRow2);
        editorDockRoot.addView(rConfirm, lpRow3);
    }

    private void updateNextTurnButtonStyle() {
        if (nextTurnIsAi) {
            btnNextTurn.setText("确认后: 己方走");
            btnNextTurn.setBackground(makeDarkBtnBg("#D909090B", "#996EE7B7", 8));
        } else {
            btnNextTurn.setText("确认后: 对手走");
            btnNextTurn.setBackground(makeDarkBtnBg("#D909090B", "#73FFFFFF", 8));
        }
    }

    private void syncCustomEditorVisuals() {
        // 工具高亮（移动 / 擦除 / 6种放置棋子 互斥高亮：纯白底黑字）
        styleToolChip(btnToolMove, "move".equals(curEditMode));
        styleToolChip(btnToolErase, "erase".equals(curEditMode));

        for (int i = 0; i < 6; i++) {
            boolean isActive = "place".equals(curEditMode) && pieceTypes[i].equals(curEditPiece);
            styleToolChip(btnPieceSlots[i], isActive);
        }

        // 阵营色指示（白棋 / 黑棋）
        boolean isW = "w".equals(curEditColor);
        btnColorWhite.setBackground(makeDarkBtnBg(isW ? "#27272A" : "#D909090B", isW ? "#FFFFFF" : "#40FFFFFF", 8));
        btnColorWhite.setTextColor(isW ? Color.WHITE : Color.parseColor("#A1A1AA"));

        btnColorBlack.setBackground(makeDarkBtnBg(!isW ? "#27272A" : "#D909090B", !isW ? "#FFFFFF" : "#40FFFFFF", 8));
        btnColorBlack.setTextColor(!isW ? Color.WHITE : Color.parseColor("#A1A1AA"));

        updateNextTurnButtonStyle();

        // 更新顶部状态提示
        if ("move".equals(curEditMode)) {
            tvStatus.setText("自定义: 点选自由移子");
        } else if ("erase".equals(curEditMode)) {
            tvStatus.setText("自定义: 点击格子擦除");
        } else {
            String cname = isW ? "白" : "黑";
            String pname = "";
            for (int i = 0; i < 6; i++) {
                if (pieceTypes[i].equals(curEditPiece)) pname = pieceLabels[i];
            }
            tvStatus.setText("自定义: 放置" + cname + pname);
        }

        webView.evaluateJavascript(
                "setCustomEditTool('" + curEditMode + "','" + curEditColor + "','" + curEditPiece + "')",
                null
        );
    }

    private void styleToolChip(TextView tv, boolean active) {
        if (active) {
            tv.setBackground(makeDarkBtnBg("#FFFFFF", "#FFFFFF", 8));
            tv.setTextColor(Color.BLACK);
        } else {
            tv.setBackground(makeDarkBtnBg("#D909090B", "#59FFFFFF", 8));
            tv.setTextColor(Color.WHITE);
        }
    }

    private void updateEditorDockPosition() {
        if (!isEditorDockAttached || editorDockRoot == null) return;
        int dockWidth = Math.max(boardSizePx, dp(332));
        int dockEstimatedH = dp(136);
        int belowY = boardParams.y + boardSizePx + dp(6);
        int targetY;
        if (belowY + dockEstimatedH <= screenHeightPx - dp(16)) {
            targetY = belowY;
        } else {
            targetY = Math.max(dp(24), screenHeightPx - dockEstimatedH - dp(16));
        }
        editorDockParams.width = dockWidth;
        editorDockParams.x = boardParams.x;
        editorDockParams.y = targetY;
        try {
            wm.updateViewLayout(editorDockRoot, editorDockParams);
        } catch (Exception ignored) {}
    }

    private void enterCustomEditMode() {
        if (isCustomEditing) return;
        if (isSlidersOpen) {
            isSlidersOpen = false;
            slidersPanel.setVisibility(View.GONE);
            btnAdjustToggle.setBackground(makeDarkBtnBg("#D909090B", "#73FFFFFF", 8));
            wm.updateViewLayout(controlShell, controlParams);
        }
        if (isSaveOpen) {
            setSavePanelVisible(false);
        }
        cancelAutoReturn();
        setBoardPassThrough(false);

        isCustomEditing = true;
        curEditMode = "move";
        nextTurnIsAi = true;

        btnCustomToggle.setBackground(makeDarkBtnBg("#FFFFFF", "#FFFFFF", 8));
        btnCustomToggle.setTextColor(Color.BLACK);

        webView.evaluateJavascript("enterCustomEdit()", null);
        syncCustomEditorVisuals();

        int dockWidth = Math.max(boardSizePx, dp(332));
        int dockEstimatedH = dp(136);
        int belowY = boardParams.y + boardSizePx + dp(6);
        editorDockParams.width = dockWidth;
        editorDockParams.x = boardParams.x;
        editorDockParams.y = (belowY + dockEstimatedH <= screenHeightPx - dp(16))
                ? belowY
                : Math.max(dp(24), screenHeightPx - dockEstimatedH - dp(16));

        editorDockRoot.animate().cancel();
        if (!isEditorDockAttached) {
            editorDockRoot.setAlpha(0f);
            editorDockRoot.setTranslationY(dp(14));
            editorDockRoot.setScaleX(0.95f);
            editorDockRoot.setScaleY(0.95f);
            wm.addView(editorDockRoot, editorDockParams);
            isEditorDockAttached = true;
        } else {
            wm.updateViewLayout(editorDockRoot, editorDockParams);
        }
        editorDockRoot.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260)
                .setInterpolator(new PathInterpolator(0.20f, 0.94f, 0.32f, 1.03f))
                .start();
    }

    private void exitCustomEditMode(boolean cancelChanges) {
        if (!isCustomEditing) return;
        isCustomEditing = false;

        btnCustomToggle.setBackground(makeDarkBtnBg("#D909090B", "#73FFFFFF", 8));
        btnCustomToggle.setTextColor(Color.WHITE);

        if (cancelChanges) {
            webView.evaluateJavascript("cancelCustomEdit()", null);
            tvStatus.setText("录入对手");
        }

        if (isEditorDockAttached && editorDockRoot != null) {
            editorDockRoot.animate().cancel();
            editorDockRoot.animate()
                    .alpha(0f)
                    .translationY(dp(10))
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        if (!isCustomEditing && isEditorDockAttached) {
                            try {
                                wm.removeView(editorDockRoot);
                            } catch (Exception ignored) {}
                            isEditorDockAttached = false;
                        }
                    })
                    .start();
        }
    }

    private void snapCircleToEdgeWithInertia(float vx, float vy) {
        if (activeSnapAnimator != null && activeSnapAnimator.isRunning()) {
            activeSnapAnimator.cancel();
        }
        final int startX = controlParams.x;
        final int startY = controlParams.y;

        float projectedX = startX + vx * 0.14f;
        float projectedY = startY + vy * 0.14f;

        int edgeMargin = dp(10);
        boolean snapLeft = (projectedX + circleSizePx / 2f) < (screenWidthPx / 2f);
        final int targetX = snapLeft ? edgeMargin : (screenWidthPx - circleSizePx - edgeMargin);

        int minTop = dp(36);
        int maxBottom = screenHeightPx - circleSizePx - dp(48);
        final int targetY = Math.max(minTop, Math.min(maxBottom, Math.round(projectedY)));

        activeSnapAnimator = ValueAnimator.ofFloat(0f, 1f);
        activeSnapAnimator.setDuration(400);
        activeSnapAnimator.setInterpolator(new OvershootInterpolator(1.12f));
        activeSnapAnimator.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            controlParams.x = Math.round(startX + (targetX - startX) * f);
            controlParams.y = Math.round(startY + (targetY - startY) * f);
            try {
                wm.updateViewLayout(controlShell, controlParams);
            } catch (Exception ignored) {}
        });
        activeSnapAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                collapsedBubbleX = targetX;
                collapsedBubbleY = targetY;
            }
        });
        activeSnapAnimator.start();
    }

    private void collapseToCircle() {
        if (isMinimized || isMorphing) return;
        isMorphing = true;

        if (isSlidersOpen) {
            isSlidersOpen = false;
            slidersPanel.setVisibility(View.GONE);
            btnAdjustToggle.setBackground(makeDarkBtnBg("#D909090B", "#73FFFFFF", 8));
        }
        if (isSaveOpen) {
            setSavePanelVisible(false);
        }

        int targetCtrlWidth = Math.max(boardSizePx, dp(332));
        controlShell.measure(
                View.MeasureSpec.makeMeasureSpec(targetCtrlWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        final int startW = controlShell.getWidth() > 0 ? controlShell.getWidth() : targetCtrlWidth;
        final int startH = controlShell.getHeight() > 0 ? controlShell.getHeight() : controlShell.getMeasuredHeight();
        final int startX = controlParams.x;
        final int startY = controlParams.y;
        expandedCtrlX = startX;
        expandedCtrlY = startY;

        final int endW = circleSizePx;
        final int endH = circleSizePx;
        int edgeMargin = dp(10);
        final int endX = (collapsedBubbleX >= 0) ? collapsedBubbleX : (screenWidthPx - endW - edgeMargin);
        final int endY = (collapsedBubbleY >= 0) ? collapsedBubbleY : Math.max(dp(36), startY);

        final float startRadius = dp(12);
        final float endRadius = circleSizePx / 2f;

        controlBarContent.animate()
                .alpha(0f)
                .scaleX(0.84f)
                .scaleY(0.84f)
                .setDuration(140)
                .setInterpolator(new PathInterpolator(0.32f, 0.72f, 0f, 1f))
                .withEndAction(() -> controlBarContent.setVisibility(View.GONE))
                .start();

        ValueAnimator morphAnim = ValueAnimator.ofFloat(0f, 1f);
        morphAnim.setDuration(350);
        morphAnim.setInterpolator(new PathInterpolator(0.20f, 0.96f, 0.32f, 1.03f));
        morphAnim.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            controlParams.width = Math.round(startW + (endW - startW) * f);
            controlParams.height = Math.round(startH + (endH - startH) * f);
            controlParams.x = Math.round(startX + (endX - startX) * f);
            controlParams.y = Math.round(startY + (endY - startY) * f);
            shellBgDrawable.setCornerRadius(startRadius + (endRadius - startRadius) * f);
            try {
                wm.updateViewLayout(controlShell, controlParams);
            } catch (Exception ignored) {}
        });

        morphAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                rookCircleView.setVisibility(View.VISIBLE);
                rookCircleView.setAlpha(0f);
                rookCircleView.setScaleX(0.55f);
                rookCircleView.setScaleY(0.55f);
                rookCircleView.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setStartDelay(105)
                        .setDuration(240)
                        .setInterpolator(new OvershootInterpolator(1.6f))
                        .start();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                isMinimized = true;
                isMorphing = false;
                collapsedBubbleX = controlParams.x;
                collapsedBubbleY = controlParams.y;
            }
        });
        morphAnim.start();
    }

    private void expandFromCircle() {
        if (!isMinimized || isMorphing) return;
        isMorphing = true;

        final int startW = controlParams.width;
        final int startH = controlParams.height;
        final int startX = controlParams.x;
        final int startY = controlParams.y;

        final int endW = Math.max(boardSizePx, dp(332));
        controlBarContent.setVisibility(View.VISIBLE);
        controlBarContent.setAlpha(0f);
        controlBarContent.setScaleX(0.88f);
        controlBarContent.setScaleY(0.88f);
        controlBarContent.measure(
                View.MeasureSpec.makeMeasureSpec(endW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        final int endH = controlBarContent.getMeasuredHeight();
        final int endX = boardParams.x;
        final int endY = Math.max(dp(30), boardParams.y - dp(90));

        final float startRadius = circleSizePx / 2f;
        final float endRadius = dp(12);

        rookCircleView.animate()
                .alpha(0f)
                .scaleX(0.55f)
                .scaleY(0.55f)
                .setDuration(130)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> rookCircleView.setVisibility(View.GONE))
                .start();

        ValueAnimator expandAnim = ValueAnimator.ofFloat(0f, 1f);
        expandAnim.setDuration(350);
        expandAnim.setInterpolator(new PathInterpolator(0.20f, 0.96f, 0.32f, 1.03f));
        expandAnim.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            controlParams.width = Math.round(startW + (endW - startW) * f);
            controlParams.height = Math.round(startH + (endH - startH) * f);
            controlParams.x = Math.round(startX + (endX - startX) * f);
            controlParams.y = Math.round(startY + (endY - startY) * f);
            shellBgDrawable.setCornerRadius(startRadius + (endRadius - startRadius) * f);
            try {
                wm.updateViewLayout(controlShell, controlParams);
            } catch (Exception ignored) {}
        });

        expandAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                controlBarContent.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setStartDelay(95)
                        .setDuration(230)
                        .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                        .start();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                controlParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
                try {
                    wm.updateViewLayout(controlShell, controlParams);
                } catch (Exception ignored) {}
                isMinimized = false;
                isMorphing = false;
            }
        });
        expandAnim.start();
    }

    private void buildSlidersPanel() {
        slidersPanel = new LinearLayout(this);
        slidersPanel.setOrientation(LinearLayout.VERTICAL);
        slidersPanel.setPadding(dp(6), dp(10), dp(6), dp(4));
        slidersPanel.setVisibility(View.GONE);

        LinearLayout rSize = makeSliderRow("棋盘尺寸", boardSizePx + "px");
        tvSizeVal = (TextView) rSize.getChildAt(1);
        sbSize = new SeekBar(this);
        sbSize.setMax(screenWidthPx - minBoardPx);
        sbSize.setProgress(boardSizePx - minBoardPx);
        sbSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                boardSizePx = minBoardPx + progress;
                tvSizeVal.setText(boardSizePx + "px");
                if (fromUser) {
                    boardParams.width = boardSizePx;
                    boardParams.height = boardSizePx;
                    controlParams.width = Math.max(boardSizePx, dp(332));
                    wm.updateViewLayout(boardRoot, boardParams);
                    wm.updateViewLayout(controlShell, controlParams);
                    updateEditorDockPosition();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        LinearLayout rBg = makeSliderRow("背景透度", boardAlphaPercent + "%");
        tvBoardAlphaVal = (TextView) rBg.getChildAt(1);
        sbBoardAlpha = new SeekBar(this);
        sbBoardAlpha.setMax(100);
        sbBoardAlpha.setProgress(boardAlphaPercent);
        sbBoardAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                boardAlphaPercent = progress;
                tvBoardAlphaVal.setText(progress + "%");
                syncOpacityToWeb();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        LinearLayout rPc = makeSliderRow("棋子透度", pieceAlphaPercent + "%");
        tvPieceAlphaVal = (TextView) rPc.getChildAt(1);
        sbPieceAlpha = new SeekBar(this);
        sbPieceAlpha.setMax(100);
        sbPieceAlpha.setProgress(pieceAlphaPercent);
        sbPieceAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pieceAlphaPercent = progress;
                tvPieceAlphaVal.setText(progress + "%");
                syncOpacityToWeb();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        slidersPanel.addView(rSize);
        slidersPanel.addView(sbSize);
        slidersPanel.addView(rBg);
        slidersPanel.addView(sbBoardAlpha);
        slidersPanel.addView(rPc);
        slidersPanel.addView(sbPieceAlpha);
    }

    private LinearLayout makeSliderRow(String label, String valText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(4), dp(2), dp(2));

        TextView tvL = new TextView(this);
        tvL.setText(label);
        tvL.setTextColor(Color.WHITE);
        tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(tvL, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvV = new TextView(this);
        tvV.setText(valText);
        tvV.setTextColor(Color.parseColor("#D4D4D8"));
        tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(tvV);
        return row;
    }

    private void buildSavePanel() {
        savePanel = new LinearLayout(this);
        savePanel.setOrientation(LinearLayout.VERTICAL);
        savePanel.setPadding(dp(4), dp(10), dp(4), dp(4));
        savePanel.setVisibility(View.GONE);

        etSaveName = new EditText(this);
        etSaveName.setHint("输入存档名称");
        etSaveName.setTextColor(Color.WHITE);
        etSaveName.setHintTextColor(Color.parseColor("#71717A"));
        etSaveName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        etSaveName.setSingleLine(true);
        etSaveName.setPadding(dp(10), dp(7), dp(10), dp(7));
        etSaveName.setBackground(makeDarkBtnBg("#18181B", "#FFFFFF", 8));
        savePanel.addView(etSaveName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpRow = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpRow.topMargin = dp(8);

        TextView btnOverwrite = makeTopTextBtn("覆盖当前存档", 12);
        TextView btnSaveNew = makeTopTextBtn("存为新存档", 12);

        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(0, dp(34), 1f);
        lpB.setMargins(dp(3), 0, dp(3), 0);

        btnOverwrite.setOnClickListener(v -> {
            PresetStore.Preset cur = PresetStore.getActive(this);
            String inputName = etSaveName.getText().toString().trim();
            if (!inputName.isEmpty()) cur.name = inputName;
            cur.x = boardParams.x;
            cur.y = boardParams.y;
            cur.size = boardSizePx;
            cur.boardAlpha = boardAlphaPercent;
            cur.pieceAlpha = pieceAlphaPercent;
            PresetStore.saveOrUpdate(this, cur);
            sendBroadcast(new Intent(MainActivity.ACTION_PRESET_CHANGED).setPackage(getPackageName()));
            Toast.makeText(this, "已保存: " + cur.name, Toast.LENGTH_SHORT).show();
            toggleSavePanel();
        });

        btnSaveNew.setOnClickListener(v -> {
            String inputName = etSaveName.getText().toString().trim();
            if (inputName.isEmpty()) inputName = "存档 " + (PresetStore.getAll(this).size() + 1);
            PresetStore.Preset created = new PresetStore.Preset(
                    UUID.randomUUID().toString(),
                    inputName,
                    boardParams.x,
                    boardParams.y,
                    boardSizePx,
                    boardAlphaPercent,
                    pieceAlphaPercent
            );
            PresetStore.saveOrUpdate(this, created);
            sendBroadcast(new Intent(MainActivity.ACTION_PRESET_CHANGED).setPackage(getPackageName()));
            Toast.makeText(this, "已新建存档: " + inputName, Toast.LENGTH_SHORT).show();
            toggleSavePanel();
        });

        btnRow.addView(btnOverwrite, lpB);
        btnRow.addView(btnSaveNew, lpB);
        savePanel.addView(btnRow, lpRow);
    }

    private void toggleSlidersPanel() {
        if (isCustomEditing) exitCustomEditMode(true);
        if (isSaveOpen) setSavePanelVisible(false);
        isSlidersOpen = !isSlidersOpen;
        if (isSlidersOpen) {
            slidersPanel.setVisibility(View.VISIBLE);
            slidersPanel.setAlpha(0f);
            slidersPanel.setTranslationY(-dp(8));
            slidersPanel.animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).start();
            btnAdjustToggle.setBackground(makeDarkBtnBg("#27272A", "#FFFFFF", 8));
        } else {
            slidersPanel.setVisibility(View.GONE);
            btnAdjustToggle.setBackground(makeDarkBtnBg("#D909090B", "#73FFFFFF", 8));
        }
        wm.updateViewLayout(controlShell, controlParams);
    }

    private void toggleSavePanel() {
        if (isCustomEditing) exitCustomEditMode(true);
        if (isSlidersOpen) {
            isSlidersOpen = false;
            slidersPanel.setVisibility(View.GONE);
            btnAdjustToggle.setBackground(makeDarkBtnBg("#D909090B", "#73FFFFFF", 8));
        }
        setSavePanelVisible(!isSaveOpen);
    }

    private void setSavePanelVisible(boolean visible) {
        isSaveOpen = visible;
        if (visible) {
            PresetStore.Preset cur = PresetStore.getActive(this);
            etSaveName.setText(cur.name);
            etSaveName.setSelection(etSaveName.getText().length());
            controlParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            savePanel.setVisibility(View.VISIBLE);
            savePanel.setAlpha(0f);
            savePanel.animate().alpha(1f).setDuration(180).start();
            btnSaveToggle.setBackground(makeDarkBtnBg("#27272A", "#FFFFFF", 8));
            wm.updateViewLayout(controlShell, controlParams);
            etSaveName.requestFocus();
        } else {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etSaveName.getWindowToken(), 0);
            controlParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            savePanel.setVisibility(View.GONE);
            btnSaveToggle.setBackground(makeDarkBtnBg("#D909090B", "#73FFFFFF", 8));
            wm.updateViewLayout(controlShell, controlParams);
        }
    }

    private void applyPresetFromStore() {
        PresetStore.Preset p = PresetStore.getActive(this);
        boardSizePx = Math.max(minBoardPx, Math.min(screenWidthPx, p.size));
        boardAlphaPercent = p.boardAlpha;
        pieceAlphaPercent = p.pieceAlpha;

        boardParams.x = p.x;
        boardParams.y = p.y;
        boardParams.width = boardSizePx;
        boardParams.height = boardSizePx;

        if (!isMinimized) {
            controlParams.x = p.x;
            controlParams.y = Math.max(dp(30), p.y - dp(90));
            controlParams.width = Math.max(boardSizePx, dp(332));
            wm.updateViewLayout(controlShell, controlParams);
        }

        if (sbSize != null) sbSize.setProgress(boardSizePx - minBoardPx);
        if (sbBoardAlpha != null) sbBoardAlpha.setProgress(boardAlphaPercent);
        if (sbPieceAlpha != null) sbPieceAlpha.setProgress(pieceAlphaPercent);

        wm.updateViewLayout(boardRoot, boardParams);
        updateEditorDockPosition();
        syncOpacityToWeb();
    }

    private void syncOpacityToWeb() {
        if (webView != null) {
            webView.evaluateJavascript("setBoardOpacity(" + boardAlphaPercent + ");setPieceOpacity(" + pieceAlphaPercent + ");", null);
        }
    }

    private void setBoardPassThrough(boolean passThrough) {
        isPassThrough = passThrough;
        outsideTapCount = 0;
        if (passThrough) {
            passThroughEnabledAtMs = SystemClock.uptimeMillis();
            boardParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            boardParams.alpha = 0.78f;
            btnToggleMode.setText("穿透中");
            btnToggleMode.setBackground(makeEmeraldGradientBg(8, "#996EE7B7"));
            tvStatus.setText("请走棋");
        } else {
            boardParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            boardParams.alpha = 1.0f;
            btnToggleMode.setText("录入中");
            btnToggleMode.setBackground(makeDarkBtnBg("#D909090B", "#88FFFFFF", 8));
            if (!isCustomEditing) {
                tvStatus.setText("录入对手");
            }
        }
        wm.updateViewLayout(boardRoot, boardParams);
        webView.evaluateJavascript("setVisualPassThrough(" + passThrough + ")", null);
    }

    private void scheduleAutoReturnToInput(long delayMs, String statusHint) {
        cancelAutoReturn();
        tvStatus.setText(statusHint);
        autoSwitchRunnable = () -> {
            if (isPassThrough) {
                setBoardPassThrough(false);
            }
        };
        mainHandler.postDelayed(autoSwitchRunnable, delayMs);
    }

    private void cancelAutoReturn() {
        if (autoSwitchRunnable != null) {
            mainHandler.removeCallbacks(autoSwitchRunnable);
            autoSwitchRunnable = null;
        }
    }

    private class JsBridge {
        @JavascriptInterface
        public void onStateChanged(final String statusText, final String moveText, final boolean autoPassThrough) {
            mainHandler.post(() -> {
                tvMove.setText("走法: " + moveText);
                cancelAutoReturn();
                setBoardPassThrough(autoPassThrough);
                tvStatus.setText(statusText);
            });
        }

        @JavascriptInterface
        public void closeOverlay() {
            mainHandler.post(() -> stopSelf());
        }

        @JavascriptInterface
        public void showToast(final String message) {
            mainHandler.post(() -> Toast.makeText(OverlayService.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void onCustomEditClosed() {
            mainHandler.post(() -> exitCustomEditMode(false));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        cancelAutoReturn();
        if (activeSnapAnimator != null) activeSnapAnimator.cancel();
        if (isEditorDockAttached && editorDockRoot != null) {
            try {
                wm.removeView(editorDockRoot);
            } catch (Exception ignored) {}
            isEditorDockAttached = false;
        }
        if (controlShell != null) wm.removeView(controlShell);
        if (boardRoot != null) wm.removeView(boardRoot);
        sendBroadcast(new Intent(MainActivity.ACTION_PRESET_CHANGED).setPackage(getPackageName()));
    }
}
