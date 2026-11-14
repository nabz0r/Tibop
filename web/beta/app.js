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
    document.getElementById("intro").textContent = english ? "Play a duel at your own pace in WordRivet."
      : "Retrouvez votre ami sur WordRivet pour jouer à votre rythme.";
  }
  const button = document.getElementById("open");
  if (english) {
    document.documentElement.lang = "en";
    document.title = "Android beta · WordRivet";
    document.querySelector(".eyebrow").textContent = "Android beta";
    document.getElementById("detail").textContent = "Install WordRivet and open this invitation again on Android.";
    document.getElementById("changes").textContent = "Drag tiles to reorder them on your rack. New classic solo or same-device games in French use the filtered dictionary.";
    const download = document.querySelector(".download a");
    download.textContent = "Download Android APK " + download.querySelector("span").textContent;
    button.textContent = "Open in WordRivet";
  }
  button.href = "lettrine://beta/v1/?" + params;
  button.hidden = false;
} else {
  document.getElementById("detail").textContent = "Ouvrez l’adresse HTTPS fournie pour la bêta.";
}
