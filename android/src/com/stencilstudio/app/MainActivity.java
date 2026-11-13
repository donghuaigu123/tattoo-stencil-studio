package com.stencilstudio.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简 WebView 外壳：把 assets/index.html 当作离线应用运行。
 * 提供两个原生能力：
 *   1) 系统文件选择器（onShowFileChooser），支持多选与 TIFF 等浏览器无法直接解码的格式；
 *   2) 把 JS 生成的 JPG / ZIP 保存到系统「下载」目录（分块接收，规避 Binder 事务大小限制）。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final int REQ_PERM = 1002;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private final Map<String, StringBuilder> chunks = new HashMap<String, StringBuilder>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#08090c"));
            getWindow().setNavigationBarColor(Color.parseColor("#08090c"));
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#08090c"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择图片"), REQ_PICK);
                } catch (Exception e) {
                    filePathCallback = null;
                    toast("无法打开文件选择器");
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || filePathCallback == null) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<Uri> picked = new ArrayList<Uri>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    picked.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }

            // content:// 直接交回 WebView 在部分机型上不可读，先复制到应用缓存并保留原文件名
            ArrayList<Uri> local = new ArrayList<Uri>();
            for (int i = 0; i < picked.size(); i++) {
                Uri copy = copyToCache(picked.get(i), i);
                if (copy != null) local.add(copy);
            }
            if (!local.isEmpty()) result = local.toArray(new Uri[local.size()]);
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
    }

    private Uri copyToCache(Uri src, int index) {
        String name = displayName(src);
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() == 0) safe = "image";
        if (!safe.contains(".")) safe = safe + ".jpg";

        File out = new File(getCacheDir(),
                "pick_" + System.currentTimeMillis() + "_" + index + "_" + safe);
        InputStream in = null;
        OutputStream os = null;
        try {
            in = getContentResolver().openInputStream(src);
            if (in == null) return null;
            os = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            return Uri.fromFile(out);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (os != null) os.close(); } catch (Exception ignored) { }
        }
    }

    private String displayName(Uri uri) {
        String name = "image";
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) {
                    String v = c.getString(idx);
                    if (v != null && v.length() > 0) name = v;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (c != null) c.close(); } catch (Exception ignored) { }
        }
        return name;
    }

    private void saveToDownloads(byte[] data, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("MediaStore 不可用");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("无法写入文件");
            os.write(data);
            os.flush();
            os.close();
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, v, null, null);
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.flush();
            fos.close();
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()},
                    new String[]{mime}, null);
        }
    }

    private void toast(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private class Bridge {
        @JavascriptInterface
        public void chunk(String id, int index, String part) {
            synchronized (chunks) {
                StringBuilder sb = chunks.get(id);
                if (sb == null) {
                    sb = new StringBuilder();
                    chunks.put(id, sb);
                }
                sb.append(part);
            }
        }

        @JavascriptInterface
        public void finish(String id, String name, String mime, int count) {
            StringBuilder sb;
            synchronized (chunks) {
                sb = chunks.remove(id);
            }
            if (sb == null || sb.length() == 0) {
                toast("保存失败：数据不完整");
                return;
            }
            try {
                byte[] data = Base64.decode(sb.toString(), Base64.DEFAULT);
                if (data.length == 0) throw new Exception("空文件");
                saveToDownloads(data, name, mime);
                toast("已保存到「下载」：" + name);
            } catch (Exception e) {
                toast("保存失败：" + e.getMessage());
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
