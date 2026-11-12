use crate::{
    engine::{Engine, Game, PAIRS},
    metrics::Metrics,
};
use anyhow::{Context, Result, bail};
use rusqlite::{Connection, OpenFlags, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{path::Path, time::Duration};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Campaign {
    pub target: u32,
    pub seed: u32,
    pub lexicon: String,
    pub lexicon_words: usize,
    pub lexicon_sha256: String,
    pub engine: String,
}
impl Campaign {
    pub fn new(engine: &Engine, target: u32, seed: u32) -> Self {
        Self {
            target,
            seed,
            lexicon: engine.lexicon.clone(),
            lexicon_words: engine.words,
            lexicon_sha256: engine.digest.clone(),
            engine: engine.profile.id.clone(),
        }
    }
}

pub fn open(path: &Path, write: bool) -> Result<Connection> {
    let flags = if write {
        OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE
    } else {
        OpenFlags::SQLITE_OPEN_READ_ONLY
    };
    let conn = Connection::open_with_flags(path, flags)
        .with_context(|| format!("ouverture de {}", path.display()))?;
    conn.busy_timeout(Duration::from_secs(15))?;
    if write {
        conn.execute_batch("
            PRAGMA journal_mode=WAL;
            PRAGMA synchronous=NORMAL;
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS games (
                id INTEGER PRIMARY KEY, seed INTEGER NOT NULL UNIQUE, a INTEGER NOT NULL, b INTEGER NOT NULL,
                score_a INTEGER NOT NULL, score_b INTEGER NOT NULL, turns INTEGER NOT NULL,
                winner INTEGER, payload BLOB NOT NULL
            );
            CREATE INDEX IF NOT EXISTS games_a ON games(a,id);
            CREATE INDEX IF NOT EXISTS games_b ON games(b,id);
        ")?;
    }
    Ok(conn)
}

pub fn get<T: serde::de::DeserializeOwned>(conn: &Connection, key: &str) -> Result<Option<T>> {
    let value: Option<String> = conn
        .query_row("SELECT value FROM meta WHERE key=?1", [key], |row| {
            row.get(0)
        })
        .optional()?;
    value
        .map(|s| serde_json::from_str(&s).map_err(Into::into))
        .transpose()
}

fn set(conn: &Connection, key: &str, value: &impl Serialize) -> Result<()> {
    conn.execute(
        "INSERT OR REPLACE INTO meta VALUES (?1,?2)",
        params![key, serde_json::to_string(value)?],
    )?;
    Ok(())
}

pub fn setup(conn: &mut Connection, campaign: &Campaign) -> Result<Metrics> {
    if let Some(previous) = get::<Campaign>(conn, "campaign")?
        && &previous != campaign
    {
        bail!("cette base contient une campagne différente. Utilisez un autre fichier .sqlite");
    }
    let metrics = get(conn, "metrics")?.unwrap_or_default();
    let tx = conn.transaction()?;
    set(&tx, "campaign", campaign)?;
    set(&tx, "metrics", &metrics)?;
    publish(&tx, campaign, &metrics, true, None)?;
    tx.commit()?;
    Ok(metrics)
}

fn publish(
    conn: &Connection,
    campaign: &Campaign,
    metrics: &Metrics,
    running: bool,
    error: Option<&str>,
) -> Result<()> {
    let mut public = metrics.public();
    public["running"] = json!(running && metrics.completed < campaign.target);
    public["target"] = json!(campaign.target);
    public["pair_target"] = json!(campaign.target / PAIRS.len() as u32);
    public["seed"] = json!(campaign.seed);
    public["lexicon"] = json!(campaign.lexicon);
    public["lexicon_words"] = json!(campaign.lexicon_words);
    public["lexicon_sha256"] = json!(campaign.lexicon_sha256);
    public["engine"] = json!(campaign.engine);
    public["error"] = json!(error);
    set(conn, "public", &public)
}

pub fn save(
    conn: &mut Connection,
    campaign: &Campaign,
    metrics: &Metrics,
    batch: Vec<(Game, Vec<u8>)>,
    running: bool,
    error: Option<&str>,
) -> Result<()> {
    let tx = conn.transaction()?;
    {
        let mut insert =
            tx.prepare_cached("INSERT INTO games VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)")?;
        for (g, payload) in batch {
            insert.execute(params![
                g.id,
                g.seed,
                g.levels[0],
                g.levels[1],
                g.scores[0],
                g.scores[1],
                g.turns as u32,
                g.winner,
                payload
            ])?;
        }
    }
    set(&tx, "metrics", metrics)?;
    publish(&tx, campaign, metrics, running, error)?;
    tx.commit()?;
    Ok(())
}

pub fn encode(game: &Game) -> Result<Vec<u8>> {
    Ok(zstd::bulk::compress(&serde_json::to_vec(game)?, 1)?)
}
pub fn decode(data: &[u8]) -> Result<Vec<u8>> {
    Ok(zstd::bulk::decompress(data, 256 * 1024)?)
}

pub fn detail(conn: &Connection, id: u32) -> Result<Option<Vec<u8>>> {
    let payload: Option<Vec<u8>> = conn
        .query_row("SELECT payload FROM games WHERE id=?1", [id], |r| r.get(0))
        .optional()?;
    payload.map(|p| decode(&p)).transpose()
}

#[derive(Default, Deserialize)]
pub struct Filter {
    pub limit: Option<u32>,
    pub offset: Option<u32>,
    pub level: Option<u8>,
    pub elite: Option<u8>,
    pub q: Option<String>,
    pub format: Option<String>,
}
impl Filter {
    pub fn bindings(&self) -> (Option<u8>, bool, Option<i64>) {
        let number = self.q.as_deref().filter(|q| !q.trim().is_empty()).map(|q| {
            q.trim()
                .trim_start_matches('#')
                .parse::<i64>()
                .unwrap_or(-1)
        });
        (self.level, self.elite == Some(1), number)
    }
}

pub const WHERE: &str = " WHERE (?1 IS NULL OR a=?1 OR b=?1) AND (NOT ?2 OR (a=3 AND b=3)) AND (?3 IS NULL OR id=?3 OR seed=?3)";

pub fn summary(r: &rusqlite::Row<'_>) -> rusqlite::Result<Value> {
    Ok(json!({
        "id":r.get::<_,u32>(0)?, "seed":r.get::<_,u32>(1)?,
        "levels":[r.get::<_,u8>(2)?,r.get::<_,u8>(3)?], "scores":[r.get::<_,i32>(4)?,r.get::<_,i32>(5)?],
        "turns":r.get::<_,u32>(6)?, "winner":r.get::<_,Option<u8>>(7)?
    }))
}

pub fn list(conn: &Connection, f: &Filter) -> Result<Value> {
    let (level, elite, q) = f.bindings();
    let total: u32 = conn.query_row(
        &format!("SELECT COUNT(*) FROM games{WHERE}"),
        params![level, elite, q],
        |r| r.get(0),
    )?;
    let mut stmt = conn.prepare(&format!("SELECT id,seed,a,b,score_a,score_b,turns,winner FROM games{WHERE} ORDER BY id DESC LIMIT ?4 OFFSET ?5"))?;
    let items = stmt
        .query_map(
            params![
                level,
                elite,
                q,
                f.limit.unwrap_or(20).clamp(1, 100),
                f.offset.unwrap_or(0)
            ],
            summary,
        )?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(json!({"items":items,"total":total}))
}

pub fn export(
    conn: &Connection,
    f: &Filter,
    mut write: impl FnMut(Vec<u8>) -> Result<()>,
) -> Result<()> {
    let (level, elite, q) = f.bindings();
    let csv = f.format.as_deref() == Some("csv");
    let array = f.format.as_deref() == Some("json");
    let columns = if csv {
        "id,seed,a,b,score_a,score_b,turns,winner"
    } else {
        "payload"
    };
    let mut stmt = conn.prepare(&format!(
        "SELECT {columns} FROM games{WHERE} ORDER BY id ASC"
    ))?;
    let mut rows = stmt.query(params![level, elite, q])?;
    if csv {
        write(b"id,seed,level_a,level_b,score_a,score_b,turns,winner\n".to_vec())?;
    }
    let mut buffer = Vec::with_capacity(64 * 1024);
    let mut first = true;
    if array {
        buffer.push(b'[');
    }
    while let Some(row) = rows.next()? {
        if csv {
            use std::io::Write;
            let winner = row
                .get::<_, Option<u8>>(7)?
                .map(|n| n.to_string())
                .unwrap_or_default();
            writeln!(
                buffer,
                "{},{},{},{},{},{},{},{}",
                row.get::<_, u32>(0)?,
                row.get::<_, u32>(1)?,
                row.get::<_, u8>(2)?,
                row.get::<_, u8>(3)?,
                row.get::<_, i32>(4)?,
                row.get::<_, i32>(5)?,
                row.get::<_, u32>(6)?,
                winner
            )?;
        } else {
            if array && !first {
                buffer.push(b',');
            }
            buffer.extend(decode(row.get_ref(0)?.as_blob()?)?);
            if !array {
                buffer.push(b'\n');
            }
        }
        first = false;
        if buffer.len() >= 64 * 1024 {
            write(std::mem::replace(
                &mut buffer,
                Vec::with_capacity(64 * 1024),
            ))?;
        }
    }
    if array {
        buffer.push(b']');
    }
    if !buffer.is_empty() {
        write(buffer)?;
    }
    Ok(())
}
