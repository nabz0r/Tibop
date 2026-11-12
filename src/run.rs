use crate::{
    engine::{Engine, Game},
    store::{self, Campaign},
};
use anyhow::{Context, Result, anyhow, bail};
use std::{
    path::Path,
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc,
    },
    thread,
    time::Instant,
};
use wolges::game_state::GameState;

pub fn run(path: &Path, engine: &Engine, total: u32, seed: u32, workers: usize) -> Result<()> {
    if total == 0 || workers == 0 {
        bail!("nombre de parties et de workers strictement positifs");
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let lock_path = path.with_extension("sqlite.lock");
    let lock = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .open(lock_path)?;
    lock.try_lock()
        .context("une autre simulation utilise cette base")?;
    let mut conn = store::open(path, true)?;
    let campaign = Campaign::new(engine, total, seed);
    let mut metrics = store::setup(&mut conn, &campaign)?;
    let previous_time = metrics.elapsed_seconds;
    let start = Instant::now();
    let mut done = vec![false; total as usize];
    {
        let mut stmt = conn.prepare("SELECT id FROM games")?;
        for id in stmt.query_map([], |r| r.get::<_, u32>(0))? {
            done[(id? - 1) as usize] = true;
        }
    }
    let remaining: Vec<_> = (1..=total).filter(|id| !done[(id - 1) as usize]).collect();
    eprintln!(
        "{} · {} mots · {} parties à calculer · {workers} workers",
        engine.lexicon,
        engine.words,
        remaining.len()
    );
    let cursor = AtomicUsize::new(0);
    let cancelled = AtomicBool::new(false);
    let (tx, rx) = mpsc::sync_channel::<Result<(Game, Vec<u8>)>>(workers * 32);
    let result = thread::scope(|scope| -> Result<()> {
        for _ in 0..workers {
            let tx = tx.clone();
            let cursor = &cursor;
            let remaining = &remaining;
            let cancelled = &cancelled;
            scope.spawn(move || {
                let mut generator = engine.generator();
                let mut state = GameState::new(&engine.config);
                while !cancelled.load(Ordering::Relaxed) {
                    let Some(&id) = remaining.get(cursor.fetch_add(1, Ordering::Relaxed)) else {
                        break;
                    };
                    let result = engine
                        .simulate(&mut generator, &mut state, id, seed)
                        .and_then(|g| Ok((store::encode(&g)?, g)))
                        .map(|(payload, g)| (g, payload));
                    let failed = result.is_err();
                    if tx.send(result).is_err() || failed {
                        break;
                    }
                }
            });
        }
        drop(tx);
        let mut batch = Vec::with_capacity(256);
        let mut error = None;
        let mut next_notice = (metrics.completed / 5000 + 1) * 5000;
        for result in rx {
            match result {
                Ok(item) => {
                    metrics.add(&item.0);
                    batch.push(item);
                }
                Err(e) => {
                    cancelled.store(true, Ordering::Relaxed);
                    error = Some(e.to_string());
                }
            }
            if batch.len() == 256 {
                metrics.elapsed_seconds = previous_time + start.elapsed().as_secs_f64();
                metrics.sample();
                store::save(
                    &mut conn,
                    &campaign,
                    &metrics,
                    std::mem::take(&mut batch),
                    error.is_none(),
                    error.as_deref(),
                )?;
            }
            if metrics.completed >= next_notice {
                eprintln!(
                    "{}/{} · {:.1} s · Elo {:?}",
                    metrics.completed,
                    total,
                    start.elapsed().as_secs_f64(),
                    metrics.ratings()
                );
                next_notice += 5000;
            }
        }
        metrics.elapsed_seconds = previous_time + start.elapsed().as_secs_f64();
        metrics.sample();
        store::save(
            &mut conn,
            &campaign,
            &metrics,
            batch,
            false,
            error.as_deref(),
        )?;
        if let Some(error) = error {
            return Err(anyhow!(error));
        }
        if metrics.completed != total {
            bail!("campagne incomplète");
        }
        Ok(())
    });
    result.context("simulation")?;
    conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);")?;
    println!(
        "{} parties · {:.1} s · Elo {:?}",
        metrics.completed,
        metrics.elapsed_seconds,
        metrics.ratings()
    );
    Ok(())
}
