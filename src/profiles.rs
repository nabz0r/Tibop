use anyhow::{Context, Result, anyhow, bail, ensure};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::fmt::Write;
use std::path::{Path, PathBuf};
use wolges::{alphabet::Alphabet, board_layout, game_config};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Profile {
    pub schema_version: u8,
    pub id: String,
    pub language: String,
    pub name: String,
    pub status: ProfileStatus,
    pub board: Option<BoardSpec>,
    pub rules: Option<Rules>,
    pub orthography: Orthography,
    pub lexicon: LexiconSpec,
    pub tiles: Option<Vec<TileSpec>>,
    pub sources: Vec<Source>,
    pub notes: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ProfileStatus {
    Active,
    OptIn,
    Draft,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BoardSpec {
    pub size: u8,
    pub layout: String,
    pub center: [u8; 2],
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rules {
    pub rack_size: u8,
    pub min_word_tiles: u8,
    pub bingo_tiles: u8,
    pub bingo_bonus: i16,
    pub players: Vec<u8>,
    pub exchange_min_bag: i16,
    pub exchange_policy: ExchangePolicy,
    pub scoreless_end: ScorelessEnd,
    pub rack_out_scoring: RackOutScoring,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExchangePolicy {
    DrawThenReturn,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScorelessEnd {
    pub trigger: ScorelessTrigger,
    pub limit: u8,
    pub per_player: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ScorelessTrigger {
    Passes,
    ZeroTurns,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RackOutScoring {
    TransferResidue,
    DoubleResidue,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Orthography {
    pub script: String,
    pub normalization: Option<String>,
    pub segmentation: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LexiconSpec {
    pub id: String,
    pub name: String,
    pub license: Option<String>,
    pub status: String,
    pub path: Option<PathBuf>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TileSpec {
    pub symbol: String,
    pub count: u8,
    pub points: i8,
    pub vowel: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Source {
    pub title: String,
    pub url: String,
}

fn directory() -> PathBuf {
    std::env::var_os("LETTRINE_PROFILES_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("data/profiles"))
}

fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
}

pub fn load(id: &str) -> Result<Profile> {
    ensure!(valid_id(id), "identifiant de profil invalide");
    load_file(&directory().join(format!("{id}.json")))
}

pub fn list() -> Result<Vec<Profile>> {
    let directory = directory();
    let mut paths = std::fs::read_dir(&directory)
        .with_context(|| format!("lecture de {}", directory.display()))?
        .map(|entry| entry.map(|entry| entry.path()))
        .collect::<std::io::Result<Vec<_>>>()?;
    paths.retain(|path| path.extension().is_some_and(|suffix| suffix == "json") && path.is_file());
    paths.sort_unstable();
    paths.iter().map(|path| load_file(path)).collect()
}

fn load_file(path: &Path) -> Result<Profile> {
    let contents = std::fs::read(path).with_context(|| format!("lecture de {}", path.display()))?;
    let profile: Profile = serde_json::from_slice(&contents)
        .with_context(|| format!("profil JSON invalide dans {}", path.display()))?;
    ensure!(
        path.file_stem().and_then(|name| name.to_str()) == Some(profile.id.as_str()),
        "l’identifiant {} ne correspond pas à {}",
        profile.id,
        path.display()
    );
    profile
        .validate()
        .with_context(|| format!("profil {}", profile.id))?;
    Ok(profile)
}

impl Profile {
    pub fn validate(&self) -> Result<()> {
        ensure!(self.schema_version == 1, "version de profil inconnue");
        ensure!(valid_id(&self.id), "identifiant invalide");
        ensure!(
            !self.name.trim().is_empty()
                && !self.language.trim().is_empty()
                && !self.sources.is_empty(),
            "nom, langue et sources requis"
        );
        let (Some(board), Some(rules), Some(tiles)) = (&self.board, &self.rules, &self.tiles)
        else {
            ensure!(
                self.status == ProfileStatus::Draft,
                "plateau, règles et jetons requis"
            );
            return Ok(());
        };
        ensure!(
            board.layout == "classic-15" && board.size == 15 && board.center == [7, 7],
            "géométrie de plateau non prise en charge"
        );
        ensure!(
            rules.min_word_tiles == 2 && (2..=7).contains(&rules.rack_size),
            "taille de chevalet ou longueur de mot non prise en charge"
        );
        ensure!(
            (1..=rules.rack_size).contains(&rules.bingo_tiles) && rules.bingo_bonus >= 0,
            "prime de pose invalide"
        );
        ensure!(
            !rules.players.is_empty()
                && rules.players.iter().all(|n| (2..=4).contains(n))
                && rules.players.iter().collect::<HashSet<_>>().len() == rules.players.len(),
            "nombre de joueurs invalide"
        );
        ensure!(
            rules.scoreless_end.limit > 0
                && (!rules.scoreless_end.per_player
                    || rules.players.iter().all(|p| rules
                        .scoreless_end
                        .limit
                        .checked_mul(*p)
                        .is_some())),
            "limite de fin de partie invalide"
        );
        ensure!(
            rules.rack_out_scoring != RackOutScoring::DoubleResidue || rules.players == [2],
            "doublement du reliquat pris en charge en duel uniquement"
        );
        ensure!(
            (2..=64).contains(&tiles.len())
                && tiles[0].symbol == "?"
                && tiles[0].points == 0
                && !tiles[0].vowel,
            "alphabet invalide, le joker de valeur zéro doit être le premier jeton"
        );
        let mut symbols = HashSet::with_capacity(tiles.len());
        for tile in tiles {
            ensure!(
                tile.symbol.chars().count() == 1
                    && tile.symbol.to_uppercase() == tile.symbol
                    && !tile.symbol.chars().any(char::is_whitespace)
                    && !tile.symbol.chars().any(char::is_control)
                    && tile.count > 0
                    && (0..=32).contains(&tile.points)
                    && symbols.insert(&tile.symbol),
                "jeton invalide ou répété : {}",
                tile.symbol
            );
        }
        let total_tiles: u16 = tiles.iter().map(|t| u16::from(t.count)).sum();
        ensure!(
            total_tiles
                >= u16::from(rules.rack_size) * u16::from(*rules.players.iter().max().unwrap())
                && rules.exchange_min_bag >= 1
                && rules.exchange_min_bag as u16 <= total_tiles,
            "sac incompatible avec la distribution initiale ou le seuil d’échange"
        );
        Ok(())
    }

    pub fn game_config(&self, players: u8) -> Result<game_config::GameConfig> {
        self.validate()?;
        if self.status == ProfileStatus::Draft {
            bail!("le profil {} est documenté comme brouillon", self.id);
        }
        let rules = self.rules.as_ref().context("règles absentes")?;
        let tiles = self.tiles.as_ref().context("jetons absents")?;
        ensure!(
            rules.players.contains(&players),
            "ce profil ne prend pas en charge {players} joueurs"
        );
        let mut alphabet_text = String::new();
        for tile in tiles {
            writeln!(
                alphabet_text,
                "{}\t{}\t{}\t{}\t{}\t0\t0",
                tile.symbol,
                tile.symbol.to_lowercase(),
                tile.count,
                tile.points,
                u8::from(tile.vowel)
            )?;
        }
        let alphabet =
            Alphabet::new_static_from_text(&alphabet_text).map_err(|e| anyhow!("{e}"))?;
        let end = &rules.scoreless_end;
        let end_limit = if end.per_player {
            end.limit * players
        } else {
            end.limit
        };
        let (passes, zeros) = match end.trigger {
            ScorelessTrigger::Passes => (end_limit, 0),
            ScorelessTrigger::ZeroTurns => (0, end_limit),
        };
        Ok(game_config::GameConfig::Static(
            game_config::StaticGameConfig {
                game_rules: game_config::GameRules::Classic,
                alphabet,
                board_layout: board_layout::make_standard_board_layout(),
                rack_size: rules.rack_size,
                num_played_bonus: game_config::make_num_played_bonus(&[(
                    rules.bingo_tiles,
                    rules.bingo_bonus,
                )]),
                num_players: players,
                num_passes_to_end: passes,
                challenges_are_passes: false,
                num_zeros_to_end: zeros,
                zeros_can_end_empty_board: true,
                exchanges_are_zeros: zeros > 0,
                exchanges_allowed_per_player: i16::MAX,
                exchange_tile_limit: rules.exchange_min_bag,
            },
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn french_profile_matches_the_existing_campaign() -> Result<()> {
        let current = load("fr-classic-v1")?.game_config(2)?;
        let previous = game_config::make_french_game_config();
        let old = previous.alphabet();
        let new = current.alphabet();
        assert_eq!(new.num_tiles(), 102);
        assert_eq!(new.len(), old.len());
        for tile in 0..new.len() {
            assert_eq!(new.of_rack(tile), old.of_rack(tile));
            assert_eq!(new.of_board(tile | 128), old.of_board(tile | 128));
            assert_eq!(new.freq(tile), old.freq(tile));
            assert_eq!(new.score(tile), old.score(tile));
            assert_eq!(new.is_vowel(tile), old.is_vowel(tile));
        }
        assert_eq!(current.num_passes_to_end(), 6);
        assert_eq!(current.num_zeros_to_end(), 0);
        assert_eq!(current.exchange_tile_limit(), 7);
        assert_eq!(current.rack_size(), 7);
        assert_eq!(current.num_played_bonus(7), 50);
        assert!(!current.exchanges_are_zeros());
        Ok(())
    }

    #[test]
    fn international_profiles_keep_distinct_rules_and_alphabets() -> Result<()> {
        let en = load("en-wespa-v1")?;
        let en_config = en.game_config(2)?;
        assert_eq!(en_config.alphabet().num_tiles(), 100);
        assert_eq!(en_config.num_passes_to_end(), 0);
        assert_eq!(en_config.num_zeros_to_end(), 6);
        assert!(en_config.exchanges_are_zeros());
        assert_eq!(
            en.rules.as_ref().unwrap().rack_out_scoring,
            RackOutScoring::DoubleResidue
        );
        assert!(en.game_config(4).is_err());

        let ru_config = load("ru-mattel-v1")?.game_config(4)?;
        assert_eq!(ru_config.alphabet().num_tiles(), 104);
        assert_eq!(ru_config.num_passes_to_end(), 8);
        assert_eq!(ru_config.exchange_tile_limit(), 1);
        assert_eq!(ru_config.alphabet().of_rack(6), Some("Е"));
        assert_eq!(ru_config.alphabet().of_rack(7), Some("Ё"));

        assert!(load("ja-nihongo-v0")?.game_config(2).is_err());
        assert!(load("../fr-classic-v1").is_err());
        Ok(())
    }
}
