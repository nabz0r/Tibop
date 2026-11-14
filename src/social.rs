use crate::{engine::Engine, multiplayer_game::Game, social_google};
use anyhow::{Result, ensure};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode, header},
    middleware,
    response::{IntoResponse, Response},
    routing::{delete, get, post},
};
use rand::{RngExt, seq::SliceRandom};
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
use wolges::{
    klv::AdjustLeave,
    movegen::{
        EquityPredicate, GenMovesParams, KurniaMoveGenerator, PassPolicy, PlacePredicate, Play,
    },
    play_scorer::PlayScorer,
};

mod referral;
mod tournament;

const FR_LEXICON: &str = "8d024eba26b05909beb31a26af5a99ad501efebb895f9488ae1eb09f6487f81a";
const EN_LEXICON: &str = "452528054b24cce68199a01534e4639d451ac0da3bdac70113546ad6e13fda0e";
const FR_CLASSIC_LEXICON: &str = "ac01cba21cc2557f4c5d19abdfdf6cf5780b7279d04eac2cd84b834b40fe8c84";
const LEXICON_HEADER: &str = "x-lettrine-lexicon";
const CLASSIC_EXCLUSIONS: &str = include_str!("../data/fr/classic-exclusions.txt");
const INVITE_SECONDS: i64 = 2 * 24 * 60 * 60;
const NONCE_SECONDS: i64 = 5 * 60;
const MAX_FRIENDS: i64 = 100;
const GOOGLE_PROFILE_CONFLICT: &str =
    "Ce compte Google utilise déjà un autre profil Tibop. Votre profil invité est conservé.";
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
    CREATE TABLE IF NOT EXISTS social_duel_deals(
        duel_id TEXT PRIMARY KEY REFERENCES social_duels(id) ON DELETE CASCADE,
        rack_a TEXT NOT NULL CHECK(length(rack_a)=7),
        rack_b TEXT NOT NULL CHECK(length(rack_b)=7),
        mirror_of TEXT UNIQUE REFERENCES social_duels(id) ON DELETE CASCADE
    );
    CREATE TABLE IF NOT EXISTS social_lexicon_clients(
        user_id TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE,
        lexicon_sha256 TEXT NOT NULL,
        PRIMARY KEY(user_id,lexicon_sha256)
    );
    CREATE TABLE IF NOT EXISTS social_duel_lexicons(
        duel_id TEXT PRIMARY KEY REFERENCES social_duels(id) ON DELETE CASCADE,
        lexicon_sha256 TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS social_google_nonces(
        session TEXT PRIMARY KEY REFERENCES social_sessions(token) ON DELETE CASCADE,
        nonce TEXT NOT NULL,
        expires_at INTEGER NOT NULL
    );
";

#[derive(Debug)]
struct ApiError(StatusCode, String);
type Api<T> = std::result::Result<T, ApiError>;

#[derive(Clone)]
struct ErrorMessage(String);

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let mut response = (self.0, Json(json!({ "error": self.1 }))).into_response();
        response.extensions_mut().insert(ErrorMessage(self.1));
        response
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

async fn english_errors(mut response: Response) -> Response {
    if let Some(ErrorMessage(message)) = response.extensions_mut().remove::<ErrorMessage>() {
        let translated = if let Some(word) = message
            .strip_prefix("« ")
            .and_then(|text| text.strip_suffix(" » est absent du lexique."))
        {
            format!("“{word}” is not in the dictionary.")
        } else {
            match message.as_str() {
                "Stockage indisponible." => "Storage unavailable.",
                "Requête JSON invalide." => "Invalid JSON request.",
                "Aucun tirage jouable. Réessayez." => "No playable rack. Try again.",
                "Session indisponible. Reconnectez-vous." => "Session unavailable. Sign in again.",
                "Le pseudo doit compter de 1 à 24 caractères." => "Your nickname must have 1 to 24 characters.",
                "Utilisez un code pays à deux lettres." => "Use a two-letter country code.",
                "Joueur introuvable." => "Player not found.",
                "Identifiant de session invalide." => "Invalid session identifier.",
                "Joueur invalide." => "Invalid player.",
                "Trop d’invitations en cours." => "Too many pending invitations.",
                "Invitation invalide." => "Invalid invitation.",
                "Invitation introuvable ou expirée." => "Invitation not found or expired.",
                "Cette invitation est la vôtre." => "This is your own invitation.",
                "Invitation indisponible." => "Invitation unavailable.",
                "Invitation déjà utilisée." => "This invitation has already been used.",
                "La liste d’amis est pleine." => "The friends list is full.",
                "Duel introuvable." => "Duel not found.",
                "Ami introuvable." => "Friend not found.",
                "Un duel est déjà en cours avec cet ami." => "A duel with this friend is already in progress.",
                "Duel indisponible." => "Duel unavailable.",
                "Un duel terminé ne peut pas être annulé." => "A finished duel cannot be cancelled.",
                "Terminez le duel avant de proposer une revanche." => "Finish the duel before requesting a rematch.",
                "La revanche miroir est disponible après un duel à deux chevalets." => "A mirror rematch follows a duel with different racks.",
                "Placement invalide." | "Ce placement est invalide." => "Invalid placement.",
                "Vous avez déjà joué ce duel." => "You already played in this duel.",
                "Chevalet indisponible." => "Rack unavailable.",
                "La connexion Google n’est pas configurée. Le mode invité reste disponible." => "Google sign-in is unavailable. You can continue as a guest.",
                "Identifiant Google invalide." => "Invalid Google identifier.",
                "Impossible de vérifier ce compte Google." => "This Google account could not be verified.",
                "La session a changé. Réessayez." => "The session changed. Try again.",
                "Ce profil est déjà associé à un autre compte Google." => "This profile is linked to another Google account.",
                GOOGLE_PROFILE_CONFLICT => "This Google account already uses another Tibop profile. Your guest profile is still available.",
                "Défi introuvable." => "Challenge not found.",
                "Ce tournoi n’est plus disponible." => "This tournament is no longer available.",
                "Vous avez déjà joué ce défi." => "You already played this challenge.",
                "Le tournoi est fermé." => "The tournament is closed.",
                "Plateau incompatible." => "Incompatible board.",
                "Cette lettre du chevalet est indisponible." => "That rack tile is unavailable.",
                "Posez des lettres sur le plateau." => "Place tiles on the board.",
                "Cette case est hors du plateau." => "That square is outside the board.",
                "Cette case est déjà occupée." => "That square is already occupied.",
                "Choisissez la lettre représentée par le joker." => "Choose a letter for the blank.",
                "La lettre posée doit correspondre à celle du chevalet." => "The letter must match the tile on the rack.",
                "Alignez les nouvelles lettres." => "Place the new tiles in one line.",
                "Formez un mot d’au moins deux lettres." => "Make a word with at least two letters.",
                "Comblez les trous entre les lettres." => "Fill the gaps between the letters.",
                "Le premier mot doit couvrir l’étoile centrale." => "The first word must cover the center star.",
                "Reliez le mot aux lettres déjà posées." => "Connect your word to the tiles on the board.",
                _ => "The request could not be completed.",
            }.into()
        };
        *response.body_mut() = Json(json!({ "error": translated }))
            .into_response()
            .into_body();
        response.headers_mut().remove(header::CONTENT_LENGTH);
    }
    response
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

impl Puzzle {
    fn identity_for_rack(&self, engine: &Engine, rack: &str) -> String {
        let mut digest = Sha256::new();
        digest.update(b"v1/");
        digest.update(engine.digest.as_bytes());
        digest.update([0]);
        for &tile in &self.board_codes {
            digest.update([
                if tile == 0 {
                    0
                } else {
                    engine.letter(tile) as u8
                },
                0,
            ]);
        }
        digest.update(rack.as_bytes());
        format!("{:x}", digest.finalize())[..24].to_string()
    }

    fn for_rack(&self, engine: &Engine, rack: Option<&str>, curated: bool) -> Value {
        let rack = rack.unwrap_or(self.rack);
        let identity = self.identity_for_rack(engine, rack);
        let mut position = json!({
            "date":self.date, "opening_word":self.opening_word,
            "opening_start":self.opening_start, "opening_end":self.opening_end,
            "rack":rack, "identity":identity, "catalog_identity":self.identity,
        });
        if curated {
            position["lexicon_sha256"] = json!(FR_CLASSIC_LEXICON);
        }
        position
    }

    fn deal(&self, engine: &Engine, curated: bool) -> Api<[String; 2]> {
        let alphabet = engine.config.alphabet();
        let mut counts = (0..alphabet.len())
            .map(|tile| alphabet.freq(tile))
            .collect::<Vec<_>>();
        for &tile in &self.board_codes {
            if tile != 0 {
                counts[usize::from(tile)] -= 1;
            }
        }
        let mut bag = counts
            .iter()
            .enumerate()
            .skip(1)
            .flat_map(|(tile, &count)| std::iter::repeat_n(tile as u8, usize::from(count)))
            .collect::<Vec<_>>();
        let mut rng = rand::rng();
        let mut generator = engine.generator();
        let mut closest = None;
        for _ in 0..32 {
            let (dealt, _) = bag.partial_shuffle(&mut rng, 14);
            let (left, right) = dealt.split_at(7);
            let mut sorted_left = left.to_vec();
            let mut sorted_right = right.to_vec();
            sorted_left.sort_unstable();
            sorted_right.sort_unstable();
            if sorted_left == sorted_right {
                continue;
            }
            let mut best_score = |rack: &[u8]| {
                self.generate(engine, &mut generator, rack, curated);
                generator
                    .plays
                    .iter()
                    .filter_map(|entry| match &entry.play {
                        Play::Place { score, .. } => Some(wolges::equity::descale_score(*score)),
                        _ => None,
                    })
                    .max()
            };
            let (Some(a), Some(b)) = (best_score(left), best_score(right)) else {
                continue;
            };
            let spread = a.abs_diff(b);
            let racks =
                [left, right].map(|rack| rack.iter().map(|&tile| engine.letter(tile)).collect());
            if spread <= 5 {
                return Ok(racks);
            }
            if closest.as_ref().is_none_or(|(best, _)| spread < *best) {
                closest = Some((spread, racks));
            }
        }
        closest.map(|(_, racks)| racks).ok_or_else(|| {
            err(
                StatusCode::SERVICE_UNAVAILABLE,
                "Aucun tirage jouable. Réessayez.",
            )
        })
    }

    fn generate(
        &self,
        engine: &Engine,
        generator: &mut KurniaMoveGenerator,
        rack: &[u8],
        curated: bool,
    ) {
        if !curated {
            engine.generate(generator, &self.board_codes, rack, 8);
            return;
        }
        let snapshot = engine.snapshot(&self.board_codes);
        let mut scorer = PlayScorer::new();
        let mut allowed = |down, lane, idx, word: &[u8], score| {
            scorer.words_all(
                &snapshot,
                &Play::Place {
                    down,
                    lane,
                    idx,
                    word: word.into(),
                    score,
                },
                |word| !classic_excludes(engine, word),
            )
        };
        generator.gen_moves_filtered_lean(
            &GenMovesParams {
                board_snapshot: &snapshot,
                rack,
                max_gen: 8,
                num_exchanges_by_this_player: 0,
                pass_policy: PassPolicy::OnlyWhenForced,
                dynamic_leaves: None,
            },
            PlacePredicate::Dyn(&mut allowed),
            AdjustLeave::Identity,
            EquityPredicate::AcceptAll,
        );
    }
}

struct Social {
    db: Mutex<Connection>,
    engine: Engine,
    puzzles: Vec<Puzzle>,
    curated_tournament_from: Option<i64>,
    #[cfg(test)]
    tournament_time: std::sync::atomic::AtomicI64,
}

impl Social {
    fn open(db: &FilePath, engine: Engine) -> Result<Self> {
        let (expected_digest, catalog) = match engine.profile.id.as_str() {
            "fr-classic-v1" => (FR_LEXICON, include_str!("social_puzzles.tsv")),
            "en-wespa-v1" => (EN_LEXICON, include_str!("social_puzzles_en.tsv")),
            _ => anyhow::bail!("profil social incompatible"),
        };
        ensure!(
            engine.digest == expected_digest,
            "lexique social incompatible"
        );
        let puzzles = catalog
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
                let puzzle = Puzzle {
                    date: fields[0],
                    opening_word: fields[1],
                    opening_start,
                    opening_end,
                    rack: fields[4],
                    identity: fields[5],
                    board_codes,
                    rack_codes,
                };
                ensure!(
                    puzzle.identity == puzzle.identity_for_rack(&engine, puzzle.rack),
                    "identité de position sociale incompatible"
                );
                Ok(puzzle)
            })
            .collect::<Result<Vec<_>>>()?;
        ensure!(puzzles.len() == 164, "catalogue social incomplet");
        let mut conn = Connection::open(db)?;
        conn.busy_timeout(Duration::from_secs(5))?;
        conn.execute_batch("PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;")?;
        let scope = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        scope.execute_batch(
            "CREATE TABLE IF NOT EXISTS social_scope(
            singleton INTEGER PRIMARY KEY CHECK(singleton=1), profile TEXT NOT NULL
        );",
        )?;
        let profile: Option<String> = scope
            .query_row(
                "SELECT profile FROM social_scope WHERE singleton=1",
                [],
                |row| row.get(0),
            )
            .optional()?;
        if let Some(profile) = profile {
            ensure!(
                profile == engine.profile.id,
                "base sociale d’une autre langue"
            );
        } else {
            let existing: bool = scope.query_row(
                "SELECT EXISTS(SELECT 1 FROM sqlite_schema WHERE type='table' AND name='social_users')",
                [],
                |row| row.get(0),
            )?;
            ensure!(
                !existing || engine.profile.language == "fr",
                "base sociale française"
            );
            scope.execute(
                "INSERT INTO social_scope(singleton,profile) VALUES(1,?1)",
                [&engine.profile.id],
            )?;
        }
        scope.commit()?;
        conn.execute_batch(SCHEMA)?;
        conn.execute_batch(tournament::SCHEMA)?;
        let curated_tournament_from = if engine.profile.language == "fr" {
            Some(tournament::curated_from(&conn, now())?)
        } else {
            None
        };
        conn.execute_batch(referral::SCHEMA)?;
        Ok(Self {
            db: Mutex::new(conn),
            engine,
            puzzles,
            curated_tournament_from,
            #[cfg(test)]
            tournament_time: std::sync::atomic::AtomicI64::new(0),
        })
    }
}

pub(crate) async fn serve(
    db: &FilePath,
    engine: Engine,
    en_db: &FilePath,
    en_engine: Engine,
    address: &str,
) -> Result<()> {
    ensure!(
        db != en_db,
        "bases sociales française et anglaise identiques"
    );
    ensure!(
        engine.profile.language == "fr",
        "profil social français requis"
    );
    let app = Arc::new(Social::open(db, engine)?);
    let english = Arc::new(Social::open(en_db, en_engine)?);
    let listener = tokio::net::TcpListener::bind(address).await?;
    eprintln!("Service social Tibop sur http://{address}");
    axum::serve(listener, bilingual_router(app, english)).await?;
    Ok(())
}

fn router(app: Arc<Social>) -> Router {
    Router::new()
        .route("/health", get(|| async { "ok" }))
        .nest("/api/social", api_routes(app))
}

fn bilingual_router(french: Arc<Social>, english: Arc<Social>) -> Router {
    router(french).nest(
        "/api/social-en",
        api_routes(english).layer(middleware::map_response(english_errors)),
    )
}

fn api_routes(app: Arc<Social>) -> Router {
    Router::new()
        .route("/session", post(session))
        .route("/me", get(me))
        .route("/turns", get(turns))
        .route("/profile", post(save_profile).delete(delete_profile))
        .route("/friends/{id}", delete(unfriend))
        .route("/friends/{id}/block", post(block_friend))
        .route("/invites", post(invite))
        .route("/invites/accept", post(accept))
        .route("/duels", post(create_duel))
        .route("/duels/{id}", get(get_duel).delete(cancel_duel))
        .route("/duels/{id}/play", post(play))
        .route("/duels/{id}/rematch", post(rematch))
        .route("/duels/{id}/mirror", post(mirror))
        .route("/google/challenge", post(google_challenge))
        .route("/google", post(google_link))
        .route("/tournament", get(tournament::get_weekend))
        .route(
            "/tournament/{id}/slots/{number}/play",
            post(tournament::play_slot),
        )
        .layer(DefaultBodyLimit::max(12 * 1024))
        .with_state(app)
}

async fn write<T: Send + 'static>(
    app: Arc<Social>,
    f: impl FnOnce(&Social, &Transaction<'_>) -> Api<T> + Send + 'static,
) -> Api<T> {
    with_db(app, move |app, db| {
        let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let output = f(app, &tx)?;
        tx.commit()?;
        Ok(output)
    })
    .await
}

async fn with_db<T: Send + 'static>(
    app: Arc<Social>,
    f: impl FnOnce(&Social, &mut Connection) -> Api<T> + Send + 'static,
) -> Api<T> {
    tokio::task::spawn_blocking(move || {
        let mut db = app
            .db
            .lock()
            .map_err(|_| err(StatusCode::SERVICE_UNAVAILABLE, "Stockage indisponible."))?;
        f(&app, &mut db)
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

fn supports_curated(headers: &HeaderMap) -> bool {
    headers
        .get(LEXICON_HEADER)
        .is_some_and(|value| value == FR_CLASSIC_LEXICON)
}

fn note_curated(app: &Social, db: &Connection, user: &str, supported: bool) -> Api<()> {
    if supported && app.engine.profile.language == "fr" {
        db.execute(
            "INSERT OR IGNORE INTO social_lexicon_clients(user_id,lexicon_sha256) VALUES(?1,?2)",
            params![user, FR_CLASSIC_LEXICON],
        )?;
    }
    Ok(())
}

fn classic_excludes(engine: &Engine, word: &[u8]) -> bool {
    static FORMS: std::sync::LazyLock<Vec<&str>> =
        std::sync::LazyLock::new(|| CLASSIC_EXCLUSIONS.lines().collect());
    let mut letters = [0u8; 15];
    if word.len() > letters.len() {
        return false;
    }
    for (out, &tile) in letters.iter_mut().zip(word) {
        *out = engine.letter(tile) as u8;
    }
    let word = std::str::from_utf8(&letters[..word.len()]).expect("lettres françaises ASCII");
    FORMS.binary_search(&word).is_ok()
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
    headers: HeaderMap,
    body: std::result::Result<Json<SessionRequest>, JsonRejection>,
) -> Api<Json<Value>> {
    let Json(body) = body.map_err(json_error)?;
    if !hex(&body.token, 64) {
        return Err(invalid("Identifiant de session invalide."));
    }
    let session = digest(&body.token);
    let curated = supports_curated(&headers);
    let supplied_name = body
        .nickname
        .as_deref()
        .map(nickname)
        .transpose()?
        .map(str::to_owned);
    write(app, move |app, db| {
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
            let name = supplied_name.unwrap_or_else(|| {
                let role = if app.engine.profile.language == "en" {
                    "Player"
                } else {
                    "Joueur"
                };
                format!("{role} {}", &id[..5])
            });
            db.execute(
                "INSERT INTO social_users(id,nickname) VALUES(?1,?2)",
                params![id, name],
            )?;
            db.execute(
                "INSERT INTO social_sessions(token,user_id) VALUES(?1,?2)",
                params![session, id],
            )?;
            referral::register(db, &id, now())?;
            id
        };
        note_curated(app, db, &id, curated)?;
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
    let curated = supports_curated(&headers);
    let session = auth(headers)?;
    write(app, move |app, db| {
        let owner = user_id(db, &session)?;
        note_curated(app, db, &owner, curated)?;
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
    let curated = supports_curated(&headers);
    let session = auth(headers)?;
    if !hex(&body.token, 32) {
        return Err(invalid("Invitation invalide."));
    }
    let token = digest(&body.token);
    write(app, move |app, db| {
        let recipient = user_id(db, &session)?;
        note_curated(app, db, &recipient, curated)?;
        let time = now();
        let (owner, used) = db
            .query_row(
                "SELECT owner,accepted_by FROM social_invites WHERE token=?1 AND expires_at>?2",
                params![token, time],
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
            referral::attribute(db, &owner, &recipient, time)?;
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
    deal: Option<[String; 2]>,
    mirror_of: Option<String>,
    mirror_id: Option<String>,
    original_scores: Option<[i32; 2]>,
    lexicon_sha256: Option<String>,
}

const DUEL_SELECT: &str = "SELECT d.id,d.a,d.b,d.puzzle,d.score_a,d.score_b,d.move_a,d.move_b,
    (SELECT r.id FROM social_duels r WHERE r.rematch_of=d.id),
    deals.rack_a,deals.rack_b,deals.mirror_of,
    (SELECT m.duel_id FROM social_duel_deals m WHERE m.mirror_of=d.id),
    original.score_a,original.score_b,lexicons.lexicon_sha256
    FROM social_duels d
    LEFT JOIN social_duel_deals deals ON deals.duel_id=d.id
    LEFT JOIN social_duels original ON original.id=deals.mirror_of
    LEFT JOIN social_duel_lexicons lexicons ON lexicons.duel_id=d.id";

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
        deal: row
            .get::<_, Option<String>>(9)?
            .zip(row.get::<_, Option<String>>(10)?)
            .map(|(a, b)| [a, b]),
        mirror_of: row.get(11)?,
        mirror_id: row.get(12)?,
        original_scores: row
            .get::<_, Option<i32>>(13)?
            .zip(row.get::<_, Option<i32>>(14)?)
            .map(|(a, b)| [a, b]),
        lexicon_sha256: row.get(15)?,
    })
}

impl Duel {
    fn curated(&self) -> bool {
        self.lexicon_sha256.as_deref() == Some(FR_CLASSIC_LEXICON)
    }

    fn seat(&self, user: &str) -> usize {
        usize::from(self.a != user)
    }

    fn rack(&self, user: &str) -> Option<&str> {
        self.deal
            .as_ref()
            .map(|racks| racks[self.seat(user)].as_str())
    }

    fn completed(&self) -> bool {
        self.score_a.is_some() && self.score_b.is_some()
    }

    fn can_mirror(&self) -> bool {
        self.completed() && self.mirror_of.is_none() && self.deal.is_some()
    }
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
    let seat = duel.seat(user);
    let (original_score, original_opponent_score) =
        duel.original_scores.map_or((None, None), |scores| {
            (Some(scores[seat]), Some(scores[1 - seat]))
        });
    Ok(json!({
        "id":duel.id, "opponent":profile(db,other)?, "date":puzzle.date,
        "puzzle":puzzle.for_rack(&app.engine,duel.rack(user),duel.curated()),
        "score":score, "opponent_score":if completed {opponent_score} else {None},
        "status":status, "winner":winner, "rematch_id":duel.rematch_id,
        "can_cancel":!completed,
        "kind":if duel.mirror_of.is_some() {"mirror"} else {"classic"},
        "mirror_available":duel.can_mirror() && duel.mirror_id.is_none(), "mirror_id":duel.mirror_id,
        "original_score":original_score, "original_opponent_score":original_opponent_score,
    }))
}

fn duel_detail(app: &Social, db: &Connection, duel: &Duel, user: &str) -> Api<Value> {
    let mut result = duel_json(app, db, duel, user)?;
    if duel.completed() {
        let puzzle = &app.puzzles[duel.puzzle];
        let (own, opponent, other) = if duel.a == user {
            (&duel.move_a, &duel.move_b, duel.b.as_str())
        } else {
            (&duel.move_b, &duel.move_a, duel.a.as_str())
        };
        if let (Some(own), Some(opponent)) = (
            revealed_move(own.as_deref(), duel.rack(user).unwrap_or(puzzle.rack)),
            revealed_move(opponent.as_deref(), duel.rack(other).unwrap_or(puzzle.rack)),
        ) {
            result["move"] = own;
            result["opponent_move"] = opponent;
        }
    }
    Ok(result)
}

fn revealed_move(stored: Option<&str>, rack: &str) -> Option<Value> {
    let tiles = serde_json::from_str::<Vec<MoveTile>>(stored?).ok()?;
    let prepared = MoveRequest { tiles }.prepare().ok()?;
    let tiles: Vec<_> = prepared
        .tiles
        .into_iter()
        .map(|tile| {
            let rack_tile = rack.as_bytes().get(tile.rack_index)?;
            Some(json!({
                "row":tile.position / 15, "col":tile.position % 15, "letter":tile.letter,
                "blank":*rack_tile == b'?',
            }))
        })
        .collect::<Option<_>>()?;
    Some(json!(tiles))
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
    separate_racks: bool,
    supports_curated: bool,
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
    // A mirror reuses the preceding board. Follow the latest classic board rather
    // than counting rows, including when earlier releases counted the mirrors.
    let last_puzzle: Option<i64> = db
        .query_row(
            "SELECT d.puzzle FROM social_duels d
            LEFT JOIN social_duel_deals deal ON deal.duel_id=d.id
            WHERE d.a=?1 AND d.b=?2 AND deal.mirror_of IS NULL
            ORDER BY d.rowid DESC LIMIT 1",
            params![a, b],
            |row| row.get(0),
        )
        .optional()?;
    let id = random_hex::<16>();
    let puzzle = (last_puzzle.unwrap_or(-1) + 1) % app.puzzles.len() as i64;
    let curated = supports_curated
        && app.engine.profile.language == "fr"
        && db.query_row(
            "SELECT count(*) FROM social_lexicon_clients WHERE user_id IN (?1,?2) AND lexicon_sha256=?3",
            params![a, b, FR_CLASSIC_LEXICON],
            |row| row.get::<_, i64>(0),
        )? == 2;
    db.execute(
        "INSERT INTO social_duels(id,a,b,puzzle,created_at,rematch_of)
        VALUES(?1,?2,?3,?4,?5,?6)",
        params![id, a, b, puzzle, now(), previous],
    )?;
    if curated {
        record_curated_duel(db, &id)?;
    }
    if separate_racks {
        let [rack_a, rack_b] = app.puzzles[puzzle as usize].deal(&app.engine, curated)?;
        db.execute(
            "INSERT INTO social_duel_deals(duel_id,rack_a,rack_b) VALUES(?1,?2,?3)",
            params![id, rack_a, rack_b],
        )?;
    }
    duel(db, &id, a)
}

fn record_curated_duel(db: &Connection, id: &str) -> Api<()> {
    db.execute(
        "INSERT INTO social_duel_lexicons(duel_id,lexicon_sha256) VALUES(?1,?2)",
        params![id, FR_CLASSIC_LEXICON],
    )?;
    Ok(())
}

fn supports_separate_racks(headers: &HeaderMap) -> bool {
    headers
        .get("x-lettrine-duel-version")
        .is_some_and(|value| value == "2")
}

async fn create_duel(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    body: std::result::Result<Json<DuelRequest>, JsonRejection>,
) -> Api<Json<Value>> {
    let Json(body) = body.map_err(json_error)?;
    let separate_racks = supports_separate_racks(&headers);
    let curated = supports_curated(&headers);
    let session = auth(headers)?;
    write(app, move |app, db| {
        let id = user_id(db, &session)?;
        note_curated(app, db, &id, curated)?;
        let (a, b) = pair(&id, &body.friend_id);
        let duel = insert_duel(app, db, a, b, None, separate_racks, curated)?;
        Ok(Json(json!({"duel":duel_json(app,db,&duel,&id)?})))
    })
    .await
}

async fn get_duel(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    Path(duel_id): Path<String>,
) -> Api<Json<Value>> {
    let session = auth(headers)?;
    write(app, move |app, db| {
        let user = user_id(db, &session)?;
        let duel = duel(db, &duel_id, &user)?;
        if !friends(db, &duel.a, &duel.b)? {
            return Err(missing("Duel indisponible."));
        }
        Ok(Json(json!({
            "duel":duel_detail(app,db,&duel,&user)?,
            "referral":referral::snapshot(db,&user)?,
        })))
    })
    .await
}

async fn cancel_duel(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    Path(duel_id): Path<String>,
) -> Api<Json<Value>> {
    let session = auth(headers)?;
    write(app, move |_, db| {
        let user = user_id(db, &session)?;
        let duel = duel(db, &duel_id, &user)?;
        if !friends(db, &duel.a, &duel.b)? {
            return Err(missing("Duel indisponible."));
        }
        if duel.completed() {
            return Err(conflict("Un duel terminé ne peut pas être annulé."));
        }
        db.execute("DELETE FROM social_duels WHERE id=?1", [&duel_id])?;
        Ok(Json(json!({"cancelled":true})))
    })
    .await
}

#[derive(Debug, PartialEq, Serialize)]
struct PendingTurn {
    id: String,
    opponent: String,
    kind: &'static str,
    created_at: i64,
}

fn pending_turns(db: &Connection, user: &str) -> Api<Vec<PendingTurn>> {
    // At most one active duel per pair. Start from the user's bounded friend list
    // so polling does not scan their archived games.
    let mut statement = db.prepare_cached(
        "WITH contacts AS (
            SELECT a,b,b AS opponent FROM social_friends WHERE a=?1
            UNION ALL
            SELECT a,b,a AS opponent FROM social_friends WHERE b=?1
        )
        SELECT d.id,u.nickname,deals.mirror_of IS NOT NULL,d.created_at
        FROM contacts f
        JOIN social_duels d INDEXED BY social_active_duel ON d.a=f.a AND d.b=f.b
        JOIN social_users u ON u.id=f.opponent
        LEFT JOIN social_duel_deals deals ON deals.duel_id=d.id
        WHERE (d.score_a IS NULL OR d.score_b IS NULL)
          AND CASE WHEN d.a=?1 THEN d.score_a ELSE d.score_b END IS NULL
        ORDER BY d.created_at DESC,d.id",
    )?;
    let rows = statement.query_map([user], |row| {
        Ok(PendingTurn {
            id: row.get(0)?,
            opponent: row.get(1)?,
            kind: if row.get::<_, bool>(2)? {
                "mirror"
            } else {
                "classic"
            },
            created_at: row.get(3)?,
        })
    })?;
    Ok(rows.collect::<rusqlite::Result<_>>()?)
}

async fn turns(State(app): State<Arc<Social>>, headers: HeaderMap) -> Api<Response> {
    let session = auth(headers)?;
    let turns = with_db(app, move |_, db| {
        let user = user_id(db, &session)?;
        pending_turns(db, &user)
    })
    .await?;
    Ok((
        [(header::CACHE_CONTROL, "private, no-store")],
        Json(json!({"turns":turns})),
    )
        .into_response())
}

async fn rematch(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    Path(duel_id): Path<String>,
) -> Api<Json<Value>> {
    let separate_racks = supports_separate_racks(&headers);
    let curated = supports_curated(&headers);
    let session = auth(headers)?;
    write(app, move |app, db| {
        let user = user_id(db, &session)?;
        note_curated(app, db, &user, curated)?;
        let previous = duel(db, &duel_id, &user)?;
        if !previous.completed() {
            return Err(conflict("Terminez le duel avant de proposer une revanche."));
        }
        let duel = insert_duel(
            app,
            db,
            &previous.a,
            &previous.b,
            Some(&duel_id),
            separate_racks,
            curated,
        )?;
        Ok(Json(json!({"duel":duel_detail(app,db,&duel,&user)?})))
    })
    .await
}

async fn mirror(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    Path(duel_id): Path<String>,
) -> Api<Json<Value>> {
    let session = auth(headers)?;
    write(app, move |app, db| {
        let user = user_id(db, &session)?;
        let previous = duel(db, &duel_id, &user)?;
        if !friends(db, &previous.a, &previous.b)? {
            return Err(missing("Duel indisponible."));
        }
        if let Some(mirror_id) = &previous.mirror_id {
            let existing = duel(db, mirror_id, &user)?;
            return Ok(Json(json!({"duel":duel_detail(app,db,&existing,&user)?})));
        }
        if !previous.can_mirror() {
            return Err(conflict(
                "La revanche miroir est disponible après un duel à deux chevalets.",
            ));
        }
        if active_duel(db, &previous.a, &previous.b)?.is_some() {
            return Err(conflict("Un duel est déjà en cours avec cet ami."));
        }
        let curated = previous.curated();
        let [rack_a, rack_b] = previous.deal.expect("duel à deux chevalets");
        let id = random_hex::<16>();
        db.execute(
            "INSERT INTO social_duels(id,a,b,puzzle,created_at) VALUES(?1,?2,?3,?4,?5)",
            params![id, previous.a, previous.b, previous.puzzle as i64, now()],
        )?;
        if curated {
            record_curated_duel(db, &id)?;
        }
        db.execute(
            "INSERT INTO social_duel_deals(duel_id,rack_a,rack_b,mirror_of) VALUES(?1,?2,?3,?4)",
            params![id, rack_b, rack_a, duel_id],
        )?;
        let mirror = duel(db, &id, &user)?;
        Ok(Json(json!({"duel":duel_json(app,db,&mirror,&user)?})))
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

    fn score(&self, app: &Social, puzzle: &Puzzle, curated: bool) -> Api<i32> {
        self.score_with_rack(app, puzzle, &puzzle.rack_codes, curated)
    }

    fn score_with_rack(
        &self,
        app: &Social,
        puzzle: &Puzzle,
        rack: &[u8],
        curated: bool,
    ) -> Api<i32> {
        let placements: Vec<_> = self
            .tiles
            .iter()
            .map(|tile| (tile.position, tile.rack_index, tile.letter))
            .collect();
        let excludes = |word: &[u8]| classic_excludes(&app.engine, word);
        let filter = curated.then_some(&excludes as &dyn Fn(&[u8]) -> bool);
        Game::score_position(&app.engine, &puzzle.board_codes, rack, &placements, filter)
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
            previous.move_a.as_ref()
        } else {
            previous.move_b.as_ref()
        };
        if let Some(prior_move) = prior_move {
            if &canonical != prior_move {
                return Err(conflict("Vous avez déjà joué ce duel."));
            }
        } else {
            let puzzle = &app.puzzles[previous.puzzle];
            let score = match previous.rack(&user) {
                Some(rack) => {
                    let rack = rack
                        .chars()
                        .map(|letter| app.engine.symbol_id(letter))
                        .collect::<Result<Vec<_>>>()
                        .map_err(|_| {
                            err(StatusCode::INTERNAL_SERVER_ERROR, "Chevalet indisponible.")
                        })?;
                    body.score_with_rack(app, puzzle, &rack, previous.curated())?
                }
                None => body.score(app, puzzle, previous.curated())?,
            };
            let update = if seat_a {
                "UPDATE social_duels SET score_a=?1,move_a=?2 WHERE id=?3"
            } else {
                "UPDATE social_duels SET score_b=?1,move_b=?2 WHERE id=?3"
            };
            db.execute(update, params![score, canonical, duel_id])?;
        }
        let duel = duel(db, &duel_id, &user)?;
        if !previous.completed()
            && duel.completed()
            && duel.move_a.is_some()
            && duel.move_b.is_some()
        {
            referral::complete(db, &duel.a, &duel.b, now())?;
        }
        Ok(Json(json!({
            "duel":duel_detail(app,db,&duel,&user)?,
            "referral":referral::snapshot(db,&user)?,
        })))
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
    let curated = supports_curated(&headers);
    let session = auth(headers)?;
    write(app, move |app, db| {
        let user = user_id(db,&session)?;
        note_curated(app, db, &user, curated)?;
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
            "referral":referral::snapshot(db,&user)?,
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
        OR EXISTS(SELECT 1 FROM social_referral_wallets WHERE user_id=?1)
        OR EXISTS(SELECT 1 FROM social_blocks WHERE owner=?1 OR other=?1)",
        [user],
        |row| row.get::<_, bool>(0),
    )? {
        return Err(conflict(GOOGLE_PROFILE_CONFLICT));
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
        serve_test(router(app)).await
    }

    async fn serve_test(router: Router) -> (String, JoinHandle<()>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = format!("http://{}", listener.local_addr().unwrap());
        let handle = tokio::spawn(async move {
            axum::serve(listener, router).await.unwrap();
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
        request_path(
            base,
            method,
            &format!("/api/social/{path}"),
            token,
            body,
            status,
        )
        .await
    }

    async fn request_path(
        base: &str,
        method: Method,
        path: &str,
        token: &str,
        body: Value,
        status: StatusCode,
    ) -> Value {
        let mut req = reqwest::Client::new().request(method, format!("{base}{path}"));
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

    pub(super) fn english_engine() -> Engine {
        Engine::load(
            FilePath::new("data/en/words.txt"),
            "ENABLE2K".into(),
            crate::profiles::load("en-wespa-v1").unwrap(),
        )
        .unwrap()
    }

    #[tokio::test]
    async fn french_and_english_sessions_duels_and_tournaments_are_isolated() {
        let french = Arc::new(Social::open(FilePath::new(":memory:"), engine()).unwrap());
        let english = Arc::new(Social::open(FilePath::new(":memory:"), english_engine()).unwrap());
        english
            .tournament_time
            .store(20_771 * 86_400, std::sync::atomic::Ordering::Relaxed);
        let (base, server) = serve_test(bilingual_router(french, english.clone())).await;
        let en = |method, path: &str, token: &str, body, status| {
            let base = base.clone();
            let path = format!("/api/social-en/{path}");
            let token = token.to_owned();
            async move { request_path(&base, method, &path, &token, body, status).await }
        };
        let fr_token = "1".repeat(64);
        let a = "a".repeat(64);
        let b = "b".repeat(64);
        request(
            &base,
            Method::POST,
            "session",
            "",
            json!({"token":fr_token,"nickname":"Alice FR"}),
            StatusCode::OK,
        )
        .await;
        let denied_en = en(
            Method::GET,
            "me",
            &fr_token,
            Value::Null,
            StatusCode::UNAUTHORIZED,
        )
        .await;
        assert_eq!(denied_en["error"], "Session unavailable. Sign in again.");
        let alice = en(
            Method::POST,
            "session",
            "",
            json!({"token":a}),
            StatusCode::OK,
        )
        .await;
        assert!(
            alice["profile"]["nickname"]
                .as_str()
                .unwrap()
                .starts_with("Player ")
        );
        let bob = en(
            Method::POST,
            "session",
            "",
            json!({"token":b,"nickname":"Bob EN"}),
            StatusCode::OK,
        )
        .await;
        let denied_fr = request(
            &base,
            Method::GET,
            "me",
            &a,
            Value::Null,
            StatusCode::UNAUTHORIZED,
        )
        .await;
        assert_eq!(
            denied_fr["error"],
            "Session indisponible. Reconnectez-vous."
        );
        let invite = en(Method::POST, "invites", &a, json!({}), StatusCode::OK).await;
        en(
            Method::POST,
            "invites/accept",
            &b,
            json!({"token":invite["token"]}),
            StatusCode::OK,
        )
        .await;
        let response = reqwest::Client::new()
            .post(format!("{base}/api/social-en/duels"))
            .bearer_auth(&a)
            .header("X-Lettrine-Duel-Version", "2")
            .json(&json!({"friend_id":bob["profile"]["id"]}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let duel: Value = response.json().await.unwrap();
        assert_eq!(duel["duel"]["puzzle"]["opening_word"], "DATE");
        let puzzle = &english.puzzles[0];
        assert_eq!(duel["duel"]["puzzle"]["catalog_identity"], puzzle.identity);
        let id = duel["duel"]["id"].as_str().unwrap();
        let bob_duel = en(
            Method::GET,
            &format!("duels/{id}"),
            &b,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        let rack_a = duel["duel"]["puzzle"]["rack"].as_str().unwrap();
        let rack_b = bob_duel["duel"]["puzzle"]["rack"].as_str().unwrap();
        assert_ne!(rack_a, rack_b);
        let placement_a = candidate(&english, puzzle, rack_a, rack_b);
        let placement_b = candidate(&english, puzzle, rack_b, rack_a);
        let duel_path = format!("duels/{id}/play");
        let played = en(
            Method::POST,
            &duel_path,
            &a,
            placement_a.clone(),
            StatusCode::OK,
        )
        .await;
        assert_eq!(played["referral"]["tickets_earned"], 0);
        let finished = en(
            Method::POST,
            &duel_path,
            &b,
            placement_b.clone(),
            StatusCode::OK,
        )
        .await;
        assert_eq!(finished["duel"]["status"], "finished");
        assert!(finished["duel"]["score"].as_i64().unwrap() > 0);
        assert_eq!(
            finished["referral"],
            json!({"wallet_id":bob["profile"]["id"],"tickets_earned":1})
        );
        let credited = en(
            Method::GET,
            &format!("duels/{id}"),
            &a,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        assert_eq!(
            credited["referral"],
            json!({"wallet_id":alice["profile"]["id"],"tickets_earned":1})
        );
        let mirror = en(
            Method::POST,
            &format!("duels/{id}/mirror"),
            &a,
            json!({}),
            StatusCode::OK,
        )
        .await;
        assert_eq!(mirror["duel"]["puzzle"]["rack"], rack_b);
        assert_eq!(mirror["duel"]["puzzle"]["opening_word"], "DATE");
        assert_eq!(mirror["duel"]["kind"], "mirror");
        let mirror_path = format!("duels/{}/play", mirror["duel"]["id"].as_str().unwrap());
        en(Method::POST, &mirror_path, &a, placement_b, StatusCode::OK).await;
        let finished = en(Method::POST, &mirror_path, &b, placement_a, StatusCode::OK).await;
        assert_eq!(finished["duel"]["status"], "finished");
        assert_eq!(finished["referral"]["tickets_earned"], 1);
        assert_eq!(
            finished["duel"]["score"],
            finished["duel"]["original_opponent_score"]
        );
        assert_eq!(
            finished["duel"]["opponent_score"],
            finished["duel"]["original_score"]
        );
        let weekend = en(Method::GET, "tournament", &a, Value::Null, StatusCode::OK).await;
        assert_eq!(weekend["status"], "open");
        for (index, puzzle) in english.puzzles.iter().take(3).enumerate() {
            assert_eq!(
                weekend["slots"][index]["puzzle"]["identity"],
                puzzle.identity
            );
            let path = format!(
                "tournament/{}/slots/{}/play",
                weekend["id"].as_i64().unwrap(),
                index + 1
            );
            let played = en(
                Method::POST,
                &path,
                &a,
                candidate(&english, puzzle, puzzle.rack, puzzle.rack),
                StatusCode::OK,
            )
            .await;
            assert_eq!(played["completed"], index + 1);
            assert_eq!(played["card_background_unlocked"], index == 2);
        }
        server.abort();
    }

    async fn turn_feed(base: &str, token: &str) -> Value {
        let response = reqwest::Client::new()
            .get(format!("{base}/api/social/turns"))
            .bearer_auth(token)
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            response.headers()[header::CACHE_CONTROL],
            "private, no-store"
        );
        response.json().await.unwrap()
    }

    fn expected_turn(id: &str, opponent: &str, kind: &str, feed: &Value) -> Value {
        let created_at = feed["turns"][0]["created_at"].as_i64().unwrap();
        assert!(created_at > 0 && created_at <= now());
        json!({"turns":[{"id":id,"opponent":opponent,"kind":kind,"created_at":created_at}]})
    }

    fn candidate(app: &Social, puzzle: &Puzzle, rack: &str, other: &str) -> Value {
        let rack_codes = rack
            .chars()
            .map(|tile| app.engine.symbol_id(tile).unwrap())
            .collect::<Vec<_>>();
        let mut generator = app.engine.generator();
        app.engine
            .generate(&mut generator, &puzzle.board_codes, &rack_codes, 128);
        let moves = generator
            .plays
            .iter()
            .filter_map(|entry| {
                let (_, tiles, _) = app.engine.describe(&puzzle.board_codes, &entry.play);
                if tiles.is_empty() {
                    return None;
                }
                let mut used = [false; 7];
                let moves = tiles
                    .into_iter()
                    .map(|tile| {
                        let index = rack
                            .chars()
                            .enumerate()
                            .find(|&(index, letter)| !used[index] && letter == tile.letter)
                            .unwrap()
                            .0;
                        used[index] = true;
                        json!({"position":usize::from(tile.row) * 15 + usize::from(tile.col),
                    "rack_index":index, "letter":tile.letter})
                    })
                    .collect::<Vec<_>>();
                Some(moves)
            })
            .collect::<Vec<_>>();
        let best = moves
            .iter()
            .find(|tiles| {
                tiles.iter().any(|tile| {
                    let index = tile["rack_index"].as_u64().unwrap() as usize;
                    other.as_bytes()[index] != tile["letter"].as_str().unwrap().as_bytes()[0]
                })
            })
            .or_else(|| moves.first())
            .expect("chevalet jouable");
        json!({"tiles":best})
    }

    #[test]
    fn every_catalog_board_supports_two_playable_racks_from_one_bag() {
        let app = Social::open(FilePath::new(":memory:"), engine()).unwrap();
        let alphabet = app.engine.config.alphabet();
        for puzzle in &app.puzzles {
            let [a, b] = puzzle.deal(&app.engine, false).unwrap();
            assert_ne!(
                puzzle.for_rack(&app.engine, Some(&a), false)["identity"],
                puzzle.for_rack(&app.engine, Some(&b), false)["identity"]
            );
            assert_eq!(
                puzzle.for_rack(&app.engine, None, false)["identity"],
                puzzle.identity
            );
            let mut remaining = (0..alphabet.len())
                .map(|tile| alphabet.freq(tile))
                .collect::<Vec<_>>();
            for &tile in &puzzle.board_codes {
                if tile != 0 {
                    remaining[usize::from(tile)] -= 1;
                }
            }
            for (rack, other) in [(&a, &b), (&b, &a)] {
                let rack_codes = rack
                    .chars()
                    .map(|letter| app.engine.symbol_id(letter).unwrap())
                    .collect::<Vec<_>>();
                assert_eq!(rack_codes.len(), 7);
                for &tile in &rack_codes {
                    let remaining = &mut remaining[usize::from(tile)];
                    assert_ne!(*remaining, 0);
                    *remaining -= 1;
                }
                let moves: MoveRequest =
                    serde_json::from_value(candidate(&app, puzzle, rack, other)).unwrap();
                assert!(
                    moves
                        .prepare()
                        .unwrap()
                        .score_with_rack(&app, puzzle, &rack_codes, false)
                        .is_ok()
                );
            }
        }
    }

    #[test]
    fn curated_catalog_uses_android_digest_and_keeps_every_board_playable() {
        let raw = std::fs::read_to_string("data/fr/words.txt").unwrap();
        let excluded = CLASSIC_EXCLUSIONS.lines().collect::<Vec<_>>();
        assert!(excluded.windows(2).all(|pair| pair[0] < pair[1]));
        let mut fingerprint = Sha256::new();
        let mut removed = 0;
        for word in raw.lines() {
            if excluded.binary_search(&word).is_ok() {
                removed += 1;
            } else {
                fingerprint.update(word);
                fingerprint.update(b"\n");
            }
        }
        assert_eq!(removed, excluded.len());
        assert_eq!(format!("{:x}", fingerprint.finalize()), FR_CLASSIC_LEXICON);

        let app = Social::open(FilePath::new(":memory:"), engine()).unwrap();
        let mut generator = app.engine.generator();
        let mut scorer = PlayScorer::new();
        for puzzle in &app.puzzles {
            let opening = puzzle
                .opening_word
                .chars()
                .map(|c| app.engine.symbol_id(c).unwrap())
                .collect::<Vec<_>>();
            assert!(!classic_excludes(&app.engine, &opening));
            let [a, b] = puzzle.deal(&app.engine, true).unwrap();
            for rack in [puzzle.rack, a.as_str(), b.as_str()] {
                let rack = rack
                    .chars()
                    .map(|c| app.engine.symbol_id(c).unwrap())
                    .collect::<Vec<_>>();
                puzzle.generate(&app.engine, &mut generator, &rack, true);
                let snapshot = app.engine.snapshot(&puzzle.board_codes);
                assert!(
                    generator
                        .plays
                        .iter()
                        .any(|entry| matches!(entry.play, Play::Place { .. })),
                    "aucun placement classique pour {}",
                    puzzle.date
                );
                for entry in &generator.plays {
                    assert!(
                        scorer.words_all(&snapshot, &entry.play, |word| !classic_excludes(
                            &app.engine,
                            word
                        ))
                    );
                }
            }
        }
    }

    async fn curated_request(
        base: &str,
        method: Method,
        route: &str,
        token: &str,
        body: Value,
        status: StatusCode,
    ) -> Value {
        let mut request = reqwest::Client::new()
            .request(method, format!("{base}/api/social/{route}"))
            .header(LEXICON_HEADER, FR_CLASSIC_LEXICON);
        if !token.is_empty() {
            request = request.bearer_auth(token);
        }
        if !body.is_null() {
            request = request.json(&body);
        }
        let response = request.send().await.unwrap();
        let actual = response.status();
        let payload = response.json::<Value>().await.unwrap();
        assert_eq!(actual, status, "{route}: {payload}");
        payload
    }

    #[tokio::test]
    async fn french_rollout_preserves_old_invites_and_active_duels_and_persists_new_rules() {
        let app = Arc::new(Social::open(FilePath::new(":memory:"), engine()).unwrap());
        let (base, server) = server(app.clone()).await;
        let a = "a".repeat(64);
        let b = "b".repeat(64);
        let alice = curated_request(
            &base,
            Method::POST,
            "session",
            "",
            json!({"token":a}),
            StatusCode::OK,
        )
        .await;
        let bob = request(
            &base,
            Method::POST,
            "session",
            "",
            json!({"token":b}),
            StatusCode::OK,
        )
        .await;
        let invite = request(
            &base,
            Method::POST,
            "invites",
            &b,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        let accepted = curated_request(
            &base,
            Method::POST,
            "invites/accept",
            &a,
            json!({"token":invite["token"]}),
            StatusCode::OK,
        )
        .await;
        assert_eq!(accepted["friend"]["id"], bob["profile"]["id"]);
        let legacy = curated_request(
            &base,
            Method::POST,
            "duels",
            &a,
            json!({"friend_id":bob["profile"]["id"]}),
            StatusCode::OK,
        )
        .await;
        assert!(legacy["duel"]["puzzle"].get("lexicon_sha256").is_none());
        let id = legacy["duel"]["id"].as_str().unwrap();
        let path = format!("duels/{id}");
        let play = format!("{path}/play");
        let puzzle = &app.puzzles[0];
        let move_a = candidate(&app, puzzle, puzzle.rack, puzzle.rack);
        let placed = request(
            &base,
            Method::POST,
            &play,
            &a,
            move_a.clone(),
            StatusCode::OK,
        )
        .await;
        assert_eq!(placed["duel"]["status"], "waiting");
        curated_request(&base, Method::GET, "me", &b, Value::Null, StatusCode::OK).await;
        let legacy_b = curated_request(
            &base,
            Method::POST,
            "duels",
            &b,
            json!({"friend_id":alice["profile"]["id"]}),
            StatusCode::OK,
        )
        .await;
        assert_eq!(legacy_b["duel"]["id"], id);
        assert_eq!(legacy_b["duel"]["puzzle"], legacy["duel"]["puzzle"]);
        let finished =
            curated_request(&base, Method::POST, &play, &b, move_a, StatusCode::OK).await;
        assert_eq!(finished["duel"]["status"], "finished");
        assert_eq!(finished["duel"]["opponent_score"], placed["duel"]["score"]);
        let curated = curated_request(
            &base,
            Method::POST,
            &format!("{path}/rematch"),
            &a,
            json!({}),
            StatusCode::OK,
        )
        .await;
        let new_id = curated["duel"]["id"].as_str().unwrap();
        let new_path = format!("duels/{new_id}");
        assert_eq!(
            curated["duel"]["puzzle"]["lexicon_sha256"],
            FR_CLASSIC_LEXICON
        );
        assert_eq!(
            curated["duel"]["puzzle"]["identity"],
            app.puzzles[1].identity
        );
        let old_client = request(
            &base,
            Method::GET,
            &new_path,
            &b,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        assert_eq!(old_client["duel"]["puzzle"], curated["duel"]["puzzle"]);

        // Place H next to P in the PASSE opening. Supply a deterministic rack
        // after asserting the shared-rack payload, then verify a legacy build
        // can submit but cannot bypass the duel's stored dictionary.
        app.db.lock().unwrap().execute(
            "INSERT INTO social_duel_deals(duel_id,rack_a,rack_b) VALUES(?1,'HATESUX','HATESOU')",
            [new_id],
        ).unwrap();
        let ph = json!({"tiles":[{"position":83,"rack_index":0,"letter":"H"}]});
        let ph_move = serde_json::from_value::<MoveRequest>(ph.clone())
            .unwrap()
            .prepare()
            .unwrap();
        let h = app.engine.symbol_id('H').unwrap();
        assert!(
            ph_move
                .score_with_rack(&app, &app.puzzles[1], &[h], false)
                .is_ok()
        );
        let rejected = request(
            &base,
            Method::POST,
            &format!("{new_path}/play"),
            &b,
            ph,
            StatusCode::BAD_REQUEST,
        )
        .await;
        assert_eq!(rejected["error"], "« PH » est absent du lexique.");

        // Verify the same formed-word filter on a normal placement and joker.
        let board = vec![0; 225];
        let rack = ['K', 'M'].map(|c| app.engine.symbol_id(c).unwrap());
        let tiles = [(112, 0, 'K'), (113, 1, 'M')];
        assert!(Game::score_position(&app.engine, &board, &rack, &tiles, None).is_ok());
        let excludes = |word: &[u8]| classic_excludes(&app.engine, word);
        for rack in [rack, [0, rack[1]]] {
            assert_eq!(
                Game::score_position(&app.engine, &board, &rack, &tiles, Some(&excludes)),
                Err("« KM » est absent du lexique.".into())
            );
        }
        server.abort();
    }

    #[tokio::test]
    async fn mirror_swaps_racks_once_without_changing_the_board_or_leaking_play() {
        let app = Arc::new(Social::open(FilePath::new(":memory:"), engine()).unwrap());
        let (base, server) = server(app.clone()).await;
        let a = "a".repeat(64);
        let b = "b".repeat(64);
        let c = "c".repeat(64);
        for token in ["", "invalid", &a] {
            request(
                &base,
                Method::GET,
                "turns",
                token,
                Value::Null,
                StatusCode::UNAUTHORIZED,
            )
            .await;
        }
        assert_eq!(
            app.db
                .lock()
                .unwrap()
                .query_row("SELECT count(*) FROM social_sessions", [], |row| row
                    .get::<_, i64>(0))
                .unwrap(),
            0
        );
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
        let bob = request(&base, Method::GET, "me", &b, Value::Null, StatusCode::OK).await;
        let id_b = bob["profile"]["id"].as_str().unwrap();
        assert_eq!(
            bob["referral"],
            json!({"wallet_id":id_b,"tickets_earned":0})
        );
        let invitation = request(
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
            json!({"token":invitation["token"]}),
            StatusCode::OK,
        )
        .await;
        let response = reqwest::Client::new()
            .post(format!("{base}/api/social/duels"))
            .bearer_auth(&a)
            .header("X-Lettrine-Duel-Version", "2")
            .json(&json!({"friend_id":id_b}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let response: Value = response.json().await.unwrap();
        let a_classic = &response["duel"];
        let id = a_classic["id"].as_str().unwrap();
        let empty = json!({"turns":[]});
        let classic_a_turn = turn_feed(&base, &a).await;
        let classic_b_turn = turn_feed(&base, &b).await;
        assert_eq!(
            classic_a_turn,
            expected_turn(id, "Benoît", "classic", &classic_a_turn)
        );
        assert_eq!(
            classic_b_turn,
            expected_turn(id, "Alice", "classic", &classic_b_turn)
        );
        assert_eq!(
            classic_a_turn["turns"][0]["created_at"],
            classic_b_turn["turns"][0]["created_at"]
        );
        assert_eq!(turn_feed(&base, &c).await, empty);
        let classic_path = format!("duels/{id}");
        let classic_play = format!("{classic_path}/play");
        let mirror_path = format!("{classic_path}/mirror");
        let b_classic = request(
            &base,
            Method::GET,
            &classic_path,
            &b,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        let a_puzzle = &a_classic["puzzle"];
        let b_puzzle = &b_classic["duel"]["puzzle"];
        let rack_a = a_puzzle["rack"].as_str().unwrap();
        let rack_b = b_puzzle["rack"].as_str().unwrap();
        assert_ne!(rack_a, rack_b);
        assert_ne!(a_puzzle["identity"], b_puzzle["identity"]);
        assert_eq!(a_classic["kind"], "classic");
        assert_eq!(a_classic["mirror_available"], false);
        assert!(a_puzzle.get("reference").is_none() && a_classic.get("opponent_rack").is_none());
        for field in [
            "date",
            "catalog_identity",
            "opening_word",
            "opening_start",
            "opening_end",
        ] {
            assert_eq!(a_puzzle[field], b_puzzle[field]);
        }
        request(
            &base,
            Method::GET,
            &classic_path,
            &c,
            Value::Null,
            StatusCode::NOT_FOUND,
        )
        .await;
        request(
            &base,
            Method::POST,
            &mirror_path,
            &c,
            json!({}),
            StatusCode::NOT_FOUND,
        )
        .await;
        request(
            &base,
            Method::POST,
            &mirror_path,
            &a,
            json!({}),
            StatusCode::CONFLICT,
        )
        .await;

        let move_a = candidate(&app, &app.puzzles[0], rack_a, rack_b);
        let move_b = candidate(&app, &app.puzzles[0], rack_b, rack_a);
        let first = request(
            &base,
            Method::POST,
            &classic_play,
            &a,
            move_a.clone(),
            StatusCode::OK,
        )
        .await;
        assert_eq!(first["duel"]["status"], "waiting");
        assert!(first["duel"]["opponent_score"].is_null());
        assert!(first["duel"].get("opponent_move").is_none());
        let unopened = request(
            &base,
            Method::GET,
            &classic_path,
            &b,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        assert!(unopened["duel"].get("opponent_move").is_none());
        assert_eq!(turn_feed(&base, &a).await, empty);
        assert_eq!(turn_feed(&base, &b).await, classic_b_turn);
        let finished = request(
            &base,
            Method::POST,
            &classic_play,
            &b,
            move_b.clone(),
            StatusCode::OK,
        )
        .await;
        assert_eq!(finished["duel"]["mirror_available"], true);
        assert_eq!(
            finished["duel"]["opponent_move"].as_array().unwrap().len(),
            move_a["tiles"].as_array().unwrap().len()
        );
        assert!(finished["duel"].get("opponent_rack").is_none());
        assert!(
            finished["duel"]["opponent_move"][0]
                .get("rack_index")
                .is_none()
        );
        assert_eq!(turn_feed(&base, &b).await, empty);
        let (a_mirror, b_mirror) = tokio::join!(
            request(
                &base,
                Method::POST,
                &mirror_path,
                &a,
                json!({}),
                StatusCode::OK
            ),
            request(
                &base,
                Method::POST,
                &mirror_path,
                &b,
                json!({}),
                StatusCode::OK
            ),
        );
        let id_m = a_mirror["duel"]["id"].as_str().unwrap();
        let mirror_a_turn = turn_feed(&base, &a).await;
        let mirror_b_turn = turn_feed(&base, &b).await;
        assert_eq!(
            mirror_a_turn,
            expected_turn(id_m, "Benoît", "mirror", &mirror_a_turn)
        );
        assert_eq!(
            mirror_b_turn,
            expected_turn(id_m, "Alice", "mirror", &mirror_b_turn)
        );
        assert_eq!(a_mirror["duel"]["id"], b_mirror["duel"]["id"]);
        assert_ne!(id, id_m);
        assert_eq!(a_mirror["duel"]["kind"], "mirror");
        assert_eq!(a_mirror["duel"]["puzzle"], *b_puzzle);
        assert_eq!(b_mirror["duel"]["puzzle"], *a_puzzle);
        assert_eq!(a_mirror["duel"]["original_score"], first["duel"]["score"]);
        assert_eq!(
            a_mirror["duel"]["original_opponent_score"],
            finished["duel"]["score"]
        );
        assert!(a_mirror["duel"]["opponent_score"].is_null());
        let original = request(
            &base,
            Method::GET,
            &classic_path,
            &b,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        assert_eq!(original["duel"]["mirror_id"], id_m);
        assert_eq!(original["duel"]["mirror_available"], false);
        let m_play = format!("duels/{id_m}/play");
        if move_a["tiles"].as_array().unwrap().iter().any(|tile| {
            rack_b.as_bytes()[tile["rack_index"].as_u64().unwrap() as usize]
                != tile["letter"].as_str().unwrap().as_bytes()[0]
        }) {
            request(
                &base,
                Method::POST,
                &m_play,
                &a,
                move_a.clone(),
                StatusCode::BAD_REQUEST,
            )
            .await;
        }
        let replayed = request(&base, Method::POST, &m_play, &a, move_b, StatusCode::OK).await;
        assert_eq!(replayed["duel"]["score"], finished["duel"]["score"]);
        assert_eq!(turn_feed(&base, &a).await, empty);
        assert_eq!(turn_feed(&base, &b).await, mirror_b_turn);
        let complete = request(&base, Method::POST, &m_play, &b, move_a, StatusCode::OK).await;
        assert_eq!(complete["duel"]["score"], first["duel"]["score"]);
        assert_eq!(complete["duel"]["mirror_available"], false);
        let restored_mirror = request(
            &base,
            Method::POST,
            &mirror_path,
            &b,
            json!({}),
            StatusCode::OK,
        )
        .await;
        assert_eq!(restored_mirror["duel"], complete["duel"]);
        request(
            &base,
            Method::POST,
            &format!("duels/{id_m}/mirror"),
            &a,
            json!({}),
            StatusCode::CONFLICT,
        )
        .await;
        let rematch_path = format!("{classic_path}/rematch");
        let regular = request(
            &base,
            Method::POST,
            &rematch_path,
            &a,
            json!({}),
            StatusCode::OK,
        )
        .await;
        assert_eq!(regular["duel"]["kind"], "classic");
        assert_eq!(regular["duel"]["date"], app.puzzles[1].date);
        assert_eq!(
            turn_feed(&base, &b).await["turns"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        let rematch_id = regular["duel"]["id"].as_str().unwrap();
        let puzzle = &app.puzzles[1];
        let placement = candidate(&app, puzzle, puzzle.rack, puzzle.rack);
        let rematch_play = format!("duels/{rematch_id}/play");
        request(
            &base,
            Method::POST,
            &rematch_play,
            &a,
            placement.clone(),
            StatusCode::OK,
        )
        .await;
        let finished_rematch = request(
            &base,
            Method::POST,
            &rematch_play,
            &b,
            placement,
            StatusCode::OK,
        )
        .await;
        let restored_rematch = request(
            &base,
            Method::POST,
            &rematch_path,
            &b,
            json!({}),
            StatusCode::OK,
        )
        .await;
        assert_eq!(restored_rematch["duel"], finished_rematch["duel"]);
        request(
            &base,
            Method::POST,
            &format!("friends/{id_b}/block"),
            &a,
            json!({}),
            StatusCode::OK,
        )
        .await;
        assert_eq!(turn_feed(&base, &a).await, empty);
        assert_eq!(turn_feed(&base, &b).await, empty);
        request(
            &base,
            Method::GET,
            &format!("duels/{id_m}"),
            &b,
            Value::Null,
            StatusCode::NOT_FOUND,
        )
        .await;
        request(
            &base,
            Method::POST,
            &mirror_path,
            &b,
            json!({}),
            StatusCode::NOT_FOUND,
        )
        .await;
        request(
            &base,
            Method::DELETE,
            "profile",
            &a,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        let db = app.db.lock().unwrap();
        assert_eq!(
            db.query_row("SELECT count(*) FROM social_duel_deals", [], |row| row
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
        server.abort();
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
        assert_eq!(duel["duel"]["mirror_available"], false);
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
        assert_eq!(played["referral"]["tickets_earned"], 0);
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
        assert_eq!(waiting["duels"][0]["can_cancel"], true);
        let completed = request(
            &base,
            Method::POST,
            &submission,
            &b,
            reference.clone(),
            StatusCode::OK,
        )
        .await;
        assert_eq!(completed["duel"]["status"], "finished");
        assert_eq!(completed["duel"]["score"], 17);
        assert_eq!(completed["duel"]["opponent_score"], 15);
        assert_eq!(completed["duel"]["winner"], id_b);
        assert_eq!(completed["duel"]["can_cancel"], false);
        let single_tile = json!([{"row":7,"col":11,"letter":"S","blank":false}]);
        assert_eq!(completed["duel"]["opponent_move"], single_tile);
        let duel_path = format!("duels/{id}");
        let details = request(
            &base,
            Method::GET,
            &duel_path,
            &a,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        assert_eq!(details["duel"]["move"], single_tile);
        assert_eq!(details["duel"]["opponent_move"], completed["duel"]["move"]);
        request(
            &base,
            Method::DELETE,
            &duel_path,
            &a,
            Value::Null,
            StatusCode::CONFLICT,
        )
        .await;
        assert_eq!(completed["referral"]["tickets_earned"], 1);
        assert_eq!(completed["duel"]["mirror_available"], false);
        let repeated = request(
            &base,
            Method::POST,
            &submission,
            &b,
            reference,
            StatusCode::OK,
        )
        .await;
        assert_eq!(repeated["referral"], completed["referral"]);
        request(
            &base,
            Method::POST,
            &format!("duels/{id}/mirror"),
            &b,
            json!({}),
            StatusCode::CONFLICT,
        )
        .await;
        let ranks = request(&base, Method::GET, "me", &a, Value::Null, StatusCode::OK).await;
        assert_eq!(
            ranks["referral"],
            json!({"wallet_id":id_a,"tickets_earned":1})
        );
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

    #[tokio::test]
    async fn participant_can_cancel_an_unfinished_duel_without_a_result_or_referral() {
        let app = Arc::new(Social::open(FilePath::new(":memory:"), engine()).unwrap());
        let (base, server) = server(app).await;
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
        let invitation = request(
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
            json!({"token":invitation["token"]}),
            StatusCode::OK,
        )
        .await;
        let bob = request(&base, Method::GET, "me", &b, Value::Null, StatusCode::OK).await;
        let friend = json!({"friend_id":bob["profile"]["id"]});
        let original = request(
            &base,
            Method::POST,
            "duels",
            &a,
            friend.clone(),
            StatusCode::OK,
        )
        .await;
        let id = original["duel"]["id"].as_str().unwrap();
        let path = format!("duels/{id}");
        let play_path = format!("{path}/play");
        let placement = json!({"tiles":[{"position":116,"rack_index":6,"letter":"S"}]});
        request(
            &base,
            Method::POST,
            &play_path,
            &a,
            placement.clone(),
            StatusCode::OK,
        )
        .await;
        request(
            &base,
            Method::DELETE,
            &path,
            "",
            Value::Null,
            StatusCode::UNAUTHORIZED,
        )
        .await;
        request(
            &base,
            Method::DELETE,
            &path,
            &c,
            Value::Null,
            StatusCode::NOT_FOUND,
        )
        .await;
        assert_eq!(turn_feed(&base, &b).await["turns"][0]["id"], id);

        let cancelled = request(
            &base,
            Method::DELETE,
            &path,
            &a,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        assert_eq!(cancelled, json!({"cancelled":true}));
        request(
            &base,
            Method::POST,
            &play_path,
            &b,
            placement,
            StatusCode::NOT_FOUND,
        )
        .await;
        for token in [&a, &b] {
            assert_eq!(turn_feed(&base, token).await, json!({"turns":[]}));
            let dashboard =
                request(&base, Method::GET, "me", token, Value::Null, StatusCode::OK).await;
            assert_eq!(dashboard["duels"], json!([]));
            assert_eq!(dashboard["referral"]["tickets_earned"], 0);
            assert!(
                dashboard["ranking"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .all(|row| row["points"] == 0)
            );
        }

        let replacement = request(&base, Method::POST, "duels", &a, friend, StatusCode::OK).await;
        assert_ne!(replacement["duel"]["id"], id);
        let replacement_path = format!("duels/{}", replacement["duel"]["id"].as_str().unwrap());
        request(
            &base,
            Method::DELETE,
            &replacement_path,
            &b,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        server.abort();
    }

    #[test]
    fn existing_duel_keeps_its_saved_state_and_catalog_rack_after_upgrade() {
        let path =
            std::env::temp_dir().join(format!("lettrine-duel-{}.sqlite", random_hex::<16>()));
        let a = "a";
        let b = "b";
        {
            let db = Connection::open(&path).unwrap();
            db.execute_batch(SCHEMA).unwrap();
            db.execute_batch(
                "DROP TABLE social_duel_deals;
                INSERT INTO social_users(id,nickname) VALUES ('a','Alice'),('b','Benoît');
                INSERT INTO social_friends(a,b) VALUES ('a','b');
                INSERT INTO social_duels(id,a,b,puzzle,score_a,move_a,created_at)
                    VALUES ('legacy','a','b',0,15,'legacy move',1);",
            )
            .unwrap();
        }
        {
            let app = Social::open(&path, engine()).unwrap();
            let db = app.db.lock().unwrap();
            let legacy = duel(&db, "legacy", a).unwrap();
            assert_eq!(legacy.move_a.as_deref(), Some("legacy move"));
            let alice = duel_json(&app, &db, &legacy, a).unwrap();
            let bob = duel_json(&app, &db, &legacy, b).unwrap();
            assert_eq!(alice["puzzle"], bob["puzzle"]);
            assert_eq!(alice["puzzle"]["rack"], "TEEATES");
            assert_eq!(alice["puzzle"]["identity"], "31f9f7d95680b9d54ef6d063");
            assert_eq!(alice["status"], "waiting");
            assert_eq!(alice["score"], 15);
            assert_eq!(bob["status"], "your_turn");
            assert!(bob["opponent_score"].is_null());
            assert_eq!(bob["mirror_available"], false);
            assert!(active_duel(&db, a, b).unwrap().is_some());
            assert!(pending_turns(&db, a).unwrap().is_empty());
            assert_eq!(
                pending_turns(&db, b).unwrap(),
                vec![PendingTurn {
                    id: "legacy".into(),
                    opponent: "Alice".into(),
                    kind: "classic",
                    created_at: 1,
                }]
            );
        }
        std::fs::remove_file(&path).unwrap();
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
