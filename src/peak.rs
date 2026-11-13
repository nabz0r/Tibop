use crate::engine::{Engine, ExpertPolicy};
use anyhow::{Context, Result, anyhow, ensure};
use rand::{SeedableRng, seq::SliceRandom};
use rand_xoshiro::Xoshiro256PlusPlus;
use serde::{Deserialize, Serialize};
use std::thread;
use wolges::{
    anagrams::ArmTables,
    endgame::{EndgameSolver, Limits},
    equity,
    game_state::{CheckGameEnded, GameState},
    kwg,
    movegen::{KurniaMoveGenerator, Play},
};

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct PeakConfig {
    pub candidates: usize,
    pub samples: usize,
    pub rollout_weight: f64,
    pub endgame_max_tiles: usize,
    pub endgame_max_states: usize,
}

pub const DEFAULT_CONFIG: PeakConfig = PeakConfig {
    candidates: 5,
    samples: 16,
    rollout_weight: 0.70,
    endgame_max_tiles: 14,
    endgame_max_states: 18_000,
};

pub fn promoted_config(
    engine: &Engine,
    expert: ExpertPolicy,
    report: &serde_json::Value,
) -> Option<PeakConfig> {
    promoted_config_for(&engine.profile.id, &engine.digest, expert, report)
}

pub fn promoted_config_for(
    profile: &str,
    digest: &str,
    expert: ExpertPolicy,
    report: &serde_json::Value,
) -> Option<PeakConfig> {
    (report["schema"] == "lettrine.peak-benchmark.v1"
        && report["promoted"] == true
        && report["profile"] == profile
        && report["lexicon_sha256"] == digest
        && serde_json::from_value::<ExpertPolicy>(report["reference"].clone()).ok() == Some(expert))
    .then(|| serde_json::from_value::<PeakConfig>(report["config"].clone()).ok())
    .flatten()
}

pub struct Decision {
    pub play: Play,
    pub exact_endgame: bool,
    pub sampled: bool,
}

pub struct PeakSearch {
    expert: ExpertPolicy,
    config: PeakConfig,
    generator: KurniaMoveGenerator,
}

impl PeakSearch {
    pub fn new(engine: &Engine, expert: ExpertPolicy, config: PeakConfig) -> Result<Self> {
        ensure!(
            (1..=12).contains(&config.candidates),
            "nombre de candidats hors limites"
        );
        ensure!(
            (1..=32).contains(&config.samples),
            "nombre de répartitions hors limites"
        );
        ensure!(
            (0.0..=1.0).contains(&config.rollout_weight),
            "poids des variantes hors limites"
        );
        ensure!(
            config.endgame_max_tiles <= 14 && config.endgame_max_states <= 250_000,
            "budget de fin de partie hors limites"
        );
        ensure!(
            (1..=256).contains(&expert.width),
            "largeur Expert hors limites"
        );
        Ok(Self {
            expert,
            config,
            generator: engine.generator(),
        })
    }

    pub fn choose(&mut self, engine: &Engine, state: &GameState, seed: u64) -> Result<Decision> {
        let mut rng = Xoshiro256PlusPlus::seed_from_u64(seed);
        let rack = &state.current_player().rack;
        let bag_len = state.bag.len();
        let (base, unseen) = public_position(engine, state)?;
        if bag_len == 0
            && rack.len() + unseen.len() <= self.config.endgame_max_tiles
            && self.config.endgame_max_states > 0
            && let Some(play) =
                exact_endgame(engine, &base, &unseen, self.config.endgame_max_states)?
        {
            return Ok(Decision {
                play,
                exact_endgame: true,
                sampled: false,
            });
        }
        engine.generate(
            &mut self.generator,
            &state.board_tiles,
            rack,
            self.expert.width + 2,
        );
        let expert = engine.choose(&self.generator, state, 3, self.expert, &mut rng);
        if self.config.rollout_weight == 0.0 || !matches!(expert, Play::Place { .. }) {
            return Ok(Decision {
                play: expert,
                exact_endgame: false,
                sampled: false,
            });
        }
        let mut ranked: Vec<_> = self
            .generator
            .plays
            .iter()
            .filter_map(|candidate| {
                matches!(candidate.play, Play::Place { .. }).then_some(&candidate.play)
            })
            .collect();
        ranked.sort_by_key(|p| std::cmp::Reverse(raw_points(p)));
        ranked.truncate(self.expert.width);
        ranked.sort_by_cached_key(|p| {
            std::cmp::Reverse(engine.evaluate(p, rack, bag_len, self.expert))
        });
        let candidates: Vec<_> = std::iter::once(expert.clone())
            .chain(ranked.into_iter().filter(|p| *p != &expert).cloned())
            .take(self.config.candidates)
            .collect();
        if candidates.len() <= 1 {
            return Ok(Decision {
                play: expert,
                exact_endgame: false,
                sampled: false,
            });
        }
        let player = usize::from(state.turn);
        let starting_margin = margin(&base, player);
        let samples = if bag_len == 0 { 1 } else { self.config.samples };
        let mut values = vec![0.0; candidates.len()];
        let mut draw = unseen.clone();
        for n in 0..samples {
            draw.copy_from_slice(&unseen);
            draw.shuffle(&mut rng);
            let mut scenario = base.clone();
            scenario.players[1 - player]
                .rack
                .extend_from_slice(&draw[bag_len..]);
            scenario.bag.set_from_iter(draw[..bag_len].iter().copied());
            let scenario_seed = seed.wrapping_add(n as u64).rotate_left(31);
            for (index, candidate) in candidates.iter().enumerate() {
                values[index] +=
                    self.rollout(engine, scenario.clone(), candidate, scenario_seed, player)?
                        - starting_margin;
            }
        }
        let weight = self.config.rollout_weight;
        let mut best = (f64::NEG_INFINITY, 0);
        for (i, play) in candidates.iter().enumerate() {
            let static_value = f64::from(engine.evaluate(play, rack, bag_len, self.expert)) / 4.0;
            let value = (1.0 - weight) * static_value + weight * values[i] / samples as f64;
            if value > best.0 {
                best = (value, i);
            }
        }
        Ok(Decision {
            play: candidates[best.1].clone(),
            exact_endgame: false,
            sampled: true,
        })
    }

    fn rollout(
        &mut self,
        engine: &Engine,
        mut state: GameState,
        first: &Play,
        seed: u64,
        player: usize,
    ) -> Result<f64> {
        let mut rng = Xoshiro256PlusPlus::seed_from_u64(seed);
        let mut play = first.clone();
        let mut leave = [0.0; 2];
        for depth in 0..3 {
            let seat = usize::from(state.turn);
            if depth != 0 {
                engine.generate(
                    &mut self.generator,
                    &state.board_tiles,
                    &state.current_player().rack,
                    self.expert.width + 2,
                );
                play = engine.choose(&self.generator, &state, 3, self.expert, &mut rng);
            }
            leave[seat] = f64::from(
                engine.evaluate(
                    &play,
                    &state.current_player().rack,
                    state.bag.len(),
                    self.expert,
                ) - 4 * raw_points(&play),
            ) / 4.0;
            state
                .play(&engine.config, &mut rng, &play)
                .map_err(|e| anyhow!("{e}"))?;
            if let Some(scores) = final_scores(engine, &state)? {
                return Ok(f64::from(scores[player] - scores[1 - player]));
            }
            state.next_turn();
        }
        Ok(margin(&state, player) + 0.55 * (leave[player] - leave[1 - player]))
    }
}

fn public_position(engine: &Engine, state: &GameState) -> Result<(GameState, Vec<u8>)> {
    ensure!(
        state.players.len() == 2,
        "la recherche avancée nécessite deux sièges"
    );
    let alphabet = engine.config.alphabet();
    let mut counts: Vec<i16> = (0..alphabet.len())
        .map(|i| i16::from(alphabet.freq(i)))
        .collect();
    for tile in state
        .board_tiles
        .iter()
        .copied()
        .filter(|&t| t != 0)
        .chain(state.current_player().rack.iter().copied())
    {
        let index = if tile & 128 != 0 {
            0
        } else {
            usize::from(tile)
        };
        let n = counts.get_mut(index).context("jeton hors alphabet")?;
        *n -= 1;
        ensure!(*n >= 0, "inventaire de jetons incohérent");
    }
    let unseen: Vec<_> = counts
        .into_iter()
        .enumerate()
        .flat_map(|(i, n)| std::iter::repeat_n(i as u8, n as usize))
        .collect();
    let unknown_rack = unseen
        .len()
        .checked_sub(state.bag.len())
        .context("sac incohérent")?;
    ensure!(
        unknown_rack <= usize::from(engine.config.rack_size()),
        "taille du chevalet adverse incohérente"
    );
    let mut public = GameState::new(&engine.config);
    public.turn = state.turn;
    public.zero_turns = state.zero_turns;
    public.pass_turns = state.pass_turns;
    public.board_tiles.copy_from_slice(&state.board_tiles);
    for (dest, src) in public.players.iter_mut().zip(&state.players) {
        dest.score = src.score;
        dest.num_exchanges = src.num_exchanges;
    }
    public.players[usize::from(state.turn)]
        .rack
        .clone_from(&state.current_player().rack);
    public.bag.set_from_iter(std::iter::empty());
    Ok((public, unseen))
}

fn exact_endgame(
    engine: &Engine,
    state: &GameState,
    opponent: &[u8],
    states: usize,
) -> Result<Option<Play>> {
    let snapshot = engine.snapshot(&state.board_tiles);
    let mut solver = EndgameSolver::<kwg::Node24, kwg::Node22, true>::new(
        &engine.config,
        snapshot.kwg,
        &ArmTables::None,
    )
    .map_err(|e| anyhow!("{e}"))?;
    let rack = &state.current_player().rack;
    let racks = if state.turn == 0 {
        [rack.as_slice(), opponent]
    } else {
        [opponent, rack.as_slice()]
    };
    solver.init_after_scoreless_turns(
        &state.board_tiles,
        racks,
        state.pass_turns,
        state.zero_turns,
    );
    let answer = solver
        .solve_within(
            state.turn,
            &Limits {
                time: None,
                states: Some(states),
                memory: None,
            },
        )
        .map_err(|e| anyhow!("{e}"))?;
    if !answer.exact {
        return Ok(None);
    }
    let mut line = Vec::new();
    solver.collect_pv(state.turn, &mut line);
    Ok(line.into_iter().next().map(|(_, play)| play))
}

fn raw_points(play: &Play) -> i32 {
    match play {
        Play::Place { score, .. } => equity::descale_score(*score),
        _ => 0,
    }
}

fn margin(state: &GameState, seat: usize) -> f64 {
    f64::from(equity::descale_score(
        state.players[seat].score - state.players[1 - seat].score,
    ))
}

fn final_scores(engine: &Engine, state: &GameState) -> Result<Option<[i32; 2]>> {
    let mut scores = [0; 2];
    let result = state
        .check_game_ended(&engine.config, &mut scores)
        .map_err(|e| anyhow!("{e}"))?;
    Ok((!matches!(result, CheckGameEnded::NotEnded)).then(|| scores.map(equity::descale_score)))
}

#[derive(Serialize)]
pub struct PeakBenchmark {
    pub schema: &'static str,
    pub profile: String,
    pub lexicon_sha256: String,
    pub reference: ExpertPolicy,
    pub config: PeakConfig,
    pub first_seed: u32,
    pub paired_deals: u32,
    pub games: u32,
    pub wins: u32,
    pub draws: u32,
    pub losses: u32,
    pub match_score_rate: f64,
    pub confidence_interval_95: [f64; 2],
    pub mean_margin: f64,
    pub elo_delta: f64,
    pub exact_endgame_moves: u64,
    pub sampled_moves: u64,
    pub elapsed_seconds: f64,
    pub promoted: bool,
}

#[derive(Default)]
struct Outcome {
    wins: u32,
    draws: u32,
    margin: i64,
    paired_squares: f64,
    exact: u64,
    sampled: u64,
}

impl Outcome {
    fn add(&mut self, other: Self) {
        self.wins += other.wins;
        self.draws += other.draws;
        self.margin += other.margin;
        self.paired_squares += other.paired_squares;
        self.exact += other.exact;
        self.sampled += other.sampled;
    }
}

pub fn benchmark(
    engine: &Engine,
    expert: ExpertPolicy,
    config: PeakConfig,
    first_seed: u32,
    paired_deals: u32,
) -> Result<PeakBenchmark> {
    ensure!(
        (2..=20_000).contains(&paired_deals),
        "nombre de tirages appariés entre 2 et 20 000"
    );
    ensure!(
        first_seed.checked_add(paired_deals - 1).is_some(),
        "intervalle de graines trop grand"
    );
    let workers = thread::available_parallelism()
        .map_or(1, |n| n.get().saturating_sub(2).clamp(1, 12)) as u32;
    let started = std::time::Instant::now();
    let result = thread::scope(|scope| -> Result<Outcome> {
        let handles: Vec<_> = (0..workers.min(paired_deals))
            .map(|worker| {
                scope.spawn(move || -> Result<Outcome> {
                    let mut state = GameState::new(&engine.config);
                    let mut engine_generator = engine.generator();
                    let mut peak = PeakSearch::new(engine, expert, config)?;
                    let mut outcome = Outcome::default();
                    for pair in (worker..paired_deals).step_by(workers as usize) {
                        let seed = first_seed + pair;
                        let mut pair_points = 0.0_f64;
                        for player in 0..2 {
                            let mut rng = Xoshiro256PlusPlus::seed_from_u64(u64::from(seed));
                            state.reset_and_draw_tiles(&engine.config, &mut rng);
                            let mut ending = None;
                            for turn in 1..=200 {
                                let play = if usize::from(state.turn) == player {
                                    let decision_seed = u64::from(seed) << 16 | turn;
                                    let decision = peak.choose(engine, &state, decision_seed)?;
                                    outcome.exact += u64::from(decision.exact_endgame);
                                    outcome.sampled += u64::from(decision.sampled);
                                    decision.play
                                } else {
                                    engine.generate(
                                        &mut engine_generator,
                                        &state.board_tiles,
                                        &state.current_player().rack,
                                        expert.width + 2,
                                    );
                                    engine.choose(&engine_generator, &state, 3, expert, &mut rng)
                                };
                                state
                                    .play(&engine.config, &mut rng, &play)
                                    .map_err(|e| anyhow!("{e}"))?;
                                if let Some(scores) = final_scores(engine, &state)? {
                                    ending = Some(scores);
                                    break;
                                }
                                state.next_turn();
                            }
                            let scores = ending
                                .with_context(|| format!("partie sans fin, graine {seed}"))?;
                            let margin = scores[player] - scores[1 - player];
                            outcome.margin += i64::from(margin);
                            outcome.wins += u32::from(margin > 0);
                            outcome.draws += u32::from(margin == 0);
                            pair_points += if margin > 0 {
                                1.0
                            } else if margin == 0 {
                                0.5
                            } else {
                                0.0
                            };
                        }
                        outcome.paired_squares += (pair_points * 0.5).powi(2);
                    }
                    Ok(outcome)
                })
            })
            .collect();
        let mut outcome = Outcome::default();
        for handle in handles {
            outcome.add(
                handle
                    .join()
                    .map_err(|_| anyhow!("worker de recherche interrompu"))??,
            );
        }
        Ok(outcome)
    })?;
    let games = paired_deals * 2;
    let score = f64::from(result.wins) + 0.5 * f64::from(result.draws);
    let rate = score / f64::from(games);
    let pairs = f64::from(paired_deals);
    let se = ((result.paired_squares / pairs - rate * rate).max(0.0) / (pairs - 1.0)).sqrt();
    let ci = [(rate - 1.96 * se).max(0.0), (rate + 1.96 * se).min(1.0)];
    let regularized = (score + 0.5) / f64::from(games + 1);
    Ok(PeakBenchmark {
        schema: "lettrine.peak-benchmark.v1",
        profile: engine.profile.id.clone(),
        lexicon_sha256: engine.digest.clone(),
        reference: expert,
        config,
        first_seed,
        paired_deals,
        games,
        wins: result.wins,
        draws: result.draws,
        losses: games - result.wins - result.draws,
        match_score_rate: rate,
        confidence_interval_95: ci,
        mean_margin: result.margin as f64 / f64::from(games),
        elo_delta: 400.0 * (regularized / (1.0 - regularized)).log10(),
        exact_endgame_moves: result.exact,
        sampled_moves: result.sampled,
        elapsed_seconds: started.elapsed().as_secs_f64(),
        promoted: paired_deals >= 200 && ci[0] > 0.5 && rate >= 0.51,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{engine::BASE_EXPERT, profiles};
    use std::path::Path;

    #[test]
    fn sampled_decision_ignores_the_actual_hidden_allocation() -> Result<()> {
        let engine = Engine::load(
            Path::new("data/fr/words.txt"),
            "Grammalecte 7.7".into(),
            profiles::load("fr-classic-v1")?,
        )?;
        let mut state = GameState::new(&engine.config);
        state.reset_and_draw_tiles(&engine.config, &mut Xoshiro256PlusPlus::seed_from_u64(2310));
        let mut search = PeakSearch::new(&engine, BASE_EXPERT, DEFAULT_CONFIG)?;
        let first = search.choose(&engine, &state, 17)?;
        assert!(first.sampled);
        let (_, mut unseen) = public_position(&engine, &state)?;
        unseen.reverse();
        let bag_len = state.bag.len();
        state.bag.set_from_iter(unseen[..bag_len].iter().copied());
        state.players[1].rack = unseen[bag_len..].to_vec();
        let second = search.choose(&engine, &state, 17)?;
        assert!(first.play == second.play);
        Ok(())
    }
}
