package com.xdu.tavern;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 自研原生壳：应用内 WebView 展示 SillyTavern，Node 服务由 JNI 在同一进程启动。
 * 数据全部落在 getNoBackupFilesDir()（应用私有、不备份、卸载即被系统删除）。
 * 不申请任何外部存储权限，不使用系统下载管理器，不跳转外部浏览器。
 */
public class MainActivity extends Activity {

    private static final String TAG = "Tavern";
    private static final String BASE = "http://127.0.0.1:8000/";
    private static final int REQ_FILE = 1001;
    /** 最多等待 180 秒；期间启动屏会实时显示 Node 日志，可随时中断 */
    private static final int BOOT_TICKS = 360;

    /** 拦截前端 blob: 下载，全部改写到应用私有目录 */
    private static final String DOWNLOAD_HOOK =
            "(function(){if(window.__tavernHook)return;window.__tavernHook=1;" +
            "document.addEventListener('click',function(e){" +
            "var t=e&&e.target;if(!t)return;" +
            "var a=(t.closest?t.closest('a[download]'):null)||" +
            "((t.parentElement&&t.parentElement.closest)?t.parentElement.closest('a[download]'):null);" +
            "if(!a)return;var h=a.getAttribute('href')||'';" +
            "if(h.indexOf('blob:')!==0)return;" +
            "e.preventDefault();e.stopPropagation();" +
            "fetch(h).then(function(r){return r.blob();}).then(function(b){" +
            "var fr=new FileReader();fr.onload=function(){" +
            "var s=String(fr.result);var i=s.indexOf(',');" +
            "TavernApp.onDownload(a.getAttribute('download')||'download.bin',s.substring(i+1));" +
            "};fr.readAsDataURL(b);" +
            "}).catch(function(err){console.error('tavern download hook',err);});" +
            "},true);})();";

    private static final String[] MENU = {
            "查看运行日志", "复制日志到剪贴板", "重试连接", "冷启动重启",
            "重新解压内置资源", "应用内文件", "重新加载页面",
            "复位悬浮按钮位置", "数据与卸载说明"
    };

    private static boolean sNodeStarted = false;

    /** 内置资源版本：与 APK versionCode 解耦。只有 st.zip 内容真的变了才 +1，
     *  纯壳代码（UI/JNI）改动不会触发全量重解压，用户角色卡与设置原地保留。 */
    private static final long ASSET_VERSION = 3;

    private static final String PREF = "tavern_ui";
    private static final String KEY_BX = "bubble_x";
    private static final String KEY_BY = "bubble_y";
    private static final String KEY_TIP = "bubble_tip";
    private static final int BUBBLE_DP = 46;
    private static final float BUBBLE_IDLE_ALPHA = 0.42f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout rootLayout;
    private LinearLayout splash;
    private TextView statusView;
    private TextView logView;
    private ProgressBar progress;
    private WebView web;
    private TextView bubble;
    private int bubbleSize;
    private float downRawX, downRawY, downTx, downTy;
    private boolean dragging = false;
    private File stRoot;
    private File bootLog;
    private volatile String nodeExit = null;
    private volatile boolean bootFinished = false;
    private boolean webShown = false;
    private long lastBack = 0L;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        stRoot = new File(getNoBackupFilesDir(), "st");
        bootLog = new File(getNoBackupFilesDir(), "boot.log");
        buildSplash();
        new Thread(this::boot, "tavern-boot").start();
    }

    // ---------------------------------------------------------------- UI

    private void buildSplash() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#141922"));
        splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("酒馆");
        title.setTextColor(Color.parseColor("#E8C88A"));
        title.setTextSize(34f);
        title.setGravity(Gravity.CENTER);
        splash.addView(title);

        TextView sub = new TextView(this);
        sub.setText("SillyTavern · 应用内运行");
        sub.setTextColor(Color.parseColor("#8A94A6"));
        sub.setTextSize(14f);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 12, 0, 48);
        splash.addView(sub);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        splash.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setText("正在启动本地服务…");
        statusView.setTextColor(Color.parseColor("#C7CEDA"));
        statusView.setTextSize(13f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 32, 0, 0);
        splash.addView(statusView);

        // 启动阶段就能看到实时日志，失败时不必再找入口
        ScrollView logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#7F8A9C"));
        logView.setTextSize(10f);
        logView.setTextIsSelectable(true);
        logView.setPadding(8, 24, 8, 8);
        logScroll.addView(logView);
        logScroll.setVisibility(View.GONE);
        splash.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        rootLayout.addView(splash, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 悬浮菜单气泡：启动屏阶段就存在，但默认停在右侧中部，绝不压住酒馆顶栏。
        // 可拖动、松手吸附左右边缘、位置持久化（存应用私有 SharedPreferences）。
        addBubble();

        setContentView(rootLayout);
    }

    // ---------------------------------------------------------------- 可拖动悬浮气泡

    private void addBubble() {
        bubbleSize = dp(BUBBLE_DP);
        bubble = new TextView(this);
        bubble.setText("⋯");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(20f);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#CC20262F"));
        bg.setStroke(dp(1), Color.parseColor("#80E8C88A"));
        bubble.setBackground(bg);
        bubble.setAlpha(BUBBLE_IDLE_ALPHA);
        bubble.setContentDescription("应用内菜单，可拖动");

        // 用 translation 定位，避免依赖 gravity 的固定位置
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bubbleSize, bubbleSize);
        rootLayout.addView(bubble, lp);

        bubble.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = ev.getRawX();
                    downRawY = ev.getRawY();
                    downTx = v.getTranslationX();
                    downTy = v.getTranslationY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - downRawX;
                    float dy = ev.getRawY() - downRawY;
                    if (!dragging && (dx * dx + dy * dy) > dp(8) * dp(8)) {
                        dragging = true;
                        v.setAlpha(0.95f);
                    }
                    if (dragging) moveBubble(downTx + dx, downTy + dy);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        snapBubble();          // 吸附到最近边缘并保存位置
                        v.setAlpha(BUBBLE_IDLE_ALPHA);
                        dragging = false;
                    } else {
                        v.setAlpha(BUBBLE_IDLE_ALPHA);
                        showMenu();            // 轻点 → 打开菜单（自行处理，不依赖系统 click 状态机）
                    }
                    return true;               // 整个序列自消费，点击/拖动都可靠
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) { snapBubble(); dragging = false; }
                    v.setAlpha(BUBBLE_IDLE_ALPHA);
                    return true;
                default:
                    return true;
            }
        });

        // 布局完成（含旋转 / 键盘弹出导致的尺寸变化）后，按比例复原并夹回屏内
        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private int lastW = -1, lastH = -1;
                    @Override
                    public void onGlobalLayout() {
                        int w = rootLayout.getWidth(), h = rootLayout.getHeight();
                        if (w <= 0 || h <= 0 || (w == lastW && h == lastH)) return;
                        lastW = w;
                        lastH = h;
                        restoreBubblePos();
                    }
                });
        ui.post(this::restoreBubblePos);
    }

    /** 相对坐标（0~1）持久化，换分辨率 / 横竖屏都能还原 */
    private void restoreBubblePos() {
        if (bubble == null || rootLayout.getWidth() <= 0) return;
        SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        float rx = p.getFloat(KEY_BX, -1f), ry = p.getFloat(KEY_BY, -1f);
        if (rx < 0f || ry < 0f) {
            // 默认：右侧、垂直约 45% 处 —— 避开顶部工具栏与底部输入栏
            rx = 1f;
            ry = 0.45f;
        }
        float maxX = rootLayout.getWidth() - bubbleSize;
        float maxY = rootLayout.getHeight() - bubbleSize;
        moveBubble(rx * maxX, ry * maxY);
    }

    private void moveBubble(float x, float y) {
        float maxX = Math.max(0f, rootLayout.getWidth() - bubbleSize);
        float maxY = Math.max(0f, rootLayout.getHeight() - bubbleSize);
        bubble.setTranslationX(Math.max(0f, Math.min(maxX, x)));
        bubble.setTranslationY(Math.max(0f, Math.min(maxY, y)));
    }

    private void snapBubble() {
        float x = bubble.getTranslationX();
        float y = bubble.getTranslationY();
        float maxX = Math.max(1f, rootLayout.getWidth() - bubbleSize);
        float maxY = Math.max(1f, rootLayout.getHeight() - bubbleSize);
        float snappedX = (x < maxX / 2f) ? 0f : maxX;   // 吸附到左/右边缘
        moveBubble(snappedX, y);
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putFloat(KEY_BX, snappedX / maxX)
                .putFloat(KEY_BY, Math.max(0f, Math.min(maxY, y)) / maxY)
                .apply();
    }

    private void resetBubblePos() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .remove(KEY_BX).remove(KEY_BY).apply();
        restoreBubblePos();
        toast("悬浮按钮已复位到右侧中部（可长按拖动）");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- 启动流程

    private void boot() {
        try {
            bootFinished = false;
            stRoot.mkdirs();
            appendBoot("---- boot start ----");
            appendBoot("device: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " SDK=" + Build.VERSION.SDK_INT
                    + " ABIs=" + Arrays.toString(Build.SUPPORTED_ABIS));

            // 部分 VPN/加速器会设置 Java 层 HTTP 代理，可能把回环请求也劫持掉
            try {
                System.setProperty("http.proxyHost", "");
                System.setProperty("http.proxyPort", "");
                System.setProperty("https.proxyHost", "");
                System.setProperty("https.proxyPort", "");
            } catch (Throwable ignored) {}

            // 内置资源版本与 versionCode 解耦：只有 st.zip 内容变化才重解压，
            // 纯 UI 改动不会覆盖 data/ 里的角色卡，也不用再等一次全量解压。
            long ver = ASSET_VERSION;
            File marker = new File(stRoot, ".asset_version");
            String unpackedVer = readSmallFile(marker);
            if (!String.valueOf(ver).equals(unpackedVer)) {
                ui.post(() -> statusView.setText("正在更新内置资源（首次启动约 10~30 秒）"));
                appendBoot("asset version mismatch: apk=" + ver + ", unpacked=" + unpackedVer);
                unpackAssets();
                writeSmallFile(marker, String.valueOf(ver));
                appendBoot("unpack done, asset version=" + ver);
            } else {
                appendBoot("assets up to date, version=" + ver);
            }

            copyAsset("bootstrap.cjs", new File(stRoot, "bootstrap.cjs"));
            dlDir().mkdirs();
            appendBoot("prebuilt/lib.js exists: " + new File(stRoot, "prebuilt/lib.js").exists());
            startLogPump();

            String loadErr = NodeBridge.ensureLoaded();
            if (loadErr != null) {
                appendBoot("FATAL loadLibrary:\n" + loadErr);
                fail("无法加载 Node 运行时", loadErr);
                return;
            }
            appendBoot("native libs loaded");

            if (!sNodeStarted) {
                sNodeStarted = true;
                new Thread(() -> {
                    try {
                        int rc = NodeBridge.start(stRoot.getAbsolutePath());
                        nodeExit = "Node 退出 rc=" + rc;
                    } catch (Throwable t) {
                        nodeExit = "Node 异常: " + Log.getStackTraceString(t);
                    }
                    appendBoot(nodeExit);
                }, "node-main").start();
            }
            ui.post(() -> statusView.setText("正在连接本地服务…"));

            for (int i = 0; i < BOOT_TICKS; i++) {
                if (ping()) {
                    bootFinished = true;
                    appendBoot("service reachable, showing WebView");
                    ui.post(this::showWeb);
                    return;
                }
                if (nodeExit != null && i > 4) {
                    fail("Node 服务已退出", nodeExit);
                    return;
                }
                if (i % 20 == 0) appendBoot("waiting 127.0.0.1:8000 ... " + (i / 2) + "s");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            fail("服务启动超时", collectDiag());
        } catch (Exception e) {
            Log.e(TAG, "boot failed", e);
            String st = Log.getStackTraceString(e);
            appendBoot("boot exception: " + st);
            fail("启动失败", st);
        }
    }

    /** 启动期间每 2 秒把 Node 日志尾部刷到启动屏，用户能直接看到卡在哪一步 */
    private void startLogPump() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bootFinished || webShown) return;
                String s = tail(new File(stRoot, "node.log"), 14);
                if (!s.trim().isEmpty()) {
                    ui.post(() -> {
                        if (splash.getParent() != null) {
                            ((View) logView.getParent()).setVisibility(View.VISIBLE);
                            logView.setText(s);
                        }
                    });
                }
                ui.postDelayed(this, 2000);
            }
        }, 5000);
    }

    private void fail(String title, String detail) {
        bootFinished = true;
        appendBoot("FAILED: " + title);
        if (detail != null) appendBoot(detail);
        ui.post(() -> {
            statusView.setText(title + " · 点右上角 ⋯ 查看或复制日志");
            progress.setVisibility(View.GONE);
            String d = collectDiag();
            ((View) logView.getParent()).setVisibility(View.VISIBLE);
            logView.setText(d);
        });
    }

    private void showWeb() {
        if (webShown) return;
        webShown = true;
        rootLayout.removeView(splash); // 保留顶层 ⋯ 菜单

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setBackgroundColor(Color.parseColor("#141922"));

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost();
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
                toast("已拦截外部链接，酒馆始终在应用内运行");
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url.startsWith(BASE) || url.startsWith("http://localhost:8000")) {
                    v.evaluateJavascript(DOWNLOAD_HOOK, null);
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) {
                    appendBoot("WebView main frame error: " + err.getDescription());
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivityForResult(i, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    toast("无法打开文件选择器");
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new AppJs(), "TavernApp");
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, len) -> {
            if (url == null || url.startsWith("blob:")) return;
            new Thread(() -> fetchIntoPrivate(url, userAgent, contentDisposition),
                    "tavern-download").start();
        });

        rootLayout.addView(web, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        web.loadUrl(BASE);

        // 一次性提示：让用户知道悬浮按钮不再挡顶栏、且能挪位置
        SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        if (!p.getBoolean(KEY_TIP, false)) {
            p.edit().putBoolean(KEY_TIP, true).apply();
            ui.postDelayed(() -> toast("右侧 ⋯ 可拖动/点按打开菜单，可在菜单里复位位置"), 1200);
        }
    }

    // ---------------------------------------------------------------- 应用内菜单

    private void showMenu() {
        new AlertDialog.Builder(this)
                .setTitle("酒馆 · 应用内菜单")
                .setItems(MENU, (d, which) -> {
                    switch (which) {
                        case 0: showLog(); break;
                        case 1: copyLog(); break;
                        case 2: retryConnect(); break;
                        case 3: restartCold(); break;
                        case 4: confirmWipe(); break;
                        case 5: showFiles(); break;
                        case 6: if (web != null) web.reload(); else toast("页面尚未加载"); break;
                        case 7: resetBubblePos(); break;
                        case 8: showAbout(); break;
                    }
                })
                .show();
    }

    private void showLog() {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(collectDiag());
        tv.setTextSize(11f);
        tv.setTextColor(Color.parseColor("#C7CEDA"));
        tv.setTextIsSelectable(true);
        tv.setPadding(24, 16, 24, 16);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(sv)
                .setPositiveButton("复制全部", (d, w) -> copyLog())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("tavern-log", collectDiag()));
            toast("日志已复制，可直接粘贴发送");
        } catch (Exception e) {
            toast("复制失败：" + e.getMessage());
        }
    }

    private String collectDiag() {
        String b = tail(bootLog, 60);
        String n = tail(new File(stRoot, "node.log"), 80);
        String s = "【启动日志 boot.log】\n" + (b.isEmpty() ? "(空)\n" : b)
                + "\n【Node 日志 node.log】\n" + (n.isEmpty() ? "(空)\n" : n);
        if (s.length() > 12000) s = s.substring(s.length() - 12000);
        return s;
    }

    private void retryConnect() {
        if (webShown) { toast("服务已连接"); return; }
        if (nodeExit != null) { toast("Node 已退出，请用「冷启动重启」"); return; }
        toast("正在重新探测本地服务…");
        new Thread(() -> {
            for (int i = 0; i < 120; i++) {
                if (ping()) {
                    bootFinished = true;
                    ui.post(this::showWeb);
                    return;
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) { break; }
            }
            ui.post(() -> toast("仍未连接，请查看运行日志"));
        }, "tavern-retry").start();
    }

    private void restartCold() {
        appendBoot("cold restart requested");
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 500, pi);
        } catch (Exception e) {
            appendBoot("alarm setup failed: " + e);
            toast("请手动从最近任务划掉本应用后重新打开");
        }
        finish();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle("重新解压内置资源")
                .setMessage("仅重新解压程序文件，会保留 data 里的角色卡与聊天记录。"
                        + "用于修复资源损坏，随后自动冷启动。")
                .setPositiveButton("执行", (d, w) -> {
                    File[] fs = stRoot.listFiles();
                    if (fs != null) {
                        for (File f : fs) {
                            String n = f.getName();
                            if (n.equals("data") || n.equals("downloads")
                                    || n.equals("node.log") || n.equals("boot.log")) continue;
                            deleteRecursively(f);
                        }
                    }
                    appendBoot("program files wiped, will re-unpack");
                    restartCold();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFiles() {
        File dir = dlDir();
        File[] fs = dir.listFiles();
        List<String> lines = new ArrayList<>();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile()) lines.add(f.getName() + "   " + (f.length() / 1024) + " KB");
            }
        }
        if (lines.isEmpty()) lines.add("（暂无应用内下载文件）");
        new AlertDialog.Builder(this)
                .setTitle("应用内文件 · 卸载自动清除")
                .setItems(lines.toArray(new String[0]), null)
                .setPositiveButton("清空全部", (d, w) -> {
                    File[] all = dir.listFiles();
                    int n = 0;
                    if (all != null) for (File f : all) if (f.isFile() && f.delete()) n++;
                    toast("已清空 " + n + " 个应用内文件");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("数据与卸载说明")
                .setMessage("本应用完全在应用内运行酒馆，不跳转外部浏览器。\n\n"
                        + "· 角色卡、聊天记录、设置、扩展、下载的文件全部保存在应用私有目录\n"
                        + "· 不申请外部存储权限，不写入公共 Download / DCIM / Documents\n"
                        + "· 已关闭云备份与设备迁移，卸载时 Android 会自动删除全部私有数据\n"
                        + "· 前端导出/下载会被接管并保存到应用内部，可在「应用内文件」查看\n"
                        + "· 应用从未把任何文件写到外部存储，因此不会产生残留\n\n"
                        + "模型推理来自你在酒馆里配置的 API（云端），本应用不内置模型。")
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------------------------------------------------------------- 下载接管

    private final class AppJs {
        @JavascriptInterface
        public void onDownload(String name, String b64) {
            runOnUiThread(() -> {
                try {
                    byte[] data = Base64.decode(b64, Base64.DEFAULT);
                    File dir = dlDir();
                    dir.mkdirs();
                    File f = new File(dir, safeName(name));
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        o.write(data);
                    }
                    toast("已保存到应用内部：" + f.getName() + "（卸载自动清除）");
                } catch (Exception e) {
                    toast("保存失败：" + e.getMessage());
                }
            });
        }
    }

    private void fetchIntoPrivate(String url, String ua, String cd) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            c.connect();
            File f = new File(dlDir(), safeName(guessName(url, cd)));
            byte[] buf = new byte[32768];
            try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            toast("已保存到应用内部：" + f.getName());
        } catch (Exception e) {
            toast("下载失败：" + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String guessName(String url, String cd) {
        if (cd != null) {
            int i = cd.toLowerCase().indexOf("filename=");
            if (i >= 0) {
                String v = cd.substring(i + 9).replace("\"", "").trim();
                int semi = v.indexOf(';');
                if (semi > 0) v = v.substring(0, semi);
                if (!v.isEmpty()) return v;
            }
        }
        String p = url;
        int q = p.indexOf('?');
        if (q > 0) p = p.substring(0, q);
        int slash = p.lastIndexOf('/');
        if (slash >= 0 && slash < p.length() - 1) p = p.substring(slash + 1);
        return p.isEmpty() ? "download.bin" : p;
    }

    private static String safeName(String n) {
        if (n == null || n.isEmpty()) return "download.bin";
        n = n.replace('/', '_').replace('\\', '_').replace('\0', '_');
        if (n.equals(".") || n.equals("..")) return "download.bin";
        return n;
    }

    // ---------------------------------------------------------------- 资源解压

    private void unpackAssets() throws Exception {
        stRoot.mkdirs();
        final String canonicalRoot = stRoot.getCanonicalPath() + File.separator;
        byte[] buf = new byte[65536];
        int count = 0;
        try (InputStream raw = getAssets().open("st.zip");
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                File out = new File(stRoot, name);
                // 只在名字可疑时做 canonical 校验，避免 2.6 万个文件逐次系统调用
                if (name.contains("..") && !out.getCanonicalPath().startsWith(canonicalRoot)) {
                    zis.closeEntry();
                    continue; // 防 zip 路径穿越
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream o = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
                if (++count % 2000 == 0) {
                    final int c = count;
                    appendBoot("unpacked " + c + " files");
                    ui.post(() -> statusView.setText("首次启动 · 正在解压内置资源（" + c + " 个文件）"));
                }
            }
        }
        appendBoot("unpack total files=" + count);
    }

    private void copyAsset(String name, File out) throws Exception {
        try (InputStream in = getAssets().open(name); FileOutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursively(x);
        }
        f.delete();
    }

    private long appVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 1L;
        }
    }

    private static String readSmallFile(File f) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeSmallFile(File f, String s) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    // ---------------------------------------------------------------- 工具

    private File dlDir() {
        return new File(getNoBackupFilesDir(), "downloads");
    }

    /** 先用裸 Socket 探测端口（绕开系统 HTTP 代理/VPN），再确认 HTTP 层可用 */
    private boolean ping() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 8000), 600);
        } catch (Exception e) {
            return false;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE).openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(800);
            c.setReadTimeout(1200);
            c.setInstanceFollowRedirects(false);
            return c.getResponseCode() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private synchronized void appendBoot(String line) {
        if (bootLog == null) return;
        try (FileWriter w = new FileWriter(bootLog, true)) {
            w.append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                    .append(' ').append(line).append('\n');
        } catch (Exception ignored) {}
    }

    private static String tail(File f, int maxLines) {
        if (f == null || !f.exists()) return "";
        ArrayDeque<String> q = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                q.addLast(line);
                if (q.size() > maxLines) q.removeFirst();
            }
        } catch (Exception e) {
            return "读取日志失败：" + e.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        for (String l : q) sb.append(l).append('\n');
        return sb.toString();
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE) return;
        Uri[] result = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            finish();
            // Node 在同一进程只能 Start 一次，退出时结束进程，保证下次干净冷启动
            android.os.Process.killProcess(android.os.Process.myPid());
        } else {
            lastBack = now;
            toast("再按一次退出酒馆（单击 Home 可切到后台）");
        }
    }
}
