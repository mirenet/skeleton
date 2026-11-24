package com.webhtml.app;

import android.app.Activity;
import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.Keep;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AllowCors {

    private final Context context;
    private final WebView webView;
    private final String fallbackUserAgent;
    private volatile String cachedWebViewUserAgent = "";

    private static final String FETCH_BRIDGE_JS =
            "(() => {" +
            "  window.webhtml = true;" +
            "  if (!window.fetch || window.__allowCorsInstalled) return;" +
            "  window.__allowCorsInstalled = true;" +
            "  const nativeFetch = window.fetch.bind(window);" +
            "  window.__corsFetchCbs = window.__corsFetchCbs || {};" +
            "  window.fetch = function (input, init = {}) {" +
            "    const url = typeof input === 'string' ? input : (input && input.url) || '';" +
            "    const hasUaValue = /(?:^|#)ua=[^&#]+/.test(url);" +
            "    const canBridge = Boolean(" +
            "      window.webhtml &&" +
            "      window.AllowCORSBridge &&" +
            "      typeof window.AllowCORSBridge.fetchWithFragmentUa === 'function' &&" +
            "      location.protocol === 'file:' &&" +
            "      /^https?:\\/\\//i.test(url) &&" +
            "      hasUaValue" +
            "    );" +
            "    if (!canBridge) {" +
            "      return nativeFetch(input, init);" +
            "    }" +
            "    return new Promise((resolve, reject) => {" +
            "      const id = 'cors_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);" +
            "      window.__corsFetchCbs[id] = (status, bodyPayload, contentType, suggestedFilename) => {" +
            "        delete window.__corsFetchCbs[id];" +
            "        const mime = contentType || 'text/plain; charset=UTF-8';" +
            "        const headers = { 'Content-Type': mime };" +
            "        if (suggestedFilename) headers['X-Suggested-Filename'] = suggestedFilename;" +
            "        resolve(new Response(bodyPayload, {" +
            "          status: status," +
            "          statusText: status >= 200 && status < 300 ? 'OK' : 'Error'," +
            "          headers: headers" +
            "        }));" +
            "      };" +
            "      try {" +
            "        const method = (init && init.method) || (typeof input === 'object' && input && input.method) || 'GET';" +
            "        window.AllowCORSBridge.fetchWithFragmentUa(url, method, id);" +
            "      } catch (err) {" +
            "        delete window.__corsFetchCbs[id];" +
            "        reject(err);" +
            "      }" +
            "    });" +
            "  };" +
            "})();";

    public AllowCors(Context context, WebView webView, String defaultUserAgent) {
        this.context = context;
        this.webView = webView;
        this.fallbackUserAgent = defaultUserAgent != null ? defaultUserAgent : "";
        refreshWebViewUserAgent();
    }

    private void refreshWebViewUserAgent() {
        if (context == null) return;
        try {
            String ua = WebSettings.getDefaultUserAgent(context);
            if (ua != null && !ua.trim().isEmpty()) {
                cachedWebViewUserAgent = ua.trim();
            }
        } catch (Exception ignored) {}
    }

    private String getWebViewUserAgent() {
        if (cachedWebViewUserAgent != null && !cachedWebViewUserAgent.isEmpty()) {
            return cachedWebViewUserAgent;
        }
        return fallbackUserAgent;
    }

    public void register() {
        if (webView != null) {
            refreshWebViewUserAgent();
            webView.addJavascriptInterface(this, "AllowCORSBridge");
        }
    }

    public void injectFetchHook(WebView targetView, String pageUrl) {
        if (targetView != null && pageUrl != null && pageUrl.startsWith("file://")) {
            refreshWebViewUserAgent();
            targetView.evaluateJavascript(FETCH_BRIDGE_JS, null);
        }
    }

    private static String extractFilenameFromContentDisposition(String disposition) {
        if (disposition == null || disposition.trim().isEmpty()) return "";
        try {
            Matcher starMatcher = Pattern.compile("filename\\*=[^']*'[^']*'([^;\\s]+)", Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (starMatcher.find()) {
                return URLDecoder.decode(starMatcher.group(1).replace("\"", "").trim(), "UTF-8");
            }
            Matcher quotedMatcher = Pattern.compile("filename\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (quotedMatcher.find()) {
                return quotedMatcher.group(1).trim();
            }
            Matcher plainMatcher = Pattern.compile("filename\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (plainMatcher.find()) {
                return plainMatcher.group(1).replace("\"", "").trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    @Keep
    @JavascriptInterface
    public void fetchWithFragmentUa(String fullUrl, String method, String callbackId) {
        refreshWebViewUserAgent();

        new Thread(() -> {
            int statusCode = 500;
            String body = "";
            String responseContentType = null;
            String suggestedFilename = "";

            try {
                String[] mainParts = fullUrl.split("#", 2);
                String cleanUrlStr = mainParts[0];
                String fragment = mainParts.length > 1 ? mainParts[1] : "";

                String customUa = "";
                if (fragment.contains("ua=")) {
                    try {
                        String[] uaParts = fragment.split("ua=", 2);
                        if (uaParts.length > 1) {
                            customUa = URLDecoder.decode(uaParts[1].split("&")[0], "UTF-8").trim();
                        }
                    } catch (Exception ignored) {}
                }

                final String finalUa;
                if (!customUa.isEmpty() && customUa.length() < 300) {
                    finalUa = customUa;
                } else {
                    finalUa = getWebViewUserAgent();
                }

                String lowerUa = finalUa.toLowerCase(Locale.ROOT);
                boolean isHtmlMode = lowerUa.contains("mozilla");

                URL url = new URL(cleanUrlStr);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                String reqMethod = (method != null && !method.trim().isEmpty()) ? method.trim().toUpperCase(Locale.ROOT) : "GET";
                connection.setRequestMethod(reqMethod);
                if (!finalUa.isEmpty()) {
                    connection.setRequestProperty("User-Agent", finalUa);
                }

                if (isHtmlMode) {
                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                    connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                    connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
                } else {
                    connection.setRequestProperty("Accept", "application/json, application/ld+json, text/plain, */*");
                }

                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                statusCode = connection.getResponseCode();
                responseContentType = connection.getContentType();
                if (responseContentType == null) {
                    responseContentType = isHtmlMode ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8";
                }

                String disposition = connection.getHeaderField("Content-Disposition");
                suggestedFilename = extractFilenameFromContentDisposition(disposition);

                InputStream inputStream = (statusCode >= 200 && statusCode < 400)
                        ? connection.getInputStream()
                        : connection.getErrorStream();

                if (inputStream != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }

                    byte[] rawBytes = out.toByteArray();
                    String encoding = "UTF-8";
                    if (responseContentType.contains("charset=")) {
                        try {
                            encoding = responseContentType.split("charset=")[1].split(";")[0].trim();
                        } catch (Exception ignored) {}
                    }
                    body = new String(rawBytes, encoding);
                }
            } catch (Exception e) {
                statusCode = 500;
                body = e.getMessage() != null ? e.getMessage() : "AllowCors fetch error";
                responseContentType = "text/plain; charset=utf-8";
                suggestedFilename = "";
            }

            final int finalStatus = statusCode;
            final String finalBody = body;
            final String finalContentType = responseContentType;
            final String finalFilename = suggestedFilename;

            if (context instanceof Activity && webView != null) {
                ((Activity) context).runOnUiThread(() -> {
                    String js = "window.__corsFetchCbs && window.__corsFetchCbs[" + JSONObject.quote(callbackId) + "] && "
                            + "window.__corsFetchCbs[" + JSONObject.quote(callbackId) + "]("
                            + finalStatus + ", " + JSONObject.quote(finalBody) + ", " + JSONObject.quote(finalContentType) + ", " + JSONObject.quote(finalFilename) + ");";
                    webView.evaluateJavascript(js, null);
                });
            }
        }).start();
    }
}
