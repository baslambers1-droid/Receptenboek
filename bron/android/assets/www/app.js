/* Receptenboek – UI. Talks to the Android side through window.Android. */
(function () {
  "use strict";

  const A = window.Android || mockBridge();
  const WEB = A.platform === "web";
  if (WEB) document.body.classList.add("web");
  const $app = document.getElementById("app");
  const CATEGORIES = ["Voorgerecht", "Hoofdgerecht", "Nagerecht", "Snack", "Borrel", "Sauzen", "Overig"];
  const CAT_EMOJI = { Voorgerecht: "🥗", Hoofdgerecht: "🍝", Nagerecht: "🍨", Snack: "🥨", Borrel: "🧀", Sauzen: "🫙", Overig: "🍽️" };
  // Older versions used other categories: map them to the current types.
  const OLD_CAT = { Ontbijt: "Overig", Lunch: "Hoofdgerecht", Diner: "Hoofdgerecht", Bijgerecht: "Overig", Soep: "Voorgerecht", Salade: "Voorgerecht",
    Dessert: "Nagerecht", Gebak: "Nagerecht", Drinken: "Overig", Saus: "Sauzen" };
  const ICON = {
    clock: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>',
    heart: '<svg viewBox="0 0 24 24"><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/></svg>',
    back: '<svg viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M8 5.5v13l10-6.5z" fill="currentColor"/></svg>',
    ext: '<svg viewBox="0 0 24 24"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>',
    check: '<svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
    search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/></svg>',
    edit: '<svg viewBox="0 0 24 24"><path d="M4 20h4L19 9l-4-4L4 16z"/></svg>',
    share: '<svg viewBox="0 0 24 24"><circle cx="18" cy="5.5" r="2.5"/><circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="18.5" r="2.5"/><path d="M8.2 10.8l7.6-4.1M8.2 13.2l7.6 4.1"/></svg>',
    flame: '<svg viewBox="0 0 24 24"><path d="M12 21c-3.9 0-6.5-2.6-6.5-6.2 0-3.6 3-5.6 3.6-9.3 2.4 1.4 3.6 3.4 3.6 5.5 1-.6 1.7-1.7 1.9-2.9 2 1.6 3.9 4 3.9 6.7 0 3.6-2.6 6.2-6.5 6.2z"/></svg>',
  };

  const state = {
    tab: "home",
    recipes: [],
    jobs: [],
    settings: {},
    stack: [],          // overlay pages: {type:'recipe', id} | {type:'edit', id}
    query: "",
    filterCat: "",
    filterTag: "",
    detail: { servings: null, seg: "ing", checked: {}, stepsDone: {}, cook: false },
  };

  // ------------------------------------------------------------------ data
  function loadRecipes() {
    try { state.recipes = JSON.parse(A.getRecipes() || "[]"); } catch (e) { state.recipes = []; }
    state.recipes.forEach((r) => {
      if (!CATEGORIES.includes(r.category)) {
        r.category = OLD_CAT[r.category] || "Overig";
        A.saveRecipe(JSON.stringify(r));
      }
    });
    state.recipes.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
  }
  function loadJobs() { try { state.jobs = JSON.parse(A.getJobs() || "[]"); } catch (e) { state.jobs = []; } }
  function loadSettings() { try { state.settings = JSON.parse(A.getSettings() || "{}"); } catch (e) { state.settings = {}; } }
  function byId(id) { return state.recipes.find((r) => r.id === id); }
  function save(r) { A.saveRecipe(JSON.stringify(r)); loadRecipes(); }

  // ------------------------------------------------------------------ helpers
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
  function isEst(x) { return !!(x && (x.estimated || /geschat/i.test(x.note || ""))); }
  function incomplete(r) { return r.complete === false; }
  function minutes(r) { return r.totalMinutes || ((r.prepMinutes || 0) + (r.cookMinutes || 0)) || null; }
  function fmtTime(m) { if (!m) return ""; if (m < 60) return m + " min"; const h = Math.floor(m / 60), r = m % 60; return h + " u" + (r ? " " + r : ""); }
  function imgStyle(r) { return r.image ? `style="background-image:url('${esc(r.image)}')"` : ""; }
  function pic(r) { return r.image ? `<div class="img" ${imgStyle(r)}></div>` : `<div class="placeholder">${CAT_EMOJI[r.category] || "🍽️"}</div>`; }
  function toast(msg) { const t = document.getElementById("toast"); t.textContent = msg; t.classList.add("show"); clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.remove("show"), 2600); }
  function sourceName(r) {
    const t = r.sourceType;
    if (t === "instagram") return "Instagram" + (r.author ? " · @" + r.author : "");
    if (t === "youtube") return "YouTube" + (r.author ? " · " + r.author : "");
    if (t === "tiktok") return "TikTok" + (r.author ? " · " + r.author : "");
    if (t === "foto") return "Uit foto's";
    if (t === "video") return "Uit video";
    if (r.sourceUrl) { try { return r.author || new URL(r.sourceUrl).hostname.replace(/^www\./, ""); } catch (e) { return "Website"; } }
    return "Eigen tekst";
  }

  const FRACTIONS = [[0.125, "⅛"], [0.25, "¼"], [0.333, "⅓"], [0.5, "½"], [0.667, "⅔"], [0.75, "¾"]];
  function fmtQty(q, unit) {
    if (q == null || q === "" || isNaN(q)) return "";
    const u = (unit || "").toLowerCase();
    if (["g", "gram", "ml", "mg"].includes(u)) {
      if (q >= 100) q = Math.round(q / 5) * 5; else if (q >= 10) q = Math.round(q); else q = Math.round(q * 10) / 10;
      return String(q).replace(".", ",");
    }
    if (["kg", "l", "liter", "dl", "cl"].includes(u)) return String(Math.round(q * 100) / 100).replace(".", ",");
    const whole = Math.floor(q + 0.04), frac = q - whole;
    if (frac < 0.06) return String(whole || (q > 0 ? q.toFixed(2).replace(".", ",") : "0"));
    let best = null;
    for (const [v, s] of FRACTIONS) if (Math.abs(frac - v) < 0.06) best = s;
    if (best) return (whole ? whole + " " : "") + best;
    return String(Math.round(q * 10) / 10).replace(".", ",");
  }

  function groupBy(list, key) {
    const out = [];
    list.forEach((it) => {
      const g = (it[key] || "").trim();
      let last = out[out.length - 1];
      if (!last || last.group !== g) { last = { group: g, items: [] }; out.push(last); }
      last.items.push(it);
    });
    return out;
  }

  // ------------------------------------------------------------------ render root
  function render() {
    const top = state.stack[state.stack.length - 1];
    document.body.classList.toggle("detail", !!top);
    document.querySelectorAll("#tabbar button[data-tab]").forEach((b) => b.classList.toggle("on", b.dataset.tab === state.tab));
    if (top && top.type === "recipe") return renderRecipe(top.id);
    if (top && top.type === "edit") return renderEdit(top.id);
    if (!state.settings.apiKey && state.tab !== "settings") return renderWelcome();
    if (state.tab === "home") return renderHome();
    if (state.tab === "search") return renderSearch(false);
    if (state.tab === "favorites") return renderSearch(true);
    if (state.tab === "settings") return renderSettings();
  }

  function pasteBar() {
    if (!WEB) return "";
    return `<div class="pastebar"><button class="btn primary block" data-action="paste">📋 Recept plakken uit de Opdracht</button></div>`;
  }

  function jobsHtml() {
    if (!state.jobs.length) return pasteBar();
    return `<div class="jobs">${state.jobs.map((j) => `
      <div class="job ${j.status === "error" ? "error" : ""}">
        <div class="spin">${j.status === "error" ? "!" : ""}</div>
        <div class="txt"><b>${j.status === "error" ? "Niet gelukt" : "Recept toevoegen"} · ${esc(j.source)}</b>
          <small>${esc(j.status === "error" ? j.error : j.stage + "…")}</small></div>
        ${j.status === "error" ? `<div class="acts"><button data-retry="${j.id}">Opnieuw</button><button data-remove="${j.id}">✕</button></div>` : ""}
      </div>`).join("")}</div>${pasteBar()}`;
  }

  function poster(r, wide) {
    const m = minutes(r);
    return `<button class="poster ${wide ? "wide" : ""}" data-open="${r.id}">
      <div class="pic">${pic(r)}
        ${m ? `<div class="time">${ICON.clock}${fmtTime(m)}</div>` : ""}
        ${r.favorite ? `<div class="fav">${ICON.heart}</div>` : ""}
        ${incomplete(r) ? `<div class="badge-inc">Aangevuld</div>` : ""}
      </div>
      <div class="title">${esc(r.title)}</div>
    </button>`;
  }

  function rowHtml(title, list, opts) {
    if (!list.length) return "";
    opts = opts || {};
    return `<section class="row">
      <div class="row-head"><h2>${esc(title)}</h2>${opts.more ? `<button data-more="${esc(opts.more)}">Alles</button>` : ""}</div>
      <div class="scroller">${list.map((r) => poster(r, opts.wide)).join("")}</div>
    </section>`;
  }

  // ------------------------------------------------------------------ home
  function renderHome() {
    const R = state.recipes;
    if (!R.length) {
      $app.innerHTML = `<div class="topbar"><div class="brand">Recepten<b>boek</b></div></div>${jobsHtml()}
        <div class="empty fade-in"><div class="big">📖</div><h2>Je boek is nog leeg</h2>
          <p>${WEB ? "Zie je een lekker recept op Instagram, YouTube of een website? Tik op <b>Delen</b> en kies de Opdracht <b>Receptenboek</b>. Open daarna deze app en tik op <b>Recept plakken</b>."
            : "Zie je een lekker recept op Instagram, YouTube, TikTok of een website? Tik op <b>Delen</b> en kies <b>Receptenboek</b>. De rest gaat vanzelf."}</p>
          ${WEB ? "" : '<button class="btn primary" data-action="add">Of plak een link</button>'}</div>`;
      return;
    }
    // Hero: the newest recipe that has a photo, otherwise the newest.
    const hero = R.find((r) => r.image) || R[0];
    const hm = minutes(hero);
    let html = `<div class="topbar over"><div class="brand">Recepten<b>boek</b></div>
        <button class="icon-btn" data-tab-go="search">${ICON.search}</button></div>
      <section class="hero fade-in" data-open="${hero.id}">
        <div class="img" ${imgStyle(hero)}></div>${hero.image ? "" : `<div class="placeholder">${CAT_EMOJI[hero.category] || "🍽️"}</div>`}
        <div class="content">
          <span class="eyebrow">${hero === R[0] ? "Nieuw in je boek" : "Uitgelicht"} · ${esc(hero.category || "Recept")}</span>
          <h1>${esc(hero.title)}</h1>
          <div class="meta">${hm ? `<span>${fmtTime(hm)}</span>` : ""}${hero.servings ? `<span>${hero.servings} personen</span>` : ""}<span>${esc(sourceName(hero))}</span></div>
          <div class="actions"><button class="btn primary" data-open="${hero.id}">${ICON.play} Koken</button>
            <button class="btn ghost" data-fav="${hero.id}">${ICON.heart} ${hero.favorite ? "Bewaard" : "Favoriet"}</button></div>
        </div>
      </section>${jobsHtml()}`;

    html += rowHtml("Net toegevoegd", R.slice(0, 12), { wide: true });
    html += rowHtml("Jouw favorieten", R.filter((r) => r.favorite), { more: "fav" });
    html += rowHtml("Klaar in 30 minuten", R.filter((r) => minutes(r) && minutes(r) <= 30), {});
    CATEGORIES.forEach((c) => { html += rowHtml(c, R.filter((r) => r.category === c), { more: "cat:" + c }); });

    $app.innerHTML = html;
  }

  // ------------------------------------------------------------------ welcome
  function renderWelcome() {
    $app.innerHTML = `<div class="empty fade-in" style="padding-top:56px">
      <div class="big">👩‍🍳</div><h2>Welkom bij je Receptenboek</h2>
      <p>Eén keer instellen, daarna deel je recepten vanuit elke app.</p>
      <div class="steps-list">
        <div><span>1</span><div>Maak een gratis Gemini API-sleutel aan bij <a class="link" data-url="https://aistudio.google.com/apikey">Google AI Studio</a> (inloggen met je Google-account, dan <i>Create API key</i>).</div></div>
        <div><span>2</span><div>Plak de sleutel hieronder.</div></div>
        ${WEB ? `<div><span>3</span><div>Installeer de Opdracht <b>Receptenboek</b> ${state.settings.shortcutUrl ? `via <a class="link" data-url="${esc(state.settings.shortcutUrl)}">deze link</a>` : "(zie Instellingen)"}. Daarna deel je posts vanuit Instagram naar die Opdracht.</div></div>`
          : `<div><span>3</span><div>Deel een post vanuit Instagram, YouTube, TikTok of je browser naar <b>Receptenboek</b>.</div></div>`}
      </div>
      <div class="field"><input id="w-key" placeholder="Plak hier je API-sleutel (AIza…)" autocomplete="off" spellcheck="false"></div>
      <button class="btn primary block" data-action="save-key">Opslaan en beginnen</button>
    </div>`;
  }

  // ------------------------------------------------------------------ search / favorites
  function renderSearch(favOnly) {
    const q = state.query.trim().toLowerCase();
    let list = state.recipes.filter((r) => !favOnly || r.favorite);
    const tagCount = {};
    list.forEach((r) => (r.tags || []).forEach((t) => (tagCount[t] = (tagCount[t] || 0) + 1)));
    const tags = Object.keys(tagCount).sort((a, b) => tagCount[b] - tagCount[a]).slice(0, 24);
    const hasInc = list.some(incomplete);
    if (state.filterCat === "__inc") list = list.filter(incomplete);
    else if (state.filterCat) list = list.filter((r) => r.category === state.filterCat);
    if (state.filterTag) list = list.filter((r) => (r.tags || []).includes(state.filterTag));
    if (q) list = list.filter((r) => [r.title, r.description, r.cuisine, r.category, (r.tags || []).join(" "),
      (r.ingredients || []).map((i) => i.name).join(" "), r.author].join(" ").toLowerCase().includes(q));
    const cats = CATEGORIES.filter((c) => state.recipes.some((r) => r.category === c && (!favOnly || r.favorite)));
    $app.innerHTML = `<h1 class="page-title">${favOnly ? "Favorieten" : "Zoeken"}</h1>
      <div class="searchbox">${ICON.search}<input id="q" type="search" placeholder="Gerecht, ingrediënt of tag" value="${esc(state.query)}"></div>
      ${cats.length ? `<div class="chips">${["", ...cats].map((c) => `<button class="chip ${state.filterCat === c ? "on" : ""}" data-cat="${esc(c)}">${c ? (CAT_EMOJI[c] || "") + " " + esc(c) : "Alles"}</button>`).join("")}${hasInc ? `<button class="chip ${state.filterCat === "__inc" ? "on" : ""}" data-cat="__inc">⚠️ Niet compleet</button>` : ""}</div>` : ""}
      ${tags.length ? `<div class="chips">${tags.map((t) => `<button class="chip ${state.filterTag === t ? "on" : ""}" data-tag="${esc(t)}">#${esc(t)}</button>`).join("")}</div>` : ""}
      <div class="count">${list.length} ${list.length === 1 ? "recept" : "recepten"}</div>
      ${list.length ? `<div class="grid">${list.map((r) => poster(r)).join("")}</div>`
        : `<div class="empty">${favOnly && !q ? "Tik op het hartje bij een recept om het hier te bewaren." : "Niets gevonden."}</div>`}`;
    const input = document.getElementById("q");
    input.addEventListener("input", () => {
      state.query = input.value;
      const pos = input.selectionStart;
      renderSearch(favOnly);
      const i2 = document.getElementById("q"); i2.focus(); i2.setSelectionRange(pos, pos);
    });
  }

  // ------------------------------------------------------------------ recipe detail
  function openRecipe(id) {
    state.detail = { servings: null, seg: "ing", checked: {}, stepsDone: {}, cook: false };
    state.stack.push({ type: "recipe", id });
    render();
    window.scrollTo(0, 0);
  }

  function renderRecipe(id) {
    const r = byId(id);
    if (!r) { state.stack.pop(); return render(); }
    const d = state.detail;
    const base = r.servings || 0;
    if (d.servings == null) d.servings = base || 1;
    const factor = base ? d.servings / base : 1;
    const m = minutes(r);
    const ings = r.ingredients || [];
    const steps = (r.steps || []).map((s) => (typeof s === "string" ? { text: s, group: "" } : s));

    let ingHtml = "";
    let idx = 0;
    groupBy(ings, "group").forEach((g) => {
      if (g.group) ingHtml += `<div class="group-title">${esc(g.group)}</div>`;
      g.items.forEach((i) => {
        const k = idx++;
        const q = i.quantity != null ? fmtQty(i.quantity * factor, i.unit) : "";
        ingHtml += `<button class="ing ${d.checked[k] ? "done" : ""}" data-ing="${k}">
          <span class="box">${ICON.check}</span>
          <span class="qty ${isEst(i) ? "est" : ""}">${esc(q)} ${esc(q ? i.unit || "" : "")}</span>
          <span class="name">${esc(i.name)}${i.note && !/^geschat$/i.test(i.note) ? ` <small>(${esc(i.note)})</small>` : ""}${isEst(i) ? ` <em class="est-tag">geschat</em>` : ""}</span></button>`;
      });
    });
    let stepHtml = "";
    let n = 0;
    groupBy(steps, "group").forEach((g) => {
      if (g.group) stepHtml += `<div class="group-title">${esc(g.group)}</div>`;
      g.items.forEach((s) => {
        const k = n++;
        stepHtml += `<button class="step ${d.stepsDone[k] ? "done" : ""}" data-step="${k}"><span class="n">${k + 1}</span><p>${esc(s.text)}${isEst(s) ? ` <em class="est-tag">aangevuld</em>` : ""}</p></button>`;
      });
    });

    $app.innerHTML = `<div class="topbar over">
        <button class="icon-btn" data-action="back">${ICON.back}</button><div style="flex:1"></div>
        <button class="icon-btn" data-action="share">${ICON.share}</button>
        <button class="icon-btn ${r.favorite ? "on" : ""}" data-fav="${r.id}">${ICON.heart}</button>
      </div>
      <div class="detail-hero">${pic(r)}</div>
      <div class="detail-body fade-in ${d.cook ? "cook-on" : ""}">
        <button class="type-btn" data-action="pick-type">${CAT_EMOJI[r.category] || ""} ${esc(r.category || "Kies type")} <span>▾</span></button>${r.cuisine ? `<span class="eyebrow" style="margin-left:10px">${esc(r.cuisine)}</span>` : ""}
        <h1>${esc(r.title)}</h1>
        ${r.description ? `<p class="desc">${esc(r.description)}</p>` : ""}
        ${incomplete(r) ? `<div class="incomplete">
            <div class="inc-head"><span class="inc-icon">!</span><b>Niet compleet in het origineel</b></div>
            <p>Gemini heeft ontbrekende onderdelen aangevuld, waar mogelijk op basis van de video. Die zijn hieronder gemarkeerd met <em class="est-tag">geschat</em> of <em class="est-tag">aangevuld</em>.</p>
            ${(r.missingInfo || []).length ? `<ul>${r.missingInfo.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>` : ""}
            <button class="btn block" data-action="mark-checked">${ICON.check} Gecontroleerd, markering weghalen</button>
          </div>` : ""}
        <div class="facts">
          <div class="fact"><b>${m ? fmtTime(m) : "–"}</b><small>Tijd</small></div>
          <div class="fact"><b>${base || "–"}</b><small>Personen</small></div>
          <div class="fact"><b>${esc(r.difficulty || "–")}</b><small>Niveau</small></div>
        </div>
        ${(r.tags || []).length ? `<div class="tags">${r.tags.map((t) => `<span class="tag">#${esc(t)}</span>`).join("")}</div>` : ""}
        ${r.sourceUrl ? `<button class="source" data-url="${esc(r.sourceUrl)}"><span class="lbl">Bekijk origineel · ${esc(sourceName(r))}</span>${ICON.ext}</button>`
          : `<div class="source"><span class="lbl">${esc(sourceName(r))}</span></div>`}

        <div class="seg"><button data-seg="ing" class="${d.seg === "ing" ? "on" : ""}">Ingrediënten</button><button data-seg="steps" class="${d.seg === "steps" ? "on" : ""}">Bereiding</button></div>

        <div class="${d.seg === "ing" ? "" : "hidden"}">
          ${base ? `<div class="servings"><span class="lbl">Voor ${d.servings} ${d.servings === 1 ? "persoon" : "personen"}</span>
            <div class="stepper"><button data-serv="-1">−</button><b>${d.servings}</b><button data-serv="1">+</button></div></div>` : ""}
          ${ingHtml || '<p class="desc">Geen ingrediënten gevonden.</p>'}
        </div>
        <div class="${d.seg === "steps" ? "" : "hidden"}">
          <button class="btn block ${d.cook ? "primary" : ""}" data-action="cook" style="margin-bottom:6px">${ICON.flame} ${d.cook ? "Kookmodus aan: scherm blijft aan" : "Kookmodus (scherm blijft aan)"}</button>
          ${stepHtml || '<p class="desc">Geen stappen gevonden.</p>'}
        </div>

        ${(r.tips || []).length ? `<div class="tips"><h3>Tips</h3><ul>${r.tips.map((t) => `<li>${esc(t)}</li>`).join("")}</ul></div>` : ""}

        <div class="notes"><h3>Mijn notities</h3>
          <textarea id="notes" rows="3" placeholder="Bijv. minder suiker, 5 min langer in de oven…">${esc(r.notes || "")}</textarea></div>

        <div class="detail-actions">
          <button class="btn" data-action="edit">${ICON.edit} Bewerken</button>
          <button class="btn danger" data-action="delete">Verwijderen</button>
        </div>
      </div>`;

    const notes = document.getElementById("notes");
    notes.addEventListener("change", () => { r.notes = notes.value; save(r); toast("Notitie bewaard"); });
  }

  function recipeAsText(r) {
    const lines = [r.title, ""];
    if (r.description) lines.push(r.description, "");
    lines.push("Ingrediënten" + (r.servings ? ` (${r.servings} personen)` : "") + ":");
    groupBy(r.ingredients || [], "group").forEach((g) => {
      if (g.group) lines.push("", g.group + ":");
      g.items.forEach((i) => lines.push("- " + [fmtQty(i.quantity, i.unit), i.quantity != null ? i.unit : "", i.name].filter(Boolean).join(" ") + (i.note ? ` (${i.note})` : "")));
    });
    lines.push("", "Bereiding:");
    (r.steps || []).forEach((s, i) => lines.push(`${i + 1}. ${typeof s === "string" ? s : s.text}`));
    if (r.sourceUrl) lines.push("", "Bron: " + r.sourceUrl);
    return lines.join("\n");
  }

  // ------------------------------------------------------------------ edit
  function ingToLine(i) { return [i.quantity != null ? String(i.quantity).replace(".", ",") : "", i.quantity != null ? i.unit || "" : "", i.name].filter(Boolean).join(" ") + (i.note ? ` (${i.note})` : ""); }
  const UNITS = ["g", "kg", "ml", "l", "dl", "cl", "el", "tl", "stuk", "stuks", "snuf", "teen", "teentjes", "blik", "bos", "plak", "plakken", "takje", "takjes", "handje", "scheut", "zakje", "pak", "kop", "mespunt"];
  function lineToIng(line, group) {
    const m = line.match(/^\s*([\d.,/½¼¾⅓⅔]+(?:\s*-\s*[\d.,]+)?)?\s*(\S+)?\s*(.*)$/);
    let quantity = null, unit = "", name = line.trim(), note = "";
    if (m && m[1]) {
      const raw = m[1].replace(",", ".").replace("½", ".5").replace("¼", ".25").replace("¾", ".75").replace("⅓", ".333").replace("⅔", ".667");
      quantity = raw.includes("/") ? raw.split("/").reduce((a, b) => parseFloat(a) / parseFloat(b)) : parseFloat(raw);
      if (isNaN(quantity)) quantity = null;
      if (m[2] && UNITS.includes(m[2].toLowerCase())) { unit = m[2]; name = m[3]; } else { name = [m[2], m[3]].filter(Boolean).join(" "); }
    }
    const nm = name.match(/^(.*?)\s*\(([^)]*)\)\s*$/);
    if (nm) { name = nm[1]; note = nm[2]; }
    return { group, quantity, unit, name: name.trim(), note };
  }
  function linesWithGroups(list, toLine) {
    const out = [];
    groupBy(list, "group").forEach((g) => { if (g.group) out.push(g.group + ":"); g.items.forEach((i) => out.push(toLine(i))); });
    return out.join("\n");
  }
  function parseGrouped(text, fromLine) {
    let group = "";
    const out = [];
    text.split("\n").map((l) => l.trim()).filter(Boolean).forEach((l) => {
      if (/:$/.test(l) && l.length < 60) { group = l.slice(0, -1); return; }
      out.push(fromLine(l.replace(/^[-•*]\s*/, "").replace(/^\d+[.)]\s*/, ""), group));
    });
    return out;
  }

  function renderEdit(id) {
    const r = byId(id);
    if (!r) { state.stack.pop(); return render(); }
    const steps = (r.steps || []).map((s) => (typeof s === "string" ? { text: s, group: "" } : s));
    $app.innerHTML = `<div class="topbar"><button class="icon-btn" data-action="back">${ICON.back}</button><div class="brand">Bewerken</div>
        <button class="btn primary" data-action="save-edit" style="height:40px">Opslaan</button></div>
      <div class="form fade-in">
        <div class="field"><label>Titel</label><input id="e-title" value="${esc(r.title)}"></div>
        <div class="field"><label>Omschrijving</label><textarea id="e-desc" rows="2">${esc(r.description || "")}</textarea></div>
        <div class="two">
          <div class="field"><label>Personen</label><input id="e-serv" type="number" inputmode="numeric" value="${r.servings || ""}"></div>
          <div class="field"><label>Totale tijd (min)</label><input id="e-time" type="number" inputmode="numeric" value="${minutes(r) || ""}"></div>
        </div>
        <div class="two">
          <div class="field"><label>Type gerecht</label><select id="e-cat">${CATEGORIES.map((c) => `<option ${c === r.category ? "selected" : ""}>${c}</option>`).join("")}</select></div>
          <div class="field"><label>Keuken</label><input id="e-cuisine" value="${esc(r.cuisine || "")}"></div>
        </div>
        <div class="field"><label>Tags (komma's ertussen)</label><input id="e-tags" value="${esc((r.tags || []).join(", "))}"></div>
        <div class="field"><label>Ingrediënten</label><textarea id="e-ing" rows="10">${esc(linesWithGroups(r.ingredients || [], ingToLine))}</textarea>
          <div class="hint">Eén per regel, bijv. "200 g bloem". Een regel die eindigt op ":" wordt een kopje.</div></div>
        <div class="field"><label>Bereiding</label><textarea id="e-steps" rows="12">${esc(linesWithGroups(steps, (s) => s.text))}</textarea>
          <div class="hint">Eén stap per regel.</div></div>
        <div class="field"><label>Tips</label><textarea id="e-tips" rows="3">${esc((r.tips || []).join("\n"))}</textarea></div>
        <div class="field"><label>Link naar origineel</label><input id="e-src" value="${esc(r.sourceUrl || "")}"></div>
      </div>`;
  }

  function saveEdit(id) {
    const r = byId(id);
    const v = (x) => document.getElementById(x).value;
    r.title = v("e-title").trim() || r.title;
    r.description = v("e-desc").trim();
    r.servings = parseInt(v("e-serv"), 10) || null;
    r.totalMinutes = parseInt(v("e-time"), 10) || null;
    r.category = v("e-cat");
    r.cuisine = v("e-cuisine").trim();
    r.tags = v("e-tags").split(",").map((t) => t.trim().toLowerCase().replace(/^#/, "")).filter(Boolean);
    const oldIng = {}, oldStep = {};
    (r.ingredients || []).forEach((i) => (oldIng[ingToLine(i)] = isEst(i)));
    (r.steps || []).forEach((st) => { if (typeof st === "object") oldStep[st.text] = isEst(st); });
    r.ingredients = parseGrouped(v("e-ing"), lineToIng).map((i) => Object.assign(i, { estimated: !!oldIng[ingToLine(i)] }));
    r.steps = parseGrouped(v("e-steps"), (t, g) => ({ group: g, text: t, estimated: !!oldStep[t] }));
    r.tips = v("e-tips").split("\n").map((t) => t.trim()).filter(Boolean);
    r.sourceUrl = v("e-src").trim();
    save(r);
    state.stack.pop();
    state.detail.servings = null;
    render();
    toast("Opgeslagen");
  }

  // ------------------------------------------------------------------ settings
  function renderSettings() {
    const s = state.settings;
    $app.innerHTML = `<h1 class="page-title">Instellingen</h1>
      <div class="section"><h3>Gemini API-sleutel</h3>
        <p>Gratis aan te maken bij <a data-url="https://aistudio.google.com/apikey">Google AI Studio</a>. De sleutel blijft alleen op je telefoon.</p>
        <div class="field"><input id="s-key" type="password" value="${esc(s.apiKey || "")}" placeholder="AIza…" autocomplete="off" spellcheck="false"></div>
        <div class="field"><label>Model</label><input id="s-model" value="${esc(s.model || "")}" spellcheck="false"></div>
        <p style="margin-top:-4px">Standaard: <i>gemini-flash-latest</i> (altijd het nieuwste gratis Flash-model).</p>
        <button class="btn primary block" data-action="save-settings">Opslaan</button></div>

      ${WEB ? `<div class="section"><h3>Delen vanuit Instagram</h3>
        <p>Dit gaat via de Opdracht <b>Receptenboek</b> in de Opdrachten-app. Die haalt de caption en de video op vanaf je iPhone.</p>
        ${s.shortcutUrl ? `<button class="btn block" data-url="${esc(s.shortcutUrl)}">Opdracht installeren</button>` : ""}
        <p style="margin-top:12px">Na het delen: open deze app en tik op <b>Recept plakken</b>.</p></div>` : `<div class="section"><h3>Instagram</h3>
        <p>Log één keer in, dan kan de app captions en video's van Instagram veel betrouwbaarder ophalen. Je inloggegevens blijven op je telefoon.</p>
        <p><span class="status ${s.instagramLoggedIn ? "ok" : ""}">${s.instagramLoggedIn ? "Ingelogd" : "Niet ingelogd"}</span></p>
        <div class="btn-row"><button class="btn" data-action="ig-login">${s.instagramLoggedIn ? "Opnieuw inloggen" : "Inloggen bij Instagram"}</button>
          ${s.instagramLoggedIn ? `<button class="btn" data-action="ig-logout">Uitloggen</button>` : ""}</div></div>`}

      <div class="section"><h3>Back-up</h3>
        <p>Je hebt ${state.recipes.length} recepten. Bewaar af en toe een back-up (bijv. in Google Drive), dan raak je nooit iets kwijt.</p>
        <div class="btn-row"><button class="btn" data-action="export">Back-up maken</button><button class="btn" data-action="import">Terugzetten</button></div></div>

      <div class="section"><h3>Zo voeg je recepten toe</h3>
        <p>Deel een post, video of webpagina vanuit Instagram, YouTube, TikTok, Cheflix of je browser naar <b>Receptenboek</b>. Je kunt ook tekst, screenshots of een video delen, of op de oranje + tikken.</p>
        ${WEB ? "" : "<p>Na het delen gaat de app meteen terug naar waar je was. Het recept wordt op de achtergrond gelezen en je krijgt een melding zodra het in je boek staat.</p>"}
        <p>Versie ${esc(s.version || "")}</p></div>`;
  }

  // ------------------------------------------------------------------ add sheet
  function openSheet(html) {
    const sh = document.getElementById("sheet");
    sh.innerHTML = html;
    document.body.classList.add("sheet-open");
  }
  function closeSheet() { document.body.classList.remove("sheet-open"); }

  function openAdd() {
    openSheet(`<h2>Recept toevoegen</h2>
      <p>Plak een link (Instagram, YouTube, TikTok, Cheflix, website) of de tekst van een recept.</p>
      <textarea id="add-text" placeholder="https://www.instagram.com/reel/…"></textarea>
      <div style="height:12px"></div>
      <button class="btn primary block" data-action="add-submit">Toevoegen</button>
      ${WEB ? '<div class="or">of</div><button class="btn block" data-action="paste">📋 Plakken uit de Opdracht</button>' : ""}
      <div class="or">of</div>
      <button class="btn block" data-action="add-pick">Kies screenshots of een video</button>`);
    setTimeout(() => {
      const t = document.getElementById("add-text");
      if (navigator.clipboard && navigator.clipboard.readText) {
        navigator.clipboard.readText().then((c) => { if (c && /^https?:\/\//.test(c.trim()) && !t.value) t.value = c.trim(); }).catch(() => {});
      }
    }, 50);
  }

  // ------------------------------------------------------------------ events
  document.addEventListener("click", (e) => {
    const el = e.target.closest("[data-open],[data-fav],[data-action],[data-tab],[data-tab-go],[data-more],[data-cat],[data-tag],[data-retry],[data-remove],[data-seg],[data-serv],[data-ing],[data-step],[data-url]");
    if (!el) { if (e.target.id === "sheet-backdrop") closeSheet(); return; }
    const ds = el.dataset;

    if (ds.fav) { e.stopPropagation(); const r = byId(ds.fav); r.favorite = !r.favorite; save(r); toast(r.favorite ? "Bewaard bij favorieten" : "Uit favorieten"); return render(); }
    if (ds.open) return openRecipe(ds.open);
    if (ds.url) return A.openUrl(ds.url);
    if (ds.retry) return A.retryJob(ds.retry);
    if (ds.remove) return A.removeJob(ds.remove);
    if (ds.tab) {
      if (ds.tab === "add") return openAdd();
      state.tab = ds.tab; state.stack = []; state.filterCat = ""; state.filterTag = "";
      render(); return window.scrollTo(0, 0);
    }
    if (ds.tabGo) { state.tab = ds.tabGo; render(); return window.scrollTo(0, 0); }
    if (ds.more) {
      state.tab = ds.more === "fav" ? "favorites" : "search";
      state.filterCat = ds.more.startsWith("cat:") ? ds.more.slice(4) : "";
      state.filterTag = ""; state.query = "";
      render(); return window.scrollTo(0, 0);
    }
    if (ds.cat !== undefined) { state.filterCat = ds.cat; return render(); }
    if (ds.tag !== undefined) { state.filterTag = state.filterTag === ds.tag ? "" : ds.tag; return render(); }
    if (ds.seg) { state.detail.seg = ds.seg; return render(); }
    if (ds.serv) { state.detail.servings = Math.max(1, state.detail.servings + parseInt(ds.serv, 10)); return render(); }
    if (ds.ing !== undefined) { state.detail.checked[ds.ing] = !state.detail.checked[ds.ing]; el.classList.toggle("done"); return; }
    if (ds.step !== undefined) { state.detail.stepsDone[ds.step] = !state.detail.stepsDone[ds.step]; el.classList.toggle("done"); return; }

    const top = state.stack[state.stack.length - 1];
    switch (ds.action) {
      case "back": return back();
      case "add": return openAdd();
      case "paste": closeSheet(); state.tab = "home"; state.stack = []; return Promise.resolve(A.pasteFromShortcut()).then(() => { render(); window.scrollTo(0, 0); });
      case "add-submit": {
        const t = document.getElementById("add-text").value.trim();
        if (!t) return toast("Plak eerst een link of tekst");
        A.submitText(t); closeSheet(); state.tab = "home"; state.stack = []; render(); window.scrollTo(0, 0);
        return toast("Recept wordt toegevoegd…");
      }
      case "add-pick": { const t = document.getElementById("add-text").value.trim(); closeSheet(); state.tab = "home"; render(); return A.pickImages(t); }
      case "save-key": {
        const k = document.getElementById("w-key").value.trim();
        if (k.length < 20) return toast("Dat lijkt geen geldige sleutel");
        A.setSetting("apiKey", k); loadSettings(); render(); return toast("Klaar! Deel nu je eerste recept.");
      }
      case "save-settings":
        A.setSetting("apiKey", document.getElementById("s-key").value);
        A.setSetting("model", document.getElementById("s-model").value || "gemini-flash-latest");
        loadSettings(); render(); return toast("Opgeslagen");
      case "ig-login": return A.instagramLogin();
      case "ig-logout": A.instagramLogout(); loadSettings(); render(); return toast("Uitgelogd bij Instagram");
      case "export": return A.exportBackup();
      case "import": return A.importBackup();
      case "share": return A.shareText(recipeAsText(byId(top.id)));
      case "cook": state.detail.cook = !state.detail.cook; A.keepScreenOn(state.detail.cook); return render();
      case "edit": state.stack.push({ type: "edit", id: top.id }); render(); return window.scrollTo(0, 0);
      case "save-edit": return saveEdit(top.id);
      case "pick-type": {
        const r = byId(top.id);
        openSheet(`<h2>Type gerecht</h2><p>Kies waar "${esc(r.title)}" in je boek komt te staan.</p>
          <div class="type-grid">${CATEGORIES.map((c) => `<button class="type-opt ${c === r.category ? "on" : ""}" data-action="set-type" data-type="${c}"><span>${CAT_EMOJI[c]}</span>${c}</button>`).join("")}</div>`);
        return;
      }
      case "set-type": { const r = byId(top.id); r.category = ds.type; save(r); closeSheet(); render(); return toast("Type: " + ds.type); }
      case "mark-checked": {
        const r = byId(top.id);
        r.complete = true; r.missingInfo = [];
        (r.ingredients || []).forEach((i) => { i.estimated = false; if (/^geschat$/i.test(i.note || "")) i.note = ""; });
        (r.steps || []).forEach((st) => { if (typeof st === "object") st.estimated = false; });
        save(r); render(); return toast("Gemarkeerd als gecontroleerd");
      }
      case "delete": {
        const r = byId(top.id);
        openSheet(`<h2>Recept verwijderen?</h2><p>"${esc(r.title)}" wordt uit je boek gehaald. Dit kan niet ongedaan worden gemaakt.</p>
          <div class="btn-row"><button class="btn" data-action="close-sheet">Annuleren</button><button class="btn primary" style="background:var(--danger)" data-action="confirm-delete">Verwijderen</button></div>`);
        return;
      }
      case "confirm-delete": A.deleteRecipe(top.id); loadRecipes(); closeSheet(); state.stack = []; A.keepScreenOn(false); render(); return toast("Verwijderd");
      case "close-sheet": return closeSheet();
    }
  });

  function back() {
    if (document.body.classList.contains("sheet-open")) { closeSheet(); return true; }
    if (state.stack.length) {
      const top = state.stack.pop();
      if (top.type === "recipe") { A.keepScreenOn(false); state.detail.cook = false; }
      render();
      return true;
    }
    if (state.tab !== "home") { state.tab = "home"; render(); return true; }
    return false;
  }

  // ------------------------------------------------------------------ API for Android
  window.App = {
    back,
    onJobsChanged() {
      const before = state.jobs.length;
      loadJobs();
      if (!state.stack.length && (state.tab === "home" || before !== state.jobs.length)) {
        if (state.tab === "home") render();
      }
    },
    onRecipesChanged() {
      const n = state.recipes.length;
      loadRecipes(); loadJobs();
      if (state.recipes.length > n) toast("Nieuw recept toegevoegd: " + state.recipes[0].title);
      if (!state.stack.length || state.stack[state.stack.length - 1].type === "recipe") render();
    },
    onSettingsChanged() { loadSettings(); render(); },
    toast,
    openRecipeById(id) { loadRecipes(); state.stack = []; closeSheet(); if (byId(id)) openRecipe(id); else render(); },
    showHome() { state.tab = "home"; state.stack = []; closeSheet(); loadJobs(); render(); window.scrollTo(0, 0); },
  };

  loadSettings(); loadRecipes(); loadJobs(); render();

  // ------------------------------------------------------------------ browser preview
  function mockBridge() {
    let recipes = window.MOCK_RECIPES || [];
    const settings = { apiKey: "demo", model: "gemini-flash-latest", instagramLoggedIn: false, version: "1.0" };
    return {
      getRecipes: () => JSON.stringify(recipes),
      saveRecipe: (j) => { const r = JSON.parse(j); recipes = recipes.filter((x) => x.id !== r.id).concat([r]); },
      deleteRecipe: (id) => { recipes = recipes.filter((x) => x.id !== id); },
      getJobs: () => JSON.stringify(window.MOCK_JOBS || []),
      retryJob() {}, removeJob() {}, submitText() {}, pickImages() {},
      getSettings: () => JSON.stringify(settings), setSetting: (k, v) => { settings[k] = v; },
      instagramLogin() {}, instagramLogout() {}, openUrl() {}, exportBackup() {}, importBackup() {},
      keepScreenOn() {}, shareText() {}, toast() {},
    };
  }
})();
