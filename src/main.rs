mod adaptive;
mod engine;
mod fisf_benchmark;
mod fisf_match;
mod import;
mod llm_benchmark;
mod metrics;
mod multiplayer;
mod multiplayer_game;
mod peak;
mod pg;
mod profiles;
mod review;
mod run;
mod server;
mod social;
mod social_google;
mod store;
mod telemetry;
mod timeline;
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
        "calibrate" => {
            let engine = load_engine()?;
            let conn = store::open(&db, false)?;
            let stats = store::get::<serde_json::Value>(&conn, "public")?.unwrap_or_default();
            let mut elo = store::elo_anchors(&stats);
            let expert = server::model_policy(&engine.digest);
            if expert != engine::BASE_EXPERT {
                elo[3] += 13;
            }
            let mut scale = adaptive::StrengthScale::new(elo, expert)?;
            if elo[0] > 700 {
                scale = scale.with_experimental_floor(700)?;
            }
            let pairs = env::args()
                .nth(2)
                .unwrap_or_else(|| "1500".into())
                .parse()?;
            let output = env::args().nth(3).unwrap_or_else(|| {
                format!("data/models/adaptive-{}-v1.json", engine.profile.language)
            });
            let report = adaptive::calibrate(
                &engine,
                &scale,
                &[
                    700.0,
                    f64::from(elo[0]),
                    900.0,
                    1050.0,
                    1200.0,
                    1350.0,
                    1500.0,
                    f64::from(elo[3]),
                ],
                2,
                931271800,
                pairs,
            )?;
            std::fs::write(output, serde_json::to_vec_pretty(&report)?)?;
        }
        "benchmark-peak" => {
            let engine = load_engine()?;
            let expert = server::model_policy(&engine.digest);
            let pairs = env::args()
                .nth(2)
                .unwrap_or_else(|| "1000".into())
                .parse()?;
            let output = env::args()
                .nth(3)
                .unwrap_or_else(|| "results/peak-test.json".into());
            let first_seed = env::args()
                .nth(4)
                .unwrap_or_else(|| "982900000".into())
                .parse()?;
            let report = peak::benchmark(&engine, expert, peak::DEFAULT_CONFIG, first_seed, pairs)?;
            std::fs::write(output, serde_json::to_vec_pretty(&report)?)?;
        }
        "benchmark-fisf" => {
            let args: Vec<_> = env::args_os().skip(2).collect();
            let [pairs, seed, report, games] = args.as_slice() else {
                bail!(
                    "usage : lettrine benchmark-fisf <paires> <graine> <rapport.json> <parties.jsonl>"
                );
            };
            let pairs = pairs.to_string_lossy().parse()?;
            let seed = seed.to_string_lossy().parse()?;
            let words = env::var_os("LETTRINE_WORDS")
                .ok_or_else(|| anyhow::anyhow!("indiquez le fichier LETTRINE_WORDS"))?;
            let name = env::var("LETTRINE_LEXICON")?;
            if name.trim().is_empty() {
                bail!("indiquez le nom et l’édition du lexique dans LETTRINE_LEXICON");
            }
            let reference = env::var_os("LETTRINE_REFERENCE")
                .ok_or_else(|| anyhow::anyhow!("indiquez le programme LETTRINE_REFERENCE"))?;
            let engine = Engine::load(
                &PathBuf::from(words),
                name,
                profiles::load("fr-classic-v1")?,
            )?;
            fisf_benchmark::run(
                &engine,
                &PathBuf::from(reference),
                pairs,
                seed,
                &PathBuf::from(report),
                &PathBuf::from(games),
            )?;
        }
        "benchmark-reference" => {
            let engine = load_engine()?;
            let pairs = env::args()
                .nth(2)
                .unwrap_or_else(|| "50000".into())
                .parse()?;
            let output = env::args()
                .nth(3)
                .unwrap_or_else(|| "data/models/reference-holdout-fr.json".into());
            train::reference(
                &engine,
                server::model_policy(&engine.digest),
                pairs,
                &PathBuf::from(output),
            )?;
        }
        "social" => {
            let engine = load_engine()?;
            let address =
                env::var("LETTRINE_SOCIAL_BIND").unwrap_or_else(|_| "127.0.0.1:8790".into());
            let db = PathBuf::from(
                env::var("LETTRINE_SOCIAL_DB").unwrap_or_else(|_| "results/social.sqlite".into()),
            );
            let en_db = env::var_os("LETTRINE_SOCIAL_EN_DB")
                .map(PathBuf::from)
                .unwrap_or_else(|| {
                    let mut filename = db.as_os_str().to_owned();
                    filename.push(".en");
                    PathBuf::from(filename)
                });
            let en_profile = profiles::load("en-wespa-v1")?;
            let en_path = en_profile
                .lexicon
                .path
                .clone()
                .ok_or_else(|| anyhow::anyhow!("lexique social anglais indisponible"))?;
            let en_engine = Engine::load(&en_path, en_profile.lexicon.name.clone(), en_profile)?;
            social::serve(&db, engine, &en_db, en_engine, &address).await?;
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
                "lettrine profiles | run | train [model.json] | benchmark-llm [tirages] | benchmark-peak [tirages] [rapport.json] [graine] | benchmark-fisf <paires> <graine> <rapport.json> <parties.jsonl> | serve | social | stats | export [jsonl|csv|json]"
            )
        }
        _ => bail!("commande inconnue : {command}"),
    }
    Ok(())
}
