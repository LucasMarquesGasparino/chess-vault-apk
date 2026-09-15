package com.chessvault.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public final class MainActivity extends Activity {

    private WebView web;
    private DatabaseHelper db;
    private VaultBridge bridge;
    private static final int REQ_NOTIF = 9001;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1003;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    String msg = e.toString() + "\n" + android.util.Log.getStackTraceString(e);
                    android.util.Log.e("ChessVaultCrash", msg);
                    try {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() {
                                try { Toast.makeText(getApplicationContext(), "Chess Vault crash: " + e.getMessage(), Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                            }
                        });
                    } catch (Exception ignored) {}
                    try {
                        java.io.File c1 = new java.io.File(getExternalFilesDir(null), "chess_crash.log");
                        java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(c1, true));
                        pw.println(new java.util.Date() + " " + msg);
                        pw.close();
                    } catch (Exception ignored) {}
                    try { Thread.sleep(2500); } catch (Exception ignored) {}
                } catch (Exception ignored) {}
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(2);
            }
        });
        getWindow().setStatusBarColor(Color.parseColor("#0a0a0a"));
        getWindow().setNavigationBarColor(Color.parseColor("#0a0a0a"));

        db = new DatabaseHelper(this);
        bridge = new VaultBridge(this, db);
        createNotificationChannels();
        requestNotifPermission();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0a0a0a"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0a0a0a"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        try { web.setLayerType(View.LAYER_TYPE_HARDWARE, null); } catch (Exception ignored) {}

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(web, true);
        } catch (Exception ignored) {}

        web.addJavascriptInterface(bridge, "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }
            private boolean handleUrl(String url) {
                if (url == null) return false;
                if (url.startsWith("file://")) return false;
                if (url.startsWith("https://www.chess.com/") || url.startsWith("https://api.chess.com/")) return false;
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    intent.setType("*/*");
                    String[] mimeTypes = {"application/json", "text/json", "application/octet-stream"};
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(intent, "Selecione o JSON"), FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Erro ao abrir seletor: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/chess-vault/index.html");

        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);

        String enabled = db.getConfig("auto_sync_enabled", "true");
        if ("true".equals(enabled)) scheduleAlarm();

        String openTab = getIntent() != null ? getIntent().getStringExtra("open_tab") : null;
        if (openTab != null) {
            final String tab = openTab;
            web.postDelayed(new Runnable() {
                @Override public void run() {
                    web.evaluateJavascript("window.openTab && window.openTab('" + tab + "')", null);
                }
            }, 1500);
        }
    }

    public void startSyncService(String mode) {
        try {
            Intent svc = new Intent(this, SyncService.class);
            if (mode != null) svc.putExtra(SyncService.EXTRA_SYNC_MODE, mode);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            String msg = "full".equalsIgnoreCase(mode) ? "Iniciando histórico completo..." : "Sincronizando partidas recentes...";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao iniciar sincronização: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void startSyncService() {
        startSyncService(null);
    }

    public void scheduleAlarm() {
        try {
            AlarmReceiver.scheduleExactAlarm(this);
            db.putConfig("auto_sync_enabled", "true");
            String next = db.getConfig("next_alarm_human", "");
            Toast.makeText(this, "Sync 9h e 21h Brasília agendado" + (next.isEmpty() ? "" : " → próximo " + next), Toast.LENGTH_LONG).show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception ignored) {}
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (am != null && !am.canScheduleExactAlarms()) {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao agendar sync: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void cancelAlarm() {
        try {
            AlarmReceiver.cancelAlarm(this);
            db.putConfig("auto_sync_enabled", "false");
            Toast.makeText(this, "Sync automático cancelado", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel("chess_sync") == null) {
                NotificationChannel ch = new NotificationChannel("chess_sync", "Chess Vault Sync", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Sincronização diária");
                nm.createNotificationChannel(ch);
            }
            if (nm.getNotificationChannel("chess_sync_summary") == null) {
                NotificationChannel ch2 = new NotificationChannel("chess_sync_summary", "Chess Vault Resumo", NotificationManager.IMPORTANCE_HIGH);
                ch2.setDescription("Resumo diário com partidas");
                nm.createNotificationChannel(ch2);
            }
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "Notificações ativadas ✓" : "Notificações negadas", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        if (db != null) db.close();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && web != null) {
            web.resumeTimers();
        }
    }
}
