mod engine;
mod import;
mod llm_benchmark;
mod metrics;
mod pg;
mod profiles;
mod review;
mod run;
mod server;
mod store;
mod train;

use anyhow::{Result, bail};
use engine::Engine;
use std::{env, path::PathBuf};

#[tokio::main]
async fn main() -> Result<()> {
    let command = env::args().nth(1).unwrap_or_else(|| "help".to_string());
    let db =
        PathBuf::from(env::var("LETTRINE_DB").unwrap_or_else(|_| "results/campaign.sqlite".into()));
    let load_engine = || -> Result<Engine> {
        let profile_id = env::var("LETTRINE_PROFILE").unwrap_or_else(|_| "fr-classic-v1".into());
        let profile = profiles::load(&profile_id)?;
        let path = env::var_os("LETTRINE_WORDS")
            .map(PathBuf::from)
            .or_else(|| profile.lexicon.path.clone())
            .ok_or_else(|| anyhow::anyhow!("indiquez LETTRINE_WORDS pour ce profil"))?;
        let name = env::var("LETTRINE_LEXICON").unwrap_or_else(|_| profile.lexicon.name.clone());
        Engine::load(&path, name, profile)
    };
    match command.as_str() {
        "profiles" => println!("{}", serde_json::to_string_pretty(&profiles::list()?)?),
        "probe" => {
            let url = env::args()
                .nth(2)
                .unwrap_or_else(|| "http://127.0.0.1:8787/health".into());
            reqwest::Client::new()
                .get(url)
                .send()
                .await?
                .error_for_status()?;
        }
        "run" => {
            let engine = load_engine()?;
            let total = env::var("LETTRINE_GAMES")
                .unwrap_or_else(|_| "100000".into())
                .parse()?;
            let seed = env::var("LETTRINE_SEED")
                .unwrap_or_else(|_| "20261112".into())
                .parse()?;
            let workers = env::var("LETTRINE_WORKERS")
                .unwrap_or_else(|_| {
                    std::thread::available_parallelism()
                        .map(|n| n.get().saturating_sub(2).max(1))
                        .unwrap_or(4)
                        .to_string()
                })
                .parse()?;
            run::run(&db, &engine, total, seed, workers)?;
        }
        "train" => {
            let engine = load_engine()?;
            let output = PathBuf::from(
                env::args()
                    .nth(2)
                    .unwrap_or_else(|| "data/models/fr-bots-v1.json".into()),
            );
            if engine.profile.language != "fr" {
                bail!("l’entraînement v1 est calibré pour le français");
            }
            train::train(&engine, &output)?;
        }
        "benchmark-llm" => {
            let engine = load_engine()?;
            if engine.profile.language != "fr" {
                bail!("le banc Llama v1 est en français");
            }
            let pairs = env::args().nth(2).unwrap_or_else(|| "6".into()).parse()?;
            let output = PathBuf::from(
                env::args()
                    .nth(3)
                    .unwrap_or_else(|| "data/models/llm-benchmarks.json".into()),
            );
            llm_benchmark::benchmark(
                &engine,
                server::model_policy(&engine.digest),
                &output,
                pairs,
            )
            .await?;
        }
        "serve" => {
            let engine = load_engine()?;
            let address = env::var("LETTRINE_BIND").unwrap_or_else(|_| "127.0.0.1:8787".into());
            let web = PathBuf::from(env::var("LETTRINE_WEB").unwrap_or_else(|_| "web".into()));
            server::serve(db, engine, address, web).await?;
        }
        "export" => {
            use std::io::Write;
            let conn = store::open(&db, false)?;
            let filter = store::Filter {
                format: env::args().nth(2),
                level: env::var("LETTRINE_LEVEL")
                    .ok()
                    .map(|s| s.parse())
                    .transpose()?,
                elite: env::var("LETTRINE_ELITE")
                    .ok()
                    .map(|s| s.parse())
                    .transpose()?,
                q: env::var("LETTRINE_QUERY").ok(),
                ..Default::default()
            };
            let mut out = std::io::stdout().lock();
            store::export(&conn, &filter, |bytes| Ok(out.write_all(&bytes)?))?;
        }
        "stats" => {
            let conn = store::open(&db, false)?;
            let stats = store::get::<serde_json::Value>(&conn, "public")?.unwrap_or_default();
            println!("{}", serde_json::to_string_pretty(&stats)?);
        }
        "help" | "-h" | "--help" => {
            println!(
                "lettrine profiles | run | train [model.json] | benchmark-llm [tirages] | serve | stats | export [jsonl|csv|json]"
            )
        }
        _ => bail!("commande inconnue : {command}"),
    }
    Ok(())
}
