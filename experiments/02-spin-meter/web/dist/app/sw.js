// Serve Spin Meter service worker: works offline after the first visit, and receives videos
// shared from other apps (Photos → Share → Spin Meter) through the manifest's share_target.
const VERSION = "55a2de0";
const SHELL = "shell-" + VERSION, LIBS = "libs-v1", SHARED = "shared";
const SHELL_FILES = ["./", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(SHELL_FILES)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k.startsWith("shell-") && k !== SHELL).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  // A shared video: keep it for the page, then open the page, which picks it up.
  if (e.request.method === "POST" && url.origin === location.origin && url.pathname.endsWith("/share")) {
    e.respondWith((async () => {
      try {
        const form = await e.request.formData();
        const file = form.get("video");
        if (file && file.size) {
          const c = await caches.open(SHARED);
          await c.put("shared-video", new Response(file, { headers: {
            "content-type": file.type || "video/mp4", "x-file-name": encodeURIComponent(file.name || "shared.mp4") } }));
        }
      } catch (err) { /* open the page anyway */ }
      return Response.redirect("./?shared=1", 303);
    })());
    return;
  }
  if (e.request.method !== "GET") return;
  if (url.origin === location.origin && url.pathname.endsWith("/version.json")) return;   // always from the network
  // The page itself: network first (revalidated, so a new build isn't held back by the HTTP
  // cache), cached copy when offline.
  if (e.request.mode === "navigate") {
    e.respondWith(fetch(e.request.url, { cache: "no-cache", credentials: "same-origin" }).then((r) => {
      const copy = r.clone(); caches.open(SHELL).then((c) => c.put("./", copy)); return r;
    }).catch(() => caches.match("./")));
    return;
  }
  // mp4box.js and fonts from their CDNs: cache first (versioned URLs).
  if (url.hostname === "cdn.jsdelivr.net" || url.hostname.endsWith("googleapis.com") || url.hostname.endsWith("gstatic.com")) {
    e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request).then((r) => {
      if (r.ok || r.type === "opaque") { const copy = r.clone(); caches.open(LIBS).then((c) => c.put(e.request, copy)); }
      return r;
    })));
    return;
  }
  if (url.origin === location.origin) e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request)));
});
