package com.xdu.tavern;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读导出通道：把应用私有目录里的文件以 content:// 临时授权给外部 App。
 *
 * <p>设计要点（保持「默认零残留」）：
 * <ul>
 *   <li>exported=false：外部无法直接访问；仅当本应用通过 Intent +
 *       FLAG_GRANT_READ_URI_PERMISSION 显式授权时，接收方才能临时读取。</li>
 *   <li>不申请任何外部存储权限，也不需要 androidx / FileProvider（本工程无 Gradle）。</li>
 *   <li>只暴露 noBackupFilesDir 下的普通文件（st/ 与 downloads/ 都在其内），
 *       openFile 只读，并做 canonical 路径校验。</li>
 * </ul>
 *
 * <p>URI 形式：content://com.xdu.tavern.export/file?p=&lt;相对 noBackupFilesDir 的路径&gt;
 * 用查询参数传相对路径（而非多段 path），中文文件名由 Uri.Builder 自动编解码，最稳。
 * 注意 downloads/ 与 st/ 是兄弟目录，根必须是 noBackupFilesDir 而非 st/，否则会解析错。
 */
public class ExportProvider extends ContentProvider {

    public static final String AUTHORITY = "com.xdu.tavern.export";

    private File root;

    @Override
    public boolean onCreate() {
        // 根取 noBackupFilesDir 本身：st/ 与 downloads/ 都是它的子目录
        root = getContext().getNoBackupFilesDir();
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String rel = uri.getQueryParameter("p");
        if (TextUtils.isEmpty(rel)) throw new FileNotFoundException("missing path");
        final File base;
        final File f;
        try {
            base = root.getCanonicalFile();
            f = new File(base, rel).getCanonicalFile();
        } catch (Exception e) {
            throw new FileNotFoundException(e.getMessage());
        }
        // 防路径穿越：解析后的真实路径必须仍在应用私有 st 根内
        String prefix = base.getAbsolutePath() + File.separator;
        if (!f.getAbsolutePath().startsWith(prefix)) {
            throw new FileNotFoundException("path outside app private root");
        }
        if (!f.isFile()) throw new FileNotFoundException("not a regular file");
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && (mode.contains("w") || mode.contains("W"))) {
            throw new FileNotFoundException("read-only provider");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return ExportHelper.mimeOf(uri.getQueryParameter("p"));
    }

    /** 部分接收方会查询文件名与大小，返回 OpenableColumns 标准列以便正确显示 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        final File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, 1);
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null; // 只读
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
