package com.chess.syncoverlay;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 1001;
    public static final String ACTION_PRESET_CHANGED = "com.chess.syncoverlay.ACTION_PRESET_CHANGED";

    private LinearLayout presetListContainer;
    private TextView btnToggleOverlay;
    private boolean pendingSemiAutoAfterPerm = false;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUi();
        }
    };

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable makeBox(String fillHex, String strokeHex, float radiusDp, float strokeWidthDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fillHex));
        gd.setCornerRadius(dp(radiusDp));
        if (strokeHex != null && strokeWidthDp > 0) {
            gd.setStroke(Math.max(1, dp(strokeWidthDp)), Color.parseColor(strokeHex));
        }
        return gd;
    }

    private void attachSpringPress(View v) {
        v.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(190)
                        .setInterpolator(new OvershootInterpolator(1.6f)).start();
            }
            return false;
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("国际象棋同步推演");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnNewPreset = new TextView(this);
        btnNewPreset.setText("新建存档");
        btnNewPreset.setTextColor(Color.WHITE);
        btnNewPreset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnNewPreset.setPadding(dp(14), dp(8), dp(14), dp(8));
        btnNewPreset.setBackground(makeBox("#0A0A0C", "#FFFFFF", 10, 1));
        attachSpringPress(btnNewPreset);
        btnNewPreset.setOnClickListener(v -> showCreatePresetDialog());
        header.addView(btnNewPreset);

        root.addView(header);

        btnToggleOverlay = new TextView(this);
        btnToggleOverlay.setGravity(Gravity.CENTER);
        btnToggleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnToggleOverlay.setTypeface(Typeface.DEFAULT_BOLD);
        btnToggleOverlay.setPadding(dp(16), dp(15), dp(16), dp(15));
        attachSpringPress(btnToggleOverlay);
        btnToggleOverlay.setOnClickListener(v -> toggleOverlayService(false));
        btnToggleOverlay.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            launchSemiAutoMode();
            return true;
        });

        LinearLayout.LayoutParams lpLaunch = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpLaunch.topMargin = dp(20);
        lpLaunch.bottomMargin = dp(22);
        root.addView(btnToggleOverlay, lpLaunch);

        TextView sectionLabel = new TextView(this);
        sectionLabel.setText("配置存档");
        sectionLabel.setTextColor(Color.parseColor("#A1A1AA"));
        sectionLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lpSec = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpSec.bottomMargin = dp(10);
        root.addView(sectionLabel, lpSec);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        presetListContainer = new LinearLayout(this);
        presetListContainer.setOrientation(LinearLayout.VERTICAL);
        LayoutTransition lt = new LayoutTransition();
        lt.setDuration(220);
        presetListContainer.setLayoutTransition(lt);
        scroll.addView(presetListContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        IntentFilter filter = new IntentFilter(ACTION_PRESET_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) {}
    }

    private void refreshUi() {
        boolean running = OverlayService.isRunning;
        if (running) {
            btnToggleOverlay.setText(OverlayService.isSemiAutoMode ? "关闭悬浮棋盘（半自动）" : "关闭悬浮棋盘");
            btnToggleOverlay.setTextColor(Color.WHITE);
            btnToggleOverlay.setBackground(makeBox("#18181B", "#FFFFFF", 14, 1.5f));
        } else {
            btnToggleOverlay.setText("启动悬浮棋盘");
            btnToggleOverlay.setTextColor(Color.BLACK);
            btnToggleOverlay.setBackground(makeBox("#FFFFFF", "#FFFFFF", 14, 1.5f));
        }

        presetListContainer.removeAllViews();
        List<PresetStore.Preset> presets = PresetStore.getAll(this);
        String activeId = PresetStore.getActiveId(this);

        for (PresetStore.Preset p : presets) {
            boolean isCurrent = p.id.equals(activeId);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackground(makeBox("#09090B", isCurrent ? "#FFFFFF" : "#3F3F46", 14, isCurrent ? 1.5f : 1f));

            LinearLayout.LayoutParams lpCard = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpCard.bottomMargin = dp(12);

            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(this);
            tvName.setText(p.name);
            tvName.setTextColor(Color.WHITE);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            topRow.addView(tvName, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (isCurrent) {
                TextView tag = new TextView(this);
                tag.setText("当前启用");
                tag.setTextColor(Color.WHITE);
                tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                tag.setPadding(dp(8), dp(3), dp(8), dp(3));
                tag.setBackground(makeBox("#18181B", "#FFFFFF", 6, 1));
                topRow.addView(tag);
            }
            card.addView(topRow);

            TextView tvInfo = new TextView(this);
            tvInfo.setText("尺寸 " + p.size + "px   坐标 (" + p.x + ", " + p.y + ")   背景 " + p.boardAlpha + "%   棋子 " + p.pieceAlpha + "%");
            tvInfo.setTextColor(Color.parseColor("#A1A1AA"));
            tvInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams lpInfo = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpInfo.topMargin = dp(6);
            lpInfo.bottomMargin = dp(12);
            card.addView(tvInfo, lpInfo);

            LinearLayout actRow = new LinearLayout(this);
            actRow.setOrientation(LinearLayout.HORIZONTAL);

            TextView btnUse = makeActionBtn(isCurrent ? "已启用" : "启用");
            btnUse.setOnClickListener(v -> {
                PresetStore.setActiveId(this, p.id);
                if (OverlayService.isRunning) {
                    Intent it = new Intent(this, OverlayService.class);
                    it.setAction(OverlayService.ACTION_APPLY_PRESET);
                    startService(it);
                }
                refreshUi();
            });

            TextView btnRename = makeActionBtn("命名");
            btnRename.setOnClickListener(v -> showRenameDialog(p));

            TextView btnDel = makeActionBtn("删除");
            btnDel.setOnClickListener(v -> {
                if (presets.size() <= 1) {
                    Toast.makeText(this, "至少保留一个存档", Toast.LENGTH_SHORT).show();
                    return;
                }
                PresetStore.delete(this, p.id);
                refreshUi();
            });

            LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpBtn.rightMargin = dp(8);
            actRow.addView(btnUse, lpBtn);
            actRow.addView(btnRename, lpBtn);
            if (presets.size() > 1) {
                actRow.addView(btnDel);
            }

            card.addView(actRow);
            presetListContainer.addView(card, lpCard);
        }
    }

    private TextView makeActionBtn(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(14), dp(6), dp(14), dp(6));
        tv.setBackground(makeBox("#121215", "#71717A", 8, 1));
        attachSpringPress(tv);
        return tv;
    }

    private void showRenameDialog(PresetStore.Preset preset) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(10));
        box.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("修改存档名称");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        EditText et = new EditText(this);
        et.setText(preset.name);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(Color.GRAY);
        et.setSingleLine(true);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setBackground(makeBox("#121215", "#FFFFFF", 8, 1));
        LinearLayout.LayoutParams lpEt = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpEt.topMargin = dp(12);
        box.addView(et, lpEt);

        builder.setView(box);
        builder.setPositiveButton("保存", (d, w) -> {
            String n = et.getText().toString().trim();
            if (!n.isEmpty()) {
                preset.name = n;
                PresetStore.saveOrUpdate(this, preset);
                refreshUi();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showCreatePresetDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(10));
        box.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("新建配置存档");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        EditText et = new EditText(this);
        et.setHint("输入存档名称");
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(Color.GRAY);
        et.setSingleLine(true);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setBackground(makeBox("#121215", "#FFFFFF", 8, 1));
        LinearLayout.LayoutParams lpEt = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpEt.topMargin = dp(12);
        box.addView(et, lpEt);

        builder.setView(box);
        builder.setPositiveButton("创建", (d, w) -> {
            String n = et.getText().toString().trim();
            if (n.isEmpty()) n = "新存档";
            PresetStore.Preset cur = PresetStore.getActive(this);
            PresetStore.Preset created = new PresetStore.Preset(
                    UUID.randomUUID().toString(),
                    n,
                    cur.x,
                    cur.y,
                    cur.size,
                    cur.boardAlpha,
                    cur.pieceAlpha
            );
            PresetStore.saveOrUpdate(this, created);
            refreshUi();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void toggleOverlayService(boolean semiAuto) {
        if (OverlayService.isRunning && !semiAuto) {
            stopService(new Intent(this, OverlayService.class));
            btnToggleOverlay.postDelayed(this::refreshUi, 120);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            pendingSemiAutoAfterPerm = semiAuto;
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        Intent it = new Intent(this, OverlayService.class);
        it.setAction(semiAuto ? OverlayService.ACTION_START_SEMI_AUTO : OverlayService.ACTION_START_NORMAL);
        startService(it);
        btnToggleOverlay.postDelayed(this::refreshUi, 120);
    }

    private void launchSemiAutoMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            pendingSemiAutoAfterPerm = true;
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        if (!ChessTapAccessibilityService.isEnabledInSettings(this)) {
            Toast.makeText(this, "请开启象棋同步推演自动落子无障碍服务", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
            return;
        }
        toggleOverlayService(true);
        Toast.makeText(this, "已进入半自动模式", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                boolean wantSemi = pendingSemiAutoAfterPerm;
                pendingSemiAutoAfterPerm = false;
                if (wantSemi) {
                    launchSemiAutoMode();
                } else {
                    toggleOverlayService(false);
                }
                refreshUi();
            }
        }
    }
}
