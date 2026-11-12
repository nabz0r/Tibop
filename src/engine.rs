use crate::profiles::{Profile, RackOutScoring};
use anyhow::{Context, Result, anyhow, bail};
use rand::{Rng, RngExt, SeedableRng};
use rand_xoshiro::Xoshiro256PlusPlus;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::HashMap, fs, path::Path};
use unicode_normalization::{UnicodeNormalization, char::is_combining_mark};
use wolges::{
    alphabet, build, equity, game_config,
    game_state::GameState,
    klv, kwg,
    movegen::{BoardSnapshot, GenMovesParams, KurniaMoveGenerator, PassPolicy, Play},
};

pub const LEVELS: [&str; 4] = ["Découverte", "Initié", "Stratège", "Expert"];
pub const PAIRS: [(u8, u8); 10] = [
    (0, 0),
    (0, 1),
    (0, 2),
    (0, 3),
    (1, 1),
    (1, 2),
    (1, 3),
    (2, 2),
    (2, 3),
    (3, 3),
];
pub const ENGINE_VERSION: &str = "fr-classic-v1";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Tile {
    pub row: u8,
    pub col: u8,
    pub letter: char,
    pub blank: bool,
}

#[derive(Debug, Copy, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MoveKind {
    Play,
    Pass,
    Exchange,
}

#[derive(Debug, Copy, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum End {
    RackOut,
    Passes,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Move {
    pub turn: u16,
    pub player: u8,
    #[serde(rename = "type")]
    pub kind: MoveKind,
    pub word: String,
    pub score: i32,
    pub tiles: Vec<Tile>,
    pub rack: String,
    pub exchanged: String,
    pub bag_before: usize,
    pub direction: Option<char>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Game {
    pub id: u32,
    pub seed: u32,
    pub levels: [u8; 2],
    pub scores: [i32; 2],
    pub adjustments: [i32; 2],
    pub final_racks: [String; 2],
    pub turns: usize,
    pub winner: Option<u8>,
    pub end: End,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub profile: String,
    pub moves: Vec<Move>,
}

#[derive(Debug, Copy, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExpertPolicy {
    pub leave_scale: i32,
    pub blank_weight: i32,
    pub s_weight: i32,
    pub turnover: i32,
    pub end_penalty: i32,
    pub width: usize,
}

pub const BASE_EXPERT: ExpertPolicy = ExpertPolicy {
    leave_scale: 100,
    blank_weight: 23,
    s_weight: 9,
    turnover: 0,
    end_penalty: 3,
    width: 12,
};

pub struct Engine {
    pub config: game_config::GameConfig,
    kwg: kwg::Kwg<kwg::Node24>,
    klv: klv::Klv<kwg::Node22>,
    ranks: HashMap<Vec<u8>, usize>,
    pub lexicon: String,
    pub digest: String,
    pub words: usize,
    pub profile: Profile,
    symbols: Vec<char>,
    symbol_ids: HashMap<char, u8>,
}

impl Engine {
    pub fn load(path: &Path, lexicon: String, profile: Profile) -> Result<Self> {
        let config = profile.game_config(2)?;
        let symbols: Vec<_> = profile
            .tiles
            .as_ref()
            .context("distribution de jetons absente")?
            .iter()
            .map(|t| t.symbol.chars().next().expect("profil validé"))
            .collect();
        let symbol_ids: HashMap<_, _> = symbols
            .iter()
            .copied()
            .enumerate()
            .map(|(i, c)| (c, i as u8))
            .collect();
        let bytes = fs::read(path).with_context(|| format!("lecture de {}", path.display()))?;
        let digest = format!("{:x}", Sha256::digest(&bytes));
        let list = std::str::from_utf8(&bytes)?.trim_start_matches('\u{feff}');
        let mut ranks: HashMap<Vec<u8>, usize> = HashMap::new();
        for (index, line) in list.lines().enumerate() {
            let word = line.trim();
            if word.is_empty() {
                continue;
            }
            let normalized: String =
                if profile.orthography.normalization.as_deref() == Some("fr_latin_fold") {
                    word.to_uppercase()
                        .replace('Œ', "OE")
                        .replace('Æ', "AE")
                        .nfd()
                        .filter(|&c| !is_combining_mark(c))
                        .collect()
                } else {
                    word.to_uppercase().nfc().collect()
                };
            let key: Option<Vec<_>> = normalized
                .chars()
                .map(|c| symbol_ids.get(&c).copied().filter(|&t| t != 0))
                .collect();
            let Some(key) = key.filter(|k| (2..=15).contains(&k.len())) else {
                bail!(
                    "ligne {} invalide pour {} : de 2 à 15 jetons du profil sont attendus",
                    index + 1,
                    profile.id
                );
            };
            ranks.entry(key).or_insert(index);
        }
        if ranks.is_empty() {
            bail!("lexique vide");
        }
        let words = ranks.len();
        let mut machine: Vec<wolges::bites::Bites> =
            ranks.keys().map(|w| w.as_slice().into()).collect();
        machine.sort_unstable();
        let encoded = build::build_for_game(&config, &machine).map_err(|e| anyhow!("{e}"))?;
        let kwg = kwg::Kwg::from_bytes_alloc(&encoded).map_err(|e| anyhow!("{e}"))?;
        let klv = klv::Klv::from_bytes_alloc(klv::EMPTY_KLV_BYTES).map_err(|e| anyhow!("{e}"))?;
        Ok(Self {
            config,
            kwg,
            klv,
            ranks,
            lexicon,
            digest,
            words,
            profile,
            symbols,
            symbol_ids,
        })
    }

    pub fn letter(&self, tile: u8) -> char {
        self.symbols[(tile & 0x7f) as usize]
    }
    pub fn symbol_id(&self, letter: char) -> Result<u8> {
        self.symbol_ids
            .get(&letter)
            .copied()
            .with_context(|| format!("jeton {letter} absent du profil"))
    }
    fn rack_string(&self, rack: &[u8]) -> String {
        let mut sorted = rack.to_vec();
        sorted.sort_unstable();
        sorted.into_iter().map(|t| self.letter(t)).collect()
    }

    pub fn generator(&self) -> KurniaMoveGenerator {
        KurniaMoveGenerator::new(&self.config)
    }

    pub fn snapshot<'a>(&'a self, board: &'a [u8]) -> BoardSnapshot<'a, kwg::Node24, kwg::Node22> {
        BoardSnapshot {
            board_tiles: board,
            game_config: &self.config,
            kwg: &self.kwg,
            anagrams: None,
            rack_lengths: None,
            klv: &self.klv,
        }
    }

    pub fn generate(
        &self,
        generator: &mut KurniaMoveGenerator,
        board: &[u8],
        rack: &[u8],
        count: usize,
    ) {
        generator.gen_moves_unfiltered_lean(&GenMovesParams {
            board_snapshot: &self.snapshot(board),
            rack,
            max_gen: count,
            num_exchanges_by_this_player: 0,
            pass_policy: PassPolicy::OnlyWhenForced,
            dynamic_leaves: None,
        });
    }

    pub fn score(&self, tile: u8) -> i32 {
        self.config.alphabet().rack_score(&[tile])
    }

    pub fn describe(&self, board: &[u8], play: &Play) -> (String, Vec<Tile>, Option<char>) {
        let Play::Place {
            down,
            lane,
            idx,
            word,
            ..
        } = play
        else {
            return (String::new(), Vec::new(), None);
        };
        let mut full_word = String::new();
        let mut tiles = Vec::with_capacity(7);
        for (i, &tile) in word.iter().enumerate() {
            let offset = *idx as usize + i;
            let (row, col) = if *down {
                (offset, *lane as usize)
            } else {
                (*lane as usize, offset)
            };
            if tile == 0 {
                full_word.push(self.letter(board[row * 15 + col]));
            } else {
                full_word.push(self.letter(tile));
                tiles.push(Tile {
                    row: row as u8,
                    col: col as u8,
                    letter: self.letter(tile),
                    blank: tile & 128 != 0,
                });
            }
        }
        (full_word, tiles, Some(if *down { 'V' } else { 'H' }))
    }

    pub(crate) fn choose(
        &self,
        generator: &KurniaMoveGenerator,
        state: &GameState,
        level: u8,
        policy: ExpertPolicy,
        rng: &mut impl Rng,
    ) -> Play {
        let rack = &state.current_player().rack;
        let mut places: Vec<_> = generator
            .plays
            .iter()
            .filter_map(|entry| match &entry.play {
                Play::Place { score, .. } => Some((&entry.play, equity::descale_score(*score))),
                _ => None,
            })
            .collect();
        places.sort_by_key(|p| std::cmp::Reverse(p.1));
        if !places.is_empty() {
            let choice = match level {
                0 | 1 => {
                    let upper = places.len().min(if level == 0 { 16 } else { 8 });
                    let lower = (upper / if level == 0 { 3 } else { 4 }).min(upper - 1);
                    let start = rng.random_range(lower..upper);
                    let end = (start + 3).min(upper);
                    places[start..end]
                        .iter()
                        .max_by_key(|(p, score)| {
                            let (w, _, _) = self.describe(&state.board_tiles, p);
                            let machine: Vec<_> = w.chars().map(|c| self.symbol_ids[&c]).collect();
                            let rank = *self.ranks.get(&machine).unwrap_or(&self.words);
                            *score - ((rank + 1).ilog2() as i32 * if level == 0 { 2 } else { 1 })
                        })
                        .unwrap()
                        .0
                }
                2 => places[rng.random_range(0..places.len().min(3))].0,
                _ => {
                    places
                        .iter()
                        .take(policy.width)
                        .max_by_key(|(p, _)| self.evaluate(p, rack, state.bag.len(), policy))
                        .unwrap()
                        .0
                }
            };
            return choice.clone();
        }

        // Bound consecutive exchanges in a stalled game while still allowing a new draw.
        if state.bag.len() >= self.config.exchange_tile_limit() as usize
            && state.current_player().num_exchanges < 8
        {
            let mut toss: Vec<_> = rack
                .iter()
                .copied()
                .filter(|&t| self.score(t) >= 4)
                .collect();
            if toss.is_empty() {
                toss.extend(rack.iter().copied().filter(|&t| t != 0).take(3));
            }
            toss.truncate(state.bag.len());
            toss.sort_unstable();
            return Play::Exchange {
                tiles: toss.as_slice().into(),
            };
        }
        Play::Exchange {
            tiles: (&[] as &[u8]).into(),
        }
    }

    pub fn evaluation(&self, play: &Play, rack: &[u8], bag: usize) -> i32 {
        self.evaluate(play, rack, bag, BASE_EXPERT)
    }

    pub(crate) fn evaluate(
        &self,
        play: &Play,
        rack: &[u8],
        bag: usize,
        policy: ExpertPolicy,
    ) -> i32 {
        let Play::Place { word, score, .. } = play else {
            return 0;
        };
        let mut leave = [0i32; 64];
        for &tile in rack {
            leave[tile as usize] += 1;
        }
        for &tile in word.iter().filter(|&&t| t != 0) {
            leave[if tile >= 128 { 0 } else { tile as usize }] -= 1;
        }
        let points = equity::descale_score(*score);
        let n: i32 = leave.iter().sum();
        if bag == 0 {
            let residue: i32 = (1..self.symbols.len())
                .map(|t| self.score(t as u8) * leave[t])
                .sum();
            return 4 * points
                + if n == 0 {
                    160
                } else {
                    -policy.end_penalty * residue
                };
        }
        const KEEP: [i32; 27] = [
            23, 2, -4, -3, -1, 3, -6, -4, -5, 2, -9, -12, 1, -2, 2, 0, -3, -14, 3, 9, 2, -4, -7,
            -13, -5, -12, -9,
        ];
        let letters: i32 = if self.profile.language == "fr" {
            leave.iter().zip(KEEP).map(|(n, k)| n * k).sum::<i32>()
                + leave[0] * (policy.blank_weight - 23)
                + leave[19] * (policy.s_weight - 9)
        } else {
            (1..self.symbols.len())
                .map(|i| leave[i] * (3 - self.score(i as u8)))
                .sum::<i32>()
                + leave[0] * policy.blank_weight
        };
        let doubles: i32 = leave.iter().skip(1).map(|x| (x - 1).max(0)).sum();
        let vowels: i32 = if self.profile.language == "fr" {
            [1, 5, 9, 15, 21].iter().map(|&t| leave[t]).sum()
        } else {
            (1..self.symbols.len())
                .filter(|&i| self.config.alphabet().is_vowel(i as u8))
                .map(|i| leave[i])
                .sum()
        };
        let imbalance = (2 * vowels - (n - leave[0])).abs().saturating_sub(1);
        4 * points + policy.leave_scale * (letters - 5 * doubles - 4 * imbalance) / 100
            - policy.turnover * n
    }

    pub fn simulate(
        &self,
        generator: &mut KurniaMoveGenerator,
        state: &mut GameState,
        id: u32,
        seed: u32,
    ) -> Result<Game> {
        let index = id - 1;
        let pair = PAIRS[(index / 2 % PAIRS.len() as u32) as usize];
        let levels = if index.is_multiple_of(2) {
            [pair.0, pair.1]
        } else {
            [pair.1, pair.0]
        };
        self.play_seeded(
            generator,
            state,
            id,
            seed.wrapping_add(index),
            levels,
            [BASE_EXPERT; 2],
        )
    }

    pub fn play_seeded(
        &self,
        generator: &mut KurniaMoveGenerator,
        state: &mut GameState,
        id: u32,
        seed: u32,
        levels: [u8; 2],
        policies: [ExpertPolicy; 2],
    ) -> Result<Game> {
        let mut rng = Xoshiro256PlusPlus::seed_from_u64(u64::from(seed));
        state.reset_and_draw_tiles(&self.config, &mut rng);
        let mut moves = Vec::with_capacity(30);
        let mut end = None;
        let mut outgoing = None;
        for turn in 1..=200 {
            let player = state.turn;
            let level = levels[player as usize];
            let policy = policies[player as usize];
            self.generate(
                generator,
                &state.board_tiles,
                &state.current_player().rack,
                if level == 3 {
                    policy.width + 2
                } else {
                    [18, 10, 5][level as usize]
                },
            );
            let play = self.choose(generator, state, level, policy, &mut rng);
            let (word, tiles, direction) = self.describe(&state.board_tiles, &play);
            let (kind, score, exchanged) = match &play {
                Play::Place { score, .. } => {
                    (MoveKind::Play, equity::descale_score(*score), String::new())
                }
                Play::Exchange { tiles } if tiles.is_empty() => (MoveKind::Pass, 0, String::new()),
                Play::Exchange { tiles } => (MoveKind::Exchange, 0, self.rack_string(tiles)),
            };
            moves.push(Move {
                turn,
                player,
                kind,
                word,
                score,
                tiles,
                rack: self.rack_string(&state.current_player().rack),
                exchanged,
                bag_before: state.bag.len(),
                direction,
            });
            state
                .play(&self.config, &mut rng, &play)
                .map_err(|e| anyhow!("{e}"))?;
            if state.bag.is_empty() && state.current_player().rack.is_empty() {
                outgoing = Some(player as usize);
                end = Some(End::RackOut);
                break;
            }
            if state.scoreless_turns_ended(&self.config) {
                end = Some(End::Passes);
                break;
            }
            state.next_turn();
        }
        let end = end.with_context(|| format!("partie {id} sans fin après 200 tours"))?;
        let alphabet: &alphabet::Alphabet = self.config.alphabet();
        let residue: [i32; 2] =
            std::array::from_fn(|i| alphabet.rack_score(&state.players[i].rack));
        let mut adjustments = [-residue[0], -residue[1]];
        if let Some(i) = outgoing {
            let points = residue.iter().sum::<i32>();
            match self
                .profile
                .rules
                .as_ref()
                .expect("profil validé")
                .rack_out_scoring
            {
                RackOutScoring::TransferResidue => adjustments[i] += points,
                RackOutScoring::DoubleResidue => {
                    adjustments = [0; 2];
                    adjustments[i] = 2 * points;
                }
            }
        }
        let scores =
            std::array::from_fn(|i| equity::descale_score(state.players[i].score) + adjustments[i]);
        let winner = match scores[0].cmp(&scores[1]) {
            std::cmp::Ordering::Greater => Some(0),
            std::cmp::Ordering::Less => Some(1),
            std::cmp::Ordering::Equal => None,
        };
        Ok(Game {
            id,
            seed,
            levels,
            scores,
            adjustments,
            winner,
            end,
            profile: if self.profile.language == "fr" {
                String::new()
            } else {
                self.profile.id.clone()
            },
            turns: moves.len(),
            moves,
            final_racks: std::array::from_fn(|i| self.rack_string(&state.players[i].rack)),
        })
    }
}
