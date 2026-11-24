package com.webhtml.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Keep;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private List<String> externalDomains = new ArrayList<>();
    private String startURL = "";
    private String broAgent = "";
    private boolean portrait = true;
    private boolean screenOn = false;
    private boolean allowZoom = false;
    private boolean forceDark = false;
    private boolean refreshOn = false;
    private boolean scrollBar = true;
    private boolean mobilView = true;
    private boolean background = false;
    private boolean isLongPressBack = false;
    private WebView webView;
    private WebView overlayWebView;
    private MenuControler menuControler;
    private DownloadHelper downloadHelper;
    private AllowCors allowCors;

    private ValueCallback<Uri[]> uploadMessage;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    private final static int LOCATION_PERMISSION_REQUEST_CODE = 100;
    private final static int MEDIA_PERMISSION_REQUEST_CODE = 101;

    private String pendingGeolocationOrigin;
    private GeolocationPermissions.Callback pendingGeolocationCallback;
    private PermissionRequest pendingPermissionRequest;

    private void applyUserAgentPolicy(WebSettings targetSettings, Context ctx) {
        if (targetSettings == null || ctx == null) return;
        if (broAgent != null && !broAgent.trim().isEmpty()) {
            targetSettings.setUserAgentString(broAgent.trim());
        } else {
            targetSettings.setUserAgentString(WebSettings.getDefaultUserAgent(ctx));
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "QueryPermissionsNeeded"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        loadConfigFromInternalStorage();

        if (forceDark) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            );
        }

        super.onCreate(savedInstanceState);

        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (uploadMessage == null) return;
                    Uri[] results = null;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        String dataString = result.getData().getDataString();
                        if (dataString != null) {
                            results = new Uri[]{Uri.parse(dataString)};
                        }
                    }
                    uploadMessage.onReceiveValue(results);
                    uploadMessage = null;
                }
        );

        if (portrait) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        if (screenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. Donji WebView (Glavni sadržaj) - uslovno koristimo KeepAlive ako je background true
        if (background) {
            webView = new KeepAliveWebView(this);
        } else {
            webView = new WebView(this);
        }

        webView.setBackgroundColor(Color.parseColor("#070707"));
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Primena PullRefresh-a ISKLJUČIVO na glavni WebView
        if (refreshOn) {
            PullRefresh pullRefreshHelper = new PullRefresh(this, webView);
            SwipeRefreshLayout refreshLayout = pullRefreshHelper.setup(
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            );
            rootLayout.addView(refreshLayout);
        } else {
            rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }

        // 2. Gornji WebView (Učitava menu.html) - uslovno KeepAlive ako je background true
        if (background) {
            overlayWebView = new KeepAliveWebView(this);
        } else {
            overlayWebView = new WebView(this);
        }

        overlayWebView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlayWebView.setBackgroundColor(Color.TRANSPARENT);
        overlayWebView.getSettings().setJavaScriptEnabled(true);
        overlayWebView.getSettings().setDomStorageEnabled(true);
        overlayWebView.getSettings().setAllowFileAccess(true);
        overlayWebView.setVisibility(View.GONE);

        overlayWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                return launchFileChooser(filePathCallback, fileChooserParams);
            }
        });

        overlayWebView.addJavascriptInterface(new ConfigSaverInterface(this), "AndroidBridgeSaver");

        overlayWebView.addJavascriptInterface(new Object() {
            @Keep
            @JavascriptInterface
            public void closeMenu() {
                runOnUiThread(() -> {
                    if (overlayWebView != null) overlayWebView.setVisibility(View.GONE);
                });
            }
        }, "AndroidMenuBridge");

        overlayWebView.loadUrl("file:///android_asset/menu.html?menu=true");
        rootLayout.addView(overlayWebView);

        setContentView(rootLayout);

        menuControler = new MenuControler(overlayWebView);
        downloadHelper = new DownloadHelper(this);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setSupportMultipleWindows(false);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        webSettings.setSupportZoom(allowZoom);
        webSettings.setBuiltInZoomControls(allowZoom);
        webSettings.setDisplayZoomControls(false);

        boolean setView = !mobilView;
        webSettings.setLoadWithOverviewMode(setView);
        webSettings.setUseWideViewPort(setView);

        webView.setVerticalScrollBarEnabled(scrollBar);
        webView.setHorizontalScrollBarEnabled(scrollBar);

        String defaultWebViewUa = WebSettings.getDefaultUserAgent(this);
        applyUserAgentPolicy(webSettings, this);

        if (forceDark) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, true);
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    && WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                applyLegacyForceDark(webSettings);
            }
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        allowCors = new AllowCors(this, webView, defaultWebViewUa);
        allowCors.register();

        webView.addJavascriptInterface(downloadHelper, "AndroidBridge");
        webView.addJavascriptInterface(new ConfigSaverInterface(this), "AndroidBridgeSaver");

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isLongPressBack) {
                    isLongPressBack = false;
                    return;
                }

                android.webkit.WebBackForwardList history = webView.copyBackForwardList();
                int currentIndex = history.getCurrentIndex();

                if (currentIndex <= 0 || !webView.canGoBack()) {
                    try {
                        Intent serviceIntent = new Intent(MainActivity.this, MediaService.class);
                        stopService(serviceIntent);
                    } catch (Exception ignored) {}

                    finish();
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(0);
                } else {
                    webView.goBack();
                }
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            String rawSuggestedName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
            String extension = "txt";
            if (rawSuggestedName != null && rawSuggestedName.contains(".")) {
                extension = rawSuggestedName.substring(rawSuggestedName.lastIndexOf(".") + 1);
            }
            final String suggestedFileName = "download." + extension;

            if (url.startsWith("blob:") || url.startsWith("data:")) {
                String js = "(function() {" +
                        "  fetch('" + url + "')" +
                        "    .then(res => res.blob())" +
                        "    .then(blob => {" +
                        "      var reader = new FileReader();" +
                        "      reader.onload = function() {" +
                        "        window.AndroidBridge.cacheData(reader.result);" +
                        "      };" +
                        "      reader.readAsDataURL(blob);" +
                        "    }).catch(err => window.AndroidBridge.cacheData('ERROR'));" +
                        "})();";
                webView.evaluateJavascript(js, null);

                webView.postDelayed(() -> DialogHelper.showNativeDownloadDialog(
                        MainActivity.this, suggestedFileName, url, mimetype, true,
                        finalName -> downloadHelper.executeDownloadTask(finalName, url, mimetype, true)
                ), 300);
            } else {
                DialogHelper.showNativeDownloadDialog(
                        MainActivity.this, suggestedFileName, url, mimetype, false,
                        finalName -> downloadHelper.executeDownloadTask(finalName, url, mimetype, false)
                );
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrlLoading(view, request.getUrl().toString());
            }

            /* @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlLoading(view, url);
            } */

            private boolean handleUrlLoading(WebView view, String url) {
                if (isExternalDomain(url)) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        view.getContext().startActivity(intent);
                        return true;
                    } catch (Exception e) {
                        return true;
                    }
                }

                if (url.startsWith("file://") || url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }

                if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        if (intent != null) {
                            intent.addCategory(Intent.CATEGORY_BROWSABLE);
                            try {
                                startActivity(intent);
                                return true;
                            } catch (Exception e) {
                                String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                                if (fallbackUrl != null) {
                                    view.loadUrl(fallbackUrl);
                                    return true;
                                }
                            }
                        }
                    } catch (Exception e) {}
                    return true;
                }

                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    view.getContext().startActivity(intent);
                    return true;
                } catch (Exception e) {
                    return true;
                }
            }

            private boolean isExternalDomain(String url) {
                if (externalDomains == null || externalDomains.isEmpty()) {
                    return false;
                }
                try {
                    Uri uri = Uri.parse(url);
                    String host = uri.getHost();
                    if (host == null) return false;

                    for (String domainEntry : externalDomains) {
                        String cleanDomain = domainEntry.trim().toLowerCase();
                        if (cleanDomain.startsWith("https://")) cleanDomain = cleanDomain.substring(8);
                        if (cleanDomain.startsWith("http://")) cleanDomain = cleanDomain.substring(7);
                        if (cleanDomain.endsWith("/")) cleanDomain = cleanDomain.substring(0, cleanDomain.length() - 1);

                        if (host.equalsIgnoreCase(cleanDomain) || host.toLowerCase().endsWith("." + cleanDomain)) {
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (view == webView && url != null && url.startsWith("file://")) {
                    view.evaluateJavascript("window.webhtml = true;", null);
                    if (allowCors != null) {
                        allowCors.injectFetchHook(view, url);
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if (view == webView) {
                    if (url != null && url.startsWith("file://")) {
                        view.evaluateJavascript("window.webhtml = true;", null);
                        if (allowCors != null) {
                            allowCors.injectFetchHook(view, url);
                        }
                    }

                    if (background) {
                        String antiPauseJs =
                                "(function() {" +
                                "   try {" +
                                "       Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });" +
                                "       Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });" +
                                "       Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });" +
                                "       Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });" +
                                "   } catch(err) {}" +
                                "})();";

                        view.evaluateJavascript(antiPauseJs, null);
                    }
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String urlStr = request.getUrl().toString();

                if (urlStr.endsWith("appConfig.js")) {
                    try {
                        File configFile = new File(getFilesDir(), "appConfig.js");
                        if (configFile.exists()) {
                            InputStream targetStream = new FileInputStream(configFile);
                            return new WebResourceResponse("application/javascript", "UTF-8", targetStream);
                        }
                    } catch (Exception ignored) {}
                }

                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                DialogHelper.showCustomAlert(MainActivity.this, message, result);
                return true;
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
            return BitmapFactory.decodeResource(getResources(), R.drawable.video);
            } // hide webview video placeholder image

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(MainActivity.this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    androidx.core.app.ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION},
                            LOCATION_PERMISSION_REQUEST_CODE);
                    pendingGeolocationOrigin = origin;
                    pendingGeolocationCallback = callback;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                String[] requestedResources = request.getResources();
                boolean needsCamera = false;
                boolean needsAudio = false;

                for (String resource : requestedResources) {
                    if (resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) needsCamera = true;
                    if (resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) needsAudio = true;
                }

                java.util.ArrayList<String> permissionsToRequest = new java.util.ArrayList<>();
                if (needsCamera && androidx.core.content.ContextCompat.checkSelfPermission(MainActivity.this,
                        android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(android.Manifest.permission.CAMERA);
                }
                if (needsAudio && androidx.core.content.ContextCompat.checkSelfPermission(MainActivity.this,
                        android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO);
                }

                if (!permissionsToRequest.isEmpty()) {
                    pendingPermissionRequest = request;
                    androidx.core.app.ActivityCompat.requestPermissions(MainActivity.this,
                            permissionsToRequest.toArray(new String[0]),
                            MEDIA_PERMISSION_REQUEST_CODE);
                } else {
                    request.grant(requestedResources);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                return launchFileChooser(filePathCallback, fileChooserParams);
            }
        });

        if (background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        }

        if (startURL != null && !startURL.trim().isEmpty() && !startURL.startsWith("file:///")) {
            webView.loadUrl(startURL);
        } else {
            webView.loadUrl("file:///android_asset/menu.html");
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) {
                isLongPressBack = false;
                event.startTracking();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            isLongPressBack = true;
            try {
                Intent serviceIntent = new Intent(this, MediaService.class);
                stopService(serviceIntent);
            } catch (Exception ignored) {}

            finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    private boolean launchFileChooser(ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {
        if (uploadMessage != null) {
            uploadMessage.onReceiveValue(null);
            uploadMessage = null;
        }

        uploadMessage = filePathCallback;
        Intent intent = fileChooserParams.createIntent();

        try {
            fileChooserLauncher.launch(intent);
        } catch (Exception e) {
            uploadMessage = null;
            return false;
        }

        return true;
    }

    @SuppressWarnings("deprecation")
    private void applyLegacyForceDark(WebSettings settings) {
        WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (menuControler != null && menuControler.handleTouchEvent(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private void loadConfigFromInternalStorage() {
        File configFile = new File(getFilesDir(), "appConfig.js");

        if (!configFile.exists()) {
            copyAssetToFile("appConfig.js", configFile);
        }

        try (FileInputStream inputStream = new FileInputStream(configFile)) {
            int size = inputStream.available();
            byte[] buffer = new byte[size];
            inputStream.read(buffer);
            String jsContent = new String(buffer, "UTF-8");

            externalDomains = parseArray(jsContent, "external");
            startURL = parseString(jsContent, "startURL", "");
            broAgent = parseString(jsContent, "broAgent", "");
            portrait = parseBoolean(jsContent, "portrait", true);
            screenOn = parseBoolean(jsContent, "screenOn", false);
            allowZoom = parseBoolean(jsContent, "allowZoom", false);
            forceDark = parseBoolean(jsContent, "forceDark", false);
            refreshOn = parseBoolean(jsContent, "refreshOn", false);
            scrollBar = parseBoolean(jsContent, "scrollBar", true);
            mobilView = parseBoolean(jsContent, "mobilView", true);
            background = parseBoolean(jsContent, "background", false);

        } catch (Exception e) {
            copyAssetToFile("appConfig.js", configFile);
            loadConfigFromInternalStorage();
        }
    }

    private void copyAssetToFile(String assetName, File outFile) {
        try {
            File parentDir = outFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (InputStream in = getAssets().open(assetName);
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        } catch (Exception ignored) {}
    }

    @Keep
    public static class ConfigSaverInterface {
        Context mContext;
        ConfigSaverInterface(Context c) { mContext = c; }

        @Keep
        @JavascriptInterface
        public String loadConfigFileRaw() {
            try {
                File configFile = new File(mContext.getFilesDir(), "appConfig.js");
                if (!configFile.exists()) {
                    return "// Fajl ne postoji";
                }
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    byte[] buffer = new byte[(int) configFile.length()];
                    fis.read(buffer);
                    return new String(buffer, "UTF-8");
                }
            } catch (Exception e) {
                return "// Greška pri čitanju: " + e.getMessage();
            }
        }

        @Keep
        @JavascriptInterface
        public void saveConfigFile(String jsContent) {
            try {
                File dir = mContext.getFilesDir();
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File configFile = new File(dir, "appConfig.js");
                try (FileOutputStream fos = new FileOutputStream(configFile)) {
                    fos.write(jsContent.getBytes());
                }
            } catch (Exception ignored) {}
        }

        @Keep
        @JavascriptInterface
        public void clearStartUrl() {
            try {
                File configFile = new File(mContext.getFilesDir(), "appConfig.js");
                if (configFile.exists()) {
                    FileInputStream fis = new FileInputStream(configFile);
                    byte[] buffer = new byte[(int) configFile.length()];
                    fis.read(buffer);
                    fis.close();
                    String content = new String(buffer, "UTF-8");

                    String updated = content.replaceAll("startURL\\s*:\\s*[\"'][^\"']*[\"']", "startURL: \"\"");

                    FileOutputStream fos = new FileOutputStream(configFile);
                    fos.write(updated.getBytes());
                    fos.close();
                }
            } catch (Exception ignored) {}
        }

        @Keep
        @JavascriptInterface
        public void restartApp() {
            try {
                Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(mContext.getPackageName());
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(intent);
                    if (mContext instanceof Activity) {
                        ((Activity) mContext).finish();
                    }
                    System.exit(0);
                }
            } catch (Exception ignored) {}
        }

        @Keep
        @JavascriptInterface
        public void reloadMainApp() {
            if (mContext instanceof MainActivity) {
                ((MainActivity) mContext).runOnUiThread(() -> {
                    MainActivity act = (MainActivity) mContext;
                    if (act.webView != null) {
                        act.webView.loadUrl("file:///android_asset/menu.html");
                    }
                });
            }
        }

        @Keep
        @JavascriptInterface
        public void reloadWebView() {
            if (mContext instanceof MainActivity) {
                ((MainActivity) mContext).runOnUiThread(() -> {
                    MainActivity act = (MainActivity) mContext;
                    if (act.webView != null) {
                        act.loadConfigFromInternalStorage();
                        act.applyUserAgentPolicy(act.webView.getSettings(), act);

                        if (act.startURL != null && !act.startURL.trim().isEmpty() && !act.startURL.startsWith("file:///")) {
                            act.webView.loadUrl(act.startURL);
                        } else {
                            act.webView.clearHistory();
                            act.webView.reload();
                        }
                    }
                });
            }
        }

        @Keep
        @JavascriptInterface
        public void clearAppCacheAndData() {
            if (mContext instanceof MainActivity) {
                ((MainActivity) mContext).runOnUiThread(() -> {
                    MainActivity act = (MainActivity) mContext;
                    try {
                        if (act.webView != null) {
                            act.webView.clearCache(true);
                            act.webView.clearHistory();
                            act.webView.clearFormData();
                            android.webkit.WebStorage.getInstance().deleteAllData();
                        }
                        CookieManager cookieManager = CookieManager.getInstance();
                        cookieManager.removeAllCookies(null);
                        cookieManager.flush();
                    } catch (Exception ignored) {}
                });
            }
        }

        @Keep
        @JavascriptInterface
        public void clearTrackingAndSession(String url) {
            try {
                android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
                String cookies = cookieManager.getCookie(url);
                if (cookies != null) {
                    String[] cookiePairs = cookies.split(";");
                    for (String cookiePair : cookiePairs) {
                        String[] parts = cookiePair.split("=", 2);
                        if (parts.length > 0) {
                            String cookieName = parts[0].trim();
                            String expiredCookie = cookieName + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/";
                            cookieManager.setCookie(url, expiredCookie);
                        }
                    }
                    cookieManager.flush();
                }
            } catch (Exception ignored) {}
        }

        @Keep
        @JavascriptInterface
        public void clearHistory() {
            if (mContext instanceof MainActivity) {
                ((MainActivity) mContext).runOnUiThread(() -> {
                    MainActivity act = (MainActivity) mContext;
                    if (act.webView != null) {
                        act.webView.clearHistory();
                    }
                });
            }
        }
    }

    private boolean parseBoolean(String content, String key, boolean defaultValue) {
        try {
            Pattern pattern = Pattern.compile(key + "\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return Boolean.parseBoolean(matcher.group(1));
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private String parseString(String content, String key, String defaultValue) {
        try {
            Pattern pattern = Pattern.compile(key + "\\s*:\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private List<String> parseArray(String content, String key) {
        List<String> list = new ArrayList<>();
        try {
            Pattern pattern = Pattern.compile(key + "\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                String arrayContent = matcher.group(1).trim();
                if (!arrayContent.isEmpty()) {
                    Pattern itemPattern = Pattern.compile("[\"']([^\"']+)[\"']");
                    Matcher itemMatcher = itemPattern.matcher(arrayContent);
                    while (itemMatcher.find()) {
                        list.add(itemMatcher.group(1));
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (background && !isFinishing()) {
            try {
                Intent serviceIntent = new Intent(this, MediaService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            if (!background) {
                webView.onPause();
            }
        }
        if (overlayWebView != null) overlayWebView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (overlayWebView != null) overlayWebView.onResume();

        if (background) {
            try {
                Intent serviceIntent = new Intent(this, MediaService.class);
                stopService(serviceIntent);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (pendingGeolocationCallback != null && pendingGeolocationOrigin != null) {
                boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
                pendingGeolocationCallback.invoke(pendingGeolocationOrigin, granted, false);
                pendingGeolocationCallback = null;
                pendingGeolocationOrigin = null;
            }
        } else if (requestCode == MEDIA_PERMISSION_REQUEST_CODE) {
            if (pendingPermissionRequest != null) {
                boolean allGranted = true;
                for (int res : grantResults) {
                    if (res != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                } else {
                    pendingPermissionRequest.deny();
                }
                pendingPermissionRequest = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        if (overlayWebView != null) {
            if (overlayWebView.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlayWebView.getParent()).removeView(overlayWebView);
            }
            overlayWebView.removeAllViews();
            overlayWebView.destroy();
            overlayWebView = null;
        }
        super.onDestroy();
    }
}
