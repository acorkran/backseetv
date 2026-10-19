/* Toddler Lock — standalone fun lock surface.
 *
 * Runs in two modes:
 *  - Browser: locks within the page (fullscreen + Keyboard Lock API + event capture).
 *    Good enough as a kiosk lock; can't block other apps (browser sandbox).
 *  - Tauri desktop: also calls the native input-grab (start_lock/stop_lock) so the
 *    WHOLE machine is locked while your editor/agent keeps running underneath.
 *
 * The fun half (animals, sparkles, neural voices, timed adult unlock) is identical
 * in both. D is for Dad 👨, M is for Mum 👩.
 */
(function () {
  "use strict";

  // ---- native bridge (present only in the Tauri build) ----
  var TAURI = window.__TAURI__ && window.__TAURI__.core ? window.__TAURI__.core : null;
  function nativeLock(on) {
    if (!TAURI) return;
    try { TAURI.invoke(on ? "start_lock" : "stop_lock").catch(function () {}); } catch (e) {}
  }
  // Listen for native lock triggers (global hotkey / tray menu). lock() is hoisted.
  function onTauriEvent(name, cb) {
    if (window.__TAURI__ && window.__TAURI__.event && window.__TAURI__.event.listen) {
      try { window.__TAURI__.event.listen(name, cb); } catch (e) {}
    }
  }
  onTauriEvent("trigger-lock", function () { lock(); });

  // Heads-up for browsers without the Keyboard Lock API (Firefox/Safari): they can't
  // suppress the Windows key, so Start can pop. Only shown in the web build.
  (function () {
    if (TAURI) return;
    var kbLock = !!(navigator.keyboard && navigator.keyboard.lock);
    var note = document.getElementById("kb-note");
    if (note && !kbLock) note.hidden = false;
  })();

  // ---- launch footer (home screen funnel): wire links from config.js, hide empties ----
  // Web build only — in the native app these external links would be dead (no opener).
  (function () {
    if (TAURI) return;
    var cfg = window.TL_CONFIG || {};
    function link(id, url) {
      var a = document.getElementById(id);
      if (!a || !url) return false;
      a.href = url; a.hidden = false; return true;
    }
    var any = false;
    any = link("link-win", cfg.winDownload) || any;
    any = link("link-survey", cfg.survey) || any;
    any = link("link-kofi", cfg.kofi) || any;
    any = link("link-github", cfg.github) || any;
    if (cfg.crypto) {
      var box = document.getElementById("foot-crypto"), addr = document.getElementById("foot-crypto-addr");
      if (box && addr) { addr.textContent = cfg.crypto; box.hidden = false; any = true; }
    }
    var foot = document.getElementById("home-foot");
    if (foot && any) foot.hidden = false;
  })();

  var overlay = document.getElementById("lock-overlay");
  var lockBtn = document.getElementById("lock-btn");
  var home = document.getElementById("home");
  var locked = false, refsTimer = null, lastSpark = {}, settingsOpen = false;

  var ANIMALS = {
    A: ["Alligator", "🐊"], B: ["Bear", "🐻"], C: ["Cat", "🐱"], D: ["Dad", "👨"],
    E: ["Elephant", "🐘"], F: ["Frog", "🐸"], G: ["Goat", "🐐"], H: ["Hippo", "🦛"],
    I: ["Iguana", "🦎"], J: ["Jellyfish", "🪼"], K: ["Koala", "🐨"], L: ["Lion", "🦁"],
    M: ["Mum", "👩"], N: ["Narwhal", "🐳"], O: ["Octopus", "🐙"], P: ["Penguin", "🐧"],
    Q: ["Quokka", "🐹"], R: ["Rabbit", "🐰"], S: ["Snake", "🐍"], T: ["Tiger", "🐯"],
    U: ["Unicorn", "🦄"], V: ["Vulture", "🦅"], W: ["Whale", "🐋"], X: ["Fox", "🦊"],
    Y: ["Yak", "🐂"], Z: ["Zebra", "🦓"]
  };
  var COLORS = ["#ff6b6b", "#ffd43b", "#51cf66", "#6ea8fe", "#cc7be8", "#ff922b", "#4dd4c4", "#f783ac"];
  var BURSTS = ["🎉", "⭐", "🌈", "✨", "🎈", "💜"];
  var SPARKS = ["✨", "⭐", "🌟", "💫", "❇️"];
  function rand(a) { return a[Math.floor(Math.random() * a.length)]; }

  // ---- languages ----
  // English (default) uses the bundled neural CLIPS. Other languages are display + word
  // packs spoken via the OS voice for now (neural recording packs per language come later).
  // Native speakers are welcome to refine the word lists. Each entry: LETTER -> [word, emoji].
  var LANGS = {
    es: {
      name: "Español", isFor: "de", locale: "es-ES",
      numbers: ["cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve"],
      letters: {
        A: ["Abeja", "🐝"], B: ["Ballena", "🐋"], C: ["Conejo", "🐰"], D: ["Delfín", "🐬"],
        E: ["Elefante", "🐘"], F: ["Foca", "🦭"], G: ["Gato", "🐱"], H: ["Hipopótamo", "🦛"],
        I: ["Iguana", "🦎"], J: ["Jirafa", "🦒"], K: ["Koala", "🐨"], L: ["León", "🦁"],
        M: ["Mamá", "👩"], N: ["Nutria", "🦦"], O: ["Oso", "🐻"], P: ["Perro", "🐶"],
        Q: ["Queso", "🧀"], R: ["Rana", "🐸"], S: ["Serpiente", "🐍"], T: ["Tigre", "🐯"],
        U: ["Unicornio", "🦄"], V: ["Vaca", "🐄"], W: ["Wombat", "🐹"], X: ["Xilófono", "🎵"],
        Y: ["Yoyó", "🪀"], Z: ["Zorro", "🦊"]
      }
    },
    fr: {
      name: "Français", isFor: "comme", locale: "fr-FR",
      numbers: ["zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf"],
      letters: {
        A: ["Abeille", "🐝"], B: ["Baleine", "🐋"], C: ["Chat", "🐱"], D: ["Dauphin", "🐬"],
        E: ["Éléphant", "🐘"], F: ["Fraise", "🍓"], G: ["Gâteau", "🍰"], H: ["Hibou", "🦉"],
        I: ["Igloo", "🧊"], J: ["Jus", "🧃"], K: ["Koala", "🐨"], L: ["Lion", "🦁"],
        M: ["Maman", "👩"], N: ["Nuage", "☁️"], O: ["Ours", "🐻"], P: ["Poisson", "🐟"],
        Q: ["Quokka", "🐹"], R: ["Renard", "🦊"], S: ["Serpent", "🐍"], T: ["Tigre", "🐯"],
        U: ["Ukulélé", "🪕"], V: ["Vache", "🐄"], W: ["Wagon", "🚃"], X: ["Xylophone", "🎵"],
        Y: ["Yack", "🐂"], Z: ["Zèbre", "🦓"]
      }
    }
  };
  function curLang() { return localStorage.getItem("tl_lang") || "en"; }
  function letterPack() {
    var l = curLang();
    if (l === "en" || !LANGS[l]) return { letters: ANIMALS, isFor: "is for", locale: "en-AU", numbers: null };
    return LANGS[l];
  }

  // ---- voices ----
  var kidAudio = document.getElementById("kid-audio");
  // Neural clips only — no OS/system speech-synthesis fallback (it was sneaking through on
  // rapid keymashing when an <audio> play() got interrupted). If a clip can't play, stay silent.
  function volume() { var v = parseInt(localStorage.getItem("tl_volume"), 10); return isNaN(v) ? 0.8 : Math.max(0, Math.min(1, v / 100)); }
  // Voices are per-language now (each language has its own neural clip set).
  function voicesForLang(lang) { return (window.__tlVoices || []).filter(function (v) { return (v.lang || "en") === lang; }); }
  function hasClips(lang) { return voicesForLang(lang).length > 0; }
  function clipVoice() {
    var lang = curLang(), list = voicesForLang(lang);
    var saved = localStorage.getItem("tl_clip_voice_" + lang);
    if (saved && list.some(function (v) { return v.dir === saved; })) return saved;
    return list[0] ? list[0].dir : (lang === "en" ? "jenny" : "");
  }
  // Route to the saved speaker. deviceIds can rotate between sessions, so if the saved
  // id fails we re-resolve by the saved LABEL (stable) and rewrite it — that's what makes
  // the choice persist across reloads/rebuilds.
  function applySink() {
    if (!kidAudio || !kidAudio.setSinkId) return Promise.resolve();
    var sink = localStorage.getItem("tl_sink"), label = localStorage.getItem("tl_sink_label");
    if (!sink) return kidAudio.setSinkId("").catch(function () {});
    return kidAudio.setSinkId(sink).catch(function () {
      if (!label || !navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return;
      return navigator.mediaDevices.enumerateDevices().then(function (list) {
        var m = list.filter(function (x) { return x.kind === "audiooutput" && x.label === label; })[0];
        if (m) { localStorage.setItem("tl_sink", m.deviceId); return kidAudio.setSinkId(m.deviceId).catch(function () {}); }
      });
    });
  }
  function playKey(key) {
    var v = clipVoice(); if (!v) return; // no voice for this language yet -> stay silent
    // Android WebView (kidodi play mode): route to the native SoundPool bridge at an
    // app-controlled gain (governable + mutable by the kiosk; never the OS voice). The
    // guard means this is a no-op in the browser / Tauri desktop build.
    if (window.AndroidAudio && window.AndroidAudio.play) {
      try { window.AndroidAudio.play(v, String(key)); } catch (e) {}
      return;
    }
    // Desktop: native playback to the chosen output device at our own volume.
    if (TAURI) {
      try { TAURI.invoke("play_clip", { voice: v, key: String(key), volume: volume() }).catch(function () {}); } catch (e) {}
      return;
    }
    // Browser: the <audio> sink was set on selection/load (not here) so play() doesn't race
    // setSinkId. Just point at the clip and play. No OS-voice fallback.
    if (!kidAudio) return;
    try {
      kidAudio.src = "audio/" + encodeURIComponent(v) + "/" + encodeURIComponent(key) + ".wav";
      kidAudio.volume = volume();
      var pr = kidAudio.play(); if (pr && pr.catch) pr.catch(function () {});
    } catch (e) {}
  }
  // Speak a key in the current language (neural clip; silent if that language has no pack).
  function speakKey(L) { playKey(L); }
  // A short sample of the current language+voice (settings test button / on-change).
  function testSample() {
    var pk = letterPack(), w = (pk.letters.A && pk.letters.A[0]) || "A";
    speakKey("A", "A " + pk.isFor + " " + w, pk.locale);
  }
  applySink(); // arm the saved speaker on load

  // ---- per-clip timing (audio/timings.json, built by scripts/gen-timings.sh) ----
  // [clip ms, animal-word ms] per voice per key. The letter gate waits the clip's OWN
  // length -- muted or not -- so the pacing a child learns never changes with the volume.
  // Loaded by a <script> tag, NOT fetched: this page is served from file:// inside the
  // Android WebView, where fetch() is blocked by the opaque origin and would leave every
  // clip on the fallback guess. (audio/voices.json is still fetched -- see the note there.)
  var LETTER_TAIL = 140, FALLBACK_TIMING = [1600, 900];
  function clipTiming(key) {
    var T = window.__tlTimings, v = clipVoice(), m = T && T[v] && T[v][key];
    if (!m || m.length < 2) m = FALLBACK_TIMING;
    return { d: m[0], w: m[1] };
  }
  // Land on the touch point, but keep the whole thing on screen when a finger hits an edge.
  // The margins aren't square: the letter is centred on the finger and the animal + caption
  // hang BELOW it, so the bottom needs far more room than the top.
  var EDGE = { x: 140, top: 100, bottom: 215 };
  function placeAt(el, x, y) {
    var W = window.innerWidth, H = window.innerHeight;
    if (typeof x !== "number" || typeof y !== "number") {
      x = W * (0.15 + Math.random() * 0.7);
      y = H * (0.22 + Math.random() * 0.45);
    }
    var mx = Math.min(EDGE.x, W / 2), top = Math.min(EDGE.top, H / 2), bot = Math.min(EDGE.bottom, H / 2);
    el.style.left = Math.max(mx, Math.min(W - mx, x)) + "px";
    el.style.top = Math.max(top, Math.min(H - bot, y)) + "px";
  }

  // ---- fun: letters & sparkles ----
  // The letter appears under the finger as its name is spoken; the animal picture is held
  // back until the clip reaches the word, so "A" lands with "A" and the alligator lands
  // with "Alligator". The whole thing lives exactly as long as its clip.
  function showLetter(key, x, y) {
    var L = (key || "").toUpperCase();
    var pk = letterPack();
    var t = clipTiming(L);
    var life = t.d + LETTER_TAIL;
    var el = document.createElement("div"); el.className = "fly-letter";
    el.style.color = rand(COLORS);
    el.style.setProperty("--life", life + "ms");
    placeAt(el, x, y);
    if (pk.letters[L]) {
      var word = pk.letters[L][0], emoji = pk.letters[L][1];
      // .fl-stage is absolutely positioned, so revealing it can't nudge the letter.
      el.innerHTML = '<span class="fl-big">' + L + '</span>'
                   + '<span class="fl-stage" hidden><span class="fl-pic">' + emoji + '</span>'
                   + '<span class="fl-word">' + pk.isFor + " " + word + '</span></span>';
      speakKey(L, L + " " + pk.isFor + " " + word, pk.locale);
      var stage = el.querySelector(".fl-stage");
      setTimeout(function () { if (stage && stage.parentNode) stage.hidden = false; }, t.w);
    } else if (/^[0-9]$/.test(L)) {
      el.innerHTML = '<span class="fl-big">' + L + "</span>";
      speakKey(L, pk.numbers ? pk.numbers[+L] : L, pk.locale);
    } else {
      el.innerHTML = '<span class="fl-big">' + rand(BURSTS) + "</span>";
    }
    overlay.appendChild(el); setTimeout(function () { el.remove(); }, life);
  }
  // id = the finger (pointerId/touch identifier). The throttle is PER FINGER: a single
  // shared one meant a four-finger mash only ever drew one trail.
  function sparkle(x, y, id) {
    var k = id == null ? "m" : String(id), now = Date.now();
    if (now - (lastSpark[k] || 0) < 26) return;
    lastSpark[k] = now;
    var s = document.createElement("div"); s.className = "sparkle"; s.textContent = rand(SPARKS);
    s.style.left = x + "px"; s.style.top = y + "px"; s.style.fontSize = (12 + Math.random() * 18) + "px";
    overlay.appendChild(s); setTimeout(function () { s.remove(); }, 720);
  }

  // ---- one letter clip at a time + colorful gap-fill shapes ----
  // Mashing used to fire 2-5 letter clips at once (overloaded playback). Now a tap/key speaks a
  // letter ONLY when no clip is playing; while one plays it drops a random colorful SHAPE instead
  // SoundPool gives no completion callback, so the wait came from a flat ~1.3s guess -- but the
  // clips run 0.7-2.0s, so letters overlapped. The wait is now the clip's mapped length.
  var audioBusy = false, audioTimer = null;
  function markAudio(ms) {
    audioBusy = true;
    if (audioTimer) clearTimeout(audioTimer);
    audioTimer = setTimeout(function () { audioBusy = false; audioTimer = null; }, ms);
  }
  var SHAPES = ["circle", "square", "triangle", "star"];
  function spawnShape(x, y) {
    if (typeof x !== "number") x = window.innerWidth * (0.15 + Math.random() * 0.7);
    if (typeof y !== "number") y = window.innerHeight * (0.2 + Math.random() * 0.55);
    var el = document.createElement("div");
    el.className = "tl-shape tl-" + rand(SHAPES);
    var size = 46 + Math.random() * 74;   // ~46-120px
    el.style.left = x + "px"; el.style.top = y + "px";
    el.style.width = size + "px"; el.style.height = size + "px";
    el.style.background = rand(COLORS);
    el.style.setProperty("--rot", (Math.random() * 50 - 25) + "deg");
    overlay.appendChild(el); setTimeout(function () { el.remove(); }, 900);
  }
  // Fire a spoken letter if free, otherwise a shape. x/y optional (keyboard -> random placement).
  function fireOrShape(key, x, y) {
    if (audioBusy) { spawnShape(x, y); return; }
    showLetter(key, x, y);
    markAudio(clipTiming(String(key || "").toUpperCase()).d + LETTER_TAIL);
  }

  // ---- fullscreen + keyboard lock (browser-side defence) ----
  function isFs() { return !!(document.fullscreenElement || document.webkitFullscreenElement); }
  function goFullscreen() {
    // Browser fullscreen is what reliably makes Windows hide the taskbar, so keep it in
    // Tauri too (alongside the native fullscreen + foreground in main.rs). Skip the
    // Keyboard Lock API in Tauri though — the native input grab covers keys, and the
    // Keyboard Lock API can swallow PrintScreen.
    var el = document.documentElement, rf = el.requestFullscreen || el.webkitRequestFullscreen;
    if (!rf) return;
    function afterFs() { if (!TAURI) lockKeyboard(); }
    try { var p = rf.call(el); if (p && p.then) p.then(afterFs).catch(function () {}); else afterFs(); } catch (e) {}
  }
  function exitFullscreen() { if (isFs()) { var xf = document.exitFullscreen || document.webkitExitFullscreen; if (xf) { try { xf.call(document); } catch (e) {} } } }
  function lockKeyboard() { try { if (navigator.keyboard && navigator.keyboard.lock) navigator.keyboard.lock(); } catch (e) {} }
  function unlockKeyboard() { try { if (navigator.keyboard && navigator.keyboard.unlock) navigator.keyboard.unlock(); } catch (e) {} }

  function inUnlock(t) { return t && t.closest && t.closest("#unlock-panel"); }
  function blockOther(e) { if (inUnlock(e.target)) return; e.preventDefault(); e.stopImmediatePropagation(); }
  function onKeydown(e) {
    if (inUnlock(e.target)) return;
    if (locked && !isFs()) goFullscreen();
    fireOrShape(e.key, null, null);   // keyboard still works; gated by the same one-clip-at-a-time rule
    e.preventDefault(); e.stopImmediatePropagation();
  }
  // Mouse/pen only -- touch goes through onTouch, which walks every finger. Handling touch
  // here as well would draw each trail twice.
  function onPointer(e) {
    if (e.pointerType === "touch") return;
    if (locked && !inUnlock(e.target)) sparkle(e.clientX, e.clientY, e.pointerId);
  }
  // Every finger trails from its OWN position. This read e.touches[0] and nothing else, so a
  // four-finger mash piled the whole stream onto the first finger's spot.
  function onTouch(e) {
    if (!locked || inUnlock(e.target)) return;
    var ts = e.changedTouches && e.changedTouches.length ? e.changedTouches : e.touches;
    if (!ts) return;
    for (var i = 0; i < ts.length; i++) sparkle(ts[i].clientX, ts[i].clientY, ts[i].identifier);
  }
  // Touch-native mash: a tap/click fires a RANDOM letter (the physical keyboard still fires
  // the real key). This is what makes the playground work on tablets/phones with no keyboard.
  function onTapLetter(e) {
    if (!locked) return;
    var t = e.target;
    if (t && t.closest && t.closest("#unlock-panel, #adult-unlock-btn, #sp-consent")) return;
    if (!isFs()) goFullscreen();
    var L = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(Math.floor(Math.random() * 26));
    fireOrShape(L, e.clientX, e.clientY);   // letter if free, else a colorful shape at the touch point
    if (typeof e.clientX === "number") sparkle(e.clientX, e.clientY, e.pointerId);
  }
  var OTHERS = ["keyup", "keypress", "contextmenu", "wheel"];

  function lock() {
    if (locked) return; locked = true;
    if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
    if (home) home.hidden = true;
    overlay.hidden = false; document.body.classList.add("locked");
    window.addEventListener("keydown", onKeydown, true);
    OTHERS.forEach(function (t) { window.addEventListener(t, blockOther, true); });
    window.addEventListener("pointermove", onPointer, true);
    window.addEventListener("touchmove", onTouch, true);
    window.addEventListener("pointerdown", onTapLetter, true);
    goFullscreen();
    nativeLock(true);
    if (soundPlayOn()) startSoundPlay(); // voice -> letters, alongside the keyboard
  }
  function unlock() {
    if (!locked) return; locked = false;
    if (refsTimer) { clearTimeout(refsTimer); refsTimer = null; }
    overlay.hidden = true; document.body.classList.remove("locked");
    if (home) home.hidden = false;
    window.removeEventListener("keydown", onKeydown, true);
    OTHERS.forEach(function (t) { window.removeEventListener(t, blockOther, true); });
    window.removeEventListener("pointermove", onPointer, true);
    window.removeEventListener("touchmove", onTouch, true);
    window.removeEventListener("pointerdown", onTapLetter, true);
    closePanel(); stopChallenge(); unlockKeyboard(); exitFullscreen();
    overlay.querySelectorAll(".fly-letter,.sparkle").forEach(function (n) { n.remove(); });
    stopSoundPlay();
    nativeLock(false);
    // Android WebView (kidodi play mode): a successful timed-code unlock exits play mode.
    if (window.AndroidExit && window.AndroidExit.exit) { try { window.AndroidExit.exit(); } catch (e) {} }
  }
  if (lockBtn) lockBtn.addEventListener("click", lock);

  // re-assert fullscreen if Esc drops it (unless settings dialog is open)
  function onFsChange() {
    if (!locked || settingsOpen) return;
    if (isFs()) { if (refsTimer) { clearTimeout(refsTimer); refsTimer = null; } }
    else { if (refsTimer) clearTimeout(refsTimer); refsTimer = setTimeout(function () { if (locked && !isFs() && !settingsOpen) goFullscreen(); }, 2000); }
  }
  document.addEventListener("fullscreenchange", onFsChange);
  document.addEventListener("webkitfullscreenchange", onFsChange);

  // ---- timed 6-digit adult unlock ----
  var aBtn = document.getElementById("adult-unlock-btn"), panel = document.getElementById("unlock-panel"),
      closeBtn = document.getElementById("up-close"), codeEl = document.getElementById("unlock-code"),
      inp = document.getElementById("unlock-input"), timerEl = document.getElementById("unlock-timer"), msg = document.getElementById("unlock-msg");
  var code = "", remaining = 30, tick = null;
  function newCode() { code = ""; for (var i = 0; i < 6; i++) code += Math.floor(Math.random() * 10); if (codeEl) codeEl.textContent = code.split("").join(" "); }
  function freshTimer() { remaining = 30; if (timerEl) timerEl.textContent = "30"; }
  function startChallenge() {
    newCode(); if (inp) inp.value = ""; if (msg) msg.textContent = ""; freshTimer();
    if (tick) clearInterval(tick);
    tick = setInterval(function () {
      remaining--; if (timerEl) timerEl.textContent = String(Math.max(0, remaining));
      if (remaining <= 0) { if (msg) msg.textContent = "time up — fresh code"; newCode(); if (inp) inp.value = ""; freshTimer(); }
    }, 1000);
  }
  function stopChallenge() { if (tick) { clearInterval(tick); tick = null; } }
  function openPanel() { if (!panel) return; panel.hidden = false; resetToUnlock(); startChallenge(); if (inp) setTimeout(function () { inp.focus(); }, 40); }
  function closePanel() { if (panel) panel.hidden = true; stopChallenge(); settingsOpen = false; resetToUnlock(); if (locked && !isFs()) goFullscreen(); }
  function resetToUnlock() { var uv = document.getElementById("unlock-view"); if (uv) uv.hidden = false; }
  if (aBtn) aBtn.addEventListener("click", function (e) { e.stopPropagation(); if (panel && panel.hidden) openPanel(); else closePanel(); });
  if (closeBtn) closeBtn.addEventListener("click", function (e) { e.stopPropagation(); closePanel(); });
  if (inp) inp.addEventListener("input", function () {
    var v = inp.value.replace(/[^0-9]/g, "").slice(0, 6); inp.value = v;
    if (v === code) { unlock(); return; }
    if (v && code.indexOf(v) !== 0) { if (msg) msg.textContent = "oops — new code, try again"; newCode(); inp.value = ""; freshTimer(); }
  });

  // ---- settings: voice / device / volume ----
  function renderDevices(outs) {
    var dl = document.getElementById("device-list"); if (!dl) return;
    dl.innerHTML = ""; var sel = localStorage.getItem("tl_sink") || "";
    [{ deviceId: "", label: "System default speaker" }].concat(outs).forEach(function (o) {
      var b = document.createElement("button"); b.className = "device-item" + (o.deviceId === sel ? " active" : "");
      b.textContent = o.label || ("output " + (o.deviceId || "default"));
      b.addEventListener("click", function (e) {
        e.stopPropagation(); localStorage.setItem("tl_sink", o.deviceId); localStorage.setItem("tl_sink_label", o.label || "system default");
        dl.querySelectorAll(".device-item").forEach(function (x) { x.classList.toggle("active", x === b); });
        showCurrentDevice(); applySink();
      });
      dl.appendChild(b);
    });
  }
  // Show output devices IF their names are already readable (i.e. mic was granted before,
  // e.g. by Sound Play). Otherwise prompt the user to tap "choose speaker".
  function listSpeakers() {
    var md = navigator.mediaDevices, dl = document.getElementById("device-list"); if (!dl) return;
    if (!md || !md.enumerateDevices) { dl.innerHTML = '<div class="device-empty">output selection not supported here</div>'; return; }
    md.enumerateDevices().then(function (list) {
      var outs = list.filter(function (x) { return x.kind === "audiooutput" && x.label && x.deviceId && x.deviceId !== "default"; });
      if (outs.length) renderDevices(outs);
      else dl.innerHTML = '<div class="device-empty">tap “choose speaker” to let the browser read your device names (mic, same as Sound Play)</div>';
    }).catch(function () { dl.innerHTML = '<div class="device-empty">device list unavailable</div>'; });
  }
  // Browsers only reveal speaker NAMES after audio (mic) permission. We grant it once and
  // stop the mic immediately — we only needed the permission. (Consistent with Sound Play.)
  function micUnlock() {
    var md = navigator.mediaDevices, dl = document.getElementById("device-list");
    if (!md || !md.getUserMedia) { if (dl) dl.innerHTML = '<div class="device-empty">not supported — plays on system default</div>'; return; }
    if (dl) dl.innerHTML = '<div class="device-empty">reading your speaker names…</div>';
    md.getUserMedia({ audio: true }).then(function (s) { s.getTracks().forEach(function (t) { t.stop(); }); listSpeakers(); })
      .catch(function () { if (dl) dl.innerHTML = '<div class="device-empty">mic access declined — audio plays on the system default. The desktop app picks speakers with no prompt.</div>'; });
  }
  function chooseSpeaker() {
    var md = navigator.mediaDevices, dl = document.getElementById("device-list");
    // Preferred: the no-mic OS picker (Chrome/Edge 105+).
    if (md && md.selectAudioOutput) {
      if (dl) dl.innerHTML = '<div class="device-empty">opening speaker picker…</div>';
      md.selectAudioOutput().then(function (d) {
        localStorage.setItem("tl_sink", d.deviceId); localStorage.setItem("tl_sink_label", d.label || "chosen device");
        showCurrentDevice(); applySink(); listSpeakers();
      }).catch(function (err) {
        if (err && err.name === "AbortError") { showCurrentDevice(); listSpeakers(); return; }
        micUnlock(); // picker refused -> reveal names via mic
      });
      return;
    }
    micUnlock(); // no picker API (this Edge) -> reveal names via mic
  }
  function loadVoices() {
    var clipSel = document.getElementById("clip-voice");
    function fill() {
      if (!clipSel) return;
      var vs = voicesForLang(curLang()), cur = clipVoice(); clipSel.innerHTML = "";
      if (!vs.length) { var e = document.createElement("option"); e.value = ""; e.textContent = "(system voice)"; clipSel.appendChild(e); return; }
      vs.forEach(function (v) { var o = document.createElement("option"); o.value = v.dir; o.textContent = v.label || v.dir; if (v.dir === cur) o.selected = true; clipSel.appendChild(o); });
    }
    if (window.__tlVoices) { fill(); return; }
    // NOTE: on the Android kiosk this page runs from file://, where fetch() is refused, so
    // this lands in the catch and the voice list stays empty -- the playground then always
    // speaks the "jenny" default and this picker has nothing in it. Harmless but wrong;
    // the fix is to ship voices as a <script> assignment the way audio/timings.js is.
    fetch("audio/voices.json").then(function (r) { return r.json(); }).then(function (l) { window.__tlVoices = l || []; fill(); }).catch(function () { window.__tlVoices = []; });
  }
  loadVoices(); // load the voice manifest at startup so the first keypress has clips ready
  var settingsPanel = document.getElementById("settings-panel"),
      unlockView = document.getElementById("unlock-view"), sBack = document.getElementById("settings-back"),
      deviceRow = document.getElementById("device-row"),
      scanBtn = document.getElementById("scan-device"), testBtn = document.getElementById("test-sound"),
      volRange = document.getElementById("volume-range"), volVal = document.getElementById("volume-val"),
      clipSel = document.getElementById("clip-voice"), devCur = document.getElementById("device-current");
  function showCurrentDevice() { if (devCur) devCur.textContent = "using: " + (localStorage.getItem("tl_sink_label") || "system default"); }
  if (volRange) { var vp = Math.round(volume() * 100); volRange.value = String(vp); if (volVal) volVal.textContent = vp + "%"; }
  if (volRange) volRange.addEventListener("input", function () { var v = parseInt(volRange.value, 10) || 0; localStorage.setItem("tl_volume", String(v)); if (volVal) volVal.textContent = v + "%"; if (kidAudio) kidAudio.volume = volume(); });
  if (clipSel) clipSel.addEventListener("change", function (e) { e.stopPropagation(); localStorage.setItem("tl_clip_voice_" + curLang(), clipSel.value); testSample(); });
  // Native (desktop) output-device selection — a real inline list from cpal.
  function nativeSink() { return localStorage.getItem("tl_sink_name") || ""; }
  function showNativeDevice() { if (devCur) devCur.textContent = "using: " + (nativeSink() || "system default"); }
  function applyNativeSink() { if (TAURI) { try { TAURI.invoke("set_audio_device", { name: nativeSink() || null }).catch(function () {}); } catch (e) {} } }
  function populateNativeDevices() {
    var deviceList = document.getElementById("device-list"); if (!deviceList || !TAURI) return;
    deviceList.innerHTML = '<div class="device-empty">finding outputs…</div>';
    try {
      TAURI.invoke("list_audio_devices").then(function (list) {
        deviceList.innerHTML = ""; var sel = nativeSink();
        [{ name: "", label: "System default device" }].concat((list || []).map(function (n) { return { name: n, label: n }; })).forEach(function (o) {
          var b = document.createElement("button"); b.className = "device-item" + (o.name === sel ? " active" : ""); b.textContent = o.label;
          b.addEventListener("click", function (e) {
            e.stopPropagation(); localStorage.setItem("tl_sink_name", o.name);
            deviceList.querySelectorAll(".device-item").forEach(function (x) { x.classList.toggle("active", x === b); });
            showNativeDevice(); applyNativeSink();
          });
          deviceList.appendChild(b);
        });
        showNativeDevice();
      }).catch(function () { deviceList.innerHTML = '<div class="device-empty">no output devices found</div>'; });
    } catch (e) {}
  }

  function applyVoiceUI() {
    loadVoices();
    if (deviceRow) deviceRow.hidden = false;
    var deviceList = document.getElementById("device-list"),
        browserHint = document.getElementById("device-browser-hint"),
        nativeNote = document.getElementById("device-native-note");
    if (TAURI) {
      // Native inline device list (no browser popup, no "coming soon" note).
      if (nativeNote) nativeNote.hidden = true;
      if (browserHint) browserHint.hidden = true;
      if (scanBtn) scanBtn.hidden = true;
      if (devCur) devCur.hidden = false;
      if (deviceList) deviceList.hidden = false;
      populateNativeDevices();
    } else {
      // Browser: show the output-device list (names need a one-time mic grant, which the
      // "choose speaker" button handles — same mic Sound Play already uses).
      if (browserHint) browserHint.hidden = true;
      if (nativeNote) nativeNote.hidden = true;
      if (devCur) devCur.hidden = false;
      if (scanBtn) scanBtn.hidden = false;
      if (deviceList) deviceList.hidden = false;
      showCurrentDevice();
      listSpeakers();
    }
  }
  function openSettings() { if (!settingsPanel) return; settingsOpen = true; settingsPanel.hidden = false; applyVoiceUI(); }
  // Settings are reached only from the home screen now (never mid-lock), so closing
  // just hides the modal and returns to home.
  function closeSettings() { if (settingsPanel) settingsPanel.hidden = true; settingsOpen = false; }
  var langSelect = document.getElementById("lang-select");
  if (langSelect) {
    langSelect.value = curLang();
    langSelect.addEventListener("change", function (e) {
      e.stopPropagation(); localStorage.setItem("tl_lang", langSelect.value);
      loadVoices();   // refill the voice list for the new language
      testSample();   // sample the new language right away
    });
  }
  var homeSettingsBtn = document.getElementById("home-settings-btn");
  if (homeSettingsBtn) homeSettingsBtn.addEventListener("click", function (e) { e.stopPropagation(); openSettings(); });
  var settingsClose = document.getElementById("settings-close");
  if (settingsClose) settingsClose.addEventListener("click", function (e) { e.stopPropagation(); closeSettings(); });
  if (sBack) sBack.addEventListener("click", function (e) { e.stopPropagation(); closeSettings(); });
  if (scanBtn) scanBtn.addEventListener("click", function (e) { e.stopPropagation(); chooseSpeaker(); });
  if (testBtn) testBtn.addEventListener("click", function (e) { e.stopPropagation(); testSample(); });

  // ---- global lock shortcut (desktop only; default Ctrl+Alt+Shift+L) ----
  var shortcutRow = document.getElementById("shortcut-row"),
      shortcutCurrent = document.getElementById("shortcut-current"),
      shortcutRecord = document.getElementById("shortcut-record");
  var DEFAULT_SHORTCUT = "Ctrl+Alt+Shift+L", recordingShortcut = false;
  function curShortcut() { return localStorage.getItem("tl_shortcut") || DEFAULT_SHORTCUT; }
  function showShortcut() { if (shortcutCurrent) shortcutCurrent.textContent = curShortcut(); }
  function applyShortcut(previous) {
    if (!TAURI) return;
    try { TAURI.invoke("set_shortcut", { accelerator: curShortcut(), previous: previous || null }).catch(function () {}); } catch (e) {}
  }
  // Build a Tauri accelerator string ("Ctrl+Alt+Shift+L") from a keydown; returns null
  // until a non-modifier key is included so the user can hold the modifiers first.
  function comboFromEvent(e) {
    var k = e.key;
    if (k === "Control" || k === "Alt" || k === "Shift" || k === "Meta" || k === "OS") return null;
    var parts = [];
    if (e.ctrlKey) parts.push("Ctrl");
    if (e.altKey) parts.push("Alt");
    if (e.shiftKey) parts.push("Shift");
    if (e.metaKey) parts.push("Super");
    if (k === " " || k === "Spacebar") k = "Space";
    else if (k.indexOf("Arrow") === 0) k = k.slice(5); // ArrowUp -> Up
    else if (k.length === 1) k = k.toUpperCase();
    parts.push(k);
    return parts.join("+");
  }
  if (shortcutRow) shortcutRow.hidden = !TAURI;
  showShortcut();
  if (shortcutRecord) shortcutRecord.addEventListener("click", function (e) {
    e.stopPropagation(); recordingShortcut = true; shortcutRecord.textContent = "press your combo…";
  });
  window.addEventListener("keydown", function (e) {
    if (!recordingShortcut) return;
    e.preventDefault(); e.stopImmediatePropagation(); // beat the lock's key handler
    var combo = comboFromEvent(e);
    if (!combo) return; // modifiers only so far — keep waiting
    var prev = curShortcut();
    localStorage.setItem("tl_shortcut", combo);
    recordingShortcut = false;
    if (shortcutRecord) shortcutRecord.textContent = "⌨️ record new shortcut";
    showShortcut();
    applyShortcut(prev);
  }, true);
  applyShortcut(null); // register the saved/default shortcut at startup

  // ---- system tray toggle (desktop only) ----
  var trayRow = document.getElementById("tray-row"), trayToggle = document.getElementById("tray-toggle");
  function trayEnabled() { return localStorage.getItem("tl_tray") !== "0"; } // default on
  function applyTray() { if (!TAURI) return; try { TAURI.invoke("set_tray", { visible: trayEnabled() }).catch(function () {}); } catch (e) {} }
  if (trayRow) trayRow.hidden = !TAURI;
  if (trayToggle) {
    trayToggle.checked = trayEnabled();
    trayToggle.addEventListener("change", function () { localStorage.setItem("tl_tray", trayToggle.checked ? "1" : "0"); applyTray(); });
  }
  applyTray(); // honor the saved preference on launch

  // arm the saved native output device on launch (desktop)
  applyNativeSink();

  // ---- floating lock button (desktop only) ----
  var floatRow = document.getElementById("float-row"),
      floatToggle = document.getElementById("float-toggle"),
      floatCorner = document.getElementById("float-corner"),
      floatSize = document.getElementById("float-size"), floatSizeVal = document.getElementById("float-size-val"),
      floatOpacity = document.getElementById("float-opacity"), floatOpacityVal = document.getElementById("float-opacity-val");
  function fOn() { return localStorage.getItem("tl_float_on") === "1"; }
  function fCorner() { return localStorage.getItem("tl_float_corner") || "br"; }
  function fSize() { var n = parseInt(localStorage.getItem("tl_float_size"), 10); return isNaN(n) ? 96 : n; }
  function fOpacity() { var n = parseInt(localStorage.getItem("tl_float_opacity"), 10); return isNaN(n) ? 85 : n; }
  function applyFloat() {
    if (!TAURI) return;
    try {
      TAURI.invoke("set_float_size", { px: fSize() });
      TAURI.invoke("set_float_corner", { corner: fCorner() });
      TAURI.invoke("set_float_opacity", { pct: fOpacity() });
      TAURI.invoke("set_float_visible", { visible: fOn() });
    } catch (e) {}
  }
  if (floatRow) floatRow.hidden = !TAURI;
  if (floatToggle) { floatToggle.checked = fOn(); floatToggle.addEventListener("change", function () { localStorage.setItem("tl_float_on", floatToggle.checked ? "1" : "0"); applyFloat(); }); }
  if (floatCorner) { floatCorner.value = fCorner(); floatCorner.addEventListener("change", function () { localStorage.setItem("tl_float_corner", floatCorner.value); applyFloat(); }); }
  if (floatSize) {
    floatSize.value = String(fSize()); if (floatSizeVal) floatSizeVal.textContent = String(fSize());
    floatSize.addEventListener("input", function () {
      var v = parseInt(floatSize.value, 10) || 96; localStorage.setItem("tl_float_size", String(v)); if (floatSizeVal) floatSizeVal.textContent = String(v);
      if (TAURI) { try { TAURI.invoke("set_float_size", { px: v }); TAURI.invoke("set_float_corner", { corner: fCorner() }); } catch (e) {} }
    });
  }
  if (floatOpacity) {
    floatOpacity.value = String(fOpacity()); if (floatOpacityVal) floatOpacityVal.textContent = String(fOpacity());
    floatOpacity.addEventListener("input", function () {
      var v = parseInt(floatOpacity.value, 10) || 85; localStorage.setItem("tl_float_opacity", String(v)); if (floatOpacityVal) floatOpacityVal.textContent = String(v);
      if (TAURI) { try { TAURI.invoke("set_float_opacity", { pct: v }); } catch (e) {} }
    });
  }
  applyFloat(); // honor saved float prefs on launch

  // ---- auto idle-lock (focused window only) ----
  var iToggle = document.getElementById("idle-toggle"), iRange = document.getElementById("idle-range"), iMin = document.getElementById("idle-min-val");
  var idleTimer = null;
  function idleEnabled() { return localStorage.getItem("tl_idle_enabled") === "1"; }
  function idleMins() { var m = parseInt(localStorage.getItem("tl_idle_mins"), 10); return isNaN(m) ? 5 : Math.max(1, Math.min(30, m)); }
  function clearIdle() { if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; } }
  function armIdle() {
    clearIdle();
    if (!idleEnabled() || locked || !document.hasFocus()) return;
    idleTimer = setTimeout(function () { if (idleEnabled() && document.hasFocus() && !locked) lock(); }, idleMins() * 60000);
  }
  ["mousemove", "keydown", "pointerdown", "wheel", "touchstart", "scroll"].forEach(function (ev) { window.addEventListener(ev, armIdle, true); });
  window.addEventListener("focus", armIdle); window.addEventListener("blur", clearIdle);
  document.addEventListener("visibilitychange", function () { if (document.hidden) clearIdle(); else armIdle(); });
  if (iToggle) { iToggle.checked = idleEnabled(); iToggle.addEventListener("change", function () { localStorage.setItem("tl_idle_enabled", iToggle.checked ? "1" : "0"); armIdle(); }); }
  if (iRange) { iRange.value = String(idleMins()); if (iMin) iMin.textContent = String(idleMins()); iRange.addEventListener("input", function () { var v = parseInt(iRange.value, 10) || 5; localStorage.setItem("tl_idle_mins", String(v)); if (iMin) iMin.textContent = String(v); armIdle(); }); }
  armIdle();

  // ---- Sound Play: opt-in toggle; runs INSIDE the lock (words fly with the keys) ----
  var spStatus = document.getElementById("sp-status"), spToggle = document.getElementById("soundplay-toggle"),
      spConsent = document.getElementById("sp-consent"), spStartBtn = document.getElementById("sp-consent-start"),
      spCancelBtn = document.getElementById("sp-consent-cancel");
  function soundPlayOn() { return localStorage.getItem("tl_soundplay_on") === "1"; }
  function flyWord(text) {
    if (!overlay || overlay.hidden) return; // only while the lock playground is up
    String(text).split(/\s+/).forEach(function (w, i) {
      if (!w) return;
      setTimeout(function () {
        var el = document.createElement("div"); el.className = "fly-letter";
        el.style.color = rand(COLORS); el.style.left = (12 + Math.random() * 66) + "vw"; el.style.top = (22 + Math.random() * 42) + "vh";
        el.innerHTML = '<span class="fl-big">' + w.toUpperCase() + "</span>";
        overlay.appendChild(el); setTimeout(function () { el.remove(); }, 1500);
      }, i * 180);
    });
  }
  function spSetStatus(s) {
    if (!spStatus) return;
    if (!s || /listening/i.test(s)) { spStatus.hidden = true; spStatus.textContent = ""; } // hide once live
    else { spStatus.textContent = s; spStatus.hidden = false; }
  }
  function startSoundPlay() { if (window.SoundPlay) { spSetStatus("starting voice…"); window.SoundPlay.start(flyWord, spSetStatus); } }
  function stopSoundPlay() { if (window.SoundPlay) window.SoundPlay.stop(); if (spStatus) spStatus.hidden = true; }
  if (spToggle) {
    spToggle.checked = soundPlayOn();
    spToggle.addEventListener("change", function () {
      if (spToggle.checked) {
        if (localStorage.getItem("tl_soundplay_ok") === "1") {
          localStorage.setItem("tl_soundplay_on", "1");
          if (locked) startSoundPlay(); // turn on mid-lock too
        } else {
          spToggle.checked = false;                 // need consent first
          if (spConsent) spConsent.hidden = false;
        }
      } else {
        localStorage.setItem("tl_soundplay_on", "0");
        if (locked) stopSoundPlay();
      }
    });
  }
  if (spStartBtn) spStartBtn.addEventListener("click", function (e) {
    e.stopPropagation();
    localStorage.setItem("tl_soundplay_ok", "1"); localStorage.setItem("tl_soundplay_on", "1");
    if (spConsent) spConsent.hidden = true; if (spToggle) spToggle.checked = true;
    if (locked) startSoundPlay();
  });
  if (spCancelBtn) spCancelBtn.addEventListener("click", function (e) {
    e.stopPropagation(); if (spConsent) spConsent.hidden = true; if (spToggle) spToggle.checked = false; localStorage.setItem("tl_soundplay_on", "0");
  });

  // expose a tiny hook so the native side (or tests) can drive the lock
  window.ToddlerLock = { lock: lock, unlock: function () { unlock(); }, isLocked: function () { return locked; } };
})();
