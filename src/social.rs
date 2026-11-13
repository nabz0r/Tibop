use crate::{engine::Engine, multiplayer_game::Game, social_google};
use anyhow::{Result, ensure};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
};
use rand::RngExt;
use rusqlite::{Connection, OptionalExtension, Row, Transaction, TransactionBehavior, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    path::Path as FilePath,
    sync::{Arc, Mutex},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

mod tournament;

const LEXICON: &str = "8d024eba26b05909beb31a26af5a99ad501efebb895f9488ae1eb09f6487f81a";
const INVITE_SECONDS: i64 = 2 * 24 * 60 * 60;
const NONCE_SECONDS: i64 = 5 * 60;
const MAX_FRIENDS: i64 = 100;
const SCHEMA: &str = "
    CREATE TABLE IF NOT EXISTS social_users(
        id TEXT PRIMARY KEY,
        nickname TEXT NOT NULL,
        country TEXT,
        google_sub TEXT UNIQUE
    );
    CREATE TABLE IF NOT EXISTS social_sessions(
        token TEXT PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE
    );
    CREATE TABLE IF NOT EXISTS social_friends(
        a TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE,
        b TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE,
        PRIMARY KEY(a,b), CHECK(a<b)
    );
    CREATE INDEX IF NOT EXISTS social_friends_b ON social_friends(b);
    CREATE TABLE IF NOT EXISTS social_blocks(
        owner TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE,
        other TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE,
        PRIMARY KEY(owner,other), CHECK(owner<>other)
    );
    CREATE TABLE IF NOT EXISTS social_invites(
        token TEXT PRIMARY KEY,
        owner TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL,
        accepted_by TEXT REFERENCES social_users(id) ON DELETE CASCADE
    );
    CREATE INDEX IF NOT EXISTS social_invites_owner ON social_invites(owner);
    CREATE INDEX IF NOT EXISTS social_invites_expires ON social_invites(expires_at);
    CREATE TABLE IF NOT EXISTS social_duels(
        id TEXT PRIMARY KEY,
        a TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE,
        b TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE,
        puzzle INTEGER NOT NULL,
        score_a INTEGER,
        score_b INTEGER,
        move_a TEXT,
        move_b TEXT,
        rematch_of TEXT UNIQUE REFERENCES social_duels(id) ON DELETE SET NULL,
        created_at INTEGER NOT NULL,
        CHECK(a<b)
    );
    CREATE INDEX IF NOT EXISTS social_duels_a ON social_duels(a,created_at);
    CREATE INDEX IF NOT EXISTS social_duels_b ON social_duels(b,created_at);
    CREATE UNIQUE INDEX IF NOT EXISTS social_active_duel ON social_duels(a,b)
        WHERE score_a IS NULL OR score_b IS NULL;
    CREATE TABLE IF NOT EXISTS social_google_nonces(
        session TEXT PRIMARY KEY REFERENCES social_sessions(token) ON DELETE CASCADE,
        nonce TEXT NOT NULL,
        expires_at INTEGER NOT NULL
    );
";

#[derive(Debug)]
struct ApiError(StatusCode, String);
type Api<T> = std::result::Result<T, ApiError>;

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({ "error": self.1 }))).into_response()
    }
}

impl From<rusqlite::Error> for ApiError {
    fn from(error: rusqlite::Error) -> Self {
        eprintln!("Stockage social : {error}");
        err(StatusCode::INTERNAL_SERVER_ERROR, "Stockage indisponible.")
    }
}

fn err(status: StatusCode, message: &str) -> ApiError {
    ApiError(status, message.into())
}

fn invalid(message: &str) -> ApiError {
    err(StatusCode::BAD_REQUEST, message)
}

fn conflict(message: &str) -> ApiError {
    err(StatusCode::CONFLICT, message)
}

fn json_error(error: JsonRejection) -> ApiError {
    err(error.status(), "Requête JSON invalide.")
}

fn missing(message: &str) -> ApiError {
    err(StatusCode::NOT_FOUND, message)
}

#[derive(Serialize)]
struct Puzzle {
    date: &'static str,
    opening_word: &'static str,
    opening_start: usize,
    opening_end: usize,
    rack: &'static str,
    identity: &'static str,
    #[serde(skip)]
    board_codes: Vec<u8>,
    #[serde(skip)]
    rack_codes: Vec<u8>,
}

struct Social {
    db: Mutex<Connection>,
    engine: Engine,
    puzzles: Vec<Puzzle>,
    #[cfg(test)]
    tournament_time: std::sync::atomic::AtomicI64,
}

impl Social {
    fn open(db: &FilePath, engine: Engine) -> Result<Self> {
        ensure!(engine.digest == LEXICON, "lexique social incompatible");
        let puzzles = include_str!("social_puzzles.tsv")
            .lines()
            .map(|line| {
                let fields = line.split('\t').collect::<Vec<_>>();
                ensure!(fields.len() == 6, "position sociale invalide");
                let opening_start: usize = fields[2].parse()?;
                let opening_end: usize = fields[3].parse()?;
                let step = if opening_start / 15 == opening_end / 15 {
                    1
                } else {
                    15
                };
                let mut board_codes = vec![0; 225];
                for (i, letter) in fields[1].chars().enumerate() {
                    board_codes[opening_start + i * step] = engine.symbol_id(letter)?;
                }
                let rack_codes = fields[4]
                    .chars()
                    .map(|letter| engine.symbol_id(letter))
                    .collect::<Result<Vec<_>>>()?;
                Ok(Puzzle {
                    date: fields[0],
                    opening_word: fields[1],
                    opening_start,
                    opening_end,
                    rack: fields[4],
                    identity: fields[5],
                    board_codes,
                    rack_codes,
                })
            })
            .collect::<Result<Vec<_>>>()?;
        ensure!(
            puzzles.len() >= tournament::CATALOG_SIZE,
            "catalogue social incomplet"
        );
        let conn = Connection::open(db)?;
        conn.busy_timeout(Duration::from_secs(5))?;
        conn.execute_batch("PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;")?;
        conn.execute_batch(SCHEMA)?;
        conn.execute_batch(tournament::SCHEMA)?;
        Ok(Self {
            db: Mutex::new(conn),
            engine,
            puzzles,
            #[cfg(test)]
            tournament_time: std::sync::atomic::AtomicI64::new(0),
        })
    }
}

pub(crate) async fn serve(db: &FilePath, engine: Engine, address: &str) -> Result<()> {
    let app = Arc::new(Social::open(db, engine)?);
    let listener = tokio::net::TcpListener::bind(address).await?;
    eprintln!("Lettrine social sur http://{address}");
    axum::serve(listener, router(app)).await?;
    Ok(())
}

fn router(app: Arc<Social>) -> Router {
    Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/api/social/session", post(session))
        .route("/api/social/me", get(me))
        .route(
            "/api/social/profile",
            post(save_profile).delete(delete_profile),
        )
        .route("/api/social/friends/{id}", delete(unfriend))
        .route("/api/social/friends/{id}/block", post(block_friend))
        .route("/api/social/invites", post(invite))
        .route("/api/social/invites/accept", post(accept))
        .route("/api/social/duels", post(create_duel))
        .route("/api/social/duels/{id}/play", post(play))
        .route("/api/social/duels/{id}/rematch", post(rematch))
        .route("/api/social/google/challenge", post(google_challenge))
        .route("/api/social/google", post(google_link))
        .route("/api/social/tournament", get(tournament::get_weekend))
        .route(
            "/api/social/tournament/{id}/slots/{number}/play",
            post(tournament::play_slot),
        )
        .layer(DefaultBodyLimit::max(12 * 1024))
        .with_state(app)
}

async fn write<T: Send + 'static>(
    app: Arc<Social>,
    f: impl FnOnce(&Social, &Transaction<'_>) -> Api<T> + Send + 'static,
) -> Api<T> {
    tokio::task::spawn_blocking(move || {
        let mut db = app
            .db
            .lock()
            .map_err(|_| err(StatusCode::SERVICE_UNAVAILABLE, "Stockage indisponible."))?;
        let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let output = f(&app, &tx)?;
        tx.commit()?;
        Ok(output)
    })
    .await
    .map_err(|_| err(StatusCode::SERVICE_UNAVAILABLE, "Stockage indisponible."))?
}

fn hex(value: &str, len: usize) -> bool {
    value.len() == len
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn random_hex<const N: usize>() -> String {
    rand::rng()
        .random::<[u8; N]>()
        .into_iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn digest(value: &str) -> String {
    format!("{:x}", Sha256::digest(value.as_bytes()))
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

fn auth(headers: HeaderMap) -> Api<String> {
    headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .filter(|token| hex(token, 64))
        .map(digest)
        .ok_or_else(unauthorized)
}

fn unauthorized() -> ApiError {
    err(
        StatusCode::UNAUTHORIZED,
        "Session indisponible. Reconnectez-vous.",
    )
}

fn user_id(db: &Connection, session: &str) -> Api<String> {
    db.query_row(
        "SELECT user_id FROM social_sessions WHERE token=?1",
        [session],
        |row| row.get(0),
    )
    .optional()?
    .ok_or_else(unauthorized)
}

fn nickname(value: &str) -> Api<&str> {
    let name = value.trim();
    if !(1..=24).contains(&name.chars().count())
        || name.chars().any(|c| {
            c.is_control() || matches!(c, '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}')
        })
    {
        return Err(invalid("Le pseudo doit compter de 1 à 24 caractères."));
    }
    Ok(name)
}

fn country(value: Option<&str>) -> Api<Option<String>> {
    let Some(country) = value.map(str::trim).filter(|country| !country.is_empty()) else {
        return Ok(None);
    };
    if country.len() != 2 || !country.bytes().all(|c| c.is_ascii_alphabetic()) {
        return Err(invalid("Utilisez un code pays à deux lettres."));
    }
    Ok(Some(country.to_ascii_uppercase()))
}

#[derive(Clone, Serialize)]
struct Profile {
    id: String,
    nickname: String,
    country: Option<String>,
}

fn profile_row(row: &Row<'_>) -> rusqlite::Result<Profile> {
    Ok(Profile {
        id: row.get(0)?,
        nickname: row.get(1)?,
        country: row.get(2)?,
    })
}

fn profile(db: &Connection, id: &str) -> Api<Profile> {
    db.query_row(
        "SELECT id,nickname,country FROM social_users WHERE id=?1",
        [id],
        profile_row,
    )
    .optional()?
    .ok_or_else(|| missing("Joueur introuvable."))
}

fn private_profile(db: &Connection, id: &str) -> Api<Value> {
    let p = profile(db, id)?;
    let google_linked = db.query_row(
        "SELECT google_sub IS NOT NULL FROM social_users WHERE id=?1",
        [id],
        |row| row.get::<_, bool>(0),
    )?;
    Ok(json!({"id":p.id,"nickname":p.nickname,"country":p.country,"google_linked":google_linked}))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SessionRequest {
    token: String,
    nickname: Option<String>,
}

async fn session(
    State(app): State<Arc<Social>>,
    body: std::result::Result<Json<SessionRequest>, JsonRejection>,
) -> Api<Json<Value>> {
    let Json(body) = body.map_err(json_error)?;
    if !hex(&body.token, 64) {
        return Err(invalid("Identifiant de session invalide."));
    }
    let session = digest(&body.token);
    let supplied_name = body
        .nickname
        .as_deref()
        .map(nickname)
        .transpose()?
        .map(str::to_owned);
    write(app, move |_, db| {
        let existing = db
            .query_row(
                "SELECT user_id FROM social_sessions WHERE token=?1",
                [&session],
                |row| row.get::<_, String>(0),
            )
            .optional()?;
        let id = if let Some(existing) = existing {
            existing
        } else {
            let id = random_hex::<16>();
            let name = supplied_name.unwrap_or_else(|| format!("Joueur {}", &id[..5]));
            db.execute(
                "INSERT INTO social_users(id,nickname) VALUES(?1,?2)",
                params![id, name],
            )?;
            db.execute(
                "INSERT INTO social_sessions(token,user_id) VALUES(?1,?2)",
                params![session, id],
            )?;
            id
        };
        Ok(Json(json!({"profile":private_profile(db,&id)?})))
    })
    .await
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ProfileRequest {
    nickname: String,
    country: Option<String>,
}

async fn save_profile(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    body: std::result::Result<Json<ProfileRequest>, JsonRejection>,
) -> Api<Json<Value>> {
    let Json(body) = body.map_err(json_error)?;
    let session = auth(headers)?;
    let nickname = nickname(&body.nickname)?.to_owned();
    let country = country(body.country.as_deref())?;
    write(app, move |_, db| {
        let id = user_id(db, &session)?;
        db.execute(
            "UPDATE social_users SET nickname=?1,country=?2 WHERE id=?3",
            params![nickname, country, id],
        )?;
        Ok(Json(json!({"profile":private_profile(db,&id)?})))
    })
    .await
}

async fn delete_profile(State(app): State<Arc<Social>>, headers: HeaderMap) -> Api<Json<Value>> {
    let session = auth(headers)?;
    write(app, move |_, db| {
        let id = user_id(db, &session)?;
        db.execute("DELETE FROM social_users WHERE id=?1", [&id])?;
        Ok(Json(json!({"deleted":true})))
    })
    .await
}

fn pair<'a>(a: &'a str, b: &'a str) -> (&'a str, &'a str) {
    if a < b { (a, b) } else { (b, a) }
}

fn friends(db: &Connection, a: &str, b: &str) -> Api<bool> {
    let (a, b) = pair(a, b);
    Ok(db.query_row(
        "SELECT EXISTS(SELECT 1 FROM social_friends WHERE a=?1 AND b=?2)",
        params![a, b],
        |row| row.get(0),
    )?)
}

fn friend_count(db: &Connection, id: &str) -> Api<i64> {
    Ok(db.query_row(
        "SELECT count(*) FROM social_friends WHERE a=?1 OR b=?1",
        [id],
        |row| row.get(0),
    )?)
}

async fn unfriend(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    Path(other): Path<String>,
) -> Api<Json<Value>> {
    change_friend(app, headers, other, false).await
}

async fn block_friend(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    Path(other): Path<String>,
) -> Api<Json<Value>> {
    change_friend(app, headers, other, true).await
}

async fn change_friend(
    app: Arc<Social>,
    headers: HeaderMap,
    other: String,
    block: bool,
) -> Api<Json<Value>> {
    let session = auth(headers)?;
    write(app, move |_, db| {
        let id = user_id(db, &session)?;
        if id == other || !hex(&other, 32) {
            return Err(invalid("Joueur invalide."));
        }
        if block {
            profile(db, &other)?;
            db.execute(
                "INSERT OR IGNORE INTO social_blocks(owner,other) VALUES(?1,?2)",
                params![id, other],
            )?;
        }
        let (a, b) = pair(&id, &other);
        db.execute(
            "DELETE FROM social_friends WHERE a=?1 AND b=?2",
            params![a, b],
        )?;
        db.execute(
            "DELETE FROM social_duels WHERE a=?1 AND b=?2 AND (score_a IS NULL OR score_b IS NULL)",
            params![a, b],
        )?;
        Ok(Json(json!({"ok":true})))
    })
    .await
}

async fn invite(State(app): State<Arc<Social>>, headers: HeaderMap) -> Api<Json<Value>> {
    let session = auth(headers)?;
    write(app, move |_, db| {
        let owner = user_id(db, &session)?;
        let now = now();
        db.execute("DELETE FROM social_invites WHERE expires_at<=?1", [now])?;
        if db.query_row(
            "SELECT count(*) FROM social_invites WHERE owner=?1 AND accepted_by IS NULL",
            [&owner],
            |row| row.get::<_, i64>(0),
        )? >= 20
        {
            return Err(err(
                StatusCode::TOO_MANY_REQUESTS,
                "Trop d’invitations en cours.",
            ));
        }
        let token = random_hex::<16>();
        db.execute(
            "INSERT INTO social_invites(token,owner,expires_at) VALUES(?1,?2,?3)",
            params![digest(&token), owner, now + INVITE_SECONDS],
        )?;
        Ok(Json(json!({"token":token,"expires_at":now+INVITE_SECONDS})))
    })
    .await
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct InviteRequest {
    token: String,
}

async fn accept(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    body: std::result::Result<Json<InviteRequest>, JsonRejection>,
) -> Api<Json<Value>> {
    let Json(body) = body.map_err(json_error)?;
    let session = auth(headers)?;
    if !hex(&body.token, 32) {
        return Err(invalid("Invitation invalide."));
    }
    let token = digest(&body.token);
    write(app, move |_, db| {
        let recipient = user_id(db, &session)?;
        let (owner, used) = db
            .query_row(
                "SELECT owner,accepted_by FROM social_invites WHERE token=?1 AND expires_at>?2",
                params![token, now()],
                |row| Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?)),
            )
            .optional()?
            .ok_or_else(|| missing("Invitation introuvable ou expirée."))?;
        if recipient == owner {
            return Err(invalid("Cette invitation est la vôtre."));
        }
        let blocked = db.query_row(
            "SELECT EXISTS(SELECT 1 FROM social_blocks
            WHERE (owner=?1 AND other=?2) OR (owner=?2 AND other=?1))",
            params![owner, recipient],
            |row| row.get::<_, bool>(0),
        )?;
        if blocked {
            return Err(missing("Invitation indisponible."));
        }
        if let Some(used) = used {
            if used != recipient || !friends(db, &owner, &recipient)? {
                return Err(conflict("Invitation déjà utilisée."));
            }
        } else {
            if !friends(db, &owner, &recipient)?
                && (friend_count(db, &owner)? >= MAX_FRIENDS
                    || friend_count(db, &recipient)? >= MAX_FRIENDS)
            {
                return Err(conflict("La liste d’amis est pleine."));
            }
            let (a, b) = pair(&owner, &recipient);
            db.execute(
                "INSERT OR IGNORE INTO social_friends(a,b) VALUES(?1,?2)",
                params![a, b],
            )?;
            db.execute(
                "UPDATE social_invites SET accepted_by=?1 WHERE token=?2",
                params![recipient, token],
            )?;
        }
        Ok(Json(json!({"friend":profile(db,&owner)?})))
    })
    .await
}

#[derive(Clone)]
struct Duel {
    id: String,
    a: String,
    b: String,
    puzzle: usize,
    score_a: Option<i32>,
    score_b: Option<i32>,
    move_a: Option<String>,
    move_b: Option<String>,
    rematch_id: Option<String>,
}

const DUEL_SELECT: &str = "SELECT d.id,d.a,d.b,d.puzzle,d.score_a,d.score_b,d.move_a,d.move_b,
    (SELECT r.id FROM social_duels r WHERE r.rematch_of=d.id) FROM social_duels d";

fn duel_row(row: &Row<'_>) -> rusqlite::Result<Duel> {
    Ok(Duel {
        id: row.get(0)?,
        a: row.get(1)?,
        b: row.get(2)?,
        puzzle: row.get::<_, u16>(3)?.into(),
        score_a: row.get(4)?,
        score_b: row.get(5)?,
        move_a: row.get(6)?,
        move_b: row.get(7)?,
        rematch_id: row.get(8)?,
    })
}

fn duel(db: &Connection, id: &str, user: &str) -> Api<Duel> {
    db.query_row(
        &format!("{DUEL_SELECT} WHERE d.id=?1 AND (d.a=?2 OR d.b=?2)"),
        params![id, user],
        duel_row,
    )
    .optional()?
    .ok_or_else(|| missing("Duel introuvable."))
}

fn duel_json(app: &Social, db: &Connection, duel: &Duel, user: &str) -> Api<Value> {
    let (other, score, opponent_score) = if duel.a == user {
        (&duel.b, duel.score_a, duel.score_b)
    } else {
        (&duel.a, duel.score_b, duel.score_a)
    };
    let completed = score.is_some() && opponent_score.is_some();
    let winner = match (score, opponent_score) {
        (Some(a), Some(b)) if a > b => Some(user),
        (Some(a), Some(b)) if a < b => Some(other.as_str()),
        _ => None,
    };
    let status = if completed {
        "finished"
    } else if score.is_some() {
        "waiting"
    } else {
        "your_turn"
    };
    let puzzle = &app.puzzles[duel.puzzle];
    Ok(json!({
        "id":duel.id, "opponent":profile(db,other)?, "date":puzzle.date, "puzzle":puzzle,
        "score":score, "opponent_score":if completed {opponent_score} else {None},
        "status":status, "winner":winner, "rematch_id":duel.rematch_id
    }))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct DuelRequest {
    friend_id: String,
}

fn active_duel(db: &Connection, a: &str, b: &str) -> Api<Option<Duel>> {
    Ok(db
        .query_row(
            &format!(
                "{DUEL_SELECT} WHERE d.a=?1 AND d.b=?2
        AND (d.score_a IS NULL OR d.score_b IS NULL)"
            ),
            params![a, b],
            duel_row,
        )
        .optional()?)
}

fn insert_duel(
    app: &Social,
    db: &Connection,
    a: &str,
    b: &str,
    previous: Option<&str>,
) -> Api<Duel> {
    if !friends(db, a, b)? {
        return Err(missing("Ami introuvable."));
    }
    if let Some(previous) = previous {
        if let Some(existing) = db
            .query_row(
                &format!("{DUEL_SELECT} WHERE d.rematch_of=?1"),
                [previous],
                duel_row,
            )
            .optional()?
        {
            return Ok(existing);
        }
    }
    if let Some(active) = active_duel(db, a, b)? {
        if previous.is_some() {
            return Err(conflict("Un duel est déjà en cours avec cet ami."));
        }
        return Ok(active);
    }
    let count: i64 = db.query_row(
        "SELECT count(*) FROM social_duels WHERE a=?1 AND b=?2",
        params![a, b],
        |row| row.get(0),
    )?;
    let id = random_hex::<16>();
    db.execute(
        "INSERT INTO social_duels(id,a,b,puzzle,created_at,rematch_of)
        VALUES(?1,?2,?3,?4,?5,?6)",
        params![id, a, b, count % app.puzzles.len() as i64, now(), previous],
    )?;
    duel(db, &id, a)
}

async fn create_duel(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    body: std::result::Result<Json<DuelRequest>, JsonRejection>,
) -> Api<Json<Value>> {
    let Json(body) = body.map_err(json_error)?;
    let session = auth(headers)?;
    write(app, move |app, db| {
        let id = user_id(db, &session)?;
        let (a, b) = pair(&id, &body.friend_id);
        let duel = insert_duel(app, db, a, b, None)?;
        Ok(Json(json!({"duel":duel_json(app,db,&duel,&id)?})))
    })
    .await
}

async fn rematch(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    Path(duel_id): Path<String>,
) -> Api<Json<Value>> {
    let session = auth(headers)?;
    write(app, move |app, db| {
        let user = user_id(db, &session)?;
        let previous = duel(db, &duel_id, &user)?;
        if previous.score_a.is_none() || previous.score_b.is_none() {
            return Err(conflict("Terminez le duel avant de proposer une revanche."));
        }
        let duel = insert_duel(app, db, &previous.a, &previous.b, Some(&duel_id))?;
        Ok(Json(json!({"duel":duel_json(app,db,&duel,&user)?})))
    })
    .await
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct MoveRequest {
    tiles: Vec<MoveTile>,
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct MoveTile {
    position: usize,
    rack_index: usize,
    letter: char,
}

impl MoveRequest {
    fn prepare(mut self) -> Api<Self> {
        if !(1..=7).contains(&self.tiles.len())
            || self.tiles.iter().any(|tile| {
                tile.position >= 225 || tile.rack_index >= 7 || !tile.letter.is_ascii_uppercase()
            })
        {
            return Err(invalid("Placement invalide."));
        }
        self.tiles.sort_unstable_by_key(|tile| tile.position);
        Ok(self)
    }

    fn canonical(&self) -> String {
        serde_json::to_string(&self.tiles).expect("tuiles JSON")
    }

    fn score(&self, app: &Social, puzzle: &Puzzle) -> Api<i32> {
        let placements: Vec<_> = self
            .tiles
            .iter()
            .map(|tile| (tile.position, tile.rack_index, tile.letter))
            .collect();
        Game::score_position(
            &app.engine,
            &puzzle.board_codes,
            &puzzle.rack_codes,
            &placements,
        )
        .map_err(|message| invalid(&message))
    }
}

async fn play(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    Path(duel_id): Path<String>,
    body: std::result::Result<Json<MoveRequest>, JsonRejection>,
) -> Api<Json<Value>> {
    let Json(body) = body.map_err(json_error)?;
    let session = auth(headers)?;
    let body = body.prepare()?;
    let canonical = body.canonical();
    write(app, move |app, db| {
        let user = user_id(db, &session)?;
        let previous = duel(db, &duel_id, &user)?;
        if !friends(db, &previous.a, &previous.b)? {
            return Err(missing("Duel indisponible."));
        }
        let seat_a = previous.a == user;
        let prior_move = if seat_a {
            previous.move_a
        } else {
            previous.move_b
        };
        if let Some(prior_move) = prior_move {
            if canonical != prior_move {
                return Err(conflict("Vous avez déjà joué ce duel."));
            }
        } else {
            let puzzle = &app.puzzles[previous.puzzle];
            let score = body.score(app, puzzle)?;
            let update = if seat_a {
                "UPDATE social_duels SET score_a=?1,move_a=?2 WHERE id=?3"
            } else {
                "UPDATE social_duels SET score_b=?1,move_b=?2 WHERE id=?3"
            };
            db.execute(update, params![score, canonical, duel_id])?;
        }
        let duel = duel(db, &duel_id, &user)?;
        Ok(Json(json!({"duel":duel_json(app,db,&duel,&user)?})))
    })
    .await
}

#[derive(Default)]
struct Standing {
    wins: u32,
    losses: u32,
    draws: u32,
    points: i64,
}

impl Standing {
    fn add(&mut self, score: i32, other: i32) {
        self.points += i64::from(score);
        match score.cmp(&other) {
            std::cmp::Ordering::Greater => self.wins += 1,
            std::cmp::Ordering::Less => self.losses += 1,
            std::cmp::Ordering::Equal => self.draws += 1,
        }
    }
}

fn ranking(db: &Connection, me: Profile, contacts: &[Profile]) -> Api<Vec<Value>> {
    let id = me.id.clone();
    let mut rows: HashMap<_, _> = contacts
        .iter()
        .cloned()
        .chain(std::iter::once(me))
        .map(|profile| (profile.id.clone(), (profile, Standing::default())))
        .collect();
    let mut scores = db.prepare("SELECT d.a,d.b,d.score_a,d.score_b FROM social_duels d
        JOIN social_friends f ON f.a=d.a AND f.b=d.b
        WHERE d.score_a IS NOT NULL AND d.score_b IS NOT NULL
        AND (d.a=?1 OR EXISTS(SELECT 1 FROM social_friends WHERE (a=?1 AND b=d.a) OR (b=?1 AND a=d.a)))
        AND (d.b=?1 OR EXISTS(SELECT 1 FROM social_friends WHERE (a=?1 AND b=d.b) OR (b=?1 AND a=d.b)))")?;
    let scores = scores.query_map([id], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, i32>(2)?,
            row.get::<_, i32>(3)?,
        ))
    })?;
    for score in scores {
        let (a, b, score_a, score_b) = score?;
        rows.get_mut(&a)
            .expect("joueur classé")
            .1
            .add(score_a, score_b);
        rows.get_mut(&b)
            .expect("joueur classé")
            .1
            .add(score_b, score_a);
    }
    let mut rows = rows.into_values().collect::<Vec<_>>();
    rows.sort_by(|(pa, a), (pb, b)| {
        b.wins
            .cmp(&a.wins)
            .then_with(|| b.draws.cmp(&a.draws))
            .then_with(|| b.points.cmp(&a.points))
            .then_with(|| pa.nickname.cmp(&pb.nickname))
            .then_with(|| pa.id.cmp(&pb.id))
    });
    Ok(rows
        .into_iter()
        .map(|(p, s)| {
            json!({
                "id":p.id,"nickname":p.nickname,"country":p.country,"wins":s.wins,
                "losses":s.losses,"draws":s.draws,"points":s.points,
            })
        })
        .collect())
}

async fn me(State(app): State<Arc<Social>>, headers: HeaderMap) -> Api<Json<Value>> {
    let session = auth(headers)?;
    write(app, move |app, db| {
        let user = user_id(db,&session)?;
        let mut contacts = db.prepare("SELECT u.id,u.nickname,u.country FROM social_friends f
            JOIN social_users u ON u.id=CASE WHEN f.a=?1 THEN f.b ELSE f.a END
            WHERE f.a=?1 OR f.b=?1 ORDER BY u.nickname,u.id")?;
        let contacts = contacts.query_map([&user], profile_row)?.collect::<rusqlite::Result<Vec<_>>>()?;
        let mut duels = db.prepare(&format!("{DUEL_SELECT}
            JOIN social_friends f ON f.a=d.a AND f.b=d.b WHERE d.a=?1 OR d.b=?1
            ORDER BY (d.score_a IS NULL OR d.score_b IS NULL) DESC,d.created_at DESC,d.rowid DESC LIMIT 100"))?;
        let duels = duels.query_map([&user],duel_row)?
            .map(|row| duel_json(app,db,&row?,&user)).collect::<Api<Vec<_>>>()?;
        Ok(Json(json!({
            "profile":private_profile(db,&user)?,
            "friends":contacts,
            "duels":duels,
            "ranking":ranking(db,profile(db,&user)?,&contacts)?,
            "google_client_id":social_google::client_id(),
        })))
    }).await
}

fn google_client_id() -> Api<String> {
    social_google::client_id().ok_or_else(|| {
        err(
            StatusCode::SERVICE_UNAVAILABLE,
            "La connexion Google n’est pas configurée. Le mode invité reste disponible.",
        )
    })
}

async fn google_challenge(State(app): State<Arc<Social>>, headers: HeaderMap) -> Api<Json<Value>> {
    let session = auth(headers)?;
    let client_id = google_client_id()?;
    write(app, move |_, db| {
        user_id(db, &session)?;
        let nonce = random_hex::<32>();
        db.execute(
            "INSERT INTO social_google_nonces(session,nonce,expires_at) VALUES(?1,?2,?3)
            ON CONFLICT(session) DO UPDATE SET nonce=excluded.nonce,expires_at=excluded.expires_at",
            params![session, digest(&nonce), now() + NONCE_SECONDS],
        )?;
        Ok(Json(json!({"nonce":nonce,"google_client_id":client_id})))
    })
    .await
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct GoogleRequest {
    id_token: String,
    nonce: String,
}

async fn google_link(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    body: std::result::Result<Json<GoogleRequest>, JsonRejection>,
) -> Api<Json<Value>> {
    let Json(body) = body.map_err(json_error)?;
    let session = auth(headers)?;
    google_client_id()?;
    if !hex(&body.nonce, 64) || body.id_token.len() > 8192 {
        return Err(invalid("Identifiant Google invalide."));
    }
    let key = session.clone();
    let nonce_digest = digest(&body.nonce);
    let user = write(app.clone(), move |_, db| {
        let user = user_id(db, &key)?;
        if db.execute(
            "DELETE FROM social_google_nonces WHERE session=?1 AND nonce=?2 AND expires_at>?3",
            params![key, nonce_digest, now()],
        )? != 1
        {
            return Err(unauthorized());
        }
        Ok(user)
    })
    .await?;
    let sub = social_google::verify(&body.id_token, &body.nonce)
        .await
        .map_err(|_| {
            err(
                StatusCode::UNAUTHORIZED,
                "Impossible de vérifier ce compte Google.",
            )
        })?;
    write(app, move |_, db| {
        if user_id(db, &session)? != user {
            return Err(conflict("La session a changé. Réessayez."));
        }
        let id = attach_google(db, &session, &user, &sub)?;
        Ok(Json(json!({"profile":private_profile(db,&id)?})))
    })
    .await
}

fn attach_google(db: &Connection, session: &str, user: &str, sub: &str) -> Api<String> {
    let bound = db.query_row(
        "SELECT google_sub FROM social_users WHERE id=?1",
        [user],
        |row| row.get::<_, Option<String>>(0),
    )?;
    if let Some(bound) = bound {
        if bound == sub {
            return Ok(user.to_owned());
        }
        return Err(conflict(
            "Ce profil est déjà associé à un autre compte Google.",
        ));
    }
    let existing = db
        .query_row(
            "SELECT id FROM social_users WHERE google_sub=?1",
            [sub],
            |row| row.get::<_, String>(0),
        )
        .optional()?;
    let Some(existing) = existing else {
        db.execute(
            "UPDATE social_users SET google_sub=?1 WHERE id=?2",
            params![sub, user],
        )?;
        return Ok(user.to_owned());
    };
    if db.query_row(
        "SELECT
        EXISTS(SELECT 1 FROM social_friends WHERE a=?1 OR b=?1)
        OR EXISTS(SELECT 1 FROM social_duels WHERE a=?1 OR b=?1)
        OR EXISTS(SELECT 1 FROM social_tournament_scores WHERE user_id=?1)
        OR EXISTS(SELECT 1 FROM social_blocks WHERE owner=?1 OR other=?1)",
        [user],
        |row| row.get::<_, bool>(0),
    )? {
        return Err(conflict(
            "Ce compte Google utilise déjà un autre profil Lettrine. Votre profil invité est conservé.",
        ));
    }
    db.execute(
        "UPDATE social_sessions SET user_id=?1 WHERE token=?2",
        params![existing, session],
    )?;
    db.execute("DELETE FROM social_users WHERE id=?1", [user])?;
    Ok(existing)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::Method;
    use tokio::task::JoinHandle;

    pub(super) fn engine() -> Engine {
        let path = FilePath::new("data/fr/words.txt");
        Engine::load(
            path,
            "Grammalecte".into(),
            crate::profiles::load("fr-classic-v1").unwrap(),
        )
        .unwrap()
    }

    pub(super) async fn server(app: Arc<Social>) -> (String, JoinHandle<()>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = format!("http://{}", listener.local_addr().unwrap());
        let handle = tokio::spawn(async move {
            axum::serve(listener, router(app)).await.unwrap();
        });
        (address, handle)
    }

    pub(super) async fn request(
        base: &str,
        method: Method,
        path: &str,
        token: &str,
        body: Value,
        status: StatusCode,
    ) -> Value {
        let mut req = reqwest::Client::new().request(method, format!("{base}/api/social/{path}"));
        if !token.is_empty() {
            req = req.bearer_auth(token);
        }
        if !body.is_null() {
            req = req.json(&body);
        }
        let response = req.send().await.unwrap();
        let actual = response.status();
        let body: Value = response.json().await.unwrap();
        assert_eq!(actual, status, "{path}: {body}");
        body
    }

    #[tokio::test]
    async fn guests_can_accept_play_rematch_and_block_without_leaking_placements() {
        let app = Arc::new(Social::open(FilePath::new(":memory:"), engine()).unwrap());
        let (base, server) = server(app.clone()).await;
        let a = "a".repeat(64);
        let b = "b".repeat(64);
        let c = "c".repeat(64);
        for (token, name) in [(&a, "Alice"), (&b, "Benoît"), (&c, "Clara")] {
            request(
                &base,
                Method::POST,
                "session",
                "",
                json!({"token":token,"nickname":name}),
                StatusCode::OK,
            )
            .await;
        }
        let alice = request(&base, Method::GET, "me", &a, Value::Null, StatusCode::OK).await;
        let id_a = alice["profile"]["id"].as_str().unwrap();
        let invitation = request(
            &base,
            Method::POST,
            "invites",
            &a,
            json!({}),
            StatusCode::OK,
        )
        .await;
        let invite = json!({"token":invitation["token"]});
        request(
            &base,
            Method::POST,
            "invites/accept",
            &a,
            invite.clone(),
            StatusCode::BAD_REQUEST,
        )
        .await;
        request(
            &base,
            Method::POST,
            "invites/accept",
            &b,
            invite.clone(),
            StatusCode::OK,
        )
        .await;
        request(
            &base,
            Method::POST,
            "invites/accept",
            &b,
            invite.clone(),
            StatusCode::OK,
        )
        .await;
        request(
            &base,
            Method::POST,
            "invites/accept",
            &c,
            invite,
            StatusCode::CONFLICT,
        )
        .await;
        let bob = request(&base, Method::GET, "me", &b, Value::Null, StatusCode::OK).await;
        let id_b = bob["profile"]["id"].as_str().unwrap();
        assert_eq!(bob["friends"][0]["id"], id_a);
        assert_eq!(bob["friends"][0]["nickname"], "Alice");
        assert!(bob["friends"][0].get("google_linked").is_none());
        let duel = request(
            &base,
            Method::POST,
            "duels",
            &b,
            json!({"friend_id":id_a}),
            StatusCode::OK,
        )
        .await;
        let id = duel["duel"]["id"].as_str().unwrap();
        assert_eq!(
            duel["duel"]["puzzle"]["identity"],
            "31f9f7d95680b9d54ef6d063"
        );
        assert_eq!(duel["duel"]["puzzle"]["rack"], "TEEATES");
        assert!(duel["duel"]["puzzle"].get("reference").is_none());
        let submission = format!("duels/{id}/play");
        let simple = json!({"tiles":[{"position":116,"rack_index":6,"letter":"S"}]});
        let reference = json!({"tiles":[{"position":118,"rack_index":1,"letter":"E"},
            {"position":116,"rack_index":0,"letter":"T"},{"position":117,"rack_index":4,"letter":"T"}]});
        request(
            &base,
            Method::POST,
            &submission,
            &c,
            simple.clone(),
            StatusCode::NOT_FOUND,
        )
        .await;
        request(
            &base,
            Method::POST,
            &submission,
            &a,
            json!({"score":999}),
            StatusCode::UNPROCESSABLE_ENTITY,
        )
        .await;
        request(
            &base,
            Method::POST,
            &submission,
            &a,
            json!({"tiles":[{"position":116,"rack_index":6,"letter":"X"}]}),
            StatusCode::BAD_REQUEST,
        )
        .await;
        let played = request(
            &base,
            Method::POST,
            &submission,
            &a,
            simple.clone(),
            StatusCode::OK,
        )
        .await;
        assert_eq!(played["duel"]["status"], "waiting");
        assert_eq!(played["duel"]["score"], 15);
        assert!(played["duel"]["opponent_score"].is_null());
        request(&base, Method::POST, &submission, &a, simple, StatusCode::OK).await;
        request(
            &base,
            Method::POST,
            &submission,
            &a,
            reference.clone(),
            StatusCode::CONFLICT,
        )
        .await;
        let waiting = request(&base, Method::GET, "me", &b, Value::Null, StatusCode::OK).await;
        assert_eq!(waiting["duels"][0]["status"], "your_turn");
        assert!(waiting["duels"][0]["opponent_score"].is_null());
        let completed = request(
            &base,
            Method::POST,
            &submission,
            &b,
            reference,
            StatusCode::OK,
        )
        .await;
        assert_eq!(completed["duel"]["status"], "finished");
        assert_eq!(completed["duel"]["score"], 17);
        assert_eq!(completed["duel"]["opponent_score"], 15);
        assert_eq!(completed["duel"]["winner"], id_b);
        let ranks = request(&base, Method::GET, "me", &a, Value::Null, StatusCode::OK).await;
        assert_eq!(ranks["ranking"][0]["wins"], 1);
        assert_eq!(ranks["ranking"][0]["id"], id_b);
        let path = format!("duels/{id}/rematch");
        let rematch = request(&base, Method::POST, &path, &a, json!({}), StatusCode::OK).await;
        let replay = request(&base, Method::POST, &path, &b, json!({}), StatusCode::OK).await;
        assert_eq!(rematch["duel"]["id"], replay["duel"]["id"]);
        assert_ne!(duel["duel"]["date"], replay["duel"]["date"]);
        request(
            &base,
            Method::POST,
            &format!("friends/{id_b}/block"),
            &a,
            json!({}),
            StatusCode::OK,
        )
        .await;
        let new_invite = request(
            &base,
            Method::POST,
            "invites",
            &a,
            json!({}),
            StatusCode::OK,
        )
        .await;
        request(
            &base,
            Method::POST,
            "invites/accept",
            &b,
            json!({"token":new_invite["token"]}),
            StatusCode::NOT_FOUND,
        )
        .await;
        let removed = request(&base, Method::GET, "me", &b, Value::Null, StatusCode::OK).await;
        assert_eq!(removed["duels"], json!([]));
        assert_eq!(removed["friends"], json!([]));
        assert_eq!(removed["ranking"].as_array().unwrap().len(), 1);
        request(
            &base,
            Method::DELETE,
            "profile",
            &a,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        request(
            &base,
            Method::GET,
            "me",
            &a,
            Value::Null,
            StatusCode::UNAUTHORIZED,
        )
        .await;
        let db = app.db.lock().unwrap();
        assert_eq!(
            db.query_row("SELECT count(*) FROM social_duels", [], |row| row
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
        server.abort();
    }

    #[test]
    fn linking_google_preserves_existing_guest_and_restores_from_fresh_device() {
        let app = Social::open(FilePath::new(":memory:"), engine()).unwrap();
        let db = app.db.lock().unwrap();
        db.execute("INSERT INTO social_users(id,nickname) VALUES('a','Alice'),('b','Benoît'),('c','Clara')",[]).unwrap();
        db.execute(
            "INSERT INTO social_sessions(token,user_id) VALUES('aa','a'),('bb','b'),('cc','c')",
            [],
        )
        .unwrap();
        assert_eq!(attach_google(&db, "aa", "a", "google-a").unwrap(), "a");
        db.execute("INSERT INTO social_friends(a,b) VALUES('a','b')", [])
            .unwrap();
        assert!(attach_google(&db, "bb", "b", "google-a").is_err());
        assert_eq!(user_id(&db, "bb").unwrap(), "b");
        assert_eq!(attach_google(&db, "cc", "c", "google-a").unwrap(), "a");
        assert_eq!(user_id(&db, "cc").unwrap(), "a");
        assert!(profile(&db, "c").is_err());
    }
}
