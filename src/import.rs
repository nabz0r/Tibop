use anyhow::{Context, Result, bail, ensure};
use postgres::Client;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

fn documents(bytes: &[u8]) -> Result<Vec<Value>> {
    let text = std::str::from_utf8(bytes)?
        .trim()
        .trim_start_matches('\u{feff}');
    ensure!(!text.is_empty(), "Fichier vide.");
    match serde_json::from_str::<Value>(text) {
        Ok(Value::Array(records)) => Ok(records),
        Ok(mut obj @ Value::Object(_)) => match obj.get_mut("games") {
            Some(Value::Array(games)) => Ok(std::mem::take(games)),
            _ => Ok(vec![obj]),
        },
        Ok(_) => bail!("Un objet, une liste de parties ou du JSONL est attendu."),
        Err(_) => text
            .lines()
            .enumerate()
            .filter(|(_, line)| !line.trim().is_empty())
            .map(|(i, line)| {
                serde_json::from_str(line)
                    .with_context(|| format!("JSONL invalide à la ligne {}", i + 1))
            })
            .collect(),
    }
}

fn normalize(mut game: Value) -> Result<(String, Value)> {
    ensure!(game.is_object(), "Une partie doit être un objet JSON.");
    let scores = game["scores"].as_array().context("Scores manquants.")?;
    ensure!(
        (2..=4).contains(&scores.len())
            && scores
                .iter()
                .all(|s| s.as_i64().is_some_and(|n| (-500..=9999).contains(&n))),
        "Deux à quatre scores entiers sont attendus."
    );
    let players = scores.len() as u64;
    let best = scores.iter().filter_map(Value::as_i64).max().unwrap();
    let winners: Vec<_> = scores
        .iter()
        .enumerate()
        .filter_map(|(i, s)| (s.as_i64() == Some(best)).then_some(i))
        .collect();
    let moves = game["moves"]
        .as_array()
        .context("Journal de coups manquant.")?;
    ensure!(
        !moves.is_empty() && moves.len() <= 200,
        "Le journal doit contenir de 1 à 200 tours."
    );
    let turns = moves.len();
    let mut occupied = [false; 225];
    for (i, m) in moves.iter().enumerate() {
        ensure!(
            m["turn"].as_u64() == Some(i as u64 + 1)
                && m["player"].as_u64().is_some_and(|p| p < players),
            "Tour ou joueur invalide au coup {}.",
            i + 1
        );
        let kind = m["type"].as_str().unwrap_or_default();
        ensure!(
            matches!(kind, "play" | "pass" | "exchange"),
            "Action invalide au coup {}.",
            i + 1
        );
        ensure!(
            m["score"].as_i64().is_some_and(|v| (0..=9999).contains(&v)),
            "Score du coup {} invalide.",
            i + 1
        );
        let tiles = m["tiles"]
            .as_array()
            .context("Liste de jetons manquante.")?;
        ensure!(
            (kind == "play" && (1..=7).contains(&tiles.len()))
                || (kind != "play" && tiles.is_empty()),
            "Nombre de jetons invalide au coup {}.",
            i + 1
        );
        for t in tiles {
            let row = t["row"]
                .as_u64()
                .filter(|n| *n < 15)
                .context("Ligne de jeton invalide.")?;
            let col = t["col"]
                .as_u64()
                .filter(|n| *n < 15)
                .context("Colonne de jeton invalide.")?;
            let pos = (row * 15 + col) as usize;
            ensure!(!occupied[pos], "Une case est occupée deux fois.");
            ensure!(
                t["letter"].as_str().is_some_and(|s| {
                    let mut letters = s.chars();
                    letters
                        .next()
                        .is_some_and(|c| c.is_alphabetic() && !c.is_lowercase())
                        && letters.next().is_none()
                }) && t["blank"].is_boolean(),
                "Lettre ou indicateur de joker invalide."
            );
            occupied[pos] = true;
        }
    }
    let existing_id = game.get("external_id").or_else(|| game.get("id"));
    let external_id = match existing_id {
        Some(Value::String(s)) if s.len() <= 128 => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    };
    let origin = if game["source"].as_str() == Some("android") {
        "android"
    } else {
        "fichier"
    };
    game.as_object_mut().unwrap().remove("id");
    game["external_id"] = json!(external_id);
    game["source"] = json!(origin);
    game["origin"] = json!(origin);
    game["turns"] = json!(turns);
    game["winner"] = if winners.len() == 1 {
        json!(winners[0])
    } else {
        Value::Null
    };
    game["winners"] = json!(winners);
    let valid_uuid = external_id.len() == 36
        && external_id.bytes().enumerate().all(|(i, c)| {
            if matches!(i, 8 | 13 | 18 | 23) {
                c == b'-'
            } else {
                c.is_ascii_hexdigit()
            }
        });
    let material = if origin == "android" && valid_uuid && game["schema"] == "lettrine.game.v1" {
        format!("android:{}", external_id.to_ascii_lowercase()).into_bytes()
    } else {
        serde_json::to_vec(&game)?
    };
    let digest = format!("{:x}", Sha256::digest(material));
    Ok((digest, game))
}

pub fn prepare(bytes: &[u8]) -> Result<Vec<(String, Value)>> {
    let documents = documents(bytes)?;
    ensure!(!documents.is_empty(), "Aucune partie dans le fichier.");
    documents
        .into_iter()
        .enumerate()
        .map(|(i, g)| normalize(g).with_context(|| format!("Partie {}", i + 1)))
        .collect()
}

pub fn insert(client: &mut Client, games: Vec<(String, Value)>) -> Result<Value> {
    let count = games.len();
    let mut tx = client.transaction()?;
    let stmt = tx.prepare("INSERT INTO lab_imports (fingerprint,payload) VALUES ($1,$2) ON CONFLICT(fingerprint) DO NOTHING")?;
    let mut imported = 0;
    for (key, game) in games {
        imported += tx.execute(&stmt, &[&key, &game])?;
    }
    tx.commit()?;
    Ok(json!({"imported":imported,"duplicates":count as u64 - imported}))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stable_after_roundtrip_and_validates_coordinates() {
        let game = json!({
            "id":"local-id","source":"android","scores":[15,10,15],"moves":[
                {"turn":1,"player":0,"type":"play","score":10,"word":"JE","tiles":[
                    {"row":7,"col":7,"letter":"J","blank":false},{"row":7,"col":8,"letter":"E","blank":false}
                ]}
            ]
        });
        let (key, mut result) = normalize(game).unwrap();
        assert_eq!(result["winners"], json!([0, 2]));
        assert_eq!(result["winner"], Value::Null);
        result["id"] = json!(35);
        assert_eq!(normalize(result.clone()).unwrap().0, key);
        result["moves"][0]["tiles"][1]["col"] = json!(7);
        assert!(normalize(result).is_err());
    }
}
