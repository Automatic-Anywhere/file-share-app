package com.yourname.fileshare;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 8000;
    private static final int FILE_CHOOSER_REQUEST = 1;
    
    private FileShareServer server;
    private WebView webView;
    private LinearLayout selectionLayout, webLayout, clientLayout;
    private Button btnBackToolbar;
    private TextView tvTitle;
    private ValueCallback<Uri[]> filePathCallback;

    public static class HistoryItem {
        String name;
        String url;
        HistoryItem(String name, String url) { this.name = name; this.url = url; }
    }

    private List<HistoryItem> historyList = new ArrayList<>();
    private Map<String, Boolean> onlineStatus = new HashMap<>();
    private ArrayAdapter<HistoryItem> historyAdapter;
    private SharedPreferences prefs;

    private Handler pingHandler = new Handler(Looper.getMainLooper());
    private ExecutorService pingExecutor = Executors.newCachedThreadPool();
    private Runnable pingRunnable;
    
    private boolean isClientMode = false;
    private String currentConnectedUrl = "";
    private Handler connectionCheckHandler = new Handler(Looper.getMainLooper());
    private Runnable connectionCheckRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("FileSharePrefs", MODE_PRIVATE);

        webView = findViewById(R.id.webView);
        selectionLayout = findViewById(R.id.selectionLayout);
        webLayout = findViewById(R.id.webLayout);
        clientLayout = findViewById(R.id.clientLayout);
        btnBackToolbar = findViewById(R.id.btnBack);
        tvTitle = findViewById(R.id.tvTitle);

        findViewById(R.id.btnHost).setOnClickListener(v -> startHostMode());
        findViewById(R.id.btnClient).setOnClickListener(v -> showClientMode());

        findViewById(R.id.btnBackToSelection).setOnClickListener(v -> {
            clientLayout.setVisibility(View.GONE);
            selectionLayout.setVisibility(View.VISIBLE);
            pingHandler.removeCallbacks(pingRunnable);
        });

        findViewById(R.id.btnConnectIp).setOnClickListener(v -> {
            EditText et = findViewById(R.id.etIpAddress);
            String ip = et.getText().toString().trim();
            if (ip.isEmpty()) { Toast.makeText(this, "Vui lòng nhập IP", Toast.LENGTH_SHORT).show(); return; }
            if (!ip.startsWith("http")) ip = "http://" + ip;
            if (!ip.contains(":")) ip += ":8000";
            promptServerNameAndConnect(ip, null);
        });

        findViewById(R.id.btnScanQr).setOnClickListener(v -> startQrScan());

        ListView lvHistory = findViewById(R.id.lvHistory);
        loadHistory();
        
        historyAdapter = new ArrayAdapter<HistoryItem>(this, R.layout.item_history, historyList) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) convertView = getLayoutInflater().inflate(R.layout.item_history, parent, false);
                
                HistoryItem item = getItem(position);
                TextView tvName = convertView.findViewById(R.id.tvName);
                TextView tvUrl = convertView.findViewById(R.id.tvUrl);
                Button btnPlay = convertView.findViewById(R.id.btnPlay);
                Button btnEdit = convertView.findViewById(R.id.btnEdit);
                Button btnDelete = convertView.findViewById(R.id.btnDelete);
                View viewStatus = convertView.findViewById(R.id.viewStatus);

                tvName.setText(item.name);
                tvUrl.setText(item.url);

                Boolean isOnline = onlineStatus.get(item.url);
                boolean isActuallyOnline = (isOnline != null && isOnline);

                viewStatus.setBackgroundResource(isActuallyOnline ? R.drawable.circle_green : R.drawable.circle_red);
                
                convertView.setOnClickListener(v -> {
                    if (isActuallyOnline) connectToServer(item.url, item.name);
                    else Toast.makeText(MainActivity.this, "Server đang offline!", Toast.LENGTH_SHORT).show();
                });

                btnPlay.setOnClickListener(v -> {
                    if (isActuallyOnline) connectToServer(item.url, item.name);
                    else Toast.makeText(MainActivity.this, "Server đang offline!", Toast.LENGTH_SHORT).show();
                });

                btnEdit.setOnClickListener(v -> showEditDialog(position));

                btnDelete.setOnClickListener(v -> {
                    historyList.remove(position);
                    saveHistory();
                    notifyDataSetChanged();
                });
                return convertView;
            }
        };
        lvHistory.setAdapter(historyAdapter);

        btnBackToolbar.setOnClickListener(v -> {
            if (isClientMode) disconnectClient();
            else {
                if (server != null) { server.clearAllData(); server.stop(); server = null; }
                webView.loadUrl("about:blank");
                webLayout.setVisibility(View.GONE);
                selectionLayout.setVisibility(View.VISIBLE);
                Toast.makeText(this, "Đã làm sạch dữ liệu", Toast.LENGTH_SHORT).show();
            }
        });

        pingRunnable = new Runnable() {
            @Override
            public void run() {
                pingAllServers();
                pingHandler.postDelayed(this, 3000);
            }
        };

        connectionCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (isClientMode && !currentConnectedUrl.isEmpty()) {
                    pingExecutor.execute(() -> {
                        boolean isOnline = false;
                        try {
                            URI uri = URI.create(currentConnectedUrl);
                            Socket socket = new Socket();
                            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort()), 2000);
                            socket.close();
                            isOnline = true;
                        } catch (Exception e) { isOnline = false; }

                        if (!isOnline) {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "Mất kết nối đến Server!", Toast.LENGTH_LONG).show();
                                disconnectClient();
                            });
                        }
                    });
                }
                connectionCheckHandler.postDelayed(this, 3000);
            }
        };
    }

    private void promptServerNameAndConnect(String url, String existingName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Đặt tên cho Server");
        
        final EditText input = new EditText(this);
        input.setHint("VD: Máy tính của Nam");
        if (existingName != null) input.setText(existingName);
        
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(50, 20, 50, 20);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Tiếp tục", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) name = "Server không tên";
            
            boolean exists = false;
            for (HistoryItem item : historyList) {
                if (item.url.equals(url)) { exists = true; break; }
            }
            if (!exists) {
                historyList.add(0, new HistoryItem(name, url));
                if (historyList.size() > 10) historyList.remove(10);
                saveHistory();
                historyAdapter.notifyDataSetChanged();
            }
            connectToServer(url, name);
        });
        builder.setNegativeButton("Hủy", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showEditDialog(int position) {
        HistoryItem item = historyList.get(position);
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Chỉnh sửa thông tin");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(50, 20, 50, 20);

        EditText inputName = new EditText(this);
        inputName.setHint("Tên thiết bị");
        inputName.setText(item.name);
        container.addView(inputName);

        LinearLayout ipLayout = new LinearLayout(this);
        ipLayout.setOrientation(LinearLayout.HORIZONTAL);
        ipLayout.setPadding(0, 20, 0, 0);
        
        EditText inputIp = new EditText(this);
        inputIp.setHint("Địa chỉ IP");
        inputIp.setText(item.url);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inputIp.setLayoutParams(params);
        ipLayout.addView(inputIp);

        Button btnCopyIp = new Button(this);
        btnCopyIp.setText("Copy");
        btnCopyIp.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("IP", inputIp.getText().toString());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Đã copy IP!", Toast.LENGTH_SHORT).show();
            }
        });
        ipLayout.addView(btnCopyIp);
        
        container.addView(ipLayout);
        builder.setView(container);

        builder.setPositiveButton("Lưu", (dialog, which) -> {
            String newName = inputName.getText().toString().trim();
            String newIp = inputIp.getText().toString().trim();
            if (!newIp.startsWith("http")) newIp = "http://" + newIp;
            if (!newIp.contains(":")) newIp += ":8000";
            
            historyList.set(position, new HistoryItem(newName, newIp));
            saveHistory();
            historyAdapter.notifyDataSetChanged();
        });
        builder.setNegativeButton("Hủy", null);
        builder.show();
    }

    private void disconnectClient() {
        isClientMode = false;
        currentConnectedUrl = "";
        connectionCheckHandler.removeCallbacks(connectionCheckRunnable);
        
        webView.loadUrl("about:blank");
        webLayout.setVisibility(View.GONE);
        clientLayout.setVisibility(View.VISIBLE);
        pingHandler.post(pingRunnable);
    }

    private void pingAllServers() {
        for (HistoryItem item : historyList) {
            pingExecutor.execute(() -> {
                boolean isOnline = false;
                try {
                    URI uri = URI.create(item.url);
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort()), 2000);
                    socket.close();
                    isOnline = true;
                } catch (Exception e) { isOnline = false; }

                boolean finalIsOnline = isOnline;
                runOnUiThread(() -> {
                    onlineStatus.put(item.url, finalIsOnline);
                    historyAdapter.notifyDataSetChanged();
                });
            });
        }
    }

    private void startHostMode() {
        try {
            isClientMode = false;
            btnBackToolbar.setText("⬅️ Đóng Server");
            tvTitle.setText("Máy chủ của bạn");
            
            server = new FileShareServer(this, PORT);
            server.start();
            selectionLayout.setVisibility(View.GONE);
            webLayout.setVisibility(View.VISIBLE);
            setupWebView();
            webView.loadUrl("http://localhost:" + PORT);
        } catch (IOException e) {
            Toast.makeText(this, "Lỗi khởi động Server", Toast.LENGTH_SHORT).show();
        }
    }

    private void showClientMode() {
        selectionLayout.setVisibility(View.GONE);
        clientLayout.setVisibility(View.VISIBLE);
        pingHandler.post(pingRunnable);
    }

    private void startQrScan() {
        new IntentIntegrator(this).setPrompt("Quét mã QR máy Host").setOrientationLocked(false).initiateScan();
    }

    private void connectToServer(String url, String serverName) {
        isClientMode = true;
        currentConnectedUrl = url;
        btnBackToolbar.setText("⬅️ Rời Server");
        
        // CẬP NHẬT TÊN SERVER LÊN THANH TIÊU ĐỀ
        if (serverName != null && !serverName.isEmpty()) {
            tvTitle.setText(serverName);
        } else {
            tvTitle.setText("File Share");
        }
        
        clientLayout.setVisibility(View.GONE);
        webLayout.setVisibility(View.VISIBLE);
        setupWebView();
        webView.loadUrl(url);
        
        pingHandler.removeCallbacks(pingRunnable);
        connectionCheckHandler.removeCallbacks(connectionCheckRunnable);
        connectionCheckHandler.post(connectionCheckRunnable);
    }

    private void loadHistory() {
        String json = prefs.getString("history", "[]");
        try {
            JSONArray array = new JSONArray(json);
            historyList.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj.has("name")) {
                    historyList.add(new HistoryItem(obj.getString("name"), obj.getString("url")));
                } else {
                    String oldUrl = obj.getString("url");
                    historyList.add(new HistoryItem("Server cũ", oldUrl));
                }
            }
        } catch (JSONException e) { e.printStackTrace(); }
    }

    private void saveHistory() {
        JSONArray array = new JSONArray();
        try {
            for (HistoryItem item : historyList) {
                JSONObject obj = new JSONObject();
                obj.put("name", item.name);
                obj.put("url", item.url);
                array.put(obj);
            }
        } catch (JSONException e) { e.printStackTrace(); }
        prefs.edit().putString("history", array.toString()).apply();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, "Không thể kết nối đến Server!", Toast.LENGTH_LONG).show();
                    if (isClientMode) disconnectClient();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try { startActivityForResult(intent, FILE_CHOOSER_REQUEST); } 
                catch (Exception e) { MainActivity.this.filePathCallback = null; return false; }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                
                if (filename.endsWith(".bin") || filename.matches("^\\d+$") || !filename.contains(".")) {
                    String[] parts = url.split("/");
                    if (parts.length > 0) {
                        String lastPart = parts[parts.length - 1];
                        if (lastPart.contains("?")) lastPart = lastPart.split("\\?")[0];
                        filename = android.net.Uri.decode(lastPart);
                        if (!filename.contains(".")) {
                            filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                        }
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
            } catch (Exception e) { Toast.makeText(this, "Lỗi tải file", Toast.LENGTH_LONG).show(); }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) {
                String scannedUrl = result.getContents();
                if (!scannedUrl.startsWith("http")) scannedUrl = "http://" + scannedUrl;
                if (!scannedUrl.matches(".*:\\d+.*")) scannedUrl += ":8000";
                promptServerNameAndConnect(scannedUrl, null);
            }
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
        } else { super.onActivityResult(requestCode, resultCode, data); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
        pingHandler.removeCallbacks(pingRunnable);
        connectionCheckHandler.removeCallbacks(connectionCheckRunnable);
        pingExecutor.shutdown();
    }
}
