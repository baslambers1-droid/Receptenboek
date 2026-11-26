package nl.bas.receptenboek;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns shared links, text, photos and videos into recipes, one background job per share. */
public class Processor {
    interface Listener { void onJobsChanged(); void onRecipesChanged(); }

    static class Job {
        String id;
        String text;
        List<File> files = new ArrayList<File>();
        List<String> mimes = new ArrayList<String>();
        String status = "busy"; // busy | error
        String stage = "In de wachtrij";
        String error;
        String source = "";
        long createdAt = System.currentTimeMillis();
    }

    private static Processor instance;
    private final Context ctx;
    private final Store store;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final List<Job> jobs = new ArrayList<Job>();
    private Listener listener;

    static synchronized Processor get(Context c) {
        if (instance == null) instance = new Processor(c.getApplicationContext());
        return instance;
    }

    private Processor(Context c) { ctx = c; store = Store.get(c); }

    void setListener(Listener l) { listener = l; }

    private void changed() { if (listener != null) listener.onJobsChanged(); }

    synchronized String jobsJson() {
        JSONArray a = new JSONArray();
        try {
            for (Job j : jobs) {
                JSONObject o = new JSONObject();
                o.put("id", j.id);
                o.put("status", j.status);
                o.put("stage", j.stage);
                o.put("error", j.error == null ? "" : j.error);
                o.put("source", j.source);
                o.put("text", j.text == null ? "" : j.text);
                o.put("files", j.files.size());
                a.put(o);
            }
        } catch (Exception ignored) {}
        return a.toString();
    }

    void submit(String text, final List<Uri> uris, final ContentResolver cr) {
        final Job j = new Job();
        j.id = "j" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        j.text = text;
        String url = firstUrl(text);
        j.source = url != null ? hostLabel(url) : (uris != null && !uris.isEmpty() ? "Foto/video" : "Tekst");
        // Copy shared files right away, while we still have permission to read them.
        if (uris != null) {
            for (Uri u : uris) {
                try {
                    String mime = cr.getType(u);
                    if (mime == null) {
                        String ext = MimeTypeMap.getFileExtensionFromUrl(u.toString());
                        mime = ext != null ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) : null;
                    }
                    if (mime == null) mime = "image/jpeg";
                    File f = new File(ctx.getCacheDir(), j.id + "-" + j.files.size());
                    InputStream in = cr.openInputStream(u);
                    FileOutputStream os = new FileOutputStream(f);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    in.close();
                    os.close();
                    j.files.add(f);
                    j.mimes.add(mime);
                } catch (Exception ignored) {}
            }
        }
        synchronized (this) { jobs.add(0, j); }
        changed();
        start(j);
    }

    void retry(String id) {
        Job j = find(id);
        if (j == null || "busy".equals(j.status)) return;
        j.status = "busy";
        j.error = null;
        j.stage = "Opnieuw proberen";
        changed();
        start(j);
    }

    void remove(String id) {
        Job j = find(id);
        if (j == null) return;
        synchronized (this) { jobs.remove(j); }
        for (File f : j.files) f.delete();
        changed();
    }

    synchronized boolean hasBusyJobs() {
        for (Job j : jobs) if ("busy".equals(j.status)) return true;
        return false;
    }

    private void keepAlive(String text) {
        try {
            Intent i = new Intent(ctx, ProcessingService.class);
            i.putExtra("text", text);
            ctx.startForegroundService(i);
        } catch (Throwable ignored) {
            // Not allowed right now (e.g. app in background on retry); the job still runs while the process lives.
        }
    }

    private void finished() {
        if (hasBusyJobs()) return;
        try { ctx.stopService(new Intent(ctx, ProcessingService.class)); } catch (Throwable ignored) {}
        ((android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(Notifier.ID_BUSY);
    }

    private synchronized Job find(String id) {
        for (Job j : jobs) if (j.id.equals(id)) return j;
        return null;
    }

    private void start(final Job j) {
        keepAlive(j.source + ": " + j.stage);
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject r = process(j);
                    synchronized (Processor.this) { jobs.remove(j); }
                    for (File f : j.files) f.delete();
                    Notifier.done(ctx, r.optString("id"), r.optString("title"));
                    if (listener != null) listener.onRecipesChanged();
                } catch (Throwable e) {
                    j.status = "error";
                    j.error = e.getMessage() != null ? e.getMessage() : e.toString();
                    Notifier.failed(ctx, j.source, j.error);
                }
                finished();
                changed();
            }
        });
    }

    private void stage(Job j, String s) {
        j.stage = s;
        try { Notifier.updateBusy(ctx, j.source + ": " + s + "…"); } catch (Throwable ignored) {}
        changed();
    }

    // ---------------------------------------------------------------- main flow

    static class Material {
        JSONArray parts = new JSONArray();
        JSONArray mediaParts = new JSONArray(); // video parts, dropped on retry if Gemini rejects them
        byte[] cover;
        String coverUrl;
        String sourceUrl;
        String sourceType = "tekst";
        String author = "";
        StringBuilder notes = new StringBuilder();
        String skippedVideoUrl;   // Instagram video not sent because the caption looked complete
        String skippedYoutube;    // same for YouTube
    }

    private JSONObject process(Job j) throws Exception {
        String apiKey = store.prefs().getString("apiKey", "");
        String model = store.prefs().getString("model", Gemini.DEFAULT_MODEL);
        if (model.isEmpty()) model = Gemini.DEFAULT_MODEL;
        if (apiKey.isEmpty()) throw new Exception("Vul eerst je gratis Gemini API-sleutel in bij Instellingen.");

        Material m = new Material();
        String text = j.text == null ? "" : j.text.trim();
        String url = firstUrl(text);
        if (url != null) {
            m.sourceUrl = url;
            String rest = text.replace(url, "").trim();
            stage(j, "Post ophalen");
            String host = Uri.parse(url).getHost();
            host = host == null ? "" : host.toLowerCase();
            try {
                if (host.contains("instagram.com") || host.contains("instagr.am")) instagram(j, url, m, apiKey);
                else if (host.contains("youtube.com") || host.contains("youtu.be")) youtube(url, m);
                else if (host.contains("tiktok.com")) tiktok(url, m);
                else website(url, m);
            } catch (Exception e) {
                m.notes.append("(Ophalen van de link lukte niet helemaal: ").append(e.getMessage()).append(")\n");
            }
            if (!rest.isEmpty()) m.parts.put(textPart("Gedeelde tekst bij de link:\n" + rest));
        } else if (!text.isEmpty()) {
            m.parts.put(textPart("Gedeelde tekst:\n" + text));
        }

        // Shared photos and videos.
        for (int i = 0; i < j.files.size(); i++) {
            File f = j.files.get(i);
            String mime = j.mimes.get(i);
            if (mime.startsWith("image/")) {
                byte[] raw = Net.readAll(new FileInputStream(f));
                byte[] scaled = Net.scaleJpeg(raw, 1600, 85);
                if (scaled == null) continue;
                if (m.cover == null) m.cover = scaled;
                m.parts.put(inline("image/jpeg", scaled));
                if (url == null) m.sourceType = "foto";
            } else if (mime.startsWith("video/")) {
                stage(j, "Video uploaden naar Gemini");
                String[] up = Gemini.uploadFile(apiKey, f, mime);
                m.mediaParts.put(fileData(up[1], up[0]));
                if (url == null) m.sourceType = "video";
            }
        }

        if (m.parts.length() == 0 && m.mediaParts.length() == 0)
            throw new Exception("Er was niets om te verwerken. Deel een link, tekst, foto of video.");

        if (m.notes.length() > 0) m.parts.put(textPart(m.notes.toString()));
        m.parts.put(textPart("Haal hieruit het recept en schrijf het in het Nederlands volgens het schema."));

        stage(j, "Gemini leest het recept");
        JSONObject r;
        try {
            r = Gemini.extractRecipe(apiKey, model, concat(m.mediaParts, m.parts));
        } catch (Gemini.GeminiException e) {
            // A video Gemini cannot open (private, too long, ...): try again with the rest of the material.
            if (m.mediaParts.length() > 0 && m.parts.length() > 1 && !e.getMessage().contains("daglimiet")) {
                stage(j, "Opnieuw, zonder video");
                r = Gemini.extractRecipe(apiKey, model, m.parts);
            } else throw e;
        }
        if (r.optBoolean("isRecipe", true) && !r.optBoolean("complete", true)
                && m.mediaParts.length() == 0 && (m.skippedVideoUrl != null || m.skippedYoutube != null)) {
            // Caption was incomplete: watch the video to fill in what's missing.
            try {
                stage(j, "Video bekijken voor ontbrekende details");
                if (m.skippedYoutube != null) {
                    m.mediaParts.put(new JSONObject().put("fileData", new JSONObject().put("fileUri", m.skippedYoutube)));
                } else {
                    File vf = new File(ctx.getCacheDir(), j.id + "-ig.mp4");
                    Net.download(m.skippedVideoUrl, vf, 150L << 20);
                    String[] up = Gemini.uploadFile(apiKey, vf, "video/mp4");
                    vf.delete();
                    m.mediaParts.put(fileData(up[1], up[0]));
                }
                stage(j, "Gemini vult het recept aan");
                r = Gemini.extractRecipe(apiKey, model, concat(m.mediaParts, m.parts));
            } catch (Exception e) {
                // Keep the first (caption-based) result.
            }
        }
        if (!r.optBoolean("isRecipe", true)) {
            String why = r.optString("notRecipeReason");
            throw new Exception("Geen recept gevonden" + (why.isEmpty() ? "." : ": " + why));
        }

        stage(j, "Opslaan in je receptenboek");
        String id = "r" + System.currentTimeMillis();
        if (m.cover == null && m.coverUrl != null) {
            try {
                Net.Response img = Net.get(m.coverUrl, null, 15 << 20);
                if (img.code == 200) m.cover = img.body;
            } catch (Exception ignored) {}
        }
        if (m.cover != null) {
            byte[] jpg = Net.scaleJpeg(m.cover, 1200, 85);
            if (jpg != null) {
                Net.writeFile(new File(store.imageDir(), id + ".jpg"), jpg);
                r.put("image", "img/" + id + ".jpg");
            }
        }
        r.remove("isRecipe");
        r.remove("notRecipeReason");
        r.put("id", id);
        r.put("createdAt", System.currentTimeMillis());
        r.put("sourceUrl", m.sourceUrl == null ? "" : m.sourceUrl);
        r.put("sourceType", m.sourceType);
        r.put("author", m.author);
        r.put("favorite", false);
        r.put("notes", "");
        store.upsert(r);
        return r;
    }

    // ---------------------------------------------------------------- sources

    private void instagram(Job j, String url, Material m, String apiKey) throws Exception {
        m.sourceType = "instagram";
        if (url.contains("/share/")) {
            try { url = Net.get(url, null, 4 << 20).finalUrl; m.sourceUrl = url; } catch (Exception ignored) {}
        }
        Matcher mt = Pattern.compile("instagram\\.com/(?:[^/?#]+/)?(p|reel|reels|tv)/([A-Za-z0-9_-]+)").matcher(url);
        if (!mt.find()) { website(url, m); m.sourceType = "instagram"; return; }
        String code = mt.group(2);
        String caption = null, video = null, image = null;

        Map<String, String> api = new HashMap<String, String>();
        api.put("X-IG-App-ID", "936619743392459");
        api.put("Accept", "*/*");
        api.put("X-Requested-With", "XMLHttpRequest");

        // 1) Logged-in JSON endpoints (work best after "Inloggen bij Instagram" in Instellingen).
        String[] apiUrls = {
            "https://www.instagram.com/api/v1/media/" + mediaId(code) + "/info/",
            "https://www.instagram.com/p/" + code + "/?__a=1&__d=dis"
        };
        String igCookie = null;
        try { igCookie = android.webkit.CookieManager.getInstance().getCookie("https://www.instagram.com/"); } catch (Throwable ignored) {}
        boolean loggedIn = igCookie != null && igCookie.contains("sessionid=");
        for (String u : apiUrls) {
            if (!loggedIn) break;
            if (caption != null && (video != null || image != null)) break;
            try {
                Net.Response r = Net.get(u, api, 8 << 20);
                if (r.code != 200 || !r.text().trim().startsWith("{")) continue;
                JSONObject o = new JSONObject(r.text());
                JSONObject item = null;
                if (o.optJSONArray("items") != null && o.getJSONArray("items").length() > 0) item = o.getJSONArray("items").getJSONObject(0);
                if (item == null) continue;
                JSONObject cap = item.optJSONObject("caption");
                if (cap != null && !cap.optString("text").isEmpty()) caption = cap.optString("text");
                JSONObject user = item.optJSONObject("user");
                if (user != null) m.author = user.optString("username");
                JSONArray vv = item.optJSONArray("video_versions");
                if (vv != null && vv.length() > 0) video = vv.getJSONObject(0).optString("url", null);
                JSONObject iv = item.optJSONObject("image_versions2");
                if (iv != null && iv.optJSONArray("candidates") != null && iv.getJSONArray("candidates").length() > 0)
                    image = iv.getJSONArray("candidates").getJSONObject(0).optString("url", null);
                // Carousels: take the first video/image inside.
                JSONArray car = item.optJSONArray("carousel_media");
                if (car != null && car.length() > 0) {
                    for (int i = 0; i < car.length(); i++) {
                        JSONObject c = car.getJSONObject(i);
                        JSONArray cv = c.optJSONArray("video_versions");
                        if (video == null && cv != null && cv.length() > 0) video = cv.getJSONObject(0).optString("url", null);
                        JSONObject ci = c.optJSONObject("image_versions2");
                        if (image == null && ci != null && ci.optJSONArray("candidates") != null && ci.getJSONArray("candidates").length() > 0)
                            image = ci.getJSONArray("candidates").getJSONObject(0).optString("url", null);
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2) Public embed page.
        if (caption == null || (video == null && image == null)) {
            try {
                Net.Response r = Net.get("https://www.instagram.com/p/" + code + "/embed/captioned/", null, 8 << 20);
                if (r.code == 200) {
                    String html = r.text();
                    if (caption == null) {
                        Matcher c = Pattern.compile("<div class=\"Caption\">(.*?)<div class=\"CaptionComments\">", Pattern.DOTALL).matcher(html);
                        if (c.find()) {
                            String t = htmlToText(c.group(1).replaceAll("(?s)<a class=\"CaptionUsername\".*?</a>", ""));
                            if (!t.isEmpty()) caption = t;
                        }
                    }
                    String un = unescapeJs(html);
                    if (video == null) video = group(un, "\"video_url\":\"(https:[^\"]+)\"");
                    if (image == null) image = group(un, "\"display_url\":\"(https:[^\"]+)\"");
                    if (image == null) image = htmlDecode(group(html, "class=\"EmbeddedMediaImage\"[^>]*src=\"([^\"]+)\""));
                    if (m.author.isEmpty()) { String a = group(html, "class=\"UsernameText\">([^<]+)<"); if (a != null) m.author = a; }
                }
            } catch (Exception ignored) {}
        }

        // 3) The normal post page (Open Graph tags).
        if (caption == null || image == null) {
            try {
                Net.Response r = Net.get("https://www.instagram.com/p/" + code + "/", null, 8 << 20);
                String html = r.text();
                String desc = meta(html, "og:description");
                if (caption == null && desc != null) {
                    Matcher c = Pattern.compile(":\\s*\"(.*)\"\\s*$", Pattern.DOTALL).matcher(desc);
                    caption = c.find() ? c.group(1) : desc;
                }
                if (image == null) image = meta(html, "og:image");
                if (video == null) video = meta(html, "og:video");
            } catch (Exception ignored) {}
        }

        if (caption != null) m.parts.put(textPart("Instagram-caption" + (m.author.isEmpty() ? "" : " van @" + m.author) + ":\n" + caption));
        else m.notes.append("(De caption van deze Instagram-post kon niet worden opgehaald. Tip: log in bij Instagram via Instellingen.)\n");
        m.coverUrl = image;

        if (video != null && looksLikeRecipe(caption)) {
            // The caption already holds the recipe: skip the slow video for now. If Gemini still finds
            // gaps, the video is added in a second pass.
            m.skippedVideoUrl = video;
            video = null;
        }
        if (video != null) {
            try {
                stage(j, "Video downloaden");
                File vf = new File(ctx.getCacheDir(), j.id + "-ig.mp4");
                Net.download(video, vf, 150L << 20);
                stage(j, "Video uploaden naar Gemini");
                String[] up = Gemini.uploadFile(apiKey, vf, "video/mp4");
                vf.delete();
                m.mediaParts.put(fileData(up[1], up[0]));
            } catch (Exception e) {
                m.notes.append("(De video kon niet worden meegestuurd: ").append(e.getMessage()).append(")\n");
            }
        }
        if (m.mediaParts.length() == 0 && image != null) {
            try {
                Net.Response img = Net.get(image, null, 15 << 20);
                if (img.code == 200) {
                    m.cover = img.body;
                    byte[] s = Net.scaleJpeg(img.body, 1600, 85);
                    if (s != null) m.parts.put(inline("image/jpeg", s));
                }
            } catch (Exception ignored) {}
        }
        if (caption == null && video == null && image == null)
            throw new Exception("Instagram gaf deze post niet vrij. Log in bij Instagram via Instellingen en probeer opnieuw.");
    }

    private void youtube(String url, Material m) throws Exception {
        m.sourceType = "youtube";
        String id = group(url, "(?:v=|youtu\\.be/|shorts/|live/|embed/)([A-Za-z0-9_-]{11})");
        if (id == null) { website(url, m); m.sourceType = "youtube"; return; }
        String watch = "https://www.youtube.com/watch?v=" + id;
        m.sourceUrl = url.contains("shorts/") ? "https://www.youtube.com/shorts/" + id : watch;
        m.coverUrl = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        try {
            Net.Response o = Net.get("https://www.youtube.com/oembed?format=json&url=" + Uri.encode(watch), null, 1 << 20);
            if (o.code == 200) {
                JSONObject j = new JSONObject(o.text());
                m.author = j.optString("author_name");
                m.parts.put(textPart("YouTube-video: \"" + j.optString("title") + "\" van " + m.author));
            }
        } catch (Exception ignored) {}
        try {
            Map<String, String> h = new HashMap<String, String>();
            h.put("Cookie", "CONSENT=YES+1; SOCS=CAI");
            Net.Response p = Net.get(watch, h, 6 << 20);
            String d = group(p.text(), "\"shortDescription\":\"((?:[^\"\\\\]|\\\\.)*)\"");
            if (d != null) {
                String desc = new JSONObject("{\"d\":\"" + d + "\"}").getString("d");
                if (!desc.trim().isEmpty()) m.parts.put(textPart("Beschrijving onder de video:\n" + desc));
                if (looksLikeRecipe(desc)) {
                    m.skippedYoutube = watch;
                    // Recipe is in the description: a thumbnail is enough, no need to watch the whole video.
                    try {
                        Net.Response img = Net.get(m.coverUrl, null, 5 << 20);
                        if (img.code == 200) m.cover = img.body;
                    } catch (Exception ignored) {}
                    return;
                }
            }
        } catch (Exception ignored) {}
        m.mediaParts.put(new JSONObject().put("fileData", new JSONObject().put("fileUri", watch)));
    }

    private void tiktok(String url, Material m) throws Exception {
        m.sourceType = "tiktok";
        try { url = Net.get(url, null, 4 << 20).finalUrl; } catch (Exception ignored) {}
        m.sourceUrl = url;
        Net.Response o = Net.get("https://www.tiktok.com/oembed?url=" + Uri.encode(url), null, 1 << 20);
        if (o.code == 200) {
            JSONObject j = new JSONObject(o.text());
            m.author = j.optString("author_name");
            m.coverUrl = j.optString("thumbnail_url", null);
            m.parts.put(textPart("TikTok-caption van " + m.author + ":\n" + j.optString("title")));
            if (m.coverUrl != null) {
                try {
                    Net.Response img = Net.get(m.coverUrl, null, 10 << 20);
                    byte[] s = img.code == 200 ? Net.scaleJpeg(img.body, 1600, 85) : null;
                    if (s != null) { m.cover = img.body; m.parts.put(inline("image/jpeg", s)); }
                } catch (Exception ignored) {}
            }
        } else website(url, m);
    }

    private void website(String url, Material m) throws Exception {
        if ("tekst".equals(m.sourceType)) m.sourceType = "web";
        Net.Response r = Net.get(url, null, 6 << 20);
        if (r.code >= 400) throw new Exception("website gaf foutcode " + r.code);
        m.sourceUrl = r.finalUrl;
        String html = r.text();
        StringBuilder sb = new StringBuilder();
        sb.append("Webpagina: ").append(r.finalUrl).append("\n");
        String title = meta(html, "og:title");
        if (title == null) title = htmlToText(group(html, "(?s)<title[^>]*>(.*?)</title>"));
        if (title != null) sb.append("Titel: ").append(title).append("\n");
        String desc = meta(html, "og:description");
        if (desc == null) desc = meta(html, "description");
        if (desc != null) sb.append("Omschrijving: ").append(desc).append("\n");
        String site = meta(html, "og:site_name");
        if (site != null) m.author = site;
        m.coverUrl = meta(html, "og:image");

        // Structured recipe data (schema.org), which most recipe sites include.
        Matcher ld = Pattern.compile("(?is)<script[^>]*application/ld\\+json[^>]*>(.*?)</script>").matcher(html);
        StringBuilder lds = new StringBuilder();
        while (ld.find()) {
            String block = ld.group(1).trim();
            if (block.contains("Recipe") || block.contains("recipeIngredient")) lds.append(block).append("\n");
        }
        if (lds.length() > 0) {
            sb.append("\nGestructureerde receptgegevens (JSON-LD):\n").append(trim(lds.toString(), 25000)).append("\n");
            if (m.coverUrl == null) {
                String img = group(lds.toString(), "\"image\"\\s*:\\s*\\[?\\s*\"(https?:[^\"]+)\"");
                if (img == null) img = group(lds.toString(), "\"image\"\\s*:\\s*\\{[^}]*\"url\"\\s*:\\s*\"(https?:[^\"]+)\"");
                m.coverUrl = img != null ? img.replace("\\/", "/") : null;
            }
        }
        String body = html.replaceAll("(?is)<(script|style|noscript|svg|nav|footer|header|form|iframe)[^>]*>.*?</\\1>", " ");
        String text = htmlToText(body);
        sb.append("\nTekst van de pagina:\n").append(trim(text, lds.length() > 0 ? 8000 : 30000));
        m.parts.put(textPart(sb.toString()));
        if (m.coverUrl != null) m.coverUrl = htmlDecode(m.coverUrl);
    }

    // ---------------------------------------------------------------- helpers

    /** True when a caption/description clearly contains the ingredient list already. */
    static boolean looksLikeRecipe(String t) {
        if (t == null) return false;
        String low = t.toLowerCase();
        int hits = 0;
        for (String line : low.split("\n")) {
            if (line.matches(".*(\\d|½|¼|¾)\\s*(g|gr|gram|kg|ml|l|dl|cl|el|tl|eetlepels?|theelepels?|tbsp|tsp|cups?|oz|stuks?|teentjes?|tenen|blik|snufje)\\b.*")
                    || line.matches("^\\s*[-•*▪️✔️]?\\s*(\\d|½|¼|¾).*")) hits++;
        }
        boolean header = low.contains("ingrediënt") || low.contains("ingredient") || low.contains("benodigdheden") || low.contains("zutaten") || low.contains("ingrédients");
        boolean method = low.contains("bereiding") || low.contains("werkwijze") || low.contains("instructions") || low.contains("method")
                || low.contains("directions") || low.contains("stap 1") || low.contains("step 1") || low.contains("zubereitung")
                || low.matches("(?s).*\\n\\s*2[.)].*");
        return method && (hits >= 4 || (header && hits >= 2));
    }

    static String firstUrl(String text) {
        if (text == null) return null;
        Matcher mt = Pattern.compile("https?://[^\\s<>\"']+").matcher(text);
        if (!mt.find()) return null;
        return mt.group().replaceAll("[).,!?]+$", "");
    }

    static String hostLabel(String url) {
        String h = Uri.parse(url).getHost();
        if (h == null) return "Link";
        h = h.toLowerCase();
        if (h.contains("instagram")) return "Instagram";
        if (h.contains("youtu")) return "YouTube";
        if (h.contains("tiktok")) return "TikTok";
        if (h.contains("cheflix")) return "Cheflix";
        return h.replaceFirst("^www\\.", "");
    }

    static String mediaId(String shortcode) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        String sc = shortcode.length() > 11 ? shortcode.substring(0, 11) : shortcode;
        BigInteger id = BigInteger.ZERO;
        for (char c : sc.toCharArray()) id = id.multiply(BigInteger.valueOf(64)).add(BigInteger.valueOf(alphabet.indexOf(c)));
        return id.toString();
    }

    static JSONObject textPart(String t) throws Exception { return new JSONObject().put("text", t); }

    static JSONObject inline(String mime, byte[] data) throws Exception {
        return new JSONObject().put("inlineData", new JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(data, Base64.NO_WRAP)));
    }

    static JSONObject fileData(String mime, String uri) throws Exception {
        return new JSONObject().put("fileData", new JSONObject().put("mimeType", mime).put("fileUri", uri));
    }

    static JSONArray concat(JSONArray a, JSONArray b) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) out.put(a.get(i));
        for (int i = 0; i < b.length(); i++) out.put(b.get(i));
        return out;
    }

    static String group(String s, String regex) {
        if (s == null) return null;
        Matcher mt = Pattern.compile(regex).matcher(s);
        return mt.find() ? mt.group(1) : null;
    }

    static String meta(String html, String name) {
        String q = Pattern.quote(name);
        String v = group(html, "(?is)<meta[^>]+(?:property|name)=[\"']" + q + "[\"'][^>]*content=[\"']([^\"']*)[\"']");
        if (v == null) v = group(html, "(?is)<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']" + q + "[\"']");
        return v == null || v.trim().isEmpty() ? null : htmlDecode(v.trim());
    }

    static String unescapeJs(String s) {
        for (int i = 0; i < 3; i++) s = s.replace("\\\\", "\\").replace("\\/", "/").replace("\\u0026", "&").replace("\\\"", "\"");
        return s;
    }

    static String htmlDecode(String s) {
        if (s == null) return null;
        s = s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
             .replace("&lt;", "<").replace("&gt;", ">");
        String[][] named = {{"eacute","é"},{"egrave","è"},{"euml","ë"},{"ecirc","ê"},{"iuml","ï"},{"ouml","ö"},{"uuml","ü"},
            {"auml","ä"},{"agrave","à"},{"aacute","á"},{"ccedil","ç"},{"oacute","ó"},{"iacute","í"},{"uacute","ú"},{"ntilde","ñ"},
            {"hellip","…"},{"rsquo","’"},{"lsquo","‘"},{"ldquo","“"},{"rdquo","”"},{"ndash","–"},{"mdash","—"},{"deg","°"},
            {"frac12","½"},{"frac14","¼"},{"frac34","¾"},{"times","×"},{"bull","•"}};
        for (String[] e : named) s = s.replace("&" + e[0] + ";", e[1]);
        Matcher mt = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (mt.find()) {
            int cp;
            try { cp = Integer.parseInt(mt.group(2), mt.group(1).isEmpty() ? 10 : 16); } catch (Exception e) { cp = 32; }
            mt.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        mt.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }

    static String htmlToText(String html) {
        if (html == null) return null;
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|li|h[1-6]|tr)>", "\n").replaceAll("(?i)<li[^>]*>", "• ");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = htmlDecode(s);
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\\n[ \\n]*", "\n");
        return s.trim();
    }

    static String trim(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "…"; }
}
