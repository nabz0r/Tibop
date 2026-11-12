use axum::Json;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    env,
    fs::File,
    io::Read,
    path::PathBuf,
    sync::{Mutex, OnceLock},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const SERVICE_IDS: [&str; 8] = [
    "dashboard",
    "api",
    "postgres",
    "pgvector",
    "redis",
    "worker",
    "beat",
    "ollama",
];
const USAGE_KEY: &str = "lettrine:ollama:usage";
const REDIS_DEADLINE: Duration = Duration::from_millis(1500);
const HOST_STALE_MS: u64 = 35_000;

#[derive(Default, Deserialize, Serialize)]
struct HostMetrics {
    #[serde(skip_serializing_if = "Option::is_none")]
    cpu_percent: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    memory_used_mb: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    memory_total_mb: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    battery_percent: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    charging: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    power_source: Option<String>,
}

#[derive(Deserialize)]
struct HostSnapshot {
    updated_at: u64,
    host: HostMetrics,
    services: Vec<ContainerSample>,
}

#[derive(Deserialize)]
struct ContainerSample {
    id: String,
    status: String,
    cpu_percent: Option<f64>,
    memory_mb: Option<f64>,
}

#[derive(Serialize)]
struct Service {
    id: &'static str,
    status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    cpu_percent: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    memory_mb: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    detail: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    row_count: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    queued_jobs: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    heartbeat_ttl_seconds: Option<u64>,
}

impl Service {
    fn new(id: &'static str, sample: Option<&ContainerSample>) -> Self {
        Self {
            id,
            status: sample.map_or("unknown", |s| match s.status.as_str() {
                "healthy" => "healthy",
                "unhealthy" => "unhealthy",
                "running" => "running",
                "stopped" => "stopped",
                "starting" => "starting",
                _ => "unknown",
            }),
            cpu_percent: sample.and_then(|s| s.cpu_percent),
            memory_mb: sample.and_then(|s| s.memory_mb),
            detail: None,
            version: None,
            row_count: None,
            queued_jobs: None,
            heartbeat_ttl_seconds: None,
        }
    }
}

#[derive(Default)]
struct Usage {
    prompt_tokens: Option<u64>,
    output_tokens: Option<u64>,
    requests: u64,
}

impl Usage {
    fn add(&mut self, prompt: Option<u64>, output: Option<u64>, requests: u64) {
        if let Some(prompt) = prompt {
            self.prompt_tokens = Some(self.prompt_tokens.unwrap_or(0).saturating_add(prompt));
        }
        if let Some(output) = output {
            self.output_tokens = Some(self.output_tokens.unwrap_or(0).saturating_add(output));
        }
        self.requests = self.requests.saturating_add(requests);
    }
}

static LOCAL_USAGE: Mutex<Usage> = Mutex::new(Usage {
    prompt_tokens: None,
    output_tokens: None,
    requests: 0,
});

fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn host_snapshot() -> Option<HostSnapshot> {
    let path = env::var_os("LETTRINE_HOST_METRICS")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("results/host-metrics.json"));
    let file = File::open(path).ok()?;
    let mut bytes = Vec::new();
    file.take(32_768).read_to_end(&mut bytes).ok()?;
    serde_json::from_slice(&bytes).ok()
}

struct PostgresDetails {
    server_version: String,
    vector_version: Option<String>,
    vector_rows: Option<i64>,
}

async fn postgres_probe() -> (bool, Option<PostgresDetails>) {
    let enabled =
        env::var_os("LETTRINE_DATABASE_URL").is_some() || env::var_os("LETTRINE_PG_HOST").is_some();
    if !enabled {
        return (false, None);
    }
    let query = tokio::task::spawn_blocking(|| -> anyhow::Result<Option<PostgresDetails>> {
        let Some(mut conn) = crate::pg::connect()? else {
            return Ok(None);
        };
        conn.batch_execute("SET statement_timeout TO '1500ms'")?;
        let info = conn.query_one(
            "SELECT current_setting('server_version'),
             (SELECT extversion FROM pg_extension WHERE extname = 'vector'),
             to_regclass('lab_vectors') IS NOT NULL",
            &[],
        )?;
        let vector_rows = if info.get::<_, bool>(2) {
            conn.query_one("SELECT count(*) FROM lab_vectors", &[])
                .ok()
                .map(|row| row.get(0))
        } else {
            None
        };
        Ok(Some(PostgresDetails {
            server_version: info.get(0),
            vector_version: info.get(1),
            vector_rows,
        }))
    });
    let result = tokio::time::timeout(Duration::from_secs(4), query)
        .await
        .ok()
        .and_then(Result::ok)
        .and_then(Result::ok)
        .flatten();
    (true, result)
}

struct RedisDetails {
    worker_ttl: i64,
    beat_ttl: i64,
    queued_jobs: u64,
    usage: Usage,
}

fn redis_url() -> Option<String> {
    env::var("LETTRINE_REDIS_URL")
        .ok()
        .or_else(|| env::var("REDIS_URL").ok())
}

async fn redis_probe() -> (bool, Option<RedisDetails>) {
    let Some(url) = redis_url() else {
        return (false, None);
    };
    let result = tokio::time::timeout(REDIS_DEADLINE, async {
        let client = redis::Client::open(url).ok()?;
        let mut conn = client.get_multiplexed_async_connection().await.ok()?;
        type Reply = (String, i64, i64, u64, Vec<Option<u64>>);
        let (ping, worker_ttl, beat_ttl, queued_jobs, usage): Reply = redis::pipe()
            .cmd("PING")
            .cmd("TTL")
            .arg("lettrine:worker:heartbeat")
            .cmd("TTL")
            .arg("lettrine:beat:heartbeat")
            .cmd("XLEN")
            .arg("lettrine:jobs")
            .cmd("HMGET")
            .arg(USAGE_KEY)
            .arg(&["prompt_tokens", "output_tokens", "requests"])
            .query_async(&mut conn)
            .await
            .ok()?;
        (ping == "PONG").then(|| RedisDetails {
            worker_ttl,
            beat_ttl,
            queued_jobs,
            usage: Usage {
                prompt_tokens: usage.first().copied().flatten(),
                output_tokens: usage.get(1).copied().flatten(),
                requests: usage.get(2).copied().flatten().unwrap_or(0),
            },
        })
    })
    .await
    .ok()
    .flatten();
    (true, result)
}

pub async fn record_ollama_usage(answer: &Value) {
    let prompt = answer["prompt_eval_count"].as_u64();
    let output = answer["eval_count"].as_u64();
    if prompt.is_none() && output.is_none() {
        return;
    }
    if let Some(url) = redis_url() {
        let _ = tokio::time::timeout(REDIS_DEADLINE, async {
            let client = redis::Client::open(url)?;
            let mut conn = client.get_multiplexed_async_connection().await?;
            let mut pipe = redis::pipe();
            pipe.atomic()
                .cmd("HINCRBY")
                .arg(USAGE_KEY)
                .arg("requests")
                .arg(1)
                .ignore();
            if let Some(tokens) = prompt {
                pipe.cmd("HINCRBY")
                    .arg(USAGE_KEY)
                    .arg("prompt_tokens")
                    .arg(tokens)
                    .ignore();
            }
            if let Some(tokens) = output {
                pipe.cmd("HINCRBY")
                    .arg(USAGE_KEY)
                    .arg("output_tokens")
                    .arg(tokens)
                    .ignore();
            }
            pipe.query_async::<()>(&mut conn).await
        })
        .await;
        // After an ambiguous network failure Redis may already have committed.
        return;
    }
    if let Ok(mut usage) = LOCAL_USAGE.lock() {
        usage.add(prompt, output, 1);
    }
}

#[derive(Serialize)]
struct Model {
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    size_mb: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    vram_mb: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    context_length: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    expires_at: Option<String>,
}

async fn ollama_probe() -> (bool, Option<Vec<Model>>) {
    let Ok(base) = env::var("LETTRINE_OLLAMA_URL") else {
        return (false, None);
    };
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    let client = CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(Duration::from_secs(2))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .expect("configuration HTTP statique")
    });
    let result = async {
        let response: Value = client
            .get(format!("{}/api/ps", base.trim_end_matches('/')))
            .send()
            .await
            .ok()?
            .error_for_status()
            .ok()?
            .json()
            .await
            .ok()?;
        let models = response["models"]
            .as_array()?
            .iter()
            .filter_map(|value| {
                let to_mb = |n: &Value| n.as_u64().map(|bytes| bytes as f64 / 1_048_576.0);
                Some(Model {
                    name: value["name"]
                        .as_str()
                        .or_else(|| value["model"].as_str())?
                        .to_owned(),
                    size_mb: to_mb(&value["size"]),
                    vram_mb: to_mb(&value["size_vram"]),
                    context_length: value["context_length"].as_u64(),
                    expires_at: value["expires_at"].as_str().map(str::to_owned),
                })
            })
            .collect();
        Some(models)
    }
    .await;
    (true, result)
}

pub async fn snapshot() -> Json<Value> {
    let (postgres, redis, ollama) = tokio::join!(postgres_probe(), redis_probe(), ollama_probe());
    let now = unix_ms();
    let sampler = host_snapshot();
    let fresh = sampler.as_ref().filter(|s| {
        s.updated_at <= now.saturating_add(1000)
            && now.saturating_sub(s.updated_at) <= HOST_STALE_MS
    });
    let services: Vec<_> = SERVICE_IDS
        .into_iter()
        .map(|id| {
            let sampled = fresh.and_then(|x| x.services.iter().find(|s| s.id == id));
            let mut service = Service::new(id, sampled);
            match id {
                "api" => service.status = "healthy",
                "postgres" | "pgvector" if postgres.0 => {
                    if let Some(info) = postgres.1.as_ref() {
                        if id == "postgres" {
                            service.status = "healthy";
                            service.version = Some(info.server_version.clone());
                            service.detail = service.version.clone();
                        } else if let Some(version) = info.vector_version.as_ref() {
                            service.status = "healthy";
                            service.version = Some(version.clone());
                            service.row_count = info.vector_rows;
                            service.detail = Some(match service.row_count {
                                Some(rows) => format!("{version} · {rows} entrées"),
                                None => version.clone(),
                            });
                        } else {
                            service.status = "unavailable";
                        }
                    } else {
                        service.status = "unavailable";
                    }
                }
                "redis" | "worker" | "beat" if redis.0 => {
                    if let Some(info) = redis.1.as_ref() {
                        if id == "redis" {
                            service.status = "healthy";
                            service.queued_jobs = Some(info.queued_jobs);
                            service.detail =
                                Some(format!("{} tâches dans la file", info.queued_jobs));
                        } else {
                            let ttl = if id == "worker" {
                                info.worker_ttl
                            } else {
                                info.beat_ttl
                            };
                            if ttl > 0 {
                                service.status = "healthy";
                                service.heartbeat_ttl_seconds = Some(ttl as u64);
                                service.detail =
                                    Some(format!("Signal Redis valide · {ttl} s restantes"));
                            } else {
                                service.status = "unavailable";
                                service.detail = Some("Signal Redis absent".into());
                            }
                        }
                    } else if id == "redis" {
                        service.status = "unavailable";
                    }
                }
                "ollama" if ollama.0 => {
                    if let Some(models) = ollama.1.as_ref() {
                        service.status = "healthy";
                        service.detail = Some(format!("{} modèle(s) chargé(s)", models.len()));
                    } else {
                        service.status = "unavailable";
                    }
                }
                _ => {}
            }
            service
        })
        .collect();
    let mut usage = Usage::default();
    if let Some(info) = redis.1 {
        usage.add(
            info.usage.prompt_tokens,
            info.usage.output_tokens,
            info.usage.requests,
        );
    }
    if let Ok(local) = LOCAL_USAGE.lock() {
        usage.add(local.prompt_tokens, local.output_tokens, local.requests);
    }
    let mut ollama = json!({"models": ollama.1.unwrap_or_default()});
    if usage.requests > 0 {
        ollama["requests"] = json!(usage.requests);
        ollama["token_scope"] = json!("studio_recorded");
    }
    if let Some(value) = usage.prompt_tokens {
        ollama["prompt_tokens"] = json!(value);
    }
    if let Some(value) = usage.output_tokens {
        ollama["output_tokens"] = json!(value);
    }
    Json(json!({
        "services": services,
        "host": fresh.map_or_else(|| json!({}), |s| json!(s.host)),
        "host_sampled_at": sampler.as_ref().map(|s| s.updated_at),
        "host_stale": sampler.is_some() && fresh.is_none(),
        "ollama": ollama,
        "updated_at": now,
    }))
}
