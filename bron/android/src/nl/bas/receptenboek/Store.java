package nl.bas.receptenboek;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Recipes live in one JSON file in app storage; cover images as JPEGs next to it. */
public class Store {
    private static Store instance;
    private final Context ctx;
    private final File file;
    private JSONArray recipes;

    static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    private Store(Context c) {
        ctx = c;
        file = new File(c.getFilesDir(), "recipes.json");
        load();
    }

    SharedPreferences prefs() { return ctx.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    File imageDir() {
        File d = new File(ctx.getFilesDir(), "images");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void load() {
        try {
            if (file.exists()) {
                recipes = new JSONArray(new String(Net.readAll(new FileInputStream(file)), "UTF-8"));
                return;
            }
        } catch (Exception e) {
            // Keep the broken file aside rather than overwriting it.
            file.renameTo(new File(file.getPath() + ".broken-" + System.currentTimeMillis()));
        }
        recipes = new JSONArray();
    }

    private void save() {
        try {
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            os.write(recipes.toString().getBytes("UTF-8"));
            os.getFD().sync();
            os.close();
            // Keep one previous version as a safety net.
            File bak = new File(file.getPath() + ".bak");
            if (file.exists()) { bak.delete(); file.renameTo(bak); }
            tmp.renameTo(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    synchronized String recipesJson() { return recipes.toString(); }

    synchronized void upsert(JSONObject r) {
        String id = r.optString("id");
        for (int i = 0; i < recipes.length(); i++) {
            JSONObject o = recipes.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                try { recipes.put(i, r); } catch (Exception ignored) {}
                save();
                return;
            }
        }
        recipes.put(r);
        save();
    }

    synchronized void delete(String id) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < recipes.length(); i++) {
            JSONObject o = recipes.optJSONObject(i);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) {
                new File(imageDir(), id + ".jpg").delete();
                continue;
            }
            out.put(o);
        }
        recipes = out;
        save();
    }

    synchronized String exportBackup() throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "receptenboek");
        root.put("format", 1);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("recipes", new JSONArray(recipes.toString()));
        JSONObject imgs = new JSONObject();
        for (int i = 0; i < recipes.length(); i++) {
            String id = recipes.getJSONObject(i).optString("id");
            File f = new File(imageDir(), id + ".jpg");
            if (f.exists()) imgs.put(id, Base64.encodeToString(Net.readAll(new FileInputStream(f)), Base64.NO_WRAP));
        }
        root.put("images", imgs);
        return root.toString();
    }

    /** Merges a backup: recipes with an id that already exists are replaced. */
    synchronized int importBackup(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray in = root.getJSONArray("recipes");
        JSONObject imgs = root.optJSONObject("images");
        int n = 0;
        for (int i = 0; i < in.length(); i++) {
            JSONObject r = in.getJSONObject(i);
            String id = r.optString("id");
            if (id.isEmpty()) continue;
            if (imgs != null && imgs.has(id)) {
                FileOutputStream os = new FileOutputStream(new File(imageDir(), id + ".jpg"));
                os.write(Base64.decode(imgs.getString(id), Base64.DEFAULT));
                os.close();
            }
            boolean replaced = false;
            for (int j = 0; j < recipes.length(); j++) {
                if (id.equals(recipes.getJSONObject(j).optString("id"))) { recipes.put(j, r); replaced = true; break; }
            }
            if (!replaced) recipes.put(r);
            n++;
        }
        save();
        return n;
    }
}
