use crate::engine::{Engine, ExpertPolicy};
use anyhow::{Result, anyhow, ensure};
use rand::{Rng, RngExt, SeedableRng};
use rand_xoshiro::Xoshiro256PlusPlus;
use serde::{Deserialize, Serialize};
use std::thread;
use wolges::{
    game_state::GameState,
    movegen::{KurniaMoveGenerator, Play},
};

const EXTRAPOLATED_TIER: u8 = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct StrengthScale {
    pub anchors: [i32; 4],
    pub expert: ExpertPolicy,
    pub experimental_floor: Option<i32>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct StrengthTarget {
    pub requested_elo: f64,
    pub applied_elo: f64,
    pub mixture_elo: f64,
    pub lower_level: u8,
    pub upper_level: u8,
    pub upper_fraction: f64,
    pub below_reference_range: bool,
    pub clamped: bool,
    pub calibrated: bool,
    expert: ExpertPolicy,
}

impl StrengthScale {
    pub fn new(anchors: [i32; 4], expert: ExpertPolicy) -> Result<Self> {
        ensure!(
            anchors.windows(2).all(|levels| levels[0] < levels[1]),
            "les quatre Elo de référence doivent être strictement croissants"
        );
        ensure!(
            (1..=256).contains(&expert.width),
            "largeur Expert hors limites"
        );
        Ok(Self {
            anchors,
            expert,
            experimental_floor: None,
        })
    }

    pub fn with_experimental_floor(mut self, elo: i32) -> Result<Self> {
        ensure!(
            elo < self.anchors[0],
            "la cote expérimentale doit être sous la première référence"
        );
        self.experimental_floor = Some(elo);
        Ok(self)
    }

    pub fn at(&self, target_elo: f64) -> Result<StrengthTarget> {
        ensure!(target_elo.is_finite(), "Elo demandé non fini");
        let floor = self.experimental_floor.unwrap_or(self.anchors[0]);
        let applied = target_elo.clamp(f64::from(floor), f64::from(self.anchors[3]));
        Ok(self.target(target_elo, applied, applied, false))
    }

    fn target(
        &self,
        requested: f64,
        applied: f64,
        mixture: f64,
        calibrated: bool,
    ) -> StrengthTarget {
        let below_reference_range = mixture < f64::from(self.anchors[0]);
        let (lower_level, upper_level, lower_elo, upper_elo) = if below_reference_range {
            (
                EXTRAPOLATED_TIER,
                0,
                self.experimental_floor.unwrap_or(self.anchors[0]),
                self.anchors[0],
            )
        } else {
            let upper = self
                .anchors
                .partition_point(|&elo| f64::from(elo) < mixture);
            let upper = upper.min(3);
            let lower = if f64::from(self.anchors[upper]) == mixture {
                upper
            } else {
                upper.saturating_sub(1)
            };
            (
                lower as u8,
                upper as u8,
                self.anchors[lower],
                self.anchors[upper],
            )
        };
        let upper_fraction = if lower_level == upper_level {
            0.0
        } else {
            let fraction =
                (mixture - f64::from(lower_elo)) / (f64::from(upper_elo) - f64::from(lower_elo));
            fraction.clamp(0.0, 1.0)
        };
        StrengthTarget {
            requested_elo: requested,
            applied_elo: applied,
            mixture_elo: mixture,
            lower_level,
            upper_level,
            upper_fraction,
            below_reference_range,
            clamped: requested != applied,
            calibrated,
            expert: self.expert,
        }
    }

    pub fn at_calibrated(
        &self,
        target_elo: f64,
        calibration: &Calibration,
    ) -> Result<StrengthTarget> {
        ensure!(target_elo.is_finite(), "Elo demandé non fini");
        ensure!(
            calibration.scale == *self,
            "calibration incompatible avec les références"
        );
        let fitted = calibration.monotonic_curve();
        ensure!(
            fitted.len() >= 2,
            "la calibration demande au moins deux mesures"
        );
        let min = fitted[0].1;
        let max = fitted.last().expect("deux mesures").1;
        ensure!(max > min, "le test ne sépare pas les forces demandées");
        let applied = target_elo.clamp(min, max);
        let upper = fitted.partition_point(|&(_, measured)| measured < applied);
        let (x1, y1) = fitted[upper];
        let mixture = if upper == 0 || y1 == applied {
            x1
        } else {
            let (x0, y0) = fitted[upper - 1];
            x0 + (x1 - x0) * (applied - y0) / (y1 - y0)
        };
        Ok(self.target(target_elo, applied, mixture, true))
    }
}

impl StrengthTarget {
    pub fn pick(&self, rng: &mut impl Rng) -> (u8, ExpertPolicy) {
        let level = if self.lower_level == self.upper_level || self.upper_fraction <= 0.0 {
            self.lower_level
        } else if self.upper_fraction >= 1.0 || rng.random::<f64>() < self.upper_fraction {
            self.upper_level
        } else {
            self.lower_level
        };
        (level, self.expert)
    }

    pub fn choose_move(
        &self,
        engine: &Engine,
        generator: &mut KurniaMoveGenerator,
        state: &GameState,
        rng: &mut impl Rng,
    ) -> Play {
        let (level, policy) = self.pick(rng);
        let width = [18, 10, 5, policy.width + 2, 32][usize::from(level)];
        engine.generate(
            generator,
            &state.board_tiles,
            &state.current_player().rack,
            width,
        );
        engine.choose(generator, state, level, policy, rng)
    }
}

pub fn expected_score(elo: f64, opponent: f64) -> f64 {
    1.0 / (1.0 + 10f64.powf((opponent - elo) / 400.0))
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Calibration {
    pub schema: String,
    pub profile: String,
    pub lexicon: String,
    pub lexicon_sha256: String,
    pub scale: StrengthScale,
    pub anchor_level: u8,
    pub anchor_elo: i32,
    pub first_seed: u32,
    pub paired_deals_per_target: u32,
    pub measurements: Vec<Measurement>,
    pub method: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Measurement {
    pub mixture: StrengthTarget,
    pub games: u32,
    pub wins: u32,
    pub draws: u32,
    pub losses: u32,
    pub mean_margin: f64,
    pub match_score_rate: f64,
    pub match_score_ci95: [f64; 2],
    pub measured_elo: f64,
    pub measured_elo_ci95: [f64; 2],
    pub turns_by_tier: [u64; 5],
}

impl Calibration {
    pub fn compatible(&self, engine: &Engine, scale: &StrengthScale) -> bool {
        self.schema == "lettrine.adaptive-calibration.v1"
            && self.profile == engine.profile.id
            && self.lexicon_sha256 == engine.digest
            && self.scale == *scale
    }

    fn monotonic_curve(&self) -> Vec<(f64, f64)> {
        // Isotonic least squares pools adjacent points that disagree on order.
        let mut observations: Vec<_> = self
            .measurements
            .iter()
            .filter_map(|p| {
                (p.measured_elo.is_finite() && p.mixture.mixture_elo.is_finite() && p.games > 0)
                    .then_some((p.mixture.mixture_elo, p.measured_elo, f64::from(p.games)))
            })
            .collect();
        observations.sort_by(|a, b| a.0.total_cmp(&b.0));
        observations.dedup_by(|a, b| {
            if a.0 != b.0 {
                return false;
            }
            let total = a.2 + b.2;
            b.1 = (a.1 * a.2 + b.1 * b.2) / total;
            b.2 = total;
            true
        });
        let mut blocks: Vec<(usize, f64, f64)> = Vec::new();
        for (_, measured, weight) in &observations {
            blocks.push((1, measured * weight, *weight));
            while let [.., (_, previous_sum, previous_weight), (_, sum, weight)] = blocks.as_slice()
            {
                if previous_sum / previous_weight <= sum / weight {
                    break;
                }
                let right = blocks.pop().expect("deux blocs");
                let left = blocks.last_mut().expect("bloc précédent");
                left.0 += right.0;
                left.1 += right.1;
                left.2 += right.2;
            }
        }
        observations
            .into_iter()
            .map(|point| point.0)
            .zip(
                blocks
                    .into_iter()
                    .flat_map(|(count, sum, weight)| std::iter::repeat_n(sum / weight, count)),
            )
            .collect()
    }
}

#[derive(Default)]
struct Outcomes {
    wins: u32,
    draws: u32,
    margin: i64,
    turns_by_tier: [u64; 5],
}
impl Outcomes {
    fn add(&mut self, other: Self) {
        self.wins += other.wins;
        self.draws += other.draws;
        self.margin += other.margin;
        for (count, additional) in self.turns_by_tier.iter_mut().zip(other.turns_by_tier) {
            *count += additional;
        }
    }

    fn summarize(self, target: StrengthTarget, anchor_elo: i32, paired_deals: u32) -> Measurement {
        let games = paired_deals * 2;
        let points = f64::from(self.wins) + 0.5 * f64::from(self.draws);
        let rate = points / f64::from(games);
        // Treat a mirrored pair as one independent observation. The Wilson
        // interval for a bounded pair mean is conservative against pooling the two games.
        let n = f64::from(paired_deals);
        let z = 1.959_963_984_540_054;
        let adjusted = 1.0 + z * z / n;
        let center = (rate + z * z / (2.0 * n)) / adjusted;
        let width = z * (rate * (1.0 - rate) / n + z * z / (4.0 * n * n)).sqrt() / adjusted;
        let ci = [(center - width).max(0.0), (center + width).min(1.0)];
        let to_elo = |p: f64| {
            // A half-match continuity correction keeps extreme estimates finite.
            let rate = (p * f64::from(games) + 0.5) / f64::from(games + 1);
            f64::from(anchor_elo) + 400.0 * (rate / (1.0 - rate)).log10()
        };
        Measurement {
            mixture: target,
            games,
            wins: self.wins,
            draws: self.draws,
            losses: games - self.wins - self.draws,
            mean_margin: self.margin as f64 / f64::from(games),
            match_score_rate: rate,
            match_score_ci95: ci,
            measured_elo: to_elo(rate),
            measured_elo_ci95: ci.map(to_elo),
            turns_by_tier: self.turns_by_tier,
        }
    }
}

pub fn calibrate(
    engine: &Engine,
    scale: &StrengthScale,
    targets: &[f64],
    anchor_level: u8,
    first_seed: u32,
    paired_deals: u32,
) -> Result<Calibration> {
    ensure!(
        anchor_level < 4,
        "le niveau de référence doit être entre 0 et 3"
    );
    ensure!(
        (2..=100_000).contains(&paired_deals),
        "nombre de tirages appariés entre 2 et 100 000"
    );
    ensure!(
        first_seed.checked_add(paired_deals - 1).is_some(),
        "intervalle de graines trop grand"
    );
    ensure!(!targets.is_empty(), "indiquez au moins une cible Elo");
    let mut configurations = targets
        .iter()
        .map(|&value| scale.at(value))
        .collect::<Result<Vec<_>>>()?;
    configurations.sort_by(|a, b| a.applied_elo.total_cmp(&b.applied_elo));
    configurations.dedup_by(|a, b| a.applied_elo == b.applied_elo);
    let anchor_elo = scale.anchors[usize::from(anchor_level)];
    let workers = thread::available_parallelism()
        .map_or(1, |n| n.get().saturating_sub(2).clamp(1, 12)) as u32;
    let mut measurements = Vec::with_capacity(configurations.len());
    for target in configurations {
        let outcomes = thread::scope(|scope| -> Result<Outcomes> {
            let handles: Vec<_> = (0..workers.min(paired_deals))
                .map(|worker| {
                    scope.spawn(move || -> Result<Outcomes> {
                        let mut state = GameState::new(&engine.config);
                        let mut generator = engine.generator();
                        let mut outcomes = Outcomes::default();
                        for pair in (worker..paired_deals).step_by(workers as usize) {
                            let seed = first_seed + pair;
                            for seat in 0..2u8 {
                                let mut tier_rng = Xoshiro256PlusPlus::seed_from_u64(
                                    u64::from(seed) ^ 0x8ed6_9b14_5e13_65cb,
                                );
                                let mut levels = [anchor_level; 2];
                                levels[usize::from(seat)] = target.lower_level;
                                let game = engine.play_seeded_by(
                                    &mut generator,
                                    &mut state,
                                    1 + pair * 2 + u32::from(seat),
                                    seed,
                                    levels,
                                    |player, _turn| {
                                        if player == seat {
                                            let selected = target.pick(&mut tier_rng);
                                            outcomes.turns_by_tier[usize::from(selected.0)] += 1;
                                            selected
                                        } else {
                                            (anchor_level, scale.expert)
                                        }
                                    },
                                )?;
                                outcomes.wins += u32::from(game.winner == Some(seat));
                                outcomes.draws += u32::from(game.winner.is_none());
                                outcomes.margin += i64::from(
                                    game.scores[usize::from(seat)]
                                        - game.scores[usize::from(1 - seat)],
                                );
                            }
                        }
                        Ok(outcomes)
                    })
                })
                .collect();
            let mut outcomes = Outcomes::default();
            for handle in handles {
                outcomes.add(
                    handle
                        .join()
                        .map_err(|_| anyhow!("worker de calibration interrompu"))??,
                );
            }
            Ok(outcomes)
        })?;
        measurements.push(outcomes.summarize(target, anchor_elo, paired_deals));
    }
    Ok(Calibration {
        schema: "lettrine.adaptive-calibration.v1".into(),
        profile: engine.profile.id.clone(),
        lexicon: engine.lexicon.clone(),
        lexicon_sha256: engine.digest.clone(),
        scale: *scale,
        anchor_level,
        anchor_elo,
        first_seed,
        paired_deals_per_target: paired_deals,
        measurements,
        method: "Deux sièges par tirage, sélection de politique à chaque tour. Cotes déduites du taux de points face à une référence fixe, nulles à ½. Intervalle Wilson approximatif, un tirage apparié comme unité.".into(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::BASE_EXPERT;

    #[test]
    fn interpolates_reference_tiers_and_bounds_floor() {
        let scale = StrengthScale::new([748, 1016, 1409, 1640], BASE_EXPERT)
            .unwrap()
            .with_experimental_floor(700)
            .unwrap();
        let novice = scale.at(724.0).unwrap();
        assert_eq!(
            (
                novice.lower_level,
                novice.upper_level,
                novice.upper_fraction
            ),
            (4, 0, 0.5)
        );
        assert!(novice.below_reference_range);
        let middle = scale.at(1212.5).unwrap();
        assert_eq!(
            (
                middle.lower_level,
                middle.upper_level,
                middle.upper_fraction
            ),
            (1, 2, 0.5)
        );
        let strongest = scale.at(2500.0).unwrap();
        assert_eq!(strongest.applied_elo, 1640.0);
        assert_eq!(
            strongest.pick(&mut Xoshiro256PlusPlus::seed_from_u64(1)),
            (3, BASE_EXPERT)
        );
        assert!(strongest.clamped);
        assert!(scale.at(f64::NAN).is_err());
    }
}
