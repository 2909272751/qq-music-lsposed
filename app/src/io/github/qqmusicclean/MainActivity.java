package io.github.qqmusicclean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** A small, local settings page inspired by the user's grouped-switch reference. */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(245, 247, 250);
    private static final int INK = Color.rgb(30, 43, 48);
    private static final int MUTED = Color.rgb(101, 116, 124);
    private static final int ACCENT = Color.rgb(0, 143, 114);
    private static final int SECTION = Color.rgb(26, 111, 160);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final Map<String, Boolean> defaults = new LinkedHashMap<>();
    private TextView status;
    private TextView compatibility;
    private ProgressBar scanProgress;
    private AlertDialog restartDialog;
    private boolean active;
    private boolean refreshing;
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(body);
        setContentView(scroll);

        TextView heading = text("QQ 音乐精简", 25, INK, true);
        body.addView(heading);
        TextView subtitle = text("仅作用于 QQ 音乐 · 各项功能独立选择", 13, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        body.addView(subtitle);

        LinearLayout statusCard = card();
        status = text("正在连接 LSPosed…", 14, INK, false);
        statusCard.addView(status);
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(Config.FEATURES.length);
        scanProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5));
        progressParams.topMargin = dp(8);
        statusCard.addView(scanProgress, progressParams);
        compatibility = text("", 12, MUTED, false);
        compatibility.setPadding(0, dp(8), 0, 0);
        statusCard.addView(compatibility);
        body.addView(statusCard);

        section(body, "开屏广告");
        LinearLayout splashCard = card();
        toggle(splashCard, Config.BLOCK_SPLASH, "拦截开屏广告", "走 QQ 音乐自身的无广告启动流程", true);
        body.addView(splashCard);

        section(body, "推送通知");
        LinearLayout notifyCard = card();
        addNote(notifyCard, "对应页面：通知栏。只拦广告通知，播放中、下载完成的通知照常显示。", 12);
        toggle(notifyCard, Config.BLOCK_PUSH_NOTIFY, "拦截推送通知广告",
                "拦通知下发这个唯一出口：先判通知渠道，再判标题正文里的广告词", true);
        body.addView(notifyCard);

        section(body, "底部标签页");
        LinearLayout tabsCard = card();
        addNote(tabsCard, "首页固定保留。关闭其他标签会隐藏入口，重新打开 QQ 音乐后生效。", 12);
        toggle(tabsCard, Config.TAB_VIDEO, "显示「视频」", null, true);
        toggle(tabsCard, Config.TAB_KSONG, "显示「刷歌」", null, true);
        toggle(tabsCard, Config.TAB_STAR, "显示「星光」", null, true);
        toggle(tabsCard, Config.TAB_MY, "显示「我的」", null, true);
        body.addView(tabsCard);

        section(body, "首页频道");
        LinearLayout homeCard = card();
        toggle(homeCard, Config.HOME_ONLY_RECOMMEND, "只保留「推荐」", "隐藏首页其他频道；重新打开 QQ 音乐后生效", false);
        toggle(homeCard, Config.HIDE_HOME_PROMO, "隐藏首页推广区", "尝试移除「随时随地，停不下来」区块", true);
        body.addView(homeCard);

        section(body, "首页右上角");
        LinearLayout headerCard = card();
        toggle(headerCard, Config.SHOW_RECOGNIZER, "显示「听歌识曲」", null, false);
        toggle(headerCard, Config.SHOW_BENEFITS, "显示「福利」", null, false);
        body.addView(headerCard);

        section(body, "性能选项");
        LinearLayout perfCard = card();
        toggle(perfCard, Config.REDUCE_PRELOAD, "减少相邻页面预加载", "试验功能；如切换标签异常，请关闭", false);
        body.addView(perfCard);

        section(body, "诊断");
        LinearLayout diagnosticCard = card();
        toggle(diagnosticCard, Config.DIAGNOSTICS, "记录顶部控件诊断", "仅在排查入口失效时开启；记录在 LSPosed 模块日志", false);
        body.addView(diagnosticCard);

        section(body, "恢复与说明");
        LinearLayout footer = card();
        Button reset = new Button(this);
        reset.setAllCaps(false);
        reset.setText("恢复默认设置");
        reset.setTextColor(ACCENT);
        reset.setBackgroundColor(Color.TRANSPARENT);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (App.reset()) {
                    refresh();
                    Toast.makeText(MainActivity.this, "设置已恢复；请重启 QQ 音乐", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "LSPosed 服务尚未连接", Toast.LENGTH_SHORT).show();
                }
            }
        });
        footer.addView(reset);
        addNote(footer, "打开 QQ 音乐后会逐项检测并记录结果。更改开关后，请强停 QQ 音乐再打开以更新状态。详细错误同时记入 LSPosed 日志。", 12);
        body.addView(footer);

        handleRestartRequest(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleRestartRequest(intent);
    }

    private void handleRestartRequest(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("restart_target", false) || restartDialog != null) return;
        intent.removeExtra("restart_target");
            restartDialog = new AlertDialog.Builder(this)
                    .setTitle("正在重启 QQ 音乐")
                    .setMessage("请稍候；首次使用可能需要授予本模块 Root 权限。")
                    .setCancelable(false).create();
            restartDialog.show();
            restartTarget();
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        refreshTask.run();
    }

    @Override protected void onPause() {
        active = false;
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void refresh() {
        SharedPreferences preferences = App.preferences();
        String version = targetVersion();
        if (preferences == null) {
            status.setText("● LSPosed 配置服务未连接\nQQ 音乐版本：" + version + " · 开关暂不可用");
        } else {
            status.setText("● LSPosed 配置服务已连接\nQQ 音乐版本：" + version);
        }
        status.setTextColor(preferences == null ? MUTED : ACCENT);
        refreshCompatibility();
        refreshing = true;
        try {
            for (Map.Entry<String, Switch> entry : switches.entrySet()) {
                Switch toggle = entry.getValue();
                toggle.setEnabled(preferences != null);
                boolean wanted = preferences == null ? defaults.get(entry.getKey())
                        : Config.read(preferences, entry.getKey(), defaults.get(entry.getKey()));
                if (toggle.isChecked() != wanted) toggle.setChecked(wanted);
            }
        } finally { refreshing = false; }
    }

    private void refreshCompatibility() {
        Bundle report = null;
        try { report = getContentResolver().call(StatusProvider.URI, "get", null, null); }
        catch (Throwable ignored) { }
        String token = targetToken();
        if (report == null || token.isEmpty() || !token.equals(report.getString("token", ""))) {
            scanProgress.setVisibility(View.GONE);
            compatibility.setText("尚未检测当前版本。请打开 QQ 音乐，随后返回查看结果。");
            return;
        }
        String phase = report.getString("phase", "");
        int done = report.getInt("done", 0);
        int total = report.getInt("total", Config.FEATURES.length);
        boolean running = "running".equals(phase)
                && System.currentTimeMillis() - report.getLong("time", 0) < 20000;
        scanProgress.setMax(Math.max(1, total));
        scanProgress.setProgress(done);
        scanProgress.setVisibility(running ? View.VISIBLE : View.GONE);
        StringBuilder text = new StringBuilder();
        if (running) text.append("检测中：").append(done).append('/').append(total);
        else if ("complete".equals(phase)) text.append("已检测：").append(done).append('/').append(total)
                .append("（检测数不代表全部匹配）");
        else text.append("上次检测中断，请重启 QQ 音乐重试");
        if ("complete".equals(phase)) {
            int matched = 0, ready = 0, off = 0, issues = 0;
            for (String key : Config.FEATURES) {
                String state = report.getString(key, "");
                if ("matched".equals(state)) matched++;
                else if ("ready".equals(state)) ready++;
                else if ("off".equals(state)) off++;
                else issues++;
            }
            text.append("\n已匹配 ").append(matched).append(" 项；待重启 ").append(ready)
                    .append(" 项；已关闭 ").append(off).append(" 项；未匹配/部分匹配 ").append(issues).append(" 项");
        }
        String[] labels = Config.FEATURE_LABELS;
        for (int i = 0; i < Config.FEATURES.length; i++) {
            String key = Config.FEATURES[i];
            String state = report.getString(key, "");
            String name = "matched".equals(state) ? "已匹配"
                    : "partial".equals(state) ? "部分匹配"
                    : "ready".equals(state) ? "已找到，下次启动生效"
                    : "partial_ready".equals(state) ? "部分找到，下次启动生效"
                    : "scanning".equals(state) ? "正在读取"
                    : "miss".equals(state) ? "未匹配"
                    : "off".equals(state) ? "已关闭" : "待检测";
            text.append('\n').append(labels[i]).append("：").append(name);
            String detail = report.getString(key + "_detail", "");
            if (!detail.isEmpty()) text.append("\n  ").append(detail);
        }
        compatibility.setText(text.toString());
    }

    private void restartTarget() {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean stopped = false;
                try {
                    Process process = new ProcessBuilder("su", "-c", "am force-stop com.tencent.qqmusic").start();
                    if (process.waitFor(10, TimeUnit.SECONDS)) stopped = process.exitValue() == 0;
                    else process.destroy();
                } catch (Throwable ignored) { }
                final boolean success = stopped;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (restartDialog != null) { restartDialog.dismiss(); restartDialog = null; }
                        if (!success) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("未能重启 QQ 音乐")
                                    .setMessage("请授予模块 Root 权限，或手动强停并重新打开 QQ 音乐。")
                                    .setPositiveButton("知道了", null).show();
                            return;
                        }
                        Intent launch = getPackageManager().getLaunchIntentForPackage(Config.PACKAGE);
                        if (launch != null) { startActivity(launch); finish(); }
                        else Toast.makeText(MainActivity.this, "QQ 音乐未安装或没有启动入口", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "qqmc-restart").start();
    }

    private String targetToken() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(Config.PACKAGE, 0);
            return info.versionCode + ":" + info.lastUpdateTime + ":" + Config.REPORT_SCHEMA;
        } catch (Throwable ignored) { return ""; }
    }

    private String targetVersion() {
        try { return getPackageManager().getPackageInfo(Config.PACKAGE, 0).versionName; }
        catch (Throwable ignored) { return "未安装"; }
    }

    private void toggle(LinearLayout parent, String key, String title, String detail, boolean initial) {
        if (parent.getChildCount() > 0) separator(parent);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(detail == null ? 48 : 62));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(title, 15, INK, false);
        labels.addView(label);
        if (detail != null) {
            TextView hint = text(detail, 12, MUTED, false);
            hint.setPadding(0, dp(3), dp(8), 0);
            labels.addView(hint);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(this);
        control.setContentDescription(title);
        control.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, Color.rgb(222, 226, 229)}));
        control.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.rgb(168, 224, 205), Color.rgb(190, 196, 199)}));
        control.setChecked(initial);
        control.setEnabled(false);
        control.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (refreshing) return;
                if (App.preferences() == null) return;
                if (!App.write(key, checked)) {
                    button.setOnCheckedChangeListener(null);
                    button.setChecked(!checked);
                    button.setOnCheckedChangeListener(this);
                    Toast.makeText(MainActivity.this, "保存失败，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.addView(control);
        parent.addView(row);
        switches.put(key, control);
        defaults.put(key, initial);
    }

    private void section(LinearLayout body, String title) {
        TextView label = text(title, 14, SECTION, true);
        label.setPadding(dp(4), dp(20), 0, dp(8));
        body.addView(label);
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(9), dp(13), dp(9));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(13));
        box.setBackground(shape);
        return box;
    }

    private void addNote(LinearLayout box, String message, int size) {
        TextView note = text(message, size, MUTED, false);
        note.setPadding(0, dp(4), 0, dp(5));
        box.addView(note);
    }

    private void separator(LinearLayout box) {
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(239, 242, 244));
        box.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private TextView text(String content, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
