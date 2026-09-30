package io.github.qqmusicclean;

import android.app.Application;
import android.app.Activity;
import android.app.BroadcastOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/** Per-feature structural probes. No polling or hooks in the player process. */
public final class MainHook extends XposedModule {
    private static final String TAG = "QQMusicClean";
    private String processName;
    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean configured = new AtomicBoolean(false);
    private final AtomicBoolean splashLogged = new AtomicBoolean(false);
    private final AtomicBoolean hotSplashLogged = new AtomicBoolean(false);
    private final AtomicBoolean recognizerLogged = new AtomicBoolean(false);
    private final AtomicBoolean benefitsLogged = new AtomicBoolean(false);
    private final AtomicBoolean diagnosticsLogged = new AtomicBoolean(false);
    private Context reportContext;
    private String reportToken;
    private long reportRun;
    private final AtomicInteger reportDone = new AtomicInteger();
    private String probeFailure;
    private boolean probePartial;
    /** 全部入口都挂上时的说明文字（probeFailure 为空时用它当 detail）。 */
    private String probeDetail;
    private volatile boolean configComplete;
    private ScanOverlay scanOverlay;
    /** 宿主版本在 Config.VERIFIED_VERSIONS 里：省掉打开时的扫描弹窗，规则照常安装。 */
    private boolean verifiedHost;
    private final ConcurrentHashMap<String, String> featureStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> featureDetails = new ConcurrentHashMap<>();

    @Override public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!Config.PACKAGE.equals(param.getPackageName())) return;
        if (processName != null && !Config.PACKAGE.equals(processName)) return;
        if (!installed.compareAndSet(false, true)) return;
        try {
            final ClassLoader targetLoader = param.getClassLoader();
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).setId("qqmusic_validate_before_hooks").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (configured.compareAndSet(false, true)) {
                        try { configure((Application) chain.getThisObject(), (Context) chain.getArg(0), targetLoader); }
                        catch (Throwable error) { log(Log.ERROR, TAG, "module setup failed", error); }
                    }
                    return result;
                }
            });
            log(Log.INFO, TAG, "hooked: startup validation");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "module setup failed", error);
        }
    }

    private void configure(Application application, Context context, ClassLoader targetLoader) throws Exception {
            PackageInfo info = context.getPackageManager().getPackageInfo(Config.PACKAGE, 0);
            String version = info.versionName;
            reportContext = context;
            reportToken = info.versionCode + ":" + info.lastUpdateTime + ":" + Config.REPORT_SCHEMA;
            reportRun = System.currentTimeMillis();
            reportDone.set(0);
            configComplete = false;
            featureStates.clear();
            featureDetails.clear();
            // 已实测过的版本区间：规则照常装，但不再走「打开时的扫描适配测试」弹窗。
            // 省的是弹窗和重复探测，不是钩子。
            verifiedHost = Config.isVerified(info.getLongVersionCode());
            if (verifiedHost) {
                log(Log.INFO, TAG, "verified QQ Music version " + version + " ("
                        + info.versionCode + "), covered " + Config.VERIFIED_MIN + "~"
                        + Config.VERIFIED_MAX + ": skip on-open scan prompt");
            }
            scanOverlay = new ScanOverlay(application, context, reportToken);
            if (!verifiedHost && scanOverlay.needsPrompt()) installActivityObserver();
            report("running", "", "", "");
            log(Log.INFO, TAG, "checking compatible hooks for QQ Music " + version);
            SharedPreferences preferences = getRemotePreferences(Config.GROUP);
            boolean blockSplash = Config.read(preferences, Config.BLOCK_SPLASH, true);
            probe("cold", blockSplash, new Runnable() { @Override public void run() { installColdSplash(targetLoader); } });
            probe("hot", blockSplash, new Runnable() { @Override public void run() { installHotSplash(targetLoader); } });
            boolean reducePreload = Config.read(preferences, Config.REDUCE_PRELOAD, false);
            Set<String> hiddenTabs = new HashSet<>();
            if (!Config.read(preferences, Config.TAB_VIDEO, true)) hiddenTabs.add("视频");
            if (!Config.read(preferences, Config.TAB_KSONG, true)) hiddenTabs.add("刷歌");
            if (!Config.read(preferences, Config.TAB_STAR, true)) hiddenTabs.add("星光");
            if (!Config.read(preferences, Config.TAB_MY, true)) hiddenTabs.add("我的");
            probe("tabs", !hiddenTabs.isEmpty(), new Runnable() { @Override public void run() { installTabVisibility(targetLoader, hiddenTabs); } });
            probe("promo", Config.read(preferences, Config.HIDE_HOME_PROMO, true),
                    new Runnable() { @Override public void run() { installHomePromoFilter(targetLoader); } });
            probe("recognizer", !Config.read(preferences, Config.SHOW_RECOGNIZER, false),
                    new Runnable() { @Override public void run() { installRecognizerRemoval(targetLoader); } });
            probe("benefits", !Config.read(preferences, Config.SHOW_BENEFITS, false),
                    new Runnable() { @Override public void run() { installBenefitsRemoval(targetLoader); } });
            probe("preload", reducePreload, new Runnable() { @Override public void run() { installPreloadLimit(targetLoader); } });
            probe("push_notify", Config.read(preferences, Config.BLOCK_PUSH_NOTIFY, true),
                    new Runnable() { @Override public void run() { installPushNotify(); } });
            if (Config.read(preferences, Config.DIAGNOSTICS, false))
                installHeaderDiagnostics(targetLoader);
            configComplete = true;
            report("complete", "", "", "");
            if (!verifiedHost) showScanResult();
            log(Log.INFO, TAG, "quick compatibility probes complete for QQ Music " + version);
    }

    /**
     * 推送通知广告闸门：挂 {@code NotificationManager}。
     *
     * <p>{@code notify(...)} 是 QQ 音乐进程内所有通知的唯一出口——厂商推送、自建长连接、轮询
     * 拉回来的推广最终都要调它；而且它是平台类、不参与 R8 混淆，QQ 音乐改版改名的是它自己的类，
     * 这里不受影响。
     *
     * <p>{@code createNotificationChannel} 只观测不拦截：把渠道拦掉会让后续 notify 抛异常，更糟。
     * 播放与下载通知必须活着，所以判据在 {@link NotifyGate} 里刻意避开歌名/歌手/播放侧用词。
     */
    private void installPushNotify() {
        int expect = 4;
        int got = 0;
        Class<?>[] plain = {int.class, android.app.Notification.class};
        Class<?>[] tagged = {String.class, int.class, android.app.Notification.class};
        for (Class<?>[] signature : new Class<?>[][]{plain, tagged}) {
            final boolean withTag = signature == tagged;
            try {
                Method target = android.app.NotificationManager.class.getDeclaredMethod("notify", signature);
                hook(target).setId(withTag ? "qqmusic_push_notify_tagged" : "qqmusic_push_notify_plain")
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                // 判据全在 NotifyGate 里，任何异常它自己 fail-open 放行。
                                NotifyGate.Decision decision = NotifyGate.evaluate(
                                        (android.app.Notification) chain.getArg(withTag ? 2 : 1),
                                        withTag ? (String) chain.getArg(0) : null);
                                if (decision.suppress) {
                                    // 不调 proceed() = 通知根本不下发；播放/下载通知完全不受影响。
                                    log(Log.INFO, TAG, "push_notify suppressed by: " + decision.reason);
                                    report("running", "push_notify", "matched", NotifyGate.stats());
                                    return null;
                                }
                                return chain.proceed(); // 放行路径零日志、零分配
                            }
                        });
                log(Log.INFO, TAG, "hooked: push_notify notify(" + signature.length + " args)");
                got++;
            } catch (Throwable error) {
                log(Log.WARN, TAG, "push_notify notify hook unavailable", error);
            }
        }
        try {
            Method target = android.app.NotificationManager.class.getDeclaredMethod(
                    "createNotificationChannel", android.app.NotificationChannel.class);
            hook(target).setId("qqmusic_push_notify_channel").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        NotifyGate.Decision d = NotifyGate.evaluateChannel(
                                (android.app.NotificationChannel) chain.getArg(0));
                        if (d.suppress) log(Log.INFO, TAG, "push_notify ad channel: " + d.reason);
                    } catch (Throwable ignored) {}
                    return result;
                }
            });
            log(Log.INFO, TAG, "hooked: push_notify createNotificationChannel");
            got++;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "push_notify channel hook unavailable", error);
        }
        try {
            Method target = android.app.NotificationManager.class.getDeclaredMethod(
                    "createNotificationChannels", List.class);
            hook(target).setId("qqmusic_push_notify_channels").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object arg = chain.getArg(0);
                        if (arg instanceof List) {
                            for (Object channel : (List<?>) arg) {
                                NotifyGate.Decision d = NotifyGate.evaluateChannel(
                                        (android.app.NotificationChannel) channel);
                                if (d.suppress) log(Log.INFO, TAG, "push_notify ad channel: " + d.reason);
                            }
                        }
                    } catch (Throwable ignored) {}
                    return result;
                }
            });
            log(Log.INFO, TAG, "hooked: push_notify createNotificationChannels");
            got++;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "push_notify channels hook unavailable", error);
        }
        if (got < expect) {
            // 只挂上部分入口必须单独报出，不许算成"全部生效"。
            probePartial = true;
            probeFailure = "只挂上 " + got + "/" + expect + " 个通知入口，已挂上的判定仍有效";
        } else {
            // 没有真机广告通知时，用固定样本证明"判定函数本身"是对的（含播放侧负样本）。
            String selfTest = NotifyGate.selfTest();
            log(Log.INFO, TAG, selfTest);
            probeDetail = "通知下发与渠道创建入口已挂接（" + got + "/" + expect + "）；" + selfTest;
        }
    }

    private void probe(String feature, boolean enabled, Runnable action) {
        probeFailure = null;
        probePartial = false;
        probeDetail = null;
        if (enabled) {
            try { action.run(); }
            catch (Throwable error) { probeFailure = error.toString(); log(Log.WARN, TAG, feature + " probe failed", error); }
        }
        reportDone.incrementAndGet();
        String state = !enabled ? "off" : probePartial ? "partial" : probeFailure == null ? "matched" : "miss";
        String detail = probeFailure != null ? probeFailure : probeDetail == null ? "" : probeDetail;
        report("running", feature, state, detail);
        log(Log.INFO, TAG, "feature=" + feature + " result=" + state + (detail.isEmpty() ? "" : " reason=" + detail));
    }

    private void report(String phase, String feature, String state, String detail) {
        if (!feature.isEmpty()) {
            featureStates.put(feature, state);
            featureDetails.put(feature, detail);
        }
        if (scanOverlay != null && "running".equals(phase)) {
            String stage = detail.isEmpty() ? "检测 " + feature : detail;
            scanOverlay.progress(reportDone.get(), Config.FEATURES.length, stage);
        }
        try {
            Bundle extras = new Bundle();
            extras.putString("token", reportToken);
            extras.putLong("run", reportRun);
            extras.putString("phase", phase);
            extras.putInt("done", reportDone.get());
            extras.putInt("total", Config.FEATURES.length);
            extras.putString("feature", feature);
            extras.putString("state", state);
            extras.putString("detail", detail.length() > 180 ? detail.substring(0, 180) : detail);
            Intent intent = new Intent("io.github.qqmusicclean.REPORT");
            intent.setComponent(new ComponentName("io.github.qqmusicclean", "io.github.qqmusicclean.StatusReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtras(extras);
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                reportContext.sendBroadcast(intent, null, options);
            } else {
                reportContext.sendBroadcast(intent);
            }
        } catch (Throwable error) { log(Log.WARN, TAG, "status report unavailable", error); }
    }

    private void showScanResult() {
        if (scanOverlay == null) return;
        int matched = 0;
        int disabled = 0;
        int ready = 0;
        StringBuilder results = new StringBuilder();
        boolean hasFailures = false;
        for (int i = 0; i < Config.FEATURES.length; i++) {
            String key = Config.FEATURES[i];
            String state = featureStates.get(key);
            if (results.length() > 0) results.append('\n');
            results.append("• ").append(Config.FEATURE_LABELS[i]).append("：");
            if ("matched".equals(state)) matched++;
            else if ("off".equals(state)) disabled++;
            else if ("ready".equals(state)) ready++;
            else hasFailures = true;
            results.append("matched".equals(state) ? "已匹配"
                    : "off".equals(state) ? "已关闭"
                    : "ready".equals(state) ? "已找到，重启后生效"
                    : "partial".equals(state) || "partial_ready".equals(state) ? "部分匹配"
                    : "未匹配");
            if (!"matched".equals(state) && !"off".equals(state) && !"ready".equals(state)) {
                String detail = featureDetails.get(key);
                if (detail != null && !detail.isEmpty()) results.append("（").append(detail).append("）");
            }
        }
        String title = hasFailures ? "模块适配：有未匹配项" : "模块适配完成";
        String message = "检测 " + Config.FEATURES.length + "/" + Config.FEATURES.length
                + " 项；启用规则已匹配 " + matched + "/" + (Config.FEATURES.length - disabled)
                + " 项，待重启 " + ready + " 项，主动关闭 " + disabled + " 项。\n\n"
                + results + (ready > 0 ? "\n\n重启 QQ 音乐后应用新找到的规则。" : "");
        scanOverlay.finish(title, message);
    }

    private void installActivityObserver() {
        try {
            Method resume = Instrumentation.class.getDeclaredMethod("callActivityOnResume", Activity.class);
            hook(resume).setId("qqmusic_scan_dialog_resume").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (scanOverlay != null) scanOverlay.activityResumed((Activity) chain.getArg(0));
                    return result;
                }
            });
            Method pause = Instrumentation.class.getDeclaredMethod("callActivityOnPause", Activity.class);
            hook(pause).setId("qqmusic_scan_dialog_pause").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (scanOverlay != null) scanOverlay.activityPaused((Activity) chain.getArg(0));
                    return chain.proceed();
                }
            });
            log(Log.INFO, TAG, "hooked: scan result dialog lifecycle");
        } catch (Throwable error) {
            log(Log.WARN, TAG, "scan result dialog lifecycle unavailable", error);
        }
    }

    private void installColdSplash(ClassLoader loader) {
        try {
            Class<?> task = loader.loadClass("com.tencent.qqmusic.boot.task.activitytask.n0");
            Method decision = task.getDeclaredMethod("k");
            if (decision.getReturnType() != int.class || java.lang.reflect.Modifier.isStatic(decision.getModifiers()))
                throw new NoSuchMethodException("splash decision signature changed");
            hook(decision).setId("qqmusic_cold_splash_decision").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    // In tested QQ Music 20.6.5.8, k()==5 takes its normal no-ad startup path.
                    if (splashLogged.compareAndSet(false, true))
                        log(Log.INFO, TAG, "cold splash: app no-ad path selected");
                    return Integer.valueOf(5);
                }
            });
            log(Log.INFO, TAG, "hooked: cold splash decision");
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "cold splash hook unavailable; app left unchanged", error);
        }
    }

    private void installHotSplash(ClassLoader loader) {
        try {
            Class<?> manager = loader.loadClass("com.tencent.qqmusic.business.ad.splash.hotlaunch.g");
            Method launch = manager.getDeclaredMethod("F", Activity.class, boolean.class);
            Method precheck = manager.getDeclaredMethod("x", Activity.class);
            if (launch.getReturnType() != boolean.class || precheck.getReturnType() != boolean.class)
                throw new NoSuchMethodException("hot splash gates must return boolean");
            XposedInterface.Hooker skip = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    if (hotSplashLogged.compareAndSet(false, true))
                        log(Log.INFO, TAG, "hot splash: display gate blocked");
                    return Boolean.FALSE;
                }
            };
            hook(launch).setId("qqmusic_hot_splash_launch_gate").intercept(skip);
            hook(precheck).setId("qqmusic_hot_splash_precheck").intercept(skip);
            log(Log.INFO, TAG, "hooked: hot splash gates");
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "hot splash hook unavailable; app left unchanged", error);
        }
    }

    private void installPreloadLimit(ClassLoader loader) {
        try {
            Class<?> fragment = loader.loadClass("com.tencent.qqmusic.fragment.mainpage.MainDesktopFragment");
            Method showTabs = fragment.getDeclaredMethod("showTabs");
            Field pagerField = fragment.getDeclaredField("mPagerDetail");
            pagerField.setAccessible(true);
            hook(showTabs).setId("qqmusic_optional_preload_limit").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object pager = pagerField.get(chain.getThisObject());
                        if (pager != null)
                            pager.getClass().getMethod("setOffscreenPageLimit", int.class).invoke(pager, 1);
                    } catch (Throwable error) {
                        log(Log.WARN, TAG, "preload limit skipped", error);
                    }
                    return result;
                }
            });
            log(Log.INFO, TAG, "hooked: optional pager preload limit");
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "preload limit unavailable", error);
        }
    }

    private void installTabVisibility(ClassLoader loader, final Set<String> hiddenTabs) {
        try {
            Class<?> container = loader.loadClass("com.tencent.qqmusic.ui.minibar.navigation.MainDeskNavigateContainer");
            Method addItem = null;
            for (Method candidate : container.getDeclaredMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (candidate.getName().equals("m") && candidate.getReturnType() == void.class
                        && parameters.length == 2 && parameters[1] == int.class
                        && !parameters[0].isPrimitive()) {
                    if (addItem != null) throw new NoSuchMethodException("ambiguous tab insertion methods");
                    addItem = candidate;
                }
            }
            if (addItem == null) throw new NoSuchMethodException("tab insertion method missing");
            Class<?> itemClass = addItem.getParameterTypes()[0];
            Method name = itemClass.getDeclaredMethod("i");
            Method binding = itemClass.getDeclaredMethod("h");
            Method root = binding.getReturnType().getDeclaredMethod("m");
            if (name.getReturnType() != String.class || !View.class.isAssignableFrom(root.getReturnType()))
                throw new NoSuchMethodException("tab item structure changed");
            hook(addItem).setId("qqmusic_optional_tab_visibility").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object item = chain.getArg(0);
                        String label = String.valueOf(name.invoke(item));
                        if (hiddenTabs.contains(label)) {
                            View view = (View) root.invoke(binding.invoke(item));
                            view.setVisibility(View.GONE);
                            log(Log.INFO, TAG, "tab hidden: " + label);
                        }
                    } catch (Throwable error) {
                        log(Log.WARN, TAG, "tab visibility skipped", error);
                    }
                    return result;
                }
            });
            log(Log.INFO, TAG, "hooked: optional tab visibility");
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "tab visibility unavailable", error);
        }
    }

    private static final String BEANS = "com.tencent.qqmusic.modular.module.musichall.beans.";
    private static final String VIEWS = "com.tencent.qqmusic.modular.module.musichall.views.";
    /** 推广货架的标题文案：两个词都命中才算推广位，只命中一个可能是别的卡片。 */
    private static final String PROMO_WORD_A = "随时随地";
    private static final String PROMO_WORD_B = "停不下来";

    /**
     * 隐藏首页「随时随地，停不下来」推广货架。
     *
     * <p>这一段以前是写死混淆名的（{@code views.r#b0}、{@code p0#B()}），
     * 结果 20.7.5.8 和 20.9.0.8 上全 miss——QQ 音乐每个版本都在改这些名字：
     * 7458 上适配器已经是 {@code views.q#b0}，货架模型也从 {@code p0} 变成 {@code q0}。
     *
     * <p>现在改成按签名找，不认名字：cell→group 的取法看「单参是 cell、返回值还是 beans 包里的类」；
     * 数据入口看 views 包里唯一的 {@code (List)->void}；标题不再挑某个 getter，
     * 而是把 group 上所有无参 String getter 都试一遍，谁返回推广文案就用谁。
     * 找不到唯一候选就整项跳过并写明原因，绝不猜。
     */
    private void installHomePromoFilter(ClassLoader loader) {
        try {
            Class<?> cell = loader.loadClass(BEANS + "k");
            Class<?> helper = loader.loadClass(BEANS + "l");

            // 1) cell -> group：helper 里唯一的「单参 cell、返回值是 beans 里的类」的静态方法
            Method groupOf = null;
            int groupCandidates = 0;
            for (Method m : helper.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != cell) continue;
                Class<?> ret = m.getReturnType();
                if (ret.isPrimitive() || ret == void.class || !ret.getName().startsWith(BEANS)) continue;
                groupCandidates++;
                groupOf = m;
            }
            if (groupCandidates != 1 || groupOf == null)
                throw new NoSuchMethodException("cell->group accessor candidates=" + groupCandidates);

            // 2) 标题：group 上全部无参 String getter，运行时按文案认
            Class<?> group = groupOf.getReturnType();
            ArrayList<Method> titles = new ArrayList<>();
            for (Method m : group.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == String.class) titles.add(m);
            }
            if (titles.isEmpty()) throw new NoSuchMethodException("group exposes no String getter");

            // 3) 数据入口：views 包里唯一的 (List)->void
            Method setData = null;
            int dataCandidates = 0;
            String[] shortNames = new String[52];
            for (int i = 0; i < 26; i++) { shortNames[i] = String.valueOf((char) ('a' + i)); }
            for (int i = 0; i < 26; i++) { shortNames[26 + i] = (char) ('a' + i) + "0"; }
            for (String simple : shortNames) {
                Class<?> type;
                try { type = loader.loadClass(VIEWS + simple); } catch (Throwable ignored) { continue; }
                for (Method m : type.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 1 || p[0] != List.class || m.getReturnType() != void.class) continue;
                    dataCandidates++;
                    setData = m;
                }
            }
            if (dataCandidates != 1 || setData == null)
                throw new NoSuchMethodException("home list data-set candidates=" + dataCandidates);

            groupOf.setAccessible(true);
            for (Method t : titles) t.setAccessible(true);
            final Method groupAccessor = groupOf;
            final Method[] titleGetters = titles.toArray(new Method[0]);

            XposedInterface.Hooker filter = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object[] args = new Object[chain.getExecutable().getParameterCount()];
                    for (int i = 0; i < args.length; i++) args[i] = chain.getArg(i);
                    if (!(args[0] instanceof List)) return chain.proceed();
                    List<?> batches = (List<?>) args[0];
                    ArrayList<List<?>> filtered = new ArrayList<>(batches.size());
                    int hidden = 0;
                    for (Object batch : batches) {
                        if (!(batch instanceof List)) return chain.proceed();
                        List<?> rows = (List<?>) batch;
                        ArrayList<Object> kept = new ArrayList<>(rows.size());
                        for (Object row : rows) {
                            boolean promo = false;
                            try {
                                Object g = groupAccessor.invoke(null, row);
                                if (g != null) {
                                    for (Method title : titleGetters) {
                                        Object label = title.invoke(g);
                                        if (label instanceof String && ((String) label).contains(PROMO_WORD_A)
                                                && ((String) label).contains(PROMO_WORD_B)) { promo = true; break; }
                                    }
                                }
                            } catch (Throwable error) {
                                // 反射失败不删数据：宁可漏掉一个推广位，也不能把正常货架吃掉
                                kept.add(row);
                                continue;
                            }
                            if (promo) hidden++;
                            else kept.add(row);
                        }
                        filtered.add(kept);
                    }
                    if (hidden > 0) {
                        log(Log.INFO, TAG, "home promo shelf items removed=" + hidden);
                        args[0] = filtered;
                        return chain.proceed(args);
                    }
                    return chain.proceed();
                }
            };
            hook(setData).setId("qqmusic_hide_home_promo_set_data").intercept(filter);
            log(Log.INFO, TAG, "hooked: home promotional shelf data filter on "
                    + setData.getDeclaringClass().getName() + "#" + setData.getName()
                    + " group=" + group.getName() + " titles=" + titleGetters.length);
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "home promotional shelf filter unavailable", error);
        }
    }

    private static String simple(Class<?> c) {
        return c == void.class ? "void" : c.getSimpleName();
    }

    private void installRecognizerRemoval(ClassLoader loader) {
        boolean installed = false;
        for (String variant : new String[]{"com.tencent.qqmusic.ui.desktopheader.g1",
                "com.tencent.qqmusic.ui.BaseDesktopHeader"}) {
            try {
                Class<?> header = loader.loadClass(variant);
                Method showRecognizer = header.getDeclaredMethod("l0", boolean.class);
                if (showRecognizer.getReturnType() != void.class)
                    throw new NoSuchMethodException("recognizer visibility signature changed");
                hook(showRecognizer).setId("qqmusic_hide_recognizer_" + header.getSimpleName())
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (Boolean.TRUE.equals(chain.getArg(0))
                                        && recognizerLogged.compareAndSet(false, true))
                                    log(Log.INFO, TAG, "recognizer entrance: native show request suppressed via " + variant);
                                return chain.proceed(new Object[]{Boolean.FALSE});
                            }
                        });
                installed = true;
                log(Log.INFO, TAG, "hooked: recognizer entrance variant " + variant);
            } catch (Throwable error) {
                log(Log.INFO, TAG, "recognizer variant skipped: " + variant + " (" + error + ")");
            }
        }
        if (!installed) {
            probeFailure = "未找到可兼容的听歌识曲入口";
            log(Log.WARN, TAG, "recognizer entrance: no compatible variant found");
        }
    }

    private void installBenefitsRemoval(ClassLoader loader) {
        boolean installed = false;
        for (String variant : new String[]{"com.tencent.qqmusic.ui.desktopheader.g1",
                "com.tencent.qqmusic.ui.BaseDesktopHeader"}) {
            try {
                Class<?> header = loader.loadClass(variant);
                Method showBenefits = header.getDeclaredMethod("J", int.class, boolean.class);
                if (showBenefits.getReturnType() != void.class)
                    throw new NoSuchMethodException("benefits visibility signature changed");
                hook(showBenefits).setId("qqmusic_hide_benefits_" + header.getSimpleName())
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) {
                                if (benefitsLogged.compareAndSet(false, true))
                                    log(Log.INFO, TAG, "benefits entrance: native inflation skipped via " + variant);
                                return null;
                            }
                        });
                installed = true;
                log(Log.INFO, TAG, "hooked: benefits entrance variant " + variant);
            } catch (Throwable error) {
                log(Log.INFO, TAG, "benefits variant skipped: " + variant + " (" + error + ")");
            }
        }
        if (!installed) {
            probeFailure = "未找到可兼容的福利入口";
            log(Log.WARN, TAG, "benefits entrance: no compatible variant found");
        }
    }

    private void installHeaderDiagnostics(ClassLoader loader) {
        try {
            Method resume = Activity.class.getDeclaredMethod("onResume");
            hook(resume).setId("qqmusic_optional_header_diagnostics").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Activity activity = (Activity) chain.getThisObject();
                    if (activity.getClass().getName().equals("com.tencent.qqmusic.activity.AppStarterActivity")
                            && diagnosticsLogged.compareAndSet(false, true)) {
                        try {
                            View root = activity.getWindow().getDecorView();
                            root.postDelayed(new Runnable() {
                                @Override public void run() {
                                    try { dumpHeader(root, 0, new int[]{0}); }
                                    catch (Throwable error) { log(Log.WARN, TAG, "header diagnostics failed", error); }
                                }
                            }, 3500);
                        } catch (Throwable error) {
                            log(Log.WARN, TAG, "header diagnostics unavailable", error);
                        }
                    }
                    return result;
                }
            });
            log(Log.INFO, TAG, "hooked: optional header diagnostics");
        } catch (Throwable error) {
            log(Log.WARN, TAG, "header diagnostics unavailable", error);
        }
    }

    private void dumpHeader(View view, int depth, int[] count) {
        if (depth > 22 || count[0] > 160) return;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        if (location[1] < 450 && location[0] > view.getResources().getDisplayMetrics().widthPixels / 2
                && view.getWidth() > 0 && view.getHeight() > 0) {
            String name = "none";
            try { name = view.getResources().getResourceEntryName(view.getId()); }
            catch (Throwable ignored) { }
            CharSequence description = view.getContentDescription();
            log(Log.INFO, TAG, "header view depth=" + depth + " id=" + name + " class="
                    + view.getClass().getSimpleName() + " xy=" + location[0] + "," + location[1]
                    + " size=" + view.getWidth() + "x" + view.getHeight() + " vis="
                    + view.getVisibility() + " desc=" + description);
            count[0]++;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                dumpHeader(group.getChildAt(i), depth + 1, count);
        }
    }
}
