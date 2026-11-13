use crate::{
    engine::Engine,
    multiplayer_game::{Action, BotLevel, Game, GameView, Placement, PlacementHint},
    pg,
};
use anyhow::{Context, Result};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State, rejection::JsonRejection},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    middleware,
    response::{IntoResponse, Response},
    routing::{get, post},
};
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::HashSet,
    path::{Path as FsPath, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::sync::Semaphore;
use unicode_normalization::UnicodeNormalization;

#[derive(Deserialize, Serialize)]
struct Seat {
    name: String,
    token_hash: String,
}

#[derive(Deserialize, Serialize)]
struct Room {
    code: String,
    capacity: usize,
    revision: i64,
    players: Vec<Seat>,
    #[serde(default)]
    bots: Vec<BotLevel>,
    #[serde(default)]
    bot_ready_at_ms: Option<u64>,
    profile: String,
    lexicon: String,
    lexicon_digest: String,
    game: Option<Game>,
}

#[derive(Serialize)]
struct PlayerView {
    seat: usize,
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    bot: Option<BotLevel>,
}

#[derive(Serialize)]
struct RoomView {
    code: String,
    capacity: usize,
    revision: i64,
    seat: usize,
    host: usize,
    players: Vec<PlayerView>,
    lexicon: String,
    game: Option<GameView>,
}

impl Room {
    fn human_capacity(&self) -> usize {
        self.capacity - self.bots.len()
    }

    fn seat(&self, token_hash: &str) -> Option<usize> {
        self.players
            .iter()
            .position(|player| player.token_hash == token_hash)
    }

    fn view(&self, engine: &Engine, seat: usize) -> RoomResult<RoomView> {
        self.check_lexicon(engine)?;
        Ok(RoomView {
            code: self.code.clone(),
            capacity: self.capacity,
            revision: self.revision,
            seat,
            host: 0,
            players: self.player_views(),
            lexicon: self.lexicon.clone(),
            game: self.game.as_ref().map(|game| game.view(engine, seat)),
        })
    }

    fn player_views(&self) -> Vec<PlayerView> {
        let humans = self
            .players
            .iter()
            .enumerate()
            .map(|(seat, player)| PlayerView {
                seat,
                name: player.name.clone(),
                bot: None,
            });
        let bots = self
            .bots
            .iter()
            .enumerate()
            .map(|(index, &level)| PlayerView {
                seat: self.human_capacity() + index,
                name: if self.bots.len() == 1 {
                    format!("N A B · {}", level.label())
                } else {
                    format!("N A B {} · {}", index + 1, level.label())
                },
                bot: Some(level),
            });
        humans.chain(bots).collect()
    }

    fn current_bot(&self) -> Option<(usize, BotLevel)> {
        let seat = self.game.as_ref()?.current_seat()?;
        let bot_index = seat.checked_sub(self.human_capacity())?;
        self.bots.get(bot_index).map(|&level| (seat, level))
    }

    fn schedule_bot(&mut self) {
        self.bot_ready_at_ms = self.current_bot().map(|_| now_ms().saturating_add(700));
    }

    fn due_bot(&self) -> Option<(usize, BotLevel)> {
        if self.bot_ready_at_ms.is_some_and(|ready| now_ms() < ready) {
            return None;
        }
        self.current_bot()
    }

    fn check_revision(&self, revision: i64) -> RoomResult<()> {
        if self.revision != revision {
            return Err(RoomError::stale());
        }
        Ok(())
    }

    fn check_lexicon(&self, engine: &Engine) -> RoomResult<()> {
        if self.profile != engine.profile.id || self.lexicon_digest != engine.digest {
            return Err(RoomError::conflict(
                "Le lexique de ce salon a changé. Créez un nouveau salon pour jouer.",
            ));
        }
        Ok(())
    }

    fn game_for_turn(&self, seat: usize) -> RoomResult<&Game> {
        let game = self
            .game
            .as_ref()
            .ok_or_else(|| RoomError::conflict("La partie n’a pas encore commencé."))?;
        match game.current_seat() {
            None => Err(RoomError::conflict("La partie est terminée.")),
            Some(current) if current != seat => {
                Err(RoomError::conflict("Ce n’est pas votre tour."))
            }
            Some(_) => Ok(game),
        }
    }
}

#[derive(Serialize)]
struct Membership {
    token: String,
    room: RoomView,
}

#[derive(Clone)]
struct RoomApi {
    store: RoomStore,
    engine: Arc<Engine>,
    slots: Arc<Semaphore>,
    move_slots: Arc<Semaphore>,
    active_bots: Arc<Mutex<HashSet<String>>>,
}

// A room can have only one bot calculation in this process. The revision
// check in RoomStore::update also prevents double commits across processes.
struct BotTurn<'a> {
    code: String,
    active: &'a Mutex<HashSet<String>>,
}

impl Drop for BotTurn<'_> {
    fn drop(&mut self) {
        if let Ok(mut active) = self.active.lock() {
            active.remove(&self.code);
        }
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

#[derive(Clone)]
enum RoomStore {
    Postgres,
    Sqlite(PathBuf),
}

impl RoomStore {
    fn initialize(campaign: &FsPath) -> Result<Self> {
        if let Some(mut connection) = pg::connect()? {
            connection.batch_execute(
                "CREATE TABLE IF NOT EXISTS web_rooms (
                    code text PRIMARY KEY,
                    owner_hash text NOT NULL UNIQUE,
                    revision bigint NOT NULL,
                    payload jsonb NOT NULL
                )",
            )?;
            return Ok(Self::Postgres);
        }
        let path = campaign.with_file_name("multiplayer.sqlite");
        sqlite(&path)?.execute_batch(
            "PRAGMA journal_mode=WAL;
             CREATE TABLE IF NOT EXISTS web_rooms (
                 code text PRIMARY KEY,
                 owner_hash text NOT NULL UNIQUE,
                 revision integer NOT NULL,
                 payload text NOT NULL
             );",
        )?;
        Ok(Self::Sqlite(path))
    }

    fn find(&self, query: &str, key: &str) -> Result<Option<Room>> {
        match self {
            Self::Postgres => postgres()?
                .query_opt(query, &[&key])?
                .map(|row| serde_json::from_value(row.get::<_, Value>(0)).map_err(Into::into))
                .transpose(),
            Self::Sqlite(path) => sqlite(path)?
                .query_row(query, [key], |row| row.get::<_, String>(0))
                .optional()?
                .map(|payload| serde_json::from_str(&payload).map_err(Into::into))
                .transpose(),
        }
    }

    fn by_code(&self, code: &str) -> Result<Option<Room>> {
        self.find("SELECT payload FROM web_rooms WHERE code=$1", code)
    }

    fn by_owner(&self, owner_hash: &str) -> Result<Option<Room>> {
        self.find(
            "SELECT payload FROM web_rooms WHERE owner_hash=$1",
            owner_hash,
        )
    }

    fn insert(&self, room: &Room) -> Result<bool> {
        let sql = "INSERT INTO web_rooms (code,owner_hash,revision,payload)
                   VALUES ($1,$2,$3,$4) ON CONFLICT DO NOTHING";
        let owner_hash = &room.players[0].token_hash;
        Ok(match self {
            Self::Postgres => {
                postgres()?.execute(
                    sql,
                    &[
                        &room.code,
                        owner_hash,
                        &room.revision,
                        &serde_json::to_value(room)?,
                    ],
                )? == 1
            }
            Self::Sqlite(path) => {
                sqlite(path)?.execute(
                    sql,
                    params![
                        room.code,
                        owner_hash,
                        room.revision,
                        serde_json::to_string(room)?
                    ],
                )? == 1
            }
        })
    }

    fn update(&self, room: &mut Room) -> RoomResult<()> {
        let sql = "UPDATE web_rooms SET revision=$1,payload=$2 WHERE code=$3 AND revision=$4";
        let previous = room.revision;
        room.revision += 1;
        let updated = match self {
            Self::Postgres => {
                postgres()?.execute(
                    sql,
                    &[
                        &room.revision,
                        &serde_json::to_value(&room)?,
                        &room.code,
                        &previous,
                    ],
                )? == 1
            }
            Self::Sqlite(path) => {
                sqlite(path)?.execute(
                    sql,
                    params![
                        room.revision,
                        serde_json::to_string(&room)?,
                        room.code,
                        previous
                    ],
                )? == 1
            }
        };
        if !updated {
            return Err(RoomError::stale());
        }
        Ok(())
    }

    fn room(&self, code: &str) -> RoomResult<Room> {
        self.by_code(code)?.ok_or_else(RoomError::missing)
    }
}

fn postgres() -> Result<postgres::Client> {
    pg::connect()?.context("PostgreSQL indisponible pour les salons")
}

fn sqlite(path: &FsPath) -> Result<Connection> {
    let connection = Connection::open(path)
        .with_context(|| format!("ouverture de la base des salons {}", path.display()))?;
    connection.busy_timeout(Duration::from_secs(3))?;
    Ok(connection)
}

#[derive(Debug)]
struct RoomError {
    status: StatusCode,
    message: String,
    code: Option<&'static str>,
}
type RoomResult<T> = std::result::Result<T, RoomError>;

impl RoomError {
    fn new(status: StatusCode, message: impl Into<String>) -> Self {
        Self {
            status,
            message: message.into(),
            code: None,
        }
    }

    fn invalid(message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, message)
    }

    fn unauthorized() -> Self {
        Self::new(
            StatusCode::UNAUTHORIZED,
            "Ce navigateur n’a pas de place dans ce salon.",
        )
    }

    fn missing() -> Self {
        Self::new(StatusCode::NOT_FOUND, "Salon introuvable.")
    }

    fn conflict(message: &str) -> Self {
        Self::new(StatusCode::CONFLICT, message)
    }

    fn stale() -> Self {
        let mut error = Self::conflict("Le salon a changé. Réessayez après la mise à jour.");
        error.code = Some("stale_revision");
        error
    }
}

impl IntoResponse for RoomError {
    fn into_response(self) -> Response {
        (
            self.status,
            Json(json!({"error": self.message, "code": self.code})),
        )
            .into_response()
    }
}

impl From<anyhow::Error> for RoomError {
    fn from(error: anyhow::Error) -> Self {
        eprintln!("Salons: {error:#}");
        Self::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Le salon est temporairement indisponible.",
        )
    }
}

impl From<postgres::Error> for RoomError {
    fn from(error: postgres::Error) -> Self {
        anyhow::Error::from(error).into()
    }
}

impl From<rusqlite::Error> for RoomError {
    fn from(error: rusqlite::Error) -> Self {
        anyhow::Error::from(error).into()
    }
}

impl From<serde_json::Error> for RoomError {
    fn from(error: serde_json::Error) -> Self {
        anyhow::Error::from(error).into()
    }
}

fn room_code(code: String) -> RoomResult<String> {
    if code.len() != 16 || !code.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(RoomError::missing());
    }
    Ok(code.to_ascii_uppercase())
}

fn token_hash(token: &str) -> Option<String> {
    if token.len() != 64
        || !token
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return None;
    }
    Some(format!("{:x}", Sha256::digest(token.as_bytes())))
}

fn membership_token(token: Option<String>) -> RoomResult<(String, String)> {
    let token = token.unwrap_or_else(|| {
        format!(
            "{:032x}{:032x}",
            rand::random::<u128>(),
            rand::random::<u128>()
        )
    });
    let hash =
        token_hash(&token).ok_or_else(|| RoomError::invalid("Identifiant de place invalide."))?;
    Ok((token, hash))
}

fn authorization(headers: &HeaderMap) -> RoomResult<String> {
    headers
        .get(header::AUTHORIZATION)
        .and_then(|header| header.to_str().ok())
        .and_then(|header| header.split_once(' '))
        .filter(|(scheme, _)| scheme.eq_ignore_ascii_case("Bearer"))
        .and_then(|(_, token)| token_hash(token))
        .ok_or_else(RoomError::unauthorized)
}

fn player_name(name: &str) -> RoomResult<String> {
    let name: String = name.trim().nfc().collect();
    if !(1..=24).contains(&name.chars().count()) || name.chars().any(char::is_control) {
        return Err(RoomError::invalid(
            "Choisissez un nom de 1 à 24 caractères.",
        ));
    }
    Ok(name)
}

fn body<T>(input: std::result::Result<Json<T>, JsonRejection>) -> RoomResult<T> {
    input.map(|Json(input)| input).map_err(|error| {
        RoomError::new(error.status(), "La demande envoyée au salon est invalide.")
    })
}

impl RoomApi {
    async fn run<T: Send + 'static>(
        self,
        operation: impl FnOnce(Self) -> RoomResult<T> + Send + 'static,
    ) -> RoomResult<Json<T>> {
        let permit = self.slots.clone().try_acquire_owned().map_err(|_| {
            RoomError::new(
                StatusCode::TOO_MANY_REQUESTS,
                "Le serveur est occupé. Réessayez dans un instant.",
            )
        })?;
        tokio::task::spawn_blocking(move || {
            let _permit = permit;
            operation(self).map(Json)
        })
        .await
        .map_err(anyhow::Error::from)?
    }

    fn authenticated_room(&self, code: &str, token_hash: &str) -> RoomResult<(Room, usize)> {
        let room = self.store.room(code)?;
        let seat = room.seat(token_hash).ok_or_else(RoomError::unauthorized)?;
        Ok((room, seat))
    }

    fn claim_bot(&self, code: &str) -> RoomResult<Option<BotTurn<'_>>> {
        let mut active = self
            .active_bots
            .lock()
            .map_err(|_| anyhow::anyhow!("verrou des tours des bots indisponible"))?;
        if !active.insert(code.to_owned()) {
            return Ok(None);
        }
        Ok(Some(BotTurn {
            code: code.to_owned(),
            active: &self.active_bots,
        }))
    }

    fn advance_bot(&self, room: Room) -> RoomResult<Room> {
        if room.due_bot().is_none() {
            return Ok(room);
        }
        room.check_lexicon(&self.engine)?;
        let Ok(_permit) = self.move_slots.try_acquire() else {
            return Ok(room);
        };
        let Some(_turn) = self.claim_bot(&room.code)? else {
            return Ok(room);
        };
        let mut latest = self.store.room(&room.code)?;
        let Some((seat, level)) = latest.due_bot() else {
            return Ok(latest);
        };
        latest.check_lexicon(&self.engine)?;
        latest
            .game
            .as_mut()
            .expect("un bot doit avoir une partie")
            .play_bot(&self.engine, seat, level)
            .map_err(|error| anyhow::anyhow!("tour du bot {seat} : {error}"))?;
        latest.schedule_bot();
        match self.store.update(&mut latest) {
            Ok(()) => Ok(latest),
            Err(error) if error.code == Some("stale_revision") => self.store.room(&room.code),
            Err(error) => Err(error),
        }
    }
}

#[derive(Deserialize)]
struct Create {
    name: String,
    capacity: usize,
    token: Option<String>,
    #[serde(default)]
    bots: Vec<BotLevel>,
}

async fn create(
    State(api): State<RoomApi>,
    input: std::result::Result<Json<Create>, JsonRejection>,
) -> RoomResult<Json<Membership>> {
    let Create {
        name,
        capacity,
        token,
        bots,
    } = body(input)?;
    if !(2..=4).contains(&capacity) {
        return Err(RoomError::invalid(
            "Choisissez un salon de 2, 3 ou 4 places.",
        ));
    }
    if bots.len() >= capacity {
        return Err(RoomError::invalid(
            "Gardez au moins une place pour un joueur humain.",
        ));
    }
    let name = player_name(&name)?;
    let (token, token_hash) = membership_token(token)?;
    api.run(move |api| {
        let mut room = Room {
            code: format!("{:016X}", rand::random::<u64>()),
            capacity,
            revision: 0,
            players: vec![Seat { name, token_hash }],
            bots,
            bot_ready_at_ms: None,
            profile: api.engine.profile.id.clone(),
            lexicon: api.engine.lexicon.clone(),
            lexicon_digest: api.engine.digest.clone(),
            game: None,
        };
        for _ in 0..3 {
            if api.store.insert(&room)? {
                return Ok(Membership {
                    token,
                    room: room.view(&api.engine, 0)?,
                });
            }
            if let Some(existing) = api.store.by_owner(&room.players[0].token_hash)? {
                return Ok(Membership {
                    token,
                    room: existing.view(&api.engine, 0)?,
                });
            }
            room.code = format!("{:016X}", rand::random::<u64>());
        }
        Err(anyhow::anyhow!("création d’un code de salon impossible").into())
    })
    .await
}

#[derive(Deserialize)]
struct Join {
    name: String,
    token: Option<String>,
}

async fn join(
    State(api): State<RoomApi>,
    Path(code): Path<String>,
    input: std::result::Result<Json<Join>, JsonRejection>,
) -> RoomResult<Json<Membership>> {
    let code = room_code(code)?;
    let Join { name, token } = body(input)?;
    let name = player_name(&name)?;
    let (token, token_hash) = membership_token(token)?;
    api.run(move |api| {
        loop {
            let mut room = api.store.room(&code)?;
            room.check_lexicon(&api.engine)?;
            if let Some(seat) = room.seat(&token_hash) {
                return Ok(Membership {
                    token,
                    room: room.view(&api.engine, seat)?,
                });
            }
            if room.game.is_some() {
                return Err(RoomError::conflict("La partie a déjà commencé."));
            }
            let seat = room.players.len();
            if seat == room.human_capacity() {
                return Err(RoomError::conflict("Ce salon est complet."));
            }
            room.players.push(Seat {
                name: name.clone(),
                token_hash: token_hash.clone(),
            });
            match api.store.update(&mut room) {
                Ok(()) => {
                    return Ok(Membership {
                        token,
                        room: room.view(&api.engine, seat)?,
                    });
                }
                Err(error) if error.code == Some("stale_revision") => continue,
                Err(error) => return Err(error),
            }
        }
    })
    .await
}

async fn state(
    State(api): State<RoomApi>,
    Path(code): Path<String>,
    headers: HeaderMap,
) -> RoomResult<Json<RoomView>> {
    let code = room_code(code)?;
    let token_hash = authorization(&headers)?;
    api.run(move |api| {
        let (room, seat) = api.authenticated_room(&code, &token_hash)?;
        api.advance_bot(room)?.view(&api.engine, seat)
    })
    .await
}

async fn replay(
    State(api): State<RoomApi>,
    Path(code): Path<String>,
    headers: HeaderMap,
) -> RoomResult<Json<Value>> {
    let code = room_code(code)?;
    let token_hash = authorization(&headers)?;
    api.run(move |api| {
        let (room, _) = api.authenticated_room(&code, &token_hash)?;
        let game = room
            .game
            .as_ref()
            .filter(|game| game.current_seat().is_none())
            .ok_or_else(|| {
                RoomError::conflict("Le rejeu est disponible après la fin de la partie.")
            })?;
        let replay = game.replay().ok_or_else(|| {
            RoomError::conflict("Le journal complet de cette partie est indisponible.")
        })?;
        let mut payload = serde_json::to_value(replay)?;
        payload["schema"] = json!("lettrine.game.v1");
        payload["source"] = json!("web");
        payload["id"] = json!(format!("web-{:x}", Sha256::digest(room.code.as_bytes())));
        payload["profile"] = json!(room.profile);
        payload["language"] = json!("fr");
        payload["lexicon"] = json!(room.lexicon);
        payload["lexicon_sha256"] = json!(room.lexicon_digest);
        payload["mode"] = json!(if room.human_capacity() == 1 {
            "solo"
        } else {
            "online"
        });
        payload["names"] = json!(
            room.player_views()
                .into_iter()
                .map(|player| player.name)
                .collect::<Vec<_>>()
        );
        Ok(payload)
    })
    .await
}

#[derive(Deserialize)]
struct Revision {
    revision: i64,
}

async fn start(
    State(api): State<RoomApi>,
    Path(code): Path<String>,
    headers: HeaderMap,
    input: std::result::Result<Json<Revision>, JsonRejection>,
) -> RoomResult<Json<RoomView>> {
    let code = room_code(code)?;
    let token_hash = authorization(&headers)?;
    let Revision { revision } = body(input)?;
    api.run(move |api| {
        let (mut room, seat) = api.authenticated_room(&code, &token_hash)?;
        if seat != 0 {
            return Err(RoomError::new(
                StatusCode::FORBIDDEN,
                "Seule la personne qui a créé le salon peut lancer la partie.",
            ));
        }
        room.check_revision(revision)?;
        if room.game.is_some() {
            return Err(RoomError::conflict("La partie a déjà commencé."));
        }
        if room.players.len() != room.human_capacity() {
            return Err(RoomError::conflict(
                "Toutes les places doivent être occupées pour lancer la partie.",
            ));
        }
        room.check_lexicon(&api.engine)?;
        room.game = Some(Game::new(&api.engine, room.capacity, rand::random())?);
        room.schedule_bot();
        api.store.update(&mut room)?;
        room.view(&api.engine, seat)
    })
    .await
}

#[derive(Deserialize)]
struct Turn {
    revision: i64,
    #[serde(flatten)]
    action: Action,
}

#[derive(Deserialize)]
struct Preview {
    revision: i64,
    placements: Vec<Placement>,
}

async fn preview(
    State(api): State<RoomApi>,
    Path(code): Path<String>,
    headers: HeaderMap,
    input: std::result::Result<Json<Preview>, JsonRejection>,
) -> RoomResult<Json<Value>> {
    let code = room_code(code)?;
    let token_hash = authorization(&headers)?;
    let Preview {
        revision,
        placements,
    } = body(input)?;
    api.run(move |api| {
        let (room, seat) = api.authenticated_room(&code, &token_hash)?;
        room.check_revision(revision)?;
        room.check_lexicon(&api.engine)?;
        let game = room.game_for_turn(seat)?;
        Ok(
            match game.preview_placement(&api.engine, seat, &placements) {
                Ok(preview) => {
                    json!({"valid": true, "score": preview.score, "words": preview.words})
                }
                Err(reason) => {
                    json!({"valid": false, "score": null, "words": [], "reason": reason})
                }
            },
        )
    })
    .await
}

#[derive(Serialize)]
struct HintResponse {
    hint: Option<PlacementHint>,
    alternatives: Vec<PlacementHint>,
}

async fn hint(
    State(api): State<RoomApi>,
    Path(code): Path<String>,
    headers: HeaderMap,
    input: std::result::Result<Json<Revision>, JsonRejection>,
) -> RoomResult<Json<HintResponse>> {
    let code = room_code(code)?;
    let token_hash = authorization(&headers)?;
    let Revision { revision } = body(input)?;
    api.run(move |api| {
        let (room, seat) = api.authenticated_room(&code, &token_hash)?;
        room.check_revision(revision)?;
        room.check_lexicon(&api.engine)?;
        let game = room.game_for_turn(seat)?;
        let _permit = api.move_slots.try_acquire().map_err(|_| {
            RoomError::new(
                StatusCode::TOO_MANY_REQUESTS,
                "Le moteur est occupé. Réessayez dans un instant.",
            )
        })?;
        let mut hints = game
            .placement_hints(&api.engine, seat)
            .map_err(|error| anyhow::anyhow!("suggestion de placement : {error}"))?
            .into_iter();
        Ok(HintResponse {
            hint: hints.next(),
            alternatives: hints.collect(),
        })
    })
    .await
}

async fn action(
    State(api): State<RoomApi>,
    Path(code): Path<String>,
    headers: HeaderMap,
    input: std::result::Result<Json<Turn>, JsonRejection>,
) -> RoomResult<Json<RoomView>> {
    let code = room_code(code)?;
    let token_hash = authorization(&headers)?;
    let Turn { revision, action } = body(input)?;
    api.run(move |api| {
        let (mut room, seat) = api.authenticated_room(&code, &token_hash)?;
        room.check_revision(revision)?;
        room.check_lexicon(&api.engine)?;
        room.game_for_turn(seat)?;
        room.game
            .as_mut()
            .expect("partie validée")
            .apply(&api.engine, seat, action)
            .map_err(RoomError::invalid)?;
        room.schedule_bot();
        api.store.update(&mut room)?;
        room.view(&api.engine, seat)
    })
    .await
}

async fn no_cache(mut response: Response) -> Response {
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

pub async fn routes<S>(campaign: PathBuf, engine: Arc<Engine>) -> Result<Router<S>>
where
    S: Clone + Send + Sync + 'static,
{
    let store = tokio::task::spawn_blocking(move || RoomStore::initialize(&campaign)).await??;
    Ok(Router::new()
        .route("/api/rooms", post(create))
        .route("/api/rooms/{code}", get(state))
        .route("/api/rooms/{code}/replay", get(replay))
        .route("/api/rooms/{code}/join", post(join))
        .route("/api/rooms/{code}/start", post(start))
        .route("/api/rooms/{code}/preview", post(preview))
        .route("/api/rooms/{code}/hint", post(hint))
        .route("/api/rooms/{code}/action", post(action))
        .layer(DefaultBodyLimit::max(8 * 1024))
        .layer(middleware::map_response(no_cache))
        .with_state(RoomApi {
            store,
            engine,
            slots: Arc::new(Semaphore::new(8)),
            move_slots: Arc::new(Semaphore::new(2)),
            active_bots: Arc::new(Mutex::new(HashSet::new())),
        }))
}
