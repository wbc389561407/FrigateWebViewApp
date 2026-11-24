package com.serverwebview.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Main Activity: First screen where the user inputs a server address.
 * - Auto-skips to WebView if server URL is saved (update check runs in WebViewActivity)
 * - Shows input page with version number, GitHub star link, and update indicator
 */
public class MainActivity extends AppCompatActivity {

    private static final String DEFAULT_URL = "https://192.168.6.14:8971/";
    static final String PREF_NAME = "server_webview_prefs";
    static final String KEY_LAST_URL = "last_url";
    static final String KEY_SAVED_USERNAME = "saved_username";
    static final String KEY_SAVED_PASSWORD = "saved_password";
    static final String KEY_SKIP_UPDATE = "skip_update";
    static final String KEY_LATEST_VERSION = "latest_version";
    static final String KEY_LATEST_VERSION_URL = "latest_version_url";

    private EditText urlInput;
    private Button connectButton;
    private TextView updateIndicator;
    private String currentVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get current app version
        try {
            currentVersion = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            currentVersion = "v1.0";
        }

        // Check if server URL is already saved
        String savedUrl = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getString(KEY_LAST_URL, null);

        if (savedUrl != null && !savedUrl.isEmpty()) {
            // Auto-navigate to saved server URL, skip input page.
            // Update check will be done by WebViewActivity (async, independent).
            Intent intent = new Intent(this, WebViewActivity.class);
            intent.putExtra(WebViewActivity.EXTRA_URL, savedUrl);
            startActivity(intent);
            finish();
        } else {
            // No saved URL, show input page
            setContentView(createMainLayout());
            // Check for updates on this activity (async, independent)
            UpdateChecker.check(this, currentVersion, updateIndicator);
        }
    }

    private View createMainLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 100, 48, 48);
        layout.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);

        // App title
        TextView titleView = new TextView(this);
        titleView.setText("Frigate");
        titleView.setTextSize(28f);
        titleView.setGravity(android.view.Gravity.CENTER);
        titleView.setPadding(0, 40, 0, 8);
        layout.addView(titleView);

        // Version text
        TextView versionView = new TextView(this);
        versionView.setText("当前版本: " + currentVersion);
        versionView.setTextSize(13f);
        versionView.setTextColor(0xFF888888);
        versionView.setGravity(android.view.Gravity.CENTER);
        versionView.setPadding(0, 0, 0, 50);
        layout.addView(versionView);

        // URL label
        TextView urlLabel = new TextView(this);
        urlLabel.setText("请输入服务器地址：");
        urlLabel.setTextSize(16f);
        urlLabel.setPadding(0, 0, 0, 16);
        layout.addView(urlLabel);

        // URL input field
        urlInput = new EditText(this);
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTextSize(16f);
        urlInput.setSingleLine(true);
        String savedUrl = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getString(KEY_LAST_URL, DEFAULT_URL);
        urlInput.setText(savedUrl);
        urlInput.setSelection(savedUrl.length());
        layout.addView(urlInput);

        // Connect button
        connectButton = new Button(this);
        connectButton.setText("连接");
        connectButton.setTextSize(18f);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.topMargin = 32;
        connectButton.setLayoutParams(btnParams);
        layout.addView(connectButton);

        connectButton.setOnClickListener(v -> connectToServer());
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            connectToServer();
            return true;
        });

        // === Bottom section: update indicator + GitHub star ===
        LinearLayout bottomLayout = new LinearLayout(this);
        bottomLayout.setOrientation(LinearLayout.VERTICAL);
        bottomLayout.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bottomParams.topMargin = 80;
        bottomLayout.setLayoutParams(bottomParams);

        // Update indicator (hidden by default, shown if update available)
        updateIndicator = new TextView(this);
        updateIndicator.setVisibility(View.GONE);
        updateIndicator.setTextSize(14f);
        updateIndicator.setTextColor(0xFF1976D2);
        updateIndicator.setPadding(0, 0, 0, 24);
        updateIndicator.setGravity(android.view.Gravity.CENTER);
        bottomLayout.addView(updateIndicator);

        // GitHub star link
        TextView githubLink = new TextView(this);
        SpannableStringBuilder starText = new SpannableStringBuilder();
        starText.append("GitHub 求个 Star ->");
        starText.setSpan(new UnderlineSpan(), 0, starText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        githubLink.setText(starText);
        githubLink.setTextSize(13f);
        githubLink.setTextColor(0xFF666666);
        githubLink.setGravity(android.view.Gravity.CENTER);
        githubLink.setClickable(true);
        githubLink.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/wbc389561407/FrigateWebViewApp"));
            startActivity(browserIntent);
        });
        bottomLayout.addView(githubLink);

        layout.addView(bottomLayout);

        return layout;
    }

    private void connectToServer() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_URL, url)
                .apply();

        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra(WebViewActivity.EXTRA_URL, url);
        startActivity(intent);
    }
}
