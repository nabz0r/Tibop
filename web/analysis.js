"use strict";

const analysisState = { profile: "", data: null, request: 0, cell: 112 };
const premiumClasses = { dl: "Lettre double", tl: "Lettre triple", dw: "Mot double", tw: "Mot triple" };

function renderAnalysisSummary(stats = state.stats, metrics = analysisState.profile === state.profile ? analysisState.data : null) {
  const games = asNumber(stats?.completed);
  const turns = asNumber(metrics?.summary?.turns ?? stats?.turns);
  const actions = metrics?.actions ?? {};
  const plays = asNumber(metrics?.summary?.plays ?? (turns === null ? null : turns - Math.round(turns * (Number(stats?.pass_rate) + Number(stats?.exchange_rate)))));
  const countFromRate = (rate, sample) => asNumber(rate) !== null && sample > 0 ? Math.round(rate * sample) : null;
  const first = countFromRate(stats?.first_win_rate, games);
  const bingos = metrics?.summary?.bingos ?? stats?.bingo_count;
  const exchanges = actions.exchange?.count ?? countFromRate(stats?.exchange_rate, turns);
  const passes = actions.pass?.count ?? countFromRate(stats?.pass_rate, turns);
  const values = [
    ["metric-first", "metric-first-context", first, games, " parties", "Parties de la campagne. Les nulles comptent dans le total."],
    ["metric-meta-bingos", "metric-bingo-context", bingos, plays, " poses", "Une pose de sept jetons sur l’ensemble des poses, hors passes et échanges."],
    ["metric-exchanges", "metric-exchange-context", exchanges, turns, " tours", "Un échange compte pour un tour, quel que soit le nombre de jetons rendus."],
    ["metric-passes", "metric-pass-context", passes, turns, " tours", "Une passe ne pose aucun jeton et ne change pas le chevalet."],
  ];
  for (const [id, context, count, total, unit, tooltip] of values) {
    const n = asNumber(count);
    output(id, format(n, integer));
    output(context, n !== null && total > 0 ? t("sur ") + format(total, integer) + t(unit) + " · " + format(n / total, new Intl.NumberFormat(uiLocale, { style: "percent", maximumFractionDigits: 4 })) : "—");
    setTip($(id).closest(".metric"), t(tooltip));
  }
}

function metricRow(label, count, context, type) {
  const row = node("div", "analytics-row");
  const heading = node("strong", "", label);
  if (type) {
    const key = node("span", "analytics-premium " + type);
    key.setAttribute("aria-hidden", "true");
    heading.prepend(key);
  }
  row.append(heading, node("span", "analytics-count", count), node("small", "", context));
  return row;
}

function renderAnalysisDetails(metrics) {
  const summary = metrics.summary ?? {};
  output("opening-context", format(summary.openings, integer) + t(" ouvertures sur ") + format(summary.games, integer) + t(" parties indexées."));
  const openings = (metrics.openings ?? []).slice(0, 9).map((word) => metricRow(
    word.word,
    format(word.count, integer) + " / " + format(summary.games, integer),
    format(word.mean_score) + " " + t("pts à l’ouverture"),
  ));
  replaceRows($("opening-list"), openings, t("Aucune ouverture indexée."));
  const premium = metrics.premiums ?? {};
  const classes = Object.entries(premiumClasses).map(([id, label]) => {
    const value = premium.classes?.[id];
    const row = metricRow(t(label), format(value?.tiles, integer) + t(" jetons"), format(value?.turns, integer) + t(" poses · ") + format(value?.mean_score) + " " + t("pts en moyenne"), id);
    setTip(row, t("Score moyen du coup entier lorsqu’un jeton est posé sur ce bonus. Un coup peut utiliser plusieurs catégories."));
    return row;
  });
  $("premium-list").replaceChildren(...classes);
  const contexts = [[t("Avec bonus"), premium.with_premium], [t("Sans bonus"), premium.without_premium]].map(([label, value]) =>
    metricRow(label, format(value?.turns, integer) + t(" poses"), format(value?.mean_score) + " " + t("pts en moyenne")));
  $("premium-context").replaceChildren(...contexts);
  const exchanges = metrics.exchanges ?? {};
  output("exchanges-context", format(exchanges.count, integer) + t(" échanges · ") + format(exchanges.tiles, integer) + t(" jetons rendus."));
  const letters = Object.entries(exchanges.by_letter ?? {}).sort((a, b) => b[1] - a[1]).slice(0, 6)
    .map(([letter, count]) => metricRow(letter, format(count, integer), t("jetons rendus")));
  replaceRows($("exchange-letters"), letters, t("Aucun échange indexé."));
  const sizes = Object.entries(exchanges.by_size ?? {}).map(([size, count]) =>
    metricRow(format(size, integer) + t(Number(size) === 1 ? " jeton" : " jetons"), format(count, integer) + t(" échanges"), ""));
  $("exchange-sizes").replaceChildren(...sizes);
  renderHeat();
}

function heatDescription(cell) {
  const bonus = premiumClasses[cell.premium] ? t(premiumClasses[cell.premium]) : t("Case simple");
  return [String.fromCharCode(65 + cell.row) + (cell.col + 1), bonus, format(cell.count, integer) + t(" jetons posés"),
    format(cell.mean_score) + " " + t("pts en moyenne sur ces coups")].join(" · ");
}

function selectHeat(index, focus = false) {
  analysisState.cell = index;
  const cell = analysisState.data?.heatmap?.find((item) => item.row * 15 + item.col === index);
  if (!cell) return;
  output("heat-detail", heatDescription(cell));
  for (const button of $("heat-board").querySelectorAll("button")) {
    const selected = Number(button.dataset.cell) === index;
    button.setAttribute("aria-pressed", String(selected));
    button.tabIndex = selected ? 0 : -1;
    if (focus && selected) button.focus({ preventScroll: true });
  }
}

function renderHeat() {
  const cells = analysisState.data?.heatmap ?? [];
  const mode = $("heat-mode").value;
  const max = Math.max(1, ...cells.map((cell) => Number(cell[mode]) || 0));
  const buttons = cells.map((cell) => {
    const index = cell.row * 15 + cell.col;
    const button = node("button", "heat-cell", cell.premium ? cell.premium.toUpperCase() : "");
    button.type = "button";
    button.dataset.cell = String(index);
    button.style.setProperty("--heat", (Math.min(1, Math.max(0, (Number(cell[mode]) || 0) / max)) * 100) + "%");
    button.setAttribute("aria-label", heatDescription(cell));
    button.title = heatDescription(cell);
    button.addEventListener("click", () => selectHeat(index));
    button.addEventListener("keydown", (event) => {
      const row = cell.row, col = cell.col;
      const next = {
        ArrowLeft: row * 15 + Math.max(0, col - 1), ArrowRight: row * 15 + Math.min(14, col + 1),
        ArrowUp: Math.max(0, row - 1) * 15 + col, ArrowDown: Math.min(14, row + 1) * 15 + col,
      }[event.key];
      if (next !== undefined) {
        event.preventDefault();
        selectHeat(next, true);
      }
    });
    return button;
  });
  $("heat-board").replaceChildren(...buttons);
  selectHeat(analysisState.cell);
}

async function loadAnalysis() {
  if (analysisState.profile === state.profile && analysisState.data) {
    renderAnalysisDetails(analysisState.data);
    renderAnalysisSummary();
    return;
  }
  const request = ++analysisState.request;
  const profile = state.profile;
  $("analysis-data").hidden = true;
  $("analysis-status").hidden = false;
  output("analysis-status", t("Chargement des statistiques détaillées…"));
  try {
    const result = await get(profileUrl("/api/words"));
    if (request !== analysisState.request || state.profile !== profile) return;
    const metrics = result.metrics;
    if (!metrics?.summary?.games) {
      output("analysis-status", t("Ce profil attend encore son index statistique."));
      return;
    }
    analysisState.profile = profile;
    analysisState.data = metrics;
    renderAnalysisDetails(metrics);
    renderAnalysisSummary(state.stats, metrics);
    $("analysis-data").hidden = false;
    $("analysis-status").hidden = true;
  } catch {
    if (request === analysisState.request && state.profile === profile) output("analysis-status", t("Les statistiques détaillées sont momentanément indisponibles."));
  }
}
