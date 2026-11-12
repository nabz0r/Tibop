"use strict";

const $ = (id) => document.getElementById(id);
const links = Array.from(document.querySelectorAll("[data-view]"));
const levelNames = ["Découverte", "Initié", "Stratège", "Expert"];
const matchModes = { solo: "Solo", local: "Multijoueur local", bluetooth: "Bluetooth" };
const number = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 1 });
const integer = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 0 });
const percent = new Intl.NumberFormat("fr-FR", { style: "percent", maximumFractionDigits: 1 });
const precisePercent = new Intl.NumberFormat("fr-FR", { style: "percent", maximumFractionDigits: 2 });
const signed = new Intl.NumberFormat("fr-FR", { signDisplay: "exceptZero", maximumFractionDigits: 1 });
const pageSize = 20;
const state = {
  view: "vue",
  offset: 0,
  importOffset: 0,
  search: "",
  level: "",
  elite: false,
  source: "simulations",
  archive: null,
  gameRequest: 0,
  importRequest: 0,
  detailRequest: 0,
  reviewRequest: 0,
  refreshing: false,
  importing: false,
  stats: null,
  model: null,
  benchmarks: null,
  detail: null,
  move: 0,
  reviewCache: new Map(),
  challengeCache: new Map(),
  pendingChallenge: null,
  preview: null,
  playTimer: null,
  liveStream: null,
  liveFile: null,
};

function asNumber(value) {
  if (value === null || value === undefined || value === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function format(value, formatter = number) {
  const numeric = asNumber(value);
  return numeric === null ? "—" : formatter.format(numeric);
}

function node(tag, className = "", text = "") {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== "") element.textContent = text;
  return element;
}

function output(id, value) {
  const element = $(id);
  if (element.textContent !== value) element.textContent = value;
}

function replaceRows(container, elements, emptyLabel) {
  if (!elements.length) elements.push(node("p", "empty", emptyLabel));
  container.replaceChildren(...elements);
}

function td(text, className = "") {
  return node("td", className, text);
}

function emptyTable(container, columns, text) {
  const row = node("tr");
  const cell = td(text, "empty");
  cell.colSpan = columns;
  row.append(cell);
  container.replaceChildren(row);
}

function levelName(id) {
  return state.stats?.levels?.find((level) => Number(level.id) === Number(id))?.label
    ?? levelNames[Number(id)]
    ?? String(id ?? "—");
}

function levelLabel(id, className = "level-label") {
  const label = node("span", className);
  label.dataset.level = String(id);
  const dot = node("span", "level-dot");
  dot.setAttribute("aria-hidden", "true");
  label.append(dot, document.createTextNode(levelName(id)));
  return label;
}

function bar(ratio) {
  const track = node("span", "bar");
  const fill = node("span", "bar-fill");
  fill.style.width = Math.min(100, Math.max(0, ratio * 100)) + "%";
  track.setAttribute("aria-hidden", "true");
  track.append(fill);
  return track;
}

function renderLevels(levels) {
  const ordered = levels.slice().sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0));
  const maxRating = Math.max(1800, ...ordered.map((level) => Number(level.rating) || 0));
  const highlights = ordered.map((level) => {
    const row = node("div", "level-row");
    row.dataset.level = String(level.id);
    row.setAttribute("aria-label", level.label + ", " + format(level.rating, integer) + " Elo");
    row.append(levelLabel(level.id), bar((level.rating ?? 0) / maxRating), node("span", "level-rating", format(level.rating, integer)));
    return row;
  });
  replaceRows($("overview-levels"), highlights, "En attente des premiers résultats.");

  const table = $("meta-levels");
  if (!ordered.length) {
    emptyTable(table, 6, "En attente des premiers résultats.");
    return;
  }
  const rows = ordered.map((level) => {
    const row = node("tr");
    const name = td("");
    name.append(levelLabel(level.id));
    row.append(
      name, td(format(level.rating, integer), "numeric"),
      td(format(level.games, integer), "numeric"), td(format(level.wins, integer), "numeric"),
      td(format(level.draws, integer), "numeric"), td(format(level.mean_score), "numeric"),
    );
    return row;
  });
  table.replaceChildren(...rows);
}

function renderWords(words) {
  const maxCount = Math.max(1, ...words.map((word) => Number(word.count) || 0));
  const rows = words.slice(0, 12).map(({ word, count, mean_score: meanScore }) => {
    const row = node("div", "word-row with-score");
    row.append(node("span", "word", word), bar(count / maxCount), node("span", "word-count", format(count, integer)));
    row.append(node("span", "word-score", format(meanScore) + " pts"));
    return row;
  });
  replaceRows($("meta-words"), rows, "En attente des premiers résultats.");
}

function renderMatchups(pairs) {
  const rows = pairs.map(({ a, b, games, a_wins: aWins, b_wins: bWins, draws }) => {
    const row = node("article", "matchup-row");
    const labels = node("div", "matchup-labels");
    labels.append(levelLabel(a), levelLabel(b));
    const stack = node("div", "matchup-bar");
    stack.setAttribute("aria-hidden", "true");
    for (const [level, count] of [[a, aWins], ["nuls", draws], [b, bWins]]) {
      const segment = node("span", "matchup-segment");
      segment.dataset.level = String(level);
      segment.style.width = (games > 0 ? 100 * (count ?? 0) / games : 0) + "%";
      stack.append(segment);
    }
    const summary = node("div", "matchup-stats");
    summary.append(
      node("span", "", format(aWins, integer) + " victoires"),
      node("span", "", format(draws, integer) + " nuls · " + format(games, integer) + " parties"),
      node("span", "", format(bWins, integer) + " victoires"),
    );
    row.append(labels, stack, summary);
    return row;
  });
  replaceRows($("meta-matchups"), rows, "En attente des premières confrontations.");
}

function elapsed(value) {
  const seconds = asNumber(value);
  if (seconds === null || seconds < 0) return "—";
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds / 60) % 60;
  const remainder = Math.floor(seconds) % 60;
  if (hours) return hours + " h " + String(minutes).padStart(2, "0") + " min";
  if (minutes) return minutes + " min " + String(remainder).padStart(2, "0") + " s";
  return remainder + " s";
}

function renderStats(stats) {
  const completed = Number(stats.completed) || 0;
  const target = Number(stats.target) || 0;
  const campaignState = stats.running ? "running" : target > 0 && completed >= target ? "done" : "idle";
  const campaignLabel = { running: "En cours", done: "Terminée", idle: completed ? "En pause" : "En attente" }[campaignState];
  const runProgress = $("run-progress");
  runProgress.max = Math.max(1, target);
  runProgress.value = Math.min(completed, runProgress.max);
  $("run-status").dataset.state = campaignState;
  output("run-status-label", campaignLabel);
  output("run-count", format(completed, integer));
  output("run-target", target ? "/ " + format(target, integer) : "");
  output("run-percent", target ? format(completed / target, percent) : "—");
  output("metric-score", completed ? format(stats.mean_score) : "—");
  output("metric-best", completed ? format(stats.best_score, integer) : "—");
  output("metric-turns", completed ? format(stats.mean_turns) : "—");
  output("metric-bingos", completed ? format(stats.bingo_rate, percent) : "—");
  output("metric-first", completed ? format(stats.first_win_rate, percent) : "—");
  output("metric-meta-bingos", completed ? format(stats.bingo_rate, percent) : "—");
  output("metric-exchanges", completed ? format(stats.exchange_rate, percent) : "—");
  output("metric-passes", completed ? format(stats.pass_rate, percent) : "—");
  output("admin-state", campaignLabel);
  output("admin-storage", { postgresql: "PostgreSQL", sqlite: "SQLite" }[stats.storage] ?? "—");
  output("admin-count", format(completed, integer));
  output("admin-elapsed", elapsed(stats.elapsed_seconds));
  output("admin-lexicon", stats.lexicon || "—");
  output("admin-lexicon-count", format(stats.lexicon_words, integer));
  output("sidebar-lexicon", stats.lexicon || "Scrabble francophone");
  output("local-model", stats.ollama_model || "Llama local");
  const levels = completed ? stats.levels ?? [] : [];
  const pairs = (stats.matchups ?? []).filter((pair) => pair.games > 0);
  renderLevels(levels);
  renderMatchups(pairs);
  renderWords(stats.words ?? []);
}

async function readResult(response) {
  const result = await response.json().catch(() => null);
  if (!response.ok || !result) {
    const error = new Error(typeof result?.error === "string" ? result.error : "");
    error.status = response.status;
    throw error;
  }
  return result;
}

async function get(url) {
  return readResult(await fetch(url, { cache: "no-store", headers: { Accept: "application/json" } }));
}

async function refreshStats() {
  if (state.refreshing) return;
  state.refreshing = true;
  $("refresh").disabled = true;
  try {
    const stats = await get("/api/stats");
    const previous = state.stats?.completed;
    state.stats = stats;
    renderStats(stats);
    $("connection").parentElement.dataset.state = "online";
    output("connection", "Service local");
    output("last-sync", "Actualisé à " + new Date().toLocaleTimeString("fr-FR", { timeStyle: "medium" }));
    if (previous !== undefined && previous !== stats.completed && state.view === "parties" && state.source === "simulations" && !state.offset && !state.search && !$("game-dialog").open) {
      loadGames();
    }
  } catch {
    $("connection").parentElement.dataset.state = "offline";
    output("connection", "Service indisponible");
    output("last-sync", "Le service local est momentanément indisponible.");
    $("run-status").dataset.state = "offline";
    output("run-status-label", "Hors ligne");
  } finally {
    state.refreshing = false;
    $("refresh").disabled = false;
  }
}

async function loadModel() {
  if (state.model) return;
  try {
    const model = await get("/api/models");
    if (!model.holdout) return;
    state.model = model;
    output("trained-elo", format(model.holdout.elo_delta, signed) + " Elo");
    output("trained-match-rate", format(model.holdout.match_score_rate, precisePercent));
    output("trained-games", format(model.holdout.games, integer) + " duels de test");
    $("trained-track").replaceChildren(bar(model.holdout.match_score_rate));
    $("trained-panel").hidden = false;
  } catch {
    // The dashboard still works with a campaign that predates policy training.
  }
}

function benchmarkRow(data) {
  const row = node("div", "llm-test");
  const head = node("div", "model-head");
  const title = data.model || "Modèle local";
  const elo = asNumber(data.elo_delta);
  head.append(node("strong", "", title + " · Test de jeu"));
  if (elo !== null) head.append(node("strong", "model-elo", format(elo, signed) + " Elo estimés"));
  const score = node("p", "", format(data.games, integer) + " parties face à " + (data.opponent || "l’Expert") + " · Score de match " + format(data.match_score_rate, precisePercent));
  row.append(head, score);
  if (Array.isArray(data.score_interval_95)) {
    row.append(node("p", "", "Intervalle à 95 % · " + data.score_interval_95.map((value) => format(value, precisePercent)).join(" à ")));
  }
  row.append(node("p", "", "Sélection expérimentale · " + format(data.llm_choices, integer) + " coups choisis · " + format(data.fallbacks, integer) + " replis sur le moteur"));
  return row;
}

async function loadBenchmarks() {
  if (state.benchmarks) return;
  try {
    const result = await get("/api/llm-benchmarks");
    if (!Array.isArray(result.items) || !result.items.length) return;
    state.benchmarks = result.items;
    $("llm-elo-placeholder").hidden = true;
    $("llm-benchmarks").replaceChildren(...result.items.map(benchmarkRow));
  } catch {
    // Benchmark output is optional.
  }
}

function gameLabel(id) {
  return "Partie " + String(id);
}

function winnerLabel(game) {
  if (game.winner === null || game.winner === -1 || game.winner === "draw") return "Égalité";
  const index = asNumber(game.winner);
  return index === null ? "—" : playerName(game, index);
}

function scoreLabel(game) {
  return Array.isArray(game.scores) ? game.scores.map((score) => format(score, integer)).join(" – ") : "—";
}

function originLabel(game) {
  return game.source === "android" || game.origin === "android" ? "Android" : "Fichier";
}

function importMetadata(game) {
  return [originLabel(game), matchModes[game.mode], game.lexicon].filter(Boolean).join(" · ");
}

function gameRow(game, imported = false) {
  const row = node("tr");
  const identity = td("");
  identity.append(node("span", "game-id", "#" + String(game.id)));
  if (imported) identity.append(node("span", "game-seed", importMetadata(game)));
  else if (game.seed !== null && game.seed !== undefined) identity.append(node("span", "game-seed", "Graine " + String(game.seed)));
  const players = td("");
  (game.scores ?? []).forEach((_, index) => {
    if (index) players.append(node("span", "level-versus", "×"));
    players.append(imported ? node("span", "level-chip", playerName(game, index)) : levelLabel(game.levels?.[index], "level-chip"));
  });
  const action = td("");
  const button = node("button", "button secondary", "Rejouer");
  button.type = "button";
  button.setAttribute("aria-label", "Rejouer la partie " + String(game.id));
  button.addEventListener("click", () => openGame(game.id, imported));
  action.append(button);
  row.append(identity, players, td(scoreLabel(game), "numeric"), td(format(game.turns, integer), "numeric"), td(winnerLabel(game)), action);
  return row;
}

function updatePages(source, offset, total, length) {
  const range = length > 0 ? format(offset + 1, integer) + "–" + format(offset + length, integer) + " sur " : "";
  output(source + "-count", range + format(total, integer) + (total === 1 ? " partie" : " parties"));
  $(source + "-prev").disabled = offset === 0;
  $(source + "-next").disabled = offset + length >= total || length === 0;
}

function pageError(source, message) {
  emptyTable($(source + "-rows"), 6, message);
  output(source + "-count", "—");
  $(source + "-prev").disabled = true;
  $(source + "-next").disabled = true;
}

async function loadGames() {
  const request = ++state.gameRequest;
  const query = new URLSearchParams({ limit: pageSize, offset: state.offset });
  if (state.level !== "") query.set("level", state.level);
  if (state.search) query.set("q", state.search);
  if (state.elite) query.set("elite", "1");
  try {
    const result = await get("/api/games?" + query);
    if (request !== state.gameRequest) return;
    const games = result.items ?? [];
    if (games.length) $("games-rows").replaceChildren(...games.map((game) => gameRow(game)));
    else emptyTable($("games-rows"), 6, "Aucune partie ne correspond à ces filtres.");
    updatePages("games", state.offset, Number(result.total) || 0, games.length);
  } catch {
    if (request !== state.gameRequest) return;
    pageError("games", "Impossible de charger les parties. Réessayez avec le bouton Filtrer.");
  }
}

async function loadImports() {
  const request = ++state.importRequest;
  const query = new URLSearchParams({ limit: pageSize, offset: state.importOffset });
  try {
    const result = await get("/api/imports?" + query);
    if (request !== state.importRequest) return;
    const games = result.items ?? [];
    if (games.length) $("imports-rows").replaceChildren(...games.map((game) => gameRow(game, true)));
    else emptyTable($("imports-rows"), 6, "Les parties ajoutées dans Administration apparaîtront ici.");
    updatePages("imports", state.importOffset, Number(result.total) || 0, games.length);
  } catch {
    if (request !== state.importRequest) return;
    pageError("imports", "Impossible de charger les parties importées.");
  }
}

function setView() {
  const [hash, query] = location.hash.slice(1).split("?");
  const selected = links.some((link) => link.dataset.view === hash) ? hash : "vue";
  const changed = selected !== state.view;
  state.view = selected;
  for (const link of links) {
    const active = link.dataset.view === selected;
    $("view-" + link.dataset.view).hidden = !active;
    if (active) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }
  if (selected === "parties") {
    const position = new URLSearchParams(query);
    const game = position.get("game");
    const turn = asNumber(position.get("turn"));
    if (game && /^\d+$/.test(game)) {
      setGameSource("simulations");
      openGame(game, false, Number.isInteger(turn) ? turn : undefined);
    } else {
      setGameSource(state.source);
    }
  }
  if (selected === "vue") {
    loadModel();
    loadBenchmarks();
  }
  if (changed) window.scrollTo(0, 0);
}

const premiums = new Map();
const premiumPositions = {
  tw: [[0, 0], [0, 7], [0, 14], [7, 0], [7, 14], [14, 0], [14, 7], [14, 14]],
  dw: [[1, 1], [2, 2], [3, 3], [4, 4], [7, 7], [10, 10], [11, 11], [12, 12], [13, 13], [1, 13], [2, 12], [3, 11], [4, 10], [10, 4], [11, 3], [12, 2], [13, 1]],
  tl: [[1, 5], [1, 9], [5, 1], [5, 5], [5, 9], [5, 13], [9, 1], [9, 5], [9, 9], [9, 13], [13, 5], [13, 9]],
  dl: [[0, 3], [0, 11], [2, 6], [2, 8], [3, 0], [3, 7], [3, 14], [6, 2], [6, 6], [6, 8], [6, 12], [7, 3], [7, 11], [8, 2], [8, 6], [8, 8], [8, 12], [11, 0], [11, 7], [11, 14], [12, 6], [12, 8], [14, 3], [14, 11]],
};
for (const [type, positions] of Object.entries(premiumPositions)) {
  for (const [row, col] of positions) premiums.set(row * 15 + col, type);
}
const tilePoints = { A: 1, B: 3, C: 3, D: 2, E: 1, F: 4, G: 2, H: 4, I: 1, J: 8, K: 10, L: 1, M: 2, N: 1, O: 1, P: 3, Q: 8, R: 1, S: 1, T: 1, U: 1, V: 4, W: 10, X: 10, Y: 10, Z: 10 };
const cells = Array.from({ length: 225 }, (_, index) => node("div", "board-cell" + (premiums.has(index) ? " " + premiums.get(index) : "")));
$("board").append(...cells);

function moveLabel(move) {
  if (!move) return "Position de départ";
  if (move.type === "invalid") return move.word ? move.word + " (refusé)" : "Coup refusé";
  if (move.word) return move.word;
  if (["pass", "skip"].includes(move.type)) return "Passe";
  if (["exchange", "swap"].includes(move.type)) return "Échange";
  return "Placement";
}

function tileIndex(tile) {
  const row = asNumber(tile.row);
  const col = asNumber(tile.col);
  if (!Number.isInteger(row) || !Number.isInteger(col) || row < 0 || row >= 15 || col < 0 || col >= 15) return null;
  return row * 15 + col;
}

function renderBoard(step, preview) {
  const moves = state.detail?.moves ?? [];
  const board = new Map();
  const fresh = new Set();
  const placedCount = preview ? step - 1 : step;
  for (const [index, move] of moves.slice(0, placedCount).entries()) {
    for (const tile of move.tiles ?? []) {
      const coordinate = tileIndex(tile);
      if (coordinate === null) continue;
      board.set(coordinate, tile);
      if (!preview && index === step - 1) fresh.add(coordinate);
    }
  }
  for (const tile of preview?.tiles ?? []) {
    const coordinate = tileIndex(tile);
    if (coordinate === null || board.has(coordinate)) continue;
    board.set(coordinate, tile);
    fresh.add(coordinate);
  }
  for (const [index, cell] of cells.entries()) {
    const tile = board.get(index);
    cell.classList.toggle("occupied", !!tile);
    cell.classList.toggle("new-tile", fresh.has(index));
    cell.classList.toggle("blank", !!tile?.blank);
    cell.classList.toggle("preview-tile", !!preview && fresh.has(index));
    if (tile) {
      const letter = String(tile.letter ?? "").toUpperCase();
      cell.replaceChildren(document.createTextNode(letter), node("small", "", String(tile.blank ? 0 : tilePoints[letter] ?? "")));
    } else if (index === 112) {
      cell.textContent = "★";
    } else {
      cell.textContent = "";
    }
  }
  const position = preview ? ", variante " + preview.word : ", après le coup " + step;
  $("board").setAttribute("aria-label", "Plateau du Scrabble" + position + ", " + board.size + " lettres");
}

function reviewField(label, value) {
  const pair = node("div");
  pair.append(node("dt", "", label), node("dd", "", value));
  return pair;
}

function renderReview(review) {
  const result = $("review-results");
  result.hidden = !review;
  if (!review) return;
  const summary = [
    reviewField("Coup joué", format(review.played_score, integer) + " pts"),
    reviewField(state.detail?.archive ? "Maximum indiqué" : "Meilleur score", format(review.best_score, integer) + " pts"),
    reviewField("Écart", format(review.points_left, integer) + " pts"),
  ];
  if (asNumber(review.played_rank) !== null) summary.push(reviewField("Rang du coup", "#" + format(review.played_rank, integer)));
  if (review.position_rack) {
    const rack = Array.isArray(review.position_rack) ? review.position_rack.join(" ") : String(review.position_rack);
    summary.push(reviewField("Chevalet avant le coup", rack));
  }
  if (asNumber(review.legal_moves) !== null) summary.push(reviewField("Placements légaux", format(review.legal_moves, integer)));
  if (asNumber(review.samples) !== null) summary.push(reviewField("Tirages simulés", format(review.samples, integer)));
  $("review-summary").replaceChildren(...summary);
  const options = (review.alternatives ?? []).map((choice) => {
    const row = node("li");
    const button = node("button", "review-option");
    button.type = "button";
    button.setAttribute("aria-pressed", String(state.preview === choice));
    const details = node("span", "review-choice");
    const played = state.detail?.moves?.[state.move - 1];
    details.append(node("strong", "", choice.word || moveLabel(played)));
    const leave = choice.leave === "" ? "Chevalet vidé" : choice.leave ? "Reliquat " + choice.leave : "";
    const notes = [choice.played ? "Joué" : "", leave].filter(Boolean);
    if (notes.length) details.append(node("small", "", notes.join(" · ")));
    const scores = node("span", "review-choice-scores");
    scores.append(node("strong", "", format(choice.score, integer) + " pts"));
    if (asNumber(choice.evaluation) !== null) {
      const margin = "Écart estimé " + format(choice.evaluation, signed);
      const uncertainty = choice.uncertainty > 0 ? " ± " + format(choice.uncertainty) : "";
      scores.append(node("small", "", margin + uncertainty));
    }
    button.append(details, scores);
    button.addEventListener("click", () => {
      state.preview = state.preview === choice ? null : choice;
      for (const item of $("review-options").querySelectorAll("button")) {
        item.setAttribute("aria-pressed", String(!!state.preview && item === button));
      }
      renderBoard(state.move, state.preview);
      updateReplayCaption();
    });
    row.append(button);
    return row;
  });
  $("review-options").replaceChildren(...options);
  output("review-method", review.method ?? "");
}

function refreshReviewPanel() {
  const move = state.detail?.moves?.[state.move - 1];
  const archive = !!state.detail?.archive;
  const restricted = !!(state.detail?.imported || state.detail?.live);
  $("position-review").hidden = restricted;
  if (restricted) return;
  $("review-run").hidden = archive;
  $("position-challenge").hidden = archive;
  if (archive) {
    output("review-status", move ? "Maximum signalé dans le relevé d’origine." : "Sélectionnez un coup à examiner.");
    renderReview(move && asNumber(move.top_score) !== null ? {
      played_score: move.score,
      best_score: move.top_score,
      points_left: move.top_score - move.score,
      method: "Valeurs publiées pour le lexique historique " + state.detail.dictionary + ".",
    } : null);
    if (move && asNumber(move.top_score) === null) output("review-status", "Le relevé ne renseigne pas de maximum pour ce coup.");
    return;
  }
  const cached = state.reviewCache.get(state.move);
  $("review-run").disabled = !move;
  $("review-run").textContent = cached ? "Recalculer" : "Analyser";
  output("review-status", cached ? "Sélectionnez une variante pour la placer sur le plateau." : move ? "Comparez ce coup aux placements disponibles." : "Sélectionnez un coup à examiner.");
  renderReview(cached);
  renderChallenge();
}

function renderChallenge() {
  const data = state.challengeCache.get(state.move);
  const pending = state.pendingChallenge;
  const active = pending?.detail === state.detail;
  $("challenge-run").disabled = !state.move || active;
  $("challenge-run").textContent = data?.commentary ? "Relancer Llama" : "Challenger avec Llama";
  const status = active && pending.step === state.move ? "Llama examine le coup…" : data?.error;
  $("challenge-status").hidden = !status;
  output("challenge-status", status || "");
  $("challenge-result").hidden = !data?.commentary;
  output("challenge-model", data?.model || "Llama");
  output("challenge-commentary", data?.commentary || "");
}

async function challengeMove() {
  stopReplay();
  const { detail, move: step } = state;
  const move = detail?.moves?.[step - 1];
  if (!move || detail.archive || detail.imported || state.pendingChallenge?.detail === detail) return;
  const pending = { detail, step };
  state.pendingChallenge = pending;
  state.challengeCache.delete(step);
  renderChallenge();
  const turn = encodeURIComponent(move.turn ?? step);
  try {
    const result = await get("/api/games/" + encodeURIComponent(detail.id) + "/challenge?turn=" + turn);
    if (detail === state.detail) state.challengeCache.set(step, result);
  } catch (error) {
    if (detail === state.detail) {
      state.challengeCache.set(step, { error: error.message || "Llama est indisponible sur ce Mac." });
    }
  } finally {
    if (state.pendingChallenge === pending) state.pendingChallenge = null;
    if (detail === state.detail && $("game-dialog").open) renderChallenge();
  }
}

async function reviewMove() {
  stopReplay();
  const detail = state.detail;
  const step = state.move;
  const move = detail?.moves?.[step - 1];
  if (!move) return;
  const request = ++state.reviewRequest;
  $("review-run").disabled = true;
  output("review-status", "Calcul des placements disponibles…");
  const turn = encodeURIComponent(move.turn ?? step);
  try {
    const review = await get("/api/games/" + encodeURIComponent(detail.id) + "/review?turn=" + turn);
    if (detail !== state.detail || !$("game-dialog").open) return;
    state.reviewCache.set(step, review);
    if (request !== state.reviewRequest || state.move !== step) return;
    refreshReviewPanel();
  } catch {
    if (request !== state.reviewRequest || detail !== state.detail || state.move !== step) return;
    $("review-run").disabled = false;
    output("review-status", "Analyse indisponible pour ce coup. Réessayez.");
  }
}

function updateReplayCaption() {
  const moves = state.detail?.moves ?? [];
  const current = moves[state.move - 1];
  let caption = state.move ? "Coup " + state.move + " / " + moves.length + " · " + moveLabel(current) : "Position de départ";
  if (state.preview) caption += " · Variante " + (state.preview.word || moveLabel(current)) + " (" + format(state.preview.score, integer) + " pts)";
  output("replay-caption", caption);
}

function playerName(game, seat) {
  const level = asNumber(game.levels?.[seat]);
  return game.names?.[seat] ?? game.players?.[seat] ?? (level !== null && level >= 0 ? levelName(level) : "Joueur " + (Number(seat) + 1));
}

function positionDetails(moves) {
  const game = state.detail;
  const current = moves[state.move - 1];
  const elements = [];
  if (current) {
    if (current.rack) {
      const rack = Array.isArray(current.rack) ? current.rack.join("") : String(current.rack);
      const rackLine = node("div", "position-rack");
      const rackTiles = node("span", "rack-tiles");
      rackTiles.setAttribute("aria-label", rack.replaceAll("?", " joker "));
      rackTiles.append(...Array.from(rack, (letter) => node("span", "", letter)));
      rackLine.append(node("span", "", "Chevalet avant le coup"), rackTiles);
      elements.push(rackLine);
    }
    if (asNumber(current.bag_before) !== null) {
      elements.push(node("p", "", "Sac avant le coup · " + format(current.bag_before, integer) + " jetons"));
    }
    if (current.coordinate) elements.push(node("p", "", "Position · " + current.coordinate));
  }
  const seats = game.scores?.length || game.names?.length || game.players?.length || game.levels?.length || 2;
  const totals = moves.slice(0, state.move).reduce((scores, move) => {
    if (Number.isInteger(move.player) && move.player >= 0 && move.player < scores.length) scores[move.player] += Number(move.score) || 0;
    return scores;
  }, Array(seats).fill(0));
  const score = node("div", "position-scores");
  for (const [seat, points] of totals.entries()) {
    const item = node("span");
    item.append(node("span", "", playerName(game, seat)), node("strong", "", format(points, integer)));
    score.append(item);
  }
  elements.push(score);
  if (state.move === moves.length && game.adjustments?.some((score) => score)) {
    elements.push(node("p", "", "Ajustements finaux · " + game.adjustments.map((score) => (score > 0 ? "+" : "") + format(score, integer)).join(" / ")));
  }
  $("position-state").replaceChildren(...elements);
}

function replayAt(step) {
  const moves = state.detail?.moves ?? [];
  state.move = Math.max(0, Math.min(moves.length, step));
  state.preview = null;
  renderBoard(state.move, null);
  $("replay-step").value = String(state.move);
  $("replay-prev").disabled = state.move <= 0;
  $("replay-next").disabled = state.move >= moves.length;
  updateReplayCaption();
  Array.from($("move-list").querySelectorAll("button")).forEach((button, index) => {
    if (index + 1 === state.move) {
      button.setAttribute("aria-current", "step");
      const list = $("move-list");
      if (button.offsetTop < list.scrollTop + list.offsetTop || button.offsetTop + button.offsetHeight > list.scrollTop + list.offsetTop + list.clientHeight) {
        list.scrollTop = button.offsetTop - list.offsetTop - list.clientHeight / 2;
      }
    } else {
      button.removeAttribute("aria-current");
    }
  });
  positionDetails(moves);
  refreshReviewPanel();
}

function stopReplay() {
  clearInterval(state.playTimer);
  state.playTimer = null;
  $("replay-play").setAttribute("aria-pressed", "false");
  $("replay-play").setAttribute("aria-label", "Lire la partie");
  $("replay-play").firstElementChild.textContent = "▶";
}

function jumpToMove(step) {
  stopReplay();
  replayAt(step);
}

function toggleReplay() {
  if (state.playTimer !== null) {
    stopReplay();
    return;
  }
  const total = state.detail?.moves?.length ?? 0;
  if (!total) return;
  if (state.move >= total) replayAt(0);
  $("replay-play").setAttribute("aria-pressed", "true");
  $("replay-play").setAttribute("aria-label", "Mettre en pause");
  $("replay-play").firstElementChild.textContent = "Ⅱ";
  state.playTimer = setInterval(() => {
    replayAt(state.move + 1);
    if (state.move >= total) stopReplay();
  }, 800);
}

function buildReplay(game, step) {
  const moves = game.moves ?? [];
  $("replay-step").max = String(moves.length);
  $("replay-step").disabled = !moves.length;
  $("replay-play").disabled = !moves.length;
  const rows = moves.map((move, index) => {
    const row = node("li");
    const button = node("button", "move-button");
    button.type = "button";
    const word = node("span", "move-word", moveLabel(move));
    word.append(node("small", "", playerName(game, move.player)));
    button.append(node("span", "move-number", String(index + 1)), word, node("span", "move-score", format(move.score, integer) + " pts"));
    button.addEventListener("click", () => jumpToMove(index + 1));
    row.append(button);
    return row;
  });
  $("move-list").replaceChildren(...rows);
  $("game-loading").hidden = true;
  $("game-replay").hidden = false;
  replayAt(step ?? moves.length);
}

async function openGame(id, imported = false, step) {
  resetLive();
  stopReplay();
  const request = ++state.detailRequest;
  state.detail = null;
  state.reviewCache.clear();
  state.challengeCache.clear();
  state.preview = null;
  output("game-title", gameLabel(id));
  output("game-subtitle", "");
  $("game-source").hidden = true;
  output("game-loading", "Chargement de la partie…");
  $("game-loading").hidden = false;
  $("game-replay").hidden = true;
  const dialog = $("game-dialog");
  if (!dialog.open) dialog.showModal();
  try {
    const game = await get((imported ? "/api/imports/" : "/api/games/") + encodeURIComponent(id));
    if (request !== state.detailRequest || !dialog.open) return;
    state.detail = { ...game, id, imported };
    const players = (game.scores ?? []).map((_, seat) => playerName(game, seat)).join(" × ");
    output("game-subtitle", [players, scoreLabel(game)].filter(Boolean).join(" · "));
    if (imported) {
      const source = $("game-source");
      source.replaceChildren(node("span", "", importMetadata(game)));
      source.hidden = false;
    }
    buildReplay(state.detail, step);
  } catch {
    if (request !== state.detailRequest || !dialog.open) return;
    output("game-loading", "Impossible de charger cette partie.");
  }
}

function sourceLink(url, title) {
  if (!url) return null;
  const parsed = new URL(url, location.href);
  if (parsed.protocol !== "https:") return null;
  const link = node("a", "text-link", title + " ↗");
  link.href = parsed.href;
  link.target = "_blank";
  link.rel = "noopener noreferrer";
  return link;
}

function renderCorpusResult(item) {
  const article = node("article", "corpus-result");
  const body = String(item.body ?? "");
  article.append(node("h3", "", item.title || "Document"));
  if (body.length > 300) {
    const details = node("details");
    details.append(node("summary", "", body.slice(0, 285).trimEnd() + "…"), node("p", "", body));
    article.append(details);
  } else {
    article.append(node("p", "", body));
  }
  if (item.source?.startsWith("/#parties?")) {
    const link = node("a", "text-link", "Ouvrir la position →");
    link.href = item.source.slice(1);
    article.append(link);
  } else if (item.source?.startsWith("https://")) {
    article.append(sourceLink(item.source, "Source"));
  } else if (item.source) {
    article.append(node("small", "corpus-source", item.source));
  }
  return article;
}

async function searchCorpus(event) {
  event.preventDefault();
  const button = $("corpus-submit");
  const query = $("corpus-query").value.trim();
  if (button.disabled || query.length < 2) return;
  const status = $("corpus-status");
  const results = $("corpus-results");
  button.disabled = true;
  status.hidden = false;
  output("corpus-status", "Recherche en cours…");
  results.replaceChildren();
  try {
    const result = await get("/api/search?" + new URLSearchParams({ q: query }));
    const items = Array.isArray(result.items) ? result.items : [];
    output("corpus-status", items.length ? format(items.length, integer) + (items.length === 1 ? " résultat" : " résultats") : "Aucun résultat. Essayez un autre thème.");
    results.replaceChildren(...items.map(renderCorpusResult));
  } catch (error) {
    output("corpus-status", error.message || "Recherche indisponible pour le moment.");
  } finally {
    button.disabled = false;
  }
}

function historicalTiles(turn, board) {
  if (turn.action !== "place" || !turn.word || !turn.coordinate) return [];
  const horizontal = /^([A-O])(\d{1,2})$/.exec(turn.coordinate);
  const vertical = /^(\d{1,2})([A-O])$/.exec(turn.coordinate);
  if (!horizontal && !vertical) return null;
  const row = (horizontal ? horizontal[1] : vertical[2]).charCodeAt(0) - 65;
  const col = Number(horizontal ? horizontal[2] : vertical[1]) - 1;
  const letters = Array.from(turn.word);
  const rack = Array.from(turn.rack ?? "");
  const tiles = [];
  for (const [offset, letter] of letters.entries()) {
    const tile = {
      row: row + (vertical ? offset : 0),
      col: col + (horizontal ? offset : 0),
      letter,
      blank: (turn.blank_offsets ?? []).includes(offset),
    };
    const index = tileIndex(tile);
    if (index === null) return null;
    if (board.has(index)) {
      if (board.get(index) !== letter) return null;
      continue;
    }
    const slot = rack.indexOf(tile.blank ? "?" : letter);
    if (slot === -1) return null;
    rack.splice(slot, 1);
    tiles.push(tile);
  }
  for (const tile of tiles) board.set(tileIndex(tile), tile.letter);
  return tiles;
}

function openExpert(archiveGame) {
  resetLive();
  stopReplay();
  ++state.detailRequest;
  state.reviewCache.clear();
  state.challengeCache.clear();
  state.preview = null;
  const board = new Map();
  let valid = true;
  const moves = (archiveGame.turns ?? []).map((turn) => {
    const tiles = valid ? historicalTiles(turn, board) : [];
    if (!tiles) valid = false;
    return { ...turn, turn: turn.index, type: turn.action, tiles: tiles ?? [] };
  });
  const adjustments = (archiveGame.end_adjustments ?? []).reduce((scores, move) => {
    if (move.player === 0 || move.player === 1) scores[move.player] += Number(move.score) || 0;
    return scores;
  }, [0, 0]);
  const game = { ...archiveGame, archive: true, moves, adjustments, scores: archiveGame.final_scores ?? archiveGame.archive_score_totals };
  state.detail = game;
  output("game-title", game.title);
  const result = scoreLabel(game) + (archiveGame.final_scores ? " (officiel)" : " (relevé)");
  output("game-subtitle", [game.players.join(" × "), result, game.dictionary].filter(Boolean).join(" · "));
  const source = $("game-source");
  const links = [sourceLink(game.source, "Relevé d’origine"), sourceLink(game.final_scores_source, "Résultat officiel")].filter(Boolean);
  const notes = [game.score_discrepancy ? "Le total du relevé diffère du résultat officiel." : "", valid ? "" : "Reconstitution partielle du plateau."].filter(Boolean);
  source.replaceChildren(...links, ...notes.map((note) => node("span", "", note)));
  source.hidden = !source.childNodes.length;
  if (!$("game-dialog").open) $("game-dialog").showModal();
  buildReplay(game);
}

function renderArchiveGame(game) {
  const item = node("article", "archive-row");
  const summary = node("div");
  summary.append(node("h3", "", game.title), node("p", "", game.players.join(" × ")));
  const metadata = [game.dictionary, format(game.turns?.length, integer) + " coups", scoreLabel({ scores: game.final_scores ?? game.archive_score_totals })].filter(Boolean);
  summary.append(node("p", "archive-meta", metadata.join(" · ")));
  const button = node("button", "button secondary", "Rejouer");
  button.type = "button";
  button.setAttribute("aria-label", "Rejouer " + game.title);
  button.addEventListener("click", () => openExpert(game));
  item.append(summary, button);
  return item;
}

function renderReference(reference) {
  const item = node("article", "reference");
  const head = node("div", "reference-head");
  const identity = node("div");
  identity.append(node("h3", "", reference.title), node("p", "", reference.players.join(" × ") + " · " + reference.dictionary));
  head.append(identity);
  const source = sourceLink(reference.source, "Couverture FFSc");
  if (source) head.append(source);
  item.append(head);
  const scores = node("ol", "reference-scores");
  for (const [index, [a, b]] of (reference.match_scores ?? []).entries()) {
    const round = node("li");
    round.append(node("span", "", "Partie " + (index + 1)), node("strong", "", format(a, integer) + " – " + format(b, integer)));
    scores.append(round);
  }
  item.append(scores);
  const highlight = reference.highlight;
  if (highlight) {
    const play = node("div", "reference-highlight");
    const title = node("h4", "", highlight.word + " en " + highlight.coordinate + " · " + format(highlight.score, integer) + " points");
    play.append(title, node("p", "", highlight.analysis));
    const link = sourceLink(highlight.source, "Analyse du coup");
    if (link) play.append(link);
    item.append(play);
  }
  return item;
}

async function loadArchive() {
  if (state.archive) return;
  try {
    state.archive = await get("/api/expert");
    replaceRows($("archive-list"), (state.archive.games ?? []).map(renderArchiveGame), "Aucun relevé disponible.");
    replaceRows($("reference-list"), (state.archive.references ?? []).map(renderReference), "Aucun résultat disponible.");
  } catch {
    state.archive = null;
    replaceRows($("archive-list"), [], "Archives indisponibles pour le moment.");
    replaceRows($("reference-list"), [], "Résultats indisponibles pour le moment.");
  }
}

function setGameSource(source) {
  state.source = source;
  for (const [name, panel] of [["simulations", "simulation-games"], ["archives", "archive-games"], ["imports", "imported-games"], ["live", "live-games"]]) {
    $(panel).hidden = source !== name;
    $("source-" + name).setAttribute("aria-pressed", String(source === name));
  }
  if (source === "archives") loadArchive();
  else if (source === "imports") loadImports();
  else if (source === "simulations") loadGames();
}

function showLiveStatus(message) {
  $("live-status").hidden = !message;
  output("live-status", message);
}

function resetLive() {
  state.liveStream?.close();
  state.liveStream = null;
  if (state.liveFile) URL.revokeObjectURL(state.liveFile);
  state.liveFile = null;
  $("live-export").hidden = true;
  $("live-export").removeAttribute("href");
  showLiveStatus("");
}

function renderLive(data) {
  const game = state.detail;
  if (data.type === "start") {
    state.detail = { live: true, seed: data.seed, levels: data.levels, scores: [0, 0], moves: [] };
    output("game-subtitle", data.levels.map(levelName).join(" × ") + " · Graine " + data.seed);
    showLiveStatus("En direct · 0 / " + data.turns);
    state.detail.turns = data.turns;
    buildReplay(state.detail);
  } else if (data.type === "move" && game?.live) {
    const previous = game.moves.length;
    game.moves.push({ ...data, type: data.action });
    game.scores = data.scores;
    showLiveStatus("En direct · " + data.turn + " / " + game.turns + " · " + scoreLabel(game));
    buildReplay(game, state.move === previous ? undefined : state.move);
  } else if (data.type === "done" && game?.live) {
    state.liveStream?.close();
    state.liveStream = null;
    const finished = data.game;
    state.detail = { ...finished, live: true };
    output("game-subtitle", finished.levels.map(levelName).join(" × ") + " · Graine " + finished.seed + " · " + scoreLabel(finished));
    showLiveStatus("Duel terminé · " + (finished.winner === null ? "Égalité" : "Joueur " + (finished.winner + 1) + " gagne"));
    const file = { ...finished, external_id: "live-" + finished.seed + "-" + finished.levels.join("-") };
    state.liveFile = URL.createObjectURL(new Blob([JSON.stringify(file)], { type: "application/json" }));
    const link = $("live-export");
    link.href = state.liveFile;
    link.download = "lettrine-duel-" + finished.seed + ".json";
    link.hidden = false;
    buildReplay(state.detail, state.move);
  }
}

function openLive(event) {
  event.preventDefault();
  resetLive();
  stopReplay();
  ++state.detailRequest;
  state.detail = null;
  state.reviewCache.clear();
  state.challengeCache.clear();
  state.preview = null;
  output("game-title", "Duel en direct");
  output("game-subtitle", "");
  $("game-source").hidden = true;
  output("game-loading", "Préparation du duel…");
  $("game-loading").hidden = false;
  $("game-replay").hidden = true;
  const dialog = $("game-dialog");
  if (!dialog.open) dialog.showModal();
  const query = new URLSearchParams({ a: $("live-a").value, b: $("live-b").value });
  if ($("live-seed").value !== "") query.set("seed", $("live-seed").value);
  const stream = new EventSource("/api/live?" + query);
  state.liveStream = stream;
  stream.onmessage = (message) => {
    if (state.liveStream === stream && dialog.open) renderLive(JSON.parse(message.data));
  };
  stream.onerror = () => {
    if (state.liveStream !== stream || !dialog.open) return;
    stream.close();
    state.liveStream = null;
    if (state.detail?.live) showLiveStatus("Diffusion interrompue.");
    else output("game-loading", "Impossible de démarrer le duel.");
  };
}

function showImportStatus(kind, message) {
  const status = $("import-status");
  status.hidden = !message;
  status.dataset.state = kind;
  output("import-status", message);
}

async function importGames(event) {
  event.preventDefault();
  const input = $("import-file");
  const file = input.files?.[0];
  if (state.importing || !file) return;
  if (file.size > 32 * 1024 * 1024) {
    showImportStatus("error", "Le fichier dépasse la limite de 32 Mio.");
    return;
  }
  state.importing = true;
  input.disabled = true;
  $("import-submit").disabled = true;
  showImportStatus("busy", "Import en cours…");
  const contentType = /\.(jsonl|ndjson)$/i.test(file.name) ? "application/x-ndjson" : "application/json";
  try {
    const response = await fetch("/api/import", {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": contentType },
      body: file,
    });
    const result = await readResult(response);
    const imported = Number(result.imported) || 0;
    const duplicates = Number(result.duplicates) || 0;
    const message = imported ? format(imported, integer) + (imported === 1 ? " partie importée" : " parties importées") : "Aucune nouvelle partie";
    const skipped = duplicates ? " · " + format(duplicates, integer) + (duplicates === 1 ? " doublon ignoré" : " doublons ignorés") : "";
    showImportStatus("success", message + skipped + ".");
    input.value = "";
    output("import-filename", "Aucun fichier choisi");
    state.importOffset = 0;
    if (state.view === "parties" && state.source === "imports") loadImports();
  } catch (error) {
    showImportStatus("error", error.message || (error.status === 413 ? "Le fichier dépasse la limite de 32 Mio." : "Import impossible. Vérifiez le fichier et réessayez."));
  } finally {
    state.importing = false;
    input.disabled = false;
    $("import-submit").disabled = !input.files?.length;
  }
}

$("source-simulations").addEventListener("click", () => setGameSource("simulations"));
$("source-archives").addEventListener("click", () => setGameSource("archives"));
$("source-imports").addEventListener("click", () => setGameSource("imports"));
$("source-live").addEventListener("click", () => setGameSource("live"));
$("live-form").addEventListener("submit", openLive);
$("corpus-search").addEventListener("submit", searchCorpus);
$("view-imports-link").addEventListener("click", () => { state.source = "imports"; });
$("import-file").addEventListener("change", (event) => {
  $("import-submit").disabled = !event.target.files?.length;
  output("import-filename", event.target.files?.[0]?.name || "Aucun fichier choisi");
  showImportStatus("", "");
});
$("import-form").addEventListener("submit", importGames);
$("games-filters").addEventListener("submit", (event) => {
  event.preventDefault();
  state.offset = 0;
  state.search = $("games-query").value.trim();
  state.level = $("games-level").value;
  state.elite = $("games-elite").checked;
  loadGames();
});
$("games-elite").addEventListener("change", (event) => {
  if (event.target.checked) $("games-level").value = "";
  $("games-level").disabled = event.target.checked;
});
$("games-prev").addEventListener("click", () => {
  state.offset = Math.max(0, state.offset - pageSize);
  loadGames();
});
$("games-next").addEventListener("click", () => {
  state.offset += pageSize;
  loadGames();
});
$("imports-prev").addEventListener("click", () => {
  state.importOffset = Math.max(0, state.importOffset - pageSize);
  loadImports();
});
$("imports-next").addEventListener("click", () => {
  state.importOffset += pageSize;
  loadImports();
});
$("replay-step").addEventListener("input", (event) => jumpToMove(Number(event.target.value)));
$("replay-prev").addEventListener("click", () => jumpToMove(state.move - 1));
$("replay-next").addEventListener("click", () => jumpToMove(state.move + 1));
$("replay-play").addEventListener("click", toggleReplay);
$("game-dialog").addEventListener("close", () => {
  stopReplay();
  resetLive();
});
$("review-run").addEventListener("click", reviewMove);
$("challenge-run").addEventListener("click", challengeMove);
$("refresh").addEventListener("click", refreshStats);
window.addEventListener("hashchange", setView);
setView();
refreshStats();

async function poll() {
  await refreshStats();
  setTimeout(poll, state.stats?.running ? 3500 : 12000);
}
setTimeout(poll, 3500);
