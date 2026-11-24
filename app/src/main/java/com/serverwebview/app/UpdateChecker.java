package com.serverwebview.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Update checker — runs independently of the auto-login flow.
 * Uses a static flag so the dialog only shows once per app process.
 */
public class UpdateChecker {

    private static final String GITHUB_REPO = "https://github.com/wbc389561407/FrigateWebViewApp";
    private static final String GITHUB_API_LATEST = "https://api.github.com/repos/wbc389561407/FrigateWebViewApp/releases/latest";

    private static boolean alreadyChecked = false;

    /**
     * Check for updates. Safe to call from multiple activities — only runs once per process.
     * @param activity The activity to show the dialog on (if still alive).
     * @param currentVersion Current app version string (e.g. "v1.1").
     * @param updateIndicator Optional TextView to show the persistent update indicator.
     *                        Pass null if the activity doesn't have one.
     */
    public static void check(Activity activity, String currentVersion, TextView updateIndicator) {
        if (alreadyChecked) return;
        alreadyChecked = true;

        final WeakReference<Activity> activityRef = new WeakReference<>(activity);
        final WeakReference<TextView> indicatorRef = new WeakReference<>(updateIndicator);
        final String version = currentVersion;

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
                String latestTag = json.getString("tag_name");
                String releaseUrl = json.getString("html_url");
                String downloadUrl = releaseUrl;
                if (json.has("assets") && json.getJSONArray("assets").length() > 0) {
                    downloadUrl = json.getJSONArray("assets").getJSONObject(0).getString("browser_download_url");
                }

                // Save latest version info
                SharedPreferences prefs = activityRef.get() != null ?
                        activityRef.get().getSharedPreferences(MainActivity.PREF_NAME, Activity.MODE_PRIVATE) : null;
                if (prefs != null) {
                    prefs.edit()
                            .putString(MainActivity.KEY_LATEST_VERSION, latestTag)
                            .putString(MainActivity.KEY_LATEST_VERSION_URL, downloadUrl)
                            .apply();
                }

                // Compare versions
                if (!isNewerVersion(latestTag, version)) return;

                boolean skipUpdate = prefs != null && prefs.getBoolean(MainActivity.KEY_SKIP_UPDATE, false);

                final String finalLatestTag = latestTag;
                final String finalDownloadUrl = downloadUrl;

                Activity act = activityRef.get();
                if (act == null || act.isFinishing()) return;

                act.runOnUiThread(() -> {
                    Activity a = activityRef.get();
                    if (a == null || a.isFinishing()) return;

                    // Show persistent indicator if available
                    TextView indicator = indicatorRef.get();
                    if (indicator != null) {
                        indicator.setText("有新版本 " + finalLatestTag + " 可用，点击下载");
                        indicator.setVisibility(View.VISIBLE);
                        indicator.setOnClickListener(v -> {
                            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(finalDownloadUrl));
                            a.startActivity(browserIntent);
                        });
                    }

                    // Show dialog if user hasn't opted out
                    if (!skipUpdate) {
                        showUpdateDialog(a, finalLatestTag, version, finalDownloadUrl);
                    }
                });
            } catch (Exception e) {
                // Network error — silently ignore
            }
        }).start();
    }

    private static void showUpdateDialog(Activity activity, String latestVersion, String currentVersion, String downloadUrl) {
        CheckBox checkBox = new CheckBox(activity);
        checkBox.setText("不再提示");

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(50, 30, 50, 10);

        TextView message = new TextView(activity);
        message.setText("发现新版本 " + latestVersion + "\n当前版本 " + currentVersion + "\n\n是否前往下载？");
        message.setTextSize(15f);
        container.addView(message);
        container.addView(checkBox);

        new AlertDialog.Builder(activity)
                .setTitle("发现新版本")
                .setView(container)
                .setPositiveButton("去下载", (DialogInterface dialog, int which) -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                    activity.startActivity(browserIntent);
                })
                .setNegativeButton("稍后", (DialogInterface dialog, int which) -> {
                    if (checkBox.isChecked()) {
                        activity.getSharedPreferences(MainActivity.PREF_NAME, Activity.MODE_PRIVATE)
                                .edit()
                                .putBoolean(MainActivity.KEY_SKIP_UPDATE, true)
                                .apply();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private static boolean isNewerVersion(String latest, String current) {
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
}
