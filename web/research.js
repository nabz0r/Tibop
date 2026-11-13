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
  const step = Math.max(50, Math.ceil((Math.max(...ratings) - Math.min(...ratings)) / 200) * 50);
  const min = Math.floor(Math.min(...ratings) / step) * step;
  const max = Math.max(min + step * 2, Math.ceil(Math.max(...ratings) / step) * step);
  const left = 44, right = 408, top = 27, bottom = 174;
  const x = (time) => first === last ? (left + right) / 2 : left + (right - left) * (time - first) / (last - first);
  const y = (rating) => bottom - (bottom - top) * (rating - min) / (max - min);
  const chart = svgElement("svg", { viewBox: "0 0 424 206", role: "img", "aria-label": t("Cotes publiées") + " · " + scale.label });
  chart.append(svgText(t("Cotes publiées"), { class: "plot-unit", x: left, y: 13 }));
  for (let value = min; value <= max; value += step) {
    chart.append(svgElement("line", { class: "plot-grid", x1: left, x2: right, y1: y(value), y2: y(value) }));
    chart.append(svgText(format(value, integer), { x: left - 7, y: y(value) + 3, "text-anchor": "end" }));
  }
  const dateTicks = [...new Set([first, ...(last - first > 180 * 86400000 ? [(first + last) / 2] : []), last])];
  for (const time of dateTicks) {
    const label = new Date(time).toLocaleDateString(uiLocale, { year: "numeric", month: "short", timeZone: "UTC" });
    chart.append(svgText(label, { x: x(time), y: bottom + 18, "text-anchor": first === last ? "middle" : time === first ? "start" : time === last ? "end" : "middle" }));
  }
  series.forEach((item, index) => {
    const color = "legacy-color-" + index % 9;
    const coordinates = item.points.map((point) => [x(point.time), y(point.rating)]);
    chart.append(svgElement("polyline", { class: "legacy-line " + color, "data-series": index, points: coordinates.join(" "), fill: "none" }));
    coordinates.forEach(([cx, cy], pointIndex) => {
      const dot = svgElement("circle", { class: "legacy-dot " + color, "data-series": index, cx, cy, r: 4 });
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
    const chart = legacyChart(series, scale);
    card.append(node("h3", "", scale.label), node("p", "", scale.description || ""), chart);
    const legend = node("div", "legacy-legend");
    series.forEach((item, index) => {
      const latest = item.points.at(-1);
      const label = node("button", "legacy-master legacy-color-" + index % 9);
      label.type = "button";
      label.setAttribute("aria-pressed", "false");
      const swatch = svgElement("svg", { viewBox: "0 0 24 12", width: 24, height: 12, "aria-hidden": "true" });
      swatch.append(svgElement("line", { x1: 1, x2: 23, y1: 6, y2: 6, class: "legacy-line" }));
      const identity = node("span");
      identity.append(node("strong", "", item.name), node("small", "", [item.country, latest.date, format(latest.rating, integer)].filter(Boolean).join(" · ")));
      label.append(swatch, identity);
      label.addEventListener("click", () => {
        const active = label.getAttribute("aria-pressed") === "true" ? null : index;
        [...legend.children].forEach((button, position) => button.setAttribute("aria-pressed", String(position === active)));
        for (const mark of chart.querySelectorAll("[data-series]")) mark.classList.toggle("legacy-dimmed", active !== null && Number(mark.dataset.series) !== active);
      });
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
    head.append(node("h3", "", event.title), node("span", "", historyDate(event)));
    item.append(head, node("p", "", event.description));
    const link = sourceLink(event.source, "Source");
    if (link) item.append(link);
    return item;
  });
  $("chronology-list").replaceChildren(...rows);
  $("chronology-panel").hidden = !rows.length;
}

function historyDate(event) {
  if (event.date_precision === "year") return String(event.date).slice(0, 4);
  const options = event.date_precision === "month" ? { year: "numeric", month: "long" } : { dateStyle: "medium" };
  return new Date(event.date).toLocaleDateString(uiLocale, { ...options, timeZone: "UTC" });
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
    const marker = svgElement("g", { class: "map-marker", role: "button", tabindex: "0", "aria-label": [event.city, event.date, event.winner].filter(Boolean).join(" · ") });
    const title = svgElement("title", {});
    title.textContent = [event.city, event.date].filter(Boolean).join(" · ");
    const point = { cx: x(event.lon), cy: y(event.lat) };
    marker.append(title,
      svgElement("circle", { ...point, class: "map-target", r: 13 }),
      svgElement("circle", { ...point, class: "map-halo", r: 10 }),
      svgElement("circle", { ...point, class: "map-dot", r: 5.3 }),
    );
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
  const selected = svgElement("g", { class: "map-selected", "aria-hidden": "true" });
  const selectedLabel = svgText("", { y: -14 });
  selected.append(selectedLabel);
  map.append(selected);
  const stepButton = (direction, label) => {
    const button = node("button", "button secondary geography-step", direction < 0 ? "←" : "→");
    button.type = "button";
    button.setAttribute("aria-label", t(label));
    button.addEventListener("click", () => {
      slider.value = String(Number(slider.value) + direction);
      renderEvent();
    });
    return button;
  };
  const previous = stepButton(-1, "Finale précédente");
  const next = stepButton(1, "Finale suivante");
  const range = node("div", "geography-range");
  const oldRange = slider.closest(".geography-range");
  range.append(previous, slider, next);
  oldRange?.remove();
  const controls = panel.querySelector(".geography-controls");
  const date = $("championship-date");
  controls.insertBefore(range, date);
  function renderEvent() {
    const index = Number(slider.value);
    const event = events[index];
    const date = historyDate(event);
    output("championship-date", date);
    slider.setAttribute("aria-valuetext", [event.city, date].filter(Boolean).join(" · "));
    previous.disabled = index === 0;
    next.disabled = index === events.length - 1;
    markers.forEach((marker, position) => marker.setAttribute("aria-pressed", String(index === position)));
    const markerX = x(event.lon), markerY = y(event.lat);
    selected.setAttribute("transform", `translate(${markerX} ${markerY})`);
    selectedLabel.setAttribute("x", markerX < 90 ? 10 : markerX > 798 ? -10 : 0);
    selectedLabel.setAttribute("text-anchor", markerX < 90 ? "start" : markerX > 798 ? "end" : "middle");
    selectedLabel.textContent = event.city;
    const detail = $("geography-detail");
    const place = node("div", "geography-place");
    place.append(node("strong", "", event.event), node("p", "", [event.city, event.country, date].filter(Boolean).join(" · ")));
    const result = node("div", "geography-result");
    for (const [label, name] of [["Vainqueur", event.winner], ["Finaliste", event.runner_up]]) {
      if (!name) continue;
      const row = node("div");
      row.append(node("span", "", t(label)), node("strong", "", name));
      result.append(row);
    }
    if (event.series_score) result.append(node("p", "", event.series_score));
    const source = sourceLink(event.source, "Source");
    if (source) result.append(source);
    detail.replaceChildren(place, result);
  }
  slider.max = String(events.length - 1);
  slider.value = panel.hidden ? slider.max : String(Math.min(Number(slider.value), events.length - 1));
  slider.oninput = renderEvent;
  $("championship-map").replaceChildren(map);
  renderEvent();
  panel.hidden = false;
}
