use crate::engine::{Engine, Game, MoveKind, Tile};
use anyhow::{Context, Result, anyhow, bail};
use rand::{SeedableRng, seq::SliceRandom};
use rand_xoshiro::Xoshiro256PlusPlus;
use serde::Serialize;
use wolges::{equity, game_state::GameState, movegen::Play};

const SAMPLES: usize = 16;

#[derive(Serialize)]
pub struct Alternative {
    word: String,
    score: i32,
    tiles: Vec<Tile>,
    evaluation: f64,
    uncertainty: f64,
    leave: String,
    played: bool,
}

#[derive(Serialize)]
pub struct Review {
    turn: u16,
    played_score: i32,
    best_score: i32,
    points_left: i32,
    played_rank: Option<usize>,
    position_rack: String,
    legal_moves: usize,
    samples: usize,
    method: &'static str,
    alternatives: Vec<Alternative>,
}

fn same_tiles(a: &[Tile], b: &[Tile]) -> bool {
    a.len() == b.len()
        && a.iter().all(|t| {
            b.iter().any(|u| {
                t.row == u.row && t.col == u.col && t.letter == u.letter && t.blank == u.blank
            })
        })
}

fn points(play: &Play) -> i32 {
    match play {
        Play::Place { score, .. } => equity::descale_score(*score),
        _ => 0,
    }
}

fn rack_after(engine: &Engine, rack: &[u8], play: &Play) -> String {
    let mut keep = rack.to_vec();
    let removed: Vec<_> = match play {
        Play::Place { word, .. } => word
            .iter()
            .filter(|&&t| t != 0)
            .map(|&t| if t & 128 != 0 { 0 } else { t })
            .collect(),
        Play::Exchange { tiles } => tiles.to_vec(),
    };
    for tile in removed {
        if let Some(i) = keep.iter().position(|&t| t == tile) {
            keep.remove(i);
        }
    }
    keep.iter().map(|&tile| engine.letter(tile)).collect()
}

fn sampled_value(
    engine: &Engine,
    base: &GameState,
    unseen: &[u8],
    bag_len: usize,
    play: &Play,
    seed: u64,
) -> Result<(f64, f64)> {
    let player = base.turn as usize;
    let other = 1 - player;
    let mut generator = engine.generator();
    let mut mean = 0.0;
    let mut m2 = 0.0;
    let samples = if bag_len == 0 { 1 } else { SAMPLES };
    let mut rng = Xoshiro256PlusPlus::seed_from_u64(seed);
    let mut unknown = unseen.to_vec();
    for n in 1..=samples {
        unknown.copy_from_slice(unseen);
        unknown.shuffle(&mut rng);
        let mut state = base.clone();
        state.players[other].rack.clear();
        state.players[other]
            .rack
            .extend_from_slice(&unknown[bag_len..]);
        state.bag.set_from_iter(unknown[..bag_len].iter().copied());
        state
            .play(&engine.config, &mut rng, play)
            .map_err(|e| anyhow!("{e}"))?;
        let mut finished = state.current_player().rack.is_empty();
        for _ in 0..2 {
            if finished {
                break;
            }
            state.next_turn();
            let rack = &state.current_player().rack;
            engine.generate(&mut generator, &state.board_tiles, rack, 8);
            if let Some(reply) = generator
                .plays
                .iter()
                .filter(|p| matches!(p.play, Play::Place { .. }))
                .max_by_key(|p| engine.evaluation(&p.play, rack, state.bag.len()))
            {
                state
                    .play(&engine.config, &mut rng, &reply.play)
                    .map_err(|e| anyhow!("{e}"))?;
                finished = state.current_player().rack.is_empty();
            }
        }
        let mut margin =
            equity::descale_score(state.players[player].score - state.players[other].score);
        if finished {
            let remaining = engine
                .config
                .alphabet()
                .rack_score(&state.players[1 - state.turn as usize].rack);
            margin += if state.turn as usize == player {
                2 * remaining
            } else {
                -2 * remaining
            };
        }
        let delta = f64::from(margin) - mean;
        mean += delta / n as f64;
        m2 += delta * (f64::from(margin) - mean);
    }
    let uncertainty = if samples <= 1 {
        0.0
    } else {
        (m2 / (samples * (samples - 1)) as f64).sqrt()
    };
    Ok((mean, uncertainty))
}

pub fn analyze(engine: &Engine, game: &Game, turn: u16) -> Result<Review> {
    let index = usize::from(turn.checked_sub(1).context("tour hors limites")?);
    let chosen = game.moves.get(index).context("tour hors limites")?;
    let mut base = GameState::new(&engine.config);
    base.turn = chosen.player;
    for m in &game.moves[..index] {
        for t in &m.tiles {
            base.board_tiles[usize::from(t.row) * 15 + usize::from(t.col)] =
                engine.symbol_id(t.letter)? | if t.blank { 128 } else { 0 };
        }
    }
    let rack = chosen
        .rack
        .chars()
        .map(|c| engine.symbol_id(c))
        .collect::<Result<Vec<_>>>()?;
    base.players[usize::from(base.turn)].rack.clone_from(&rack);
    let mut remaining: Vec<_> = (0..engine.config.alphabet().len())
        .map(|i| i32::from(engine.config.alphabet().freq(i)))
        .collect();
    for tile in base
        .board_tiles
        .iter()
        .copied()
        .filter(|&t| t != 0)
        .chain(rack.iter().copied())
    {
        remaining[if tile >= 128 { 0 } else { tile as usize }] -= 1;
    }
    if remaining.iter().any(|&n| n < 0) {
        bail!("inventaire des jetons incohérent");
    }
    let unseen: Vec<u8> = remaining
        .into_iter()
        .enumerate()
        .flat_map(|(i, n)| std::iter::repeat_n(i as u8, n as usize))
        .collect();
    if chosen.bag_before > unseen.len() || unseen.len() - chosen.bag_before > 7 {
        bail!("nombre de jetons inconnus incohérent");
    }

    let mut generator = engine.generator();
    engine.generate(&mut generator, &base.board_tiles, &rack, usize::MAX);
    let mut places: Vec<_> = generator
        .plays
        .iter()
        .filter(|p| matches!(p.play, Play::Place { .. }))
        .map(|p| &p.play)
        .collect();
    places.sort_by_key(|p| std::cmp::Reverse(points(p)));
    let best_score = places.first().map_or(0, |p| points(p));
    let played = if chosen.kind == MoveKind::Play {
        places
            .iter()
            .find(|p| same_tiles(&engine.describe(&base.board_tiles, p).1, &chosen.tiles))
            .map(|p| (*p).clone())
            .context("le coup enregistré est absent du lexique chargé")?
    } else {
        let mut tiles: Vec<_> = chosen
            .exchanged
            .chars()
            .map(|c| engine.symbol_id(c))
            .collect::<Result<Vec<_>>>()?;
        tiles.sort_unstable();
        Play::Exchange {
            tiles: tiles.as_slice().into(),
        }
    };
    let played_rank = (chosen.kind == MoveKind::Play)
        .then(|| places.partition_point(|p| points(p) > chosen.score) + 1);
    let best = places.first().copied();
    let legal_moves = places.len();
    places.sort_by_key(|p| std::cmp::Reverse(engine.evaluation(p, &rack, chosen.bag_before)));
    let mut candidates = vec![&played];
    if let Some(best) = best.filter(|p| **p != played) {
        candidates.push(best);
    }
    for p in places {
        if candidates.len() == 6 {
            break;
        }
        if !candidates.contains(&p) {
            candidates.push(p);
        }
    }
    let seed = (u64::from(game.seed) << 16) | u64::from(turn);
    let mut alternatives = Vec::new();
    for p in candidates {
        let (word, tiles, _) = engine.describe(&base.board_tiles, p);
        let (evaluation, uncertainty) =
            sampled_value(engine, &base, &unseen, chosen.bag_before, p, seed)?;
        alternatives.push(Alternative {
            word,
            score: points(p),
            tiles,
            evaluation,
            uncertainty,
            leave: rack_after(engine, &rack, p),
            played: p == &played,
        });
    }
    alternatives.sort_by(|a, b| b.evaluation.total_cmp(&a.evaluation));
    Ok(Review {
        turn,
        played_score: chosen.score,
        best_score,
        points_left: (best_score - chosen.score).max(0),
        played_rank,
        position_rack: chosen.rack.clone(),
        legal_moves,
        samples: if chosen.bag_before == 0 { 1 } else { SAMPLES },
        method: "Écart moyen sur trois demi-coups. Les chevalets adverses et la pioche sont échantillonnés parmi les jetons inconnus. L’incertitude indique l’erreur-type de cet échantillonnage.",
        alternatives,
    })
}
