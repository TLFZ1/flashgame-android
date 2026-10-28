package com.dsh.flashserver;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PORT = 8080;
    private static final int REQ_FILES = 1001;
    private static final int REQ_TREE = 1002;
    private static final String ASSETS_VERSION = ".assets_v30";

    private WebView web;
    private File root;
    private Uri treeUri;

    private static class Task {
        Uri uri;
        File file;
        boolean dir;
        boolean exe;
        Task(Uri u, File f, boolean d) { this(u, f, d, false); }
        Task(Uri u, File f, boolean d, boolean x) { uri = u; file = f; dir = d; exe = x; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = getExternalFilesDir(null);
        if (root == null) root = getFilesDir();
        if (!root.exists()) root.mkdirs();

        final TextView loading = new TextView(this);
        loading.setText("首次启动，正在准备资源…");
        loading.setTextColor(0xFFE6E8EE);
        loading.setBackgroundColor(0xFF12141A);
        loading.setGravity(android.view.Gravity.CENTER);
        setContentView(loading);
        applyImmersive();

        new Thread(new Runnable() {
            public void run() {
                try { installAssets(); } catch (Exception e) { Log.e("flashserver", "installAssets", e); }
                try { rebuildGamesJson(); } catch (Exception e) { Log.e("flashserver", "scan", e); }
                startServer(root);
                runOnUiThread(new Runnable() {
                    public void run() { createWebView(); }
                });
            }
        }).start();
    }

    private void applyImmersive() {
        try {
            getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Exception e) {
            Log.w("flashserver", "immersive", e);
        }
    }

    private void createWebView() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient());
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(new android.view.View.OnLongClickListener() {
            public boolean onLongClick(android.view.View v) { return true; }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.i("webconsole", m.message() + " @" + m.lineNumber());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Bridge");
        setContentView(web);
        applyImmersive();
        web.loadUrl("http://127.0.0.1:" + PORT + "/index.html");
    }

    public class Bridge {
        @JavascriptInterface
        public void setOrientation(final int mode) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (mode == 0) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    else if (mode == 1) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    else if (mode == 2) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                    else setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                }
            });
        }

        @JavascriptInterface
        public void pickFiles() {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, "选择 SWF 文件"), REQ_FILES);
        }

        @JavascriptInterface
        public void pickFolder() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_TREE);
        }

        @JavascriptInterface
        public void key(final int code, final boolean down) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (web == null) return;
                    web.requestFocus();
                    long t = android.os.SystemClock.uptimeMillis();
                    web.dispatchKeyEvent(new android.view.KeyEvent(t, t,
                            down ? android.view.KeyEvent.ACTION_DOWN : android.view.KeyEvent.ACTION_UP,
                            code, 0));
                }
            });
        }

        @JavascriptInterface
        public void deleteGame(final String rootPath) {
            new Thread(new Runnable() {
                public void run() {
                    deleteRecursive(new File(root, rootPath));
                    finishMutation();
                }
            }).start();
        }

        @JavascriptInterface
        public void renameGame(final String rootPath, final String newName) {
            new Thread(new Runnable() {
                public void run() {
                    File f = new File(root, rootPath);
                    File dest = new File(f.getParentFile(), sanitize(newName));
                    if (dest.exists()) return;
                    f.renameTo(dest);
                    finishMutation();
                }
            }).start();
        }
    }

    private void finishMutation() {
        try { rebuildGamesJson(); } catch (Exception e) { Log.e("flashserver", "scan", e); }
        reloadPage();
    }

    private void reloadPage() {
        runOnUiThread(new Runnable() {
            public void run() { if (web != null) web.reload(); }
        });
    }

    private void postProgress(final int pct) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (web != null) {
                    web.evaluateJavascript("window.setProgress&&window.setProgress(" + pct + ")", null);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        final Intent d = data;
        final int r = req;
        postProgress(0);
        new Thread(new Runnable() {
            public void run() {
                try {
                    List<Task> tasks = new ArrayList<Task>();
                    if (r == REQ_FILES) collectFiles(d, tasks);
                    else if (r == REQ_TREE) collectTree(d, tasks);
                    int total = Math.max(1, tasks.size());
                    int done = 0;
                    for (Task t : tasks) {
                        if (t.dir) t.file.mkdirs();
                        else {
                            File parent = t.file.getParentFile();
                            if (parent != null) parent.mkdirs();
                            if (t.exe) {
                                if (!extractSwf(t.uri, t.file)) Log.e("flashserver", "exe: no swf found");
                            } else {
                                copyUriToFile(t.uri, t.file);
                            }
                        }
                        done++;
                        postProgress(done * 100 / total);
                    }
                    finishMutation();
                } catch (Exception e) {
                    Log.e("flashserver", "import", e);
                    postProgress(-1);
                }
            }
        }).start();
    }

    private void collectFiles(Intent data, List<Task> out) {
        File games = new File(root, "games");
        games.mkdirs();
        List<Uri> uris = new ArrayList<Uri>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        for (Uri u : uris) {
            String name = displayName(u);
            if (name == null) continue;
            String lower = name.toLowerCase();
            if (lower.endsWith(".swf")) {
                out.add(new Task(u, new File(games, sanitize(name)), false));
            } else if (lower.endsWith(".exe")) {
                String base = name.substring(0, name.length() - 4);
                out.add(new Task(u, new File(games, sanitize(base) + ".swf"), false, true));
            }
        }
    }

    private void collectTree(Intent data, List<Task> out) {
        treeUri = data.getData();
        if (treeUri == null) return;
        String name = displayName(treeUri);
        if (name == null || name.length() == 0) name = "game";
        File dest = new File(new File(root, "games"), sanitize(name));
        dest.mkdirs();
        out.add(new Task(null, dest, true));
        collectChildren(DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)), dest, out);
    }

    private void collectChildren(Uri childrenUri, File dest, List<Task> out) {
        ContentResolver cr = getContentResolver();
        Cursor c = cr.query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
        if (c == null) return;
        while (c.moveToNext()) {
            String id = c.getString(0);
            String name = sanitize(c.getString(1));
            String mime = c.getString(2);
            Uri child = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
            File f = new File(dest, name);
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                out.add(new Task(null, f, true));
                collectChildren(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id), f, out);
            } else {
                out.add(new Task(child, f, false));
            }
        }
        c.close();
    }

    private boolean extractSwf(Uri uri, File out) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return false;
            return extractSwfFromBytes(readAll(in), out);
        } catch (Exception e) {
            Log.e("flashserver", "extractSwf", e);
            return false;
        }
    }

    // 形态 B：exe 旁边放着 data/ 之类的资源目录 —— 把主 SWF 从 exe 提到"同目录"，
    // 这样 SWF 里的相对路径（loadMovie("data/x.swf")）才对得上。
    private boolean extractSwfFromFile(File src, File out) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            return extractSwfFromBytes(readAll(in), out);
        } catch (Exception e) {
            Log.e("flashserver", "extractSwfFromFile", e);
            return false;
        }
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private boolean extractSwfFromBytes(byte[] d, File out) {
        try {
            // 一个 exe 里可能塞了好几个 SWF（真实 projector 常带几段小资源 + 主游戏），
            // 以前是"撞到第一个能解的就写"，会抓错成小资源；改成扫全文件、取最大的那个。
            int bestOff = -1, bestLen = -1, found = 0;
            for (int i = 0; i + 8 <= d.length; i++) {
                char c0 = (char) (d[i] & 0xff);
                if (c0 != 'F' && c0 != 'C' && c0 != 'Z') continue;
                if (d[i + 1] != 'W' || d[i + 2] != 'S') continue;
                int ver = d[i + 3] & 0xff;
                if (ver < 1 || ver > 50) continue;
                long ulen = (d[i + 4] & 0xffL) | ((d[i + 5] & 0xffL) << 8)
                        | ((d[i + 6] & 0xffL) << 16) | ((d[i + 7] & 0xffL) << 24);
                if (ulen < 8) continue;
                int total = -1;
                if (c0 == 'F') {
                    if (i + ulen <= d.length) total = (int) ulen;
                } else if (c0 == 'C') {
                    try {
                        java.util.zip.Inflater inf = new java.util.zip.Inflater();
                        inf.setInput(d, i + 8, d.length - (i + 8));
                        byte[] tmp = new byte[65536];
                        while (!inf.finished()) {
                            if (inf.inflate(tmp) == 0 && inf.needsInput()) break;
                        }
                        if (inf.finished()) {
                            long consumed = inf.getBytesRead();
                            if (8 + consumed <= d.length) total = (int) (8 + consumed);
                        }
                        inf.end();
                    } catch (Exception e) { }
                }
                if (total > 0) {
                    found++;
                    if (total > bestLen) { bestLen = total; bestOff = i; }
                }
            }
            Log.i("flashserver", "extractSwf: candidates=" + found + ", picked=" + bestLen);
            if (bestOff >= 0) {
                FileOutputStream os = new FileOutputStream(out);
                os.write(d, bestOff, bestLen);
                os.close();
                return true;
            }
        } catch (Exception e) {
            Log.e("flashserver", "extractSwfFromBytes", e);
        }
        return false;
    }

    private void copyUriToFile(Uri uri, File out) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return;
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        in.close();
    }

    private String displayName(Uri uri) {
        try {
            if ("content".equals(uri.getScheme())) {
                Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (c.moveToFirst() && idx >= 0) {
                        String n = c.getString(idx);
                        c.close();
                        return n;
                    }
                    c.close();
                }
            }
        } catch (Exception e) { }
        String p = uri.getLastPathSegment();
        if (p == null) return null;
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String sanitize(String n) {
        if (n == null) return "unnamed";
        return n.replace('/', '_').replace('\\', '_').trim();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        f.delete();
    }

    private String assetMarkerName() {
        // 标记跟随 versionCode：每次装新版本都会重新拷一份页面，
        // 避免"装回旧版本却还在用另一版的页面"（.assets_vNN 同名复用导致）
        try {
            return ".assets_v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return ASSETS_VERSION;
        }
    }

    private void installAssets() throws Exception {
        File marker = new File(root, assetMarkerName());
        if (marker.exists()) return;
        String[] names = getAssets().list("");
        if (names != null) {
            for (String n : names) {
                boolean wanted = n.endsWith(".wasm")
                        || n.equals("index.html") || n.equals("play_template.html")
                        || n.equals("ruffle.js") || n.equals("FlashGameCJK.ttf");
                if (!wanted) continue;
                copyAsset(n, new File(root, n));
            }
        }
        marker.createNewFile();
    }

    private void copyAsset(String name, File out) throws Exception {
        if (out.exists()) out.delete();
        InputStream in = getAssets().open(name);
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        in.close();
    }

    private void rebuildGamesJson() throws Exception {
        File games = new File(root, "games");
        games.mkdirs();
        File[] items = games.listFiles();
        List<String> entries = new ArrayList<String>();
        if (items != null) {
            Arrays.sort(items, new Comparator<File>() {
                public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
            });
            for (File f : items) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".swf")) {
                    String base = f.getName().substring(0, f.getName().length() - 4);
                    entries.add(entry(prettyName(base), "swf", "games/" + f.getName(), "games/" + f.getName(), "单文件"));
                } else if (f.isDirectory()) {
                    File main = pickMainSwf(f);
                    if (main == null) {
                        // 形态 B：目录里没有顶层 SWF（主 SWF 藏在 exe 里），把它提到同目录，
                        // 这样 data/ 之类的相对路径才对得上。
                        File exe = pickExe(f);
                        if (exe != null) {
                            String base = exe.getName();
                            int dot = base.lastIndexOf('.');
                            if (dot > 0) base = base.substring(0, dot);
                            File out = new File(f, base + ".swf");
                            if (out.exists() || extractSwfFromFile(exe, out)) {
                                main = out;
                                Log.i("flashserver", "formB: " + exe.getName() + " -> " + out.getName());
                            } else {
                                Log.w("flashserver", "formB: no swf inside " + exe.getName());
                            }
                        }
                    }
                    if (main != null) {
                        writePlayPage(f, main.getName());
                        entries.add(entry(folderName(f.getName()), "page", "games/" + f.getName() + "/play.html",
                                "games/" + f.getName(), "多文件"));
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(entries.get(i));
        }
        sb.append(']');
        FileOutputStream os = new FileOutputStream(new File(root, "games.json"));
        os.write(sb.toString().getBytes("UTF-8"));
        os.close();
    }

    // 选目录里最像"主程序"的 exe：跳过 Unity 的崩溃上报和安装器等
    private File pickExe(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        File best = null;
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (!n.endsWith(".exe")) continue;
            if (n.contains("crash") || n.startsWith("unins") || n.contains("vcredist")
                    || n.contains("dxsetup") || n.contains("redist")) continue;
            if (best == null || f.length() > best.length()) best = f;
        }
        return best;
    }

    private File pickMainSwf(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        File best = null;
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".swf")) {
                if (best == null || f.length() > best.length()) best = f;
            }
        }
        return best;
    }

    private void writePlayPage(File dir, String swfName) throws Exception {
        InputStream in = getAssets().open("play_template.html");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        String tpl = new String(bos.toByteArray(), "UTF-8");
        String html = tpl.replace("__GAME__", swfName.replace("'", "\\'"));
        File target = new File(dir, "play.html");
        if (target.exists()) target.delete();
        FileOutputStream os = new FileOutputStream(target);
        os.write(html.getBytes("UTF-8"));
        os.close();
    }

    private static String folderName(String dir) {
        if ("witch".equals(dir)) return "Witch Girl";
        if ("kungfu".equals(dir)) return "Kung-fu Girl";
        if ("shinobi".equals(dir)) return "Shinobi Girl";
        if ("tifa".equals(dir)) return "Touching Tifa";
        return prettyName(dir);
    }

    private static String prettyName(String raw) {
        String s = raw.replace("-decensored", "").replace('_', ' ').replace('-', ' ').trim();
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : raw;
    }

    private static String entry(String name, String type, String path, String rootPath, String note) {
        return "{\"n\":\"" + esc(name) + "\",\"t\":\"" + type + "\",\"p\":\"" + esc(path)
                + "\",\"r\":\"" + esc(rootPath) + "\",\"d\":\"" + esc(note) + "\"}";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void startServer(final File root) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    ServerSocket ss = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
                    while (true) {
                        Socket c = ss.accept();
                        Thread h = new Thread(new Handler(c, root));
                        h.setDaemon(true);
                        h.start();
                    }
                } catch (Exception e) {
                    Log.e("flashserver", "server died", e);
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
