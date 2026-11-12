"use strict";

let telemetrySnapshot = null;
let legacySnapshot = null;
let worldGeometry = null;
const serviceNames = { dashboard: "UI", api: "API", postgres: "PostgreSQL", pgvector: "pgvector", redis: "Redis", worker: "Worker", beat: "Beat", ollama: "Ollama" };

function memoryLabel(value) {
  const mb = asNumber(value);
  if (mb === null) return "";
  const gib = mb >= 1024;
  return format(gib ? mb / 1024 : mb) + (gib ? (uiLocale === "en" ? " GB" : " Go") : (uiLocale === "en" ? " MB" : " Mo"));
}

function telemetryTime(value) {
  const timestamp = typeof value === "number" && value < 1e12 ? value * 1000 : value;
  const date = timestamp ? new Date(timestamp) : null;
  return date && Number.isFinite(date.getTime()) ? date.toLocaleTimeString(uiLocale, { timeStyle: "medium" }) : "";
}

function renderTelemetry(data) {
  const focusedService = document.activeElement?.dataset.service;
  const services = (data.services ?? []).map((service) => {
    const id = String(service.id);
    const status = String(service.status ?? "").toLowerCase();
    const up = ["ok", "healthy", "running", "active", "available", "online", "up"].includes(status);
    const down = ["offline", "unreachable", "unhealthy", "down", "error", "stopped", "unavailable"].includes(status);
    const chip = node("span", "service-chip");
    chip.dataset.service = id;
    chip.dataset.status = up ? "up" : down ? "down" : "unknown";
    const label = serviceNames[id] || id;
    chip.append(node("i"), node("strong", "", label));
    const detail = service.detail || service.version;
    setTip(chip, [label, up ? t("Disponible") : down ? t("Indisponible") : t("Inconnu"), detail].filter(Boolean).join(" · "));
    const cpu = asNumber(service.cpu_percent);
    const memory = memoryLabel(service.memory_mb);
    if (cpu !== null || memory) chip.append(node("span", "", [cpu !== null ? format(cpu / 100, percent) : "", memory].filter(Boolean).join(" / ")));
    return chip;
  });
  const { host = {}, ollama = {} } = data;
  const values = [];
  const push = (key, value) => {
    if (!value) return;
    const metric = node("span", "host-metric");
    metric.append(node("strong", "", key), node("span", "", value));
    values.push(metric);
  };
  if (asNumber(host.cpu_percent) !== null) push("CPU", format(host.cpu_percent / 100, percent));
  const used = memoryLabel(host.memory_used_mb);
  if (used) push(t("Mémoire"), [used, memoryLabel(host.memory_total_mb)].filter(Boolean).join(" / "));
  if (asNumber(host.battery_percent) !== null) push(t("Batterie"), format(host.battery_percent / 100, percent) + (host.charging ? " · " + t("En charge") : ""));
  const models = (ollama.models ?? []).map((model) => model?.name ?? model).filter((name) => typeof name === "string");
  if (models.length) push(t("Modèles Ollama"), models.slice(0, 3).join(" · ") + (models.length > 3 ? " +" + (models.length - 3) : ""));
  if (asNumber(ollama.requests) !== null) push(t("Requêtes Ollama"), format(ollama.requests, integer));
  if (asNumber(ollama.prompt_tokens) !== null) push(t("Jetons en entrée"), format(ollama.prompt_tokens, integer));
  if (asNumber(ollama.output_tokens) !== null) push(t("Jetons en sortie"), format(ollama.output_tokens, integer));
  $("telemetry-services").replaceChildren(...services);
  if (focusedService) services.find((service) => service.dataset.service === focusedService)?.focus({ preventScroll: true });
  $("telemetry-host").replaceChildren(...values);
  $("telemetry-host").hidden = !values.length;
  output("telemetry-updated", telemetryTime(data.updated_at));
  $("system-telemetry").hidden = !services.length && !values.length;
}

async function refreshTelemetry() {
  if (!document.hidden) {
    try {
      telemetrySnapshot = await get("/api/telemetry");
      renderTelemetry(telemetrySnapshot);
    } catch {
      if (telemetrySnapshot) output("telemetry-updated", t("Actualisation indisponible"));
    }
  }
  setTimeout(refreshTelemetry, 9000);
}

function legacyPoints(series) {
  return series.points.map((point) => ({ ...point, time: Date.parse(point.date), rating: asNumber(point.rating) }))
    .filter((point) => Number.isFinite(point.time) && point.rating !== null).sort((a, b) => a.time - b.time);
}

function legacyChart(series, scale) {
  const points = series.flatMap((item) => item.points);
  const times = points.map((point) => point.time);
  const ratings = points.map((point) => point.rating);
  const first = Math.min(...times), last = Math.max(...times);
  const min = Math.floor(Math.min(...ratings) / 50) * 50;
  const max = Math.max(min + 50, Math.ceil(Math.max(...ratings) / 50) * 50);
  const x = (time) => first === last ? 226 : 36 + 376 * (time - first) / (last - first);
  const y = (rating) => 112 - 95 * (rating - min) / (max - min);
  const chart = svgElement("svg", { viewBox: "0 0 428 138", role: "img", "aria-label": t("Échelle ") + scale.label });
  for (const value of [min, (min + max) / 2, max]) {
    chart.append(svgElement("line", { class: "plot-grid", x1: 36, x2: 412, y1: y(value), y2: y(value) }));
    const text = svgElement("text", { x: 31, y: y(value) + 3, "text-anchor": "end" });
    text.textContent = format(value, integer);
    chart.append(text);
  }
  for (const time of [...new Set([first, last])]) {
    const label = svgElement("text", { x: x(time), y: 129, "text-anchor": first === last ? "middle" : time === first ? "start" : "end" });
    label.textContent = new Date(time).toLocaleDateString(uiLocale, { year: "numeric", month: "short", timeZone: "UTC" });
    chart.append(label);
  }
  series.forEach((item, index) => {
    const color = index % 4;
    const coordinates = item.points.map((point) => [x(point.time), y(point.rating)]);
    chart.append(svgElement("polyline", { class: "legacy-line legacy-color-" + color, points: coordinates.join(" "), fill: "none" }));
    coordinates.forEach(([cx, cy], pointIndex) => {
      const dot = svgElement("circle", { class: "legacy-dot legacy-color-" + color, cx, cy, r: 3.3 });
      const title = svgElement("title", {});
      const point = item.points[pointIndex];
      title.textContent = [item.name, point.date, format(point.rating, integer), point.event].filter(Boolean).join(" · ");
      dot.append(title);
      chart.append(dot);
    });
  });
  return chart;
}

function renderLegacy(data) {
  const cards = (data.scales ?? []).flatMap((scale) => {
    const series = (data.series ?? []).filter((item) => item.scale === scale.id && Array.isArray(item.points))
      .map((item) => ({ ...item, points: legacyPoints(item) })).filter((item) => item.points.length);
    if (!series.length) return [];
    const card = node("article", "legacy-card");
    card.append(node("h3", "", scale.label), node("p", "", scale.description || ""), legacyChart(series, scale));
    const legend = node("div", "legacy-legend");
    series.forEach((item, index) => {
      const latest = item.points.at(-1);
      const label = node("span", "legacy-master legacy-color-" + index % 4);
      const initials = item.name.split(/\s+/).slice(0, 2).map((word) => word[0]).join("");
      const identity = node("span");
      identity.append(node("strong", "", item.name), node("small", "", [item.country, latest.date, format(latest.rating, integer)].filter(Boolean).join(" · ")));
      label.append(node("span", "master-mark", initials), identity);
      setTip(label, t("Cote publiée par la compétition indiquée. Les autres systèmes utilisent leur propre échelle."));
      legend.append(label);
    });
    card.append(legend);
    const details = node("details");
    details.append(node("summary", "", t("Relevés datés")));
    const list = node("ul");
    series.forEach((item) => item.points.forEach((point) => {
      const entry = node("li", "", [item.name, point.date, format(point.rating, integer), point.event].filter(Boolean).join(" · "));
      const link = sourceLink(point.source, "Source");
      if (link) entry.append(document.createTextNode(" "), link);
      list.append(entry);
    }));
    details.append(list);
    card.append(details);
    const link = sourceLink(scale.source, t("Sources officielles"));
    if (link) card.append(link);
    return [card];
  });
  replaceRows($("legacy-scales"), cards, t("Chargement des archives…"));
  renderChronology(data.chronology ?? []);
}

async function loadLegacy() {
  try {
    legacySnapshot ??= await get("/api/legacy");
    renderLegacy(legacySnapshot);
    loadChampionshipMap(legacySnapshot.championships ?? []);
  } catch {
    replaceRows($("legacy-scales"), [], t("Archives indisponibles pour le moment."));
  }
}

function renderChronology(events) {
  const rows = events.map((event) => {
    const item = node("article", "study-row");
    const head = node("div", "study-heading");
    const date = event.date_precision === "year" ? String(event.date).slice(0, 4) : event.date;
    head.append(node("h3", "", event.title), node("span", "", date));
    item.append(head, node("p", "", event.description));
    const link = sourceLink(event.source, "Source");
    if (link) item.append(link);
    return item;
  });
  $("chronology-list").replaceChildren(...rows);
  $("chronology-panel").hidden = !rows.length;
}

async function loadChampionshipMap(results) {
  const events = results.filter((event) => Number.isFinite(event.lat) && Number.isFinite(event.lon))
    .sort((a, b) => String(a.date).localeCompare(String(b.date)));
  if (!events.length) return;
  try {
    worldGeometry ??= await get("/assets/world-land.json");
  } catch {
    worldGeometry = { rings: [] };
  }
  const panel = $("geography-panel");
  const slider = $("championship-time");
  const x = (lon) => 12 + (lon + 180) * 2.4;
  const y = (lat) => 12 + (85 - lat) * 2.4;
  const map = svgElement("svg", { viewBox: "0 0 888 376", role: "group", "aria-label": t("Carte des championnats") });
  for (let lon = -180; lon <= 180; lon += 30) {
    map.append(svgElement("line", { class: "map-grid", x1: x(lon), x2: x(lon), y1: 12, y2: 364 }));
  }
  for (let lat = -60; lat <= 60; lat += 30) {
    map.append(svgElement("line", { class: "map-grid", x1: 12, x2: 876, y1: y(lat), y2: y(lat) }));
  }
  for (const ring of worldGeometry.rings ?? []) {
    if (ring.every(([, lat]) => lat < -60)) continue;
    const coordinates = ring.map(([lon, lat]) => [Math.round(x(lon)), Math.round(y(lat))]);
    map.append(svgElement("polygon", { class: "map-land", points: coordinates.join(" ") }));
  }
  const markers = events.map((event, index) => {
    const marker = svgElement("circle", { class: "map-marker", cx: x(event.lon), cy: y(event.lat), r: 5, role: "button", tabindex: "0", "aria-label": [event.city, event.date, event.winner].filter(Boolean).join(" · ") });
    const title = svgElement("title", {});
    title.textContent = [event.city, event.date].filter(Boolean).join(" · ");
    marker.append(title);
    const select = () => {
      slider.value = String(index);
      renderEvent();
    };
    marker.addEventListener("click", select);
    marker.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        select();
      }
    });
    map.append(marker);
    return marker;
  });
  function renderEvent() {
    const index = Number(slider.value);
    const event = events[index];
    output("championship-date", event.date);
    markers.forEach((marker, position) => marker.setAttribute("aria-pressed", String(index === position)));
    const detail = $("geography-detail");
    const place = node("div");
    place.append(node("strong", "", event.event), node("p", "", [event.city, event.country, event.date].filter(Boolean).join(" · ")));
    const result = node("div");
    result.append(node("strong", "", [event.winner, event.runner_up].filter(Boolean).join(" × ")), node("p", "", event.series_score || ""));
    const source = sourceLink(event.source, "Source");
    if (source) result.append(source);
    detail.replaceChildren(place, result);
  }
  slider.max = String(events.length - 1);
  slider.value = slider.max;
  slider.oninput = renderEvent;
  $("championship-map").replaceChildren(map);
  renderEvent();
  panel.hidden = false;
}
