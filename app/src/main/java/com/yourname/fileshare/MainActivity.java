package com.yourname.fileshare;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 8000;
    private FileShareServer server;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Khởi động Server ngầm
        try {
            server = new FileShareServer(this, PORT);
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Không thể khởi động Server!", Toast.LENGTH_LONG).show();
            return;
        }

        // 2. Cấu hình WebView
        WebView webView = findViewById(R.id.webView);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true); // Cho phép lưu trữ local
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE); // Không dùng cache

        // Bắt sự kiện để mở link trong App, không mở trình duyệt ngoài
        webView.setWebViewClient(new WebViewClient());

        // 3. Mở giao diện web
        webView.loadUrl("http://localhost:" + PORT);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) {
            server.stop();
        }
    }
}
