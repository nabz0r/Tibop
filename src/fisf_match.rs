//! Match bookkeeping for the FISF Classic rules effective 1 September 2026.
//! https://www.ffsc.fr/files/public/fichiers/reglements/classique/Reglement.international.du.Scrabble.classique.pdf
//!
//! The simulation supplies elapsed time and the dictionary's verdict on contested
//! words. It resolves a placement before committing tiles, scoring, or drawing.

pub(crate) const STANDARD_CLOCK_MS: u64 = 20 * 60 * 1_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ClockStatus {
    Running,
    Expired,
    BothExpired,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum TurnKind {
    Placement,
    Exchange,
    Pass,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum EndReason {
    RackOut { seat: usize },
    Blocked,
    BothClocksExpired,
    SixPasses,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct ChallengeOutcome {
    pub(crate) placement_cancelled: bool,
    pub(crate) challenger_penalty: i32,
}

pub(crate) struct MatchRules {
    remaining_ms: [u64; 2],
    consecutive_passes: u8,
}

impl MatchRules {
    pub(crate) fn new(clock_ms: u64) -> Self {
        Self {
            remaining_ms: [clock_ms; 2],
            consecutive_passes: 0,
        }
    }

    pub(crate) fn remaining_ms(&self) -> [u64; 2] {
        self.remaining_ms
    }

    /// §§ 3.4 and 5.1.3. A move must be submitted strictly before expiry.
    pub(crate) fn charge(&mut self, seat: usize, elapsed_ms: u64) -> ClockStatus {
        self.remaining_ms[seat] = self.remaining_ms[seat].saturating_sub(elapsed_ms);
        if self.remaining_ms[seat] > 0 {
            ClockStatus::Running
        } else if self.remaining_ms[1 - seat] > 0 {
            ClockStatus::Expired
        } else {
            ClockStatus::BothExpired
        }
    }

    /// §§ 3.3 and 5.1.3. Neither seat can exchange once either clock has expired.
    pub(crate) fn may_exchange(&self, seat: usize, bag_len: usize) -> bool {
        bag_len >= 7 && self.remaining_ms[seat] > 0 && self.remaining_ms[1 - seat] > 0
    }

    /// An expired player may challenge, but § 3.5.2 requires time remaining to
    /// contest the final rack-out placement. The referee's lookup is clock-free.
    pub(crate) fn can_challenge(&self, challenger: usize, finishing_placement: bool) -> bool {
        !finishing_placement || self.remaining_ms[challenger] > 0
    }

    /// §§ 3.5.2–3.5.4. Look up only the selected formed words. An empty selection
    /// accepts the placement. Challenge indices identify occurrences on the board.
    pub(crate) fn resolve_challenge(
        &self,
        challenger: usize,
        formed_words: &[String],
        selected_word_indices: &[usize],
        finishing_placement: bool,
        mut is_valid: impl FnMut(&str) -> bool,
    ) -> Result<ChallengeOutcome, &'static str> {
        if selected_word_indices.is_empty() {
            return Ok(ChallengeOutcome::default());
        }
        if !self.can_challenge(challenger, finishing_placement) {
            return Err("délai de contestation du dernier coup expiré");
        }
        for (position, &word) in selected_word_indices.iter().enumerate() {
            if word >= formed_words.len() || selected_word_indices[..position].contains(&word) {
                return Err("mot contesté absent du coup ou répété");
            }
        }
        let mut outcome = ChallengeOutcome::default();
        for &word in selected_word_indices {
            if is_valid(&formed_words[word]) {
                outcome.challenger_penalty += 5;
            } else {
                outcome.placement_cancelled = true;
            }
        }
        Ok(outcome)
    }

    /// §§ 3.2 and 5.1.4. A cancelled placement counts as a pass (§ 3.5.3).
    pub(crate) fn record_turn(&mut self, kind: TurnKind) -> bool {
        self.consecutive_passes = match kind {
            TurnKind::Pass => self.consecutive_passes.saturating_add(1),
            TurnKind::Placement | TurnKind::Exchange => 0,
        };
        self.consecutive_passes >= 6
    }

    /// §§ 5.1.1–5.1.4. Values are ordinary points before any rack adjustments.
    pub(crate) fn end_adjustments(reason: EndReason, rack_values: [i32; 2]) -> [i32; 2] {
        match reason {
            EndReason::RackOut { seat } => {
                let mut points = [0, 0];
                points[seat] = rack_values[1 - seat];
                points[1 - seat] = -rack_values[1 - seat];
                points
            }
            EndReason::Blocked | EndReason::BothClocksExpired | EndReason::SixPasses => {
                rack_values.map(|value| -value)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn words() -> Vec<String> {
        ["CHAT", "CHATS", "FAUMOT"].map(str::to_owned).to_vec()
    }

    #[test]
    fn hitting_zero_forces_a_pass_and_prevents_both_players_from_exchanging() {
        let mut rules = MatchRules::new(STANDARD_CLOCK_MS);
        assert_eq!(rules.remaining_ms(), [1_200_000, 1_200_000]);
        assert!(!rules.may_exchange(0, 6));
        assert!(rules.may_exchange(0, 7));
        assert_eq!(rules.charge(0, STANDARD_CLOCK_MS - 1), ClockStatus::Running);
        assert_eq!(rules.charge(0, 1), ClockStatus::Expired);
        assert!(!rules.may_exchange(0, 100));
        assert!(!rules.may_exchange(1, 100));
        assert_eq!(rules.charge(0, u64::MAX), ClockStatus::Expired);
        assert_eq!(rules.charge(1, STANDARD_CLOCK_MS), ClockStatus::BothExpired);
        assert_eq!(rules.remaining_ms(), [0, 0]);
    }

    #[test]
    fn challenge_checks_only_selected_words_and_handles_mixed_verdict() {
        let rules = MatchRules::new(1);
        let words = words();
        let mut checked = Vec::new();
        let verdict = rules
            .resolve_challenge(1, &words, &[1, 2], false, |word| {
                checked.push(word.to_owned());
                word != "FAUMOT"
            })
            .unwrap();
        assert_eq!(checked, ["CHATS", "FAUMOT"]);
        assert!(verdict.placement_cancelled);
        assert_eq!(verdict.challenger_penalty, 5);
        let valid = rules
            .resolve_challenge(1, &words, &[0, 1], false, |_| true)
            .unwrap();
        assert!(!valid.placement_cancelled);
        assert_eq!(valid.challenger_penalty, 10);
        let invalid = rules
            .resolve_challenge(1, &words, &[2], false, |_| false)
            .unwrap();
        assert!(invalid.placement_cancelled);
        assert_eq!(invalid.challenger_penalty, 0);
        let untouched = rules
            .resolve_challenge(1, &words, &[], false, |_| panic!())
            .unwrap();
        assert_eq!(untouched, ChallengeOutcome::default());
        assert!(
            rules
                .resolve_challenge(1, &words, &[0, 3], false, |_| panic!())
                .is_err()
        );
        assert!(
            rules
                .resolve_challenge(1, &words, &[0, 0], false, |_| panic!())
                .is_err()
        );
        assert_eq!(rules.remaining_ms(), [1, 1]);
    }

    #[test]
    fn expired_challenger_can_contest_regular_turns_but_not_final_rack_out() {
        let mut rules = MatchRules::new(1);
        assert_eq!(rules.charge(1, 1), ClockStatus::Expired);
        assert!(
            rules
                .resolve_challenge(1, &words(), &[0], false, |_| true)
                .is_ok()
        );
        assert!(
            rules
                .resolve_challenge(1, &words(), &[0], true, |_| panic!())
                .is_err()
        );
        assert!(
            rules
                .resolve_challenge(1, &words(), &[], true, |_| panic!())
                .is_ok()
        );
    }

    #[test]
    fn placement_or_exchange_resets_six_pass_sequence() {
        let mut rules = MatchRules::new(1);
        for non_pass in [TurnKind::Exchange, TurnKind::Placement] {
            for _ in 0..5 {
                assert!(!rules.record_turn(TurnKind::Pass));
            }
            assert!(!rules.record_turn(non_pass));
        }
        for _ in 0..5 {
            assert!(!rules.record_turn(TurnKind::Pass));
        }
        assert!(rules.record_turn(TurnKind::Pass));
    }

    #[test]
    fn only_rack_out_transfers_the_adversarys_rack() {
        assert_eq!(
            MatchRules::end_adjustments(EndReason::RackOut { seat: 0 }, [0, 9]),
            [9, -9]
        );
        assert_eq!(
            MatchRules::end_adjustments(EndReason::RackOut { seat: 1 }, [7, 0]),
            [-7, 7]
        );
        for reason in [
            EndReason::Blocked,
            EndReason::BothClocksExpired,
            EndReason::SixPasses,
        ] {
            assert_eq!(MatchRules::end_adjustments(reason, [7, 9]), [-7, -9]);
        }
    }
}
