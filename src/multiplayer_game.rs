use crate::{
    engine::{BASE_EXPERT, End, Engine, ExpertPolicy, LEVELS, MoveKind, Tile},
    import::MAX_TURNS,
    profiles::RackOutScoring,
};
use rand::{Rng, RngExt, SeedableRng};
use rand_xoshiro::Xoshiro256PlusPlus;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use wolges::{
    equity,
    game_config::GameConfig,
    game_state::{GamePlayer, GameState},
    movegen::{GenMovesParams, KurniaMoveGenerator, PassPolicy, Play},
    play_scorer::PlayScorer,
};

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Action {
    Play { placements: Vec<Placement> },
    Exchange { tiles: Vec<usize> },
    Pass,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum BotLevel {
    Discovery,
    Initiated,
    Strategist,
    Expert,
    Adaptive,
}

impl BotLevel {
    pub fn label(self) -> &'static str {
        match self.fixed_level() {
            Some(level) => LEVELS[usize::from(level)],
            None => "Adaptatif",
        }
    }

    fn fixed_level(self) -> Option<u8> {
        match self {
            Self::Discovery => Some(0),
            Self::Initiated => Some(1),
            Self::Strategist => Some(2),
            Self::Expert => Some(3),
            Self::Adaptive => None,
        }
    }
}

#[derive(Deserialize, Serialize)]
pub struct Placement {
    x: i8,
    y: i8,
    tile: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    letter: Option<char>,
}

#[derive(Serialize)]
pub struct PlacementHint {
    word: String,
    score: i32,
    placements: Vec<Placement>,
    anchors: Vec<BoardPosition>,
    direction: &'static str,
}

#[derive(Serialize)]
struct BoardPosition {
    x: i8,
    y: i8,
}

const HINT_COUNT: usize = 12;
const HINT_CANDIDATES: usize = 128;

#[derive(Debug)]
pub struct PlacementPreview {
    pub score: i32,
    pub words: Vec<String>,
}

#[derive(Serialize, Deserialize)]
pub struct Game {
    board: Box<[u8]>,
    players: Vec<Player>,
    bag: Vec<u8>,
    turn: u8,
    pass_turns: u16,
    zero_turns: u16,
    turn_number: u32,
    end: Option<End>,
    last_move: Option<LastMove>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    history: Vec<ReplayMove>,
}

#[derive(Serialize, Deserialize)]
struct Player {
    score: i32,
    rack: Vec<u8>,
    #[serde(default)]
    num_exchanges: u32,
    #[serde(default)]
    rack_revision: u32,
}

#[derive(Serialize)]
pub struct GameView {
    board: Vec<BoardTile>,
    rack: Vec<RackTile>,
    rack_revision: u32,
    rack_counts: Vec<usize>,
    scores: Vec<i32>,
    turn: Option<u8>,
    turn_number: u32,
    remaining: usize,
    status: &'static str,
    end: Option<End>,
    winners: Vec<usize>,
    last_move: Option<LastMove>,
    replay_available: bool,
}

#[derive(Clone, Serialize, Deserialize)]
struct BoardTile {
    x: u8,
    y: u8,
    letter: char,
    blank: bool,
}

#[derive(Serialize)]
struct RackTile {
    letter: char,
    value: i32,
    blank: bool,
}

#[derive(Clone, Serialize, Deserialize)]
struct LastMove {
    #[serde(rename = "type")]
    kind: MoveKind,
    seat: usize,
    word: Option<String>,
    score: i32,
    exchanged: usize,
    tiles: Vec<BoardTile>,
}

#[derive(Serialize, Deserialize)]
struct ReplayMove {
    turn: u32,
    player: usize,
    #[serde(rename = "type")]
    kind: MoveKind,
    word: Option<String>,
    score: i32,
    exchange_count: usize,
    tiles: Vec<Tile>,
}

#[derive(Serialize)]
pub struct Replay<'a> {
    end: End,
    scores: Vec<i32>,
    adjustments: Vec<i32>,
    moves: &'a [ReplayMove],
}

impl Game {
    pub(crate) fn score_position(
        engine: &Engine,
        board: &[u8],
        rack: &[u8],
        placements: &[(usize, usize, char)],
        excluded: Option<&dyn Fn(&[u8]) -> bool>,
    ) -> Result<i32, String> {
        let config = engine.profile.game_config(2).map_err(|e| e.to_string())?;
        let mut state = GameState::new(&config);
        if board.len() != state.board_tiles.len() {
            return Err("Plateau incompatible.".into());
        }
        state.board_tiles.copy_from_slice(board);
        state.players[0].rack.extend_from_slice(rack);
        let placements = placements
            .iter()
            .map(|&(position, tile, letter)| Placement {
                x: (position % 15) as i8,
                y: (position / 15) as i8,
                tile,
                letter: Some(letter),
            })
            .collect::<Vec<_>>();
        match placement_play_with_filter(engine, &config, &state, &placements, excluded)? {
            Play::Place { score, .. } => Ok(equity::descale_score(score)),
            _ => unreachable!("un placement produit un coup de pose"),
        }
    }

    pub fn new(engine: &Engine, players: usize, seed: u64) -> anyhow::Result<Self> {
        let config = engine.profile.game_config(u8::try_from(players)?)?;
        let mut rng = Xoshiro256PlusPlus::seed_from_u64(seed);
        let mut state = GameState::new(&config);
        state.reset_and_draw_tiles(&config, &mut rng);
        state.turn = rng.random_range(0..config.num_players());
        Ok(Self {
            board: state.board_tiles,
            players: players_from_state(state.players),
            bag: state.bag.as_slice().to_vec(),
            turn: state.turn,
            pass_turns: 0,
            zero_turns: 0,
            turn_number: 0,
            end: None,
            last_move: None,
            history: Vec::new(),
        })
    }

    pub fn current_seat(&self) -> Option<usize> {
        self.end.is_none().then_some(self.turn as usize)
    }

    fn has_complete_history(&self) -> bool {
        self.history.len() == self.turn_number as usize
    }

    fn scores(&self) -> Vec<i32> {
        self.players
            .iter()
            .map(|player| equity::descale_score(player.score))
            .collect()
    }

    pub fn replay(&self) -> Option<Replay<'_>> {
        let end = self.end?;
        if self.history.is_empty() || !self.has_complete_history() {
            return None;
        }
        let scores = self.scores();
        let mut adjustments = scores.clone();
        for played in &self.history {
            adjustments[played.player] -= played.score;
        }
        Some(Replay {
            end,
            scores,
            adjustments,
            moves: &self.history,
        })
    }

    pub fn view(&self, engine: &Engine, seat: usize) -> GameView {
        let dim = engine.config.board_layout().dim();
        let board = self
            .board
            .iter()
            .enumerate()
            .filter(|&(_, &tile)| tile != 0)
            .map(|(index, &tile)| BoardTile {
                x: (index % dim.cols as usize) as u8,
                y: (index / dim.cols as usize) as u8,
                letter: engine.letter(tile),
                blank: tile & 0x80 != 0,
            })
            .collect();
        let rack = self
            .players
            .get(seat)
            .into_iter()
            .flat_map(|player| &player.rack)
            .map(|&tile| RackTile {
                letter: engine.letter(tile),
                value: engine.score(tile),
                blank: tile == 0,
            })
            .collect();
        let scores = self.scores();
        let winners = if self.end.is_some() {
            let top = scores.iter().max();
            scores
                .iter()
                .enumerate()
                .filter_map(|(seat, score)| (Some(score) == top).then_some(seat))
                .collect()
        } else {
            Vec::new()
        };
        GameView {
            board,
            rack,
            rack_revision: self
                .players
                .get(seat)
                .map_or(0, |player| player.rack_revision),
            rack_counts: self
                .players
                .iter()
                .map(|player| player.rack.len())
                .collect(),
            scores,
            turn: self.end.is_none().then_some(self.turn),
            turn_number: self.turn_number,
            remaining: self.bag.len(),
            status: if self.end.is_some() {
                "finished"
            } else {
                "active"
            },
            end: self.end,
            winners,
            last_move: self.last_move.clone(),
            replay_available: self.end.is_some()
                && !self.history.is_empty()
                && self.has_complete_history(),
        }
    }

    pub fn apply(&mut self, engine: &Engine, seat: usize, action: Action) -> Result<(), String> {
        let config = self.turn_config(engine, seat)?;
        let state = self.to_state(&config);
        let rack = &state.current_player().rack;
        let play = match action {
            Action::Play { placements } => placement_play(engine, &config, &state, &placements)?,
            Action::Exchange { tiles } => {
                if state.bag.len() < config.exchange_tile_limit() as usize {
                    return Err("Il faut au moins sept lettres dans le sac pour échanger.".into());
                }
                if tiles.is_empty() {
                    return Err("Sélectionnez les lettres à échanger.".into());
                }
                Play::Exchange {
                    tiles: selected_tiles(rack, tiles.into_iter())?.as_slice().into(),
                }
            }
            Action::Pass => Play::Exchange {
                tiles: (&[][..]).into(),
            },
        };
        self.commit_play(engine, &config, state, &play)
    }

    pub fn preview_placement(
        &self,
        engine: &Engine,
        seat: usize,
        placements: &[Placement],
    ) -> Result<PlacementPreview, String> {
        let config = self.turn_config(engine, seat)?;
        let state = self.to_state(&config);
        let play = placement_play(engine, &config, &state, placements)?;
        let Play::Place { score, .. } = &play else {
            unreachable!("un placement produit un coup de pose")
        };
        let mut snapshot = engine.snapshot(&state.board_tiles);
        snapshot.game_config = &config;
        let mut words = Vec::new();
        PlayScorer::new().words_all(&snapshot, &play, |word| {
            words.push(word.iter().map(|&tile| engine.letter(tile)).collect());
            true
        });
        Ok(PlacementPreview {
            score: equity::descale_score(*score),
            words,
        })
    }

    pub fn placement_hints(
        &self,
        engine: &Engine,
        seat: usize,
    ) -> Result<Vec<PlacementHint>, String> {
        let config = self.turn_config(engine, seat)?;
        let rack = &self.players[seat].rack;
        let mut snapshot = engine.snapshot(&self.board);
        snapshot.game_config = &config;
        let mut generator = KurniaMoveGenerator::new(&config);
        generator.gen_moves_unfiltered_lean(&GenMovesParams {
            board_snapshot: &snapshot,
            rack,
            max_gen: HINT_CANDIDATES,
            num_exchanges_by_this_player: i16::MAX, // Hints suggest placements only.
            pass_policy: PassPolicy::OnlyWhenForced,
            dynamic_leaves: None,
        });
        let state = self.to_state(&config);
        let mut hints = Vec::with_capacity(HINT_COUNT);
        let mut seen = HashSet::with_capacity(HINT_COUNT);
        for candidate in generator.plays {
            if !matches!(candidate.play, Play::Place { .. }) {
                continue;
            }
            let hint = hint_for_play(engine, &config, &state, &candidate.play)?;
            if seen.insert(hint.word.clone()) {
                hints.push(hint);
                if hints.len() == HINT_COUNT {
                    break;
                }
            }
        }
        Ok(hints)
    }

    pub fn play_bot(
        &mut self,
        engine: &Engine,
        seat: usize,
        level: BotLevel,
    ) -> Result<(), String> {
        let config = self.turn_config(engine, seat)?;
        let play = self.choose_bot(engine, &config, level, &mut rand::rng());
        let state = self.to_state(&config);
        self.commit_play(engine, &config, state, &play)
    }

    fn turn_config(&self, engine: &Engine, seat: usize) -> Result<GameConfig, String> {
        if self.end.is_some() {
            return Err("La partie est terminée.".into());
        }
        if seat != self.turn as usize {
            return Err("Ce n’est pas votre tour.".into());
        }
        engine
            .profile
            .game_config(self.players.len() as u8)
            .map_err(|_| "Règles du salon indisponibles.".into())
    }

    fn choose_bot(
        &self,
        engine: &Engine,
        config: &GameConfig,
        level: BotLevel,
        rng: &mut impl Rng,
    ) -> Play {
        // This selection state contains only the bot's own rack and public information.
        let mut state = GameState::new(config);
        state.board_tiles.clone_from(&self.board);
        state.turn = self.turn;
        state
            .bag
            .set_from_iter(std::iter::repeat_n(0, self.bag.len()));
        for (public, player) in state.players.iter_mut().zip(&self.players) {
            public.score = player.score;
        }
        let bot = &self.players[usize::from(self.turn)];
        let own = &mut state.players[usize::from(self.turn)];
        own.rack.clone_from(&bot.rack);
        own.num_exchanges = bot.num_exchanges.min(i16::MAX as u32) as i16;

        let (tier, policy) = if let Some(tier) = level.fixed_level() {
            let trained = if tier == 3 {
                crate::server::model_policy(&engine.digest)
            } else {
                BASE_EXPERT
            };
            let policy = if (1..=256).contains(&trained.width) {
                trained
            } else {
                BASE_EXPERT
            };
            (tier, policy)
        } else {
            self.adaptive_target(engine, &state, rng)
                .unwrap_or((1, BASE_EXPERT))
        };
        let mut snapshot = engine.snapshot(&state.board_tiles);
        snapshot.game_config = config;
        let mut generator = KurniaMoveGenerator::new(config);
        generator.gen_moves_unfiltered_lean(&GenMovesParams {
            board_snapshot: &snapshot,
            rack: &state.current_player().rack,
            max_gen: [18, 10, 5, policy.width + 2, 32][usize::from(tier)],
            num_exchanges_by_this_player: state.current_player().num_exchanges,
            pass_policy: PassPolicy::OnlyWhenForced,
            dynamic_leaves: None,
        });
        engine.choose(&generator, &state, tier, policy, rng)
    }

    fn adaptive_target(
        &self,
        engine: &Engine,
        state: &GameState,
        rng: &mut impl Rng,
    ) -> Option<(u8, ExpertPolicy)> {
        let (scale, calibration) =
            crate::server::strength(engine, &serde_json::Value::Null).ok()?;
        let adjustment = if self.turn_number >= (state.players.len() * 3) as u32 {
            let leading_opponent = state
                .players
                .iter()
                .enumerate()
                .filter(|&(seat, _)| seat != usize::from(state.turn))
                .map(|(_, player)| player.score)
                .max()
                .unwrap_or(0);
            let gap = equity::descale_score(leading_opponent - state.current_player().score);
            (gap * 3).clamp(-225, 225)
        } else {
            0
        };
        let target = f64::from((scale.anchors[1] + scale.anchors[2]) / 2 + adjustment);
        let strength = calibration
            .as_ref()
            .and_then(|calibration| scale.at_calibrated(target, calibration).ok())
            .or_else(|| scale.at(target).ok())?;
        Some(strength.pick(rng))
    }

    fn commit_play(
        &mut self,
        engine: &Engine,
        config: &GameConfig,
        mut state: GameState,
        play: &Play,
    ) -> Result<(), String> {
        let seat = usize::from(state.turn);
        let (word, tiles, _) = engine.describe(&state.board_tiles, play);
        let (kind, score, exchanged) = match play {
            Play::Place { score, .. } => (MoveKind::Play, equity::descale_score(*score), 0),
            Play::Exchange { tiles } if tiles.is_empty() => (MoveKind::Pass, 0, 0),
            Play::Exchange { tiles } => (MoveKind::Exchange, 0, tiles.len()),
        };
        state
            .play(config, &mut rand::rng(), play)
            .map_err(|_| "Impossible d’appliquer ce coup.")?;
        if kind == MoveKind::Exchange {
            self.players[seat].num_exchanges += 1;
        }
        let end = if state.bag.is_empty() && state.current_player().rack.is_empty() {
            Some(End::RackOut)
        } else if state.scoreless_turns_ended(config) {
            Some(End::Passes)
        } else {
            None
        };
        if let Some(end) = end {
            finish(engine, &mut state, end);
        } else {
            state.next_turn();
        }
        self.board = state.board_tiles;
        for (player, updated) in self.players.iter_mut().zip(state.players.into_vec()) {
            player.score = updated.score;
            player.rack = updated.rack;
        }
        self.bag = state.bag.as_slice().to_vec();
        self.turn = state.turn;
        self.pass_turns = state.pass_turns;
        self.zero_turns = state.zero_turns;
        let record = self.has_complete_history() && self.history.len() < MAX_TURNS;
        self.turn_number += 1;
        if kind != MoveKind::Pass {
            self.players[seat].rack_revision = self.turn_number;
        }
        self.end = end;
        let word = (!word.is_empty()).then_some(word);
        self.last_move = Some(LastMove {
            kind,
            seat,
            word: word.clone(),
            score,
            exchanged,
            tiles: tiles
                .iter()
                .map(|tile| BoardTile {
                    x: tile.col,
                    y: tile.row,
                    letter: tile.letter,
                    blank: tile.blank,
                })
                .collect(),
        });
        if record {
            self.history.push(ReplayMove {
                turn: self.turn_number,
                player: seat,
                kind,
                word,
                score,
                exchange_count: exchanged,
                tiles,
            });
        } else {
            self.history.clear();
        }
        Ok(())
    }

    fn to_state(&self, config: &GameConfig) -> GameState {
        let mut bag = wolges::bag::Bag::new(config.alphabet());
        bag.set_from_iter(self.bag.iter().copied());
        GameState {
            board_tiles: self.board.clone(),
            players: self
                .players
                .iter()
                .map(|player| GamePlayer {
                    score: player.score,
                    rack: player.rack.clone(),
                    num_exchanges: 0,
                })
                .collect(),
            bag,
            turn: self.turn,
            pass_turns: self.pass_turns,
            zero_turns: self.zero_turns,
        }
    }
}

fn players_from_state(players: Box<[GamePlayer]>) -> Vec<Player> {
    players
        .into_vec()
        .into_iter()
        .map(|player| Player {
            score: player.score,
            rack: player.rack,
            num_exchanges: 0,
            rack_revision: 0,
        })
        .collect()
}

fn selected_tiles(rack: &[u8], indices: impl Iterator<Item = usize>) -> Result<Vec<u8>, String> {
    let mut used = vec![false; rack.len()];
    indices
        .map(|index| {
            if index >= rack.len() || std::mem::replace(&mut used[index], true) {
                return Err("Cette lettre du chevalet est indisponible.".into());
            }
            Ok(rack[index])
        })
        .collect()
}

fn hint_for_play(
    engine: &Engine,
    config: &GameConfig,
    state: &GameState,
    candidate: &Play,
) -> Result<PlacementHint, String> {
    let rack = &state.current_player().rack;
    let (_, tiles, _) = engine.describe(&state.board_tiles, candidate);
    let mut used = vec![false; rack.len()];
    let mut placements = Vec::with_capacity(tiles.len());
    for tile in tiles {
        let rack_tile = if tile.blank {
            0
        } else {
            engine
                .symbol_id(tile.letter)
                .map_err(|error| error.to_string())?
        };
        let rack_index = rack
            .iter()
            .zip(&used)
            .position(|(&letter, &used)| !used && letter == rack_tile)
            .ok_or("La lettre suggérée est indisponible sur le chevalet.")?;
        used[rack_index] = true;
        placements.push(Placement {
            x: tile.col as i8,
            y: tile.row as i8,
            tile: rack_index,
            letter: tile.blank.then_some(tile.letter),
        });
    }
    let play = placement_play(engine, config, state, &placements)?;
    let (word, _, _) = engine.describe(&state.board_tiles, &play);
    let Play::Place {
        down,
        lane,
        idx,
        word: tiles,
        score,
    } = play
    else {
        unreachable!("un placement produit un coup de pose")
    };
    let anchors = (idx..)
        .zip(tiles.iter())
        .filter_map(|(offset, &tile)| {
            (tile == 0).then_some(if down {
                BoardPosition { x: lane, y: offset }
            } else {
                BoardPosition { x: offset, y: lane }
            })
        })
        .collect();
    Ok(PlacementHint {
        word,
        score: equity::descale_score(score),
        placements,
        anchors,
        direction: if down { "vertical" } else { "horizontal" },
    })
}

fn placement_play(
    engine: &Engine,
    config: &GameConfig,
    state: &GameState,
    placements: &[Placement],
) -> Result<Play, String> {
    placement_play_with_filter(engine, config, state, placements, None)
}

fn placement_play_with_filter(
    engine: &Engine,
    config: &GameConfig,
    state: &GameState,
    placements: &[Placement],
    excluded: Option<&dyn Fn(&[u8]) -> bool>,
) -> Result<Play, String> {
    let Some(first) = placements.first() else {
        return Err("Posez des lettres sur le plateau.".into());
    };
    let rack_tiles = selected_tiles(
        &state.current_player().rack,
        placements.iter().map(|placement| placement.tile),
    )?;
    let mut snapshot = engine.snapshot(&state.board_tiles);
    snapshot.game_config = config;
    let dim = snapshot.game_config.board_layout().dim();
    let mut pending = vec![0; state.board_tiles.len()];
    for (placement, tile) in placements.iter().zip(rack_tiles) {
        if !(0..dim.cols).contains(&placement.x) || !(0..dim.rows).contains(&placement.y) {
            return Err("Cette case est hors du plateau.".into());
        }
        let pos = dim.at_row_col(placement.y, placement.x);
        if state.board_tiles[pos] != 0 || pending[pos] != 0 {
            return Err("Cette case est déjà occupée.".into());
        }
        pending[pos] = if tile == 0 {
            let letter = placement
                .letter
                .and_then(|letter| engine.symbol_id(letter.to_ascii_uppercase()).ok())
                .filter(|&letter| letter != 0)
                .ok_or("Choisissez la lettre représentée par le joker.")?;
            letter | 0x80
        } else {
            if placement
                .letter
                .is_some_and(|letter| letter.to_ascii_uppercase() != engine.letter(tile))
            {
                return Err("La lettre posée doit correspondre à celle du chevalet.".into());
            }
            tile
        };
    }
    let across = placements.iter().all(|placement| placement.y == first.y);
    let down = !across
        || (placements.len() == 1
            && (first.x == 0 || state.board_tiles[dim.at_row_col(first.y, first.x - 1)] == 0)
            && (first.x + 1 == dim.cols
                || state.board_tiles[dim.at_row_col(first.y, first.x + 1)] == 0));
    if down && !placements.iter().all(|placement| placement.x == first.x) {
        return Err("Alignez les nouvelles lettres.".into());
    }

    let (lane, along): (i8, fn(&Placement) -> i8) = if down {
        (first.x, |placement| placement.y)
    } else {
        (first.y, |placement| placement.x)
    };
    let line = dim.lane(down, lane);
    let mut start = placements
        .iter()
        .map(along)
        .min()
        .expect("placement non vide");
    let mut end = placements
        .iter()
        .map(along)
        .max()
        .expect("placement non vide");
    while start > 0 && state.board_tiles[line.at(start - 1)] != 0 {
        start -= 1;
    }
    while end + 1 < line.len() && state.board_tiles[line.at(end + 1)] != 0 {
        end += 1;
    }
    if start == end {
        return Err("Formez un mot d’au moins deux lettres.".into());
    }
    let mut word = Vec::with_capacity((end - start + 1) as usize);
    for index in start..=end {
        let pos = line.at(index);
        if pending[pos] == 0 && state.board_tiles[pos] == 0 {
            return Err("Comblez les trous entre les lettres.".into());
        }
        word.push(pending[pos]);
    }
    let mut play = Play::Place {
        down,
        lane,
        idx: start,
        word: word.as_slice().into(),
        score: 0,
    };
    let mut scorer = PlayScorer::new();
    if let Some(canonical) = scorer
        .validate_play(&snapshot, state, &play)
        .map_err(|error| match error.to_string().as_str() {
            "word does not cover starting square" => {
                "Le premier mot doit couvrir l’étoile centrale."
            }
            "word is detached from existing tiles" => "Reliez le mot aux lettres déjà posées.",
            _ => "Ce placement est invalide.",
        })?
    {
        play = canonical;
    }
    let mut invalid = None;
    scorer.find_invalid_words(&snapshot, &play, |word| {
        if invalid.is_none() {
            invalid = Some(
                word.iter()
                    .map(|&tile| engine.letter(tile))
                    .collect::<String>(),
            );
        }
    });
    if let Some(excluded) = excluded.filter(|_| invalid.is_none()) {
        scorer.words_all(&snapshot, &play, |word| {
            if excluded(word) {
                invalid = Some(word.iter().map(|&tile| engine.letter(tile)).collect());
                false
            } else {
                true
            }
        });
    }
    if let Some(word) = invalid {
        return Err(format!("« {word} » est absent du lexique."));
    }
    let computed = scorer.compute_score(&snapshot, &play);
    if let Play::Place { score, .. } = &mut play {
        *score = computed;
    }
    Ok(play)
}

fn finish(engine: &Engine, state: &mut GameState, end: End) {
    // The French profile transfers the residue at every table size, including duels.
    let alphabet = engine.config.alphabet();
    let total: i32 = state
        .players
        .iter()
        .map(|player| alphabet.scaled_rack_score(&player.rack))
        .sum();
    let outgoing = &engine
        .profile
        .rules
        .as_ref()
        .expect("profil validé")
        .rack_out_scoring;
    if end == End::RackOut && *outgoing == RackOutScoring::DoubleResidue {
        state.players[state.turn as usize].score += 2 * total;
    } else {
        for player in &mut state.players {
            player.score -= alphabet.scaled_rack_score(&player.rack);
        }
        if end == End::RackOut {
            state.players[state.turn as usize].score += total;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::OnceLock;

    fn engine() -> &'static Engine {
        static ENGINE: OnceLock<Engine> = OnceLock::new();
        ENGINE.get_or_init(|| {
            let profile = crate::profiles::load("fr-classic-v1").unwrap();
            let path = profile.lexicon.path.clone().unwrap();
            let name = profile.lexicon.name.clone();
            Engine::load(&path, name, profile).unwrap()
        })
    }

    fn rack(letters: &str) -> Vec<u8> {
        letters
            .chars()
            .map(|letter| engine().symbol_id(letter).unwrap())
            .collect()
    }

    fn placement(x: i8, y: i8, tile: usize, letter: Option<char>) -> Placement {
        Placement { x, y, tile, letter }
    }

    #[test]
    fn finished_replay_roundtrips_and_legacy_rooms_keep_playing() {
        let engine = engine();
        let mut game = Game::new(engine, 2, 7).unwrap();
        game.turn = 0;
        game.players[0].rack = rack("MO?");
        game.apply(
            engine,
            0,
            Action::Play {
                placements: vec![
                    placement(7, 7, 0, None),
                    placement(8, 7, 1, None),
                    placement(9, 7, 2, Some('T')),
                ],
            },
        )
        .unwrap();
        assert!(game.replay().is_none());
        let snapshot = serde_json::to_value(&game).unwrap();
        let mut legacy_snapshot = snapshot.clone();
        legacy_snapshot.as_object_mut().unwrap().remove("history");
        let mut legacy: Game = serde_json::from_value(legacy_snapshot).unwrap();
        let mut restored: Game = serde_json::from_value(snapshot).unwrap();
        for finished in [&mut restored, &mut legacy] {
            while let Some(seat) = finished.current_seat() {
                finished.apply(engine, seat, Action::Pass).unwrap();
            }
        }
        assert!(legacy.replay().is_none());
        let replay = restored.replay().unwrap();
        assert_eq!(replay.moves.len(), 7);
        assert_eq!(replay.adjustments, [replay.scores[0] - 6, replay.scores[1]]);
        let mut file = serde_json::to_value(replay).unwrap();
        assert!(file["moves"][0]["tiles"][2]["blank"].as_bool().unwrap());
        assert!(file["moves"][0].get("rack").is_none());
        file["schema"] = serde_json::json!("lettrine.game.v1");
        file["source"] = serde_json::json!("web");
        let imported = crate::import::prepare(&serde_json::to_vec(&file).unwrap()).unwrap();
        assert_eq!(imported[0].1["origin"], "web");
    }

    #[test]
    fn bots_follow_human_turns_without_using_hidden_tiles() {
        let engine = engine();
        let mut game = Game::new(engine, 4, 70).unwrap();
        game.turn = 0;
        game.players[0].rack = rack("AS");
        game.players[1].rack = rack("TTERONU");
        game.apply(
            engine,
            0,
            Action::Play {
                placements: vec![placement(7, 7, 0, None), placement(8, 7, 1, None)],
            },
        )
        .unwrap();
        assert!(game.play_bot(engine, 2, BotLevel::Expert).is_err());
        assert_eq!(game.current_seat(), Some(1));

        let mut other: Game = serde_json::from_value(serde_json::to_value(&game).unwrap()).unwrap();
        std::mem::swap(&mut other.bag[0], &mut other.players[0].rack[0]);
        std::mem::swap(&mut other.bag[1], &mut other.players[2].rack[0]);
        other.bag.reverse();
        for (seat, player) in other.players.iter_mut().enumerate() {
            if seat != 1 {
                player.rack.reverse();
            }
        }
        let config = game.turn_config(engine, 1).unwrap();
        for level in [
            BotLevel::Discovery,
            BotLevel::Initiated,
            BotLevel::Strategist,
            BotLevel::Expert,
            BotLevel::Adaptive,
        ] {
            let choose = |game: &Game| {
                let mut rng = Xoshiro256PlusPlus::seed_from_u64(701);
                game.choose_bot(engine, &config, level, &mut rng)
            };
            let play = choose(&game);
            assert!(play == choose(&other));
            assert!(matches!(play, Play::Place { .. }));
        }
        game.play_bot(engine, 1, BotLevel::Adaptive).unwrap();
        assert_eq!(game.current_seat(), Some(2));
        assert_eq!(game.turn_number, 2);
        assert!(game.players[1].score > 0);
        let last = game.last_move.unwrap();
        assert_eq!(last.seat, 1);
        assert_eq!(last.kind, MoveKind::Play);
    }

    #[test]
    fn bot_exchange_limit_survives_persistence_and_leads_to_a_pass() {
        let engine = engine();
        let mut game = Game::new(engine, 2, 81).unwrap();
        game.turn = 1;
        game.players[1].rack = rack("Q");
        game.players[1].num_exchanges = 7;
        game.play_bot(engine, 1, BotLevel::Discovery).unwrap();
        assert_eq!(game.last_move.as_ref().unwrap().kind, MoveKind::Exchange);
        assert_eq!(game.players[1].num_exchanges, 8);
        let mut persisted = serde_json::to_value(&game).unwrap();
        persisted["players"][0]
            .as_object_mut()
            .unwrap()
            .remove("num_exchanges");
        let mut restored: Game = serde_json::from_value(persisted).unwrap();
        assert_eq!(restored.players[0].num_exchanges, 0);
        restored.apply(engine, 0, Action::Pass).unwrap();
        restored.players[1].rack = rack("Q");
        restored.play_bot(engine, 1, BotLevel::Discovery).unwrap();
        assert_eq!(restored.last_move.unwrap().kind, MoveKind::Pass);
    }

    #[test]
    fn rejects_invalid_placements_and_scores_blank_and_cross_words() {
        let engine = engine();
        let mut game = Game::new(engine, 3, 7).unwrap();
        game.turn = 0;
        game.players[0].rack = rack("MO?");
        game.players[1].rack = rack("AS");
        game.players[2].rack = rack("S");

        let shifted_opening = Action::Play {
            placements: vec![placement(7, 0, 0, None), placement(8, 0, 1, None)],
        };
        assert_eq!(
            game.apply(engine, 0, shifted_opening).unwrap_err(),
            "Le premier mot doit couvrir l’étoile centrale."
        );
        let forged_tile = Action::Play {
            placements: vec![placement(7, 7, 0, Some('Q')), placement(8, 7, 1, None)],
        };
        assert!(game.apply(engine, 0, forged_tile).is_err());
        assert_eq!(game.turn_number, 0);
        assert_eq!(game.players[0].rack, rack("MO?"));

        let opening = vec![
            placement(9, 7, 2, Some('T')),
            placement(7, 7, 0, None),
            placement(8, 7, 1, None),
        ];
        let before = serde_json::to_value(&game).unwrap();
        let preview = game.preview_placement(engine, 0, &opening).unwrap();
        assert_eq!(preview.words, ["MOT"]);
        assert_eq!(preview.score, 6);
        assert_eq!(serde_json::to_value(&game).unwrap(), before);
        game.apply(
            engine,
            0,
            Action::Play {
                placements: opening,
            },
        )
        .unwrap();
        let played = game.view(engine, 1);
        assert_eq!(played.scores, vec![6, 0, 0]);
        let last = played.last_move.unwrap();
        assert_eq!(last.word.as_deref(), Some("MOT"));
        assert!(last.tiles[2].blank);
        assert_eq!(last.tiles[2].letter, 'T');

        let invalid_cross = vec![placement(8, 8, 0, None), placement(9, 8, 1, None)];
        assert_eq!(
            game.preview_placement(engine, 1, &invalid_cross)
                .unwrap_err(),
            "« OA » est absent du lexique."
        );
        assert_eq!(
            game.apply(
                engine,
                1,
                Action::Play {
                    placements: invalid_cross
                }
            )
            .unwrap_err(),
            "« OA » est absent du lexique."
        );
        assert_eq!(game.current_seat(), Some(1));
        assert_eq!(game.view(engine, 1).board.len(), 3);

        let vertical = Action::Play {
            placements: vec![placement(7, 8, 0, None)],
        };
        game.apply(engine, 1, vertical).unwrap();
        assert_eq!(game.current_seat(), Some(2));
        assert_eq!(
            game.view(engine, 2).last_move.unwrap().word.as_deref(),
            Some("MA")
        );
        let across_and_down = vec![placement(8, 8, 0, None)];
        let preview = game.preview_placement(engine, 2, &across_and_down).unwrap();
        assert_eq!(preview.words, ["AS", "OS"]);
        assert_eq!(preview.score, 6);
        game.apply(
            engine,
            2,
            Action::Play {
                placements: across_and_down,
            },
        )
        .unwrap();
        let view = game.view(engine, 0);
        assert_eq!(view.scores, vec![6, 3, 6]);
        assert_eq!(view.last_move.unwrap().word.as_deref(), Some("AS"));
    }

    #[test]
    fn placement_preview_includes_bingo_bonus() {
        let engine = engine();
        let mut game = Game::new(engine, 2, 12).unwrap();
        game.turn = 0;
        game.players[0].rack = rack("BATEAUX");
        let placements: Vec<_> = (0..7)
            .map(|tile| placement(4 + tile as i8, 7, tile, None))
            .collect();
        let preview = game.preview_placement(engine, 0, &placements).unwrap();
        assert_eq!(preview.words, ["BATEAUX"]);
        assert_eq!(preview.score, 86);
        game.apply(engine, 0, Action::Play { placements }).unwrap();
        assert_eq!(game.last_move.unwrap().score, preview.score);
    }

    #[test]
    fn placement_hints_use_distinct_words_and_rack_indices_without_mutating_state() {
        let engine = engine();
        let mut game = Game::new(engine, 4, 19).unwrap();
        game.turn = 0;
        game.players[0].rack = rack("BANANE?");
        let before = serde_json::to_value(&game).unwrap();
        let hints = game.placement_hints(engine, 0).unwrap();
        assert_eq!(serde_json::to_value(&game).unwrap(), before);
        assert_eq!(hints.len(), HINT_COUNT);
        assert_eq!(
            hints
                .iter()
                .map(|hint| &hint.word)
                .collect::<HashSet<_>>()
                .len(),
            HINT_COUNT
        );
        for hint in &hints {
            let mut used = [false; 7];
            for placement in &hint.placements {
                assert!(!std::mem::replace(&mut used[placement.tile], true));
                assert_eq!(placement.letter.is_some(), placement.tile == 6);
            }
            let preview = game.preview_placement(engine, 0, &hint.placements).unwrap();
            assert_eq!(preview.words[0], hint.word);
            assert_eq!(preview.score, hint.score);
            assert!(hint.anchors.is_empty());
        }
        let hint = hints.first().unwrap();
        assert_eq!(hint.placements.len(), 7);
        let mut other: Game = serde_json::from_value(before).unwrap();
        other.players[1].rack = rack("ZZ");
        other.bag.clear();
        assert_eq!(
            serde_json::to_value(&hints).unwrap(),
            serde_json::to_value(other.placement_hints(engine, 0).unwrap()).unwrap()
        );
        assert!(game.placement_hints(engine, 1).is_err());
        let alternative = hints.into_iter().nth(1).unwrap();
        game.apply(
            engine,
            0,
            Action::Play {
                placements: alternative.placements,
            },
        )
        .unwrap();
        assert_eq!(game.last_move.unwrap().score, alternative.score);
    }

    #[test]
    fn placement_hints_include_the_existing_letters_of_the_main_word() {
        let engine = engine();
        let mut game = Game::new(engine, 2, 19).unwrap();
        game.turn = 0;
        game.players[0].rack = rack("MA");
        game.apply(
            engine,
            0,
            Action::Play {
                placements: vec![placement(7, 7, 0, None), placement(8, 7, 1, None)],
            },
        )
        .unwrap();
        game.players[1].rack = rack("T");
        let hints = game.placement_hints(engine, 1).unwrap();
        let mat = hints.iter().find(|hint| hint.word == "MAT").unwrap();
        assert_eq!(mat.direction, "horizontal");
        assert_eq!(
            mat.anchors
                .iter()
                .map(|tile| (tile.x, tile.y))
                .collect::<Vec<_>>(),
            [(7, 7), (8, 7)]
        );
        assert_eq!((mat.placements[0].x, mat.placements[0].y), (9, 7));
        assert!(hints.len() < HINT_COUNT);
    }

    #[test]
    fn placement_hints_return_empty_when_no_word_can_be_formed() {
        let engine = engine();
        let mut game = Game::new(engine, 2, 19).unwrap();
        game.turn = 0;
        game.players[0].rack = rack("Q");
        assert!(game.placement_hints(engine, 0).unwrap().is_empty());
    }

    #[test]
    fn round_trips_state_and_passes_rotate_for_all_supported_sizes() {
        let engine = engine();
        for player_count in 2..=4 {
            let mut game = Game::new(engine, player_count, 42).unwrap();
            let first = game.current_seat().unwrap();
            let hidden = serde_json::to_value(game.view(engine, first)).unwrap();
            assert!(hidden.get("bag").is_none() && hidden.get("players").is_none());
            assert_eq!(hidden["rack"].as_array().unwrap().len(), 7);
            assert_eq!(hidden["remaining"], 102 - 7 * player_count);
            assert_eq!(
                hidden["rack_counts"],
                serde_json::json!(vec![7; player_count])
            );
            assert!(
                game.apply(engine, (first + 1) % player_count, Action::Pass)
                    .is_err()
            );
            assert!(
                game.apply(engine, first, Action::Exchange { tiles: vec![0, 0] })
                    .is_err()
            );

            let inventory = |game: &Game| {
                let mut tiles = game.bag.clone();
                tiles.extend(game.players.iter().flat_map(|player| &player.rack));
                tiles.sort_unstable();
                tiles
            };
            let starting_tiles = inventory(&game);
            game.apply(engine, first, Action::Pass).unwrap();
            for count in 1..=2 * player_count {
                let seat = (first + count) % player_count;
                game.apply(engine, seat, Action::Exchange { tiles: vec![0, 1] })
                    .unwrap();
                let serialized = serde_json::to_string(&game).unwrap();
                game = serde_json::from_str(&serialized).unwrap();
                assert_eq!(game.pass_turns, 0);
                assert_eq!(game.bag.len(), 102 - 7 * player_count);
                assert_eq!(game.last_move.as_ref().unwrap().exchanged, 2);
                assert_eq!(inventory(&game), starting_tiles);
            }
            for count in 1..=3 * player_count {
                let seat = (first + count) % player_count;
                assert_eq!(game.current_seat(), Some(seat));
                game.apply(engine, seat, Action::Pass).unwrap();
            }
            assert_eq!(game.current_seat(), None);
            assert_eq!(game.end, Some(End::Passes));
            assert!(!game.view(engine, first).winners.is_empty());
            assert!(game.apply(engine, first, Action::Pass).is_err());
        }
    }

    #[test]
    fn exchange_with_exactly_seven_in_the_bag_draws_before_returning() {
        let engine = engine();
        let mut game = Game::new(engine, 2, 17).unwrap();
        game.turn = 1;
        game.bag = rack("EEEEEEE");
        game.players[1].rack = rack("AAAAAAA");
        game.apply(
            engine,
            1,
            Action::Exchange {
                tiles: (0..7).collect(),
            },
        )
        .unwrap();
        assert_eq!(game.players[1].rack, rack("EEEEEEE"));
        assert_eq!(game.bag, rack("AAAAAAA"));
    }

    #[test]
    fn rack_out_transfers_the_residue_at_each_supported_table_size() {
        let engine = engine();
        for player_count in 2..=4 {
            let mut game = Game::new(engine, player_count, 5).unwrap();
            game.turn = 0;
            game.bag.clear();
            game.players[0].rack = rack("AS");
            for player in &mut game.players[1..] {
                player.rack = rack("Q?");
            }
            assert!(
                game.apply(engine, 0, Action::Exchange { tiles: vec![0] })
                    .is_err()
            );
            game.apply(
                engine,
                0,
                Action::Play {
                    placements: vec![placement(7, 7, 0, None), placement(8, 7, 1, None)],
                },
            )
            .unwrap();
            assert_eq!(game.end, Some(End::RackOut));
            let view = game.view(engine, 0);
            assert_eq!(view.winners, vec![0]);
            assert_eq!(view.scores[0], 4 + 8 * (player_count as i32 - 1));
            assert_eq!(view.scores[1..], vec![-8; player_count - 1]);
        }
    }
}
