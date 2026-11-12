use crate::engine::{Engine, Game, MoveKind};
use serde::Serialize;
use serde_json::{Value, json};

#[derive(Serialize)]
pub struct Point {
    pub turn: u16,
    pub player: u8,
    pub score: i32,
    pub scores: [i32; 2],
    pub win_probability: [f64; 2],
    pub rack: String,
    pub draw: String,
    pub bag_after: usize,
    pub blank_draw_probability: Option<f64>,
}

fn take(rack: &mut Vec<char>, symbol: char) {
    if let Some(pos) = rack.iter().position(|&c| c == symbol) {
        rack.remove(pos);
    }
}

pub fn estimate(
    lead: i32,
    remaining: usize,
    elo_difference: f64,
    total: usize,
    calibration: &Value,
) -> f64 {
    let phase = match remaining {
        0..=20 => 0,
        21..=45 => 1,
        46..=74 => 2,
        _ => 3,
    };
    let mut calibrated = calibration["phases"][phase].as_array().and_then(|bins| {
        let x = f64::from(lead.abs()) / 20.0;
        let lower = (x.floor() as usize).min(bins.len().saturating_sub(1));
        let upper = (lower + 1).min(bins.len().saturating_sub(1));
        let left = bins.get(lower)?.as_f64()?;
        let right = bins.get(upper)?.as_f64()?;
        let p = left + (right - left) * (x.fract());
        Some(if lead >= 0 { p } else { 1.0 - p })
    });
    let points_logit = if let Some(p) = calibrated.take() {
        (p.clamp(0.005, 0.995) / (1.0 - p.clamp(0.005, 0.995))).ln()
    } else {
        f64::from(lead) / (10.0 + 60.0 * (remaining as f64 / total as f64).sqrt())
    };
    let rating_logit = 10.0f64.ln() * elo_difference / 400.0 * (remaining as f64 / total as f64);
    1.0 / (1.0 + (-(points_logit + rating_logit)).exp())
}

pub fn points(engine: &Engine, game: &Game, ratings: [f64; 2], calibration: &Value) -> Vec<Point> {
    let alphabet = engine.config.alphabet();
    let total = (0..alphabet.len())
        .map(|i| usize::from(alphabet.freq(i)))
        .sum::<usize>();
    let mut recorded_next = game.final_racks.clone();
    let mut draws = Vec::with_capacity(game.moves.len());
    for m in game.moves.iter().rev() {
        let mut rest = m.rack.chars().collect::<Vec<_>>();
        for t in &m.tiles {
            take(&mut rest, if t.blank { '?' } else { t.letter });
        }
        for c in m.exchanged.chars() {
            take(&mut rest, c);
        }
        let mut drawn = recorded_next[m.player as usize].chars().collect::<Vec<_>>();
        for symbol in rest {
            take(&mut drawn, symbol);
        }
        draws.push(drawn.into_iter().collect::<String>());
        recorded_next[m.player as usize] = m.rack.clone();
    }
    draws.reverse();
    let mut played = 0;
    let mut blanks_played = 0;
    let mut scores = [0, 0];
    game.moves
        .iter()
        .zip(draws)
        .enumerate()
        .map(|(i, (m, draw))| {
            let bag_after = game.moves.get(i + 1).map_or_else(
                || match m.kind {
                    MoveKind::Play => m.bag_before.saturating_sub(draw.chars().count()),
                    _ => m.bag_before,
                },
                |n| n.bag_before,
            );
            let unseen = total.saturating_sub(played + m.rack.chars().count());
            let blanks = usize::from(alphabet.freq(0))
                .saturating_sub(blanks_played + m.rack.chars().filter(|&c| c == '?').count());
            let draws = m
                .tiles
                .len()
                .max(m.exchanged.chars().count())
                .min(m.bag_before)
                .min(unseen);
            let blank_draw_probability = if draws > 0 && unseen > 0 {
                let miss = (0..draws).fold(1.0, |p, k| {
                    p * (unseen - k).saturating_sub(blanks) as f64 / (unseen - k) as f64
                });
                Some(1.0 - miss)
            } else {
                None
            };
            played += m.tiles.len();
            blanks_played += m.tiles.iter().filter(|t| t.blank).count();
            scores[m.player as usize] += m.score;
            let p = if i + 1 == game.moves.len() {
                scores = game.scores;
                match game.winner {
                    Some(0) => 1.0,
                    Some(1) => 0.0,
                    _ => 0.5,
                }
            } else {
                estimate(
                    scores[0] - scores[1],
                    total.saturating_sub(played),
                    ratings[0] - ratings[1],
                    total,
                    calibration,
                )
            };
            Point {
                turn: m.turn,
                player: m.player,
                score: m.score,
                scores,
                win_probability: [p, 1.0 - p],
                rack: m.rack.clone(),
                draw,
                bag_after,
                blank_draw_probability,
            }
        })
        .collect()
}

pub fn method(calibration: &Value) -> Value {
    json!({
        "win":if calibration["phases"].is_array() {
            "Projection issue des duels de référence à niveau égal, ajustée par l’écart Elo et les jetons restants. Une nulle vaut une demi-victoire."
        } else {
            "Projection indicative par écart de points, Elo et nombre de jetons restants."
        },
        "draw":"Jetons piochés reconstruits depuis le chevalet suivant. La chance d’un joker avant le coup est calculée sur les jetons inconnus."
    })
}
