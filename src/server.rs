use crate::{
    adaptive::{Calibration, StrengthScale},
    engine::{BASE_EXPERT, Engine, ExpertPolicy, Game, LEVELS},
    import, pg, profiles, review,
    store::{self, Filter},
    timeline,
};
use anyhow::{Result, anyhow};
use axum::{
    Json, Router,
    body::{Body, Bytes},
    extract::{DefaultBodyLimit, Path, Query, State},
    http::{HeaderValue, StatusCode, header},
    response::{
        IntoResponse, Response,
        sse::{Event, KeepAlive, Sse},
    },
    routing::{get, post},
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    env,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::sync::{Semaphore, mpsc};
use tower_http::services::{ServeDir, ServeFile};

#[derive(Clone)]
struct App {
    db: PathBuf,
    engine: Arc<Engine>,
    review_slots: Arc<Semaphore>,
    export_slots: Arc<Semaphore>,
    model_slot: Arc<Semaphore>,
    http: reqwest::Client,
    ollama: Option<(String, String)>,
    queue: Option<redis::Client>,
    engines: Arc<Mutex<HashMap<String, Arc<Engine>>>>,
    profiles: Arc<HashMap<String, profiles::Profile>>,
    models: Arc<Vec<String>>,
    live_slots: Arc<Semaphore>,
}

struct ApiError(StatusCode, String);
type ApiResult<T> = std::result::Result<T, ApiError>;

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({"error":self.1}))).into_response()
    }
}
impl From<anyhow::Error> for ApiError {
    fn from(e: anyhow::Error) -> Self {
        eprintln!("API: {e:#}");
        Self(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Erreur de lecture ou d’analyse.".into(),
        )
    }
}

async fn blocking<T: Send + 'static>(
    f: impl FnOnce() -> ApiResult<T> + Send + 'static,
) -> ApiResult<T> {
    tokio::task::spawn_blocking(f)
        .await
        .map_err(|e| ApiError::from(anyhow!(e)))?
}

#[derive(Default, Deserialize)]
struct ProfileQuery {
    profile: Option<String>,
}

fn profile(app: &App, requested: Option<&str>) -> ApiResult<String> {
    let id = requested.unwrap_or(&app.engine.profile.id);
    if !app.profiles.contains_key(id) {
        return Err(bad_request("Profil inconnu."));
    }
    Ok(id.to_string())
}
fn database(app: &App, id: &str) -> PathBuf {
    if id == app.engine.profile.id {
        app.db.clone()
    } else {
        app.db.with_file_name(format!("{id}.sqlite"))
    }
}
fn engine(app: &App, id: &str) -> ApiResult<Arc<Engine>> {
    if id == app.engine.profile.id {
        return Ok(app.engine.clone());
    }
    let mut engines = app
        .engines
        .lock()
        .map_err(|_| ApiError::from(anyhow!("cache moteur indisponible")))?;
    if let Some(engine) = engines.get(id) {
        return Ok(engine.clone());
    }
    let profile = app.profiles[id].clone();
    let path = profile
        .lexicon
        .path
        .clone()
        .ok_or_else(|| unavailable("Le lexique de ce profil reste à configurer."))?;
    if engines.values().any(|value| Arc::strong_count(value) > 1) {
        return Err(unavailable("Un autre profil est en cours d’analyse."));
    }
    engines.clear();
    let engine = Arc::new(Engine::load(&path, profile.lexicon.name.clone(), profile)?);
    engines.insert(id.to_string(), engine.clone());
    Ok(engine)
}
fn meta(app: &App, key: &str, id: &str) -> Result<Option<Value>> {
    if let Some(mut client) = pg::connect()? {
        return pg::meta(&mut client, key, id);
    }
    let path = database(app, id);
    if path.is_file() {
        return store::get(&store::open(&path, false)?, key);
    }
    Ok(None)
}
fn detail(app: &App, id: u32, profile: &str) -> Result<Option<Vec<u8>>> {
    if let Some(mut client) = pg::connect()? {
        return pg::detail(&mut client, id, profile);
    }
    let path = database(app, profile);
    if path.is_file() {
        return store::detail(&store::open(&path, false)?, id);
    }
    Ok(None)
}
fn read_value(path: &str) -> Value {
    std::fs::read(path)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}
fn calibration(profile: &profiles::Profile) -> Option<Calibration> {
    serde_json::from_value(read_value(&format!(
        "data/models/adaptive-{}-v1.json",
        profile.language
    )))
    .ok()
}
fn active_calibration(
    profile: &profiles::Profile,
    digest: &str,
    expert: ExpertPolicy,
) -> Option<Calibration> {
    calibration(profile)
        .filter(|report| report.compatible(&profile.id, digest, expert))
        .filter(|report| report.measured_bounds().is_some())
}
fn ratings(stats: &Value) -> [i32; 4] {
    store::elo_anchors(stats)
}
pub(crate) fn strength(
    engine: &Engine,
    stats: &Value,
) -> Result<(StrengthScale, Option<Calibration>)> {
    let expert = model_policy(&engine.digest);
    if let Some(calibration) = active_calibration(&engine.profile, &engine.digest, expert) {
        return Ok((calibration.scale, Some(calibration)));
    }
    let anchors = ratings(stats);
    let scale = StrengthScale::new(anchors, expert)
        .or_else(|_| StrengthScale::new(ratings(&Value::Null), expert))?;
    Ok((
        if scale.anchors[0] > 700 {
            scale.with_experimental_floor(700)?
        } else {
            scale
        },
        None,
    ))
}

fn unavailable(message: &str) -> ApiError {
    ApiError(StatusCode::SERVICE_UNAVAILABLE, message.into())
}
fn not_found(message: &str) -> ApiError {
    ApiError(StatusCode::NOT_FOUND, message.into())
}
fn bad_request(message: &str) -> ApiError {
    ApiError(StatusCode::BAD_REQUEST, message.into())
}

async fn stats(State(app): State<App>, Query(q): Query<ProfileQuery>) -> ApiResult<Json<Value>> {
    let id = profile(&app, q.profile.as_deref())?;
    blocking(move||{
        let p=&app.profiles[&id];
        let mut stats=meta(&app,"public",&id)?.unwrap_or_else(||json!({
            "completed":0,"target":0,"running":false,"levels":[],"matchups":[],"words":[],"recent":[],"lexicon":p.lexicon.name
        }));
        if stats["completed"].as_u64().unwrap_or(0)>0
            && let Some(mut client)=pg::connect()?
        {
            stats["best_game"]=pg::best_game(&mut client,&id)?.unwrap_or(Value::Null);
        }
        stats["profile"]=json!(id);
        stats["language"]=json!(p.language);
        stats["storage"]=json!(if env::var_os("LETTRINE_PG_HOST").is_some()||env::var_os("LETTRINE_DATABASE_URL").is_some() {"postgresql"} else {"sqlite"});
        stats["ollama_model"]=json!(app.ollama.as_ref().map(|(_,m)|m));
        stats["ollama_models"]=if app.ollama.is_some() {json!(&*app.models)} else {json!([])};
        let digest = if id == app.engine.profile.id {
            Some(app.engine.digest.clone())
        } else {
            meta(&app,"campaign",&id)?.and_then(|campaign| campaign["lexicon_sha256"].as_str().map(str::to_owned))
        };
        if let Some(digest) = digest {
            let expert = model_policy(&digest);
            if let Some(cal)=active_calibration(p, &digest, expert)
                && let Some((min,max))=cal.measured_bounds()
            {
                stats["adaptive"]=json!({"min":min.ceil().max(700.0),"max":max.floor(),
                    "games":cal.measurements.iter().map(|m|m.games).sum::<u32>()});
            }
            stats["peak_available"] = json!(crate::peak::promoted_config_for(
                &id, &digest, expert, &read_value("data/models/peak-fr-v1.json")
            ).is_some());
        }
        Ok(Json(stats))
    }).await
}
async fn games(State(app): State<App>, Query(f): Query<Filter>) -> ApiResult<Json<Value>> {
    let id = profile(&app, f.profile.as_deref())?;
    blocking(move || {
        if let Some(mut client) = pg::connect()? {
            return Ok(Json(pg::list(&mut client, &f, &id)?));
        }
        let path = database(&app, &id);
        if path.is_file() {
            return Ok(Json(store::list(&store::open(&path, false)?, &f)?));
        }
        Ok(Json(json!({"items":[],"total":0})))
    })
    .await
}
async fn game(
    State(app): State<App>,
    Path(id): Path<u32>,
    Query(q): Query<ProfileQuery>,
) -> ApiResult<Response> {
    let profile = profile(&app, q.profile.as_deref())?;
    blocking(move || {
        let bytes = detail(&app, id, &profile)?.ok_or_else(|| not_found("Partie introuvable."))?;
        Ok(([(header::CONTENT_TYPE, "application/json")], bytes).into_response())
    })
    .await
}

#[derive(Deserialize)]
struct Turn {
    turn: u16,
    profile: Option<String>,
    model: Option<String>,
}

async fn position(app: App, id: u32, turn: u16, profile: String) -> ApiResult<review::Review> {
    let permit = app.review_slots.clone().try_acquire_owned().map_err(|_| {
        ApiError(
            StatusCode::TOO_MANY_REQUESTS,
            "Deux analyses sont déjà en cours.".into(),
        )
    })?;
    blocking(move || {
        let _permit = permit;
        let engine = engine(&app, &profile)?;
        let campaign =
            meta(&app, "campaign", &profile)?.ok_or_else(|| not_found("Campagne introuvable."))?;
        if campaign["lexicon_sha256"].as_str() != Some(&engine.digest)
            || campaign["engine"].as_str() != Some(&engine.profile.id)
        {
            return Err(ApiError(
                StatusCode::CONFLICT,
                "Le lexique de la campagne doit être chargé pour l’analyse.".into(),
            ));
        }
        let bytes = detail(&app, id, &profile)?.ok_or_else(|| not_found("Partie introuvable."))?;
        let game = serde_json::from_slice::<Game>(&bytes).map_err(anyhow::Error::from)?;
        if turn == 0 || usize::from(turn) > game.turns {
            return Err(bad_request("Tour hors limites."));
        }
        Ok(review::analyze(&engine, &game, turn)?)
    })
    .await
}
async fn analyze(
    State(app): State<App>,
    Path(id): Path<u32>,
    Query(q): Query<Turn>,
) -> ApiResult<Json<review::Review>> {
    let profile = profile(&app, q.profile.as_deref())?;
    Ok(Json(position(app, id, q.turn, profile).await?))
}
async fn game_timeline(
    State(app): State<App>,
    Path(id): Path<u32>,
    Query(q): Query<ProfileQuery>,
) -> ApiResult<Json<Value>> {
    let profile = profile(&app, q.profile.as_deref())?;
    blocking(move||{
        let engine=engine(&app,&profile)?;
        let bytes=detail(&app,id,&profile)?.ok_or_else(||not_found("Partie introuvable."))?;
        let game:Game=serde_json::from_slice(&bytes).map_err(anyhow::Error::from)?;
        let ratings=ratings(&meta(&app,"public",&profile)?.unwrap_or_default());
        let ratings=game.levels.map(|i|f64::from(ratings[i as usize]));
        let fitted=read_value("data/models/win-model.json")["profiles"][&profile].clone();
        let method=timeline::method(&fitted);
        Ok(Json(json!({"items":timeline::points(&engine,&game,ratings,&fitted),"method":method["win"],"draw_method":method["draw"]})))
    }).await
}
async fn challenge(
    State(app): State<App>,
    Path(id): Path<u32>,
    Query(q): Query<Turn>,
) -> ApiResult<Json<Value>> {
    let id_profile = profile(&app, q.profile.as_deref())?;
    let (base, default_model) = app
        .ollama
        .clone()
        .ok_or_else(|| unavailable("Ollama n’est pas configuré."))?;
    let model = q.model.unwrap_or(default_model);
    if !app.models.contains(&model) {
        return Err(bad_request("Modèle Ollama hors liste."));
    }
    let language = app.profiles[&id_profile].language.clone();
    let _permit = app.model_slot.clone().try_acquire_owned().map_err(|_| {
        ApiError(
            StatusCode::TOO_MANY_REQUESTS,
            "Un commentaire est déjà en cours.".into(),
        )
    })?;
    let review = position(app.clone(), id, q.turn, id_profile).await?;
    let facts = serde_json::to_value(&review).map_err(anyhow::Error::from)?;
    let request = json!({
        "model":model, "stream":false, "think":false, "keep_alive":"2m",
        "format":{"type":"object","properties":{"angle":{"type":"string","enum":["simulation","immediate_score","rack"]}},
            "required":["angle"],"additionalProperties":false},
        "options":{"temperature":0,"num_predict":32,"num_ctx":4096},
        "messages":[
            {"role":"system","content":"Choose the most useful explanation for the highest-margin alternative to the recorded move. Use immediate_score if its score matters most, rack if its retained tiles matter most, or simulation for the comparison of simulated margins. Return only the requested JSON."},
            {"role":"user","content": facts.to_string()}
        ]
    });
    let (answer, error) = match generate_commentary(app, &base, &request).await {
        Ok(answer) => (answer, None),
        Err(error) => (Value::Null, Some(error.1)),
    };
    let selection = answer["commentary"]
        .as_str()
        .and_then(|content| serde_json::from_str::<CommentarySelection>(content).ok());
    let fallback = if selection.is_some() {
        None
    } else {
        Some(error.unwrap_or_else(|| "Réponse du modèle illisible.".into()))
    };
    let angle = selection.map_or_else(review::CommentaryAngle::default, |answer| answer.angle);
    Ok(Json(json!({
        "model":model,
        "commentary":review.commentary(angle, &language),
        "fallback":fallback,
        "usage":answer["usage"],
    })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CommentarySelection {
    angle: review::CommentaryAngle,
}

async fn generate_commentary(app: App, base: &str, request: &Value) -> ApiResult<Value> {
    if let Some(queue) = app.queue {
        let job_id = format!("{:032x}", rand::random::<u128>());
        let result_key = format!("lettrine:job:{job_id}");
        let mut connection = queue
            .get_multiplexed_async_connection()
            .await
            .map_err(|_| unavailable("La file Redis est indisponible."))?;
        let _: String = redis::cmd("XADD")
            .arg("lettrine:jobs")
            .arg("*")
            .arg("kind")
            .arg("challenge")
            .arg("job_id")
            .arg(&job_id)
            .arg("payload")
            .arg(request.to_string())
            .query_async(&mut connection)
            .await
            .map_err(|_| unavailable("La demande de commentaire n’a pas pu être déposée."))?;
        let started = tokio::time::Instant::now();
        loop {
            let state: Option<String> = redis::cmd("GET")
                .arg(&result_key)
                .query_async(&mut connection)
                .await
                .map_err(|_| unavailable("Le commentaire du modèle est indisponible."))?;
            if let Some(state) = state {
                let parsed: Value = serde_json::from_str(&state).map_err(anyhow::Error::from)?;
                if parsed["state"] == "done" {
                    return Ok(parsed);
                }
                if parsed["state"] == "error" {
                    return Err(unavailable(
                        parsed["error"]
                            .as_str()
                            .unwrap_or("Le traitement du commentaire a échoué."),
                    ));
                }
            }
            if started.elapsed() >= Duration::from_secs(87) {
                return Err(unavailable(
                    "Le commentaire du modèle a dépassé le délai de réponse.",
                ));
            }
            tokio::time::sleep(Duration::from_millis(350)).await;
        }
    }
    let result = app
        .http
        .post(format!("{}/api/chat", base.trim_end_matches('/')))
        .json(request)
        .send()
        .await
        .map_err(|_| unavailable("Le service Ollama local est injoignable."))?;
    if !result.status().is_success() {
        return Err(unavailable(
            "Ollama n’a pas pu charger le modèle configuré.",
        ));
    }
    let answer: Value = result
        .json()
        .await
        .map_err(|_| unavailable("Réponse Ollama illisible."))?;
    crate::telemetry::record_ollama_usage(&answer).await;
    let commentary = answer["message"]["content"]
        .as_str()
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| unavailable("Ollama n’a renvoyé aucun commentaire."))?;
    Ok(json!({"commentary":commentary}))
}

async fn import_games(bytes: Bytes) -> ApiResult<Json<Value>> {
    blocking(move || {
        let mut conn = pg::connect()?
            .ok_or_else(|| unavailable("L’import nécessite le serveur Docker PostgreSQL."))?;
        let documents = import::prepare(&bytes).map_err(|e| bad_request(&format!("{e:#}")))?;
        Ok(Json(import::insert(&mut conn, documents)?))
    })
    .await
}
async fn imports(Query(f): Query<Filter>) -> ApiResult<Json<Value>> {
    blocking(move || {
        let Some(mut conn) = pg::connect()? else {
            return Ok(Json(json!({"items":[],"total":0})));
        };
        Ok(Json(pg::list_imports(&mut conn, &f)?))
    })
    .await
}
async fn imported(Path(id): Path<u32>) -> ApiResult<Json<Value>> {
    blocking(move || {
        let mut conn =
            pg::connect()?.ok_or_else(|| unavailable("Imports PostgreSQL indisponibles."))?;
        Ok(Json(pg::imported_game(&mut conn, id)?.ok_or_else(
            || not_found("Partie importée introuvable."),
        )?))
    })
    .await
}
async fn dump(State(app): State<App>, Query(f): Query<Filter>) -> ApiResult<Response> {
    export(app, f, false).await
}
async fn dump_imports(State(app): State<App>, Query(f): Query<Filter>) -> ApiResult<Response> {
    export(app, f, true).await
}

async fn export(app: App, f: Filter, imports: bool) -> ApiResult<Response> {
    let profile = profile(&app, f.profile.as_deref())?;
    let format = f.format.as_deref().unwrap_or("jsonl");
    let (mime, suffix) = match format {
        "csv" if !imports => ("text/csv; charset=utf-8", "csv"),
        "jsonl" => ("application/x-ndjson", "jsonl"),
        "json" => ("application/json", "json"),
        _ => {
            return Err(bad_request(
                "Choisissez un format disponible pour cette collection.",
            ));
        }
    };
    let label = if imports {
        "imports"
    } else if f.elite == Some(1) {
        "experts"
    } else {
        "parties"
    };
    let permit = app.export_slots.clone().try_acquire_owned().map_err(|_| {
        ApiError(
            StatusCode::TOO_MANY_REQUESTS,
            "Deux exports sont déjà en cours.".into(),
        )
    })?;
    let (tx, rx) = mpsc::channel::<Result<Bytes, std::io::Error>>(4);
    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel();
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        let source = pg::connect().and_then(|pg| match pg {
            Some(c) => Ok(ExportSource::Postgres(Box::new(c))),
            None if imports => Err(anyhow!("Les imports nécessitent PostgreSQL.")),
            None => Ok(ExportSource::Sqlite(store::open(
                &database(&app, &profile),
                false,
            )?)),
        });
        let mut source = match source {
            Ok(s) => {
                let _ = ready_tx.send(Ok(()));
                s
            }
            Err(e) => {
                let _ = ready_tx.send(Err(ApiError::from(e)));
                return;
            }
        };
        let send = |data| {
            tx.blocking_send(Ok(Bytes::from(data)))
                .map_err(|_| anyhow!("téléchargement interrompu"))
        };
        let result = match &mut source {
            ExportSource::Postgres(conn) => pg::export(conn, &f, imports, &profile, send),
            ExportSource::Sqlite(conn) => store::export(conn, &f, send),
        };
        if let Err(e) = result {
            let _ = tx.blocking_send(Err(std::io::Error::other(e)));
        }
    });
    ready_rx.await.map_err(|e| ApiError::from(anyhow!(e)))??;
    let stream =
        futures_util::stream::unfold(rx, |mut rx| async { rx.recv().await.map(|v| (v, rx)) });
    let mut response = Body::from_stream(stream).into_response();
    response
        .headers_mut()
        .insert(header::CONTENT_TYPE, HeaderValue::from_static(mime));
    response.headers_mut().insert(
        header::CONTENT_DISPOSITION,
        HeaderValue::from_str(&format!("attachment; filename=lettrine-{label}.{suffix}"))
            .expect("nom fixe"),
    );
    Ok(response)
}
enum ExportSource {
    Postgres(Box<postgres::Client>),
    Sqlite(rusqlite::Connection),
}

#[derive(Deserialize)]
struct Rating {
    elo: Option<f64>,
    profile: Option<String>,
}
async fn adaptive(State(app): State<App>, Query(q): Query<Rating>) -> ApiResult<Json<Value>> {
    let profile = profile(&app, q.profile.as_deref())?;
    blocking(move || {
        let elo = q.elo.unwrap_or(1200.0);
        if !elo.is_finite() || !(100.0..=3000.0).contains(&elo) {
            return Err(bad_request("Elo attendu entre 100 et 3000."));
        }
        let engine = engine(&app, &profile)?;
        let stats = meta(&app, "public", &profile)?.unwrap_or_default();
        let (scale, calibration) = strength(&engine, &stats)?;
        let target = match &calibration {
            Some(c) => scale.at_calibrated(elo, c)?,
            None => scale.at(elo)?,
        };
        Ok(Json(
            json!({"player_elo":elo,"target":target,"scale":scale,"calibration":calibration,"expected_vs_reference":crate::adaptive::expected_score(target.applied_elo,f64::from(scale.anchors[2]))}),
        ))
    })
    .await
}

#[derive(Deserialize)]
struct Search {
    q: String,
}

async fn search(State(app): State<App>, Query(query): Query<Search>) -> ApiResult<Json<Value>> {
    let q = query.q.trim();
    if q.chars().count() < 2 || q.chars().count() > 180 {
        return Err(bad_request("Saisissez de 2 à 180 caractères."));
    }
    let (base, _) = app
        .ollama
        .as_ref()
        .ok_or_else(|| unavailable("Ollama n’est pas configuré."))?;
    let model = env::var("LETTRINE_EMBED_MODEL").unwrap_or_else(|_| "qwen3-embedding:0.6b".into());
    let response = app
        .http
        .post(format!("{}/api/embed", base.trim_end_matches('/')))
        .json(&json!({"model":model,"input":q,"dimensions":1024,"keep_alive":"2m"}))
        .send()
        .await
        .map_err(|_| unavailable("Le service d’indexation Ollama est injoignable."))?;
    if !response.status().is_success() {
        return Err(unavailable(
            "Le modèle d’indexation Ollama est indisponible.",
        ));
    }
    let result: Value = response
        .json()
        .await
        .map_err(|_| unavailable("Vecteur Ollama illisible."))?;
    let vector = result["embeddings"][0]
        .as_array()
        .filter(|a| a.len() == 1024 && a.iter().all(Value::is_number))
        .ok_or_else(|| unavailable("Le modèle n’a pas renvoyé un vecteur de 1 024 dimensions."))?;
    let vector = serde_json::to_string(vector).map_err(anyhow::Error::from)?;
    blocking(move || {
        let mut conn = pg::connect()?
            .ok_or_else(|| unavailable("La recherche nécessite PostgreSQL avec pgvector."))?;
        Ok(Json(pg::search(&mut conn, &vector)?))
    })
    .await
}

#[derive(Deserialize)]
struct Live {
    seed: Option<u32>,
    a: Option<u8>,
    b: Option<u8>,
    elo: Option<f64>,
    profile: Option<String>,
    peak: Option<u8>,
}
async fn live(State(app): State<App>, Query(q): Query<Live>) -> ApiResult<Response> {
    let id = profile(&app, q.profile.as_deref())?;
    let mut levels = [q.a.unwrap_or(3), q.b.unwrap_or(3)];
    if levels.iter().any(|&x| x > 3) {
        return Err(bad_request("Choisissez deux références entre 0 et 3."));
    }
    let peak_requested = q.peak == Some(1);
    if peak_requested && q.elo.is_some() {
        return Err(bad_request(
            "Choisissez un niveau réglable ou la recherche avancée.",
        ));
    }
    if q.elo
        .is_some_and(|e| !e.is_finite() || !(600.0..=3000.0).contains(&e))
    {
        return Err(bad_request("Elo cible invalide."));
    }
    if peak_requested || q.elo.is_some() {
        levels[0] = 3;
    }
    let permit = app.live_slots.clone().try_acquire_owned().map_err(|_| {
        ApiError(
            StatusCode::TOO_MANY_REQUESTS,
            "Trop de duels diffusés.".into(),
        )
    })?;
    let seed = q.seed.unwrap_or_else(rand::random::<u32>);
    let (game, points, target_elo, mode, method) = blocking(move || {
        use rand::SeedableRng;
        let engine = engine(&app, &id)?;
        let stats = meta(&app, "public", &id)?.unwrap_or_default();
        let (scale, calibration) = strength(&engine, &stats)?;
        let expert = scale.expert;
        let target = q
            .elo
            .map(|elo| match &calibration {
                Some(c) => scale.at_calibrated(elo, c),
                None => scale.at(elo),
            })
            .transpose()?;
        let mut rng = rand_xoshiro::Xoshiro256PlusPlus::seed_from_u64(u64::from(seed) ^ 0x807c);
        let mut generator = engine.generator();
        let mut state = wolges::game_state::GameState::new(&engine.config);
        let mut peak = if peak_requested {
            let report = read_value("data/models/peak-fr-v1.json");
            let config =
                crate::peak::promoted_config(&engine, expert, &report).ok_or_else(|| {
                    unavailable("La recherche avancée est indisponible pour ce profil.")
                })?;
            Some(crate::peak::PeakSearch::new(&engine, expert, config)?)
        } else {
            None
        };
        let mode = if peak.is_some() {
            "peak"
        } else if target.is_some() {
            "adaptive"
        } else {
            "reference"
        };
        let game = engine.play_seeded_with(
            &mut generator,
            &mut state,
            1,
            seed,
            levels,
            |engine, state, generator, play_rng, turn| {
                let seat = state.turn as usize;
                if seat == 0 {
                    if let Some(search) = peak.as_mut() {
                        return Ok(search
                            .choose(engine, state, (u64::from(seed) << 16) | u64::from(turn))?
                            .play);
                    }
                    if let Some(target) = target {
                        return Ok(target.choose_move(engine, generator, state, &mut rng));
                    }
                }
                let opponent_policy = if peak_requested { expert } else { BASE_EXPERT };
                Ok(engine.choose_level(generator, state, levels[seat], opponent_policy, play_rng))
            },
        )?;
        let ratings = ratings(&stats);
        let estimates = [
            target.map_or(f64::from(ratings[levels[0] as usize]), |t| t.applied_elo),
            f64::from(ratings[levels[1] as usize]),
        ];
        let fitted = read_value("data/models/win-model.json")["profiles"][&id].clone();
        let points = timeline::points(&engine, &game, estimates, &fitted);
        let method = timeline::method(&fitted)["win"].clone();
        Ok((game, points, target.map(|t| t.applied_elo), mode, method))
    })
    .await?;
    let mut labels = levels.map(|i| LEVELS[i as usize].to_string());
    if mode == "peak" {
        labels[0] = "NAB · Recherche avancée".into();
        if levels[1] == 3 {
            labels[1] = "Expert entraîné".into();
        }
    } else if let Some(elo) = target_elo {
        labels[0] = format!("NAB · {elo:.0}");
    }
    let mut events = vec![
        json!({"type":"start","seed":seed,"levels":levels,"labels":labels,"turns":game.turns,"target_elo":target_elo,"adaptive_seat":target_elo.map(|_|0),"policy":mode,"method":method}),
    ];
    for (m, p) in game.moves.iter().zip(&points) {
        let mut event = serde_json::to_value(p).map_err(anyhow::Error::from)?;
        event["type"] = json!("move");
        event["action"] = json!(m.kind);
        event["word"] = json!(m.word);
        event["tiles"] = json!(m.tiles);
        event["bag_before"] = json!(m.bag_before);
        if peak_requested {
            event
                .as_object_mut()
                .expect("point du duel")
                .remove("win_probability");
        }
        events.push(event);
    }
    events.push(json!({"type":"done","game":game}));
    let stream =
        futures_util::stream::unfold((events.into_iter(), permit), |(mut events, permit)| async {
            let event = events.next()?;
            if event["type"] == "move" {
                tokio::time::sleep(Duration::from_millis(850)).await;
            }
            Some((
                Ok::<_, std::convert::Infallible>(Event::default().data(event.to_string())),
                (events, permit),
            ))
        });
    Ok(Sse::new(stream)
        .keep_alive(KeepAlive::default())
        .into_response())
}

async fn file_json(path: &'static str) -> ApiResult<Response> {
    blocking(move || {
        let bytes = std::fs::read(path).map_err(anyhow::Error::from)?;
        Ok(([(header::CONTENT_TYPE, "application/json")], bytes).into_response())
    })
    .await
}

pub async fn serve(app_db: PathBuf, engine: Engine, address: String, web: PathBuf) -> Result<()> {
    blocking(|| {
        if let Some(mut conn) = pg::connect()? {
            pg::initialize(&mut conn)?;
        }
        Ok(())
    })
    .await
    .map_err(|e| anyhow!("{}", e.1))?;
    let ollama = env::var("LETTRINE_OLLAMA_URL").ok().map(|url| {
        (
            url,
            env::var("LETTRINE_OLLAMA_MODEL").unwrap_or_else(|_| "llama3.2:3b".into()),
        )
    });
    let profiles = profiles::list()?
        .into_iter()
        .map(|p| (p.id.clone(), p))
        .collect();
    let models = env::var("LETTRINE_OLLAMA_MODELS")
        .unwrap_or_else(|_| "llama3.2:3b,gemma4:latest,qwen3:0.6b".into());
    let mut models = models
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .collect::<Vec<_>>();
    if let Some((_, default)) = &ollama
        && !models.contains(default)
    {
        models.insert(0, default.clone());
    }
    let app = App {
        db: app_db,
        engine: Arc::new(engine),
        review_slots: Arc::new(Semaphore::new(2)),
        export_slots: Arc::new(Semaphore::new(2)),
        model_slot: Arc::new(Semaphore::new(1)),
        ollama,
        queue: env::var("LETTRINE_REDIS_URL")
            .ok()
            .map(redis::Client::open)
            .transpose()?,
        engines: Arc::new(Mutex::new(HashMap::new())),
        profiles: Arc::new(profiles),
        models: Arc::new(models),
        live_slots: Arc::new(Semaphore::new(3)),
        http: reqwest::Client::builder()
            .timeout(Duration::from_secs(90))
            .build()?,
    };
    let apk = env::var("LETTRINE_APK").unwrap_or_else(|_| "android/artifacts/lettrine.apk".into());
    let aab = env::var("LETTRINE_AAB").unwrap_or_else(|_| "android/artifacts/lettrine.aab".into());
    let room_engine = if app.engine.profile.id == pg::FRENCH {
        Some(app.engine.clone())
    } else if let Some(profile) = app.profiles.get(pg::FRENCH)
        && let Some(path) = &profile.lexicon.path
    {
        Some(Arc::new(Engine::load(
            path,
            profile.lexicon.name.clone(),
            profile.clone(),
        )?))
    } else {
        None
    };
    let rooms = if let Some(engine) = room_engine {
        crate::multiplayer::routes(app.db.clone(), engine).await?
    } else {
        Router::new()
    };
    let router = Router::new()
        .merge(rooms)
        .route_service("/", ServeFile::new(web.join("salons.html")))
        .route("/api/telemetry", get(crate::telemetry::snapshot))
        .route("/api/legacy", get(|| file_json("data/expert/legacy.json")))
        .route("/api/studies", get(studies).post(add_study))
        .route(
            "/api/participants",
            get(participants).post(add_participants),
        )
        .route("/api/participants/export", get(participants))
        .route("/api/games/{id}/timeline", get(game_timeline))
        .route("/api/games/{id}/policy", get(what_if))
        .route("/api/words", get(word_stats))
        .route("/api/commentary-benchmarks", get(commentary_benchmarks))
        .route("/health", get(|| async { "ok" }))
        .route("/api/stats", get(stats))
        .route("/api/search", get(search))
        .route(
            "/api/profiles",
            get(|| async { blocking(|| Ok(Json(crate::profiles::list()?))).await }),
        )
        .route("/api/live", get(live))
        .route("/api/llm-benchmarks", get(llm_benchmarks))
        .route("/api/models", get(model_report))
        .route("/api/expert", get(|| file_json("data/expert/sample.json")))
        .route("/api/games", get(games))
        .route("/api/games/{id}", get(game))
        .route("/api/games/{id}/review", get(analyze))
        .route("/api/games/{id}/challenge", get(challenge))
        .route("/api/export", get(dump))
        .route("/api/adaptive", get(adaptive))
        .route(
            "/api/import",
            post(import_games).layer(DefaultBodyLimit::max(32 * 1024 * 1024)),
        )
        .route("/api/imports", get(imports))
        .route("/api/imports/export", get(dump_imports))
        .route("/api/imports/{id}", get(imported))
        .route_service("/download/lettrine.apk", ServeFile::new(apk))
        .route_service(
            "/download/lettrine.aab",
            ServeFile::new_with_mime(aab, &"application/octet-stream".parse()?),
        )
        .fallback_service(ServeDir::new(web))
        .with_state(app);
    let listener = tokio::net::TcpListener::bind(&address).await?;
    eprintln!("Lettrine écoute sur {address}");
    axum::serve(listener, router).await?;
    Ok(())
}

pub fn model_policy(digest: &str) -> ExpertPolicy {
    let model = std::fs::read("data/models/fr-bots-v1.json")
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok());
    model
        .filter(|model| model["lexicon_sha256"] == digest && model["promoted"] == true)
        .and_then(|model| serde_json::from_value(model["selected"].clone()).ok())
        .unwrap_or(BASE_EXPERT)
}

async fn benchmark_items(path: &'static str, app: App, q: ProfileQuery) -> ApiResult<Json<Value>> {
    let id = profile(&app, q.profile.as_deref())?;
    blocking(move || {
        let data = read_value(path);
        let items: Vec<_> = data["items"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|item| item["profile"].as_str().unwrap_or(pg::FRENCH) == id)
            .cloned()
            .collect();
        Ok(Json(
            json!({"items":items,"profile":id,"method":data["method"]}),
        ))
    })
    .await
}
async fn llm_benchmarks(
    State(app): State<App>,
    Query(q): Query<ProfileQuery>,
) -> ApiResult<Json<Value>> {
    benchmark_items("data/models/llm-benchmarks.json", app, q).await
}
async fn commentary_benchmarks(
    State(app): State<App>,
    Query(q): Query<ProfileQuery>,
) -> ApiResult<Json<Value>> {
    benchmark_items("data/models/commentary-benchmarks.json", app, q).await
}
async fn model_report(
    State(app): State<App>,
    Query(q): Query<ProfileQuery>,
) -> ApiResult<Json<Value>> {
    let id = profile(&app, q.profile.as_deref())?;
    blocking(move || {
        let p = &app.profiles[&id];
        let mut data = if id == pg::FRENCH {
            read_value("data/models/fr-bots-v1.json")
        } else {
            json!({})
        };
        data["adaptive"] = json!(calibration(p));
        data["peak"] = if id == pg::FRENCH {
            read_value("data/models/peak-fr-v1.json")
        } else {
            Value::Null
        };
        if id == pg::FRENCH {
            data["reference_holdout"] = read_value("data/models/reference-holdout-fr.json");
        }
        Ok(Json(data))
    })
    .await
}

#[derive(Deserialize)]
struct StudyInput {
    title: String,
    summary: String,
    kind: Option<String>,
    payload: Option<Value>,
}

async fn studies(State(app): State<App>, Query(q): Query<ProfileQuery>) -> ApiResult<Json<Value>> {
    let id = profile(&app, q.profile.as_deref())?;
    blocking(move||{
        let stats=meta(&app,"public",&id)?.unwrap_or_default();
        let mut items=Vec::new();
        let mut conn=pg::connect()?;
        if let Some(conn)=conn.as_mut(){
            items.extend(pg::studies(conn,&id)?);
        }
        if let Some(n)=stats["completed"].as_u64().filter(|n|*n>0) {
            items.push(json!({"date":"2026-11-12","kind":"campaign","title":"Campagne de référence",
                "summary":format!("{n} duels, quatre repères de force, {}",stats["lexicon"].as_str().unwrap_or("lexique local")),
                "artifact":format!("/api/stats?profile={id}")}));
        }
        if let Some(c)=calibration(&app.profiles[&id]) {
            items.push(json!({"date":"2026-11-12","kind":"adaptive","title":"Calibration du bot adaptatif",
                "summary":format!("{} duels appariés sur {} cibles",c.measurements.iter().map(|m|m.games).sum::<u32>(),c.measurements.len()),
                "artifact":format!("/api/models?profile={id}")}));
        }
        let data=read_value("data/models/llm-benchmarks.json");
        for item in data["items"].as_array().into_iter().flatten()
            .filter(|item|item["profile"].as_str().unwrap_or(pg::FRENCH)==id) {
            items.push(json!({"date":"2026-11-12","kind":"llm","title":item["model"],
                "summary":format!("{} duels contre le moteur Expert",item["games"].as_u64().unwrap_or(0)),
                "artifact":format!("/api/llm-benchmarks?profile={id}")}));
        }
        if id==pg::FRENCH {
            items.push(json!({"date":"2026-11-12","kind":"training","title":"Expert entraîné et recherche avancée",
                "summary":"Autojeu, comparaison sur graines réservées et étude des répliques.",
                "artifact":"/api/models"}));
        }
        Ok(Json(json!({"items":items,"profile":id})))
    }).await
}
async fn add_study(
    State(app): State<App>,
    Query(q): Query<ProfileQuery>,
    Json(body): Json<StudyInput>,
) -> ApiResult<Json<Value>> {
    let profile = profile(&app, q.profile.as_deref())?;
    let title = body.title.trim().to_string();
    let summary = body.summary.trim().to_string();
    let kind = body.kind.unwrap_or_else(|| "note".into());
    if title.is_empty()
        || title.chars().count() > 120
        || summary.is_empty()
        || summary.chars().count() > 4000
        || !matches!(
            kind.as_str(),
            "note" | "campaign" | "llm" | "training" | "adaptive"
        )
    {
        return Err(bad_request("Titre, résumé ou type d’étude invalide."));
    }
    blocking(move||{
        let mut conn=pg::connect()?.ok_or_else(||unavailable("Le journal nécessite PostgreSQL."))?;
        let row=conn.query_one("INSERT INTO lab_studies(profile,title,summary,kind,payload) VALUES($1,$2,$3,$4,$5) RETURNING id",
            &[&profile,&title,&summary,&kind,&body.payload]).map_err(anyhow::Error::from)?;
        Ok(Json(json!({"id":row.get::<_,i64>(0)})))
    }).await
}

async fn participants(
    State(app): State<App>,
    Query(q): Query<ProfileQuery>,
) -> ApiResult<Json<Value>> {
    let id = profile(&app, q.profile.as_deref())?;
    blocking(move || {
        let mut conn =
            pg::connect()?.ok_or_else(|| unavailable("Registre PostgreSQL indisponible."))?;
        Ok(Json(pg::participants(&mut conn, &id)?))
    })
    .await
}
#[derive(Deserialize)]
struct ParticipantInput {
    name: String,
    kind: String,
    elo: Option<i32>,
    external_id: Option<String>,
}
async fn add_participants(
    State(app): State<App>,
    Query(q): Query<ProfileQuery>,
    Json(value): Json<Value>,
) -> ApiResult<Json<Value>> {
    let id = profile(&app, q.profile.as_deref())?;
    let list = value
        .as_array()
        .or_else(|| value["participants"].as_array())
        .ok_or_else(|| bad_request("Une liste de participants est attendue."))?;
    if list.is_empty() || list.len() > 500 {
        return Err(bad_request("Entre 1 et 500 participants sont attendus."));
    }
    let list: Vec<ParticipantInput> =
        serde_json::from_value(Value::Array(list.clone())).map_err(|_| {
            bad_request("Nom, type, cote et identifiant doivent avoir le format attendu.")
        })?;
    blocking(move||{
        let mut conn=pg::connect()?.ok_or_else(||unavailable("Registre PostgreSQL indisponible."))?;
        let mut tx=conn.transaction().map_err(anyhow::Error::from)?;
        let mut imported=0;
        let count=list.len();
        for p in list {
            let name=p.name.trim();
            if name.is_empty()||name.chars().count()>80||!matches!(p.kind.as_str(),"human"|"bot")
                ||p.elo.is_some_and(|n|!(0..=4000).contains(&n)) {
                return Err(bad_request("Nom, type ou cote de participant invalide."));
            }
            let key=p.external_id.unwrap_or_else(||format!("{}:{}",p.kind,name.to_lowercase()));
            if key.is_empty()||key.chars().count()>140 {return Err(bad_request("Identifiant externe invalide."));}
            imported+=tx.execute("INSERT INTO lab_participants(profile,name,kind,elo,source,external_id) VALUES($1,$2,$3,$4,'import',$5) ON CONFLICT DO NOTHING",
                &[&id,&name,&p.kind,&p.elo,&key]).map_err(anyhow::Error::from)?;
        }
        tx.commit().map_err(anyhow::Error::from)?;
        Ok(Json(json!({"imported":imported,"duplicates":count as u64-imported})))
    }).await
}

async fn word_stats(State(app): State<App>, Query(f): Query<Filter>) -> ApiResult<Json<Value>> {
    let id = profile(&app, f.profile.as_deref())?;
    blocking(move || {
        let mut conn =
            pg::connect()?.ok_or_else(|| unavailable("L’index des mots nécessite PostgreSQL."))?;
        Ok(Json(pg::word_stats(&mut conn, &id, &f)?))
    })
    .await
}

#[derive(Deserialize)]
struct PolicyQuery {
    turn: u16,
    elo: f64,
    profile: Option<String>,
}

async fn what_if(
    State(app): State<App>,
    Path(id): Path<u32>,
    Query(q): Query<PolicyQuery>,
) -> ApiResult<Json<Value>> {
    let profile = profile(&app, q.profile.as_deref())?;
    if !q.elo.is_finite() || !(600.0..=3000.0).contains(&q.elo) || q.turn == 0 {
        return Err(bad_request("Elo cible ou tour invalide."));
    }
    let permit = app.review_slots.clone().try_acquire_owned().map_err(|_| {
        ApiError(
            StatusCode::TOO_MANY_REQUESTS,
            "Deux analyses sont déjà en cours.".into(),
        )
    })?;
    blocking(move||{
        use rand::SeedableRng;
        use wolges::{equity,game_state::GameState,movegen::Play};
        let _permit=permit;
        let engine=engine(&app,&profile)?;
        let bytes=detail(&app,id,&profile)?.ok_or_else(||not_found("Partie introuvable."))?;
        let game:Game=serde_json::from_slice(&bytes).map_err(anyhow::Error::from)?;
        let index=usize::from(q.turn-1);
        let played=game.moves.get(index).ok_or_else(||bad_request("Tour hors limites."))?;
        let mut state=GameState::new(&engine.config);
        state.turn=played.player;
        state.bag.set_from_iter(std::iter::repeat_n(0,played.bag_before));
        state.players[usize::from(played.player)].rack=played.rack.chars()
            .map(|c|engine.symbol_id(c)).collect::<Result<Vec<_>>>()?;
        for m in &game.moves[..index] {
            for tile in &m.tiles {
                let value=engine.symbol_id(tile.letter)? | if tile.blank {128} else {0};
                state.board_tiles[usize::from(tile.row)*15+usize::from(tile.col)]=value;
            }
        }
        let stats=meta(&app,"public",&profile)?.unwrap_or_default();
        let (scale,cal)=strength(&engine,&stats)?;
        let target=if let Some(c)=cal.as_ref() {scale.at_calibrated(q.elo,c)?} else {scale.at(q.elo)?};
        let mut generator=engine.generator();
        let mut rng=rand_xoshiro::Xoshiro256PlusPlus::seed_from_u64((u64::from(game.seed)<<16)|u64::from(q.turn));
        let play=target.choose_move(&engine,&mut generator,&state,&mut rng);
        let (word,tiles,_)=engine.describe(&state.board_tiles,&play);
        let score=match &play {Play::Place{score,..}=>equity::descale_score(*score),_=>0};
        let evaluation=engine.evaluate(&play,&state.current_player().rack,played.bag_before,scale.expert);
        Ok(Json(json!({"target_elo":target.applied_elo,"word":word,"score":score,"tiles":tiles,
            "evaluation":f64::from(evaluation)/4.0,"score_delta":score-played.score,
            "method":"Politique du bot au même tour. La valeur statique combine points immédiats et reliquat."})))
    }).await
}
