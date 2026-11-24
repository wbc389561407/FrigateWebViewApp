package com.serverwebview.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Main Activity: First screen where the user inputs a server address.
 * Defaults to https://192.168.6.14:8971/
 * On submit, launches WebViewActivity with the URL.
 */
public class MainActivity extends AppCompatActivity {

    private static final String DEFAULT_URL = "https://192.168.6.14:8971/";
    static final String PREF_NAME = "server_webview_prefs";
    static final String KEY_LAST_URL = "last_url";
    static final String KEY_SAVED_USERNAME = "saved_username";
    static final String KEY_SAVED_PASSWORD = "saved_password";

    private EditText urlInput;
    private Button connectButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        }
    }

    private View createMainLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 120, 48, 48);
        layout.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);

        // App title
        TextView titleView = new TextView(this);
        titleView.setText("Frigate");
        titleView.setTextSize(24f);
        titleView.setGravity(android.view.Gravity.CENTER);
        titleView.setPadding(0, 60, 0, 60);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleView.setLayoutParams(titleParams);
        layout.addView(titleView);

        // URL label
        TextView urlLabel = new TextView(this);
        urlLabel.setText("请输入服务器地址：");
        urlLabel.setTextSize(16f);
        urlLabel.setPadding(0, 0, 0, 16);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        urlLabel.setLayoutParams(labelParams);
        layout.addView(urlLabel);

        // URL input field
        urlInput = new EditText(this);
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTextSize(16f);
        urlInput.setSingleLine(true);

        // Load last used URL or default
        String savedUrl = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getString(KEY_LAST_URL, DEFAULT_URL);
        urlInput.setText(savedUrl);
        urlInput.setSelection(savedUrl.length());

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        urlInput.setLayoutParams(inputParams);
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

        // Set click listener
        connectButton.setOnClickListener(v -> connectToServer());

        // Handle Enter key on the input field
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            connectToServer();
            return true;
        });

        return layout;
    }

    private void connectToServer() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }

        // Ensure URL has scheme
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        // Save the URL for next time
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_URL, url)
                .apply();

        // Launch WebView
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra(WebViewActivity.EXTRA_URL, url);
        startActivity(intent);
    }
}
