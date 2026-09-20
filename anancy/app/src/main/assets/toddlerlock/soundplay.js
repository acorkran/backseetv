/* Sound Play — on-device speech-to-letters. The child's sounds become words on screen,
 * live, using Vosk (Kaldi) compiled to WebAssembly. Everything runs locally: the mic
 * audio never leaves the device. Engine + model are lazy-loaded from a CDN the first
 * time Sound Play is turned on, so the base tool stays light.
 *
 * exposes window.SoundPlay = { start(onText, onStatus), stop() }
 */
(function () {
  "use strict";
  var VOSK_SRC = "https://cdn.jsdelivr.net/npm/vosk-browser@0.0.8/dist/vosk.js";
  var MODEL_URL = "https://ccoreilly.github.io/vosk-browser/models/vosk-model-small-en-us-0.15.tar.gz";
  var SAMPLE_RATE = 48000;

  // Constrained vocabulary: real words a toddler actually mumbles (must exist in the
  // model's dictionary — invented syllables like "ba/da/ka" can't be matched). A short
  // list snaps the child's sounds to the NEAREST word instantly and decodes much faster
  // than full English. No "[unk]", so it always lands on something. Edit freely.
  var WORDS = [
    "mama", "dada", "mom", "dad", "mommy", "daddy", "papa", "baby", "ma", "pa",
    "hi", "hello", "bye", "no", "yes", "yeah", "mine", "more", "please", "thanks",
    "la", "ah", "oh", "ooh", "uh", "boo", "wow", "yay", "ow", "uhoh",
    "do", "go", "so", "me", "you", "he", "she", "we", "my", "ya", "ha", "day", "way", "say", "hey", "woo", "wee",
    "dog", "cat", "cow", "pig", "duck", "fish", "bird", "bear", "bee", "frog", "horse", "sheep", "fox", "bug",
    "woof", "meow", "moo", "baa", "oink", "quack", "roar",
    "milk", "juice", "water", "cup", "spoon", "food", "snack", "cookie", "apple", "banana", "cheese", "bread", "egg",
    "ball", "book", "block", "toy", "car", "bus", "truck", "train", "plane", "boat", "bike", "drum", "doll",
    "nose", "eye", "ear", "mouth", "hand", "foot", "hair", "toe", "head",
    "shoe", "sock", "hat", "coat",
    "up", "down", "go", "stop", "come", "sit", "run", "jump", "look", "see", "eat", "drink", "sleep", "play", "help", "done",
    "hot", "cold", "big", "wet", "good", "nice",
    "sun", "moon", "star", "tree", "rain", "light",
    "bath", "bed", "night",
    "one", "two", "three", "four", "five",
    "red", "blue", "green"
  ];

  var model = null, recognizer = null, ctx = null, node = null, source = null, stream = null;
  var port2 = null, busy = false, onText = null, onStatus = null, shown = 0;

  function status(s) { if (onStatus) { try { onStatus(s); } catch (e) {} } }

  function loadEngine() {
    return new Promise(function (res, rej) {
      if (window.Vosk) return res();
      var s = document.createElement("script");
      s.src = VOSK_SRC;
      s.onload = function () { res(); };
      s.onerror = function () { rej(new Error("couldn’t load the speech engine (offline?)")); };
      document.head.appendChild(s);
    });
  }

  function initModel() {
    if (model) return Promise.resolve();
    return loadEngine().then(function () {
      status("downloading voice model (~40 MB, first time only)…");
      return window.Vosk.createModel(MODEL_URL);
    }).then(function (m) {
      model = m;
      var channel = new MessageChannel();
      model.registerPort(channel.port1);
      port2 = channel.port2;
      // Grammar-constrained recognizer: only ever outputs words from WORDS, snapping the
      // child's sounds to the nearest one (low latency, toddler-appropriate).
      recognizer = new model.KaldiRecognizer(SAMPLE_RATE, JSON.stringify(WORDS));
      recognizer.setWords(true);
      // Real-time: emit words from PARTIAL results (mid-speech), not just the final result
      // (which only fires after the child pauses — that was the lag). Track how many words
      // we've already shown this utterance so we only fly the newly-added ones.
      recognizer.on("partialresult", function (msg) {
        var p = ((msg && msg.result && msg.result.partial) || "").trim();
        var words = p ? p.split(/\s+/) : [];
        if (words.length > shown) {
          for (var i = shown; i < words.length; i++) { if (onText) onText(words[i]); }
          shown = words.length;
        } else if (words.length < shown) {
          shown = words.length; // recognizer revised the guess down; resync, don't re-fly
        }
      });
      recognizer.on("result", function (msg) {
        // Utterance ended: fly anything the partials missed, then reset for the next one.
        var t = ((msg && msg.result && msg.result.text) || "").trim();
        var words = t ? t.split(/\s+/) : [];
        for (var i = shown; i < words.length; i++) { if (onText) onText(words[i]); }
        shown = 0;
      });
    });
  }

  function start(textCb, statusCb) {
    onText = textCb; onStatus = statusCb; shown = 0;
    if (busy) return; busy = true;
    Promise.resolve()
      .then(function () { status("starting…"); return initModel(); })
      .then(function () {
        status("asking for the microphone…");
        // Disable echo-cancel / noise-suppression / auto-gain: they add latency and would
        // chew up a toddler's raw babble. We want the rawest, lowest-latency capture.
        return navigator.mediaDevices.getUserMedia({
          video: false,
          audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1, sampleRate: SAMPLE_RATE }
        });
      })
      .then(function (s) {
        stream = s;
        if (ctx) return ctx.resume();
        ctx = new (window.AudioContext || window.webkitAudioContext)();
        return ctx.audioWorklet.addModule("recognizer-processor.js").then(function () {
          node = new AudioWorkletNode(ctx, "recognizer-processor", { channelCount: 1, numberOfInputs: 1, numberOfOutputs: 1 });
          // hand the recognizer port to the worklet (one-time transfer)
          node.port.postMessage({ action: "init", recognizerId: recognizer.id }, [port2]);
          node.connect(ctx.destination);
        });
      })
      .then(function () {
        source = ctx.createMediaStreamSource(stream);
        source.connect(node);
        status("listening — make some sounds! 🎤");
        busy = false;
      })
      .catch(function (e) {
        busy = false;
        status("couldn’t start: " + ((e && e.message) || e));
      });
  }

  function stop() {
    try { if (source) source.disconnect(); } catch (e) {}
    try { if (stream) stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {}
    try { if (ctx) ctx.suspend(); } catch (e) {}
    source = null; stream = null;
  }

  window.SoundPlay = { start: start, stop: stop };
})();
