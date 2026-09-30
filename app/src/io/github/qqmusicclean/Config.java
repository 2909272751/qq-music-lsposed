package io.github.qqmusicclean;

import android.content.SharedPreferences;

final class Config {
    static final String PACKAGE = "com.tencent.qqmusic";
    static final String[] FEATURES = {"cold", "hot", "tabs", "home", "promo", "recognizer", "benefits", "preload", "push_notify"};
    static final String[] FEATURE_LABELS = {"冷启动开屏", "热启动开屏", "底部标签", "首页仅推荐", "首页推广区", "听歌识曲入口", "福利入口", "预加载", "推送通知广告"};
    static final String REPORT_SCHEMA = "9";
    static final String GROUP = "qqmusic_clean_settings";

    /**
     * 已实测通过的 QQ 音乐 versionCode <b>区间</b>。
     *
     * <p>20.7.5.8(7308)、20.8.0.8(7358)、20.8.5.8(7408)、20.9.0.8(7458)
     * 四个点逐个真机装包 → 冷启动 → 读模块落盘状态文件，全部通过
     * （push_notify 4/4 入口、self_test 7/7）。区间内每两个实测点之间没有再插点测试，
     * 按二分法的前提——端点与中间点都通过，则其间版本同样适配——整段标记为可用。
     *
     * <p>落在区间内直接标记为可用：<b>不再弹「打开时的扫描适配测试」对话框</b>，
     * 规则照常安装，只是不再让用户每次开 QQ 音乐都过一遍检测界面。
     * 省掉的是弹窗和重复探测，不是功能。
     *
     * <p>区间外照旧弹窗 + DexKit 后台查找 + fail-open，不会被挡住。
     * 实测到新版本后，把区间上界抬到新的 versionCode 即可。
     */
    static final long VERIFIED_MIN = 7308L; // 20.7.5.8
    static final long VERIFIED_MAX = 7458L; // 20.9.0.8

    static boolean isVerified(long versionCode) {
        return versionCode >= VERIFIED_MIN && versionCode <= VERIFIED_MAX;
    }

    static final String BLOCK_SPLASH = "block_splash";
    static final String REDUCE_PRELOAD = "reduce_preload";
    static final String TAB_VIDEO = "tab_video";
    static final String TAB_KSONG = "tab_ksong";
    static final String TAB_STAR = "tab_star";
    static final String TAB_MY = "tab_my";
    static final String HOME_ONLY_RECOMMEND = "home_only_recommend";
    static final String HIDE_HOME_PROMO = "hide_home_promo";
    static final String SHOW_RECOGNIZER = "show_recognizer";
    static final String SHOW_BENEFITS = "show_benefits";
    static final String DIAGNOSTICS = "diagnostics";
    static final String BLOCK_PUSH_NOTIFY = "block_push_notify";

    private Config() {}

    static boolean read(SharedPreferences prefs, String key, boolean defaultValue) {
        try { return prefs.getBoolean(key, defaultValue); }
        catch (Throwable ignored) { return defaultValue; }
    }
}
