package nl.bas.receptenboek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Minimal client for the Gemini REST API: structured recipe extraction and file upload. */
public class Gemini {
    static final String DEFAULT_MODEL = "gemini-flash-latest";
    static final String FALLBACK_MODEL = "gemini-flash-lite-latest";
    static final String BASE = "https://generativelanguage.googleapis.com";

    static class GeminiException extends Exception {
        GeminiException(String m) { super(m); }
    }

    static final String SYSTEM = String.join("\n",
        "Je bent een ervaren kok en receptredacteur. Je krijgt materiaal van een online receptpost:",
        "een caption, tekst van een website, afbeeldingen en/of een video (met geluid).",
        "Haal daar één compleet, kookbaar recept uit en schrijf het ALTIJD in correct, natuurlijk Nederlands,",
        "ook als het origineel in een andere taal is.",
        "",
        "Regels:",
        "- Combineer alle bronnen: caption, tekst in beeld, wat je ziet gebeuren in de video en wat er gezegd wordt.",
        "- Ingrediënten: zet hoeveelheden om naar metrische eenheden (g, kg, ml, l, el, tl, stuk, snuf, teen, blik, bos).",
        "  Cups, oz, lbs en °F reken je om naar gram/ml en °C. Gebruik 'el' voor eetlepel en 'tl' voor theelepel.",
        "  quantity is een getal (bijv. 0.5) of null als er geen hoeveelheid is. Zet de ingrediëntnaam in kleine letters,",
        "  behalve eigennamen. Gebruik 'group' voor onderdelen zoals 'Voor de saus'; anders een lege string.",
        "- Volledigheid is belangrijk. Ontbreken er in het materiaal hoeveelheden, ingrediënten, stappen, tijden of temperaturen,",
        "  kijk dan goed naar de video (wat er in de pan gaat, hoeveel, hoe lang) en luister naar wat er gezegd wordt.",
        "  Wat je dan nog niet zeker weet, vul je zelf aan met een realistische schatting als ervaren kok.",
        "  Markeer ELK ingrediënt of ELKE stap waarvan je iets hebt geschat of aangevuld met estimated=true;",
        "  alles wat letterlijk in caption, tekst, beeld of gesproken tekst staat krijgt estimated=false.",
        "- complete: true alleen als het originele materiaal het hele recept (alle hoeveelheden en de bereiding) zelf gaf.",
        "  Moest je iets schatten of aanvullen, zet complete dan op false en noem in missingInfo kort per punt wat er",
        "  ontbrak en wat je hebt aangevuld, bijv. 'Hoeveelheid pasta ontbrak, geschat op 400 g'.",
        "- Stappen: duidelijke, korte stappen in de gebiedende wijs, met temperaturen en tijden waar bekend.",
        "- servings: aantal porties als getal (schat als het onbekend is).",
        "- category: het type gerecht, precies één van: Voorgerecht, Hoofdgerecht, Nagerecht, Snack, Borrel, Sauzen, Overig.",
        "  Voorgerecht = soepen, salades en kleine starters; Hoofdgerecht = maaltijden (ook lunch of ontbijt als volledige maaltijd);",
        "  Nagerecht = desserts, taart, gebak en zoete toetjes; Snack = tussendoortjes; Borrel = hapjes en dips voor bij de borrel;",
        "  Sauzen = sauzen, dressings, marinades en kruidenmixen; Overig = alles wat nergens anders past (zoals drankjes of bijgerechten).",
        "- tags: 3 tot 6 korte Nederlandse tags in kleine letters, zoals 'pasta', 'vegetarisch', 'snel', 'airfryer', 'kip'.",
        "- description: één of twee uitnodigende zinnen over het gerecht.",
        "- Verzin geen recept als het materiaal geen recept bevat: zet dan isRecipe op false en leg in notRecipeReason uit waarom.",
        "- Negeer reclame, kortingscodes, hashtags en oproepen om te volgen.");

    static JSONObject schema() throws Exception {
        JSONObject ing = obj(
            "group", str(), "quantity", num(true), "unit", str(), "name", str(), "note", str(), "estimated", type("BOOLEAN"));
        ing.put("required", new JSONArray().put("name").put("quantity").put("unit").put("group").put("note").put("estimated"));
        JSONObject step = obj("group", str(), "text", str(), "estimated", type("BOOLEAN"));
        step.put("required", new JSONArray().put("text").put("group").put("estimated"));
        JSONObject root = obj(
            "isRecipe", type("BOOLEAN"),
            "complete", type("BOOLEAN"),
            "missingInfo", arr(str()),
            "notRecipeReason", str(),
            "title", str(),
            "description", str(),
            "servings", num(true),
            "prepMinutes", num(true),
            "cookMinutes", num(true),
            "totalMinutes", num(true),
            "category", type("STRING").put("format", "enum").put("enum", new JSONArray()
                    .put("Voorgerecht").put("Hoofdgerecht").put("Nagerecht").put("Snack").put("Borrel").put("Sauzen").put("Overig")),
            "cuisine", str(),
            "difficulty", str(),
            "ingredients", arr(ing),
            "steps", arr(step),
            "tags", arr(str()),
            "tips", arr(str()));
        root.put("required", new JSONArray().put("isRecipe").put("title").put("ingredients").put("steps")
                .put("servings").put("category").put("tags").put("description").put("totalMinutes").put("complete").put("missingInfo"));
        root.put("propertyOrdering", new JSONArray().put("isRecipe").put("notRecipeReason").put("title").put("description")
                .put("servings").put("prepMinutes").put("cookMinutes").put("totalMinutes").put("category").put("cuisine")
                .put("difficulty").put("ingredients").put("steps").put("tags").put("tips").put("complete").put("missingInfo"));
        return root;
    }

    static JSONObject type(String t) throws Exception { return new JSONObject().put("type", t); }
    static JSONObject str() throws Exception { return type("STRING"); }
    static JSONObject num(boolean nullable) throws Exception { return type("NUMBER").put("nullable", nullable); }
    static JSONObject arr(JSONObject items) throws Exception { return type("ARRAY").put("items", items); }
    static JSONObject obj(Object... kv) throws Exception {
        JSONObject props = new JSONObject();
        for (int i = 0; i < kv.length; i += 2) props.put((String) kv[i], kv[i + 1]);
        return type("OBJECT").put("properties", props);
    }

    static JSONObject extractRecipe(String apiKey, String model, JSONArray parts) throws Exception {
        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM))));
        body.put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));
        JSONObject gen = new JSONObject();
        gen.put("responseMimeType", "application/json");
        gen.put("responseSchema", schema());
        gen.put("temperature", 0.2);
        // Little "thinking" is needed to copy a recipe; this makes answers much faster.
        gen.put("thinkingConfig", new JSONObject().put("thinkingBudget", 512));
        if (hasMedia(parts)) gen.put("mediaResolution", "MEDIA_RESOLUTION_LOW");
        body.put("generationConfig", gen);

        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("x-goog-api-key", apiKey);
        // The free Flash models are sometimes overloaded (503) or simply never answer. Don't wait minutes:
        // give the chosen model a limited time, then fall back to Flash-Lite, which has its own quota and is fast.
        String[] models = model.equals(FALLBACK_MODEL) ? new String[]{model} : new String[]{model, FALLBACK_MODEL};
        boolean media = hasMedia(parts);
        Net.Response r = null;
        for (int k = 0; k < models.length; k++) {
            boolean lastTry = k == models.length - 1;
            int timeout = lastTry ? 150000 : (media ? 75000 : 45000);
            String url = BASE + "/v1beta/models/" + models[k] + ":generateContent";
            try {
                r = Net.post(url, h, body.toString().getBytes("UTF-8"), timeout);
                if (r.code == 400 && (r.text().toLowerCase().contains("thinking") || r.text().contains("mediaResolution") || r.text().contains("media_resolution"))) {
                    // Model that does not accept these speed settings: try once without them.
                    gen.remove("thinkingConfig");
                    gen.remove("mediaResolution");
                    r = Net.post(url, h, body.toString().getBytes("UTF-8"), timeout);
                }
            } catch (java.io.IOException e) {
                if (lastTry) throw new GeminiException("Gemini reageert niet (time-out). De gratis servers van Google zijn nu waarschijnlijk erg druk. Probeer het over een tijdje opnieuw.");
                continue;
            }
            if (r.code == 200) break;
            if (!lastTry && (r.code == 429 || r.code == 404 || r.code >= 500)) continue;
            throw new GeminiException(explain(r));
        }
        JSONObject resp = new JSONObject(r.text());
        JSONArray cands = resp.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) {
            JSONObject fb = resp.optJSONObject("promptFeedback");
            throw new GeminiException("Gemini gaf geen antwoord" + (fb != null ? " (" + fb.optString("blockReason") + ")" : ""));
        }
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        StringBuilder sb = new StringBuilder();
        if (content != null && content.optJSONArray("parts") != null) {
            JSONArray ps = content.getJSONArray("parts");
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.getJSONObject(i);
                if (!p.optBoolean("thought")) sb.append(p.optString("text"));
            }
        }
        String text = sb.toString().trim();
        if (text.startsWith("```")) text = text.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        if (text.isEmpty()) throw new GeminiException("Gemini gaf een leeg antwoord (" + cands.getJSONObject(0).optString("finishReason") + ")");
        return new JSONObject(text);
    }

    static boolean hasMedia(JSONArray parts) {
        for (int i = 0; i < parts.length(); i++) if (parts.optJSONObject(i) != null && parts.optJSONObject(i).has("fileData")) return true;
        return false;
    }

    static String explain(Net.Response r) {
        String msg = "";
        try { msg = new JSONObject(r.text()).getJSONObject("error").optString("message"); } catch (Exception ignored) {}
        if (r.code == 429) return "De gratis daglimiet van Gemini is bereikt. Probeer het later opnieuw.";
        if (r.code == 400 && msg.toLowerCase().contains("api key")) return "Je Gemini API-sleutel klopt niet. Controleer hem bij Instellingen.";
        if (r.code == 403) return "Gemini weigert de sleutel (403). Controleer je API-sleutel bij Instellingen. " + msg;
        if (r.code == 404) return "Het Gemini-model bestaat niet (meer). Pas het model aan bij Instellingen. " + msg;
        if (r.code >= 500) return "Gemini is even niet bereikbaar (" + r.code + "). Probeer het opnieuw.";
        return "Gemini-fout " + r.code + ": " + msg;
    }

    /** Uploads media through the resumable Files API and waits until it is ready. Returns {uri, mimeType}. */
    static String[] uploadFile(String apiKey, java.io.File data, String mime) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("x-goog-api-key", apiKey);
        h.put("X-Goog-Upload-Protocol", "resumable");
        h.put("X-Goog-Upload-Command", "start");
        h.put("X-Goog-Upload-Header-Content-Length", String.valueOf(data.length()));
        h.put("X-Goog-Upload-Header-Content-Type", mime);
        h.put("Content-Type", "application/json");
        JSONObject meta = new JSONObject().put("file", new JSONObject().put("display_name", "recept-" + System.currentTimeMillis()));
        Net.Response start = Net.post(BASE + "/upload/v1beta/files", h, meta.toString().getBytes("UTF-8"));
        String uploadUrl = start.header("x-goog-upload-url");
        if (start.code != 200 || uploadUrl == null) throw new GeminiException(explain(start));

        Map<String, String> h2 = new HashMap<String, String>();
        h2.put("X-Goog-Upload-Offset", "0");
        h2.put("X-Goog-Upload-Command", "upload, finalize");
        h2.put("Content-Type", mime);
        Net.Response up = Net.postFile(uploadUrl, h2, data);
        if (up.code != 200) throw new GeminiException("Uploaden van video mislukt: " + explain(up));
        JSONObject file = new JSONObject(up.text()).getJSONObject("file");
        String name = file.getString("name");
        String state = file.optString("state");
        Map<String, String> h3 = new HashMap<String, String>();
        h3.put("x-goog-api-key", apiKey);
        h3.put("Accept", "application/json");
        for (int i = 0; i < 60 && !"ACTIVE".equals(state); i++) {
            if ("FAILED".equals(state)) throw new GeminiException("Gemini kon de video niet verwerken");
            Thread.sleep(2000);
            Net.Response g = Net.get(BASE + "/v1beta/" + name, h3, 1 << 20);
            if (g.code == 200) { file = new JSONObject(g.text()); state = file.optString("state"); }
        }
        if (!"ACTIVE".equals(state)) throw new GeminiException("Video verwerken duurde te lang");
        return new String[]{file.getString("uri"), file.optString("mimeType", mime)};
    }
}
