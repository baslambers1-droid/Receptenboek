/* Receptenboek for iPhone/web: implements the same bridge the Android app offers (window.Android),
   with storage in IndexedDB and recipe reading through the Gemini API straight from the browser. */
(function () {
  "use strict";
  const BASE = "https://generativelanguage.googleapis.com";
  const DEFAULT_MODEL = "gemini-flash-latest";
  const FALLBACK_MODEL = "gemini-flash-lite-latest";
  const VERSION = "web 1.0";

  // ------------------------------------------------------------------ storage (IndexedDB)
  let db;
  function openDb() {
    return new Promise((res, rej) => {
      const r = indexedDB.open("receptenboek", 1);
      r.onupgradeneeded = () => {
        r.result.createObjectStore("recipes", { keyPath: "id" });
        r.result.createObjectStore("images");
      };
      r.onsuccess = () => res(r.result);
      r.onerror = () => rej(r.error);
    });
  }
  function tx(store, mode, fn) {
    return new Promise((res, rej) => {
      const t = db.transaction(store, mode);
      const out = fn(t.objectStore(store));
      t.oncomplete = () => res(out && out.result !== undefined ? out.result : undefined);
      t.onerror = () => rej(t.error);
    });
  }
  const recipes = new Map();   // id -> recipe
  const imgUrl = new Map();    // id -> object URL

  async function loadAll() {
    db = await openDb();
    const all = await tx("recipes", "readonly", (s) => s.getAll());
    all.forEach((r) => recipes.set(r.id, r));
    const keys = await tx("images", "readonly", (s) => s.getAllKeys());
    for (const k of keys) {
      const blob = await tx("images", "readonly", (s) => s.get(k));
      if (blob) imgUrl.set(k, URL.createObjectURL(blob));
    }
    try { if (navigator.storage && navigator.storage.persist) await navigator.storage.persist(); } catch (e) {}
  }
  function putRecipe(r) { recipes.set(r.id, r); tx("recipes", "readwrite", (s) => s.put(r)); }
  async function putImage(id, blob) {
    await tx("images", "readwrite", (s) => s.put(blob, id));
    if (imgUrl.has(id)) URL.revokeObjectURL(imgUrl.get(id));
    imgUrl.set(id, URL.createObjectURL(blob));
  }
  function forUi(r) {
    const c = Object.assign({}, r);
    c.image = imgUrl.get(r.id) || r.imageUrl || "";
    return c;
  }

  // ------------------------------------------------------------------ settings
  const S = {
    get(k, d) { const v = localStorage.getItem("rb_" + k); return v == null ? d : v; },
    set(k, v) { localStorage.setItem("rb_" + k, v == null ? "" : String(v).trim()); },
  };

  // ------------------------------------------------------------------ images
  function scaleToJpeg(blob, maxSide) {
    return new Promise((res) => {
      const url = URL.createObjectURL(blob);
      const img = new Image();
      img.onload = () => {
        const sc = Math.min(1, maxSide / Math.max(img.width, img.height));
        const c = document.createElement("canvas");
        c.width = Math.round(img.width * sc); c.height = Math.round(img.height * sc);
        c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
        URL.revokeObjectURL(url);
        c.toBlob((b) => res(b), "image/jpeg", 0.85);
      };
      img.onerror = () => { URL.revokeObjectURL(url); res(null); };
      img.src = url;
    });
  }
  function blobToBase64(blob) {
    return new Promise((res) => { const fr = new FileReader(); fr.onload = () => res(String(fr.result).split(",")[1]); fr.readAsDataURL(blob); });
  }
  function b64ToBlob(b64, mime) {
    const bin = atob(b64.replace(/\s/g, "")); const a = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
    return new Blob([a], { type: mime || "image/jpeg" });
  }

  // ------------------------------------------------------------------ text helpers (same as Android)
  function group(s, re) { const m = s && s.match(re); return m ? m[1] : null; }
  function htmlDecode(s) {
    if (s == null) return null;
    const t = document.createElement("textarea"); t.innerHTML = s; return t.value;
  }
  function htmlToText(html) {
    if (html == null) return null;
    let s = html.replace(/<br\s*\/?>/gi, "\n").replace(/<\/(p|div|li|h[1-6]|tr)>/gi, "\n").replace(/<li[^>]*>/gi, "• ");
    s = s.replace(/<[^>]+>/g, " ");
    s = htmlDecode(s);
    return s.replace(/[ \t\f\r\v]+/g, " ").replace(/ *\n[ \n]*/g, "\n").trim();
  }
  function meta(html, name) {
    const q = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    let v = group(html, new RegExp(`<meta[^>]+(?:property|name)=["']${q}["'][^>]*content=["']([^"']*)["']`, "is"));
    if (v == null) v = group(html, new RegExp(`<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']${q}["']`, "is"));
    return v && v.trim() ? htmlDecode(v.trim()) : null;
  }
  function unescapeJs(s) {
    for (let i = 0; i < 3; i++) s = s.split("\\\\").join("\\").split("\\/").join("/").split("\\u0026").join("&").split('\\"').join('"');
    return s;
  }
  function trim(s, n) { return s.length <= n ? s : s.slice(0, n) + "…"; }
  function firstUrl(t) { const m = t && t.match(/https?:\/\/[^\s<>"']+/); return m ? m[0].replace(/[).,!?]+$/, "") : null; }
  function hostLabel(u) {
    try {
      const h = new URL(u).hostname.toLowerCase();
      if (h.includes("instagram")) return "Instagram";
      if (h.includes("youtu")) return "YouTube";
      if (h.includes("tiktok")) return "TikTok";
      if (h.includes("cheflix")) return "Cheflix";
      return h.replace(/^www\./, "");
    } catch (e) { return "Link"; }
  }
  function looksLikeRecipe(t) {
    if (!t) return false;
    const low = t.toLowerCase();
    let hits = 0;
    low.split("\n").forEach((line) => {
      if (/(\d|½|¼|¾)\s*(g|gr|gram|kg|ml|l|dl|cl|el|tl|eetlepels?|theelepels?|tbsp|tsp|cups?|oz|stuks?|teentjes?|tenen|blik|snufje)\b/.test(line)
        || /^\s*[-•*]?\s*(\d|½|¼|¾)/.test(line)) hits++;
    });
    const header = /ingrediënt|ingredient|benodigdheden|zutaten|ingrédients/.test(low);
    const method = /bereiding|werkwijze|instructions|method|directions|stap 1|step 1|zubereitung/.test(low) || /\n\s*2[.)]/.test(low);
    return method && (hits >= 4 || (header && hits >= 2));
  }

  // ------------------------------------------------------------------ Gemini
  const T = (t) => ({ type: t });
  const STR = T("STRING");
  const NUM = { type: "NUMBER", nullable: true };
  const OBJ = (props, required) => ({ type: "OBJECT", properties: props, required });
  const SCHEMA = OBJ({
    isRecipe: T("BOOLEAN"), complete: T("BOOLEAN"), missingInfo: { type: "ARRAY", items: STR }, notRecipeReason: STR,
    title: STR, description: STR, servings: NUM, prepMinutes: NUM, cookMinutes: NUM, totalMinutes: NUM,
    category: { type: "STRING", format: "enum", enum: ["Voorgerecht", "Hoofdgerecht", "Nagerecht", "Snack", "Borrel", "Sauzen", "Overig"] },
    cuisine: STR, difficulty: STR,
    ingredients: { type: "ARRAY", items: OBJ({ group: STR, quantity: NUM, unit: STR, name: STR, note: STR, estimated: T("BOOLEAN") }, ["name", "quantity", "unit", "group", "note", "estimated"]) },
    steps: { type: "ARRAY", items: OBJ({ group: STR, text: STR, estimated: T("BOOLEAN") }, ["text", "group", "estimated"]) },
    tags: { type: "ARRAY", items: STR }, tips: { type: "ARRAY", items: STR },
  }, ["isRecipe", "title", "ingredients", "steps", "servings", "category", "tags", "description", "totalMinutes", "complete", "missingInfo"]);

  class GeminiError extends Error {}

  function explain(code, msg) {
    if (code === 429) return "De gratis daglimiet van Gemini is bereikt. Probeer het later opnieuw.";
    if (code === 400 && /api key/i.test(msg)) return "Je Gemini API-sleutel klopt niet. Controleer hem bij Instellingen.";
    if (code === 403) return "Gemini weigert de sleutel (403). Controleer je API-sleutel bij Instellingen.";
    if (code === 404) return "Het Gemini-model bestaat niet (meer). Pas het model aan bij Instellingen.";
    if (code >= 500) return "Gemini is even niet bereikbaar (" + code + "). Probeer het opnieuw.";
    return "Gemini-fout " + code + ": " + msg;
  }

  async function postJson(url, key, body, timeoutMs) {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeoutMs);
    try {
      const r = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json", "x-goog-api-key": key }, body: JSON.stringify(body), signal: ctl.signal });
      const text = await r.text();
      return { code: r.status, text };
    } finally { clearTimeout(timer); }
  }

  async function extractRecipe(key, model, parts) {
    const media = parts.some((p) => p.fileData);
    const gen = { responseMimeType: "application/json", responseSchema: SCHEMA, temperature: 0.2, thinkingConfig: { thinkingBudget: 512 } };
    if (media) gen.mediaResolution = "MEDIA_RESOLUTION_LOW";
    const body = { systemInstruction: { parts: [{ text: window.RB_SYSTEM }] }, contents: [{ role: "user", parts }], generationConfig: gen };
    const models = model === FALLBACK_MODEL ? [model] : [model, FALLBACK_MODEL];
    let r;
    for (let k = 0; k < models.length; k++) {
      const last = k === models.length - 1;
      const timeout = last ? 150000 : (media ? 75000 : 45000);
      const url = `${BASE}/v1beta/models/${models[k]}:generateContent`;
      try {
        r = await postJson(url, key, body, timeout);
        if (r.code === 400 && /thinking|mediaResolution|media_resolution/i.test(r.text)) {
          delete gen.thinkingConfig; delete gen.mediaResolution;
          r = await postJson(url, key, body, timeout);
        }
      } catch (e) {
        if (last) throw new GeminiError("Gemini reageert niet (time-out). De gratis servers van Google zijn nu waarschijnlijk erg druk. Probeer het over een tijdje opnieuw.");
        continue;
      }
      if (r.code === 200) break;
      if (!last && (r.code === 429 || r.code === 404 || r.code >= 500)) continue;
      let msg = ""; try { msg = JSON.parse(r.text).error.message; } catch (e) {}
      throw new GeminiError(explain(r.code, msg));
    }
    const resp = JSON.parse(r.text);
    const cand = (resp.candidates || [])[0];
    if (!cand) throw new GeminiError("Gemini gaf geen antwoord");
    let text = ((cand.content && cand.content.parts) || []).filter((p) => !p.thought).map((p) => p.text || "").join("").trim();
    text = text.replace(/^```(json)?/, "").replace(/```$/, "").trim();
    if (!text) throw new GeminiError("Gemini gaf een leeg antwoord (" + cand.finishReason + ")");
    return JSON.parse(text);
  }

  async function uploadFile(key, blob, mime) {
    const r = await fetch(`${BASE}/upload/v1beta/files?uploadType=media`, { method: "POST", headers: { "Content-Type": mime, "x-goog-api-key": key }, body: blob });
    const t = await r.text();
    if (r.status !== 200) { let m = ""; try { m = JSON.parse(t).error.message; } catch (e) {} throw new GeminiError("Uploaden mislukt: " + explain(r.status, m)); }
    const f = JSON.parse(t).file;
    return waitActive(key, f);
  }

  async function waitActive(key, f) {
    let state = f.state;
    const name = f.name || (f.uri || "").replace(/^.*\/v1beta\//, "");
    for (let i = 0; i < 60 && state !== "ACTIVE"; i++) {
      if (state === "FAILED") throw new GeminiError("Gemini kon de video niet verwerken");
      await new Promise((r) => setTimeout(r, 2000));
      const g = await fetch(`${BASE}/v1beta/${name}`, { headers: { "x-goog-api-key": key } });
      if (g.ok) { f = await g.json(); state = f.state; }
    }
    if (state !== "ACTIVE") throw new GeminiError("Video verwerken duurde te lang");
    return { uri: f.uri, mimeType: f.mimeType };
  }

  // ------------------------------------------------------------------ jobs
  const jobs = [];
  function jobsChanged() { if (window.App) App.onJobsChanged(); }
  function stage(j, s) { j.stage = s; jobsChanged(); }

  function submit(input) {
    const j = { id: "j" + Date.now() + Math.floor(Math.random() * 1000), status: "busy", stage: "In de wachtrij", error: "", input };
    const url = input.url || firstUrl(input.text);
    j.source = url ? hostLabel(url) : (input.files && input.files.length) || input.fileUri ? "Foto/video" : "Tekst";
    j.text = input.text || url || "";
    j.files = (input.files || []).length;
    jobs.unshift(j);
    jobsChanged();
    run(j);
  }

  async function run(j) {
    try {
      const r = await process(j);
      jobs.splice(jobs.indexOf(j), 1);
      if (window.App) App.onRecipesChanged();
      notify("Recept toegevoegd", r.title);
    } catch (e) {
      j.status = "error";
      j.error = e.message || String(e);
    }
    jobsChanged();
  }

  function notify(title, body) {
    try { if (document.hidden && window.Notification && Notification.permission === "granted") new Notification(title, { body }); } catch (e) {}
  }

  async function process(j) {
    const key = S.get("apiKey", "");
    let model = S.get("model", DEFAULT_MODEL) || DEFAULT_MODEL;
    if (!key) throw new Error("Vul eerst je gratis Gemini API-sleutel in bij Instellingen.");
    const inp = j.input;
    const m = { parts: [], media: [], notes: [], coverBlob: null, coverUrl: null, sourceUrl: "", sourceType: "tekst", author: "", skippedVideo: null };
    const url = inp.url || firstUrl(inp.text);
    const P = (t) => m.parts.push({ text: t });

    if (url) {
      m.sourceUrl = url;
      stage(j, "Post lezen");
      const host = (() => { try { return new URL(url).hostname.toLowerCase(); } catch (e) { return ""; } })();
      if (host.includes("instagram")) instagram(inp, m, P);
      else if (host.includes("youtu")) await youtube(url, m, P);
      else website(url, inp.html, m, P);
      const rest = (inp.text || "").replace(url, "").trim();
      if (rest) P("Gedeelde tekst bij de link:\n" + rest);
    } else if (inp.text) {
      P("Gedeelde tekst:\n" + inp.text);
    }

    // Video/photo already uploaded by the iPhone Opdracht.
    if (inp.fileUri) {
      stage(j, "Video klaarzetten");
      const f = await waitActive(key, { uri: inp.fileUri, state: "PROCESSING" });
      if (inp.videoSkippable && looksLikeRecipe(m.caption)) m.skippedVideo = f;
      else m.media.push({ fileData: { mimeType: inp.fileMime || f.mimeType, fileUri: f.uri } });
      if (!url) m.sourceType = (inp.fileMime || "").startsWith("image/") ? "foto" : "video";
    }
    if (inp.thumb) {
      const b = b64ToBlob(inp.thumb, "image/jpeg");
      m.coverBlob = b;
      if (!m.media.length) m.parts.push({ inlineData: { mimeType: "image/jpeg", data: inp.thumb.replace(/\s/g, "") } });
    }
    // Photos/videos picked inside the app.
    for (const file of inp.files || []) {
      if (file.type.startsWith("image/")) {
        const jpg = await scaleToJpeg(file, 1600);
        if (!jpg) continue;
        if (!m.coverBlob) m.coverBlob = jpg;
        m.parts.push({ inlineData: { mimeType: "image/jpeg", data: await blobToBase64(jpg) } });
        if (!url) m.sourceType = "foto";
      } else if (file.type.startsWith("video/")) {
        stage(j, "Video uploaden naar Gemini");
        const f = await uploadFile(key, file, file.type);
        m.media.push({ fileData: { mimeType: f.mimeType, fileUri: f.uri } });
        if (!url) m.sourceType = "video";
      }
    }
    if (!m.parts.length && !m.media.length) throw new Error("Er was niets om te verwerken. Deel een link, tekst, foto of video.");
    if (m.notes.length) P(m.notes.join("\n"));
    P("Haal hieruit het recept en schrijf het in het Nederlands volgens het schema.");

    stage(j, "Gemini leest het recept");
    let r;
    try {
      r = await extractRecipe(key, model, m.media.concat(m.parts));
    } catch (e) {
      if (e instanceof GeminiError && m.media.length && m.parts.length > 1 && !/daglimiet/.test(e.message)) {
        stage(j, "Opnieuw, zonder video");
        r = await extractRecipe(key, model, m.parts);
      } else throw e;
    }
    if (r.isRecipe !== false && r.complete === false && !m.media.length && m.skippedVideo) {
      try {
        stage(j, "Video bekijken voor ontbrekende details");
        m.media.push({ fileData: { mimeType: m.skippedVideo.mimeType, fileUri: m.skippedVideo.uri } });
        r = await extractRecipe(key, model, m.media.concat(m.parts));
      } catch (e) { /* keep caption-based result */ }
    }
    if (r.isRecipe === false) throw new Error("Geen recept gevonden" + (r.notRecipeReason ? ": " + r.notRecipeReason : "."));

    stage(j, "Opslaan in je receptenboek");
    const id = "r" + Date.now();
    delete r.isRecipe; delete r.notRecipeReason;
    Object.assign(r, { id, createdAt: Date.now(), sourceUrl: m.sourceUrl, sourceType: m.sourceType, author: m.author, favorite: false, notes: "" });
    let cover = m.coverBlob;
    if (!cover && m.coverUrl) {
      try { const resp = await fetch(m.coverUrl, { mode: "cors" }); if (resp.ok) cover = await resp.blob(); } catch (e) { r.imageUrl = m.coverUrl; }
    }
    if (cover) { const jpg = await scaleToJpeg(cover, 1200); if (jpg) { await putImage(id, jpg); r.image = "img:" + id; } }
    putRecipe(r);
    return r;
  }

  function instagram(inp, m, P) {
    m.sourceType = "instagram";
    const html = inp.html || "";
    let caption = null;
    const c = html.match(/<div class="Caption">([\s\S]*?)<div class="CaptionComments">/);
    if (c) caption = htmlToText(c[1].replace(/<a class="CaptionUsername"[\s\S]*?<\/a>/, "")) || null;
    if (!caption) {
      const un = unescapeJs(html);
      caption = group(un, /"edge_media_to_caption":\{"edges":\[\{"node":\{"text":"([\s\S]*?)"\}/);
    }
    if (!caption) {
      const d = meta(html, "og:description");
      if (d) caption = group(d, /:\s*"([\s\S]*)"\s*$/) || d;
    }
    if (!caption && html.trim() && !/<\w/.test(html)) caption = trim(html.trim(), 8000); // plain text instead of HTML
    const a = group(html, /class="UsernameText">([^<]+)</);
    if (a) m.author = a.trim();
    m.caption = caption;
    if (caption) P("Instagram-caption" + (m.author ? " van @" + m.author : "") + ":\n" + caption);
    else m.notes.push("(De caption van deze Instagram-post kon niet worden gelezen.)");
    if (!caption && !inp.fileUri && !inp.thumb)
      throw new Error("Instagram gaf deze post niet vrij. Probeer het opnieuw, of deel een schermopname van de reel.");
  }

  async function youtube(url, m, P) {
    m.sourceType = "youtube";
    const id = group(url, /(?:v=|youtu\.be\/|shorts\/|live\/|embed\/)([A-Za-z0-9_-]{11})/);
    if (!id) return;
    const watch = "https://www.youtube.com/watch?v=" + id;
    m.sourceUrl = url.includes("shorts/") ? "https://www.youtube.com/shorts/" + id : watch;
    m.coverUrl = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
    try {
      const o = await fetch("https://www.youtube.com/oembed?format=json&url=" + encodeURIComponent(watch));
      if (o.ok) { const j = await o.json(); m.author = j.author_name || ""; P(`YouTube-video: "${j.title}" van ${m.author}`); }
    } catch (e) {}
    m.media.push({ fileData: { fileUri: watch } });
  }

  function website(url, html, m, P) {
    m.sourceType = "web";
    if (!html) { P("Link: " + url + "\n(De pagina kon niet worden opgehaald; gebruik wat je weet van deze link alleen als het duidelijk een recept is.)"); return; }
    if (!/<\w/.test(html)) { P("Webpagina: " + url + "\nTekst van de pagina:\n" + trim(html, 30000)); return; }
    let out = "Webpagina: " + url + "\n";
    const title = meta(html, "og:title") || htmlToText(group(html, /<title[^>]*>([\s\S]*?)<\/title>/i));
    if (title) out += "Titel: " + title + "\n";
    const desc = meta(html, "og:description") || meta(html, "description");
    if (desc) out += "Omschrijving: " + desc + "\n";
    const site = meta(html, "og:site_name");
    if (site) m.author = site;
    m.coverUrl = meta(html, "og:image");
    let lds = "";
    const re = /<script[^>]*application\/ld\+json[^>]*>([\s\S]*?)<\/script>/gi;
    let mt;
    while ((mt = re.exec(html))) if (/Recipe|recipeIngredient/.test(mt[1])) lds += mt[1].trim() + "\n";
    if (lds) out += "\nGestructureerde receptgegevens (JSON-LD):\n" + trim(lds, 25000) + "\n";
    const body = html.replace(/<(script|style|noscript|svg|nav|footer|header|form|iframe)[^>]*>[\s\S]*?<\/\1>/gi, " ");
    out += "\nTekst van de pagina:\n" + trim(htmlToText(body), lds ? 8000 : 30000);
    P(out);
  }

  // ------------------------------------------------------------------ paste from the iPhone Opdracht
  async function pasteFromShortcut() {
    let txt = "";
    try { txt = await navigator.clipboard.readText(); } catch (e) {
      return toast("Plakken lukte niet. Sta plakken toe en probeer opnieuw.");
    }
    txt = (txt || "").trim();
    let payload = null;
    try { payload = JSON.parse(txt); } catch (e) {}
    if (payload && payload.app === "receptenboek") {
      const input = {
        url: payload.url || "", text: payload.text || "", html: payload.html || "",
        fileUri: payload.fileUri || "", fileMime: payload.fileMime || "", thumb: payload.thumb || "",
        videoSkippable: true,
      };
      submit(input);
      try { await navigator.clipboard.writeText(""); } catch (e) {}
      return true;
    }
    if (firstUrl(txt) || txt.length > 20) { submit({ text: txt }); return true; }
    toast("Geen recept op het klembord gevonden. Deel eerst een post naar de Opdracht \"Receptenboek\".");
    return false;
  }
  function toast(msg) { if (window.App && App.toast) App.toast(msg); else alert(msg); }

  // ------------------------------------------------------------------ backup
  async function exportBackup() {
    const imgs = {};
    for (const id of recipes.keys()) {
      const blob = await tx("images", "readonly", (s) => s.get(id));
      if (blob) imgs[id] = await blobToBase64(blob);
    }
    const data = JSON.stringify({ app: "receptenboek", format: 1, exportedAt: Date.now(), recipes: [...recipes.values()], images: imgs });
    const file = new File([data], `receptenboek-backup-${new Date().toISOString().slice(0, 10)}.json`, { type: "application/json" });
    if (navigator.canShare && navigator.canShare({ files: [file] })) {
      try { await navigator.share({ files: [file], title: "Receptenboek back-up" }); return; } catch (e) { if (e.name === "AbortError") return; }
    }
    const a = document.createElement("a"); a.href = URL.createObjectURL(file); a.download = file.name; a.click();
  }
  function importBackup() {
    const inp = document.createElement("input"); inp.type = "file"; inp.accept = "application/json,.json";
    inp.onchange = async () => {
      try {
        const root = JSON.parse(await inp.files[0].text());
        let n = 0;
        for (const r of root.recipes || []) {
          if (!r.id) continue;
          if (root.images && root.images[r.id]) { await putImage(r.id, b64ToBlob(root.images[r.id], "image/jpeg")); r.image = "img:" + r.id; }
          putRecipe(r); n++;
        }
        toast(n + " recepten geïmporteerd");
        if (window.App) App.onRecipesChanged();
      } catch (e) { toast("Terugzetten mislukt: " + e.message); }
    };
    inp.click();
  }

  // ------------------------------------------------------------------ bridge
  let wakeLock = null;
  window.Android = {
    platform: "web",
    getRecipes: () => JSON.stringify([...recipes.values()].map(forUi)),
    saveRecipe: (json) => {
      const r = JSON.parse(json);
      const old = recipes.get(r.id);
      if (old && old.image) r.image = old.image; else delete r.image;
      if (old && old.imageUrl) r.imageUrl = old.imageUrl;
      putRecipe(r);
    },
    deleteRecipe: (id) => {
      recipes.delete(id);
      tx("recipes", "readwrite", (s) => s.delete(id));
      tx("images", "readwrite", (s) => s.delete(id));
      if (imgUrl.has(id)) { URL.revokeObjectURL(imgUrl.get(id)); imgUrl.delete(id); }
    },
    getJobs: () => JSON.stringify(jobs.map((j) => ({ id: j.id, status: j.status, stage: j.stage, error: j.error, source: j.source, text: j.text, files: j.files }))),
    retryJob: (id) => { const j = jobs.find((x) => x.id === id); if (j && j.status !== "busy") { j.status = "busy"; j.error = ""; j.stage = "Opnieuw proberen"; jobsChanged(); run(j); } },
    removeJob: (id) => { const i = jobs.findIndex((x) => x.id === id); if (i >= 0) jobs.splice(i, 1); jobsChanged(); },
    submitText: (text) => submit({ text }),
    pickImages: (text) => {
      const inp = document.createElement("input"); inp.type = "file"; inp.multiple = true; inp.accept = "image/*,video/*";
      inp.onchange = () => { if (inp.files.length) submit({ text, files: [...inp.files] }); };
      inp.click();
    },
    pasteFromShortcut,
    getSettings: () => JSON.stringify({ apiKey: S.get("apiKey", ""), model: S.get("model", DEFAULT_MODEL), version: VERSION, shortcutUrl: window.RB_SHORTCUT_URL || "" }),
    setSetting: (k, v) => S.set(k, v),
    instagramLogin() {}, instagramLogout() {},
    openUrl: (u) => window.open(u, "_blank"),
    exportBackup, importBackup,
    keepScreenOn: async (on) => {
      try {
        if (on && navigator.wakeLock) wakeLock = await navigator.wakeLock.request("screen");
        else if (wakeLock) { await wakeLock.release(); wakeLock = null; }
      } catch (e) {}
    },
    shareText: (text) => { if (navigator.share) navigator.share({ text }).catch(() => {}); else navigator.clipboard.writeText(text).then(() => toast("Recept gekopieerd")); },
    toast: (m) => toast(m),
  };

  // Start: load storage first, then the shared UI.
  loadAll().catch((e) => console.error(e)).then(() => {
    const s = document.createElement("script"); s.src = "app.js"; document.body.appendChild(s);
  });
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
})();
