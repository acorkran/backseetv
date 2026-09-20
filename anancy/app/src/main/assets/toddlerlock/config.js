/* Toddler Lock — launch config. EDIT THESE, then redeploy. Leave a value as "" to hide
 * that link. Nothing here affects the lock/play experience; it's just the home-screen
 * footer (the funnel: Windows app, the one-question survey, the tip jar, the repo). */
window.TL_CONFIG = window.TL_CONFIG || {
  // The native desktop app (the "walk-away whole-desktop lock" upgrade). Point at your
  // GitHub Releases page once you cut a release.
  winDownload: "",   // e.g. "https://github.com/YOURNAME/toddler-lock/releases/latest"

  // The ONE-QUESTION survey (your real demand signal). Make a free Tally or Google Form
  // with a single question: "Would you pay for more voices, languages, and animals?"
  survey: "",        // e.g. "https://tally.so/r/xxxxxx"

  // Tip jar. Lead with Ko-fi / Buy-Me-a-Coffee (parents trust it; crypto looks sketchy).
  kofi: "",          // e.g. "https://ko-fi.com/yourname"

  // Public source repo.
  github: "",        // e.g. "https://github.com/YOURNAME/toddler-lock"

  // Optional crypto tip address for the nerds (kept tiny, in the footer). Leave "" to hide.
  crypto: ""         // e.g. "bc1q... (BTC)"
};
