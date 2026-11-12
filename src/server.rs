use crate::{
    engine::{BASE_EXPERT, Engine, ExpertPolicy, Game, LEVELS},
    import, pg, review,
    store::{self, Filter},
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
    env,
    path::{Path as FilePath, PathBuf},
    sync::Arc,
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
    expert: ExpertPolicy,
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

fn meta(path: &FilePath, key: &str) -> Result<Option<Value>> {
    if let Some(mut client) = pg::connect()? {
        return pg::meta(&mut client, key);
    }
    if path.is_file() {
        return store::get(&store::open(path, false)?, key);
    }
    Ok(None)
}
fn detail(path: &FilePath, id: u32) -> Result<Option<Vec<u8>>> {
    if let Some(mut client) = pg::connect()? {
        return pg::detail(&mut client, id);
    }
    if path.is_file() {
        return store::detail(&store::open(path, false)?, id);
    }
    Ok(None)
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

async fn stats(State(app): State<App>) -> ApiResult<Json<Value>> {
    blocking(move || {
        let mut stats = meta(&app.db,"public")?.unwrap_or_else(|| json!({
            "completed":0, "target":100000, "running":false, "levels":[], "matchups":[], "words":[], "recent":[],
            "lexicon":app.engine.lexicon, "lexicon_words":app.engine.words
        }));
        stats["storage"] = json!(if env::var_os("LETTRINE_PG_HOST").is_some() || env::var_os("LETTRINE_DATABASE_URL").is_some() {
            "postgresql"
        } else { "sqlite" });
        stats["ollama_model"] = json!(app.ollama.as_ref().map(|(_, model)| model));
        Ok(Json(stats))
    }).await
}
async fn games(State(app): State<App>, Query(f): Query<Filter>) -> ApiResult<Json<Value>> {
    blocking(move || {
        if let Some(mut client) = pg::connect()? {
            return Ok(Json(pg::list(&mut client, &f)?));
        }
        if app.db.is_file() {
            return Ok(Json(store::list(&store::open(&app.db, false)?, &f)?));
        }
        Ok(Json(json!({"items":[],"total":0})))
    })
    .await
}
async fn game(State(app): State<App>, Path(id): Path<u32>) -> ApiResult<Response> {
    blocking(move || {
        let bytes = detail(&app.db, id)?.ok_or_else(|| not_found("Partie introuvable."))?;
        Ok(([(header::CONTENT_TYPE, "application/json")], bytes).into_response())
    })
    .await
}

#[derive(Deserialize)]
struct Turn {
    turn: u16,
}

async fn position(app: App, id: u32, turn: u16) -> ApiResult<review::Review> {
    let permit = app.review_slots.clone().try_acquire_owned().map_err(|_| {
        ApiError(
            StatusCode::TOO_MANY_REQUESTS,
            "Deux analyses sont déjà en cours.".into(),
        )
    })?;
    blocking(move || {
        let _permit = permit;
        let campaign =
            meta(&app.db, "campaign")?.ok_or_else(|| not_found("Campagne introuvable."))?;
        if campaign["lexicon_sha256"].as_str() != Some(&app.engine.digest)
            || campaign["engine"].as_str() != Some(&app.engine.profile.id)
        {
            return Err(ApiError(
                StatusCode::CONFLICT,
                "Le lexique de la campagne doit être chargé pour l’analyse.".into(),
            ));
        }
        let bytes = detail(&app.db, id)?.ok_or_else(|| not_found("Partie introuvable."))?;
        let game = serde_json::from_slice::<Game>(&bytes).map_err(anyhow::Error::from)?;
        if turn == 0 || usize::from(turn) > game.turns {
            return Err(bad_request("Tour hors limites."));
        }
        Ok(review::analyze(&app.engine, &game, turn)?)
    })
    .await
}
async fn analyze(
    State(app): State<App>,
    Path(id): Path<u32>,
    Query(Turn { turn }): Query<Turn>,
) -> ApiResult<Json<review::Review>> {
    Ok(Json(position(app, id, turn).await?))
}
async fn challenge(
    State(app): State<App>,
    Path(id): Path<u32>,
    Query(Turn { turn }): Query<Turn>,
) -> ApiResult<Json<Value>> {
    let (base, model) = app
        .ollama
        .clone()
        .ok_or_else(|| unavailable("Ollama n’est pas configuré."))?;
    let _permit = app.model_slot.clone().try_acquire_owned().map_err(|_| {
        ApiError(
            StatusCode::TOO_MANY_REQUESTS,
            "Un commentaire Llama est déjà en cours.".into(),
        )
    })?;
    let review = position(app.clone(), id, turn).await?;
    let facts = serde_json::to_value(&review).map_err(anyhow::Error::from)?;
    let request = json!({
        "model":model, "stream":false, "keep_alive":"5m", "options":{"temperature":0.15,"num_predict":350,"num_ctx":4096},
        "messages":[
            {"role":"system","content":"Tu es un analyste francophone de jeu de lettres. Réponds en français en deux courts paragraphes. Explique le meilleur contre-argument au coup joué avec les chiffres fournis. Le score est immédiat et evaluation est une marge moyenne simulée en points sur trois demi-coups, avec une erreur-type uncertainty. Ne déduis pas de certitude de petites différences. Ne propose aucune autre lettre, placement ou mot et n’invente aucun fait. Si le coup retenu reste raisonnable, explique pourquoi."},
            {"role":"user","content": facts.to_string()}
        ]
    });
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
            .map_err(|_| unavailable("La tâche Llama n’a pas pu être déposée."))?;
        let started = tokio::time::Instant::now();
        loop {
            let state: Option<String> = redis::cmd("GET")
                .arg(&result_key)
                .query_async(&mut connection)
                .await
                .map_err(|_| unavailable("Le résultat Llama est indisponible."))?;
            if let Some(state) = state {
                let parsed: Value = serde_json::from_str(&state).map_err(anyhow::Error::from)?;
                if parsed["state"] == "done" {
                    return Ok(Json(parsed));
                }
                if parsed["state"] == "error" {
                    return Err(unavailable(
                        parsed["error"]
                            .as_str()
                            .unwrap_or("Le worker Llama a échoué."),
                    ));
                }
            }
            if started.elapsed() >= Duration::from_secs(87) {
                return Err(unavailable(
                    "Le worker Llama a dépassé le délai de réponse.",
                ));
            }
            tokio::time::sleep(Duration::from_millis(350)).await;
        }
    }
    let result = app
        .http
        .post(format!("{}/api/chat", base.trim_end_matches('/')))
        .json(&request)
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
    let commentary = answer["message"]["content"]
        .as_str()
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| unavailable("Ollama n’a renvoyé aucun commentaire."))?;
    Ok(Json(json!({"model":model,"commentary":commentary})))
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
            None => Ok(ExportSource::Sqlite(store::open(&app.db, false)?)),
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
            ExportSource::Postgres(conn) => pg::export(conn, &f, imports, send),
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
}
async fn adaptive(State(app): State<App>, Query(q): Query<Rating>) -> ApiResult<Json<Value>> {
    blocking(move || {
        let elo = q.elo.unwrap_or(1200.0);
        if !elo.is_finite() || !(100.0..=3000.0).contains(&elo) { return Err(bad_request("Elo attendu entre 100 et 3000.")); }
        let stats = meta(&app.db,"public")?.unwrap_or_default();
        let levels = stats["levels"].as_array().filter(|xs| !xs.is_empty()).ok_or_else(|| unavailable("Calibration en attente."))?;
        let nearest = levels.iter().min_by(|a,b| {
            let distance = |v: &Value| (v["rating"].as_f64().unwrap_or(1200.0)-elo).abs();
            distance(a).total_cmp(&distance(b))
        });
        let bot_elo = nearest.and_then(|b| b["rating"].as_f64()).unwrap_or(1200.0);
        Ok(Json(json!({"player_elo":elo, "level":nearest, "expected_player_score":1.0/(1.0+10f64.powf((bot_elo-elo)/400.0)), "sample_games":stats["completed"]})))
    }).await
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
}
async fn live(State(app): State<App>, Query(q): Query<Live>) -> ApiResult<Response> {
    let levels = [q.a.unwrap_or(3), q.b.unwrap_or(3)];
    if levels.iter().any(|&x| x > 3) {
        return Err(bad_request("Choisissez deux niveaux entre 0 et 3."));
    }
    let seed = q.seed.unwrap_or_else(rand::random::<u32>);
    let game = blocking(move || {
        let mut generator = app.engine.generator();
        let mut state = wolges::game_state::GameState::new(&app.engine.config);
        Ok(app
            .engine
            .play_seeded(&mut generator, &mut state, 1, seed, levels, [app.expert; 2])?)
    })
    .await?;
    let labels = levels.map(|i| LEVELS[i as usize]);
    let mut events = vec![
        json!({"type":"start","seed":seed,"levels":levels,"labels":labels,"turns":game.turns}),
    ];
    let mut scores = [0, 0];
    for m in &game.moves {
        scores[m.player as usize] += m.score;
        events.push(json!({"type":"move","turn":m.turn,"player":m.player,"action":m.kind,
            "word":m.word,"score":m.score,"tiles":m.tiles,"scores":scores,"bag_before":m.bag_before}));
    }
    events.push(json!({"type":"done","game":game}));
    let stream = futures_util::stream::unfold(events.into_iter(), |mut events| async {
        let event = events.next()?;
        if event["type"] == "move" {
            tokio::time::sleep(Duration::from_millis(850)).await;
        }
        Some((
            Ok::<_, std::convert::Infallible>(Event::default().data(event.to_string())),
            events,
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
    let engine_digest = engine.digest.clone();
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
        expert: model_policy(&engine_digest),
        http: reqwest::Client::builder()
            .timeout(Duration::from_secs(90))
            .build()?,
    };
    let apk = env::var("LETTRINE_APK").unwrap_or_else(|_| "android/artifacts/lettrine.apk".into());
    let router = Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/api/stats", get(stats))
        .route("/api/search", get(search))
        .route(
            "/api/profiles",
            get(|| async { blocking(|| Ok(Json(crate::profiles::list()?))).await }),
        )
        .route("/api/live", get(live))
        .route(
            "/api/llm-benchmarks",
            get(|| file_json("data/models/llm-benchmarks.json")),
        )
        .route(
            "/api/models",
            get(|| file_json("data/models/fr-bots-v1.json")),
        )
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
