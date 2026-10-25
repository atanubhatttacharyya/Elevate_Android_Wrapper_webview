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
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLException;

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
                + "if(!document.getElementById('elevate-android-header-touch-fix')){var hs=document.createElement('style');hs.id='elevate-android-header-touch-fix';hs.textContent='@media(max-width:600px){body.dashboard-page .elevate-global-app-header{min-height:100px!important;flex-wrap:wrap!important;justify-content:flex-start!important;row-gap:2px!important;padding:4px 10px 6px!important}body.dashboard-page .elevate-global-brand{flex:1 1 100%!important;min-height:54px!important;gap:10px!important}body.dashboard-page .elevate-global-logo{width:53px!important;height:53px!important}body.dashboard-page .elevate-global-actions{flex:1 1 100%!important;min-width:0!important;flex-wrap:wrap!important;justify-content:flex-end!important;gap:6px!important;overflow:visible!important;touch-action:pan-y!important}body.dashboard-page .elevate-global-actions>*{pointer-events:auto!important;touch-action:manipulation!important;flex:0 0 auto!important}}';document.head.appendChild(hs);}"
                + "document.querySelectorAll('.google-slot').forEach(function(slot){var strip=function(){slot.querySelectorAll('iframe').forEach(function(frame){frame.remove();});};strip();if(window.MutationObserver)new MutationObserver(strip).observe(slot,{childList:true,subtree:true});});"
                + "var originalFetch=window.fetch;if(originalFetch){window.fetch=function(input,init){var rawUrl=typeof input==='string'?input:(input&&input.url)||'',method=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();return originalFetch.apply(this,arguments).then(function(response){try{var u=new URL(rawUrl,location.href);if(u.origin===location.origin&&u.pathname==='/logout'&&method==='POST'&&response.ok){response.clone().json().then(function(data){if(data&&data.logged_out===true&&window.ElevateNative)window.ElevateNative.onLogout();}).catch(function(error){console.error('Could not confirm Elevate logout response',error);});}}catch(error){console.error('Could not process Elevate logout response',error);}return response;});};}"
                + "window.ElevateNativeResult={loginVerified:function(credential,profile){try{var f=document.getElementById('google-login-form'),c=document.getElementById('login-credential'),b=document.getElementById('login-submit'),s=document.getElementById('login-google-status');if(c)c.value=credential||'';if(b)b.disabled=!credential;if(s)s.textContent='Gmail verified. Sign in is ready.';var p=document.getElementById('modify-profile-form'),summary=document.getElementById('modify-profile-summary'),empty=document.getElementById('modify-empty-state');if(profile&&p){['username','email','first_name','last_name','phone','pincode'].forEach(function(k){var el=p.elements.namedItem(k);if(el&&profile[k]!=null)el.value=profile[k];});var n=document.getElementById('modify-summary-name'),em=document.getElementById('modify-summary-email'),ph=document.getElementById('modify-summary-phone');if(n)n.textContent=[profile.first_name,profile.last_name].filter(Boolean).join(' ')||profile.username||'';if(em)em.textContent=profile.email||'';if(ph)ph.textContent=profile.phone?'+91 '+profile.phone:'';p.hidden=true;if(summary)summary.hidden=false;if(empty)empty.hidden=true;var edit=document.getElementById('modify-edit-toggle');if(edit)edit.hidden=false;var ms=document.getElementById('modify-status');if(ms)ms.textContent='Account details loaded.';}}catch(e){console.error(e);}},done:function(mode,ok,message,profile){try{"
                + "if(mode==='login'){var s=document.getElementById('login-google-status'),b=document.getElementById('login-submit'),c=document.getElementById('login-credential');if(s)s.textContent=message|| (ok?'Gmail verified. Sign in is ready.':'Google sign-in failed.');if(!ok&&b)b.disabled=true;if(!ok&&c)c.value='';}"
                + "else if(mode==='register'){var e=document.getElementById('reg-error'),s=document.getElementById('reg-email-status');if(e){e.hidden=!!ok;e.textContent=ok?'':(message||'Google verification failed.');}if(s){s.dataset.submissionMessage='true';s.textContent=message|| (ok?'Registration complete.':'Google verification failed.');}}"
                + "else if(mode==='modify'){var s=document.getElementById('modify-status'),f=document.getElementById('modify-profile-form'),sum=document.getElementById('modify-profile-summary'),empty=document.getElementById('modify-empty-state');if(s)s.textContent=message|| (ok?'Account details updated.':'Could not update account.');if(ok&&profile&&f){['username','email','first_name','last_name','phone','pincode'].forEach(function(k){var el=f.elements.namedItem(k);if(el&&profile[k]!=null)el.value=profile[k];});f.hidden=true;if(sum)sum.hidden=false;if(empty)empty.hidden=true;var n=document.getElementById('modify-summary-name'),em=document.getElementById('modify-summary-email'),ph=document.getElementById('modify-summary-phone');if(n)n.textContent=((profile.first_name||'')+' '+(profile.last_name||'')).trim();if(em)em.textContent=profile.email||'';if(ph)ph.textContent=profile.phone||'';}}"
                + "}catch(e){console.error(e);}}};"
                + "document.addEventListener('pointerdown',function(ev){var t=ev.target;var slot=t&&t.closest?t.closest('.google-slot'):null;if(!slot)return;var id=slot.id,mode=id==='google-login-button'?'login':id==='google-register-button'?'register':id==='google-modify-save-button'?'modify':'';if(!mode)return;var payload={};"
                + "if(mode==='login'){var lf=document.getElementById('google-login-form');if(!lf||!lf.reportValidity())return;payload.username=lf.elements.namedItem('username').value.trim();if(!payload.username)return;var lc=document.getElementById('login-credential'),lb=document.getElementById('login-submit'),ls=document.getElementById('login-google-status');if(lc)lc.value='';if(lb)lb.disabled=true;if(ls)ls.textContent='Waiting for Gmail verification…';}"
                + "if(mode==='register'){var rf=document.getElementById('public-reg-form');if(!rf||!rf.reportValidity())return;payload.username=rf.elements.namedItem('username').value.trim();payload.first_name=rf.elements.namedItem('first_name').value.trim();payload.last_name=rf.elements.namedItem('last_name').value.trim();payload.phone=rf.elements.namedItem('phone').value.trim();payload.email=rf.elements.namedItem('email').value.trim();payload.pincode=rf.elements.namedItem('pincode').value.trim();payload.accept_terms=document.getElementById('reg-accept-terms').checked?'1':'0';payload.accept_privacy=document.getElementById('reg-accept-privacy').checked?'1':'0';}"
                + "if(mode==='modify'){var mf=document.getElementById('modify-profile-form'),lf=document.getElementById('google-login-form');if(!mf||mf.hidden||!mf.reportValidity())return;payload.identifier=lf?lf.elements.namedItem('username').value.trim():'';payload.username=mf.elements.namedItem('username').value.trim();payload.email=mf.elements.namedItem('email').value.trim();payload.first_name=mf.elements.namedItem('first_name').value.trim();payload.last_name=mf.elements.namedItem('last_name').value.trim();payload.phone=mf.elements.namedItem('phone').value.trim();payload.pincode=mf.elements.namedItem('pincode').value.trim();if(!payload.identifier){var st=document.getElementById('modify-edit-status');if(st)st.textContent='Enter your User ID, email or mobile number in the sign-in panel first.';return;}}"
                + "ev.preventDefault();ev.stopPropagation();if(ev.stopImmediatePropagation)ev.stopImmediatePropagation();if(window.ElevateNative)window.ElevateNative.startGoogleSignIn(mode,JSON.stringify(payload));},true);})();";
        webView.evaluateJavascript(js, null);
    }

    private class NativeBridge {
        @JavascriptInterface public void onLogout() {
            runOnUiThread(() -> {
                pendingMode = "";
                pendingPayload = new JSONObject();
                googleSignInClient.signOut().addOnCompleteListener(MainActivity.this, task -> {
                    if (!task.isSuccessful()) {
                        Log.e("ElevateAuth", "Google Sign-In state could not be cleared after Elevate logout",
                                task.getException());
                    }
                    clearElevateWebAuthState();
                });
            });
        }

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

    private void clearElevateWebAuthState() {
        runOnUiThread(() -> {
            CookieManager.getInstance().flush();
            if (webView != null) {
                webView.evaluateJavascript(
                        "try{sessionStorage.removeItem('elevate_session_token');"
                                + "localStorage.removeItem('elevate_session_token');"
                                + "window.ELEVATE_SESSION_TOKEN='';}catch(e){console.error(e);}",
                        null);
            }
        });
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
            final String cookie = CookieManager.getInstance().getCookie(BASE_URL);
            network.execute(() -> processGoogleAuth(mode, payload, token, cookie));
        } catch (ApiException ex) {
            String message = ex.getStatusCode() == CommonStatusCodes.DEVELOPER_ERROR
                    ? "Google sign-in configuration error. Register package " + getPackageName()
                            + " and this APK signing certificate SHA-1 in the Android OAuth client: "
                            + getSigningCertificateSha1()
                    : "Google sign-in was cancelled or failed (code " + ex.getStatusCode() + "). Check Google Play Services and the OAuth client configuration, then try again.";
            showNativeResult(pendingMode, false, message, null);
        } catch (JSONException ex) {
            Log.e("ElevateAuth", "Unable to prepare the Google sign-in result", ex);
            showNativeResult(pendingMode, false, "Unable to prepare the Google sign-in result (JSON error). Please retry.", null);
        } catch (RuntimeException ex) {
            showNativeResult(pendingMode, false, runtimeFailureMessage("returning from Google sign-in", ex), null);
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

    private void processGoogleAuth(String mode, JSONObject payload, String credential, String initialCookie) {
        String stage = "preparing the Elevate request";
        try {
            String cookie = initialCookie;
            if ("login".equals(mode)) {
                JSONObject verification = new JSONObject();
                verification.put("identifier", payload.optString("username"));
                verification.put("credential", credential);
                stage = "verifying the Google account with Elevate";
                Response r = postJson(BASE_URL + "/auth/google/modify/verify", verification, cookie);
                cookie = applyResponseCookies(r, cookie);
                JSONObject result = parseJson(r.body);
                JSONObject profile = result.optJSONObject("profile");
                if (r.status >= 200 && r.status < 300 && result.optBoolean("success", false) && profile != null) {
                    final String finalCookie = cookie;
                    setCookiesThen(finalCookie, () -> showNativeLoginVerified(credential, profile));
                } else {
                    showNativeResult(mode, false,
                            result.optString("message", "Google verification failed. Please check the account details and try again."),
                            null);
                }
            } else if ("register".equals(mode)) {
                payload.put("credential", credential);
                stage = "sending the registration request to Elevate";
                Response r = postJson(BASE_URL + "/auth/google/register", payload, cookie);
                cookie = applyResponseCookies(r, cookie);
                JSONObject result = parseJson(r.body);
                boolean ok = r.status >= 200 && r.status < 300 && result.optBoolean("success", false);
                showNativeResult(mode, ok, result.optString("message", ok ? "Registration complete. You are not signed in yet; sign in separately." : "Google verification failed. Please check your details."), null);
            } else if ("modify".equals(mode)) {
                JSONObject verify = new JSONObject();
                verify.put("identifier", payload.optString("identifier"));
                verify.put("credential", credential);
                stage = "verifying the account with Elevate";
                Response vr = postJson(BASE_URL + "/auth/google/modify/verify", verify, cookie);
                cookie = applyResponseCookies(vr, cookie);
                JSONObject vd = parseJson(vr.body);
                if (vr.status < 200 || vr.status >= 300 || !vd.optBoolean("success", false)) {
                    showNativeResult(mode, false, vd.optString("message", "Google verification failed. No changes were saved."), null);
                    return;
                }
                JSONObject update = new JSONObject(payload.toString());
                update.put("credential", credential);
                stage = "saving account changes with Elevate";
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
        } catch (SSLException ex) {
            Log.e("ElevateAuth", "TLS connection to Elevate failed", ex);
            showNativeResult(mode, false, "Secure connection to Elevate failed (TLS). Check the phone's date/time and network, then retry.", null);
        } catch (UnknownHostException ex) {
            Log.e("ElevateAuth", "Elevate hostname could not be resolved", ex);
            showNativeResult(mode, false, "Could not find Elevate on this network. Check internet or DNS, then retry.", null);
        } catch (SocketTimeoutException ex) {
            Log.e("ElevateAuth", "Elevate authentication request timed out", ex);
            showNativeResult(mode, false, "Elevate did not respond in time. Check the connection and retry.", null);
        } catch (IOException ex) {
            Log.e("ElevateAuth", "Elevate authentication network request failed", ex);
            showNativeResult(mode, false, "Elevate could not complete the sign-in request (network I/O). Check the connection and retry.", null);
        } catch (JSONException ex) {
            Log.e("ElevateAuth", "Elevate authentication payload or response was invalid", ex);
            showNativeResult(mode, false, "Elevate returned an unreadable sign-in response. Please retry; if it persists, share this message with support.", null);
        } catch (RuntimeException ex) {
            Log.e("ElevateAuth", "Unexpected native authentication failure", ex);
            showNativeResult(mode, false, runtimeFailureMessage(stage, ex), null);
        }
    }

    private String runtimeFailureMessage(String stage, RuntimeException ex) {
        Log.e("ElevateAuth", "Native authentication failed while " + stage, ex);
        String detail = ex.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = "no additional detail";
        } else {
            detail = detail.replaceAll("[\\r\\n]+", " ").trim();
            if (detail.length() > 160) detail = detail.substring(0, 160) + "...";
        }
        return "Sign-in failed while " + stage + " (" + ex.getClass().getSimpleName()
                + "): " + detail;
    }

    private void showNativeLoginVerified(String credential, JSONObject profile) {
        runOnUiThread(() -> {
            try {
                String js = "window.ElevateNativeResult&&window.ElevateNativeResult.loginVerified("
                        + JSONObject.quote(credential) + "," + profile.toString() + ");";
                webView.evaluateJavascript(js, null);
            } catch (RuntimeException ex) {
                Log.e("ElevateAuth", "Could not return verified Google identity to the sign-in form", ex);
                showNativeResult("login", false, runtimeFailureMessage("preparing the Sign in button", ex), null);
            }
        });
    }

    private static class Response {
        int status;
        String body = "";
        String location;
        List<String> setCookies = new ArrayList<>();
    }

    private Response postForm(String url, JSONObject data, String cookie) throws IOException {
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

    private Response postJson(String url, JSONObject data, String cookie) throws IOException {
        return request(url, data.toString(), "application/json; charset=UTF-8", cookie);
    }

    private Response request(String url, String body, String contentType, String cookie) throws IOException {
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
        runOnUiThread(() -> {
            if (cookieHeader == null || cookieHeader.trim().isEmpty()) {
                action.run();
                return;
            }
            String[] pairs = cookieHeader.split(";\\s*");
            List<String> names = new ArrayList<>();
            for (String pair : pairs) {
                if (pair.trim().isEmpty()) continue;
                int eq = pair.indexOf('=');
                if (eq < 1) continue;
                names.add(pair.trim());
            }
            if (names.isEmpty()) {
                action.run();
                return;
            }
            CookieManager cm = CookieManager.getInstance();
            final int[] remaining = { names.size() };
            for (String pair : names) {
                cm.setCookie(BASE_URL, pair, value -> {
                    boolean complete;
                    synchronized (remaining) {
                        remaining[0]--;
                        complete = remaining[0] == 0;
                    }
                    if (complete) {
                        runOnUiThread(() -> {
                            cm.flush();
                            action.run();
                        });
                    }
                });
            }
        });
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
