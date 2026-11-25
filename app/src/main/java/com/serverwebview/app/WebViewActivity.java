package com.serverwebview.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.xwalk.core.XWalkActivity;
import org.xwalk.core.XWalkCookieManager;
import org.xwalk.core.XWalkResourceClient;
import org.xwalk.core.XWalkSettings;
import org.xwalk.core.XWalkUIClient;
import org.xwalk.core.XWalkView;

import android.webkit.JavascriptInterface;

/**
 * WebView Activity using Crosswalk XWalkView (bundled Chromium engine).
 * - No dependency on system WebView (works on Android TV)
 * - HTTPS self-signed cert bypass
 * - Auto-login, credential capture, switch server button injection
 * - Network error dialog with retry
 */
public class WebViewActivity extends XWalkActivity {

    public static final String EXTRA_URL = "extra_url";

    private XWalkView xWalkView;
    private ProgressBar progressBar;
    private FrameLayout rootLayout;
    private String pendingUrl;

    private ValueCallback<Uri> fileUploadCallback;
    private boolean autoLoginAttempted = false;

    private boolean pageLoaded = false;
    private boolean errorDialogShown = false;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private static final int LOAD_TIMEOUT_MS = 15000;
    private String currentUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pendingUrl = getIntent().getStringExtra(EXTRA_URL);
        if (pendingUrl == null || pendingUrl.isEmpty()) {
            Toast.makeText(this, "URL无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(createLayout());
    }

    private View createLayout() {
        rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                8
        );
        progressBar.setLayoutParams(pbParams);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        rootLayout.addView(progressBar);

        return rootLayout;
    }

    @Override
    protected void onXWalkReady() {
        xWalkView = new XWalkView(this);
        rootLayout.addView(xWalkView, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        currentUrl = pendingUrl;
        setupXWalkView();
        xWalkView.loadUrl(currentUrl);
        startLoadTimeout();

        try {
            String ver = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            UpdateChecker.check(this, ver, null);
        } catch (Exception e) {
            UpdateChecker.check(this, "v1.0", null);
        }
    }

    @SuppressWarnings("deprecation")
    private void setupXWalkView() {
        XWalkSettings settings = xWalkView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(XWalkSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);

        XWalkCookieManager cookieManager = new XWalkCookieManager();
        cookieManager.setAcceptCookie(true);

        xWalkView.addJavascriptInterface(new Object() {
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

        xWalkView.setResourceClient(new XWalkResourceClient(xWalkView) {
            @Override
            public boolean shouldOverrideUrlLoading(XWalkView view, String url) {
                if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedSslError(XWalkView view, ValueCallback<Boolean> callback, android.net.http.SslError error) {
                callback.onReceiveValue(true);
            }

            @Override
            public void onLoadFinished(XWalkView view, String url) {
                pageLoaded = true;
                cancelLoadTimeout();
                injectLoginScript(view);
            }

            @Override
            public void onReceivedLoadError(XWalkView view, int errorCode, String description, String failingUrl) {
                showErrorDialog();
            }
        });

        xWalkView.setUIClient(new XWalkUIClient(xWalkView) {
            public void onProgressChanged(XWalkView view, int progressInPercent) {
                if (progressInPercent == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(progressInPercent);
                }
            }

            public void openFileChooser(XWalkView view, ValueCallback<Uri> uploadFile, String acceptType, String capture) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = uploadFile;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(Intent.createChooser(intent, "选择文件"), 1000);
            }
        });

        xWalkView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    private void injectLoginScript(XWalkView view) {
        String savedUsername = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .getString(MainActivity.KEY_SAVED_USERNAME, "");
        String savedPassword = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .getString(MainActivity.KEY_SAVED_PASSWORD, "");

        String escUser = savedUsername.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
        String escPass = savedPassword.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");

        boolean shouldAutoLogin = !savedUsername.isEmpty() && !savedPassword.isEmpty() && !autoLoginAttempted;

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
            "function findPass(){return document.querySelector('input[type=\"password\"]');}" +
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
            "function setVal(el,val){" +
                "if(!el) return;" +
                "var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');" +
                "if(s&&s.set){s.set.call(el,val);}else{el.value=val;}" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "}" +
            "function capture(pi){" +
                "var u=findUser(pi);" +
                "if(u&&pi&&u.value&&pi.value){AndroidBridge.saveCredentials(u.value,pi.value);}" +
            "}" +
            "function injectBtn(pi){" +
                "if(document.getElementById('__sw_btn__')) return;" +
                "var sb=findSb(pi);" +
                "var form=pi.closest?pi.closest('form'):null;" +
                "var container=form;" +
                "if(!container){" +
                    "var p=pi.parentNode;" +
                    "for(var i=0;i<4&&p;i++){" +
                        "if(p.offsetHeight>80&&p.offsetWidth>100){container=p;break;}" +
                        "p=p.parentNode;" +
                    "}" +
                    "if(!container) container=pi.parentNode;" +
                "}" +
                "var btn=document.createElement('button');" +
                "btn.id='__sw_btn__';" +
                "btn.type='button';" +
                "btn.textContent='切换服务器';" +
                "var r='6px',fs='14px',pd='10px 16px';" +
                "if(sb){" +
                    "var cs=window.getComputedStyle(sb);" +
                    "r=cs.borderRadius||r;" +
                    "fs=cs.fontSize||fs;" +
                    "pd=cs.padding||pd;" +
                "}" +
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
                "container.appendChild(btn);" +
                "btn.addEventListener('click',function(e){" +
                    "e.preventDefault();e.stopPropagation();" +
                    "AndroidBridge.switchServer();" +
                "});" +
                "btnInjected=true;" +
            "}" +
            "function poll(){" +
                "attempts++;" +
                "var pi=findPass();" +
                "if(!pi){" +
                    "if(attempts>=maxAttempts){clearInterval(pid);window.__sw_polling=false;}" +
                    "return;" +
                "}" +
                "if(!btnInjected){injectBtn(pi);}" +
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
                "var form=pi.closest?pi.closest('form'):null;" +
                "if(form&&!form.__sw_cap){form.__sw_cap=true;form.addEventListener('submit',function(){capture(pi);});}" +
                "var sb=findSb(pi);" +
                "if(sb&&!sb.__sw_cap){sb.__sw_cap=true;sb.addEventListener('click',function(){capture(pi);});}" +
                "if(!pi.__sw_cap){pi.__sw_cap=true;pi.addEventListener('keydown',function(e){if(e.key==='Enter'||e.keyCode===13){capture(pi);}});}" +
                "if(btnInjected&&(!HAS_CREDS||autoLoginDone)){" +
                    "clearInterval(pid);window.__sw_polling=false;" +
                    "if(typeof obs!=='undefined'&&obs){obs.disconnect();}" +
                "}" +
                "if(attempts>=maxAttempts){clearInterval(pid);window.__sw_polling=false;if(typeof obs!=='undefined'&&obs){obs.disconnect();}}" +
            "}" +
            "var pid=setInterval(poll,500);" +
            "poll();" +
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
                        if (xWalkView != null && currentUrl != null) {
                            xWalkView.loadUrl(currentUrl);
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

    private void doSwitchServer() {
        XWalkCookieManager cookieManager = new XWalkCookieManager();
        cookieManager.setAcceptCookie(true);
        finishSwitchServer();
    }

    private void finishSwitchServer() {
        if (xWalkView != null) {
            xWalkView.clearCache(true);
        }
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .edit()
                .remove(MainActivity.KEY_LAST_URL)
                .remove(MainActivity.KEY_SAVED_USERNAME)
                .remove(MainActivity.KEY_SAVED_PASSWORD)
                .apply();
        Toast.makeText(this, "已清除所有数据", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1000 && fileUploadCallback != null) {
            Uri result = (resultCode == Activity.RESULT_OK && data != null) ? data.getData() : null;
            fileUploadCallback.onReceiveValue(result);
            fileUploadCallback = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (xWalkView != null && xWalkView.getNavigationHistory() != null
                    && xWalkView.getNavigationHistory().canGoBack()) {
                try {
                    xWalkView.getNavigationHistory().navigate(
                        org.xwalk.core.XWalkNavigationHistory.Direction.BACKWARD, 1);
                } catch (Exception e) {
                    finish();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (xWalkView != null) xWalkView.pauseTimers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (xWalkView != null) xWalkView.resumeTimers();
    }

    @Override
    protected void onDestroy() {
        cancelLoadTimeout();
        if (xWalkView != null) {
            xWalkView.onDestroy();
        }
        super.onDestroy();
    }
}
