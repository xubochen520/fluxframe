package com.fluxframe.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;

/**
 * 原生下载桥：系统 DownloadManager 下载 + MediaStore 发布。
 *
 * 下载过程分为两个明确阶段：
 * 1. DownloadManager 将网络内容下载到应用专属的临时 Movies/Pictures 目录；
 * 2. 下载成功后，用 ContentResolver 将内容复制到 MediaStore，并通过 IS_PENDING
 *    发布到图库。
 *
 * DownloadManager 只负责可靠的网络传输和断点/后台调度，MediaStore 只负责最终的
 * 公共媒体入库。任何一个阶段失败都会显示失败，不会再把“下载任务完成”当成“图库
 * 已保存完成”。
 */
@CapacitorPlugin(name = "NativeDownloads")
public class NativeDownloadsPlugin extends Plugin {

    static final int REQUEST_STORAGE = 9001;
    static final int REQUEST_NOTIFY = 9002;

    private static final String CHANNEL_ID = "fluxframe_downloads";
    private static final String TAG = "FluxframeDownload";
    private static final long POLL_INTERVAL_MS = 500;
    private static final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private static final AtomicLong idSeq = new AtomicLong(1);
    private static NativeDownloadsPlugin instance;
    private static PluginCall pendingStorageCall;
    private static final Map<String, SavedFile> recentDone = new ConcurrentHashMap<>();

    private static class SavedFile {
        Uri uri;
        String path;
        String name;
        String mime;
        long size;
        long time;
    }

    private static class Job {
        long id;
        volatile String status = "pending"; // pending | running | finalizing | successful | failed
        volatile long downloaded = 0;
        volatile long total = -1;
        volatile String message = "";
        volatile boolean cancelled = false;
        volatile Thread thread;
        volatile long downloadManagerId = -1;
        final Context app;
        final String sourceUrl;
        final String cookie;
        File stagingFile;
        String safeName;
        Uri mediaUri;
        File destFile;
        String savedPath;
        String relativePath;

        Job(Context app, String sourceUrl, String cookie, String safeName) {
            this.app = app;
            this.sourceUrl = sourceUrl;
            this.cookie = cookie;
            this.safeName = safeName;
        }
    }

    private static class DownloadSnapshot {
        int status;
        int reason;
        long downloaded;
        long total;
        String mediaType;
    }

    private static void logInfo(String message) {
        Log.i(TAG, message);
    }

    private static void logError(String message, Throwable error) {
        Log.e(TAG, message, error);
    }

    private static String errorJson(String message) {
        try {
            return new JSONObject().put("error", message).toString();
        } catch (Exception ignored) {
            return "{\"error\":\"下载失败\"}";
        }
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
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".m4v")) return "video/x-m4v";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static boolean isVideo(Job job) {
        return mimeOf(job.safeName).startsWith("video/");
    }

    private static boolean isImage(Job job) {
        return mimeOf(job.safeName).startsWith("image/");
    }

    private static String exceptionMessage(Exception e) {
        if (e instanceof FileNotFoundException) return "下载临时文件不存在，系统未正确落盘";
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return "下载中断：" + e.getClass().getSimpleName();
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("permission denied")) return "无存储写入权限，请允许存储权限后重试";
        if (lower.contains("enospc") || lower.contains("space")) return "手机存储空间不足";
        return "下载中断：" + msg;
    }

    private static boolean isErrorContentType(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) return false;
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/") || lower.contains("json") || lower.contains("xml");
    }

    // ------------------------------------------------------------------
    // 对外方法：start / progress / cancel / openFile
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
        logInfo("start filename=" + filename + " url=" + url);
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28) {
            Activity activity = getActivity();
            if (activity != null && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingStorageCall = call;
                activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
                return;
            }
        }
        launch(call, url, filename, cookie);
        requestNotificationPermission();
    }

    public static void handlePermissionResult(int requestCode, int[] grantResults) {
        if (requestCode == REQUEST_STORAGE && instance != null && pendingStorageCall != null) {
            PluginCall call = pendingStorageCall;
            pendingStorageCall = null;
            boolean granted = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                instance.launch(call, call.getString("url"), call.getString("filename"), call.getString("cookie"));
                instance.requestNotificationPermission();
            } else {
                call.reject("未授予存储权限，无法保存到手机图库（可在系统设置中开启后重试）");
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        Activity activity = getActivity();
        if (activity != null && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFY);
        }
    }

    private void launch(PluginCall call, String url, String filename, String cookie) {
        try {
            Context app = getContext().getApplicationContext();
            String jobId = createJob(app, url, filename, cookie);
            JSObject ret = new JSObject();
            ret.put("id", jobId);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("无法开始下载：" + exceptionMessage(e));
        }
    }

    /**
     * MainActivity 加载的是电脑端 HTTP 页面，远程页面不会自动获得 Capacitor JS
     * 注入脚本；这个静态入口供 MainActivity 的 JavaScriptInterface 调用。
     */
    public static String startFromJavascript(Context context, String url, String filename, String cookie) {
        if (url == null || url.isEmpty() || filename == null || filename.isEmpty()) {
            return errorJson("下载地址与文件名不能为空");
        }
        NativeDownloadsPlugin plugin = instance;
        if (plugin == null) return errorJson("原生下载模块尚未初始化，请重启 App");
        try {
            String id = plugin.createJob(context.getApplicationContext(), url, filename, cookie);
            return new JSONObject().put("id", id).toString();
        } catch (Exception e) {
            String message = exceptionMessage(e);
            logError("javascript start failed message=" + message, e);
            return errorJson(message);
        }
    }

    private String createJob(Context app, String url, String filename, String cookie) throws IOException {
        Uri source = Uri.parse(url);
        if (source.getScheme() == null || source.getHost() == null) throw new IOException("下载地址无效");
        Job job = new Job(app, url, cookie, sanitizeFilename(filename));
        job.id = idSeq.getAndIncrement();
        jobs.put(job.id, job);
        Thread thread = new Thread(() -> runJob(job), "fluxframe-download-" + job.id);
        job.thread = thread;
        thread.start();
        logInfo("job created id=" + job.id + " source=" + url);
        return String.valueOf(job.id);
    }

    public static String progressFromJavascript(String sid) {
        try {
            long id = Long.parseLong(sid == null ? "" : sid);
            Job job = jobs.get(id);
            JSONObject ret = new JSONObject();
            if (job == null) return ret.put("status", "gone").toString();
            ret.put("status", job.status);
            ret.put("downloaded", job.downloaded);
            ret.put("total", job.total);
            if (job.message != null && !job.message.isEmpty()) ret.put("message", job.message);
            if (job.status.equals("successful")) {
                String path = job.savedPath;
                if ((path == null || path.isEmpty()) && job.relativePath != null) path = job.relativePath + job.safeName;
                ret.put("path", path == null ? "" : path);
                jobs.remove(id);
            } else if (job.status.equals("failed")) {
                jobs.remove(id);
            }
            return ret.toString();
        } catch (Exception e) {
            return errorJson("无效下载任务：" + e.getMessage());
        }
    }

    public static String cancelFromJavascript(String sid) {
        try {
            long id = Long.parseLong(sid == null ? "" : sid);
            Job job = jobs.remove(id);
            if (job != null) {
                job.cancelled = true;
                if (job.thread != null) job.thread.interrupt();
                DownloadManager manager = (DownloadManager) job.app.getSystemService(Context.DOWNLOAD_SERVICE);
                if (instance != null) instance.cleanupPartial(job, manager);
            }
            return "{}";
        } catch (Exception e) {
            return errorJson("取消失败：" + e.getMessage());
        }
    }

    public static String openFileFromJavascript(Context context, String sid) {
        NativeDownloadsPlugin plugin = instance;
        if (plugin == null) return errorJson("原生下载模块尚未初始化，请重启 App");
        SavedFile saved = recentDone.get(sid);
        if (saved == null) return errorJson("下载记录已过期，请重新下载后再打开");
        try {
            plugin.openSavedFile(context.getApplicationContext(), saved);
            return "{}";
        } catch (ActivityNotFoundException e) {
            return errorJson("手机上没有能打开此类文件的应用");
        } catch (Exception e) {
            String msg = e.getMessage();
            return errorJson("打开失败：" + (msg == null ? e.getClass().getSimpleName() : msg));
        }
    }

    /**
     * 让系统 DownloadManager 负责网络下载。临时文件放在应用专属目录，避免 OEM
     * 对公共目录的 DownloadManager 写入策略不同；最终入库统一走 MediaStore。
     */
    private void runJob(Job job) {
        DownloadManager manager = (DownloadManager) job.app.getSystemService(Context.DOWNLOAD_SERVICE);
        try {
            if (manager == null) throw new IOException("系统下载服务不可用");
            Uri source = Uri.parse(job.sourceUrl);
            if (source.getScheme() == null || source.getHost() == null) {
                throw new IOException("下载地址无效");
            }

            String stageDir = isVideo(job) ? Environment.DIRECTORY_MOVIES
                    : (isImage(job) ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS);
            String stageName = uniqueStagingName(job.app, stageDir, job.safeName);
            job.stagingFile = new File(job.app.getExternalFilesDir(stageDir), stageName);

            DownloadManager.Request request = new DownloadManager.Request(source);
            request.setMimeType(mimeOf(job.safeName));
            request.setTitle(job.safeName);
            request.setDescription("FluxFrame 正在下载并保存到图库");
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setVisibleInDownloadsUi(false);
            request.setDestinationInExternalFilesDir(job.app, stageDir, stageName);
            request.allowScanningByMediaScanner();
            request.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android) Fluxframe");
            request.addRequestHeader("Accept-Encoding", "identity");
            if (job.cookie != null && !job.cookie.isEmpty()) {
                request.addRequestHeader("Cookie", job.cookie);
            }

            job.downloadManagerId = manager.enqueue(request);
            logInfo("enqueued job=" + job.id + " dmId=" + job.downloadManagerId
                    + " stage=" + job.stagingFile.getAbsolutePath());
            job.status = "running";
            monitorDownload(job, manager);
        } catch (Exception e) {
            if (!job.cancelled) {
                job.status = "failed";
                job.message = exceptionMessage(e);
                logError("start/enqueue failed job=" + job.id + " message=" + job.message, e);
                cleanupPartial(job, manager);
            }
        }
    }

    private static String uniqueStagingName(Context app, String type, String requested) {
        File dir = app.getExternalFilesDir(type);
        String name = requested;
        for (int i = 0; dir != null && new File(dir, name).exists() && i < 100; i++) {
            name = bumpName(requested, i + 1);
        }
        return name;
    }

    private void monitorDownload(Job job, DownloadManager manager) {
        int lastStatus = -1;
        int lastReason = -1;
        while (!job.cancelled) {
            DownloadSnapshot snapshot = queryDownload(manager, job.downloadManagerId);
            if (snapshot == null) {
                failJob(job, "系统下载记录不存在，下载未落盘");
                cleanupPartial(job, manager);
                return;
            }
            job.downloaded = Math.max(0, snapshot.downloaded);
            job.total = snapshot.total > 0 ? snapshot.total : -1;
            if (snapshot.status != lastStatus || snapshot.reason != lastReason) {
                logInfo("status job=" + job.id + " dmId=" + job.downloadManagerId
                        + " status=" + snapshot.status + " reason=" + snapshot.reason
                        + " bytes=" + job.downloaded + "/" + job.total
                        + " mime=" + snapshot.mediaType);
                lastStatus = snapshot.status;
                lastReason = snapshot.reason;
            }

            if (snapshot.status == DownloadManager.STATUS_SUCCESSFUL) {
                job.status = "finalizing";
                try {
                    validateStagedDownload(job, manager, snapshot);
                    if (Build.VERSION.SDK_INT >= 29) {
                        publishToMediaStore(job, manager);
                    } else {
                        publishToLegacyFile(job, manager);
                    }
                    manager.remove(job.downloadManagerId);
                    job.status = "successful";
                    logInfo("published job=" + job.id + " uri=" + job.mediaUri
                            + " path=" + job.relativePath + job.safeName + " bytes=" + job.downloaded);
                    rememberSaved(job);
                    postCompletionNotification(job);
                } catch (Exception e) {
                    if (!job.cancelled) {
                        failJob(job, exceptionMessage(e));
                        logError("publish failed job=" + job.id + " message=" + job.message, e);
                        cleanupPartial(job, manager);
                    }
                }
                return;
            }
            if (snapshot.status == DownloadManager.STATUS_FAILED) {
                failJob(job, downloadFailureMessage(snapshot.reason));
                logInfo("download failed job=" + job.id + " message=" + job.message);
                cleanupPartial(job, manager);
                return;
            }
            if (snapshot.status == DownloadManager.STATUS_PAUSED) {
                job.message = "系统暂停下载：" + downloadReasonMessage(snapshot.reason);
            } else {
                job.message = "正在下载";
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                if (job.cancelled) return;
            }
        }
    }

    private static DownloadSnapshot queryDownload(DownloadManager manager, long id) {
        if (id < 0) return null;
        String[] projection = {
                DownloadManager.COLUMN_STATUS,
                DownloadManager.COLUMN_REASON,
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
                DownloadManager.COLUMN_MEDIA_TYPE
        };
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            DownloadSnapshot result = new DownloadSnapshot();
            result.status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            result.reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            result.downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            result.total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int mimeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
            result.mediaType = mimeIndex >= 0 ? cursor.getString(mimeIndex) : null;
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 防止登录页、错误 JSON 或空文件被当作 mp4 发布。 */
    private static void validateStagedDownload(Job job, DownloadManager manager, DownloadSnapshot snapshot) throws IOException {
        if (job.downloaded <= 0) throw new IOException("下载内容为空，未生成视频文件");
        if (job.total > 0 && job.downloaded != job.total) {
            throw new IOException("下载内容不完整（已接收 " + job.downloaded + "/" + job.total + " 字节），请重试");
        }
        if (isErrorContentType(snapshot.mediaType)) {
            throw new IOException("服务器返回的不是视频文件（" + snapshot.mediaType + "）");
        }
        ParcelFileDescriptor descriptor = manager.openDownloadedFile(job.downloadManagerId);
        if (descriptor == null || descriptor.getStatSize() == 0) {
            if (descriptor != null) descriptor.close();
            throw new IOException("系统下载完成但临时文件为空");
        }
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] prefixBytes = new byte[512];
            int count = in.read(prefixBytes);
            if (count > 0) {
                String prefix = new String(prefixBytes, 0, count, StandardCharsets.UTF_8)
                        .trim().toLowerCase(Locale.ROOT);
                if (prefix.startsWith("<!doctype") || prefix.startsWith("<html")
                        || prefix.startsWith("{\"error") || prefix.startsWith("{\"message")) {
                    throw new IOException("服务器返回登录页或错误信息，不是视频文件");
                }
            }
        }
    }

    /** Android 10+：复制到对应 MediaStore 集合，最后才将 IS_PENDING 设为 0。 */
    private void publishToMediaStore(Job job, DownloadManager manager) throws IOException {
        ContentResolver resolver = job.app.getContentResolver();
        Uri collection;
        String relativePath;
        if (isVideo(job)) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relativePath = Environment.DIRECTORY_DCIM + "/Fluxframe/";
        } else if (isImage(job)) {
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relativePath = Environment.DIRECTORY_PICTURES + "/Fluxframe/";
        } else {
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relativePath = Environment.DIRECTORY_DOWNLOADS + "/Fluxframe/";
        }

        Uri uri = null;
        String name = job.safeName;
        IOException failure = null;
        for (int attempt = 0; attempt < 20 && uri == null; attempt++) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            try {
                uri = resolver.insert(collection, values);
            } catch (Exception e) {
                failure = new IOException("媒体库拒绝创建文件：" + e.getMessage());
            }
            if (uri == null) name = bumpName(job.safeName, attempt + 1);
        }
        if (uri == null) throw failure != null ? failure : new IOException("无法在图库创建视频条目");

        job.mediaUri = uri;
        job.safeName = name;
        job.relativePath = relativePath;
        try {
            OutputStream out = resolver.openOutputStream(uri, "w");
            if (out == null) throw new IOException("图库文件无法打开写入流");
            long copied;
            try {
                copied = copyDownloadedFile(manager, job, out);
            } finally {
                try { out.close(); } catch (Exception ignored) { }
            }
            verifyCopiedBytes(job, copied);
            ContentValues published = new ContentValues();
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, published, null, null);
            job.downloaded = copied;
            verifyPublishedMediaStore(job);
        } catch (Exception e) {
            try { resolver.delete(uri, null, null); } catch (Exception ignored) { }
            job.mediaUri = null;
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("图库发布失败：" + e.getMessage(), e);
        }
    }

    private long copyDownloadedFile(DownloadManager manager, Job job, OutputStream out) throws IOException {
        long copied = 0;
        ParcelFileDescriptor descriptor = manager.openDownloadedFile(job.downloadManagerId);
        if (descriptor == null) throw new IOException("系统下载文件无法打开");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (job.cancelled) throw new IOException("下载已取消");
                if (count == 0) continue;
                out.write(buffer, 0, count);
                copied += count;
            }
            out.flush();
        }
        return copied;
    }

    private static void verifyCopiedBytes(Job job, long copied) throws IOException {
        if (copied <= 0) throw new IOException("视频文件为空，未保存到图库");
        if (job.total > 0 && copied != job.total) {
            throw new IOException("视频文件写入不完整（已写入 " + copied + "/" + job.total + " 字节）");
        }
    }

    private void verifyPublishedMediaStore(Job job) throws IOException {
        ContentResolver resolver = job.app.getContentResolver();
        String[] projection = {
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME
        };
        IOException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try (Cursor cursor = resolver.query(job.mediaUri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int pending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING));
                    long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE));
                    if (pending == 0 && size == job.downloaded && size > 0) {
                        String relative = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH));
                        String displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME));
                        if (relative != null) job.relativePath = relative;
                        if (displayName != null) job.safeName = displayName;
                        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(job.mediaUri, "r")) {
                            if (descriptor != null && descriptor.getStatSize() > 0) return;
                        }
                    }
                }
            } catch (Exception e) {
                last = new IOException("媒体库回查失败：" + e.getMessage(), e);
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
        }
        if (last != null) throw last;
        throw new IOException("系统媒体库未确认保存完成，文件可能不可见，请重试");
    }

    /** Android 9 及以下：从系统下载临时文件复制到公共 DCIM/Pictures 后扫描。 */
    private void publishToLegacyFile(Job job, DownloadManager manager) throws IOException {
        File dir;
        if (isVideo(job)) {
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Fluxframe");
        } else {
            String directory = isImage(job) ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS;
            dir = new File(Environment.getExternalStoragePublicDirectory(directory), "Fluxframe");
        }
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建公共媒体目录");
        File file = new File(dir, job.safeName);
        for (int attempt = 0; file.exists() && attempt < 20; attempt++) {
            file = new File(dir, bumpName(job.safeName, attempt + 1));
        }
        try (OutputStream out = new FileOutputStream(file)) {
            long copied = copyDownloadedFile(manager, job, out);
            verifyCopiedBytes(job, copied);
            job.downloaded = copied;
        } catch (Exception e) {
            if (file.exists()) file.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
        job.destFile = file;
        job.safeName = file.getName();
        job.savedPath = file.getAbsolutePath();
        MediaScannerConnection.scanFile(job.app, new String[]{file.getAbsolutePath()},
                new String[]{mimeOf(job.safeName)}, null);
    }

    private static String bumpName(String name, int n) {
        int ext = name.lastIndexOf('.');
        if (ext > 0) return name.substring(0, ext) + " (" + n + ")" + name.substring(ext);
        return name + " (" + n + ")";
    }

    private static String downloadFailureMessage(int reason) {
        if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) return "手机存储空间不足";
        if (reason == DownloadManager.ERROR_DEVICE_NOT_FOUND) return "手机存储不可用";
        if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS) return "临时文件已存在，请重试";
        if (reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE) return "服务器拒绝下载（HTTP 错误）";
        if (reason == DownloadManager.ERROR_HTTP_DATA_ERROR) return "网络数据错误，下载未完成";
        if (reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS) return "下载地址重定向次数过多";
        return "系统下载失败（" + reason + "）";
    }

    private static String downloadReasonMessage(int reason) {
        if (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK) return "等待网络";
        if (reason == DownloadManager.PAUSED_WAITING_TO_RETRY) return "等待重试";
        if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) return "等待 Wi-Fi";
        if (reason == DownloadManager.PAUSED_UNKNOWN) return "系统暂时暂停";
        return "系统暂时暂停（" + reason + "）";
    }

    private static void failJob(Job job, String message) {
        job.status = "failed";
        job.message = message;
    }

    private void rememberSaved(Job job) {
        pruneRecent();
        SavedFile saved = new SavedFile();
        saved.uri = job.mediaUri;
        saved.path = job.savedPath;
        saved.name = job.safeName;
        saved.mime = mimeOf(job.safeName);
        saved.size = job.downloaded;
        saved.time = System.currentTimeMillis();
        recentDone.put(String.valueOf(job.id), saved);
    }

    private static void pruneRecent() {
        long now = System.currentTimeMillis();
        if (recentDone.size() > 50) {
            recentDone.entrySet().removeIf(e -> now - e.getValue().time > 30 * 60 * 1000);
        }
    }

    private static String storageLabel(Job job) {
        if (isVideo(job)) return "图库/DCIM/Fluxframe";
        if (isImage(job)) return "图库/Pictures/Fluxframe";
        return "下载文件夹/Fluxframe";
    }

    private void cleanupPartial(Job job, DownloadManager manager) {
        try {
            if (job.mediaUri != null) {
                job.app.getContentResolver().delete(job.mediaUri, null, null);
                job.mediaUri = null;
            }
        } catch (Exception ignored) { }
        try {
            if (job.destFile != null && job.destFile.exists()) job.destFile.delete();
            if (job.stagingFile != null && job.stagingFile.exists()) job.stagingFile.delete();
        } catch (Exception ignored) { }
        try {
            if (manager != null && job.downloadManagerId >= 0) manager.remove(job.downloadManagerId);
        } catch (Exception ignored) { }
    }

    private void postCompletionNotification(Job job) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && job.app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;
            NotificationManager manager = (NotificationManager) job.app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "下载完成", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent open = null;
            if (job.mediaUri != null) {
                open = new Intent(Intent.ACTION_VIEW).setDataAndType(job.mediaUri, mimeOf(job.safeName));
                open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (job.destFile != null && job.destFile.exists()) {
                Uri fileUri = FileProvider.getUriForFile(job.app, job.app.getPackageName() + ".fileprovider", job.destFile);
                open = new Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, mimeOf(job.safeName));
                open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(job.app, CHANNEL_ID) : new Notification.Builder(job.app);
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("下载完成")
                    .setContentText(job.safeName + " 已保存到手机「" + storageLabel(job) + "」")
                    .setAutoCancel(true);
            if (open != null) {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
                builder.setContentIntent(PendingIntent.getActivity(job.app, 0, open, flags));
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
        if (job.message != null && !job.message.isEmpty()) ret.put("message", job.message);
        if (job.status.equals("successful")) {
            String displayPath = job.savedPath;
            if ((displayPath == null || displayPath.isEmpty()) && job.relativePath != null) {
                displayPath = job.relativePath + job.safeName;
            }
            ret.put("path", displayPath == null ? "" : displayPath);
        }
        call.resolve(ret);
        if (job.status.equals("successful") || job.status.equals("failed")) jobs.remove(id);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String sid = call.getString("id");
        if (sid == null) { call.reject("缺少任务 id"); return; }
        try {
            long id = Long.parseLong(sid);
            Job job = jobs.remove(id);
            if (job != null) {
                job.cancelled = true;
                if (job.thread != null) job.thread.interrupt();
                DownloadManager manager = (DownloadManager) job.app.getSystemService(Context.DOWNLOAD_SERVICE);
                cleanupPartial(job, manager);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("取消失败：" + e.getMessage());
        }
    }

    @PluginMethod
    public void openFile(PluginCall call) {
        String sid = call.getString("id");
        if (sid == null) { call.reject("缺少文件记录"); return; }
        SavedFile saved = recentDone.get(sid);
        if (saved == null) {
            call.reject("下载记录已过期，请重新下载后再打开");
            return;
        }
        try {
            openSavedFile(getContext().getApplicationContext(), saved);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("手机上没有能打开此类文件的应用");
        } catch (Exception e) {
            String msg = e.getMessage();
            call.reject("打开失败：" + (msg == null ? e.getClass().getSimpleName() : msg));
        }
    }

    private void openSavedFile(Context app, SavedFile saved) throws Exception {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri target;
        if (saved.uri != null) {
            target = saved.uri;
        } else {
            File file = new File(saved.path);
            if (!file.exists()) throw new FileNotFoundException("文件不存在：" + saved.path);
            target = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", file);
        }
        intent.setDataAndType(target, saved.mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        app.startActivity(intent);
    }
}
