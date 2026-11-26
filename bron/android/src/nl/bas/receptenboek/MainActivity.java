package nl.bas.receptenboek;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    static final String HOST = "https://app.receptenboek.local/";
    static final int REQ_EXPORT = 11;
    static final int REQ_IMPORT = 12;
    static final int REQ_PICK = 13;

    WebView web;
    final Handler main = new Handler(Looper.getMainLooper());
    Store store;
    Processor processor;
    boolean pageReady = false;
    final List<String> pendingJs = new ArrayList<String>();
    String exportPayload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        processor = Processor.get(this);
        processor.setListener(new Processor.Listener() {
            @Override public void onJobsChanged() { js("window.App && App.onJobsChanged()"); }
            @Override public void onRecipesChanged() { js("window.App && App.onRecipesChanged()"); }
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#100D0B"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null || !"app.receptenboek.local".equals(u.getHost())) return null;
                return serve(u.getPath());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("app.receptenboek.local".equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                for (String p : pendingJs) web.evaluateJavascript(p, null);
                pendingJs.clear();
            }
        });
        setContentView(web);
        web.loadUrl(HOST + "index.html");
        Notifier.channels(this);
        handleIntent(getIntent());
        askNotifications();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    WebResourceResponse serve(String path) {
        try {
            if (path == null || path.equals("/")) path = "/index.html";
            if (path.startsWith("/img/")) {
                String name = new File(path).getName();
                File f = new File(store.imageDir(), name);
                if (!f.exists()) return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
                return new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
            }
            String asset = "www" + path;
            String mime = "text/plain";
            if (path.endsWith(".html")) mime = "text/html";
            else if (path.endsWith(".js")) mime = "application/javascript";
            else if (path.endsWith(".css")) mime = "text/css";
            else if (path.endsWith(".svg")) mime = "image/svg+xml";
            else if (path.endsWith(".png")) mime = "image/png";
            else if (path.endsWith(".jpg")) mime = "image/jpeg";
            InputStream in = getAssets().open(asset);
            return new WebResourceResponse(mime, "utf-8", in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
        }
    }

    boolean canNotify() {
        return android.os.Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    void handleIntent(Intent intent) {
        if (intent == null) return;
        final String open = intent.getStringExtra("openRecipe");
        if (open != null) {
            intent.removeExtra("openRecipe");
            js("window.App && App.openRecipeById(" + org.json.JSONObject.quote(open) + ")");
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (text == null && intent.getCharSequenceExtra(Intent.EXTRA_TEXT) != null)
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT).toString();
        List<Uri> uris = new ArrayList<Uri>();
        if (Intent.ACTION_SEND.equals(action)) {
            Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) uris.add((Uri) p);
        } else {
            ArrayList<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Parcelable p : list) if (p instanceof Uri) uris.add((Uri) p);
        }
        if (uris.isEmpty() && intent.getClipData() != null) {
            ClipData cd = intent.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) if (cd.getItemAt(i).getUri() != null) uris.add(cd.getItemAt(i).getUri());
        }
        String combined = text;
        if (subject != null && text != null && !text.contains(subject)) combined = subject + "\n" + text;
        else if (text == null) combined = subject;
        if ((combined == null || combined.trim().isEmpty()) && uris.isEmpty()) return;
        processor.submit(combined, uris, getContentResolver());
        // Clear so a rotation / re-create does not re-submit.
        setIntent(new Intent(Intent.ACTION_MAIN));
        boolean hasKey = !store.prefs().getString("apiKey", "").isEmpty();
        if (canNotify() && hasKey) {
            // Go straight back to the app the recipe was shared from; a notification follows when it's ready.
            Toast.makeText(this, "Recept wordt op de achtergrond toegevoegd. Je krijgt een melding.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        } else {
            Toast.makeText(this, "Recept wordt toegevoegd…", Toast.LENGTH_SHORT).show();
            js("window.App && App.showHome()");
            askNotifications();
        }
    }

    void askNotifications() {
        if (!canNotify()) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5);
    }

    void js(final String code) {
        main.post(new Runnable() {
            @Override public void run() {
                if (!pageReady) { pendingJs.add(code); return; }
                web.evaluateJavascript(code, null);
            }
        });
    }

    void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Kan link niet openen", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.App && App.back()) ? 'y' : 'n'", new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (v == null || !v.contains("y")) MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == REQ_EXPORT && data.getData() != null) {
                OutputStream os = getContentResolver().openOutputStream(data.getData());
                os.write(exportPayload.getBytes("UTF-8"));
                os.close();
                exportPayload = null;
                Toast.makeText(this, "Back-up opgeslagen", Toast.LENGTH_LONG).show();
            } else if (requestCode == REQ_IMPORT && data.getData() != null) {
                InputStream in = getContentResolver().openInputStream(data.getData());
                String json = new String(Net.readAll(in), "UTF-8");
                int n = store.importBackup(json);
                Toast.makeText(this, n + " recepten geïmporteerd", Toast.LENGTH_LONG).show();
                js("window.App && App.onRecipesChanged()");
            } else if (requestCode == REQ_PICK) {
                List<Uri> uris = new ArrayList<Uri>();
                if (data.getClipData() != null) {
                    ClipData cd = data.getClipData();
                    for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
                } else if (data.getData() != null) uris.add(data.getData());
                if (!uris.isEmpty()) processor.submit(pendingPickText, uris, getContentResolver());
                pendingPickText = null;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Mislukt: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    String pendingPickText;

    void showInstagramLogin() {
        final android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#100D0B"));
        android.widget.Button done = new android.widget.Button(this);
        done.setText("Klaar met inloggen");
        done.setAllCaps(false);
        done.setTextColor(Color.parseColor("#1A1209"));
        done.setBackgroundColor(Color.parseColor("#F2A541"));
        final WebView w = new WebView(this);
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setDomStorageEnabled(true);
        w.getSettings().setUserAgentString(Net.MOBILE_UA);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setWebViewClient(new WebViewClient());
        root.addView(done, new android.widget.LinearLayout.LayoutParams(-1, (int) (56 * getResources().getDisplayMetrics().density)));
        root.addView(w, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) {
                CookieManager.getInstance().flush();
                w.destroy();
                js("window.App && App.onSettingsChanged()");
            }
        });
        d.setContentView(root);
        d.show();
        w.loadUrl("https://www.instagram.com/accounts/login/");
    }

    class Bridge {
        @JavascriptInterface public String getRecipes() { return store.recipesJson(); }

        @JavascriptInterface public void saveRecipe(String json) {
            try { store.upsert(new JSONObject(json)); } catch (Exception e) { toast("Opslaan mislukt"); }
        }

        @JavascriptInterface public void deleteRecipe(String id) { store.delete(id); }

        @JavascriptInterface public String getJobs() { return processor.jobsJson(); }

        @JavascriptInterface public void retryJob(String id) { processor.retry(id); }

        @JavascriptInterface public void removeJob(String id) { processor.remove(id); }

        @JavascriptInterface public void submitText(String text) { processor.submit(text, null, getContentResolver()); }

        @JavascriptInterface public void pickImages(final String text) {
            main.post(new Runnable() {
                @Override public void run() {
                    pendingPickText = text;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(i, "Kies foto's of video"), REQ_PICK);
                }
            });
        }

        @JavascriptInterface public String getSettings() {
            try {
                SharedPreferences p = store.prefs();
                JSONObject o = new JSONObject();
                o.put("apiKey", p.getString("apiKey", ""));
                o.put("model", p.getString("model", Gemini.DEFAULT_MODEL));
                String c = CookieManager.getInstance().getCookie("https://www.instagram.com/");
                o.put("instagramLoggedIn", c != null && c.contains("sessionid="));
                o.put("version", BuildConfigInfo.VERSION);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void setSetting(String key, String value) {
            store.prefs().edit().putString(key, value == null ? "" : value.trim()).apply();
        }

        @JavascriptInterface public void instagramLogin() {
            main.post(new Runnable() { @Override public void run() { showInstagramLogin(); } });
        }

        @JavascriptInterface public void instagramLogout() {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        }

        @JavascriptInterface public void openUrl(final String url) {
            main.post(new Runnable() { @Override public void run() { openExternal(url); } });
        }

        @JavascriptInterface public void exportBackup() {
            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        exportPayload = store.exportBackup();
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("application/json");
                        String d = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
                        i.putExtra(Intent.EXTRA_TITLE, "receptenboek-backup-" + d + ".json");
                        startActivityForResult(i, REQ_EXPORT);
                    } catch (Exception e) { toast("Back-up mislukt: " + e.getMessage()); }
                }
            });
        }

        @JavascriptInterface public void importBackup() {
            main.post(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, REQ_IMPORT);
                }
            });
        }

        @JavascriptInterface public void keepScreenOn(final boolean on) {
            main.post(new Runnable() {
                @Override public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface public void shareText(final String text) {
            main.post(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "Recept delen"));
                }
            });
        }

        @JavascriptInterface public void toast(final String msg) {
            main.post(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show(); } });
        }
    }
}
