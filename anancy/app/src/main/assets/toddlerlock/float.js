/* Floating quick-lock button. Lives in its own tiny transparent always-on-top window.
 * A tap calls the native `request_lock` command (brings the main window forward + locks).
 * Opacity is pushed from the main window's settings via the native side. */
(function () {
  "use strict";
  var TAURI = window.__TAURI__ && window.__TAURI__.core ? window.__TAURI__.core : null;
  var img = document.getElementById("img");
  var lock = document.getElementById("lock");

  function applyOpacity(pct) {
    var op = Math.max(0.2, Math.min(1, (parseInt(pct, 10) || 85) / 100));
    if (img) img.style.opacity = String(op);
  }

  if (TAURI) {
    try { TAURI.invoke("get_float_opacity").then(applyOpacity).catch(function () {}); } catch (e) {}
    if (window.__TAURI__.event && window.__TAURI__.event.listen) {
      window.__TAURI__.event.listen("float-opacity", function (ev) { applyOpacity(ev && ev.payload); });
    }
  }

  if (lock) lock.addEventListener("click", function () {
    if (TAURI) { try { TAURI.invoke("request_lock").catch(function () {}); } catch (e) {} }
  });
})();
