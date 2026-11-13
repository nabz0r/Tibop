"use strict";

const byId = (id) => document.getElementById(id);
const local = ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname);
const basePath = "/salons.html";
const { premiums, tilePoints } = lettrineBoard;
const bonusLabels = { tw: ["M3", "mot triple"], dw: ["M2", "mot double"], tl: ["L3", "lettre triple"], dl: ["L2", "lettre double"] };
const sections = ["welcome", "invited", "room"];
const forms = ["create-form", "join-form", "invite-form"].map(byId);
const botLevels = {
  discovery: ["Découverte", "Pour apprendre à votre rythme."],
  initiated: ["Initié", "Pour les joueurs qui connaissent déjà les règles."],
  strategist: ["Stratège", "Pour travailler vos placements et vos cases bonus."],
  expert: ["Expert", "Pour affronter le niveau fixe le plus difficile."],
  adaptive: ["N A B · Adaptatif", "Sa difficulté s’ajuste selon l’écart de score pendant la partie."],
};
const inMemory = new Map();
const draft = new Map();
const swaps = new Set();
let preview = null;
let hint = null;
let rackOrder = [];
let drag = null;
let suppressedPointer = null;
let selected = null;
let mode = "play";
let room = null;
let token = null;
let code = "";
let inviteOrigin = location.origin;
let timer;
let generation = 0;
let focusedCell = 112;
let waiting = false;

function element(tag, className, text = "") {
  const result = document.createElement(tag);
  result.className = className;
  result.textContent = text;
  return result;
}

function output(id, text) {
  const target = byId(id);
  if (target.textContent !== text) target.textContent = text;
}

function notice(id, text = "") {
  output(id, text);
  byId(id).hidden = !text;
}

function storageKey(name) {
  return `lettrine.rooms.${name}`;
}

function storage(name) {
  return name.startsWith("pending.") ? sessionStorage : localStorage;
}

function save(name, value) {
  inMemory.set(name, value);
  try { storage(name).setItem(storageKey(name), value); } catch { /* Continue in this tab. */ }
}

function read(name) {
  if (inMemory.has(name)) return inMemory.get(name);
  try { return storage(name).getItem(storageKey(name)); } catch { return null; }
}

function forget(name) {
  inMemory.delete(name);
  try { storage(name).removeItem(storageKey(name)); } catch { /* Storage is unavailable. */ }
}

function roomCode(value) {
  return value.replace(/[\s-]/g, "").toUpperCase();
}

function validCode(value) {
  return /^[0-9A-F]{16}$/.test(value);
}

function roomUrl(value, origin = location.origin) {
  const url = new URL(basePath, origin);
  url.searchParams.set("room", value);
  return url;
}

function show(section) {
  for (const name of sections) byId(name).hidden = name !== section;
  byId("rooms-link").hidden = section === "welcome";
  byId("studio-link").hidden = !local || section !== "welcome";
}

function updateInvite() {
  if (!room) return;
  byId("invite-url").value = roomUrl(room.code, inviteOrigin).href;
  output("invite-help", local && inviteOrigin === location.origin
    ? "Ce lien s’ouvre sur ce Mac. Ouvrez le lien réseau du studio pour inviter des amis sur le même Wi-Fi."
    : "Envoyez ce lien à vos amis connectés au même Wi-Fi.");
}

async function discoverInviteOrigin() {
  if (!local) return;
  try {
    const response = await fetch("/salons-network.json", { cache: "no-store" });
    if (!response.ok) return;
    const { origin } = await response.json();
    const url = new URL(origin);
    if (!["http:", "https:"].includes(url.protocol)) return;
    inviteOrigin = url.origin;
    updateInvite();
  } catch { /* Guests can still join in another browser on this Mac. */ }
}

async function request(path, { method = "GET", body, authorization, signal } = {}) {
  const headers = { Accept: "application/json" };
  if (authorization) headers.Authorization = `Bearer ${authorization}`;
  if (body) headers["Content-Type"] = "application/json";
  const response = await fetch(`/api/rooms${path}`, {
    method, headers, body: body ? JSON.stringify(body) : undefined,
    cache: "no-store", signal: signal || AbortSignal.timeout(7000),
  });
  const data = await response.json().catch(() => null);
  if (!response.ok || !data) {
    const error = new Error(data?.error || "Le salon est momentanément indisponible. Réessayez dans un instant.");
    error.status = response.status;
    error.code = data?.code;
    throw error;
  }
  return data;
}

function errorMessage(error) {
  return error.status ? error.message : "La connexion a été interrompue. Réessayez dans un instant.";
}

function setupToken(operation, fingerprint) {
  const key = `pending.${operation}`;
  const previous = JSON.parse(read(key) || "null");
  if (previous?.fingerprint === fingerprint) return previous.token;
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const value = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  save(key, JSON.stringify({ fingerprint, token: value }));
  return value;
}

function lockForms(locked) {
  for (const form of forms) {
    for (const input of form.elements) input.disabled = locked;
  }
  if (!locked) updateSetupMode();
}

function fillBotLevels(select, value = "discovery") {
  select.replaceChildren(...Object.entries(botLevels).map(([key, [label]]) => new Option(label, key)));
  select.value = value;
}

function showSoloHelp() {
  const level = botLevels[byId("solo-level").value] ?? botLevels.discovery;
  output("bot-help", level[1]);
}

function friendCapacity() {
  return Number(byId("create-form").querySelector('[name="capacity"]:checked').value);
}

function updateFriendSeats() {
  const capacity = friendCapacity();
  const count = Number(byId("bots-count").value);
  const levels = byId("bot-levels");
  const selected = Array.from(levels.querySelectorAll("select"), (select) => select.value);
  const labels = Array.from({ length: count }, (_, index) => {
    const label = element("label", "bot-level");
    const title = element("span", "", `Niveau du bot ${index + 1}`);
    const select = document.createElement("select");
    select.name = "bot-level";
    fillBotLevels(select, selected[index]);
    label.append(title, select);
    return label;
  });
  levels.replaceChildren(...labels);
  const invitations = capacity - count - 1;
  output("friend-seats", invitations === 1 ? "1 ami à inviter." : `${invitations} amis à inviter.`);
}

function updateBotCount() {
  const maximum = friendCapacity() - 2;
  const count = byId("bots-count");
  const previous = Math.min(Number(count.value), maximum);
  count.replaceChildren(...Array.from({ length: maximum + 1 }, (_, index) => new Option(
    index ? `${index} bot${index === 1 ? "" : "s"}` : "Aucun bot",
    index,
  )));
  count.value = previous;
  byId("bot-setup").hidden = maximum === 0;
  updateFriendSeats();
}

function updateSetupMode() {
  const solo = byId("create-form").elements.opponent.value === "solo";
  byId("solo-options").hidden = !solo;
  byId("solo-options").disabled = !solo;
  byId("friends-options").hidden = solo;
  byId("friends-options").disabled = solo;
  byId("create-name").required = !solo;
  byId("create-name-label").querySelector("span").hidden = !solo;
  output("create-label", solo ? "Jouer contre un bot" : "Créer le salon");
}

async function enter(event, invitedCode) {
  event.preventDefault();
  const form = event.currentTarget;
  const fields = new FormData(form);
  const create = form.id === "create-form";
  const solo = create && fields.get("opponent") === "solo";
  const name = String(fields.get("name") || "").trim() || (solo ? "Moi" : "");
  const capacity = solo ? 2 : Number(fields.get("capacity"));
  const bots = solo ? [fields.get("solo-level")] : fields.getAll("bot-level");
  const target = invitedCode || roomCode(String(fields.get("code") || ""));
  if (!name) return notice("error", "Entrez votre prénom ou un pseudo.");
  if (!create && !validCode(target)) return notice("error", "Vérifiez le code de 16 caractères dans votre invitation.");
  if (!create && read(`seat.${target}`)) {
    location.assign(roomUrl(target));
    return;
  }
  const operation = create ? "create" : "join";
  const pending = setupToken(operation, JSON.stringify([name, create ? capacity : target, bots]));
  notice("error");
  lockForms(true);
  try {
    const result = await request(create ? "" : `/${target}/join`, {
      method: "POST", body: { name, ...(create ? { capacity, bots } : {}), token: pending },
    });
    forget(`pending.${operation}`);
    token = result.token;
    code = result.room.code;
    save(`seat.${code}`, token);
    save("last", code);
    history.replaceState(null, "", roomUrl(code));
    applyRoom(result.room);
    if (solo && isSolo() && !room.game) await commit("start", {});
    else schedulePoll();
  } catch (error) {
    if (error.status === 404) notice("error", "Ce salon est introuvable. Vérifiez le lien auprès de votre ami.");
    else notice("error", errorMessage(error));
  } finally {
    lockForms(false);
  }
}

function schedulePoll(delay = document.hidden ? 4000 : 1100) {
  clearTimeout(timer);
  if (token && room?.game?.status !== "finished") timer = setTimeout(poll, delay);
}

async function poll() {
  if (!token || waiting) return schedulePoll();
  const currentGeneration = generation;
  try {
    const snapshot = await request(`/${code}`, { authorization: token });
    if (generation !== currentGeneration) return;
    notice("connection");
    applyRoom(snapshot);
  } catch (error) {
    if (generation !== currentGeneration) return;
    if ([401, 404].includes(error.status)) {
      sessionEnded(error.status);
    } else if (room) {
      notice("connection", "Connexion interrompue. Reconnexion en cours…");
    } else {
      notice("loading", "Nous essayons de retrouver votre salon. Vérifiez votre connexion.");
    }
  } finally {
    if (generation === currentGeneration) schedulePoll();
  }
}

function sessionEnded(status) {
  forget(`seat.${code}`);
  if (read("last") === code) forget("last");
  token = null;
  room = null;
  clearDrag();
  resetDraft();
  clearPreview();
  clearHint();
  rackOrder = [];
  notice("loading");
  generation += 1;
  if (status === 401) {
    show("invited");
    notice("error", "Votre place n’a pas pu être retrouvée. Vous pouvez rejoindre un salon qui attend encore des joueurs.");
  } else {
    show("welcome");
    byId("resume").hidden = true;
    history.replaceState(null, "", basePath);
    notice("error", "Ce salon est introuvable. Vous pouvez en créer un autre.");
  }
}

function playerName(seat) {
  return room.players.find((player) => player.seat === seat)?.name || "Joueur";
}

function isSolo() {
  return room.players.filter((player) => player.bot).length === room.capacity - 1;
}

function yourTurn() {
  return room?.game?.status === "active" && room.game.turn === room.seat;
}

function resetDraft() {
  if (drag?.kind === "board") clearDrag();
  draft.clear();
  swaps.clear();
  selected = null;
  mode = "play";
  if (byId("blank-dialog").open) byId("blank-dialog").close();
}

function rackChanged(prior, current) {
  if (!prior?.game || prior.code !== current.code || prior.seat !== current.seat) return true;
  const before = prior.game;
  const after = current.game;
  const moved = after.last_move;
  if (after.rack_revision !== undefined) {
    if (before.rack_revision !== after.rack_revision) return true;
  } else if (before.turn_number !== after.turn_number && moved?.seat === current.seat && ["play", "exchange"].includes(moved.type)) {
    return true;
  }
  return before.rack.length !== after.rack.length || after.rack.some((tile, index) => {
    const previous = before.rack[index];
    return tile.letter !== previous.letter || tile.blank !== previous.blank || tile.value !== previous.value;
  });
}

function applyRoom(snapshot) {
  if (room && snapshot.revision <= room.revision) return;
  clearHint();
  const prior = room;
  room = snapshot;
  if (room.game && rackChanged(prior, room)) {
    clearDrag();
    rackOrder = room.game.rack.map((_, index) => index);
    output("rack-announcement", "");
  }
  if (prior?.game?.turn_number !== room.game?.turn_number || prior?.game?.status !== room.game?.status) {
    if (room.game?.status === "finished") clearDrag();
    resetDraft();
    notice("play-error");
  }
  notice("error");
  notice("loading");
  show("room");
  const game = room.game;
  byId("room").dataset.phase = game ? "game" : "lobby";
  byId("lobby").hidden = !!game;
  byId("game").hidden = !game;
  const solo = isSolo();
  output("room-title", solo ? "Partie contre un bot" : game ? "Partie entre amis" : "Votre salon");
  output("room-subtitle", solo ? `Votre adversaire : ${playerName(room.players.find((player) => player.bot).seat)}.` : `Votre pseudo : ${playerName(room.seat)}.`);
  output("room-label", `${room.capacity} joueurs`);
  if (game) renderGame();
  else renderLobby();
}

function renderLobby() {
  const host = room.host === room.seat;
  const ready = room.players.length === room.capacity;
  const solo = isSolo();
  byId("invite-panel").hidden = solo;
  byId("lobby").classList.toggle("solo-lobby", solo);
  if (!solo) {
    updateInvite();
    output("room-code", room.code.match(/.{1,4}/g).join(" "));
  }
  const seats = Array.from({ length: room.capacity }, (_, index) => {
    const player = room.players.find((member) => member.seat === index);
    if (!player) return element("li", "empty-seat", "Place libre");
    const row = element("li", "");
    const tags = [player.seat === room.seat ? "Vous" : "", player.seat === room.host && !solo ? "Hôte" : "", player.bot ? "Bot" : ""].filter(Boolean);
    row.append(element("span", "player-name", player.name), element("span", "player-note", tags.join(" · ")));
    return row;
  });
  byId("lobby-players").replaceChildren(...seats);
  const missing = room.capacity - room.players.length;
  output("lobby-status", solo ? "Votre adversaire est prêt." : ready
    ? (host ? "Tout le monde est là. Vous pouvez lancer la partie." : "Tout le monde est là. L’hôte va lancer la partie.")
    : (missing === 1 ? "En attente d’un autre joueur." : `En attente de ${missing} autres joueurs.`));
  byId("start-game").hidden = !host;
  byId("start-game").disabled = !ready || waiting;
}

async function copyInvitation() {
  const input = byId("invite-url");
  let copied = false;
  try {
    await navigator.clipboard.writeText(input.value);
    copied = true;
  } catch {
    input.focus();
    input.select();
    input.setSelectionRange(0, input.value.length);
    try { copied = document.execCommand("copy"); } catch { /* The selected text remains copyable. */ }
  }
  notice("copy-feedback", copied ? "Lien copié." : "Le lien est sélectionné. Copiez-le puis envoyez-le à vos amis.");
}

const cells = Array.from({ length: 225 }, (_, index) => {
  const square = element("button", `board-cell ${premiums.get(index) || ""}`);
  square.type = "button";
  square.tabIndex = index === focusedCell ? 0 : -1;
  square.addEventListener("focus", () => {
    cells[focusedCell].tabIndex = -1;
    focusedCell = index;
    square.tabIndex = 0;
  });
  square.addEventListener("click", () => useSquare(index));
  square.addEventListener("pointerdown", (event) => beginDrag(event, "board", index));
  return square;
});
byId("game-board").append(...cells);
byId("game-board").addEventListener("keydown", (event) => {
  const shifts = { ArrowUp: -15, ArrowDown: 15, ArrowLeft: -1, ArrowRight: 1 };
  const shift = shifts[event.key];
  if (!shift) return;
  event.preventDefault();
  const index = focusedCell + shift;
  if (!cells[index]) return;
  if (Math.abs(shift) === 1 && Math.floor(index / 15) !== Math.floor(focusedCell / 15)) return;
  cells[index].focus();
});

const rackButtons = Array.from({ length: 7 }, (_, slot) => {
  const button = element("button", "game-tile");
  button.type = "button";
  button.addEventListener("click", () => useRackTile(rackOrder[slot]));
  button.addEventListener("pointerdown", (event) => beginDrag(event, "rack", rackOrder[slot]));
  button.addEventListener("keydown", (event) => {
    if (!event.altKey || !["ArrowLeft", "ArrowRight"].includes(event.key) || !canArrangeRack()) return;
    const target = slot + (event.key === "ArrowLeft" ? -1 : 1);
    if (target < 0 || target >= rackOrder.length) return;
    event.preventDefault();
    reorderRack(rackOrder[slot], target);
    rackButtons[target].focus();
  });
  return button;
});
byId("rack").append(...rackButtons);
for (const letter of Object.keys(tilePoints)) {
  const button = element("button", "game-tile", letter);
  button.type = "button";
  button.addEventListener("click", () => {
    const { square } = byId("blank-dialog").dataset;
    byId("blank-dialog").close();
    putTile(Number(square), letter);
  });
  byId("blank-letters").append(button);
}

function pointLabel(value) {
  return `${value} point${value === 1 ? "" : "s"}`;
}

function canArrangeRack() {
  return room?.game?.status === "active" && !waiting;
}

function canPlaceTile() {
  return yourTurn() && !waiting && mode === "play";
}

function isDrafted(index) {
  return [...draft.values()].some(({ tile }) => tile === index);
}

function reorderRack(tile, target) {
  const source = rackOrder.indexOf(tile);
  if (source < 0 || source === target) return;
  rackOrder.splice(source, 1);
  rackOrder.splice(target, 0, tile);
  renderDraft();
  output("rack-announcement", `Lettre déplacée en position ${target + 1}.`);
}

function shuffleRack() {
  if (!canArrangeRack() || rackOrder.length < 2) return;
  clearDrag();
  const previous = [...rackOrder];
  for (let index = rackOrder.length - 1; index > 0; index -= 1) {
    const other = Math.floor(Math.random() * (index + 1));
    [rackOrder[index], rackOrder[other]] = [rackOrder[other], rackOrder[index]];
  }
  if (rackOrder.every((tile, index) => tile === previous[index])) rackOrder.push(rackOrder.shift());
  renderDraft();
  output("rack-announcement", "Lettres mélangées.");
}

function beginDrag(event, kind, index) {
  if (!event.isPrimary || event.button !== 0 || drag) return;
  const tile = kind === "rack" ? room?.game?.rack[index] : draft.get(index);
  if (!tile || (kind === "rack" ? !canArrangeRack() || isDrafted(index) : !canPlaceTile())) return;
  drag = {
    pointer: event.pointerId, kind, index, tile, source: event.currentTarget,
    x: event.clientX, y: event.clientY, ghost: null, target: null,
  };
}

function dropTarget(event, source) {
  const hit = document.elementFromPoint(event.clientX, event.clientY);
  if (!hit) return null;
  const cell = hit.closest(".board-cell");
  if (cell && canPlaceTile() && cell.getAttribute("aria-disabled") === "false") {
    const index = cells.indexOf(cell);
    if (index >= 0 && !draft.has(index)) return { element: cell, index, kind: "board" };
  }
  if (canArrangeRack() && byId("rack").contains(hit)) {
    const tile = hit.closest("button");
    const slot = rackButtons.indexOf(tile);
    return { element: slot < 0 ? byId("rack") : tile, index: slot, kind: "rack" };
  }
  if (source.kind === "board" && canPlaceTile() && byId("hand-title").closest(".hand").contains(hit)) {
    return { element: byId("rack"), index: -1, kind: "rack" };
  }
  return null;
}

function highlightDrop(target, highlighted) {
  if (!target) return;
  target.element.classList.toggle(target.kind === "rack" && target.index < 0 ? "rack-drop-target" : "drop-target", highlighted);
}

function moveDrag(event) {
  if (!drag || event.pointerId !== drag.pointer) return;
  if (!drag.ghost) {
    if (Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 6) return;
    const { letter, blank } = drag.tile;
    drag.ghost = element("span", "game-tile drag-ghost", letter);
    drag.ghost.append(element("small", "", blank ? 0 : tilePoints[letter]));
    drag.ghost.setAttribute("aria-hidden", "true");
    document.body.append(drag.ghost);
    document.body.classList.add("is-dragging");
    drag.source.classList.add("drag-source");
    drag.source.setPointerCapture(event.pointerId);
  }
  event.preventDefault();
  drag.ghost.style.left = `${event.clientX}px`;
  drag.ghost.style.top = `${event.clientY}px`;
  highlightDrop(drag.target, false);
  drag.target = dropTarget(event, drag);
  highlightDrop(drag.target, true);
}

function clearDrag() {
  if (!drag) return;
  const previous = drag;
  drag = null;
  highlightDrop(previous.target, false);
  previous.source.classList.remove("drag-source");
  previous.ghost?.remove();
  document.body.classList.remove("is-dragging");
  if (previous.source.hasPointerCapture(previous.pointer)) previous.source.releasePointerCapture(previous.pointer);
  if (previous.ghost) suppressedPointer = previous.pointer;
}

function endDrag(event) {
  if (!drag || event.pointerId !== drag.pointer) return;
  const previous = drag;
  const target = previous.ghost && event.type === "pointerup" ? dropTarget(event, previous) : null;
  clearDrag();
  if (!previous.ghost) return;
  if (!target) return;
  if (target.kind === "board") {
    if (previous.kind === "rack") {
      selected = previous.index;
      placeSelected(target.index);
    } else {
      draft.delete(previous.index);
      const placement = { ...previous.tile, x: target.index % 15, y: Math.floor(target.index / 15) };
      draft.set(target.index, placement);
      notice("play-error");
      renderDraft();
      output("rack-announcement", "Lettre déplacée sur le plateau.");
    }
    return;
  }
  if (previous.kind === "board") {
    draft.delete(previous.index);
    notice("play-error");
    renderDraft();
    output("rack-announcement", "Lettre reprise.");
    if (target.index >= 0) reorderRack(previous.tile.tile, target.index);
  } else if (target.index >= 0) {
    reorderRack(previous.index, target.index);
  } else {
    const before = rackButtons.findIndex((button) => !button.hidden && event.clientX < button.getBoundingClientRect().left);
    const from = rackOrder.indexOf(previous.index);
    const slot = before < 0 ? rackOrder.length - 1 : before - Number(from < before);
    reorderRack(previous.index, slot);
  }
}

document.addEventListener("pointermove", moveDrag, { passive: false });
document.addEventListener("pointerup", endDrag);
document.addEventListener("pointercancel", endDrag);
document.addEventListener("lostpointercapture", endDrag);
document.addEventListener("pointerdown", () => { suppressedPointer = null; }, true);
window.addEventListener("blur", clearDrag);
document.addEventListener("click", (event) => {
  if (suppressedPointer === null || !event.detail || (event.pointerId > 0 && event.pointerId !== suppressedPointer)) return;
  suppressedPointer = null;
  event.preventDefault();
  event.stopImmediatePropagation();
}, true);

function useRackTile(index) {
  if (!yourTurn() || waiting || mode === "pass" || index === undefined || isDrafted(index)) return;
  if (mode === "swap") {
    if (swaps.has(index)) swaps.delete(index);
    else swaps.add(index);
  } else if (mode === "play") {
    selected = selected === index ? null : index;
  }
  notice("play-error");
  renderDraft();
  if (mode === "play" && selected !== null) cells[focusedCell].focus({ preventScroll: true });
}

function useSquare(index) {
  if (!yourTurn() || waiting || mode !== "play") return;
  if (draft.has(index)) {
    draft.delete(index);
    notice("play-error");
    renderDraft();
    return;
  }
  if (room.game.board.some(({ x, y }) => y * 15 + x === index)) return;
  if (selected === null) {
    notice("play-error", "Choisissez d’abord une de vos lettres.");
    return;
  }
  placeSelected(index);
}

function placeSelected(index) {
  if (selected === null || isDrafted(selected)) return;
  if (room.game.rack[selected].blank) {
    const dialog = byId("blank-dialog");
    dialog.dataset.square = index;
    dialog.showModal();
    return;
  }
  putTile(index);
}

function putTile(index, letter) {
  if (!canPlaceTile() || selected === null || isDrafted(selected)) return;
  const { blank, letter: original } = room.game.rack[selected];
  draft.set(index, { x: index % 15, y: Math.floor(index / 15), tile: selected, letter: letter || original, blank });
  selected = null;
  notice("play-error");
  renderDraft();
  const next = rackButtons.find((button) => !button.hidden && !button.disabled);
  next?.focus({ preventScroll: true });
}

function renderBoard() {
  const game = room.game;
  const coordinates = (tile) => tile.y * 15 + tile.x;
  const board = new Map(game.board.map((tile) => [coordinates(tile), tile]));
  const last = new Set((game.last_move?.tiles || []).map(coordinates));
  const move = currentHint();
  const suggested = new Map((move?.placements || []).map((tile) => [coordinates(tile), tile]));
  const anchors = new Set((move?.anchors || []).map(coordinates));
  for (const [index, cell] of cells.entries()) {
    const tile = draft.get(index) || board.get(index);
    const suggestion = suggested.get(index);
    const anchor = anchors.has(index);
    const suggestedLetter = suggestion && (suggestion.letter || game.rack[suggestion.tile].letter);
    const bonus = bonusLabels[premiums.get(index)];
    const center = index === 112;
    const coordinate = cellName(index % 15, Math.floor(index / 15));
    cell.classList.toggle("occupied", !!tile);
    cell.classList.toggle("draft", draft.has(index));
    cell.classList.toggle("last", last.has(index));
    cell.classList.toggle("blank", !!tile?.blank);
    cell.classList.toggle("hint-cell", !!suggestion);
    cell.classList.toggle("hint-anchor", anchor);
    cell.classList.toggle("hint-conflict", !!suggestion && !!tile && suggestedLetter !== tile.letter);
    cell.setAttribute("aria-disabled", String(!yourTurn() || waiting || mode !== "play" || board.has(index)));
    if (tile) {
      const value = tile.blank ? 0 : tilePoints[tile.letter];
      const hintLabel = suggestion ? `, indice ${suggestedLetter}` : anchor ? ", lettre utilisée par l’indice" : "";
      cell.setAttribute("aria-label", `${coordinate}, ${tile.blank ? "joker " : ""}${tile.letter}, ${pointLabel(value)}${draft.has(index) ? ", à jouer" : ""}${hintLabel}`);
      cell.replaceChildren(document.createTextNode(tile.letter), element("small", "", value));
      if (suggestion && suggestedLetter !== tile.letter) cell.append(element("span", "hint-letter hint-letter-badge", suggestedLetter));
    } else if (suggestion) {
      cell.setAttribute("aria-label", `${coordinate}, indice ${suggestedLetter}`);
      cell.replaceChildren(element("span", "hint-letter", suggestedLetter));
    } else {
      cell.setAttribute("aria-label", `${coordinate}, ${center ? "étoile, mot double" : bonus?.[1] || "vide"}`);
      cell.replaceChildren(element("span", "bonus", center ? "★" : bonus?.[0] || ""));
    }
  }
}

function cellName(x, y) {
  return `${String.fromCharCode(65 + x)}${y + 1}`;
}

function clearHint() {
  hint?.controller.abort();
  hint = null;
}

function currentHint() {
  return hint?.moves?.[hint.index];
}

function hintDetail(move) {
  const direction = { horizontal: "En ligne", vertical: "En colonne" }[move.direction];
  const squares = move.placements.map(({ x, y }) => cellName(x, y)).join(", ");
  const anchor = move.anchors?.length ? " Le cadre bleu montre les lettres déjà posées qui font partie du mot." : "";
  const replacement = draft.size ? " Poser les lettres remplacera votre brouillon." : "";
  return `${direction ? `${direction} · ` : ""}Cases dorées ${squares}.${anchor}${replacement}`;
}

function renderHint() {
  const button = byId("hint-button");
  const panel = byId("hint-result");
  button.hidden = !yourTurn() || mode !== "play";
  button.disabled = waiting || hint?.state === "pending";
  button.setAttribute("aria-expanded", String(!!hint));
  panel.hidden = !hint;
  if (!hint) return;
  const { state, index, moves } = hint;
  const move = currentHint();
  panel.dataset.state = state;
  const [label, detail] = {
    pending: ["Recherche d’un mot…", ""],
    ready: [move?.word, move ? hintDetail(move) : ""],
    none: ["Aucun mot trouvé", room.game.remaining >= 7 ? "Vous pouvez échanger des lettres ou passer votre tour." : "Vous pouvez passer votre tour."],
    error: ["Indice indisponible", "Réessayez dans un instant."],
  }[state];
  output("hint-word", label);
  output("hint-detail", detail);
  byId("hint-detail").hidden = !detail;
  output("hint-score", move ? pointLabel(move.score) : "");
  byId("hint-score").hidden = !move;
  byId("apply-hint").hidden = state !== "ready";
  byId("hint-nav").hidden = state !== "ready" || moves.length < 2;
  output("hint-count", state === "ready" ? `${index + 1} sur ${moves.length}` : "");
}

async function requestHint() {
  if (!canPlaceTile() || hint?.state === "pending") return;
  if (hint?.state === "ready" || hint?.state === "none") {
    dismissHint();
    return;
  }
  const current = { state: "pending", moves: [], index: 0, controller: new AbortController() };
  hint = current;
  renderHint();
  const timeout = setTimeout(() => current.controller.abort(), 4000);
  try {
    const result = await request(`/${code}/hint`, {
      method: "POST", body: { revision: room.revision }, authorization: token, signal: current.controller.signal,
    });
    if (hint !== current) return;
    if (result.hint) current.moves = [result.hint, ...(result.alternatives || [])];
    current.state = current.moves.length ? "ready" : "none";
  } catch (error) {
    if (hint !== current) return;
    current.state = "error";
    if (error.status === 409) schedulePoll(0);
  } finally {
    clearTimeout(timeout);
    if (hint === current) renderDraft();
  }
}

function nextHint() {
  if (!canPlaceTile() || hint?.state !== "ready" || hint.moves.length < 2) return;
  hint.index = (hint.index + 1) % hint.moves.length;
  renderDraft();
}

function dismissHint() {
  clearHint();
  renderDraft();
  byId("hint-button").focus({ preventScroll: true });
}

function placeHint() {
  const move = currentHint();
  if (!canPlaceTile() || !move) return;
  clearDrag();
  resetDraft();
  for (const { x, y, tile, letter } of move.placements) {
    const rackTile = room.game.rack[tile];
    draft.set(y * 15 + x, { x, y, tile, letter: letter || rackTile.letter, blank: rackTile.blank });
  }
  clearHint();
  notice("play-error");
  renderDraft();
  cells[draft.keys().next().value].focus({ preventScroll: true });
}

function draftPlacements() {
  return Array.from(draft.values(), ({ x, y, tile, blank, letter }) => ({ x, y, tile, ...(blank ? { letter } : {}) }));
}

function renderPreview() {
  const state = preview?.state;
  const panel = byId("move-preview");
  panel.hidden = !state;
  byId("play-move").disabled = waiting || !["valid", "unavailable"].includes(state);
  if (!state) return;
  panel.dataset.state = state;
  const { result } = preview;
  const [mark, label, detail] = {
    pending: ["…", "Vérification…", ""],
    valid: ["✓", "Coup valide", result?.words?.join(" · ") || ""],
    invalid: ["!", "Coup invalide", result?.reason || "Vérifiez les lettres et leur placement."],
    unavailable: ["!", "Aperçu indisponible", "Le coup sera vérifié au moment de jouer."],
  }[state];
  output("move-preview-mark", mark);
  output("move-preview-label", label);
  output("move-preview-detail", detail);
  byId("move-preview-detail").hidden = !detail;
  output("move-preview-score", state === "valid" ? pointLabel(result.score) : "");
  byId("move-preview-score").hidden = state !== "valid";
}

function clearPreview() {
  if (!preview) return;
  clearTimeout(preview.timer);
  preview.controller.abort();
  preview = null;
  renderPreview();
}

function syncPreview() {
  if (!yourTurn() || mode !== "play" || !draft.size) {
    clearPreview();
    renderPreview();
    return;
  }
  const body = { revision: room.revision, placements: draftPlacements() };
  const key = JSON.stringify(body);
  if (preview?.key !== key) {
    clearPreview();
    const current = { key, state: "pending", controller: new AbortController() };
    preview = current;
    current.timer = setTimeout(() => checkPreview(current, body), 90);
  }
  renderPreview();
}

async function checkPreview(current, body) {
  const timeout = setTimeout(() => current.controller.abort(), 3500);
  try {
    const result = await request(`/${code}/preview`, {
      method: "POST", body, authorization: token, signal: current.controller.signal,
    });
    if (preview !== current) return;
    current.result = result;
    current.state = result.valid === false ? "invalid" :
      result.valid === true && Number.isFinite(result.score) ? "valid" : "unavailable";
  } catch (error) {
    if (preview !== current) return;
    current.state = "unavailable";
    if (error.status === 409) schedulePoll(0);
  } finally {
    clearTimeout(timeout);
    if (preview === current) renderPreview();
  }
}

function renderDraft() {
  const game = room.game;
  const active = yourTurn();
  const placed = new Set(Array.from(draft.values(), ({ tile }) => tile));
  const suggested = new Set((currentHint()?.placements || []).map(({ tile }) => tile));
  renderBoard();
  for (const [slot, button] of rackButtons.entries()) {
    const index = rackOrder[slot];
    const tile = game.rack[index];
    const used = placed.has(index);
    button.hidden = !tile;
    if (!tile) {
      delete button.dataset.tile;
      continue;
    }
    button.dataset.tile = index;
    button.classList.toggle("used", used);
    button.classList.toggle("hint-rack", suggested.has(index));
    button.disabled = !canArrangeRack() || used;
    button.setAttribute("aria-keyshortcuts", "Alt+ArrowLeft Alt+ArrowRight");
    button.setAttribute("aria-pressed", String(mode === "swap" ? swaps.has(index) : selected === index));
    button.setAttribute("aria-label", `${tile.blank ? "Joker" : tile.letter}, ${pointLabel(tile.value)}${used ? ", sur le plateau" : ""}`);
    button.replaceChildren(document.createTextNode(tile.letter), element("small", "", tile.value));
  }
  byId("shuffle-rack").disabled = !canArrangeRack() || rackOrder.length < 2;
  byId("play-actions").hidden = !active || mode !== "play";
  byId("other-actions").hidden = !active || mode === "play";
  renderHint();
  syncPreview();
  byId("clear-move").disabled = waiting || (!draft.size && selected === null);
  byId("swap-mode").disabled = waiting || game.remaining < 7;
  byId("pass-mode").disabled = waiting;
  byId("cancel-action").disabled = waiting;
  byId("confirm-action").disabled = waiting || (mode === "swap" && !swaps.size);
  if (mode === "swap") output("confirm-action", "Confirmer l’échange");
  if (mode === "pass") output("confirm-action", "Confirmer que je passe");
  let tip = "Vos lettres restent visibles pour vous seul.";
  if (active) {
    if (mode === "swap") tip = "Touchez les lettres à échanger. L’échange passe le tour au joueur suivant.";
    else if (mode === "pass") tip = "Confirmez pour donner la main au joueur suivant.";
    else if (selected !== null) tip = "Touchez la case où vous voulez poser cette lettre.";
    else if (draft.size) tip = "Déplacez vos lettres pour ajuster votre coup.";
    else if (!game.board.length) tip = "Glissez une lettre ou sélectionnez-la puis touchez une case. Le premier mot passe par l’étoile.";
    else tip = "Glissez une lettre ou sélectionnez-la puis touchez une case. Rattachez votre mot aux lettres du plateau.";
  } else if (game.status === "finished" && !game.rack.length) {
    tip = "Vous n’avez plus de lettres.";
  }
  output("hand-tip", tip);
}

function renderGame() {
  const game = room.game;
  const yours = yourTurn();
  const finished = game.status === "finished";
  const active = room.players.find((player) => player.seat === game.turn);
  const solo = isSolo();
  byId("turn-banner").dataset.turn = yours ? "yours" : "other";
  const winners = game.winners.map(playerName);
  const title = finished ? "Partie terminée" : yours ? "À vous de jouer !" : `Au tour de ${playerName(game.turn)}`;
  const help = finished
    ? (winners.length > 1 ? `Égalité entre ${winners.join(" et ")}.` : solo && game.winners[0] === room.seat ? "Vous remportez la partie !" : `${winners[0]} remporte la partie.`)
    : yours ? "Choisissez une de vos lettres, puis une case." : active?.bot ? "Le bot cherche son prochain mot." : "Le plateau se mettra à jour après son coup.";
  output("turn-title", title);
  output("turn-help", help);
  byId("replay-export").hidden = !finished;
  byId("download-replay").hidden = !game.replay_available;
  output("replay-export-status", game.replay_available ? "Importez ce fichier dans Lettrine Studio pour revoir les coups." : "Journal complet indisponible pour cette partie.");
  const scores = room.players.map((player) => {
    const seat = player.seat;
    const name = element("span", "player-name", seat === room.seat && solo ? "Vous" : `${player.name}${seat === room.seat ? " (vous)" : ""}`);
    const count = game.rack_counts[seat];
    const info = element("div", "");
    const details = [player.bot ? "Bot" : "", !finished && game.turn === seat ? "À jouer" : "", `${count} lettre${count === 1 ? "" : "s"}`].filter(Boolean);
    info.append(name, element("span", "player-note", details.join(" · ")));
    const line = element("li", "");
    line.dataset.current = String(!finished && game.turn === seat);
    line.append(info, element("span", "score-value", game.scores[seat]));
    return line;
  });
  byId("scores").replaceChildren(...scores);
  output("bag", `${game.remaining} lettre${game.remaining === 1 ? "" : "s"} dans le sac`);
  const move = game.last_move;
  let last = "Aucun coup joué pour l’instant.";
  if (move) {
    const player = playerName(move.seat);
    if (move.type === "play") last = `${player} a joué ${move.word} pour ${pointLabel(move.score)}.`;
    if (move.type === "exchange") last = `${player} a échangé ${move.exchanged} lettre${move.exchanged === 1 ? "" : "s"}.`;
    if (move.type === "pass") last = `${player} a passé son tour.`;
  }
  output("last-turn", last);
  renderDraft();
}

async function downloadReplay() {
  const button = byId("download-replay");
  if (!room?.game?.replay_available || button.disabled) return;
  const activeGeneration = generation;
  button.disabled = true;
  try {
    const replay = await request(`/${code}/replay`, { authorization: token });
    if (activeGeneration !== generation) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(replay, null, 2)], { type: "application/json" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "lettrine-partie.json";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) {
    if (activeGeneration === generation) output("replay-export-status", errorMessage(error));
  } finally {
    button.disabled = false;
  }
}

async function commit(endpoint, data) {
  if (!room || waiting) return;
  clearDrag();
  clearHint();
  const revision = room.revision;
  const errorId = endpoint === "start" ? "error" : "play-error";
  waiting = true;
  notice(errorId);
  if (endpoint === "start") byId("start-game").disabled = true;
  else renderDraft();
  try {
    const snapshot = await request(`/${code}/${endpoint}`, {
      method: "POST", body: { revision, ...data }, authorization: token,
    });
    notice("connection");
    applyRoom(snapshot);
  } catch (error) {
    if ([401, 404].includes(error.status)) sessionEnded(error.status);
    else if (error.code === "stale_revision") notice(errorId, "La partie a changé. Le plateau se met à jour.");
    else notice(errorId, errorMessage(error));
  } finally {
    waiting = false;
    if (room?.game) renderDraft();
    else if (room) renderLobby();
    schedulePoll(200);
  }
}

byId("create-form").addEventListener("submit", enter);
byId("create-form").addEventListener("change", (event) => {
  if (event.target.name === "opponent") updateSetupMode();
  if (event.target.name === "capacity") updateBotCount();
  if (event.target.name === "bots-count") updateFriendSeats();
  if (event.target.name === "solo-level") showSoloHelp();
});
byId("join-form").addEventListener("submit", enter);
byId("invite-form").addEventListener("submit", (event) => enter(event, code));
byId("copy-link").addEventListener("click", copyInvitation);
byId("invite-url").addEventListener("click", (event) => event.target.select());
byId("start-game").addEventListener("click", () => commit("start", {}));
byId("download-replay").addEventListener("click", downloadReplay);
byId("shuffle-rack").addEventListener("click", shuffleRack);
byId("hint-button").addEventListener("click", requestHint);
byId("next-hint").addEventListener("click", nextHint);
byId("dismiss-hint").addEventListener("click", dismissHint);
byId("apply-hint").addEventListener("click", placeHint);
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape" || !hint || byId("blank-dialog").open || drag) return;
  event.preventDefault();
  dismissHint();
});
byId("play-move").addEventListener("click", () => {
  if (!byId("play-move").disabled) commit("action", { type: "play", placements: draftPlacements() });
});
byId("clear-move").addEventListener("click", () => {
  resetDraft();
  notice("play-error");
  renderDraft();
});
for (const [id, action] of [["swap-mode", "swap"], ["pass-mode", "pass"], ["cancel-action", "play"]]) {
  byId(id).addEventListener("click", () => {
    clearDrag();
    clearHint();
    if (action !== "play") draft.clear();
    mode = action;
    selected = null;
    swaps.clear();
    notice("play-error");
    renderDraft();
    byId(mode === "play" ? "play-move" : "cancel-action").focus();
  });
}
byId("confirm-action").addEventListener("click", () => {
  commit("action", mode === "swap" ? { type: "exchange", tiles: [...swaps] } : { type: "pass" });
});

function openInitialView() {
  code = roomCode(new URLSearchParams(location.search).get("room") || "");
  if (validCode(code)) {
    token = read(`seat.${code}`);
    show("invited");
    if (token) {
      byId("invited").hidden = true;
      byId("rooms-link").hidden = false;
      notice("loading", "Recherche de votre salon…");
      poll();
    }
    return;
  }
  show("welcome");
  if (code) notice("error", "Ce lien d’invitation est incomplet. Vérifiez-le auprès de votre ami.");
  const last = read("last");
  if (last && read(`seat.${last}`)) {
    byId("resume-link").href = roomUrl(last);
    byId("resume").hidden = false;
  }
}

document.addEventListener("visibilitychange", () => schedulePoll(document.hidden ? 4000 : 100));
const studioView = /^#(?:vue|meta|parties|decouverte|admin)(?:\?|$)/;
if (local && location.pathname === "/" && studioView.test(location.hash)) {
  location.replace(`/index.html${location.search}${location.hash}`);
} else {
  fillBotLevels(byId("solo-level"));
  showSoloHelp();
  updateBotCount();
  updateSetupMode();
  window.addEventListener("pageshow", () => {
    showSoloHelp();
    updateBotCount();
    updateSetupMode();
  });
  openInitialView();
  discoverInviteOrigin();
}
