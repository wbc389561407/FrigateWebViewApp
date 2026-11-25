package com.serverwebview.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.net.URLDecoder;

/**
 * WebView Activity: Opens the server URL in a full-screen WebView.
 * - Handles self-signed HTTPS certificates (proceeds past SSL errors)
 * - Full browser behavior: JS, cookies, DOM storage, file upload/download, geolocation, etc.
 * - Back button navigates WebView history
 */
public class WebViewActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "extra_url";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView titleText;

    // File chooser callback for <input type="file">
    private ValueCallback<Uri> fileUploadCallback;
    private ValueCallback<Uri[]> fileUploadCallbackLollipop;

    // Track auto-login attempts to avoid infinite loops
    private boolean autoLoginAttempted = false;
    private int autoLoginRetryCount = 0;
    private static final int MAX_AUTO_LOGIN_RETRIES = 3;

    // Network error detection
    private boolean pageLoaded = false;
    private boolean errorDialogShown = false;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private static final int LOAD_TIMEOUT_MS = 15000; // 15 seconds
    private String currentUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createLayout());

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "URL无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Check if WebView component is available (missing on Android TV etc.)
        if (!isWebViewAvailable()) {
            showWebViewMissingDialog();
            return;
        }

        setupWebView();
        currentUrl = url;
        webView.loadUrl(url);
        startLoadTimeout();

        // Check for updates — async, independent of the login/webview flow
        try {
            String ver = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            UpdateChecker.check(this, ver, null);
        } catch (Exception e) {
            UpdateChecker.check(this, "v1.0", null);
        }
    }

    /**
     * Check if a WebView provider is available on the device.
     * Android TV devices often don't have WebView/Chrome pre-installed.
     */
    private boolean isWebViewAvailable() {
        // Method 1: Use official API (Android 8+ / API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (android.webkit.WebView.getCurrentWebViewPackage() != null) {
                    return true;
                }
            } catch (Exception e) {
                // ignore, fall through
            }
        }

        // Method 2: Try to actually create a WebView — if no provider exists, it throws
        try {
            new WebView(this);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Show dialog when WebView is not available.
     * Guides user to install Android System WebView.
     */
    private void showWebViewMissingDialog() {
        new AlertDialog.Builder(this)
                .setTitle("缺少 WebView 组件")
                .setMessage("本应用需要 Android System WebView 才能运行。\n\n" +
                        "解决方法：\n" +
                        "1. 在应用商店搜索「Android System WebView」安装\n" +
                        "2. 或安装 Chrome 浏览器\n" +
                        "3. 或用 adb 安装：\n" +
                        "   adb install webview.apk\n\n" +
                        "点击下方按钮可尝试打开应用商店搜索。")
                .setPositiveButton("去应用商店", (DialogInterface dialog, int which) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=Android System WebView"));
                        startActivity(intent);
                    } catch (Exception e) {
                        // Play Store not available, open browser instead
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=Android%20System%20WebView"));
                            startActivity(intent);
                        } catch (Exception e2) {
                            Toast.makeText(this, "无法打开应用商店，请手动安装 WebView", Toast.LENGTH_LONG).show();
                        }
                    }
                    finish();
                })
                .setNegativeButton("退出", (DialogInterface dialog, int which) -> {
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private View createLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // WebView
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Progress bar at top
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                8
        );
        pbParams.topMargin = 0;
        progressBar.setLayoutParams(pbParams);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        return root;
    }

    @SuppressWarnings("deprecation")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();

        // === JavaScript & DOM ===
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // === Display & Zoom ===
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);

        // === File access ===
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // === Media ===
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // === Cache ===
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // === Cookies ===
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // === Miscellaneous ===
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        settings.setSaveFormData(true);
        settings.setSavePassword(true);

        // === JavaScript Interface for credential capture ===
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void saveCredentials(String username, String password) {
                if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
                    getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(MainActivity.KEY_SAVED_USERNAME, username)
                            .putString(MainActivity.KEY_SAVED_PASSWORD, password)
                            .apply();
                }
            }
            @JavascriptInterface
            public void switchServer() {
                runOnUiThread(() -> doSwitchServer());
            }
        }, "AndroidBridge");

        // === WebViewClient: handle SSL errors & page navigation ===
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Handle tel:, mailto:, intent: URLs externally
                String url = request.getUrl().toString();
                if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }
                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }
                return false;
            }

            /**
             * KEY: Handle SSL certificate errors.
             * For self-signed certificates on internal servers, we proceed past the error.
             * This is necessary for private/internal HTTPS servers like https://192.168.x.x
             */
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                // Accept all SSL errors (self-signed certs, cert mismatch, etc.)
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // Only show error dialog for main frame failures (not sub-resources)
                if (request.isForMainFrame()) {
                    showErrorDialog();
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // Legacy callback for older APIs — main frame errors only
                showErrorDialog();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                cancelLoadTimeout();
                CookieManager.getInstance().flush();
                injectLoginScript(view);
            }
        });

        // === WebChromeClient: handle file upload, geolocation, alerts, progress ===
        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                // Could update an action bar title here if needed
            }

            // --- Geolocation ---
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            // --- File Upload (Android 5.0+ Lollipop API) ---
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                // Cancel any previous callback
                if (fileUploadCallbackLollipop != null) {
                    fileUploadCallbackLollipop.onReceiveValue(null);
                }
                fileUploadCallbackLollipop = filePathCallback;

                Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentIntent.setType("*/*");
                contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                // Also offer camera capture
                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

                Intent chooserIntent = Intent.createChooser(contentIntent, "选择文件");
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});

                try {
                    startActivityForResult(chooserIntent, 1001);
                } catch (Exception e) {
                    fileUploadCallbackLollipop = null;
                    Toast.makeText(WebViewActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            // --- File Upload (legacy, pre-Lollipop) ---
            @SuppressWarnings("deprecation")
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = uploadMsg;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(Intent.createChooser(intent, "选择文件"), 1000);
            }

            @SuppressWarnings("deprecation")
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {
                openFileChooser(uploadMsg, acceptType, "");
            }

            @SuppressWarnings("deprecation")
            public void openFileChooser(ValueCallback<Uri> uploadMsg) {
                openFileChooser(uploadMsg, "", "");
            }

            // --- Permission requests (camera, mic, etc.) ---
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            // --- Fullscreen video support ---
            private View customView;
            private FrameLayout customViewContainer;
            private WebChromeClient.CustomViewCallback customViewCallback;

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewContainer = new FrameLayout(WebViewActivity.this);
                customViewContainer.setBackgroundColor(Color.BLACK);
                customViewContainer.addView(view, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                ));
                setContentView(customViewContainer);
                customViewCallback = callback;
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                customView.setVisibility(View.GONE);
                if (customViewContainer != null) {
                    customViewContainer.removeView(customView);
                }
                customView = null;
                if (customViewContainer != null) {
                    customViewContainer = null;
                }
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                // Restore original layout
                setContentView(createLayout());
                setupWebView();
            }

            // --- Console messages (for debugging) ---
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                return true;
            }
        });

        // === Download Listener ===
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                handleDownload(url, userAgent, contentDisposition, mimetype);
            }
        });

        // Enable background JS timers
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    /**
     * Inject JavaScript that polls for the login form (handles SPA/React async rendering),
     * then: (1) injects "切换服务器" button below the login button, (2) auto-fills if
     * credentials are saved, (3) captures credentials on manual submit.
     */
    private void injectLoginScript(WebView view) {
        String savedUsername = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .getString(MainActivity.KEY_SAVED_USERNAME, "");
        String savedPassword = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .getString(MainActivity.KEY_SAVED_PASSWORD, "");

        // Escape strings for JavaScript
        String escUser = savedUsername.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
        String escPass = savedPassword.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");

        boolean hasCredentials = !savedUsername.isEmpty() && !savedPassword.isEmpty();
        // Only auto-login on first connection, NOT on subsequent page loads (e.g. after logout)
        boolean shouldAutoLogin = hasCredentials && !autoLoginAttempted;

        // Build the JavaScript. Uses setInterval polling (500ms, up to 60 attempts = 30s)
        // plus MutationObserver for immediate detection of SPA-rendered forms.
        String js =
        "(function(){" +
            "if(window.__sw_polling) return;" +
            "window.__sw_polling=true;" +

            "var AUTO_USER='" + escUser + "';" +
            "var AUTO_PASS='" + escPass + "';" +
            "var HAS_CREDS=" + shouldAutoLogin + ";" +
            "var autoLoginDone=false;" +
            "var btnInjected=false;" +
            "var attempts=0;" +
            "var maxAttempts=60;" +

            // --- Find password input ---
            "function findPass(){" +
                "return document.querySelector('input[type=\"password\"]');" +
            "}" +

            // --- Find username input ---
            "function findUser(pi){" +
                "var inputs=document.querySelectorAll('input');" +
                "for(var i=0;i<inputs.length;i++){" +
                    "var inp=inputs[i];if(inp===pi) continue;" +
                    "var t=(inp.type||'').toLowerCase();" +
                    "var n=(inp.name||'').toLowerCase();" +
                    "var id=(inp.id||'').toLowerCase();" +
                    "var ph=(inp.placeholder||'').toLowerCase();" +
                    "if(t==='text'||t==='email'||t===''||t==='tel'){" +
                        "if(n.indexOf('user')>=0||n.indexOf('name')>=0||n.indexOf('account')>=0||n.indexOf('email')>=0||n.indexOf('login')>=0||" +
                           "id.indexOf('user')>=0||id.indexOf('name')>=0||id.indexOf('account')>=0||id.indexOf('email')>=0||id.indexOf('login')>=0||" +
                           "ph.indexOf('user')>=0||ph.indexOf('账号')>=0||ph.indexOf('用户')>=0||ph.indexOf('account')>=0||ph.indexOf('邮箱')>=0||ph.indexOf('用户名')>=0){" +
                            "return inp;" +
                        "}" +
                    "}" +
                "}" +
                "for(var i=0;i<inputs.length;i++){" +
                    "if(inputs[i]===pi) break;" +
                    "var t=(inputs[i].type||'').toLowerCase();" +
                    "if(t==='text'||t==='email'||t==='tel'||t==='') return inputs[i];" +
                "}" +
                "return null;" +
            "}" +

            // --- Find submit button (try many selectors) ---
            "function findSb(pi){" +
                "var form=pi.closest?pi.closest('form'):null;" +
                "if(form){" +
                    "var b=form.querySelector('button[type=\"submit\"],input[type=\"submit\"],button:not([type])');" +
                    "if(b) return b;" +
                "}" +
                "var sels='button,input[type=\"submit\"],[role=\"button\"],.login-btn,#login-btn,.submit-btn,.btn,a[href*=\"login\"]';" +
                "var btns=document.querySelectorAll(sels);" +
                "for(var i=0;i<btns.length;i++){" +
                    "var txt=(btns[i].textContent||btns[i].value||'').toLowerCase();" +
                    "if(txt.indexOf('login')>=0||txt.indexOf('登录')>=0||txt.indexOf('sign')>=0||txt.indexOf('提交')>=0||txt.indexOf('确定')>=0||txt.indexOf('submit')>=0) return btns[i];" +
                "}" +
                "return btns.length>0?btns[0]:null;" +
            "}" +

            // --- Set value (React/Vue compatible) ---
            "function setVal(el,val){" +
                "if(!el) return;" +
                "var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');" +
                "if(s&&s.set){s.set.call(el,val);}else{el.value=val;}" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "}" +

            // --- Capture credentials ---
            "function capture(pi){" +
                "var u=findUser(pi);" +
                "if(u&&pi&&u.value&&pi.value){AndroidBridge.saveCredentials(u.value,pi.value);}" +
            "}" +

            // --- Inject "切换服务器" button ---
            "function injectBtn(pi){" +
                "if(document.getElementById('__sw_btn__')) return;" +
                "var sb=findSb(pi);" +
                "var form=pi.closest?pi.closest('form'):null;" +
                // Find the right container to append to (NOT as sibling of submit button)
                "var container=form;" +
                "if(!container){" +
                    // Walk up from password input to find a suitable container
                    "var p=pi.parentNode;" +
                    "for(var i=0;i<4&&p;i++){" +
                        "if(p.offsetHeight>80&&p.offsetWidth>100){container=p;break;}" +
                        "p=p.parentNode;" +
                    "}" +
                    "if(!container) container=pi.parentNode;" +
                "}" +
                // Create button
                "var btn=document.createElement('button');" +
                "btn.id='__sw_btn__';" +
                "btn.type='button';" +
                "btn.textContent='切换服务器';" +
                // Copy only visual props from submit button (NOT width — that breaks flex)
                "var r='6px',fs='14px',pd='10px 16px';" +
                "if(sb){" +
                    "var cs=window.getComputedStyle(sb);" +
                    "r=cs.borderRadius||r;" +
                    "fs=cs.fontSize||fs;" +
                    "pd=cs.padding||pd;" +
                "}" +
                // Fixed width:100% + display:block — never copied from submit button
                "btn.style.cssText=" +
                    "'display:block !important;'" +
                    "+'width:100% !important;'" +
                    "+'margin-top:12px !important;'" +
                    "+'padding:'+pd+' !important;'" +
                    "+'border:1px solid #888 !important;'" +
                    "+'border-radius:'+r+' !important;'" +
                    "+'background:transparent !important;'" +
                    "+'color:#666 !important;'" +
                    "+'font-size:'+fs+' !important;'" +
                    "+'font-family:inherit !important;'" +
                    "+'cursor:pointer !important;'" +
                    "+'-webkit-appearance:none !important;'" +
                    "+'box-sizing:border-box !important;'" +
                    "+'visibility:visible !important;'" +
                    "+'opacity:1 !important;'" +
                    "+'position:relative !important;'" +
                    "+'text-align:center !important;';" +
                // Append to container end — does NOT touch login button's position
                "container.appendChild(btn);" +
                "btn.addEventListener('click',function(e){" +
                    "e.preventDefault();e.stopPropagation();" +
                    "AndroidBridge.switchServer();" +
                "});" +
                "btnInjected=true;" +
            "}" +

            // --- Main poll function ---
            "function poll(){" +
                "attempts++;" +
                "var pi=findPass();" +
                "if(!pi){" +
                    "if(attempts>=maxAttempts){clearInterval(pid);window.__sw_polling=false;}" +
                    "return;" +
                "}" +
                // Login form found
                "if(!btnInjected){injectBtn(pi);}" +
                // Auto-login
                "if(HAS_CREDS&&!autoLoginDone){" +
                    "autoLoginDone=true;" +
                    "var u=findUser(pi);" +
                    "if(u){setVal(u,AUTO_USER);}" +
                    "setVal(pi,AUTO_PASS);" +
                    "setTimeout(function(){" +
                        "var sb=findSb(pi);" +
                        "if(sb){sb.click();}" +
                        "else{var f=pi.closest?pi.closest('form'):null;if(f){f.submit();}}" +
                    "},800);" +
                "}" +
                // Capture listeners (idempotent)
                "var form=pi.closest?pi.closest('form'):null;" +
                "if(form&&!form.__sw_cap){form.__sw_cap=true;form.addEventListener('submit',function(){capture(pi);});}" +
                "var sb=findSb(pi);" +
                "if(sb&&!sb.__sw_cap){sb.__sw_cap=true;sb.addEventListener('click',function(){capture(pi);});}" +
                "if(!pi.__sw_cap){pi.__sw_cap=true;pi.addEventListener('keydown',function(e){if(e.key==='Enter'||e.keyCode===13){capture(pi);}});}" +
                // Stop polling when done
                "if(btnInjected&&(!HAS_CREDS||autoLoginDone)){" +
                    "clearInterval(pid);window.__sw_polling=false;" +
                    "if(typeof obs!=='undefined'&&obs){obs.disconnect();}" +
                "}" +
                "if(attempts>=maxAttempts){clearInterval(pid);window.__sw_polling=false;if(typeof obs!=='undefined'&&obs){obs.disconnect();}}" +
            "}" +

            // Start polling
            "var pid=setInterval(poll,500);" +
            "poll();" +

            // MutationObserver for immediate detection
            "var obs=null;" +
            "if(typeof MutationObserver!=='undefined'){" +
                "obs=new MutationObserver(function(){" +
                    "if(!btnInjected||(HAS_CREDS&&!autoLoginDone)){poll();}" +
                "});" +
                "if(document.body){obs.observe(document.body,{childList:true,subtree:true});}" +
                "setTimeout(function(){if(obs){obs.disconnect();obs=null;}},30000);" +
            "}" +
        "})();";

        view.evaluateJavascript(js, null);

        if (shouldAutoLogin) {
            autoLoginAttempted = true;
        }
    }

    /**
     * Start a timeout timer — if page doesn't finish loading within LOAD_TIMEOUT_MS,
     * show the connection error dialog.
     */
    private void startLoadTimeout() {
        cancelLoadTimeout();
        timeoutRunnable = () -> {
            if (!pageLoaded && !errorDialogShown && !isFinishing()) {
                showErrorDialog();
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, LOAD_TIMEOUT_MS);
    }

    private void cancelLoadTimeout() {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    /**
     * Show a dialog when the server can't be reached.
     * Options: Retry (reload URL) or Switch Server (clear data, go to input page).
     */
    private void showErrorDialog() {
        if (errorDialogShown || isFinishing()) return;
        errorDialogShown = true;
        cancelLoadTimeout();

        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("无法连接服务器")
                    .setMessage("无法连接到 " + currentUrl + "\n请检查网络或服务器地址是否正确。")
                    .setPositiveButton("重试", (DialogInterface dialog, int which) -> {
                        errorDialogShown = false;
                        pageLoaded = false;
                        if (webView != null && currentUrl != null) {
                            webView.loadUrl(currentUrl);
                            startLoadTimeout();
                        }
                    })
                    .setNegativeButton("切换服务器", (DialogInterface dialog, int which) -> {
                        doSwitchServer();
                    })
                    .setCancelable(false)
                    .show();
        });
    }

    /**
     * Switch server: clear all cookies, cache, DOM storage, saved credentials,
     * then return to MainActivity (server address input page).
     */
    private void doSwitchServer() {
        // Clear cookies synchronously (wait for callback before continuing)
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(value -> {
            cookieManager.flush();
            finishSwitchServer();
        });
    }

    private void finishSwitchServer() {
        // Clear WebView cache & history
        if (webView != null) {
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
        }

        // Clear DOM storage (localStorage, sessionStorage, IndexedDB)
        WebStorage.getInstance().deleteAllData();

        // Clear HTTP authentication credentials (HTTP Basic Auth etc.)
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();

        // Clear saved server URL and credentials
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .edit()
                .remove(MainActivity.KEY_LAST_URL)
                .remove(MainActivity.KEY_SAVED_USERNAME)
                .remove(MainActivity.KEY_SAVED_PASSWORD)
                .apply();

        Toast.makeText(this, "已清除所有数据", Toast.LENGTH_SHORT).show();

        // Return to MainActivity (server address input page)
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    /**
     * Handle file downloads using Android DownloadManager
     */
    private void handleDownload(String url, String userAgent, String contentDisposition, String mimetype) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimetype);

            // Extract filename from content disposition
            String fileName = URLDecoder.decode(contentDisposition, "UTF-8");
            if (fileName != null && fileName.contains("filename=")) {
                fileName = fileName.substring(fileName.indexOf("filename=") + 9);
                if (fileName.startsWith("\"")) fileName = fileName.substring(1);
                if (fileName.endsWith("\"")) fileName = fileName.substring(0, fileName.length() - 1);
            }
            if (fileName == null || fileName.isEmpty()) {
                fileName = "download_" + System.currentTimeMillis();
                if (mimetype != null) {
                    String ext = android.webkit.MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(mimetype);
                    if (ext != null) fileName += "." + ext;
                }
            }

            request.setTitle(fileName);
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            // Add cookies to download request
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies);
            }
            request.addRequestHeader("User-Agent", userAgent);

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(this, "开始下载: " + fileName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            // Fallback: open in browser
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(browserIntent);
        }
    }

    /**
     * Handle file chooser results
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1001 && fileUploadCallbackLollipop != null) {
            // Android 5.0+ file chooser
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK) {
                if (data != null) {
                    String dataString = data.getDataString();
                    ClipData clipData = data.getClipData();
                    if (clipData != null) {
                        results = new Uri[clipData.getItemCount()];
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            results[i] = clipData.getItemAt(i).getUri();
                        }
                    } else if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                } else {
                    // Camera photo (no data, camera intent captured the image)
                    // This would need a file URI setup; for now pass null
                }
            }
            fileUploadCallbackLollipop.onReceiveValue(results);
            fileUploadCallbackLollipop = null;

        } else if (requestCode == 1000 && fileUploadCallback != null) {
            // Legacy file chooser
            Uri result = (resultCode == Activity.RESULT_OK && data != null) ? data.getData() : null;
            fileUploadCallback.onReceiveValue(result);
            fileUploadCallback = null;
        }
    }

    /**
     * Back button: navigate WebView history first, then exit
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        cancelLoadTimeout();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
