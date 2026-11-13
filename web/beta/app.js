"use strict";

if (window.location.protocol === "https:") {
  const params = new URLSearchParams({ origin: window.location.origin });
  const invite = window.location.hash.slice(1);
  if (/^[a-f0-9]{32}$/.test(invite)) {
    params.set("invite", invite);
    document.getElementById("title").textContent = "Une invitation vous attend.";
    document.getElementById("intro").textContent = "Retrouvez votre ami sur Lettrine pour jouer à votre rythme.";
  }
  const button = document.getElementById("open");
  button.href = "lettrine://beta/v1/?" + params;
  button.hidden = false;
} else {
  document.getElementById("detail").textContent = "Ouvrez l’adresse HTTPS fournie pour la bêta.";
}
