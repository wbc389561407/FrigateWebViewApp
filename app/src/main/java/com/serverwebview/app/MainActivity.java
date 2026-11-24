package com.serverwebview.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Main Activity: First screen where the user inputs a server address.
 * - Auto-skips to WebView if server URL is saved
 * - Shows version number, GitHub star link, and update notification
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

    private static final String GITHUB_REPO = "https://github.com/wbc389561407/FrigateWebViewApp";
    private static final String GITHUB_API_LATEST = "https://api.github.com/repos/wbc389561407/FrigateWebViewApp/releases/latest";

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
            // Auto-navigate to saved server URL, skip input page
            Intent intent = new Intent(this, WebViewActivity.class);
            intent.putExtra(WebViewActivity.EXTRA_URL, savedUrl);
            startActivity(intent);
            finish();
        } else {
            // No saved URL, show input page
            setContentView(createMainLayout());
            checkForUpdates();
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
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleView.setLayoutParams(titleParams);
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
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO));
            startActivity(browserIntent);
        });
        bottomLayout.addView(githubLink);

        layout.addView(bottomLayout);

        return layout;
    }

    /**
     * Check for updates via GitHub API in a background thread.
     * If a newer version is found:
     * - Show update indicator on the page (always)
     * - Show dialog if user hasn't selected "don't remind again"
     */
    private void checkForUpdates() {
        new Thread(() -> {
            try {
                URL url = new URL(GITHUB_API_LATEST);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.connect();

                if (conn.getResponseCode() != 200) return;

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                String latestTag = json.getString("tag_name"); // e.g. "v1.0.0"
                String releaseUrl = json.getString("html_url");

                // Get download URL from assets
                String downloadUrl = releaseUrl;
                if (json.has("assets") && json.getJSONArray("assets").length() > 0) {
                    downloadUrl = json.getJSONArray("assets").getJSONObject(0).getString("browser_download_url");
                }

                // Save latest version info
                getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_LATEST_VERSION, latestTag)
                        .putString(KEY_LATEST_VERSION_URL, downloadUrl)
                        .apply();

                // Compare versions
                if (isNewerVersion(latestTag, currentVersion)) {
                    boolean skipUpdate = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                            .getBoolean(KEY_SKIP_UPDATE, false);

                    final String finalLatestTag = latestTag;
                    final String finalDownloadUrl = downloadUrl;
                    runOnUiThread(() -> {
                        // Always show update indicator on the page
                        showUpdateIndicator(finalLatestTag, finalDownloadUrl);

                        // Show dialog only if user hasn't opted out
                        if (!skipUpdate) {
                            showUpdateDialog(finalLatestTag, finalDownloadUrl);
                        }
                    });
                }
            } catch (Exception e) {
                // Network error or parsing error — silently ignore
            }
        }).start();
    }

    /**
     * Show the "new version available" indicator on the page.
     * Clicking it opens the download page in the browser.
     */
    private void showUpdateIndicator(String latestVersion, String downloadUrl) {
        updateIndicator.setText("有新版本 " + latestVersion + " 可用，点击下载");
        updateIndicator.setVisibility(View.VISIBLE);
        updateIndicator.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            startActivity(browserIntent);
        });
    }

    /**
     * Show update dialog with "don't remind again" checkbox.
     */
    private void showUpdateDialog(String latestVersion, String downloadUrl) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText("不再提示");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(50, 30, 50, 10);

        TextView message = new TextView(this);
        message.setText("发现新版本 " + latestVersion + "\n当前版本 " + currentVersion + "\n\n是否前往下载？");
        message.setTextSize(15f);
        container.addView(message);
        container.addView(checkBox);

        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setView(container)
                .setPositiveButton("去下载", (DialogInterface dialog, int which) -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                    startActivity(browserIntent);
                })
                .setNegativeButton("稍后", (DialogInterface dialog, int which) -> {
                    if (checkBox.isChecked()) {
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                                .edit()
                                .putBoolean(KEY_SKIP_UPDATE, true)
                                .apply();
                    }
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Compare version strings like "v1.0.0" vs "v1.0.1".
     * Returns true if latest > current.
     */
    private boolean isNewerVersion(String latest, String current) {
        try {
            String[] latestParts = latest.replace("v", "").split("\\.");
            String[] currentParts = current.replace("v", "").split("\\.");
            int len = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < len; i++) {
                int l = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                int c = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (l > c) return true;
                if (l < c) return false;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
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
