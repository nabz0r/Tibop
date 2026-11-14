"use strict";

if (window.location.protocol === "https:") {
  const params = new URLSearchParams({ origin: window.location.origin });
  const fragment = window.location.hash.slice(1);
  const english = fragment.startsWith("en/");
  const invite = english ? fragment.slice(3) : fragment;
  if (/^[a-f0-9]{32}$/.test(invite)) {
    params.set("invite", invite);
    if (english) params.set("lang", "en");
    document.getElementById("title").textContent = english ? "You’re invited." : "Une invitation vous attend.";
    document.getElementById("intro").textContent = english ? "Play a duel at your own pace in Tibop."
      : "Retrouvez votre ami dans Tibop pour jouer à votre rythme.";
  }
  const button = document.getElementById("open");
  if (english) {
    document.documentElement.lang = "en";
    document.title = "Android beta · Tibop";
    if (!params.has("invite")) {
      document.getElementById("title").textContent = "Android beta";
      document.getElementById("intro").textContent = "Play a classic word game against NAB, Tibop’s Maine Coon, or challenge a friend.";
    }
    document.getElementById("detail").textContent = "Install Tibop on Android 8 or later, then return here to connect to the beta.";
    document.getElementById("download-label").textContent = "Download the APK";
    button.textContent = "Open in Tibop";
  }
  button.href = "lettrine://beta/v1/?" + params;
  button.hidden = false;
} else {
  document.getElementById("detail").textContent = "Ouvrez l’adresse HTTPS fournie pour la bêta.";
}
