package nl.bas.receptenboek;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public class Net {
    static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    static class Response {
        int code;
        byte[] body;
        String finalUrl;
        Map<String, java.util.List<String>> headers;
        String text() { try { return new String(body, "UTF-8"); } catch (Exception e) { return ""; } }
        String header(String name) {
            if (headers == null) return null;
            for (Map.Entry<String, java.util.List<String>> e : headers.entrySet())
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            return null;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** GET with browser-like headers and the cookies of the in-app browser (for Instagram login). */
    static Response get(String url, Map<String, String> extra, long maxBytes) throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", MOBILE_UA);
            c.setRequestProperty("Accept-Language", "nl-NL,nl;q=0.9,en;q=0.8");
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            try {
                String cookie = CookieManager.getInstance().getCookie(current);
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
            } catch (Throwable ignored) {}
            if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            Response r = new Response();
            r.code = code;
            r.finalUrl = current;
            r.headers = c.getHeaderFields();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) { r.body = new byte[0]; return r; }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n; long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) { in.close(); throw new IOException("Bestand is te groot"); }
                bos.write(buf, 0, n);
            }
            in.close();
            r.body = bos.toByteArray();
            return r;
        }
        throw new IOException("Te veel doorverwijzingen");
    }

    /** POST raw bytes; returns the response (also for error codes). */
    static Response post(String url, Map<String, String> headers, byte[] body) throws IOException {
        return post(url, headers, body, 180000);
    }

    static Response post(String url, Map<String, String> headers, byte[] body, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setFixedLengthStreamingMode(body.length);
        OutputStream os = c.getOutputStream();
        os.write(body);
        os.close();
        Response r = new Response();
        r.code = c.getResponseCode();
        r.headers = c.getHeaderFields();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = in == null ? new byte[0] : readAll(in);
        r.finalUrl = url;
        return r;
    }

    /** Decodes an image, scales it down so the longest side is at most maxSide, and writes a JPEG. */
    static byte[] scaleJpeg(byte[] data, int maxSide, int quality) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        float scale = Math.min(1f, (float) maxSide / Math.max(w, h));
        if (scale < 1f) {
            Bitmap s = Bitmap.createScaledBitmap(b, Math.round(w * scale), Math.round(h * scale), true);
            if (s != b) b.recycle();
            b = s;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        b.recycle();
        return bos.toByteArray();
    }

    static void writeFile(File f, byte[] data) throws IOException {
        FileOutputStream os = new FileOutputStream(f);
        os.write(data);
        os.close();
    }

    /** Streams a URL to a file (for videos), with a size limit. */
    static void download(String url, File out, long maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", MOBILE_UA);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("Download mislukt (" + code + ")");
        InputStream in = c.getInputStream();
        FileOutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n; long total = 0;
        try {
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) throw new IOException("Video is te groot");
                os.write(buf, 0, n);
            }
        } finally { os.close(); in.close(); }
    }

    /** POSTs a file body without loading it into memory. */
    static Response postFile(String url, Map<String, String> headers, File f) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(300000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setFixedLengthStreamingMode(f.length());
        OutputStream os = c.getOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        in.close();
        os.close();
        Response r = new Response();
        r.code = c.getResponseCode();
        r.headers = c.getHeaderFields();
        InputStream rin = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = rin == null ? new byte[0] : readAll(rin);
        r.finalUrl = url;
        return r;
    }
}
