use crate::engine::{Engine, ExpertPolicy};
use anyhow::{Context, Result, bail};
use rand::SeedableRng;
use rand_xoshiro::Xoshiro256PlusPlus;
use serde_json::{Value, json};
use std::{
    env, fs,
    path::Path,
    time::{Duration, Instant},
};
use wolges::{equity, game_state::GameState, movegen::Play};

const FIRST_SEED: u32 = 926_111_200;

fn score(state: &GameState, seat: usize) -> i32 {
    equity::descale_score(state.players[seat].score)
}

async fn pick(
    client: &reqwest::Client,
    endpoint: &str,
    model: &str,
    context: Value,
    count: usize,
) -> Result<usize> {
    let response: Value = client.post(format!("{}/api/chat",endpoint.trim_end_matches('/')))
        .json(&json!({
            "model":model,"stream":false,"think":false,"keep_alive":"5m",
            "format":{"type":"object","properties":{"pick":{"type":"integer","minimum":0,"maximum":count-1}},"required":["pick"],"additionalProperties":false},
            "options":{"temperature":0,"num_ctx":2048,"num_predict":18},
            "messages":[
                {"role":"system","content":"Tu joues une partie de Scrabble. Choisis une option légale en renvoyant uniquement un JSON avec le champ pick (l'indice). Maximise les chances de gagner. Le score compte immédiatement. Conserver une bonne combinaison, un S ou un joker est utile quand il reste beaucoup de lettres. En fin de partie, vider le chevalet peut décider du résultat."},
                {"role":"user","content":context.to_string()}
            ]
        })).send().await?.error_for_status()?.json().await?;
    let answer: Value = serde_json::from_str(
        response["message"]["content"]
            .as_str()
            .context("choix Ollama absent")?,
    )?;
    answer["pick"]
        .as_u64()
        .map(|i| i as usize)
        .filter(|&i| i < count)
        .context("indice Ollama hors liste")
}

pub async fn benchmark(
    engine: &Engine,
    expert: ExpertPolicy,
    output: &Path,
    pairs: u32,
) -> Result<()> {
    if pairs == 0 || pairs > 100 {
        bail!("nombre de tirages appariés attendu entre 1 et 100");
    }
    let endpoint =
        env::var("LETTRINE_OLLAMA_URL").unwrap_or_else(|_| "http://127.0.0.1:11434".into());
    let model = env::var("LETTRINE_OLLAMA_MODEL").unwrap_or_else(|_| "llama3.2:3b".into());
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(12))
        .build()?;
    let started = Instant::now();
    let mut wins = 0;
    let mut draws = 0;
    let mut choices = 0;
    let mut fallbacks = 0;
    let mut margin_sum = 0;
    let mut details = Vec::new();
    let mut generator = engine.generator();
    let mut state = GameState::new(&engine.config);
    for pair in 0..pairs {
        let seed = FIRST_SEED + pair;
        for ai in 0..2 {
            let mut rng = Xoshiro256PlusPlus::seed_from_u64(u64::from(seed));
            state.reset_and_draw_tiles(&engine.config, &mut rng);
            let mut finished = false;
            let mut outgoing = None;
            for _ in 0..200 {
                let seat = usize::from(state.turn);
                engine.generate(
                    &mut generator,
                    &state.board_tiles,
                    &state.current_player().rack,
                    expert.width + 2,
                );
                let default = engine.choose(&generator, &state, 3, expert, &mut rng);
                let mut selected = default.clone();
                if seat == ai {
                    let mut options: Vec<_> = generator
                        .plays
                        .iter()
                        .filter(|p| matches!(p.play, Play::Place { .. }))
                        .map(|p| &p.play)
                        .collect();
                    options.sort_by_key(|p| {
                        std::cmp::Reverse(engine.evaluate(
                            p,
                            &state.current_player().rack,
                            state.bag.len(),
                            expert,
                        ))
                    });
                    let candidates: Vec<_> = options.into_iter().take(6).collect();
                    if candidates.len() > 1 {
                        choices += 1;
                        let list: Vec<_> = candidates.iter().enumerate().map(|(i,p)| {
                            let (word,tiles,_) = engine.describe(&state.board_tiles,p);
                            let mut keep = state.current_player().rack.clone();
                            for tile in &tiles {
                                let id = if tile.blank { 0 } else { engine.symbol_id(tile.letter).unwrap() };
                                if let Some(index) = keep.iter().position(|&t|t==id) { keep.remove(index); }
                            }
                            let points = match p { Play::Place{score,..}=>equity::descale_score(*score), _=>0 };
                            json!({"i":i,"mot":word,"score":points,
                                "case":[tiles[0].row,tiles[0].col],
                                "reste":keep.into_iter().map(|t|engine.letter(t)).collect::<String>()})
                        }).collect();
                        let board: Vec<String> = state
                            .board_tiles
                            .chunks(15)
                            .map(|row| {
                                row.iter()
                                    .map(|&t| if t == 0 { '.' } else { engine.letter(t) })
                                    .collect()
                            })
                            .collect();
                        let context = json!({"score_moi":score(&state,ai),"score_adverse":score(&state,1-ai),
                            "sac":state.bag.len(),"plateau":board,"options":list});
                        match pick(&client, &endpoint, &model, context, candidates.len()).await {
                            Ok(i) => selected = candidates[i].clone(),
                            Err(e) => {
                                fallbacks += 1;
                                if fallbacks <= 3 {
                                    eprintln!("Choix du modèle ignoré : {e:#}");
                                }
                                if choices == fallbacks && fallbacks >= 3 {
                                    bail!("Ollama n’a produit aucun choix valide");
                                }
                            }
                        }
                    }
                }
                state
                    .play(&engine.config, &mut rng, &selected)
                    .map_err(|e| anyhow::anyhow!("{e}"))?;
                if state.current_player().rack.is_empty() && state.bag.is_empty() {
                    outgoing = Some(seat);
                    finished = true;
                    break;
                }
                if state.scoreless_turns_ended(&engine.config) {
                    finished = true;
                    break;
                }
                state.next_turn();
            }
            if !finished {
                bail!("duel non terminé après 200 tours");
            }
            let residue: [i32; 2] = std::array::from_fn(|i| {
                engine.config.alphabet().rack_score(&state.players[i].rack)
            });
            let mut scores = [score(&state, 0) - residue[0], score(&state, 1) - residue[1]];
            if let Some(i) = outgoing {
                scores[i] += residue[0] + residue[1];
            }
            let margin = scores[ai] - scores[1 - ai];
            wins += u32::from(margin > 0);
            draws += u32::from(margin == 0);
            margin_sum += margin;
            details.push(json!({"seed":seed,"llm_seat":ai,"scores":scores}));
        }
        eprintln!(
            "{} · tirage {}/{} · {wins} victoires · {} choix valides",
            model,
            pair + 1,
            pairs,
            choices - fallbacks
        );
    }
    let games = pairs * 2;
    let rate = (f64::from(wins) + f64::from(draws) * 0.5) / f64::from(games);
    let corrected = (f64::from(wins) + f64::from(draws) * 0.5 + 0.5) / (f64::from(games) + 1.0);
    // Use one effective observation per shared draw, a conservative Wilson interval.
    // This stays informative when every match goes the same way.
    let n = f64::from(pairs);
    let z2 = 1.96_f64.powi(2);
    let center = (rate + z2 / (2.0 * n)) / (1.0 + z2 / n);
    let radius = 1.96 * (rate * (1.0 - rate) / n + z2 / (4.0 * n * n)).sqrt() / (1.0 + z2 / n);
    let mut report = json!({
        "schema":"lettrine.llm-benchmark.v1","items":[{
            "model":model,"opponent":"Expert entraîné","games":games,"paired_deals":pairs,
            "wins":wins,"draws":draws,"losses":games-wins-draws,"match_score_rate":rate,
            "elo_delta":(400.0*(corrected/(1.0-corrected)).log10()).round(),
            "score_interval_95":[(center-radius).max(0.0),(center+radius).min(1.0)],
            "mean_margin":f64::from(margin_sum)/f64::from(games),
            "llm_choices":choices-fallbacks,"fallbacks":fallbacks,"seconds":started.elapsed().as_secs_f64(),
            "matches":details,"lexicon_sha256":engine.digest,
            "method":"Expérimental. Le modèle sélectionne parmi six coups légaux de l’Expert, sans chevalet adverse. Chaque tirage est joué dans les deux sièges. L’écart Elo est régularisé. L’intervalle de Wilson utilise un effectif conservateur d’une observation par tirage."
        }]
    });
    if let Ok(bytes) = fs::read(output)
        && let Ok(previous) = serde_json::from_slice::<Value>(&bytes)
        && let Some(items) = previous["items"].as_array()
    {
        let results = report["items"]
            .as_array_mut()
            .expect("tableau de résultats");
        results.extend(
            items
                .iter()
                .filter(|item| item["model"] != model && item["lexicon_sha256"] == engine.digest)
                .cloned(),
        );
    }
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(output, serde_json::to_vec_pretty(&report)?)?;
    println!(
        "{}",
        serde_json::to_string_pretty(&report["items"][0]["elo_delta"])?
    );
    Ok(())
}
