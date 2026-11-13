use crate::{
    engine::{Engine, ExpertPolicy, MoveKind, Tile},
    fisf_match::{ClockStatus, EndReason, MatchRules, STANDARD_CLOCK_MS, TurnKind},
    peak::{DEFAULT_CONFIG, PeakConfig, PeakSearch},
};
use anyhow::{Context, Result, anyhow, bail, ensure};
use rand::SeedableRng;
use rand_xoshiro::Xoshiro256PlusPlus;
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, OpenOptions},
    io::{BufRead, BufReader, BufWriter, Read, Write},
    path::Path,
    process::{Child, Command, Stdio},
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};
use wolges::{equity, game_state::GameState, movegen::Play, play_scorer::PlayScorer};

const PROTOCOL: &str = "wordrivet.macondo.v1";
const MAX_TURNS: u16 = 200;
const BOARD_WIDTH: usize = 15;
const STARTUP_DEADLINE: Duration = Duration::from_secs(30);
const RESPONSE_GRACE_MS: u64 = 5_000;

#[derive(Deserialize, Serialize)]
struct ReferenceIdentity {
    engine: String,
    policy: String,
    protocol: String,
}

#[derive(Serialize)]
struct Position {
    board: String,
    rack: String,
    exchange_allowed: bool,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ReferenceMove {
    #[serde(rename = "type")]
    kind: MoveKind,
    row: i8,
    col: i8,
    vertical: bool,
    tiles: String,
    score: i32,
}

struct Reference {
    child: Child,
    request: mpsc::Sender<Vec<u8>>,
    response: mpsc::Receiver<Result<String>>,
}

impl Reference {
    fn start(
        path: &Path,
        graph: Vec<u8>,
        startup_deadline: Duration,
    ) -> Result<(Self, ReferenceIdentity)> {
        let graph_len = u32::try_from(graph.len())?;
        let mut child = Command::new(path)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .with_context(|| format!("Lancement du moteur de référence {}", path.display()))?;
        let mut input = child.stdin.take().expect("entrée du processus");
        let mut output = BufReader::new(child.stdout.take().expect("sortie du processus"));
        let (request, requests) = mpsc::channel::<Vec<u8>>();
        let (responses, response) = mpsc::channel();
        let reference = Self {
            child,
            request,
            response,
        };
        thread::Builder::new()
            .name("macondo-ipc".into())
            .spawn(move || {
                let handshake = (|| -> Result<_> {
                    input.write_all(&graph_len.to_le_bytes())?;
                    input.write_all(&graph)?;
                    input.flush()?;
                    read_reference_line(&mut output)
                })();
                drop(graph);
                let ready = handshake.is_ok();
                if responses.send(handshake).is_err() || !ready {
                    return;
                }
                for request in requests {
                    let reply = (|| -> Result<_> {
                        input.write_all(&request)?;
                        input.flush()?;
                        read_reference_line(&mut output)
                    })();
                    let keep_running = reply.is_ok();
                    if responses.send(reply).is_err() || !keep_running {
                        break;
                    }
                }
            })?;
        let identity: ReferenceIdentity = reference.read_message(startup_deadline)?;
        ensure!(
            identity.protocol == PROTOCOL
                && identity.engine == "Macondo v0.13.9"
                && identity.policy == "max_score",
            "Identité ou protocole du moteur externe inattendu"
        );
        Ok((reference, identity))
    }

    fn read_message<T: DeserializeOwned>(&self, deadline: Duration) -> Result<T> {
        let line = self
            .response
            .recv_timeout(deadline)
            .map_err(|error| match error {
                mpsc::RecvTimeoutError::Timeout => {
                    anyhow!("Délai de réponse du moteur externe dépassé")
                }
                mpsc::RecvTimeoutError::Disconnected => {
                    anyhow!("Canal du moteur externe interrompu")
                }
            })??;
        serde_json::from_str(&line).context("Réponse invalide du moteur externe")
    }

    fn choose(
        &self,
        position: &Position,
        engine: &Engine,
        remaining_ms: u64,
    ) -> Result<(Play, i32)> {
        let mut request = serde_json::to_vec(position)?;
        request.push(b'\n');
        self.request
            .send(request)
            .map_err(|_| anyhow!("Canal du moteur externe interrompu"))?;
        let reply: ReferenceMove =
            self.read_message(Duration::from_millis(remaining_ms + RESPONSE_GRACE_MS))?;
        let play = match reply.kind {
            MoveKind::Play => {
                ensure!(
                    (2..=BOARD_WIDTH).contains(&reply.tiles.len())
                        && (0..BOARD_WIDTH as i8).contains(&reply.row)
                        && (0..BOARD_WIDTH as i8).contains(&reply.col),
                    "Placement Macondo mal formé"
                );
                let word = reply
                    .tiles
                    .bytes()
                    .map(|letter| match letter {
                        b'.' => Ok(0),
                        b'A'..=b'Z' => engine.symbol_id(char::from(letter)),
                        b'a'..=b'z' => {
                            Ok(engine.symbol_id(char::from(letter.to_ascii_uppercase()))? | 0x80)
                        }
                        _ => bail!("Lettre de placement Macondo invalide"),
                    })
                    .collect::<Result<Vec<_>>>()?;
                Play::Place {
                    down: reply.vertical,
                    lane: if reply.vertical { reply.col } else { reply.row },
                    idx: if reply.vertical { reply.row } else { reply.col },
                    word: word.as_slice().into(),
                    score: 0,
                }
            }
            MoveKind::Exchange => {
                ensure!(
                    position.exchange_allowed && (1..=7).contains(&reply.tiles.len()),
                    "Échange Macondo inadmissible"
                );
                let tiles = reply
                    .tiles
                    .chars()
                    .map(|tile| engine.symbol_id(tile))
                    .collect::<Result<Vec<_>>>()?;
                Play::Exchange {
                    tiles: tiles.as_slice().into(),
                }
            }
            MoveKind::Pass => {
                ensure!(reply.tiles.is_empty(), "Passe Macondo mal formée");
                pass()
            }
        };
        Ok((play, reply.score))
    }
}

impl Drop for Reference {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn read_reference_line(reader: &mut impl BufRead) -> Result<String> {
    let mut line = String::new();
    let bytes = reader.by_ref().take(4097).read_line(&mut line)?;
    ensure!(
        (1..=4096).contains(&bytes) && line.ends_with('\n'),
        "Réponse du moteur externe absente, tronquée ou trop longue"
    );
    Ok(line)
}

fn pass() -> Play {
    Play::Exchange {
        tiles: (&[][..]).into(),
    }
}

fn tile_string(engine: &Engine, tiles: &[u8], board: bool) -> String {
    tiles
        .iter()
        .map(|&tile| {
            if board && tile == 0 {
                '.'
            } else {
                let letter = engine.letter(tile);
                if tile & 0x80 == 0 {
                    letter
                } else {
                    letter.to_ascii_lowercase()
                }
            }
        })
        .collect()
}

#[derive(Serialize)]
struct LoggedTurn {
    turn: u16,
    seat: usize,
    #[serde(rename = "type")]
    kind: MoveKind,
    rack_before: String,
    bag_before: usize,
    rack_after: String,
    bag_after: usize,
    exchange_allowed: bool,
    no_legal_placement: bool,
    exchanged: String,
    tiles: Vec<Tile>,
    word: String,
    direction: Option<char>,
    words: Vec<String>,
    challenged_words: Vec<String>,
    challenger_penalty: i32,
    placement_cancelled: bool,
    score: i32,
    elapsed_ms: u64,
    clocks_ms: [u64; 2],
    timed_out: bool,
}

#[derive(Serialize)]
struct MatchLog {
    seed: u32,
    nab_seat: usize,
    end: EndReason,
    initial_racks: [String; 2],
    scores: [i32; 2],
    adjustments: [i32; 2],
    final_racks: [String; 2],
    turns: Vec<LoggedTurn>,
}

#[derive(Serialize)]
struct Report {
    schema: &'static str,
    simulation: bool,
    profile: String,
    lexicon: String,
    lexicon_sha256: String,
    normalized_forms: usize,
    reference: ReferenceIdentity,
    reference_binary_sha256: String,
    host_os: &'static str,
    host_arch: &'static str,
    host_logical_cpus: Option<usize>,
    nab_base: ExpertPolicy,
    nab_search: PeakConfig,
    initial_clock_ms: u64,
    clock_model: &'static str,
    reference_startup_deadline_ms: u64,
    reference_response_grace_ms: u64,
    challenge_policy: &'static str,
    first_seed: u32,
    paired_deals: u32,
    games: u32,
    nab_wins: u32,
    draws: u32,
    reference_wins: u32,
    challenged_words: usize,
    cancelled_placements: usize,
    timed_out_turns: usize,
    match_score_rate: f64,
    paired_confidence_interval_95: [f64; 2],
    interval_method: &'static str,
    mean_margin: f64,
    relative_elo: f64,
    elo_method: &'static str,
    elapsed_seconds: f64,
}

fn score_play(
    engine: &Engine,
    state: &GameState,
    scorer: &mut PlayScorer,
    candidate: Play,
    claimed_score: Option<i32>,
) -> Result<(Play, Vec<String>, Vec<String>, i32)> {
    let snapshot = engine.snapshot(&state.board_tiles);
    let mut play = scorer
        .validate_play(&snapshot, state, &candidate)
        .map_err(|error| anyhow!("Coup refusé par l’arbitre : {error}"))?
        .unwrap_or(candidate);
    let mut words = Vec::new();
    let mut invalid = Vec::new();
    let mut points = 0;
    if matches!(play, Play::Place { .. }) {
        scorer.find_invalid_words(&snapshot, &play, |word| {
            invalid.push(word.iter().map(|&tile| engine.letter(tile)).collect())
        });
        let computed = scorer.compute_score(&snapshot, &play);
        if let Play::Place { score, .. } = &mut play {
            *score = computed;
        }
        points = equity::descale_score(computed);
        scorer.words_all(&snapshot, &play, |word| {
            words.push(word.iter().map(|&tile| engine.letter(tile)).collect());
            true
        });
    }
    ensure!(
        claimed_score.is_none_or(|claimed| claimed == points),
        "Score Macondo incohérent : annoncé {}, recalculé {points}",
        claimed_score.unwrap_or_default()
    );
    Ok((play, words, invalid, points))
}

fn paired_interval(mean: f64, squares: f64, count: u32) -> [f64; 2] {
    let n = f64::from(count);
    let variance = ((squares - n * mean * mean) / (n - 1.0)).max(0.0);
    // Empirical Bernstein for independent seed pairs, whose point means lie in [0, 1].
    // Allocate half the error probability to each tail.
    let factor = 80.0_f64.ln();
    let radius = (2.0 * variance * factor / n).sqrt() + 7.0 * factor / (3.0 * (n - 1.0));
    [(mean - radius).max(0.0), (mean + radius).min(1.0)]
}

enum ChallengePolicy {
    SharedLexicon,
    #[cfg(test)]
    Scripted(&'static [usize]),
}

impl ChallengePolicy {
    fn selected(&self, words: &[String], invalid: &[String], allowed: bool) -> Vec<usize> {
        match self {
            Self::SharedLexicon if allowed && !invalid.is_empty() => words
                .iter()
                .enumerate()
                .filter_map(|(index, word)| invalid.contains(word).then_some(index))
                .collect(),
            Self::SharedLexicon => Vec::new(),
            #[cfg(test)]
            Self::Scripted(indices) => indices.to_vec(),
        }
    }
}

struct Match<'a> {
    engine: &'a Engine,
    state: GameState,
    rules: MatchRules,
    scorer: PlayScorer,
    rng: Xoshiro256PlusPlus,
    previous_blocked_pass: bool,
}

impl Match<'_> {
    fn execute_turn(
        &mut self,
        turn: u16,
        candidate: Play,
        claimed: Option<i32>,
        elapsed_ms: u64,
        challenge: ChallengePolicy,
    ) -> Result<(LoggedTurn, Option<EndReason>)> {
        let engine = self.engine;
        let state = &mut self.state;
        let rules = &mut self.rules;
        let seat = usize::from(state.turn);
        let bag_before = state.bag.len();
        let rack_before = tile_string(engine, &state.current_player().rack, false);
        let exchange_allowed = rules.may_exchange(seat, bag_before);
        let clock = rules.charge(seat, elapsed_ms);
        let timed_out = clock != ClockStatus::Running;
        let (candidate, claimed) = if timed_out {
            (pass(), None)
        } else {
            (candidate, claimed)
        };
        if let Play::Exchange { tiles } = &candidate {
            ensure!(
                tiles.is_empty() || exchange_allowed,
                "Échange interdit par les règles du match"
            );
        }
        let (mut play, words, invalid, mut score) =
            score_play(engine, state, &mut self.scorer, candidate, claimed)?;
        let (word, tiles, direction) = engine.describe(&state.board_tiles, &play);
        let mut challenged_words = Vec::new();
        let mut challenger_penalty = 0;
        let mut placement_cancelled = false;
        if matches!(&play, Play::Place { .. }) {
            let finishing = bag_before == 0 && tiles.len() == state.current_player().rack.len();
            let selected =
                challenge.selected(&words, &invalid, rules.can_challenge(1 - seat, finishing));
            let outcome = rules
                .resolve_challenge(1 - seat, &words, &selected, finishing, |word| {
                    !invalid.iter().any(|bad| bad == word)
                })
                .map_err(|error| anyhow!("{error}"))?;
            challenged_words.extend(selected.iter().map(|&index| words[index].clone()));
            challenger_penalty = outcome.challenger_penalty;
            placement_cancelled = outcome.placement_cancelled;
            state.players[1 - seat].score -= equity::SCALE * challenger_penalty;
            if placement_cancelled {
                play = pass();
                score = 0;
            }
        }
        let (kind, rule_kind, exchanged) = match &play {
            Play::Place { .. } => (MoveKind::Play, TurnKind::Placement, String::new()),
            Play::Exchange { tiles } if tiles.is_empty() => {
                (MoveKind::Pass, TurnKind::Pass, String::new())
            }
            Play::Exchange { tiles } => (
                MoveKind::Exchange,
                TurnKind::Exchange,
                tile_string(engine, tiles, false),
            ),
        };
        if clock != ClockStatus::BothExpired {
            state
                .play(&engine.config, &mut self.rng, &play)
                .map_err(|error| anyhow!("{error}"))?;
        }
        let six_passes = rules.record_turn(rule_kind);
        let rack_after = tile_string(engine, &state.current_player().rack, false);
        let bag_after = state.bag.len();
        let opponent_expired = rules.remaining_ms()[1 - seat] == 0;
        let no_legal_placement = !timed_out
            && !placement_cancelled
            && kind == MoveKind::Pass
            && (bag_after < 7 || opponent_expired)
            && !has_placement(engine, state);
        let blocked = no_legal_placement && (opponent_expired || self.previous_blocked_pass);
        self.previous_blocked_pass = no_legal_placement;
        let end = if clock == ClockStatus::BothExpired {
            Some(EndReason::BothClocksExpired)
        } else if state.bag.is_empty() && state.current_player().rack.is_empty() {
            Some(EndReason::RackOut { seat })
        } else if blocked {
            Some(EndReason::Blocked)
        } else if six_passes {
            Some(EndReason::SixPasses)
        } else {
            None
        };
        Ok((
            LoggedTurn {
                turn,
                seat,
                kind,
                rack_before,
                bag_before,
                rack_after,
                bag_after,
                exchange_allowed,
                no_legal_placement,
                exchanged,
                tiles,
                word,
                direction,
                words,
                challenged_words,
                challenger_penalty,
                placement_cancelled,
                score,
                elapsed_ms,
                clocks_ms: rules.remaining_ms(),
                timed_out,
            },
            end,
        ))
    }
}

fn has_placement(engine: &Engine, state: &GameState) -> bool {
    let mut generator = engine.generator();
    engine.generate(
        &mut generator,
        &state.board_tiles,
        &state.current_player().rack,
        1,
    );
    generator
        .plays
        .iter()
        .any(|candidate| matches!(candidate.play, Play::Place { .. }))
}

fn play_match(
    engine: &Engine,
    reference: &Reference,
    peak: &mut PeakSearch,
    seed: u32,
    nab_seat: usize,
) -> Result<MatchLog> {
    let config = &engine.config;
    let mut rng = Xoshiro256PlusPlus::seed_from_u64(u64::from(seed));
    let mut state = GameState::new(config);
    state.reset_and_draw_tiles(config, &mut rng);
    state.turn = 0;
    let initial_racks = [0, 1].map(|seat| tile_string(engine, &state.players[seat].rack, false));
    let mut game = Match {
        engine,
        state,
        rules: MatchRules::new(STANDARD_CLOCK_MS),
        scorer: PlayScorer::new(),
        rng,
        previous_blocked_pass: false,
    };
    let mut turns = Vec::new();
    let mut end = None;
    for turn in 1..=MAX_TURNS {
        let state = &game.state;
        let seat = usize::from(state.turn);
        let clocks_ms = game.rules.remaining_ms();
        let exchange_allowed = game.rules.may_exchange(seat, state.bag.len());
        let rack_before = tile_string(engine, &state.current_player().rack, false);
        let (elapsed_ms, candidate, claimed) = if clocks_ms[seat] == 0 {
            (0, pass(), None)
        } else {
            let decision_started = Instant::now();
            let (play, claimed) = if seat == nab_seat {
                let rng_seed = (u64::from(seed) << 16) | u64::from(turn);
                let mut play = peak.choose(engine, state, rng_seed)?.play;
                if !exchange_allowed
                    && matches!(&play, Play::Exchange { tiles } if !tiles.is_empty())
                {
                    play = pass();
                }
                (play, None)
            } else {
                let (play, points) = reference.choose(
                    &Position {
                        board: tile_string(engine, &state.board_tiles, true),
                        rack: rack_before.clone(),
                        exchange_allowed,
                    },
                    engine,
                    clocks_ms[seat],
                )?;
                (play, Some(points))
            };
            let elapsed_ms = decision_started.elapsed().as_nanos().div_ceil(1_000_000) as u64;
            (elapsed_ms, play, claimed)
        };
        let (record, reason) = game
            .execute_turn(
                turn,
                candidate,
                claimed,
                elapsed_ms,
                ChallengePolicy::SharedLexicon,
            )
            .with_context(|| {
                format!("Graine {seed}, siège NAB {nab_seat}, tour {turn}, siège {seat}")
            })?;
        turns.push(record);
        end = reason;
        if end.is_some() {
            break;
        }
        game.state.next_turn();
    }
    let state = game.state;
    let end = end.context("La simulation a dépassé le nombre de tours autorisé")?;
    let final_racks = [0, 1].map(|seat| tile_string(engine, &state.players[seat].rack, false));
    let rack_values = [0, 1].map(|seat| config.alphabet().rack_score(&state.players[seat].rack));
    let adjustments = MatchRules::end_adjustments(end, rack_values);
    let scores =
        [0, 1].map(|seat| equity::descale_score(state.players[seat].score) + adjustments[seat]);
    Ok(MatchLog {
        seed,
        nab_seat,
        end,
        initial_racks,
        scores,
        adjustments,
        final_racks,
        turns,
    })
}

pub(crate) fn run(
    engine: &Engine,
    reference_path: &Path,
    pairs: u32,
    first_seed: u32,
    report_path: &Path,
    games_path: &Path,
) -> Result<()> {
    ensure!(
        engine.profile.id == "fr-classic-v1",
        "Le protocole Macondo utilise le profil français classique"
    );
    ensure!(
        (2..=20_000).contains(&pairs) && first_seed.checked_add(pairs - 1).is_some(),
        "Nombre ou intervalle de tirages appariés invalide"
    );
    ensure!(
        report_path != games_path && !report_path.exists() && !games_path.exists(),
        "Choisissez deux nouveaux chemins de sortie"
    );
    let reference_path = reference_path
        .canonicalize()
        .context("Chemin du moteur externe")?;
    let reference_hash = format!("{:x}", Sha256::digest(fs::read(&reference_path)?));
    let (reference, identity) =
        Reference::start(&reference_path, engine.reference_graph()?, STARTUP_DEADLINE)?;
    let expert = crate::server::model_policy(&engine.digest);
    let mut peak = PeakSearch::new(engine, expert, DEFAULT_CONFIG)?;
    let started = Instant::now();
    let mut log = BufWriter::new(
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(games_path)?,
    );
    let mut wins = 0;
    let mut draws = 0;
    let mut margins = 0_i64;
    let mut squares = 0.0;
    let mut challenged_words = 0;
    let mut cancelled_placements = 0;
    let mut timed_out_turns = 0;
    for pair in 0..pairs {
        let mut pair_points: f64 = 0.0;
        for nab_seat in [0, 1] {
            let game = play_match(engine, &reference, &mut peak, first_seed + pair, nab_seat)?;
            let margin = game.scores[nab_seat] - game.scores[1 - nab_seat];
            margins += i64::from(margin);
            wins += u32::from(margin > 0);
            draws += u32::from(margin == 0);
            pair_points += if margin > 0 {
                1.0
            } else if margin == 0 {
                0.5
            } else {
                0.0
            };
            for turn in &game.turns {
                challenged_words += turn.challenged_words.len();
                cancelled_placements += usize::from(turn.placement_cancelled);
                timed_out_turns += usize::from(turn.timed_out);
            }
            serde_json::to_writer(&mut log, &game)?;
            log.write_all(b"\n")?;
        }
        squares += (pair_points / 2.0).powi(2);
    }
    log.flush()?;
    let games = pairs * 2;
    let points = f64::from(wins) + f64::from(draws) / 2.0;
    let rate = points / f64::from(games);
    let adjusted_rate = (points + 0.5) / f64::from(games + 1);
    let report = Report {
        schema: "wordrivet.fisf-engine-match.v1",
        simulation: true,
        profile: engine.profile.id.clone(),
        lexicon: engine.lexicon.clone(),
        lexicon_sha256: engine.digest.clone(),
        normalized_forms: engine.words,
        reference: identity,
        reference_binary_sha256: reference_hash,
        host_os: std::env::consts::OS,
        host_arch: std::env::consts::ARCH,
        host_logical_cpus: std::thread::available_parallelism().ok().map(usize::from),
        nab_base: expert,
        nab_search: DEFAULT_CONFIG,
        initial_clock_ms: STANDARD_CLOCK_MS,
        clock_model: "wall_time_monotonic_ms_rounded_up_includes_reference_ipc",
        reference_startup_deadline_ms: STARTUP_DEADLINE.as_millis() as u64,
        reference_response_grace_ms: RESPONSE_GRACE_MS,
        challenge_policy: "automatically_challenge_only_words_outside_shared_lexicon_when_allowed",
        first_seed,
        paired_deals: pairs,
        games,
        nab_wins: wins,
        draws,
        reference_wins: games - wins - draws,
        challenged_words,
        cancelled_placements,
        timed_out_turns,
        match_score_rate: rate,
        paired_confidence_interval_95: paired_interval(rate, squares, pairs),
        interval_method: "two-sided empirical Bernstein on seed-pair means in [0,1]",
        mean_margin: margins as f64 / f64::from(games),
        relative_elo: 400.0 * (adjusted_rate / (1.0 - adjusted_rate)).log10(),
        elo_method: "400*log10(p/(1-p)); p=(match points+0.5)/(games+1)",
        elapsed_seconds: started.elapsed().as_secs_f64(),
    };
    let output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(report_path)?;
    serde_json::to_writer_pretty(output, &report)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profiles;
    use std::sync::OnceLock;

    fn fixture() -> Match<'static> {
        static ENGINE: OnceLock<Engine> = OnceLock::new();
        let engine = ENGINE.get_or_init(|| {
            let profile = profiles::load("fr-classic-v1").unwrap();
            Engine::load(
                Path::new("data/fr/words.txt"),
                "Grammalecte 7.7".into(),
                profile,
            )
            .unwrap()
        });
        let mut state = GameState::new(&engine.config);
        replace_rack(engine, &mut state, 0, "AAEPRSU");
        replace_rack(engine, &mut state, 1, "AAEINRT");
        for (row, col, letter) in [
            (6, 7, 'A'),
            (6, 8, 'Q'),
            (7, 6, 'C'),
            (7, 7, 'H'),
            (8, 7, 'I'),
        ] {
            state.board_tiles[row * BOARD_WIDTH + col] = engine.symbol_id(letter).unwrap();
        }
        state.bag.set_from_iter([1, 2, 3, 4, 5, 6, 7, 8]);
        Match {
            engine,
            state,
            rules: MatchRules::new(100),
            scorer: PlayScorer::new(),
            rng: Xoshiro256PlusPlus::seed_from_u64(1),
            previous_blocked_pass: false,
        }
    }

    fn replace_rack(engine: &Engine, state: &mut GameState, seat: usize, rack: &str) {
        state.players[seat].rack = rack
            .chars()
            .map(|letter| engine.symbol_id(letter).unwrap())
            .collect();
    }

    fn place(engine: &Engine, row: i8, col: i8, letters: &str) -> Play {
        let word: Vec<_> = letters
            .chars()
            .map(|letter| {
                if letter == '.' {
                    0
                } else {
                    engine.symbol_id(letter).unwrap()
                }
            })
            .collect();
        Play::Place {
            down: false,
            lane: row,
            idx: col,
            word: word.as_slice().into(),
            score: 0,
        }
    }

    #[test]
    fn mixed_challenge_penalizes_valid_word_and_cancels_before_drawing() {
        let mut game = fixture();
        // CHAPEAU is legal and its crossing QA is invalid.
        let play = place(game.engine, 7, 6, "..APEAU");
        let (_, words, invalid, _) = score_play(
            game.engine,
            &game.state,
            &mut game.scorer,
            play.clone(),
            None,
        )
        .unwrap();
        assert_eq!(words, ["CHAPEAU", "QA"]);
        assert_eq!(invalid, ["QA"]);
        let board_before = game.state.board_tiles.clone();
        let bag_before = game.state.bag.as_slice().to_vec();
        let (turn, end) = game
            .execute_turn(1, play, None, 1, ChallengePolicy::Scripted(&[0, 1]))
            .unwrap();
        assert!(end.is_none());
        assert!(turn.placement_cancelled);
        assert_eq!(turn.kind, MoveKind::Pass);
        assert_eq!(turn.challenger_penalty, 5);
        assert_eq!(turn.rack_after, turn.rack_before);
        assert_eq!(game.state.bag.as_slice(), bag_before);
        assert_eq!(game.state.board_tiles, board_before);
        assert_eq!(equity::descale_score(game.state.players[1].score), -5);
    }

    #[test]
    fn automatic_challenge_targets_only_invalid_words() {
        let mut game = fixture();
        let candidate = place(game.engine, 7, 6, "..APEAU");
        let (turn, end) = game
            .execute_turn(1, candidate, None, 1, ChallengePolicy::SharedLexicon)
            .unwrap();
        assert_eq!(end, None);
        assert_eq!(turn.challenged_words, ["QA"]);
        assert_eq!(turn.challenger_penalty, 0);
        assert!(turn.placement_cancelled);
        assert_eq!(turn.rack_after, turn.rack_before);
        assert_eq!(turn.bag_after, turn.bag_before);
    }

    #[test]
    fn expired_challenger_cannot_contest_final_rack_out() {
        let mut game = fixture();
        replace_rack(game.engine, &mut game.state, 0, "AAPEU");
        game.state.bag.set_from_iter([]);
        assert_eq!(game.rules.charge(1, 100), ClockStatus::Expired);
        let candidate = place(game.engine, 7, 6, "..APEAU");
        let (turn, end) = game
            .execute_turn(1, candidate, None, 1, ChallengePolicy::SharedLexicon)
            .unwrap();
        assert_eq!(end, Some(EndReason::RackOut { seat: 0 }));
        assert!(turn.challenged_words.is_empty());
        assert!(!turn.placement_cancelled);
        assert_eq!(turn.rack_after, "");
    }

    #[test]
    fn clock_expiry_discards_candidate_before_validation_or_draw() {
        let mut game = fixture();
        let candidate = place(game.engine, 7, 6, "..APEAU");
        let (first, end) = game
            .execute_turn(1, candidate, None, 100, ChallengePolicy::SharedLexicon)
            .unwrap();
        assert!(first.timed_out && end.is_none());
        assert_eq!(first.rack_before, first.rack_after);
        assert_eq!(first.bag_before, first.bag_after);
        assert_eq!(first.clocks_ms, [0, 100]);
        game.state.next_turn();
        let (last, end) = game
            .execute_turn(2, pass(), None, 100, ChallengePolicy::SharedLexicon)
            .unwrap();
        assert!(last.timed_out);
        assert_eq!(end, Some(EndReason::BothClocksExpired));
    }

    #[test]
    fn two_proven_passes_with_small_bag_close_a_blocked_game() {
        let mut game = fixture();
        game.state
            .board_tiles
            .fill(game.engine.symbol_id('A').unwrap());
        game.state.bag.set_from_iter([1, 2, 3]);
        let (first, early) = game
            .execute_turn(1, pass(), None, 1, ChallengePolicy::SharedLexicon)
            .unwrap();
        assert!(first.no_legal_placement);
        assert_eq!(early, None);
        game.state.next_turn();
        let (second, end) = game
            .execute_turn(2, pass(), None, 1, ChallengePolicy::SharedLexicon)
            .unwrap();
        assert!(second.no_legal_placement);
        assert_eq!(end, Some(EndReason::Blocked));
    }

    #[cfg(unix)]
    #[test]
    fn startup_watchdog_bounds_a_stalled_pipe_write() {
        // cat echoes the large graph until stdout fills, then stops reading stdin.
        let result = Reference::start(
            Path::new("/bin/cat"),
            vec![0; 4 * 1024 * 1024],
            Duration::from_millis(50),
        );
        let Err(error) = result else {
            panic!("a stalled adapter unexpectedly replied")
        };
        assert!(error.to_string().contains("Délai de réponse"));
    }

    #[test]
    fn identical_outcomes_keep_finite_non_degenerate_interval() {
        assert_eq!(paired_interval(1.0, 2.0, 2), [0.0, 1.0]);
        let bound = paired_interval(1.0, 1000.0, 1000);
        assert!(bound[0] < 1.0 && bound[0] > 0.98);
        assert_eq!(bound[1], 1.0);
    }
}
