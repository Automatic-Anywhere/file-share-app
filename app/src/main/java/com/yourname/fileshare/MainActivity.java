package com.yourname.fileshare;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 8000;
    private static final int FILE_CHOOSER_REQUEST = 1;
    
    private FileShareServer server;
    private WebView webView;
    private LinearLayout selectionLayout;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        selectionLayout = findViewById(R.id.selectionLayout);

        // Xử lý nút Host
        findViewById(R.id.btnHost).setOnClickListener(v -> startHostMode());

        // Xử lý nút Client
        findViewById(R.id.btnClient).setOnClickListener(v -> startClientMode());
    }

    // CHẾ ĐỘ 1: MỞ SERVER (HOST)
    private void startHostMode() {
        try {
            server = new FileShareServer(this, PORT);
            server.start();
            selectionLayout.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            setupWebView();
            
            // Máy Host tự mở Localhost
            webView.loadUrl("http://localhost:" + PORT);
        } catch (IOException e) {
            Toast.makeText(this, "Lỗi khởi động Server", Toast.LENGTH_SHORT).show();
        }
    }

    // CHẾ ĐỘ 2: THAM GIA SERVER (CLIENT)
    private void startClientMode() {
        // Hiện hộp thoại cho phép chọn nhập IP hoặc quét QR
        String[] options = {"Nhập địa chỉ IP", "Quét mã QR"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Tham gia Server")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    showIpInputDialog();
                } else {
                    startQrScan();
                }
            })
            .show();
    }

    private void showIpInputDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("VD: 192.168.1.15:8000");
        input.setPadding(40, 40, 40, 40);

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Nhập IP Server")
            .setView(input)
            .setPositiveButton("Kết nối", (dialog, which) -> {
                String ip = input.getText().toString().trim();
                if (!ip.startsWith("http")) ip = "http://" + ip;
                if (!ip.contains(":")) ip += ":8000";
                connectToServer(ip);
            })
            .setNegativeButton("Hủy", null)
            .show();
    }

    private void startQrScan() {
        new IntentIntegrator(this)
            .setPrompt("Quét mã QR trên màn hình máy Host")
            .setOrientationLocked(false)
            .initiateScan();
    }

    private void connectToServer(String url) {
        selectionLayout.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        setupWebView();
        webView.loadUrl(url);
    }

    // ================= CẤU HÌNH WEBVIEW & TẢI FILE =================
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());

        // Upload file
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Download file
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (filename.endsWith(".bin") || filename.startsWith("download")) {
                    String[] parts = url.split("/");
                    if (parts.length > 0) {
                        filename = android.net.Uri.decode(parts[parts.length - 1]);
                    }
                }

                request.setTitle(filename);
                request.setDescription("Đang tải file...");
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(this, "Đang tải: " + filename, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Lỗi tải file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    // Nhận kết quả Upload file
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Nhận kết quả quét QR
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) {
                String scannedUrl = result.getContents();
                // Nếu QR chỉ chứa IP, tự thêm http://
                if (!scannedUrl.startsWith("http")) {
                    scannedUrl = "http://" + scannedUrl;
                }
                // Nếu chưa có port, thêm :8000
                if (!scannedUrl.matches(".*:\\d+.*")) {
                    scannedUrl += ":8000";
                }
                connectToServer(scannedUrl);
            } else {
                Toast.makeText(this, "Hủy quét QR", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // Nhận kết quả Upload file
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
    }
}
