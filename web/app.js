"use strict";

const $ = (id) => document.getElementById(id);
const links = Array.from(document.querySelectorAll("[data-view]"));
const defaultProfile = "fr-classic-v1";
const profileNames = { fr: "Français", en: "English", de: "Deutsch", ru: "Русский", "ja-JP": "日本語" };
const levelNames = ["Découverte", "Initié", "Stratège", "Expert"];
const matchModes = { solo: "Solo", local: "Multijoueur local", bluetooth: "Bluetooth" };
const defaultChallengeModel = "llama3.2:3b";
const pageSize = 20;
let number, integer, percent, precisePercent, signed;
const state = {
  view: "vue",
  profile: new URLSearchParams(location.search).get("profile") || defaultProfile,
  profiles: [],
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
  policyRequest: 0,
  policyTimer: null,
  refreshing: false,
  statsRequest: 0,
  importing: false,
  stats: null,
  metricsKey: null,
  model: null,
  benchmarks: null,
  commentaryBenchmarks: null,
  studies: null,
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
const profileLinks = [...document.querySelectorAll('a[href^="/api/export"], a[href="/api/models"]')]
  .map((link) => [link, link.getAttribute("href")]);

function profileUrl(path, query) {
  const url = new URL(path, location.origin);
  for (const [key, value] of new URLSearchParams(query)) url.searchParams.set(key, value);
  url.searchParams.set("profile", state.profile);
  return url.pathname + url.search;
}

function localizeProfile() {
  const profile = state.profiles.find((item) => item.id === state.profile);
  const english = profile ? profile.language === "en" : state.profile.startsWith("en-");
  setLanguage(english ? "en" : "fr");
  const locale = english ? "en-GB" : "fr-FR";
  number = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 });
  integer = new Intl.NumberFormat(locale, { maximumFractionDigits: 0 });
  percent = new Intl.NumberFormat(locale, { style: "percent", maximumFractionDigits: 1 });
  precisePercent = new Intl.NumberFormat(locale, { style: "percent", maximumFractionDigits: 2 });
  signed = new Intl.NumberFormat(locale, { signDisplay: "exceptZero", maximumFractionDigits: 1 });
  for (const [link, path] of profileLinks) link.href = profileUrl(path);
  $("source-archives").hidden = state.profile !== defaultProfile;
  if (telemetrySnapshot) renderTelemetry(telemetrySnapshot);
}

function chooseProfile(id) {
  if (id === state.profile) return;
  const profile = state.profiles.find((item) => item.id === id);
  if (!profile?.lexicon?.path) return;
  const previous = state.stats;
  state.profile = id;
  state.stats = null;
  state.model = null;
  state.benchmarks = null;
  state.commentaryBenchmarks = null;
  state.studies = null;
  state.offset = 0;
  state.search = "";
  $("games-query").value = "";
  if (id !== defaultProfile && state.source === "archives") state.source = "simulations";
  ++state.gameRequest;
  ++state.detailRequest;
  state.detail = null;
  if ($("game-dialog").open) $("game-dialog").close();
  const url = new URL(location.href);
  url.searchParams.set("profile", id);
  if (url.hash.startsWith("#parties?game=")) url.hash = "#parties";
  history.replaceState(null, "", url);
  localizeProfile();
  $("profile-select").value = id;
  $("trained-panel").hidden = true;
  $("commentary-tests").hidden = true;
  $("llm-benchmarks").replaceChildren();
  $("llm-elo-placeholder").hidden = false;
  $("corpus-results").replaceChildren();
  $("corpus-status").hidden = true;
  renderStats({ lexicon: profile.lexicon.name, ollama_model: previous?.ollama_model, ollama_models: previous?.ollama_models });
  setView();
  refreshStats();
}

async function loadProfiles() {
  try {
    const profiles = await get("/api/profiles");
    if (!Array.isArray(profiles)) return;
    state.profiles = profiles;
    const order = ["fr", "en", "de", "ru", "ja-JP"];
    const sorted = [...profiles].sort((a, b) => order.indexOf(a.language) - order.indexOf(b.language));
    const choices = sorted.map((profile) => {
      const available = !!profile.lexicon?.path && profile.status !== "draft";
      const name = profileNames[profile.language] || profile.name;
      const option = new Option(name + (available ? "" : " · " + t("Profil en préparation")), profile.id);
      option.disabled = !available;
      return option;
    });
    $("profile-select").replaceChildren(...choices);
    if (!choices.some((item) => item.value === state.profile && !item.disabled)) chooseProfile(defaultProfile);
    $("profile-select").value = state.profile;
  } catch {
    // The default profile is still accessible if discovery is temporarily unavailable.
  }
}

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

function setTip(element, text) {
  element.classList.add("has-tip");
  element.dataset.tip = text;
  element.title = text;
  if (!element.matches("a, button, input, select, svg, [tabindex]")) element.tabIndex = 0;
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
  return t(state.stats?.levels?.find((level) => Number(level.id) === Number(id))?.label
    ?? levelNames[Number(id)]
    ?? String(id ?? "—"));
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
    const row = node("a", "level-row");
    row.href = "#parties?level=" + encodeURIComponent(level.id);
    row.dataset.level = String(level.id);
    row.setAttribute("aria-label", levelName(level.id) + ", " + format(level.rating, integer) + " Elo");
    setTip(row, t("Elo relatif aux adversaires de la campagne de référence."));
    row.append(levelLabel(level.id), bar((level.rating ?? 0) / maxRating), node("span", "level-rating", format(level.rating, integer)));
    return row;
  });
  replaceRows($("overview-levels"), highlights, t("En attente des premiers résultats."));

  const table = $("meta-levels");
  if (!ordered.length) {
    emptyTable(table, 6, t("En attente des premiers résultats."));
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
  replaceRows($("meta-words"), rows, t("En attente des premiers résultats."));
}

function renderMatchups(pairs) {
  const rows = pairs.map(({ a, b, games, a_wins: aWins, b_wins: bWins, draws, confidence_interval_95: interval }) => {
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
      node("span", "", format(aWins, integer) + t(" victoires")),
      node("span", "", format(draws, integer) + t(" nuls · ") + format(games, integer) + t(" parties")),
      node("span", "", format(bWins, integer) + t(" victoires")),
    );
    const detail = node("p", "matchup-evidence", "n = " + format(games, integer) + " · " + t("Victoires par côté") + " " + format(aWins / games, percent) + " / " + format(bWins / games, percent));
    if (Array.isArray(interval)) detail.append(document.createTextNode(" · " + t("Intervalle à 95 % · ") + interval.map((value) => format(value, precisePercent)).join(t(" à "))));
    setTip(detail, t("Les deux côtés et les nulles sont comptés sur le même ensemble de duels."));
    row.append(labels, stack, summary, detail);
    return row;
  });
  replaceRows($("meta-matchups"), rows, t("En attente des premières confrontations."));
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
  const campaignLabel = { running: t("En cours"), done: t("Terminée"), idle: completed ? t("En pause") : t("En attente") }[campaignState];
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
  output("metric-bingos", format(stats.bingo_count, integer));
  output("kpi-bingo-context", stats.turns ? t("sur ") + format(stats.turns, integer) + t(" tours") : t("Poses de sept lettres"));
  $("kpi-best").href = stats.best_game?.id ? "#parties?game=" + encodeURIComponent(stats.best_game.id) : "#parties?sort=peak";
  setTip($("kpi-score"), t("Moyenne des scores finaux par joueur. Ouvrez le détail par niveau."));
  setTip($("kpi-best"), t("Plus haut score individuel d’une partie. Ouvrez le duel correspondant."));
  setTip($("kpi-turns"), t("Nombre total de tours divisé par les parties. Ouvrez les duels classés par longueur."));
  setTip($("kpi-bingos"), t("Nombre de coups posant sept jetons. Ouvrez les comptes et leur dénominateur."));
  renderAnalysisSummary(stats);
  output("admin-state", campaignLabel);
  output("admin-storage", { postgresql: "PostgreSQL", sqlite: "SQLite" }[stats.storage] ?? "—");
  output("admin-count", format(completed, integer));
  output("admin-elapsed", elapsed(stats.elapsed_seconds));
  output("admin-lexicon", stats.lexicon || "—");
  output("admin-lexicon-count", format(stats.lexicon_words, integer));
  output("sidebar-lexicon", stats.lexicon || t("Scrabble francophone"));
  output("local-model", stats.ollama_model || t("Llama local"));
  setChallengeModels(stats);
  const levels = completed ? stats.levels ?? [] : [];
  const maxElo = Math.max(700, ...levels.map((level) => Number(level.rating) || 0));
  $("live-target").min = String(stats.adaptive?.min ?? 700);
  $("policy-elo").min = $("live-target").min;
  if (maxElo > 700) {
    $("live-target").max = String(stats.adaptive?.max ?? maxElo);
    $("policy-elo").max = $("live-target").max;
  }
  output("policy-elo-value", format($("policy-elo").value, integer));
  refreshLiveForm();
  const pairs = (stats.matchups ?? []).filter((pair) => pair.games > 0);
  const metricsKey = state.profile + ":" + JSON.stringify([completed, levels, pairs, stats.words]);
  if (metricsKey !== state.metricsKey) {
    state.metricsKey = metricsKey;
    renderLevels(levels);
    renderMatchups(pairs);
    renderWords(stats.words ?? []);
  }
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
  const profile = state.profile;
  if (state.refreshing === profile) return;
  const request = ++state.statsRequest;
  state.refreshing = profile;
  $("refresh").disabled = true;
  try {
    const stats = await get(profileUrl("/api/stats"));
    if (request !== state.statsRequest) return;
    const previous = state.stats?.completed;
    state.stats = stats;
    renderStats(stats);
    $("connection").parentElement.dataset.state = "online";
    output("connection", t("Service local"));
    output("last-sync", t("Actualisé à ") + new Date().toLocaleTimeString(uiLocale, { timeStyle: "medium" }));
    if (state.view === "vue") loadCommentaryBenchmarks();
    if (previous !== stats.completed && state.view === "parties" && state.source === "simulations" && !state.offset && !state.search && !$("game-dialog").open) {
      loadGames();
    }
  } catch {
    if (request !== state.statsRequest) return;
    $("connection").parentElement.dataset.state = "offline";
    output("connection", t("Service indisponible"));
    output("last-sync", t("Le service local est momentanément indisponible."));
    $("run-status").dataset.state = "offline";
    output("run-status-label", t("Hors ligne"));
  } finally {
    if (request === state.statsRequest) {
      state.refreshing = false;
      $("refresh").disabled = false;
    }
  }
}

async function loadModel() {
  if (state.model) return;
  const profile = state.profile;
  try {
    const model = await get(profileUrl("/api/models"));
    if (profile !== state.profile || !model.holdout) return;
    state.model = model;
    output("trained-elo", format(model.holdout.elo_delta, signed) + " Elo");
    output("trained-match-rate", format(model.holdout.match_score_rate, precisePercent));
    output("trained-games", format(model.holdout.games, integer) + t(" duels de test"));
    $("trained-track").replaceChildren(bar(model.holdout.match_score_rate));
    $("trained-panel").hidden = false;
  } catch {
    // The dashboard still works with a campaign that predates policy training.
  }
}

function benchmarkRow(data) {
  const row = node("div", "llm-test");
  const head = node("div", "model-head");
  const title = data.model || t("Modèle local");
  const elo = asNumber(data.elo_delta);
  head.append(node("strong", "", title + t(" · Test de jeu")));
  if (elo !== null) head.append(node("strong", "model-elo", format(elo, signed) + t(" Elo estimés")));
  const score = node("p", "", format(data.games, integer) + t(" parties face à ") + (data.opponent || t("l’Expert")) + t(" · Score de match ") + format(data.match_score_rate, precisePercent));
  row.append(head, score);
  if (Array.isArray(data.score_interval_95)) {
    row.append(node("p", "", t("Intervalle à 95 % · ") + data.score_interval_95.map((value) => format(value, precisePercent)).join(t(" à "))));
  }
  row.append(node("p", "", t("Sélection expérimentale · ") + format(data.llm_choices, integer) + t(" coups choisis · ") + format(data.fallbacks, integer) + t(" replis sur le moteur")));
  return row;
}

async function loadBenchmarks() {
  if (state.benchmarks) return;
  const profile = state.profile;
  try {
    const result = await get(profileUrl("/api/llm-benchmarks"));
    if (profile !== state.profile || !Array.isArray(result.items) || !result.items.length) return;
    state.benchmarks = result.items;
    $("llm-elo-placeholder").hidden = true;
    $("llm-benchmarks").replaceChildren(...result.items.map(benchmarkRow));
  } catch {
    // Benchmark output is optional.
  }
}

function modelDuration(value) {
  const ms = asNumber(value);
  if (ms === null) return "—";
  return ms >= 1000 ? format(ms / 1000) + " s" : format(ms, integer) + " ms";
}

function benchmarkCount(value, total) {
  const count = asNumber(value);
  return count === null ? "—" : format(count, integer) + "/" + format(total, integer);
}

async function loadCommentaryBenchmarks() {
  if (state.commentaryBenchmarks) return;
  const profile = state.profile;
  state.commentaryBenchmarks = [];
  try {
    const result = await get(profileUrl("/api/commentary-benchmarks"));
    if (profile !== state.profile || !Array.isArray(result.items) || !result.items.length) return;
    state.commentaryBenchmarks = result.items;
    const rows = result.items.map((item) => {
      const row = node("tr");
      const title = node("th", "", item.model);
      title.scope = "row";
      row.append(title,
        td(benchmarkCount(item.valid, item.cases), "numeric"),
        td(benchmarkCount(item.grounded, item.cases), "numeric"),
        td(modelDuration(item.p50_ms), "numeric"),
        td(modelDuration(item.p95_ms), "numeric"));
      return row;
    });
    $("commentary-benchmarks").replaceChildren(...rows);
    $("commentary-tests").hidden = false;
  } catch {
    // Benchmark output is optional.
  } finally {
    if (profile === state.profile && !state.commentaryBenchmarks?.length) state.commentaryBenchmarks = null;
  }
}

async function loadStudies() {
  if (state.studies) return;
  const profile = state.profile;
  const container = $("study-list");
  replaceRows(container, [], t("En attente des premières études."));
  try {
    const result = await get(profileUrl("/api/studies"));
    if (profile !== state.profile || !Array.isArray(result.items)) return;
    state.studies = result.items;
    const rows = result.items.map((study) => {
      const row = node("article", "study-row");
      const title = node("div", "study-heading");
      title.append(node("h3", "", study.title), node("span", "", [study.date, study.kind].filter(Boolean).join(" · ")));
      row.append(title, node("p", "", study.summary));
      if (typeof study.artifact === "string" && study.artifact.startsWith("/api/")) {
        const link = node("a", "text-link", t("Données JSON") + " ↓");
        link.href = profileUrl(study.artifact);
        link.download = "";
        row.append(link);
      }
      return row;
    });
    replaceRows(container, rows, t("Aucune étude enregistrée pour ce profil."));
  } catch {
    if (profile === state.profile) replaceRows(container, [], t("Historique des recherches indisponible."));
  }
}

function gameLabel(id) {
  return t("Partie ") + String(id);
}

function winnerLabel(game) {
  if (game.winner === null || game.winner === -1 || game.winner === "draw") return t("Égalité");
  const index = asNumber(game.winner);
  return index === null ? "—" : playerName(game, index);
}

function scoreLabel(game) {
  return Array.isArray(game.scores) ? game.scores.map((score) => format(score, integer)).join(" – ") : "—";
}

function originLabel(game) {
  return game.source === "android" || game.origin === "android" ? "Android" : t("Fichier");
}

function importMetadata(game) {
  return [originLabel(game), t(matchModes[game.mode]), game.lexicon].filter(Boolean).join(" · ");
}

function gameRow(game, imported = false) {
  const row = node("tr");
  const identity = td("");
  identity.append(node("span", "game-id", "#" + String(game.id)));
  if (imported) identity.append(node("span", "game-seed", importMetadata(game)));
  else if (game.seed !== null && game.seed !== undefined) identity.append(node("span", "game-seed", t("Graine ") + String(game.seed)));
  const players = td("");
  (game.scores ?? []).forEach((_, index) => {
    if (index) players.append(node("span", "level-versus", "×"));
    players.append(imported ? node("span", "level-chip", playerName(game, index)) : levelLabel(game.levels?.[index], "level-chip"));
  });
  const action = td("");
  const button = node("button", "button secondary", t("Rejouer"));
  button.type = "button";
  button.setAttribute("aria-label", t("Rejouer la partie ") + String(game.id));
  button.addEventListener("click", () => openGame(game.id, imported));
  action.append(button);
  row.append(identity, players, td(scoreLabel(game), "numeric"), td(format(game.turns, integer), "numeric"), td(winnerLabel(game)), action);
  return row;
}

function matchCard(game) {
  const card = node("article", "match-card");
  const top = node("div", "match-card-top");
  top.append(node("strong", "", "#" + game.id), node("span", "", t("Graine ") + game.seed));
  const seats = node("div", "match-card-seats");
  (game.levels ?? []).forEach((level, seat) => {
    const side = node("div", "match-seat match-seat-" + seat);
    const rating = state.stats?.levels?.find((item) => Number(item.id) === Number(level))?.rating;
    const name = node("span", "match-level", levelName(level));
    const score = node("strong", "", format(game.scores?.[seat], integer));
    const label = node("span", "match-elo", rating != null ? format(rating, integer) + " Elo" : "");
    side.append(name, score, label);
    seats.append(side);
  });
  const bar = node("div", "match-scorebar");
  bar.setAttribute("aria-hidden", "true");
  const [a, b] = (game.scores ?? [0, 0]).map((score) => Math.max(0, Number(score) || 0));
  bar.style.setProperty("--seat-a", (a + b ? a / (a + b) * 100 : 50) + "%");
  const foot = node("div", "match-card-footer");
  const result = node("div", "match-result");
  const winner = asNumber(game.winner);
  const outcome = winner === null ? t("Égalité") : t("Joueur ") + (winner + 1) + t(" gagne");
  result.append(node("strong", "", outcome), node("span", "", format(game.turns, integer) + t(" tours")));
  const replay = node("button", "button secondary compact", t("Rejouer"));
  replay.type = "button";
  replay.setAttribute("aria-label", t("Rejouer la partie ") + game.id);
  replay.addEventListener("click", () => openGame(game.id));
  foot.append(result, replay);
  card.append(top, seats, bar, foot);
  return card;
}

function updatePages(source, offset, total, length) {
  const range = length > 0 ? format(offset + 1, integer) + "–" + format(offset + length, integer) + t(" sur ") : "";
  output(source + "-count", range + format(total, integer) + (total === 1 ? t(" partie") : t(" parties")));
  $(source + "-prev").disabled = offset === 0;
  $(source + "-next").disabled = offset + length >= total || length === 0;
}

function pageError(source, message) {
  const rows = $(source + "-rows");
  if (source === "games") replaceRows(rows, [], message);
  else emptyTable(rows, 6, message);
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
  const [sort, order] = $("games-sort").value.split(":");
  if (sort !== "recent" || order !== "desc") {
    query.set("sort", sort);
    query.set("order", order);
  }
  try {
    const result = await get(profileUrl("/api/games", query));
    if (request !== state.gameRequest) return;
    const games = result.items ?? [];
    replaceRows($("games-rows"), games.map(matchCard), t("Aucune partie ne correspond à ces filtres."));
    updatePages("games", state.offset, Number(result.total) || 0, games.length);
  } catch {
    if (request !== state.gameRequest) return;
    pageError("games", t("Impossible de charger les parties. Réessayez avec le bouton Filtrer."));
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
    else emptyTable($("imports-rows"), 6, t("Les parties ajoutées dans Administration apparaîtront ici."));
    updatePages("imports", state.importOffset, Number(result.total) || 0, games.length);
  } catch {
    if (request !== state.importRequest) return;
    pageError("imports", t("Impossible de charger les parties importées."));
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
    } else if (position.get("live") === "1") {
      setGameSource("live");
    } else if (position.has("level") || position.has("sort")) {
      state.offset = 0;
      state.search = "";
      state.elite = false;
      state.level = position.get("level") ?? "";
      $("games-query").value = "";
      $("games-elite").checked = false;
      $("games-level").disabled = false;
      $("games-level").value = state.level;
      const sort = position.get("sort") || "recent";
      $("games-sort").value = [...$("games-sort").options].some((option) => option.value === sort + ":desc") ? sort + ":desc" : "recent:desc";
      setGameSource("simulations");
    } else {
      setGameSource(state.source);
    }
  }
  if (selected === "vue") {
    loadModel();
    loadBenchmarks();
    loadCommentaryBenchmarks();
    loadStudies();
  }
  if (selected === "decouverte") loadLegacy();
  if (selected === "meta") {
    loadAnalysis();
    const section = new URLSearchParams(query).get("section");
    const target = { rating: "rating-title", bingos: "metric-meta-bingos" }[section];
    if (target) requestAnimationFrame(() => $(target).scrollIntoView({ block: "center" }));
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
  if (!move) return t("Position de départ");
  if (move.type === "invalid") return move.word ? move.word + t(" (refusé)") : t("Coup refusé");
  if (move.word) return move.word;
  if (["pass", "skip"].includes(move.type)) return t("Passe");
  if (["exchange", "swap"].includes(move.type)) return t("Échange");
  return t("Placement");
}

function tileIndex(tile) {
  const row = asNumber(tile.row);
  const col = asNumber(tile.col);
  if (!Number.isInteger(row) || !Number.isInteger(col) || row < 0 || row >= 15 || col < 0 || col >= 15) return null;
  return row * 15 + col;
}

function renderBoard(step, preview) {
  const moves = state.detail?.moves ?? [];
  const game = state.detail;
  const profile = state.profiles.find((item) => game?.language ? item.language === game.language : item.id === state.profile);
  const points = profile?.tiles ? new Map(profile.tiles.map((tile) => [tile.symbol, tile.points])) : null;
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
      cell.replaceChildren(document.createTextNode(letter), node("small", "", String(tile.blank ? 0 : points?.get(letter) ?? tilePoints[letter] ?? "")));
    } else if (index === 112) {
      cell.textContent = "★";
    } else {
      cell.textContent = "";
    }
  }
  const position = preview ? t(", variante ") + preview.word : t(", après le coup ") + step;
  $("board").setAttribute("aria-label", t("Plateau du Scrabble") + position + ", " + board.size + t(" lettres"));
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
    reviewField(t("Coup joué"), [review.played_word, format(review.played_score, integer) + " pts"].filter(Boolean).join(" · ")),
    reviewField(state.detail?.archive ? t("Maximum indiqué") : t("Meilleur score"), format(review.best_score, integer) + " pts"),
    reviewField(t("Écart"), format(review.points_left, integer) + " pts"),
  ];
  if (review.best_word) summary.push(reviewField(t("Meilleur coup publié"), review.best_word));
  if (asNumber(review.played_rank) !== null) summary.push(reviewField(t("Rang du coup"), "#" + format(review.played_rank, integer)));
  if (review.position_rack) {
    const rack = Array.isArray(review.position_rack) ? review.position_rack.join(" ") : String(review.position_rack);
    summary.push(reviewField(t("Chevalet avant le coup"), rack));
  }
  if (asNumber(review.legal_moves) !== null) summary.push(reviewField(t("Placements légaux"), format(review.legal_moves, integer)));
  if (asNumber(review.samples) !== null) summary.push(reviewField(t("Tirages simulés"), format(review.samples, integer)));
  $("review-summary").replaceChildren(...summary);
  const options = (review.alternatives ?? []).map((choice) => {
    const row = node("li");
    const button = node("button", "review-option");
    button.type = "button";
    button.setAttribute("aria-pressed", String(state.preview === choice));
    setTip(button, t("Afficher cette variante sur le plateau à la place du coup joué."));
    const details = node("span", "review-choice");
    const played = state.detail?.moves?.[state.move - 1];
    details.append(node("strong", "", choice.word || moveLabel(played)));
    const leave = choice.leave === "" ? t("Chevalet vidé") : choice.leave ? t("Reliquat ") + choice.leave : "";
    const notes = [choice.played ? t("Joué") : "", leave].filter(Boolean);
    if (notes.length) details.append(node("small", "", notes.join(" · ")));
    const scores = node("span", "review-choice-scores");
    scores.append(node("strong", "", format(choice.score, integer) + " pts"));
    if (asNumber(choice.score) !== null && asNumber(review.played_score) !== null) {
      scores.append(node("small", "review-delta", t("Écart de points") + " " + format(choice.score - review.played_score, signed)));
    }
    if (asNumber(choice.evaluation) !== null) {
      const margin = t("Écart estimé ") + format(choice.evaluation, signed);
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
  $("policy-controls").hidden = archive || !move;
  setTip($("policy-controls").firstElementChild, t("Choix du moteur à cet Elo, sur ce même chevalet."));
  if (archive) {
    output("review-status", move ? t("Maximum signalé dans le relevé d’origine.") : t("Sélectionnez un coup à examiner."));
    renderReview(move && asNumber(move.top_score) !== null ? {
      played_score: move.score,
      played_word: moveLabel(move),
      best_score: move.top_score,
      best_word: move.top_word,
      points_left: move.top_score - move.score,
      position_rack: move.rack,
      method: t("Valeurs publiées pour le lexique historique ") + state.detail.dictionary + ".",
    } : null);
    if (move && asNumber(move.top_score) === null) output("review-status", t("Le relevé ne renseigne pas de maximum pour ce coup."));
    return;
  }
  const cached = state.reviewCache.get(state.move);
  $("review-run").disabled = !move;
  $("review-run").textContent = cached ? t("Recalculer") : t("Analyser");
  output("review-status", cached ? t("Sélectionnez une variante pour la placer sur le plateau.") : move ? t("Comparez ce coup aux placements disponibles.") : t("Sélectionnez un coup à examiner."));
  renderReview(cached);
  renderChallenge();
}

function setChallengeModels(stats) {
  const models = Array.isArray(stats.ollama_models) ? stats.ollama_models.filter((tag) => typeof tag === "string" && tag) : [];
  const tags = [...new Set(models.length ? models : [stats.ollama_model].filter(Boolean))];
  const select = $("challenge-select");
  const previous = select.value;
  if (tags.length === select.options.length && tags.every((tag, i) => tag === select.options[i].value)) return;
  select.replaceChildren(...tags.map((tag) => new Option(tag, tag)));
  const preferred = [previous, defaultChallengeModel, stats.ollama_model].find((tag) => tags.includes(tag));
  if (preferred) select.value = preferred;
  renderChallenge();
}

function challengeKey(turn, model) {
  return turn + ":" + model;
}

function setPolicyStatus(message) {
  $("policy-status").hidden = !message;
  output("policy-status", message);
}

async function previewPolicy() {
  stopReplay();
  clearTimeout(state.policyTimer);
  const { detail, move: step } = state;
  const move = detail?.moves?.[step - 1];
  if (!move || detail.archive || detail.imported || detail.live) return;
  const request = ++state.policyRequest;
  const elo = $("policy-elo").value;
  $("policy-run").disabled = true;
  setPolicyStatus(t("Calcul de la variante…"));
  try {
    const position = await get(profileUrl("/api/games/" + encodeURIComponent(detail.id) + "/policy", { turn: move.turn ?? step, elo }));
    if (request !== state.policyRequest || detail !== state.detail || step !== state.move) return;
    state.preview = position;
    for (const item of $("review-options").querySelectorAll("button")) item.setAttribute("aria-pressed", "false");
    renderBoard(step, position);
    updateReplayCaption();
    const difference = asNumber(position.score) === null ? "" : " · " + t("Écart de points") + " " + format(position.score - move.score, signed);
    setPolicyStatus(format(position.target_elo ?? elo, integer) + " Elo" + difference);
  } catch (error) {
    if (request === state.policyRequest && detail === state.detail) setPolicyStatus(error.message || t("Variante indisponible pour cet Elo."));
  } finally {
    if (request === state.policyRequest) $("policy-run").disabled = false;
  }
}

function renderChallenge() {
  const select = $("challenge-select");
  const model = select.value;
  const data = state.challengeCache.get(challengeKey(state.move, model));
  const pending = state.pendingChallenge;
  const active = pending?.detail === state.detail;
  select.disabled = !model;
  $("challenge-run").disabled = !state.move || !model || active;
  $("challenge-run").textContent = data?.commentary ? t("Relancer l’analyse") : t("Challenger le coup");
  const status = active ? t("Analyse en cours avec ") + pending.model + "…" : data?.error || (!model ? t("Aucun modèle d’analyse configuré.") : "");
  $("challenge-status").hidden = !status;
  output("challenge-status", status || "");
  $("challenge-result").hidden = !data?.commentary;
  output("challenge-model", data?.model || model);
  output("challenge-commentary", data?.commentary || "");
}

async function challengeMove() {
  stopReplay();
  const { detail, move: step } = state;
  const move = detail?.moves?.[step - 1];
  const model = $("challenge-select").value;
  if (!move || !model || detail.archive || detail.imported || detail.live || state.pendingChallenge?.detail === detail) return;
  const key = challengeKey(step, model);
  const pending = { detail, model };
  state.pendingChallenge = pending;
  state.challengeCache.delete(key);
  renderChallenge();
  const query = new URLSearchParams({ turn: move.turn ?? step, model });
  try {
    const result = await get(profileUrl("/api/games/" + encodeURIComponent(detail.id) + "/challenge", query));
    if (detail === state.detail) state.challengeCache.set(key, result);
  } catch (error) {
    if (detail === state.detail) {
      state.challengeCache.set(key, { error: error.message || t("Le modèle est indisponible sur ce Mac.") });
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
  output("review-status", t("Calcul des placements disponibles…"));
  const turn = encodeURIComponent(move.turn ?? step);
  try {
    const review = await get(profileUrl("/api/games/" + encodeURIComponent(detail.id) + "/review", { turn }));
    if (detail !== state.detail || !$("game-dialog").open) return;
    state.reviewCache.set(step, review);
    if (request !== state.reviewRequest || state.move !== step) return;
    refreshReviewPanel();
  } catch {
    if (request !== state.reviewRequest || detail !== state.detail || state.move !== step) return;
    $("review-run").disabled = false;
    output("review-status", t("Analyse indisponible pour ce coup. Réessayez."));
  }
}

function updateReplayCaption() {
  const moves = state.detail?.moves ?? [];
  const current = moves[state.move - 1];
  let caption = state.move ? t("Coup ") + state.move + " / " + moves.length + " · " + moveLabel(current) : t("Position de départ");
  if (state.preview) caption += t(" · Variante ") + (state.preview.word || moveLabel(current)) + " (" + format(state.preview.score, integer) + " pts)";
  output("replay-caption", caption);
}

function playerName(game, seat) {
  const level = asNumber(game.levels?.[seat]);
  return game.names?.[seat] ?? game.players?.[seat] ?? game.labels?.[seat] ?? (level !== null && level >= 0 ? levelName(level) : t("Joueur ") + (Number(seat) + 1));
}

function letterRack(label, value) {
  const rack = Array.isArray(value) ? value.join("") : String(value ?? "");
  const line = node("div", "position-rack");
  const tiles = node("span", "rack-tiles");
  tiles.setAttribute("aria-label", rack.replaceAll("?", uiLocale === "en" ? " blank " : " joker "));
  tiles.append(...Array.from(rack, (letter) => node("span", "", letter)));
  line.append(node("span", "", label), rack ? tiles : node("span", "", "—"));
  return line;
}

function positionDetails(moves) {
  const game = state.detail;
  const current = moves[state.move - 1];
  const telemetry = game.timeline?.get(state.move) ?? current;
  const elements = [];
  if (current) {
    if (telemetry?.rack != null) elements.push(letterRack(t("Chevalet avant le coup"), telemetry.rack));
    if (telemetry?.draw != null) {
      const draw = letterRack(t("Jetons piochés"), telemetry.draw);
      const explanation = typeof game.timelineMethod === "object" && game.timelineMethod?.draw;
      setTip(draw, t(explanation || "Jetons effectivement reçus après ce tour."));
      elements.push(draw);
    }
    if (asNumber(telemetry?.blank_draw_probability) !== null) {
      elements.push(node("p", "", t("Chance de piocher un joker · ") + format(telemetry.blank_draw_probability, percent)));
    }
    if (asNumber(telemetry?.bag_after) !== null) {
      elements.push(node("p", "", t("Sac après le coup · ") + format(telemetry.bag_after, integer) + t(" jetons")));
    } else if (asNumber(current.bag_before) !== null) {
      elements.push(node("p", "", t("Sac avant le coup · ") + format(current.bag_before, integer) + t(" jetons")));
    }
    if (current.coordinate) elements.push(node("p", "", t("Position · ") + current.coordinate));
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
    elements.push(node("p", "", t("Ajustements finaux · ") + game.adjustments.map((score) => (score > 0 ? "+" : "") + format(score, integer)).join(" / ")));
  }
  $("position-state").replaceChildren(...elements);
}

function svgElement(name, attributes) {
  const element = document.createElementNS("http://www.w3.org/2000/svg", name);
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, value);
  return element;
}

function renderPlot(target, rows, turns, current, bounds, label, values, tip, tickFormat) {
  const holder = $(target);
  const focused = holder.contains(document.activeElement);
  const chart = svgElement("svg", { viewBox: "0 0 252 76", role: "group", "aria-label": label, tabindex: "0" });
  const explanation = svgElement("title", {});
  explanation.textContent = tip;
  chart.append(explanation);
  chart.addEventListener("click", (event) => {
    const { left, width } = chart.getBoundingClientRect();
    jumpToMove(Math.round(((event.clientX - left) / width * 252 - 32) / 214 * turns));
  });
  chart.addEventListener("keydown", (event) => {
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      jumpToMove(state.move + (event.key === "ArrowLeft" ? -1 : 1));
    }
  });
  const [min, max] = bounds;
  const x = (turn) => 32 + 214 * turn / Math.max(turns, 1);
  const y = (value) => 63 - 56 * (Math.min(max, Math.max(min, value)) - min) / Math.max(max - min, 1);
  for (const value of [min, (min + max) / 2, max]) {
    const line = y(value);
    chart.append(svgElement("line", { class: "plot-grid", x1: 32, x2: 246, y1: line, y2: line }));
    const tick = svgElement("text", { x: 28, y: line + 2, "text-anchor": "end" });
    tick.textContent = tickFormat(value);
    chart.append(tick);
  }
  for (const turn of [0, turns]) {
    const tick = svgElement("text", { x: x(turn), y: 74, "text-anchor": turn ? "end" : "start" });
    tick.textContent = format(turn, integer);
    chart.append(tick);
  }
  const cursor = x(current);
  chart.append(svgElement("line", { class: "plot-cursor", x1: cursor, x2: cursor, y1: 7, y2: 63 }));
  for (const seat of [0, 1]) {
    const points = rows.flatMap((row) => {
      const value = asNumber(values(row)?.[seat]);
      return value === null ? [] : [[x(row.turn), y(value)]];
    });
    if (points.length) chart.append(svgElement("polyline", { class: "plot-player-" + seat, points: points.join(" "), fill: "none" }));
    const selected = asNumber(values(rows[current] ?? {})?.[seat]);
    if (selected !== null) chart.append(svgElement("circle", { class: "plot-dot-" + seat, cx: cursor, cy: y(selected), r: 3 }));
  }
  holder.replaceChildren(chart);
  if (focused) chart.focus({ preventScroll: true });
}

function renderTimeline(moves) {
  const game = state.detail;
  const panel = $("match-timeline");
  panel.hidden = !moves.length || !!(game.archive || game.imported) || game.scores?.length !== 2;
  if (panel.hidden) return;
  const rows = [{ turn: 0, scores: [0, 0] }];
  const running = [0, 0];
  moves.forEach((move, index) => {
    const turn = index + 1;
    running[move.player] += Number(move.score) || 0;
    const telemetry = game.timeline?.get(turn) ?? move;
    rows.push({ turn, scores: telemetry.scores ?? [...running], probability: telemetry.win_probability });
  });
  const turns = Math.max(game.turns || 0, moves.length);
  const scores = rows.flatMap((row) => row.scores);
  const current = rows[state.move];
  const chances = rows.some((row) => Array.isArray(row.probability));
  $("chance-panel").hidden = !chances;
  const legend = [0, 1].map((seat) => {
    const item = node("span", "timeline-player plot-key-" + seat);
    const probability = asNumber(current.probability?.[seat]);
    item.append(node("strong", "", playerName(game, seat)), node("span", "", format(current.scores[seat], integer) + " pts" + (probability === null ? "" : " · " + format(probability, percent))));
    if (seat === 0 && game.targetElo) {
      const tier = eloTier(game.targetElo);
      const badge = node("span", "elo-tier", t(tier.label));
      badge.dataset.tier = tier.id;
      item.append(badge);
    }
    return item;
  });
  $("timeline-legend").replaceChildren(...legend);
  renderPlot("score-chart", rows, turns, state.move, [Math.min(0, ...scores), Math.max(1, ...scores)], t("Score cumulé"), (row) => row.scores, t("Score de chaque joueur après chaque tour. Cliquez ou utilisez les flèches pour parcourir la partie."), (value) => format(value, integer));
  if (chances) renderPlot("chance-chart", rows, turns, state.move, [0, 1], t("Chances de victoire"), (row) => row.probability, t("Estimation du moteur après chaque coup. Cliquez ou utilisez les flèches pour parcourir la partie."), (value) => Math.round(value * 100) + "%");
  const method = typeof game.timelineMethod === "string" ? game.timelineMethod : game.timelineMethod?.win;
  output("timeline-method", chances ? t(method || "Probabilités estimées à chaque coup.") : "");
}

function replayAt(step) {
  ++state.policyRequest;
  clearTimeout(state.policyTimer);
  $("policy-run").disabled = false;
  setPolicyStatus("");
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
  renderTimeline(moves);
  refreshReviewPanel();
}

function stopReplay() {
  clearInterval(state.playTimer);
  state.playTimer = null;
  $("replay-play").setAttribute("aria-pressed", "false");
  $("replay-play").setAttribute("aria-label", t("Lire la partie"));
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
  $("replay-play").setAttribute("aria-label", t("Mettre en pause"));
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
  output("game-loading", t("Chargement de la partie…"));
  $("game-loading").hidden = false;
  $("game-replay").hidden = true;
  const dialog = $("game-dialog");
  if (!dialog.open) dialog.showModal();
  try {
    const path = (imported ? "/api/imports/" : "/api/games/") + encodeURIComponent(id);
    const game = await get(imported ? path : profileUrl(path));
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
    if (!imported) loadGameTimeline(state.detail);
  } catch {
    if (request !== state.detailRequest || !dialog.open) return;
    output("game-loading", t("Impossible de charger cette partie."));
  }
}

async function loadGameTimeline(game) {
  try {
    const timeline = await get(profileUrl("/api/games/" + encodeURIComponent(game.id) + "/timeline"));
    if (state.detail !== game || !Array.isArray(timeline.items)) return;
    game.timeline = new Map(timeline.items.map((item) => [item.turn, item]));
    game.timelineMethod = typeof timeline.method === "string" ? { win: timeline.method, draw: timeline.draw_method } : timeline.method;
    positionDetails(game.moves);
    renderTimeline(game.moves);
  } catch {
    // Running scores remain available from the replay log.
  }
}

function sourceLink(url, title) {
  if (typeof url !== "string" || !url.startsWith("https://")) return null;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  const link = node("a", "text-link", title + " ↗");
  link.href = parsed.href;
  link.target = "_blank";
  link.rel = "noopener noreferrer";
  return link;
}

function renderCorpusResult(item) {
  const article = node("article", "corpus-result");
  const body = String(item.body ?? "");
  article.append(node("h3", "", item.title || t("Document")));
  if (body.length > 300) {
    const details = node("details");
    details.append(node("summary", "", body.slice(0, 285).trimEnd() + "…"), node("p", "", body));
    article.append(details);
  } else {
    article.append(node("p", "", body));
  }
  if (item.source?.startsWith("/#parties?")) {
    const link = node("a", "text-link", t("Ouvrir la position →"));
    link.href = state.profile === defaultProfile ? item.source.slice(1) : "/?profile=" + defaultProfile + item.source.slice(1);
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
  output("corpus-status", t("Recherche en cours…"));
  results.replaceChildren();
  try {
    const result = await get("/api/search?" + new URLSearchParams({ q: query }));
    const items = Array.isArray(result.items) ? result.items : [];
    output("corpus-status", items.length ? format(items.length, integer) + (items.length === 1 ? t(" résultat") : t(" résultats")) : t("Aucun résultat. Essayez un autre thème."));
    results.replaceChildren(...items.map(renderCorpusResult));
  } catch (error) {
    output("corpus-status", error.message || t("Recherche indisponible pour le moment."));
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
  const marks = node("div", "archive-marks");
  for (const name of game.players) {
    const initials = name.split(/\s+/).slice(0, 2).map((word) => word[0]).join("");
    marks.append(node("span", "master-mark", initials));
  }
  marks.setAttribute("aria-hidden", "true");
  const summary = node("div");
  summary.append(node("h3", "", game.title), node("p", "", game.players.join(" × ")));
  const metadata = [game.dictionary, format(game.turns?.length, integer) + " coups", scoreLabel({ scores: game.final_scores ?? game.archive_score_totals })].filter(Boolean);
  summary.append(node("p", "archive-meta", metadata.join(" · ")));
  const button = node("button", "button secondary", t("Étudier les coups"));
  button.type = "button";
  button.setAttribute("aria-label", t("Étudier les coups de ") + game.title);
  button.addEventListener("click", () => openExpert(game));
  item.append(marks, summary, button);
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
    round.append(node("span", "", t("Partie ") + (index + 1)), node("strong", "", format(a, integer) + " – " + format(b, integer)));
    scores.append(round);
  }
  item.append(scores);
  const highlight = reference.highlight;
  if (highlight) {
    const play = node("div", "reference-highlight");
    const title = node("h4", "", highlight.word + " en " + highlight.coordinate + " · " + format(highlight.score, integer) + " points");
    play.append(title, node("p", "", highlight.analysis));
    const link = sourceLink(highlight.source, t("Analyse du coup"));
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

function refreshLiveForm() {
  const adaptive = $("live-mode").value === "adaptive";
  $("live-a-wrap").hidden = adaptive;
  $("live-target-wrap").hidden = !adaptive;
  output("live-target-value", format($("live-target").value, integer));
  const tier = eloTier(Number($("live-target").value));
  $("live-tier").dataset.tier = tier.id;
  output("live-tier", "NAB · " + t(tier.label));
  setTip($("live-tier"), t("Progression visuelle sur l’échelle Elo du laboratoire."));
}

function eloTier(elo) {
  const ratings = (state.stats?.levels ?? []).map((level) => asNumber(level.rating)).filter((rating) => rating !== null).sort((a, b) => a - b);
  const anchors = ratings.length === 4 ? ratings : [748, 1016, 1409, 1627];
  const boundaries = anchors.slice(1).map((rating, index) => (rating + anchors[index]) / 2);
  const tiers = [
    { id: "sprout", label: "Pousse" }, { id: "wood", label: "Bois" },
    { id: "jade", label: "Jade" }, { id: "obsidian", label: "Obsidienne" },
  ];
  return tiers[boundaries.filter((threshold) => elo >= threshold).length];
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
    const labels = data.levels.map(levelName);
    if (data.target_elo != null) labels[data.adaptive_seat ?? 0] = "NAB · " + format(data.target_elo, integer);
    state.detail = { live: true, seed: data.seed, levels: data.levels, labels, targetElo: data.target_elo, timelineMethod: data.method, scores: [0, 0], moves: [] };
    output("game-subtitle", labels.join(" × ") + " · " + t("Graine ") + data.seed);
    showLiveStatus("En direct · 0 / " + data.turns);
    state.detail.turns = data.turns;
    buildReplay(state.detail);
  } else if (data.type === "move" && game?.live) {
    const previous = game.moves.length;
    game.moves.push({ ...data, type: data.action });
    game.scores = data.scores;
    showLiveStatus(t("En direct · ") + data.turn + " / " + game.turns + " · " + scoreLabel(game));
    buildReplay(game, state.move === previous ? undefined : state.move);
  } else if (data.type === "done" && game?.live) {
    state.liveStream?.close();
    state.liveStream = null;
    const finished = data.game;
    state.detail = { ...finished, live: true, labels: game.labels, targetElo: game.targetElo, timelineMethod: game.timelineMethod, timeline: new Map(game.moves.map((move) => [move.turn, move])) };
    output("game-subtitle", game.labels.join(" × ") + " · " + t("Graine ") + finished.seed + " · " + scoreLabel(finished));
    showLiveStatus(t("Duel terminé · ") + (finished.winner === null ? t("Égalité") : t("Joueur ") + (finished.winner + 1) + t(" gagne")));
    const externalId = ["live", state.profile, finished.seed, ...finished.levels, game.targetElo ?? "fixed"].join("-");
    const file = { ...finished, profile: state.profile, target_elo: game.targetElo, external_id: externalId };
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
  output("game-title", t("Duel en direct"));
  output("game-subtitle", "");
  $("game-source").hidden = true;
  output("game-loading", t("Préparation du duel…"));
  $("game-loading").hidden = false;
  $("game-replay").hidden = true;
  const dialog = $("game-dialog");
  if (!dialog.open) dialog.showModal();
  const query = new URLSearchParams({ a: $("live-a").value, b: $("live-b").value });
  if ($("live-mode").value === "adaptive") query.set("elo", $("live-target").value);
  if ($("live-seed").value !== "") query.set("seed", $("live-seed").value);
  const stream = new EventSource(profileUrl("/api/live", query));
  state.liveStream = stream;
  stream.onmessage = (message) => {
    if (state.liveStream === stream && dialog.open) renderLive(JSON.parse(message.data));
  };
  stream.onerror = () => {
    if (state.liveStream !== stream || !dialog.open) return;
    stream.close();
    state.liveStream = null;
    if (state.detail?.live) showLiveStatus(t("Diffusion interrompue."));
    else output("game-loading", t("Impossible de démarrer le duel."));
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
    showImportStatus("error", t("Le fichier dépasse la limite de 32 Mio."));
    return;
  }
  state.importing = true;
  input.disabled = true;
  $("import-submit").disabled = true;
  showImportStatus("busy", t("Import en cours…"));
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
    const message = imported ? format(imported, integer) + (imported === 1 ? t(" partie importée") : t(" parties importées")) : t("Aucune nouvelle partie");
    const skipped = duplicates ? " · " + format(duplicates, integer) + (duplicates === 1 ? t(" doublon ignoré") : t(" doublons ignorés")) : "";
    showImportStatus("success", message + skipped + ".");
    input.value = "";
    output("import-filename", t("Aucun fichier choisi"));
    state.importOffset = 0;
    if (state.view === "parties" && state.source === "imports") loadImports();
  } catch (error) {
    showImportStatus("error", error.message || (error.status === 413 ? t("Le fichier dépasse la limite de 32 Mio.") : t("Import impossible. Vérifiez le fichier et réessayez.")));
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
$("live-mode").addEventListener("change", refreshLiveForm);
$("live-target").addEventListener("input", refreshLiveForm);
$("profile-select").addEventListener("change", (event) => chooseProfile(event.target.value));
$("corpus-search").addEventListener("submit", searchCorpus);
$("heat-mode").addEventListener("change", renderHeat);
$("view-imports-link").addEventListener("click", () => { state.source = "imports"; });
$("import-file").addEventListener("change", (event) => {
  $("import-submit").disabled = !event.target.files?.length;
  output("import-filename", event.target.files?.[0]?.name || t("Aucun fichier choisi"));
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
$("games-sort").addEventListener("change", () => {
  state.offset = 0;
  loadGames();
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
$("challenge-select").addEventListener("change", renderChallenge);
$("policy-elo").addEventListener("input", (event) => output("policy-elo-value", format(event.target.value, integer)));
$("policy-elo").addEventListener("change", () => {
  clearTimeout(state.policyTimer);
  state.policyTimer = setTimeout(previewPolicy, 300);
});
$("policy-run").addEventListener("click", previewPolicy);
$("refresh").addEventListener("click", refreshStats);
window.addEventListener("hashchange", setView);
localizeProfile();
setView();
refreshStats();
loadProfiles();
refreshTelemetry();

async function poll() {
  await refreshStats();
  setTimeout(poll, state.stats?.running ? 3500 : 12000);
}
setTimeout(poll, 3500);
