use crate::engine::{BASE_EXPERT, ENGINE_VERSION, Engine, ExpertPolicy, LEVELS};
use anyhow::{Result, anyhow};
use rand::{RngExt, SeedableRng};
use rand_xoshiro::Xoshiro256PlusPlus;
use serde::Serialize;
use serde_json::json;
use std::{fs, path::Path, thread};
use wolges::game_state::GameState;

const TRAIN_SEED: u32 = 510_000_000;
const TEST_SEED: u32 = 810_000_000;
const TRAIN_PAIRS: u32 = 1500;
const TEST_PAIRS: u32 = 6000;
const CANDIDATES: usize = 20;

#[derive(Default, Clone, Copy)]
struct Outcome {
    wins: u32,
    draws: u32,
    games: u32,
    margin: i64,
    pair_squared: f64,
}
impl Outcome {
    fn add(&mut self, other: Self) {
        self.wins += other.wins;
        self.draws += other.draws;
        self.games += other.games;
        self.margin += other.margin;
        self.pair_squared += other.pair_squared;
    }
    fn rate(self) -> f64 {
        (f64::from(self.wins) + f64::from(self.draws) * 0.5) / f64::from(self.games)
    }
    fn summary(self) -> serde_json::Value {
        let rate = self.rate();
        let pairs = f64::from(self.games / 2);
        let se = ((self.pair_squared / pairs - rate * rate).max(0.0) / (pairs - 1.0)).sqrt();
        json!({
            "games":self.games,"paired_deals":self.games/2,"wins":self.wins,"draws":self.draws,
            "losses":self.games-self.wins-self.draws,"match_score_rate":rate,
            "confidence_interval_95":[(rate-1.96*se).max(0.0),(rate+1.96*se).min(1.0)],
            "elo_delta":(400.0*(rate/(1.0-rate)).log10()).round(),
            "mean_margin":self.margin as f64/f64::from(self.games)
        })
    }
}

fn compare(
    engine: &Engine,
    policy: ExpertPolicy,
    first_seed: u32,
    pairs: u32,
    workers: u32,
) -> Result<Outcome> {
    thread::scope(|scope| {
        let handles: Vec<_> = (0..workers)
            .map(|worker| {
                scope.spawn(move || -> Result<Outcome> {
                    let mut generator = engine.generator();
                    let mut state = GameState::new(&engine.config);
                    let mut result = Outcome::default();
                    for pair in (worker..pairs).step_by(workers as usize) {
                        let seed = first_seed + pair;
                        let mut match_points = 0.0;
                        for seat in 0..=1 {
                            let mut policies = [BASE_EXPERT; 2];
                            policies[seat] = policy;
                            let game = engine.play_seeded(
                                &mut generator,
                                &mut state,
                                1 + pair * 2 + seat as u32,
                                seed,
                                [3, 3],
                                policies,
                            )?;
                            result.wins += u32::from(game.winner == Some(seat as u8));
                            result.draws += u32::from(game.winner.is_none());
                            result.margin += i64::from(game.scores[seat] - game.scores[1 - seat]);
                            result.games += 1;
                            match_points += if game.winner == Some(seat as u8) {
                                1.0
                            } else if game.winner.is_none() {
                                0.5
                            } else {
                                0.0
                            };
                        }
                        result.pair_squared += (match_points / 2.0_f64).powi(2);
                    }
                    Ok(result)
                })
            })
            .collect();
        let mut result = Outcome::default();
        for worker in handles {
            result.add(
                worker
                    .join()
                    .map_err(|_| anyhow!("worker d’entraînement interrompu"))??,
            );
        }
        Ok(result)
    })
}

#[derive(Serialize)]
struct Candidate {
    policy: ExpertPolicy,
    evaluation: serde_json::Value,
}

pub fn train(engine: &Engine, output: &Path) -> Result<()> {
    let workers = std::thread::available_parallelism()?
        .get()
        .saturating_sub(2)
        .clamp(1, 12) as u32;
    let mut rng = Xoshiro256PlusPlus::seed_from_u64(381592);
    let mut best = (0.5, BASE_EXPERT);
    let mut trials = Vec::new();
    for i in 0..CANDIDATES {
        let policy = if i == 0 {
            ExpertPolicy {
                leave_scale: 150,
                blank_weight: 53,
                s_weight: 20,
                turnover: 4,
                end_penalty: 4,
                width: 24,
            }
        } else {
            ExpertPolicy {
                leave_scale: rng.random_range(60..=180),
                blank_weight: rng.random_range(20..=78),
                s_weight: rng.random_range(6..=34),
                turnover: rng.random_range(-2..=8),
                end_penalty: rng.random_range(2..=8),
                width: rng.random_range(10..=35),
            }
        };
        let result = compare(engine, policy, TRAIN_SEED, TRAIN_PAIRS, workers)?;
        if result.rate() > best.0 {
            best = (result.rate(), policy);
        }
        eprintln!(
            "Politique {}/{} · {:.2} %",
            i + 1,
            CANDIDATES,
            result.rate() * 100.0
        );
        trials.push(Candidate {
            policy,
            evaluation: result.summary(),
        });
    }
    let heldout = compare(engine, best.1, TEST_SEED, TEST_PAIRS, workers)?;
    let heldout_stats = heldout.summary();
    let promoted = heldout_stats["confidence_interval_95"][0]
        .as_f64()
        .is_some_and(|n| n > 0.5);
    let published = if promoted { best.1 } else { BASE_EXPERT };
    let result = json!({
        "schema":"lettrine.bot-policy.v1","lexicon":engine.lexicon,"lexicon_sha256":engine.digest,
        "engine":ENGINE_VERSION,"method":"Sélection par duels auto-joués, deux sièges par graine. Évaluation indépendante sur graines réservées.",
        "levels":[
            {"id":0,"label":LEVELS[0],"policy":"sample_rank_5_to_15_with_vocabulary"},
            {"id":1,"label":LEVELS[1],"policy":"sample_rank_2_to_7_with_vocabulary"},
            {"id":2,"label":LEVELS[2],"policy":"sample_top_three"},
            {"id":3,"label":LEVELS[3],"policy":"trained_rack_and_points","weights":published}
        ],
        "baseline":BASE_EXPERT,"selected":best.1,"promoted":promoted,"training_first_seed":TRAIN_SEED,"test_first_seed":TEST_SEED,
        "training_games":CANDIDATES as u32*TRAIN_PAIRS*2,"trials":trials,"holdout":heldout_stats
    });
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(output, serde_json::to_vec_pretty(&result)?)?;
    println!("{}", serde_json::to_string_pretty(&heldout_stats)?);
    Ok(())
}
