package com.barbamarcada.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import java.util.UUID;

/** Casca Android segura para o painel web Barba Marcada. */
public final class MainActivity extends Activity {
    // O barbeiro entra primeiro no login do painel, não no agendamento público.
    private static final String START_URL = "https://barbamarcada.infinityfree.me/admin/login.php";
    private static final String ALLOWED_HOST = "barbamarcada.infinityfree.me";
    private static final int FILE_CHOOSER_REQUEST = 301;
    private static final int POST_NOTIFICATIONS_REQUEST = 302;
    private static final String PUSH_CHANNEL_ID = "barba_agendamentos";
    private static final String PREFS_NAME = "barba_marcada_preferences";
    private static final String PREF_DEVICE_ID = "push_device_id";
    private static final String PREF_PERMISSION_ASKED = "notification_permission_asked";

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> pendingFileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        createNotificationChannel();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(248, 249, 251));
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setIndeterminate(false);
        progressBar.setVisibility(View.VISIBLE);

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3), android.view.Gravity.TOP);
        root.addView(progressBar, progressParams);
        setContentView(root);

        WebSettings settings = webView.getSettings();
        String originalUserAgent = settings.getUserAgentString();
        if (originalUserAgent != null && !originalUserAgent.contains("BarbaMarcadaAndroid")) {
            settings.setUserAgentString(originalUserAgent + " BarbaMarcadaAndroid/1.3.3");
        }
        settings.setJavaScriptEnabled(true); // Necessário para o painel PHP.
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                if ("https".equalsIgnoreCase(Uri.parse(url).getScheme()) && ALLOWED_HOST.equalsIgnoreCase(Uri.parse(url).getHost())) {
                    webView.evaluateJavascript("window.__BARBA_NATIVE_DEVICE_ID=" + JSONObject.quote(getOrCreateDeviceId()) + ";", null);
                }
                requestNotificationPermissionForAdmin(url);
                syncFirebaseTokenToAdminPage(url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            // Permite escolher fotos de perfil/logo nos formulários que usam input type=file.
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(fileChooserParams.getAcceptTypes().length > 0
                        && fileChooserParams.getAcceptTypes()[0] != null
                        && fileChooserParams.getAcceptTypes()[0].startsWith("image/")
                        ? fileChooserParams.getAcceptTypes()[0] : "image/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "Escolher imagem"), FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException ex) {
                    pendingFileCallback = null;
                    Toast.makeText(MainActivity.this, "Nenhum aplicativo para selecionar arquivos foi encontrado.", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        boolean restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null;
        boolean openedFromNotification = loadTrustedPushUrl(getIntent());
        if (!restored && !openedFromNotification) {
            webView.loadUrl(START_URL);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    PUSH_CHANNEL_ID, "Agendamentos", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Avisos de novos agendamentos e atualizações da agenda.");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 250, 150, 250});
            channel.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private boolean isAdminPage(String rawUrl) {
        if (rawUrl == null) return false;
        Uri uri = Uri.parse(rawUrl);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !ALLOWED_HOST.equalsIgnoreCase(uri.getHost())) return false;
        if (!path.startsWith("/admin/")) return false;
        return !path.endsWith("/login.php") && !path.endsWith("/setup.php")
                && !path.endsWith("/esqueci-senha.php") && !path.endsWith("/resetar-senha.php");
    }

    private void requestNotificationPermissionForAdmin(String rawUrl) {
        if (!isAdminPage(rawUrl) || Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_PERMISSION_ASKED, false)) return;
        prefs.edit().putBoolean(PREF_PERMISSION_ASKED, true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, POST_NOTIFICATIONS_REQUEST);
    }

    private String getOrCreateDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String deviceId = prefs.getString(PREF_DEVICE_ID, null);
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(PREF_DEVICE_ID, deviceId).apply();
        }
        return deviceId;
    }

    /** Publica estado/token somente no contexto da WebView confiável; o PHP registra usando sessão + CSRF. */
    private void publishNativePushState(String token, String error) {
        if (webView == null) return;
        String deviceId = getOrCreateDeviceId();
        String tokenValue = token == null ? "null" : JSONObject.quote(token);
        String errorValue = JSONObject.quote(error == null ? "" : error);
        String js = "(function(){" +
                "window.__BARBA_NATIVE_DEVICE_ID=" + JSONObject.quote(deviceId) + ";" +
                "window.__BARBA_NATIVE_FCM_TOKEN=" + tokenValue + ";" +
                "window.__BARBA_NATIVE_FCM_ERROR=" + errorValue + ";" +
                "window.dispatchEvent(new Event('barba-native-push-token-ready'));" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void syncFirebaseTokenToAdminPage(String currentUrl) {
        if (!isAdminPage(currentUrl)) return;
        if (FirebaseApp.getApps(this).isEmpty()) {
            publishNativePushState(null,
                    "O Firebase não foi inicializado. Verifique se app/google-services.json está no projeto GitHub e se o APK foi recompilado com ele.");
            return;
        }
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (webView == null) return;
                String latestUrl = webView.getUrl();
                if (!isAdminPage(latestUrl)) return;
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().trim().isEmpty()) {
                    Exception cause = task.getException();
                    publishNativePushState(null,
                            "O Firebase não conseguiu gerar o token deste celular. Verifique a internet e a configuração do app." + firebaseErrorDetail(cause));
                    return;
                }
                publishNativePushState(task.getResult(), "");
            });
        } catch (RuntimeException error) {
            publishNativePushState(null,
                    "Falha ao inicializar as notificações Firebase. Confira google-services.json e o pacote com.barbamarcada.app." + firebaseErrorDetail(error));
        }
    }

    /** Fornece uma causa resumida para diagnóstico sem incluir tokens ou credenciais. */
    private String firebaseErrorDetail(Throwable error) {
        if (error == null) return "";
        String message = error.getMessage();
        if (message == null) message = "";
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (message.length() > 240) message = message.substring(0, 240) + "…";
        return " Detalhe: " + error.getClass().getSimpleName() + (message.isEmpty() ? "" : " — " + message);
    }

    private boolean loadTrustedPushUrl(Intent intent) {
        if (webView == null || intent == null) return false;
        String rawUrl = intent.getStringExtra("push_url");
        if (rawUrl == null || rawUrl.isEmpty()) rawUrl = intent.getStringExtra("url");
        if (rawUrl == null || rawUrl.isEmpty()) return false;
        Uri uri = Uri.parse(rawUrl);
        if ("https".equalsIgnoreCase(uri.getScheme()) && ALLOWED_HOST.equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)) {
            webView.loadUrl(uri.toString());
        } else {
            webView.loadUrl(START_URL);
        }
        intent.removeExtra("push_url");
        intent.removeExtra("url");
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadTrustedPushUrl(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == POST_NOTIFICATIONS_REQUEST && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Ative as notificações em Configurações do Android para receber avisos da agenda.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) syncFirebaseTokenToAdminPage(webView.getUrl());
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(11, 11, 12));
        window.setNavigationBarColor(Color.rgb(11, 11, 12));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
    }

    /** Mantém o painel dentro do app; links externos abrem no navegador do aparelho. */
    private boolean routeUrl(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("https".equalsIgnoreCase(scheme) && ALLOWED_HOST.equalsIgnoreCase(host)
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)) return false;
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e) {
                Toast.makeText(this, "Não foi possível abrir este link.", Toast.LENGTH_SHORT).show();
            }
        }
        return true; // Bloqueia file:, intent:, javascript: e esquemas não esperados.
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && pendingFileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                Uri result = data.getData();
                if (result != null) results = new Uri[]{result};
            }
            pendingFileCallback.onReceiveValue(results);
            pendingFileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
