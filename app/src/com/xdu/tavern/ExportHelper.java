package com.xdu.tavern;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 导出助手：默认所有数据都留在应用私有目录；只有用户主动点「分享 / 保存到手机」时，
 * 才把文件交出去。写公共目录这一步由系统 SAF 代劳（用户自己选位置），
 * 本应用始终不申请外部存储权限、不主动往公共目录写任何东西。
 */
public class ExportHelper {

    public static final int REQ_EXPORT = 1002;
    public static final int REQ_EXPORT_ZIP = 1003;

    private static final int ZIP_DOWNLOADS = 1;
    private static final int ZIP_FULL = 2;

    private final Activity a;
    private final File stRoot;
    private final File dlDir;

    private File pendingExport = null;
    private int pendingZipMode = 0;

    public ExportHelper(Activity a, File stRoot) {
        this.a = a;
        this.stRoot = stRoot;
        this.dlDir = new File(a.getNoBackupFilesDir(), "downloads");
    }

    // ---------------------------------------------------------------- 对外入口

    /** 前端导出完成后立刻给出导出口，避免文件「进得去出不来」 */
    public void offerExport(File f) {
        if (a.isFinishing() || a.isDestroyed()) return;
        new AlertDialog.Builder(a)
                .setTitle("文件已保存 · 现在导出？")
                .setMessage("《" + f.getName() + "》 " + (f.length() / 1024) + " KB\n\n"
                        + "默认只存在应用私有目录，卸载会一起清除。\n"
                        + "导出的文件由你自己管理，卸载本应用不会删除它。")
                .setItems(new String[]{
                        "分享 / 用其他应用打开",
                        "保存到手机（自己选位置）",
                        "导出全部数据（角色卡 + 聊天记录 zip）",
                        "仅留在应用内"
                }, (d, w) -> {
                    switch (w) {
                        case 0: share(f); break;
                        case 1: saveAs(f); break;
                        case 2: exportZip(ZIP_FULL); break;
                        default: toast("已留在应用内，可随时在 ⋯ →「应用内文件」导出"); break;
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 菜单「应用内文件」：逐项可点，支持分享 / 保存 / 删除 / 打包 / 清空 */
    public void showFiles() {
        dlDir.mkdirs();
        File[] arr = dlDir.listFiles(f -> f.isFile());
        final File[] files = arr == null ? new File[0] : arr;
        Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));

        String[] items;
        if (files.length == 0) {
            items = new String[]{"（暂无应用内文件）"};
        } else {
            items = new String[files.length];
            for (int i = 0; i < files.length; i++) {
                items[i] = files[i].getName() + "   " + (files[i].length() / 1024)
                        + " KB · 点按导出";
            }
        }

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("应用内文件 · 可导出")
                .setItems(items, (d, w) -> {
                    if (files.length > 0 && w < files.length) fileActions(files[w]);
                })
                .setNegativeButton("关闭", null);
        if (files.length > 0) {
            b.setNeutralButton("打包全部", (d, w) -> exportZip(ZIP_DOWNLOADS));
            b.setPositiveButton("清空全部", (d, w) -> {
                int n = 0;
                for (File f : files) if (f.delete()) n++;
                toast("已清空 " + n + " 个应用内文件");
            });
        }
        b.show();
    }

    /** 菜单「导出全部数据」：把 data/ 与 downloads/ 整体打包给用户 */
    public void exportFullData() {
        new AlertDialog.Builder(a)
                .setTitle("导出全部数据")
                .setMessage("将把角色卡、聊天记录、设置、扩展、世界书以及应用内文件\n"
                        + "打包成一个 zip，由你选择保存位置。\n\n"
                        + "这是从私有目录复制出来的副本，卸载不会删除它，请自行保管。")
                .setPositiveButton("选择保存位置", (d, w) -> exportZip(ZIP_FULL))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 由 MainActivity.onActivityResult 委托；返回 true 表示已消费 */
    public boolean onActivityResult(int req, int res, Intent data) {
        if (req == REQ_EXPORT) {
            final File src = pendingExport;
            pendingExport = null;
            if (res == Activity.RESULT_OK && data != null && data.getData() != null && src != null) {
                final Uri dst = data.getData();
                new Thread(() -> writeSingle(src, dst), "tavern-export").start();
            }
            return true;
        }
        if (req == REQ_EXPORT_ZIP) {
            final int mode = pendingZipMode;
            pendingZipMode = 0;
            if (res == Activity.RESULT_OK && data != null && data.getData() != null && mode != 0) {
                final Uri dst = data.getData();
                new Thread(() -> writeZip(mode, dst), "tavern-export-zip").start();
            }
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 三种导出方式

    /** 分享 / 用其他应用打开：content:// 临时授权，接收方无需任何存储权限 */
    private void share(File f) {
        try {
            Uri u = uriOf(f);
            String mime = mimeOf(f.getName());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, u);
            // setClipData 是关键：把 URI 授权随 Intent 传递给 chooser 选中的目标
            i.setClipData(new ClipData(f.getName(), new String[]{mime}, new ClipData.Item(u)));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "分享 / 导出：" + f.getName()));
        } catch (Exception e) {
            toast("分享失败：" + e.getMessage());
        }
    }

    /** 保存到手机：系统文件选择器，用户自己决定写到哪个目录 */
    private void saveAs(File f) {
        pendingExport = f;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mimeOf(f.getName()));
        i.putExtra(Intent.EXTRA_TITLE, f.getName());
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            pendingExport = null;
            toast("无法打开文件保存器：" + e.getMessage());
        }
    }

    private void exportZip(int mode) {
        pendingZipMode = mode;
        String name = (mode == ZIP_FULL ? "tavern-data-" : "tavern-downloads-")
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".zip";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, name);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            a.startActivityForResult(i, REQ_EXPORT_ZIP);
        } catch (Exception e) {
            pendingZipMode = 0;
            toast("无法打开文件保存器：" + e.getMessage());
        }
    }

    private void fileActions(File f) {
        new AlertDialog.Builder(a)
                .setTitle(f.getName() + " · " + (f.length() / 1024) + " KB")
                .setItems(new String[]{
                        "分享 / 用其他应用打开",
                        "保存到手机（选择位置）",
                        "删除此文件"
                }, (d, w) -> {
                    switch (w) {
                        case 0: share(f); break;
                        case 1: saveAs(f); break;
                        case 2: toast(f.delete() ? "已删除 " + f.getName() : "删除失败"); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 写文件（后台线程）

    private void writeSingle(File src, Uri dst) {
        try (InputStream in = new FileInputStream(src)) {
            OutputStream out = a.getContentResolver().openOutputStream(dst);
            if (out == null) throw new IllegalStateException("无法打开目标文件");
            try (OutputStream o = out) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                o.flush();
            }
            toast("已导出：" + src.getName() + "（卸载不会删除）");
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private void writeZip(int mode, Uri dst) {
        OutputStream os = null;
        try {
            os = a.getContentResolver().openOutputStream(dst);
            if (os == null) throw new IllegalStateException("无法打开目标文件");
            int count = 0;
            try (ZipOutputStream z = new ZipOutputStream(
                    new BufferedOutputStream(os), StandardCharsets.UTF_8)) {
                if (mode == ZIP_FULL) count += addTree(z, new File(stRoot, "data"), "data/");
                count += addTree(z, dlDir, "downloads/");

                z.putNextEntry(new ZipEntry("导出说明.txt"));
                String note = "酒馆 Android App 数据导出\n"
                        + "导出时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date()) + "\n\n"
                        + "data/        角色卡、聊天记录、设置、扩展、世界书等全部用户数据\n"
                        + "downloads/   前端导出/下载后暂存在应用内的文件\n\n"
                        + "说明：\n"
                        + "1. 这是从应用私有目录复制出来的副本，卸载本应用不会删除它们，请自行保管。\n"
                        + "2. 应用本身从不主动写入公共目录；仅在你主动点「保存到手机」时，\n"
                        + "   才由系统写入你选择的位置。\n"
                        + "3. 迁移时可参考 data/ 里的目录结构在新设备上恢复。\n";
                z.write(note.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            toast("已导出 " + count + " 个文件到 zip（卸载不会删除）");
        } catch (Exception e) {
            toast("打包失败：" + e.getMessage());
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
        }
    }

    private int addTree(ZipOutputStream z, File dir, String prefix) throws Exception {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        byte[] buf = new byte[65536];
        for (File f : fs) {
            if (f.isDirectory()) {
                n += addTree(z, f, prefix + f.getName() + "/");
            } else if (f.isFile()) {
                z.putNextEntry(new ZipEntry(prefix + f.getName()));
                try (FileInputStream in = new FileInputStream(f)) {
                    int r;
                    while ((r = in.read(buf)) > 0) z.write(buf, 0, r);
                }
                z.closeEntry();
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 工具

    /** 构造只授权单个文件的 content:// URI（相对路径经 Uri.Builder 编码，中文安全）。
     *  基准必须是 noBackupFilesDir：downloads/ 与 st/ 是兄弟目录，
     *  若以 st/ 为基准会算出错误相对路径，provider 端将找不到文件。 */
    private Uri uriOf(File f) {
        String root = a.getNoBackupFilesDir().getAbsolutePath();
        String abs = f.getAbsolutePath();
        String rel = abs.startsWith(root) ? abs.substring(root.length()) : f.getName();
        while (rel.startsWith("/")) rel = rel.substring(1);
        return new Uri.Builder()
                .scheme("content")
                .authority(ExportProvider.AUTHORITY)
                .appendPath("file")
                .appendQueryParameter("p", rel)
                .build();
    }

    public static String mimeOf(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase(Locale.US);
        int q = n.indexOf('?');
        if (q > 0) n = n.substring(0, q);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".json") || n.endsWith(".jsonl")) return "application/json";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")
                || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    private void toast(String m) {
        a.runOnUiThread(() -> Toast.makeText(a, m, Toast.LENGTH_SHORT).show());
    }
}
