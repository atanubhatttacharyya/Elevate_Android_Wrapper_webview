package com.elevate.optiontrading;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.view.Gravity;
import android.graphics.Color;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Elevate WebView shell with native Google Identity sign-in.
 * Google authentication is performed by Google Play Services, not inside an embedded WebView.
 */
public class MainActivity extends Activity {
    private static final int GOOGLE_SIGN_IN_REQUEST = 7104;
    private static final String BASE_URL = "https://notrade.myelevateapps.com";

    private WebView webView;
    private ProgressBar progressBar;
    private GoogleSignInClient googleSignInClient;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private String pendingMode = "";
    private JSONObject pendingPayload = new JSONObject();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieManager.getInstance().setAcceptCookie(true);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(false);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(217, 180, 91)));
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(-1, -1);
        root.addView(webView, webParams);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP);
        root.addView(progressBar, progressParams);
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setUserAgentString(settings.getUserAgentString() + " ElevateAndroidWebView/2.0");
        webView.addJavascriptInterface(new NativeBridge(), "ElevateNative");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                installNativeGoogleBridge();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeUrl(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeUrl(url);
            }
        });

        String webClientId = getString(R.string.default_web_client_id).trim();
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(webClientId)
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, options);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(BASE_URL + "/?auth=login");
        }
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private boolean routeUrl(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if ("https".equals(scheme) && (host.equals("notrade.myelevateapps.com") || host.equals("www.notrade.myelevateapps.com"))) return false;
            if ("http".equals(scheme) && host.equals("notrade.myelevateapps.com")) return false;
            if ("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme) || "tel".equals(scheme)) {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }
        } catch (Exception ignored) { }
        return true;
    }

    private void installNativeGoogleBridge() {
        String js = "(function(){if(window.__elevateNativeAuthHook)return;window.__elevateNativeAuthHook=true;"
                + "document.querySelectorAll('.google-slot').forEach(function(slot){var strip=function(){slot.querySelectorAll('iframe').forEach(function(frame){frame.remove();});};strip();if(window.MutationObserver)new MutationObserver(strip).observe(slot,{childList:true,subtree:true});});"
                + "window.ElevateNativeResult={done:function(mode,ok,message,profile){try{"
                + "if(mode==='login'){var s=document.getElementById('login-google-status'),b=document.getElementById('login-submit');if(s)s.textContent=message|| (ok?'Google sign-in completed.':'Google sign-in failed.');if(b)b.disabled=true;}"
                + "else if(mode==='register'){var e=document.getElementById('reg-error'),s=document.getElementById('reg-email-status');if(e){e.hidden=!!ok;e.textContent=ok?'':(message||'Google verification failed.');}if(s){s.dataset.submissionMessage='true';s.textContent=message|| (ok?'Registration complete.':'Google verification failed.');}}"
                + "else if(mode==='modify'){var s=document.getElementById('modify-status'),f=document.getElementById('modify-profile-form'),sum=document.getElementById('modify-profile-summary'),empty=document.getElementById('modify-empty-state');if(s)s.textContent=message|| (ok?'Account details updated.':'Could not update account.');if(ok&&profile&&f){['username','email','first_name','last_name','phone','pincode'].forEach(function(k){var el=f.elements.namedItem(k);if(el&&profile[k]!=null)el.value=profile[k];});f.hidden=true;if(sum)sum.hidden=false;if(empty)empty.hidden=true;var n=document.getElementById('modify-summary-name'),em=document.getElementById('modify-summary-email'),ph=document.getElementById('modify-summary-phone');if(n)n.textContent=((profile.first_name||'')+' '+(profile.last_name||'')).trim();if(em)em.textContent=profile.email||'';if(ph)ph.textContent=profile.phone||'';}}"
                + "}catch(e){console.error(e);}}};"
                + "document.addEventListener('pointerdown',function(ev){var t=ev.target;var slot=t&&t.closest?t.closest('.google-slot'):null;if(!slot)return;var id=slot.id,mode=id==='google-login-button'?'login':id==='google-register-button'?'register':id==='google-modify-save-button'?'modify':'';if(!mode)return;var payload={};"
                + "if(mode==='login'){var lf=document.getElementById('google-login-form');if(!lf||!lf.reportValidity())return;payload.username=lf.elements.namedItem('username').value.trim();if(!payload.username)return;}"
                + "if(mode==='register'){var rf=document.getElementById('public-reg-form');if(!rf||!rf.reportValidity())return;payload.username=rf.elements.namedItem('username').value.trim();payload.first_name=rf.elements.namedItem('first_name').value.trim();payload.last_name=rf.elements.namedItem('last_name').value.trim();payload.phone=rf.elements.namedItem('phone').value.trim();payload.email=rf.elements.namedItem('email').value.trim();payload.pincode=rf.elements.namedItem('pincode').value.trim();payload.accept_terms=document.getElementById('reg-accept-terms').checked?'1':'0';payload.accept_privacy=document.getElementById('reg-accept-privacy').checked?'1':'0';}"
                + "if(mode==='modify'){var mf=document.getElementById('modify-profile-form'),lf=document.getElementById('google-login-form');if(!mf||mf.hidden||!mf.reportValidity())return;payload.identifier=lf?lf.elements.namedItem('username').value.trim():'';payload.username=mf.elements.namedItem('username').value.trim();payload.email=mf.elements.namedItem('email').value.trim();payload.first_name=mf.elements.namedItem('first_name').value.trim();payload.last_name=mf.elements.namedItem('last_name').value.trim();payload.phone=mf.elements.namedItem('phone').value.trim();payload.pincode=mf.elements.namedItem('pincode').value.trim();if(!payload.identifier){var st=document.getElementById('modify-edit-status');if(st)st.textContent='Enter your User ID, email or mobile number in the sign-in panel first.';return;}}"
                + "ev.preventDefault();ev.stopPropagation();if(ev.stopImmediatePropagation)ev.stopImmediatePropagation();if(window.ElevateNative)window.ElevateNative.startGoogleSignIn(mode,JSON.stringify(payload));},true);})();";
        webView.evaluateJavascript(js, null);
    }

    private class NativeBridge {
        @JavascriptInterface public void startGoogleSignIn(String mode, String payloadJson) {
            runOnUiThread(() -> {
                String webClientId = getString(R.string.default_web_client_id).trim();
                if (webClientId.isEmpty() || webClientId.startsWith("REPLACE_WITH_")
                        || !webClientId.endsWith(".apps.googleusercontent.com")) {
                    showNativeResult(mode, false,
                            "Google sign-in is not configured in this build. Add Elevate's Google OAuth Web Client ID to app/src/main/res/values/strings.xml, then rebuild.", null);
                    return;
                }
                try { pendingPayload = new JSONObject(payloadJson == null ? "{}" : payloadJson); }
                catch (Exception ex) { pendingPayload = new JSONObject(); }
                pendingMode = mode == null ? "login" : mode;
                try {
                    startActivityForResult(googleSignInClient.getSignInIntent(), GOOGLE_SIGN_IN_REQUEST);
                } catch (android.content.ActivityNotFoundException ex) {
                    showNativeResult(pendingMode, false,
                            "Google Play Services sign-in is unavailable on this device. Update Google Play Services and try again.", null);
                }
            });
        }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != GOOGLE_SIGN_IN_REQUEST) return;
        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
            if (account == null || account.getIdToken() == null || account.getIdToken().isEmpty()) {
                showNativeResult(pendingMode, false, "Google did not return an ID token. Check the Android OAuth client and Web Client ID configuration.", null);
                return;
            }
            final String token = account.getIdToken();
            final String mode = pendingMode;
            final JSONObject payload = new JSONObject(pendingPayload.toString());
            network.execute(() -> processGoogleAuth(mode, payload, token));
        } catch (ApiException ex) {
            String message = ex.getStatusCode() == CommonStatusCodes.DEVELOPER_ERROR
                    ? "Google sign-in configuration error. Register package " + getPackageName()
                            + " and this APK signing certificate SHA-1 in the Android OAuth client: "
                            + getSigningCertificateSha1()
                    : "Google sign-in was cancelled or failed (code " + ex.getStatusCode() + "). Check Google Play Services and the OAuth client configuration, then try again.";
            showNativeResult(pendingMode, false, message, null);
        } catch (Exception ex) {
            showNativeResult(pendingMode, false, "Google sign-in could not be completed. Please try again.", null);
        }
    }

    @SuppressWarnings("deprecation")
    private String getSigningCertificateSha1() {
        try {
            Signature[] signatures;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                PackageInfo info = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo info = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) return "unavailable";
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray());
            StringBuilder fingerprint = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) fingerprint.append(':');
                fingerprint.append(String.format(java.util.Locale.US, "%02X", digest[i] & 0xff));
            }
            return fingerprint.toString();
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException ex) {
            Log.e("ElevateAuth", "Unable to read APK signing certificate SHA-1", ex);
            return "unavailable";
        }
    }

    private void processGoogleAuth(String mode, JSONObject payload, String credential) {
        try {
            String cookie = CookieManager.getInstance().getCookie(BASE_URL);
            if ("login".equals(mode)) {
                JSONObject form = new JSONObject();
                form.put("username", payload.optString("username"));
                form.put("credential", credential);
                form.put("public_home", "1");
                form.put("next", "/home");
                Response r = postForm(BASE_URL + "/login", form, cookie);
                cookie = applyResponseCookies(r, cookie);
                if (r.location != null && !r.location.isEmpty()) {
                    final String next = absoluteUrl(r.location);
                    final String finalCookie = cookie;
                    setCookiesThen(finalCookie, () -> webView.post(() -> webView.loadUrl(next)));
                } else if (r.status >= 200 && r.status < 300 && r.body != null && r.body.contains("google-login-form")) {
                    final String html = r.body;
                    final String finalCookie = cookie;
                    setCookiesThen(finalCookie, () -> webView.post(() -> webView.loadDataWithBaseURL(BASE_URL + "/", html, "text/html", "UTF-8", null)));
                } else {
                    showNativeResult(mode, false, extractMessage(r.body, "Sign-in failed. Please check your account details and try again."), null);
                }
            } else if ("register".equals(mode)) {
                payload.put("credential", credential);
                Response r = postJson(BASE_URL + "/auth/google/register", payload, cookie);
                cookie = applyResponseCookies(r, cookie);
                JSONObject result = parseJson(r.body);
                boolean ok = r.status >= 200 && r.status < 300 && result.optBoolean("success", false);
                showNativeResult(mode, ok, result.optString("message", ok ? "Registration complete. You are not signed in yet; sign in separately." : "Google verification failed. Please check your details."), null);
            } else if ("modify".equals(mode)) {
                JSONObject verify = new JSONObject();
                verify.put("identifier", payload.optString("identifier"));
                verify.put("credential", credential);
                Response vr = postJson(BASE_URL + "/auth/google/modify/verify", verify, cookie);
                cookie = applyResponseCookies(vr, cookie);
                JSONObject vd = parseJson(vr.body);
                if (vr.status < 200 || vr.status >= 300 || !vd.optBoolean("success", false)) {
                    showNativeResult(mode, false, vd.optString("message", "Google verification failed. No changes were saved."), null);
                    return;
                }
                JSONObject update = new JSONObject(payload.toString());
                update.put("credential", credential);
                Response ur = postJson(BASE_URL + "/auth/google/modify/update", update, cookie);
                cookie = applyResponseCookies(ur, cookie);
                JSONObject ud = parseJson(ur.body);
                boolean ok = ur.status >= 200 && ur.status < 300 && ud.optBoolean("success", false);
                JSONObject profile = vd.optJSONObject("profile");
                if (profile != null) {
                    profile.put("first_name", payload.optString("first_name"));
                    profile.put("last_name", payload.optString("last_name"));
                    profile.put("phone", payload.optString("phone"));
                    profile.put("pincode", payload.optString("pincode"));
                }
                final String finalCookie = cookie;
                JSONObject finalProfile = profile;
                setCookiesThen(finalCookie, () -> showNativeResult(mode, ok, ud.optString("message", ok ? "Account details updated successfully." : "Could not save account details."), finalProfile));
            } else {
                showNativeResult(mode, false, "Unknown Google sign-in action.", null);
            }
        } catch (Exception ex) {
            showNativeResult(mode, false, "Unable to contact Elevate securely. Check your internet connection and try again.", null);
        }
    }

    private static class Response {
        int status;
        String body = "";
        String location;
        List<String> setCookies = new ArrayList<>();
    }

    private Response postForm(String url, JSONObject data, String cookie) throws Exception {
        StringBuilder encoded = new StringBuilder();
        java.util.Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (encoded.length() > 0) encoded.append('&');
            encoded.append(URLEncoder.encode(key, "UTF-8")).append('=')
                    .append(URLEncoder.encode(data.optString(key), "UTF-8"));
        }
        return request(url, encoded.toString(), "application/x-www-form-urlencoded; charset=UTF-8", cookie);
    }

    private Response postJson(String url, JSONObject data, String cookie) throws Exception {
        return request(url, data.toString(), "application/json; charset=UTF-8", cookie);
    }

    private Response request(String url, String body, String contentType, String cookie) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(12000);
        c.setReadTimeout(18000);
        c.setInstanceFollowRedirects(false);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", contentType);
        c.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8");
        c.setRequestProperty("User-Agent", "ElevateAndroidNativeAuth/2.0");
        if (cookie != null && !cookie.trim().isEmpty()) c.setRequestProperty("Cookie", cookie);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        Response r = new Response();
        r.status = c.getResponseCode();
        r.location = c.getHeaderField("Location");
        Map<String, List<String>> headers = c.getHeaderFields();
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie") && e.getValue() != null) r.setCookies.addAll(e.getValue());
            }
        }
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192]; int n;
            while ((n = in.read(chunk)) >= 0) buffer.write(chunk, 0, n);
            in.close(); r.body = buffer.toString("UTF-8");
        }
        c.disconnect();
        return r;
    }

    private String applyResponseCookies(Response r, String current) {
        String combined = current == null ? "" : current;
        for (String header : r.setCookies) {
            String pair = header.split(";", 2)[0].trim();
            if (pair.isEmpty()) continue;
            String name = pair.substring(0, pair.indexOf('=') >= 0 ? pair.indexOf('=') : pair.length()).trim();
            String[] old = combined.split(";\\s*");
            StringBuilder next = new StringBuilder();
            for (String item : old) {
                if (item.trim().isEmpty()) continue;
                int eq = item.indexOf('='); String oldName = (eq >= 0 ? item.substring(0, eq) : item).trim();
                if (!oldName.equals(name)) { if (next.length() > 0) next.append("; "); next.append(item.trim()); }
            }
            if (next.length() > 0) next.append("; "); next.append(pair);
            combined = next.toString();
        }
        return combined;
    }

    private void setCookiesThen(String cookieHeader, Runnable action) {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) { runOnUiThread(action); return; }
        String[] pairs = cookieHeader.split(";\\s*");
        List<String> names = new ArrayList<>();
        for (String pair : pairs) {
            if (pair.trim().isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 1) continue;
            names.add(pair.trim());
        }
        if (names.isEmpty()) { runOnUiThread(action); return; }
        CookieManager cm = CookieManager.getInstance();
        final int[] remaining = { names.size() };
        for (String pair : names) {
            cm.setCookie(BASE_URL, pair, value -> {
                synchronized (remaining) {
                    remaining[0]--;
                    if (remaining[0] == 0) {
                        cm.flush();
                        runOnUiThread(action);
                    }
                }
            });
        }
    }

    private String absoluteUrl(String location) {
        if (location == null || location.isEmpty()) return BASE_URL + "/home";
        if (location.startsWith("http://") || location.startsWith("https://")) return location;
        return BASE_URL + (location.startsWith("/") ? location : "/" + location);
    }

    private JSONObject parseJson(String text) {
        try { return new JSONObject(text == null ? "{}" : text); }
        catch (Exception ex) { return new JSONObject(); }
    }

    private String extractMessage(String html, String fallback) {
        if (html == null || html.isEmpty()) return fallback;
        // Avoid injecting server HTML as JavaScript; return a neutral message if no redirect occurred.
        return fallback;
    }

    private void showNativeResult(String mode, boolean ok, String message, JSONObject profile) {
        runOnUiThread(() -> {
            try {
                if ("login".equals(mode) && !ok) {
                    Toast.makeText(this, message == null || message.isEmpty()
                            ? "Google sign-in failed. Please try again." : message, Toast.LENGTH_LONG).show();
                }
                JSONObject p = profile == null ? new JSONObject() : profile;
                String js = "window.ElevateNativeResult&&window.ElevateNativeResult.done(" + JSONObject.quote(mode) + "," + ok + "," + JSONObject.quote(message == null ? "" : message) + "," + p.toString() + ");";
                webView.evaluateJavascript(js, null);
            } catch (Exception ignored) { }
        });
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        if (webView != null) { webView.removeJavascriptInterface("ElevateNative"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
