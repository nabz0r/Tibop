use crate::engine::{End, Game, LEVELS, MoveKind, PAIRS};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashMap;

#[derive(Default, Serialize, Deserialize)]
struct Level {
    games: u32,
    wins: u32,
    draws: u32,
    points: i64,
}

#[derive(Default, Serialize, Deserialize)]
struct Pair {
    a_wins: u32,
    b_wins: u32,
    draws: u32,
}
impl Pair {
    fn count(&self) -> u32 {
        self.a_wins + self.b_wins + self.draws
    }
}

#[derive(Default, Serialize, Deserialize)]
pub struct Metrics {
    pub completed: u32,
    levels: [Level; 4],
    pairs: [Pair; 10],
    words: HashMap<String, (u32, i64)>,
    pub elapsed_seconds: f64,
    recent: Vec<Sample>,
    turns: u64,
    best_score: Option<i32>,
    bingo: u64,
    pass: u64,
    exchange: u64,
    first_wins: u32,
    draws: u32,
    rack_out: u32,
}

#[derive(Serialize, Deserialize)]
struct Sample {
    at: f64,
    completed: u32,
}

fn ratio(num: impl Into<f64>, den: impl Into<f64>) -> f64 {
    let den = den.into();
    if den == 0.0 { 0.0 } else { num.into() / den }
}

impl Metrics {
    pub fn add(&mut self, game: &Game) {
        self.completed += 1;
        self.turns += game.turns as u64;
        self.first_wins += u32::from(game.winner == Some(0));
        self.draws += u32::from(game.winner.is_none());
        self.rack_out += u32::from(game.end == End::RackOut);
        for (i, &level) in game.levels.iter().enumerate() {
            let level = &mut self.levels[level as usize];
            level.games += 1;
            level.points += i64::from(game.scores[i]);
            level.draws += u32::from(game.winner.is_none());
            level.wins += u32::from(game.winner == Some(i as u8));
            self.best_score = Some(self.best_score.unwrap_or(i32::MIN).max(game.scores[i]));
        }
        let [l, r] = game.levels;
        let pair = &mut self.pairs[PAIRS
            .iter()
            .position(|&pair| pair == (l.min(r), l.max(r)))
            .unwrap()];
        match game.winner {
            None => pair.draws += 1,
            Some(seat) if (l <= r && seat == 0) || (l > r && seat == 1) => pair.a_wins += 1,
            Some(_) => pair.b_wins += 1,
        }
        for m in &game.moves {
            match m.kind {
                MoveKind::Play => {
                    let entry = self.words.entry(m.word.clone()).or_default();
                    entry.0 += 1;
                    entry.1 += i64::from(m.score);
                    self.bingo += u64::from(m.tiles.len() == 7);
                }
                MoveKind::Pass => self.pass += 1,
                MoveKind::Exchange => self.exchange += 1,
            }
        }
    }

    pub fn sample(&mut self) {
        if self
            .recent
            .last()
            .is_none_or(|s| self.completed - s.completed >= 1000)
        {
            self.recent.push(Sample {
                at: self.elapsed_seconds,
                completed: self.completed,
            });
        }
    }

    pub fn ratings(&self) -> [i32; 4] {
        // Bradley–Terry on all cross-level results, draws count as half a win.
        // A weak symmetric prior avoids infinities before both sides win a game.
        let mut model = [0.0f64; 4];
        for _ in 0..32 {
            let mut gradients = [0.0; 4];
            let mut weights = [1.0; 4];
            for ((a, b), pair) in PAIRS.into_iter().zip(&self.pairs) {
                if a == b {
                    continue;
                }
                let (a, b) = (a as usize, b as usize);
                let expected = 1.0 / (1.0 + (model[b] - model[a]).exp());
                let games = f64::from(pair.count()) + 2.0;
                let observed = f64::from(pair.a_wins) + f64::from(pair.draws) / 2.0 + 1.0;
                let diff = observed - games * expected;
                gradients[a] += diff;
                gradients[b] -= diff;
                let info = games * expected * (1.0 - expected);
                weights[a] += info;
                weights[b] += info;
            }
            for i in 0..4 {
                model[i] += (0.5 * gradients[i] / weights[i]).clamp(-1.0, 1.0);
            }
        }
        let mean = model.iter().sum::<f64>() / 4.0;
        model.map(|r| (1200.0 + 400.0 * (r - mean) / 10f64.ln()).round() as i32)
    }

    pub fn public(&self) -> Value {
        let rating = self.ratings();
        let levels: Vec<_> = self
            .levels
            .iter()
            .enumerate()
            .map(|(i, l)| {
                json!({
                    "id": i, "label": LEVELS[i], "rating": rating[i], "games": l.games,
                    "wins": l.wins, "draws": l.draws, "mean_score": ratio(l.points as f64, l.games)
                })
            })
            .collect();
        let matchups: Vec<_> = PAIRS.into_iter().zip(&self.pairs).map(|((a,b), p)| json!({
            "a": a, "b": b, "a_wins": p.a_wins, "b_wins": p.b_wins, "draws": p.draws, "games": p.count()
        })).collect();
        let mut top: Vec<_> = self.words.iter().collect();
        top.sort_unstable_by(|a, b| b.1.0.cmp(&a.1.0).then_with(|| a.0.cmp(b.0)));
        let words: Vec<_> = top
            .into_iter()
            .take(24)
            .map(|(word, (n, p))| {
                json!({
                    "word": word, "count": n, "mean_score": ratio(*p as f64, *n),
                })
            })
            .collect();
        let points: i64 = self.levels.iter().map(|l| l.points).sum();
        let turns = self.turns as f64;
        json!({
            "completed": self.completed, "elapsed_seconds": self.elapsed_seconds,
            "mean_score": ratio(points as f64, 2.0 * f64::from(self.completed)),
            "mean_turns": ratio(turns, self.completed), "best_score": self.best_score,
            "first_win_rate": ratio(self.first_wins, self.completed),
            "draw_rate": ratio(self.draws, self.completed),
            "bingo_rate": ratio(self.bingo as f64, turns),
            "pass_rate": ratio(self.pass as f64, turns),
            "exchange_rate": ratio(self.exchange as f64, turns),
            "finished_reason": {"rack_out":self.rack_out,"passes":self.completed-self.rack_out},
            "levels": levels, "matchups": matchups, "words": words, "recent": self.recent,
            "turns": self.turns, "bingo_count": self.bingo, "distinct_words": self.words.len()
        })
    }
}
