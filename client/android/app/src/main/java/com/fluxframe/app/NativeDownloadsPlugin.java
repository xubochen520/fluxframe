package com.fluxframe.app;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 原生下载桥（自研下载器，不依赖系统 DownloadManager —— 部分手机 ROM
 * 不给存储权限时 DownloadManager 无法写入公共下载目录）。
 *
 * · Android 9 及以下：先请求「存储」运行时权限（弹窗），授权后直接写入
 *   /Download 公共下载目录；
 * · Android 10 及以上：经 MediaStore.Downloads（IS_PENDING 事务）写入
 *   公共「下载」目录，系统授权模型下无需存储权限；
 * · Android 13 及以上：附带请求通知权限，用于下载完成通知（不授权也不影响保存）。
 *
 * 进度由页面每 ~800ms 轮询 progress() 返回真实字节数；取消会中断连接并清理半成品。
 */
@CapacitorPlugin(name = "NativeDownloads")
public class NativeDownloadsPlugin extends Plugin {

    static final int REQUEST_STORAGE = 9001; // Android ≤9 的存储权限请求码
    static final int REQUEST_NOTIFY = 9002;  // Android 13+ 的通知权限请求码

    private static final String CHANNEL_ID = "fluxframe_downloads";
    private static final long CONNECT_TIMEOUT_MS = 45_000;
    private static final long READ_TIMEOUT_MS = 120_000;

    private static NativeDownloadsPlugin instance;
    private static final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private static final AtomicLong idSeq = new AtomicLong(1);
    private static PluginCall pendingStorageCall; // 等待存储授权结果的 start 调用

    private static class Job {
        long id;
        volatile String status = "pending"; // pending | running | successful | failed
        volatile long downloaded = 0;
        volatile long total = -1; // -1 = 未知
        volatile String message = "";
        volatile boolean cancelled = false;
        volatile HttpURLConnection conn;
        volatile Thread thread;
        String safeName;
        Uri mediaUri;      // Android 10+ MediaStore 条目
        File destFile;     // Android 9 及以下目标文件
    }

    @Override
    public void load() {
        instance = this;
    }

    private static String sanitizeFilename(String name) {
        String cleaned = name.replaceAll("[/\\\\:*?\"<>|\\u0000-\\u001f]+", "_").trim();
        cleaned = cleaned.replaceAll("\\.+$", "");
        if (cleaned.isEmpty()) cleaned = "download";
        if (cleaned.length() > 120) {
            int ext = cleaned.lastIndexOf('.');
            String core = ext > 0 ? cleaned.substring(0, ext) : cleaned;
            String suffix = ext > 0 ? cleaned.substring(ext) : "";
            cleaned = core.substring(0, Math.min(100, core.length())) + suffix;
        }
        return cleaned;
    }

    private static String mimeOf(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static String httpFailMessage(int code) {
        if (code == 401) return "登录已过期，请回到首页重新登录后再试";
        if (code == 403) return "服务器拒绝访问（HTTP 403）";
        if (code == 404) return "文件不存在或已被删除（HTTP 404）";
        return "服务器返回 HTTP " + code;
    }

    private static String exceptionMessage(Exception e) {
        if (e instanceof UnknownHostException) return "无法解析服务器地址";
        if (e instanceof ConnectException) return "连接服务器失败（电脑端服务未启动或不在同一网络？）";
        if (e instanceof SocketTimeoutException) return "连接服务器超时，请确认电脑端在线后重试";
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return "下载中断：" + e.getClass().getSimpleName();
        if (msg.contains("Permission denied")) return "无存储写入权限，请允许存储权限后重试";
        if (msg.contains("ENOSPC") || msg.toLowerCase().contains("space")) return "手机存储空间不足";
        return "下载中断：" + msg;
    }

    // ------------------------------------------------------------------
    // 对外方法（与页面任务坞配合：start / progress / cancel）
    // ------------------------------------------------------------------

    @PluginMethod
    public void start(PluginCall call) {
        String url = call.getString("url");
        String filename = call.getString("filename");
        String cookie = call.getString("cookie");
        if (url == null || url.isEmpty() || filename == null || filename.isEmpty()) {
            call.reject("下载地址与文件名不能为空");
            return;
        }
        /* Android 6~9：公共下载目录需要存储运行时权限 → 先弹权限框，授权后自动开始 */
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28) {
            Activity activity = getActivity();
            if (activity != null && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingStorageCall = call;
                activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
                return; // 授权结果回来后继续（MainActivity → handlePermissionResult）
            }
        }
        launch(call, url, filename, cookie);
        /* Android 13+：顺带请求通知权限（仅影响“下载完成”通知，不影响保存） */
        if (Build.VERSION.SDK_INT >= 33) {
            Activity activity = getActivity();
            if (activity != null && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFY);
            }
        }
    }

    /** 存储授权结果回调（由 MainActivity.onRequestPermissionsResult 转发） */
    public static void handlePermissionResult(int requestCode, int[] grantResults) {
        if (requestCode == REQUEST_STORAGE && instance != null && pendingStorageCall != null) {
            PluginCall call = pendingStorageCall;
            pendingStorageCall = null;
            boolean granted = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                instance.launch(call, call.getString("url"), call.getString("filename"), call.getString("cookie"));
            } else {
                call.reject("未授予存储权限，无法保存到手机「下载」文件夹（可在系统设置中开启后重试）");
            }
        }
    }

    private void launch(PluginCall call, String url, String filename, String cookie) {
        try {
            final Job job = new Job();
            job.id = idSeq.getAndIncrement();
            job.safeName = sanitizeFilename(filename);
            jobs.put(job.id, job);
            final String target = url;
            Thread thread = new Thread(() -> runJob(job, target, cookie), "fluxframe-download-" + job.id);
            job.thread = thread;
            thread.start(); // 非守护线程：页面切后台时下载继续
            JSObject ret = new JSObject();
            ret.put("id", String.valueOf(job.id));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("无法开始下载：" + exceptionMessage(e));
        }
    }

    private void runJob(Job job, String url, String cookie) {
        InputStream in = null;
        OutputStream out = null;
        boolean finalized = false;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            job.conn = conn;
            conn.setConnectTimeout((int) CONNECT_TIMEOUT_MS);
            conn.setReadTimeout((int) READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Fluxframe");
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                job.status = "failed";
                job.message = httpFailMessage(code);
                return;
            }
            job.total = conn.getContentLengthLong();
            in = conn.getInputStream();
            job.status = "running";

            Context app = getContext().getApplicationContext();
            if (Build.VERSION.SDK_INT >= 29) {
                out = openMediaStoreOutput(app, job);
            } else {
                out = openPublicFileOutput(app, job);
            }
            if (job.cancelled) return; // 半成品由调用方清理

            byte[] buf = new byte[64 * 1024];
            int n;
            long done = 0;
            while ((n = in.read(buf)) > 0) {
                if (job.cancelled) return;
                out.write(buf, 0, n);
                done += n;
                job.downloaded = done;
            }
            out.flush();
            if (job.cancelled) return;
            if (Build.VERSION.SDK_INT >= 29) {
                finalizeMediaStore(app, job); // 清除 IS_PENDING → 文件对系统可见
            }
            finalized = true;
            job.status = "successful";
            job.downloaded = job.total > 0 ? job.total : done;
            postCompletionNotification(job);
        } catch (Exception e) {
            if (job.cancelled) return;
            job.status = "failed";
            job.message = exceptionMessage(e);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            if (job.conn != null) job.conn.disconnect();
            if (!finalized && !job.cancelled && job.status.equals("failed")) {
                cleanupPartial(job);
            }
            job.conn = null;
        }
    }

    /** Android 10+：经 MediaStore 写入公共「下载」目录（无需存储权限） */
    private OutputStream openMediaStoreOutput(Context app, Job job) throws IOException {
        ContentResolver resolver = app.getContentResolver();
        String name = job.safeName;
        for (int attempt = 0; attempt < 20; attempt++) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                job.mediaUri = uri;
                return resolver.openOutputStream(uri, "w");
            }
            /* 同名冲突时自动追加 (n) */
            name = bumpName(job.safeName, attempt + 1);
        }
        throw new IOException("无法在下载目录创建文件");
    }

    private void finalizeMediaStore(Context app, Job job) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            app.getContentResolver().update(job.mediaUri, values, null, null);
        } catch (Exception ignored) { }
    }

    /** Android 9 及以下：请求存储权限后直接写公共下载目录文件 */
    private OutputStream openPublicFileOutput(Context app, Job job) throws IOException {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建下载目录");
        File file = new File(dir, job.safeName);
        for (int attempt = 0; file.exists() && attempt < 20; attempt++) {
            file = new File(dir, bumpName(job.safeName, attempt + 1));
        }
        job.destFile = file;
        job.safeName = file.getName();
        return new FileOutputStream(file);
    }

    private static String bumpName(String name, int n) {
        int ext = name.lastIndexOf('.');
        if (ext > 0) {
            return name.substring(0, ext) + " (" + n + ")" + name.substring(ext);
        }
        return name + " (" + n + ")";
    }

    private void cleanupPartial(Job job) {
        try {
            if (Build.VERSION.SDK_INT >= 29 && job.mediaUri != null) {
                getContext().getApplicationContext().getContentResolver().delete(job.mediaUri, null, null);
            } else if (job.destFile != null && job.destFile.exists()) {
                job.destFile.delete();
            }
        } catch (Exception ignored) { }
    }

    /** 下载完成/失败的系统通知（Android 13+ 未授权通知权限则跳过，不影响保存） */
    private void postCompletionNotification(Job job) {
        try {
            Context app = getContext().getApplicationContext();
            if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "下载完成", NotificationManager.IMPORTANCE_DEFAULT);
                manager.createNotificationChannel(channel);
            }
            Intent open = null;
            try {
                if (Build.VERSION.SDK_INT >= 29 && job.mediaUri != null) {
                    open = new Intent(Intent.ACTION_VIEW);
                    open.setDataAndType(job.mediaUri, mimeOf(job.safeName));
                    open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else if (job.destFile != null && job.destFile.exists()) {
                    Uri fileUri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", job.destFile);
                    open = new Intent(Intent.ACTION_VIEW);
                    open.setDataAndType(fileUri, mimeOf(job.safeName));
                    open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Exception ignored) { }
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL_ID)
                    : new Notification.Builder(app);
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("下载完成")
                    .setContentText(job.safeName + " 已保存到手机「下载」文件夹")
                    .setAutoCancel(true);
            if (open != null) {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
                builder.setContentIntent(PendingIntent.getActivity(app, 0, open, flags));
            }
            manager.notify((int) job.id, builder.build());
        } catch (Exception ignored) { }
    }

    @PluginMethod
    public void progress(PluginCall call) {
        String sid = call.getString("id");
        if (sid == null) { call.reject("缺少任务 id"); return; }
        long id;
        try {
            id = Long.parseLong(sid);
        } catch (NumberFormatException e) {
            call.reject("无效任务 id");
            return;
        }
        Job job = jobs.get(id);
        if (job == null) {
            JSObject gone = new JSObject();
            gone.put("status", "gone");
            call.resolve(gone);
            return;
        }
        JSObject ret = new JSObject();
        ret.put("status", job.status);
        ret.put("downloaded", job.downloaded);
        ret.put("total", job.total);
        if (job.status.equals("failed")) ret.put("message", job.message);
        call.resolve(ret);
        if (job.status.equals("successful") || job.status.equals("failed")) {
            jobs.remove(id); // 终态后移除，JS 不再轮询
        }
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String sid = call.getString("id");
        if (sid == null) { call.reject("缺少任务 id"); return; }
        try {
            long id = Long.parseLong(sid);
            Job job = jobs.get(id);
            if (job != null) {
                job.cancelled = true;
                try { if (job.conn != null) job.conn.disconnect(); } catch (Exception ignored) { }
                try { if (job.thread != null) job.thread.interrupt(); } catch (Exception ignored) { }
                jobs.remove(id);
                cleanupPartial(job);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("取消失败：" + e.getMessage());
        }
    }
}
