package com.fluxframe.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 内网图片管理 App 壳：
 * 启动时自动在局域网内查找图片管理服务（扫描网关同网段主机的常用端口并校验 /api/health），
 * 找到后直接连接；多个结果供选择；也可手动输入服务器地址。选择会被记住，下次秒连。
 */
public class MainActivity extends BridgeActivity {

    private static final String PREFS = "fluxframe_server";
    private static final String KEY_SERVER = "server";
    private static final int[] SCAN_PORTS = {4311, 5173};
    private static final int HEALTH_TIMEOUT_MS = 1500; // 扫描探测单次超时
    private static final int SAVED_TIMEOUT_MS = 4000;  // 已保存地址检测
    private static final int MANUAL_TIMEOUT_MS = 6000; // 手动输入检测

    private SharedPreferences prefs;
    private boolean dialogsUp = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (savedInstanceState == null) {
            startDiscovery();
        }
    }

    // ---------- 连接流程 ----------

    private void startDiscovery() {
        final String saved = prefs.getString(KEY_SERVER, null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            if (saved != null && healthError(saved, SAVED_TIMEOUT_MS) == null) {
                runOnUiThread(() -> connectTo(saved));
                pool.shutdown();
                return;
            }
            final List<String> found = scanLan();
            runOnUiThread(() -> {
                if (found.size() == 1) {
                    connectTo(found.get(0));
                } else if (found.size() > 1) {
                    pickServer(found);
                } else {
                    promptManual(saved);
                }
            });
            pool.shutdown();
        });
    }

    /** 加载远程服务页面并记住地址 */
    private void connectTo(String hostPort) {
        dialogsUp = false;
        prefs.edit().putString(KEY_SERVER, hostPort).apply();
        String url = "http://" + hostPort + "/";
        WebView webView = getBridge().getWebView();
        webView.setWebViewClient(new BridgeWebViewClient(getBridge()) {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame() && !dialogsUp) {
                    runOnUiThread(() -> promptLostConnection(hostPort));
                }
            }
        });
        webView.loadUrl(url);
    }

    /** 健康检查：http://host:port/api/health 返回 200 且含 ok。返回 null 表示正常，否则返回失败原因 */
    private String healthError(String hostPort, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + hostPort + "/api/health");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            if (code != 200) return "服务返回 HTTP " + code;
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line = reader.readLine();
            if (line != null && line.contains("ok")) return null;
            return "服务响应异常（这不是 Fluxframe 服务？）";
        } catch (Exception e) {
            String m = e.getMessage();
            if (m == null) m = e.getClass().getSimpleName();
            if (m.contains("Cleartext")) return "系统禁止明文 HTTP——请卸载后重装最新版 APK";
            if (m.contains("Permission") || m.contains("permission")) return "应用无联网权限——请到系统设置允许联网";
            if (m.contains("timed out") || m.contains("Timeout")) return "连接超时（地址不通、电脑未开机或网络慢）";
            if (m.contains("refused")) return "连接被拒绝（服务未运行或端口不对）";
            if (m.contains("unreachable")) return "网络不可达（手机与电脑不在同一局域网？）";
            return m.length() > 150 ? m.substring(0, 150) : m;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 获取本机局域网 IPv4（优先 192.168.*，其次 10.*、172.16-31.*），无则返回 null */
    private Inet4Address localLanIp() {
        try {
            List<Inet4Address> candidates = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!(addr instanceof Inet4Address)) continue;
                    if (!addr.isSiteLocalAddress()) continue;
                    candidates.add((Inet4Address) addr);
                }
            }
            Collections.sort(candidates, (a, b) -> {
                byte[] ba = a.getAddress();
                byte[] bb = b.getAddress();
                boolean aPrivate = (ba[0] & 0xff) == 192 && (ba[1] & 0xff) == 168;
                boolean bPrivate = (bb[0] & 0xff) == 192 && (bb[1] & 0xff) == 168;
                if (aPrivate != bPrivate) return aPrivate ? -1 : 1;
                return 0;
            });
            return candidates.isEmpty() ? null : candidates.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 扫描局域网：同网段 1..254 × 常用端口，返回所有健康的服务地址 */
    private List<String> scanLan() {
        List<String> results = Collections.synchronizedList(new ArrayList<String>());
        Inet4Address local = localLanIp();
        if (local == null) return results;
        byte[] raw = local.getAddress();
        final String prefix = (raw[0] & 0xff) + "." + (raw[1] & 0xff) + "." + (raw[2] & 0xff) + ".";
        final int selfLast = raw[3] & 0xff;
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i <= 254; i++) {
            final int last = i;
            pool.execute(() -> {
                if (last == selfLast) return;
                String ip = prefix + last;
                for (int port : SCAN_PORTS) {
                    String hostPort = ip + ":" + port;
                    if (healthError(hostPort, HEALTH_TIMEOUT_MS) == null) {
                        results.add(hostPort);
                        break; // 该主机已有一个可用服务
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(45, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return results;
    }

    // ---------- 原生交互对话框 ----------

    private void pickServer(List<String> found) {
        dialogsUp = true;
        final String[] items = found.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("发现多个内网服务")
                .setItems(items, (dialog, which) -> {
                    dialog.dismiss();
                    connectTo(items[which]);
                })
                .setNegativeButton("手动输入", (dialog, which) -> promptManual(null))
                .setOnCancelListener(d -> { dialogsUp = false; finish(); })
                .show();
    }

    private void promptManual(String saved) {
        dialogsUp = true;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);
        TextView hint = new TextView(this);
        hint.setText("输入图片管理服务地址，例如 192.168.1.10:4311");
        hint.setTextColor(Color.parseColor("#8b96ad"));
        hint.setTextSize(13);
        layout.addView(hint);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(saved != null ? saved : "");
        input.setHint("IP:端口");
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        layout.addView(input, inputLp);

        new AlertDialog.Builder(this)
                .setTitle("手动连接内网服务")
                .setView(layout)
                .setPositiveButton("连接", (dialog, which) -> {
                    String value = input.getText().toString().trim().replaceAll("^https?://", "").replaceAll("/+$", "");
                    if (value.isEmpty()) return;
                    dialog.dismiss();
                    testAndConnect(value);
                })
                .setNegativeButton("重新扫描", (dialog, which) -> {
                    dialog.dismiss();
                    startDiscovery();
                })
                .setOnCancelListener(d -> { dialogsUp = false; finish(); })
                .show();
    }

    private void promptLostConnection(String hostPort) {
        dialogsUp = true;
        new AlertDialog.Builder(this)
                .setTitle("无法连接服务")
                .setMessage("连接 " + hostPort + " 失败，服务可能已停止。")
                .setPositiveButton("重试", (dialog, which) -> {
                    dialog.dismiss();
                    dialogsUp = false;
                    connectTo(hostPort);
                })
                .setNegativeButton("重新查找", (dialog, which) -> {
                    dialog.dismiss();
                    startDiscovery();
                })
                .setOnCancelListener(d -> { dialogsUp = false; finish(); })
                .show();
    }

    private void testAndConnect(final String hostPort) {
        final String target = hostPort.contains(":") ? hostPort : hostPort + ":4311";
        final AlertDialog waiting = new AlertDialog.Builder(this)
                .setTitle("正在连接")
                .setMessage(target)
                .setCancelable(false)
                .show();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            final String err = healthError(target, MANUAL_TIMEOUT_MS);
            runOnUiThread(() -> {
                waiting.dismiss();
                if (err == null) {
                    connectTo(target);
                    return;
                }
                dialogsUp = true;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("连接失败")
                        .setMessage("无法访问 " + target + "\n\n原因：" + err
                                + "\n\n提示：手机浏览器能打开的话，请检查本 App 的联网权限（系统设置→应用→内网图片管理）或 Wi-Fi 代理设置。")
                        .setPositiveButton("重试", (d, w) -> {
                            d.dismiss();
                            dialogsUp = false;
                            testAndConnect(target);
                        })
                        .setNegativeButton("跳过检测直接打开", (d, w) -> {
                            d.dismiss();
                            dialogsUp = false;
                            connectTo(target);
                        })
                        .setNeutralButton("重新输入", (d, w) -> {
                            d.dismiss();
                            promptManual(target);
                        })
                        .setOnCancelListener(d -> { dialogsUp = false; finish(); })
                        .show();
            });
            pool.shutdown();
        });
    }
}
