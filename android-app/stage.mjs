// Stage runtime files into android-app/www (Capacitor webDir). No docs, tools or tests.
// Layout mirrors the site: the game lives at /spiresofazeroth/; art not in the APK is fetched from thewowdb.com by MainActivity.
import { cpSync, rmSync, mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
const here = dirname(fileURLToPath(import.meta.url));
const game = resolve(here, "..", "spiresofazeroth");
const out = join(here, "www");
rmSync(out, { recursive: true, force: true });
const dest = join(out, "spiresofazeroth");
mkdirSync(dest, { recursive: true });
cpSync(join(game, "LICENSE"), join(dest, "LICENSE"));
for (const d of ["js", "css", "data", "assets"]) if (existsSync(join(game, d))) cpSync(join(game, d), join(dest, d), { recursive: true });
// Remote art is now proxied natively by MainActivity. Remove the service worker older builds registered (it only proxied root /assets/
// and depended on CORS), clear its cache, and reload once if it was still controlling this page.
const boot = `<script>if("serviceWorker"in navigator){navigator.serviceWorker.getRegistrations().then(function(rs){return Promise.all(rs.map(function(r){return r.unregister()}))}).then(function(){if(window.caches)caches.delete("soa-remote-v1");if(navigator.serviceWorker.controller&&!sessionStorage.getItem("sw-gone")){sessionStorage.setItem("sw-gone","1");location.reload()}}).catch(function(){})}</script>`;
const html = readFileSync(join(game, "index.html"), "utf8").replace(/<head>/i, "<head>" + boot);
writeFileSync(join(dest, "index.html"), html);
// offline runtime: three.js + wgf CSS/fonts the page loads from absolute /assets and /static paths (no network or service worker needed)
cpSync(join(here, "..", "offline"), out, { recursive: true });
// Capacitor html5mode serves www/index.html for ANY path whose last segment has no ".", so redirecting to "/spiresofazeroth/" loops forever (blank screen). Point at the explicit file.
writeFileSync(join(out, "index.html"), '<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=/spiresofazeroth/index.html"><a href="/spiresofazeroth/index.html">Spires of Azeroth</a>');
console.log("staged ->", out);
