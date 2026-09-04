package com.fluxframe.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.CookieManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 原生下载桥：把内网直链交给 Android 系统 DownloadManager。
 * 系统下载器进程直连内网服务器拉取文件 → 落盘到系统「下载」文件夹，
 * 状态栏显示系统级进度/完成通知；应用内左下角任务坞通过 progress 轮询显示进度。
 * 会话 Cookie 由页面换取后以明文头传入（WebView httpOnly Cookie 系统下载器读不到）。
 */
@CapacitorPlugin(name = "NativeDownloads")
public class NativeDownloadsPlugin extends Plugin {

    private static final int NOTIFICATION_PERMISSION_REQUEST = 8811;

    private DownloadManager dm() {
        return (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
    }

    @PluginMethod
    public void start(PluginCall call) {
        String url = call.getString("url");
        String filename = call.getString("filename");
        String cookie = call.getString("cookie");
        if (url == null || url.isEmpty() || filename == null || filename.isEmpty()) {
            call.reject("下载地址与文件名不能为空");
            return;
        }
        /* 文件名去掉路径分隔/非法字符，防止写入异常目录 */
        String safeName = filename.replaceAll("[/\\\\:*?\"<>|\\s]+", "_").trim();
        if (safeName.isEmpty()) safeName = "download";
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName);
            request.setTitle(safeName);
            request.setDescription("内网图片管理器");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            long id = dm().enqueue(request);
            maybeRequestNotificationPermission();
            JSObject ret = new JSObject();
            ret.put("id", String.valueOf(id));
            call.resolve(ret);
        } catch (Exception e) {
            String msg = e.getMessage();
            call.reject("无法开始下载：" + (msg == null ? e.getClass().getSimpleName() : msg));
        }
    }

    /** Android 13+ 需要通知权限才会显示系统下载完成通知（不申请也不影响落盘） */
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        Activity activity = getActivity();
        if (activity == null) return;
        if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
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
        try {
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(id);
            Cursor cursor = null;
            try {
                cursor = dm().query(query);
                if (cursor == null || !cursor.moveToFirst()) {
                    JSObject gone = new JSObject();
                    gone.put("status", "gone");
                    call.resolve(gone);
                    return;
                }
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                JSObject ret = new JSObject();
                switch (status) {
                    case DownloadManager.STATUS_PENDING:
                        ret.put("status", "pending");
                        break;
                    case DownloadManager.STATUS_RUNNING:
                        ret.put("status", "running");
                        break;
                    case DownloadManager.STATUS_PAUSED:
                        ret.put("status", "paused");
                        break;
                    case DownloadManager.STATUS_SUCCESSFUL:
                        ret.put("status", "successful");
                        break;
                    default:
                        ret.put("status", "failed");
                        ret.put("message", failureMessage(reason));
                        break;
                }
                ret.put("downloaded", downloaded);
                ret.put("total", total);
                call.resolve(ret);
            } finally {
                if (cursor != null) cursor.close();
            }
        } catch (Exception e) {
            call.reject("查询下载进度失败：" + e.getMessage());
        }
    }

    private String failureMessage(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "手机存储空间不足";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "服务器返回错误（若提示请先登录，请重新登录后再试）";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "链接跳转过多";
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "无法断点续传";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "存储设备不可用";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "同名文件已存在";
            case DownloadManager.ERROR_FILE_ERROR:
                return "文件读写错误";
            case DownloadManager.ERROR_UNKNOWN:
                return "下载被系统中断";
            default:
                return "系统下载失败（代码 " + reason + "）";
        }
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String sid = call.getString("id");
        if (sid == null) { call.reject("缺少任务 id"); return; }
        try {
            long id = Long.parseLong(sid);
            dm().remove(id);
            call.resolve();
        } catch (Exception e) {
            call.reject("取消下载失败：" + e.getMessage());
        }
    }
}
