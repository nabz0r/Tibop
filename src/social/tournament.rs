use super::*;

const SLOTS: usize = 3;
const DAY_SECONDS: i64 = 86_400;
const WEEK_DAYS: i64 = 7;
const FIRST_WEEKEND: i64 = 20_771; // 14 November 2026, the first Saturday of the tournament.
pub(super) const CATALOG_SIZE: usize = 164;

pub(super) const SCHEMA: &str = "
    CREATE TABLE IF NOT EXISTS social_tournament_scores(
        user_id TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE,
        weekend INTEGER NOT NULL,
        slot INTEGER NOT NULL CHECK(slot BETWEEN 1 AND 3),
        score INTEGER NOT NULL CHECK(score>=0),
        move TEXT NOT NULL,
        PRIMARY KEY(user_id,weekend,slot)
    );
";

#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize)]
#[serde(rename_all = "snake_case")]
enum WeekendStatus {
    Upcoming,
    Open,
    Closed,
}

#[derive(Clone, Copy, Debug)]
struct Weekend {
    id: i64,
    status: WeekendStatus,
}

impl Weekend {
    fn at(time: i64) -> Self {
        let today = time.div_euclid(DAY_SECONDS);
        if today < FIRST_WEEKEND {
            return Self {
                id: FIRST_WEEKEND,
                status: WeekendStatus::Upcoming,
            };
        }
        let id = FIRST_WEEKEND + (today - FIRST_WEEKEND) / WEEK_DAYS * WEEK_DAYS;
        let status = if today - id < 2 {
            WeekendStatus::Open
        } else {
            WeekendStatus::Closed
        };
        Self { id, status }
    }

    fn puzzle(self, number: usize, app: &Social) -> &Puzzle {
        let index = ((self.id - FIRST_WEEKEND) / WEEK_DAYS) as usize * SLOTS + number - 1;
        &app.puzzles[index % CATALOG_SIZE]
    }

    fn next_starts_at(self) -> i64 {
        let day = if self.status == WeekendStatus::Upcoming {
            self.id
        } else {
            self.id + WEEK_DAYS
        };
        day * DAY_SECONDS
    }
}

fn current_weekend(_app: &Social) -> Weekend {
    #[cfg(test)]
    {
        let time = _app
            .tournament_time
            .load(std::sync::atomic::Ordering::Relaxed);
        if time != 0 {
            return Weekend::at(time);
        }
    }
    Weekend::at(now())
}

pub(super) async fn get_weekend(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
) -> Api<Json<Value>> {
    let session = auth(headers)?;
    write(app, move |app, db| {
        let user = user_id(db, &session)?;
        let weekend = current_weekend(app);
        Ok(Json(view(app, db, weekend, &user)?))
    })
    .await
}

pub(super) async fn play_slot(
    State(app): State<Arc<Social>>,
    headers: HeaderMap,
    Path((id, number)): Path<(i64, usize)>,
    body: std::result::Result<Json<MoveRequest>, JsonRejection>,
) -> Api<Json<Value>> {
    let session = auth(headers)?;
    let Json(body) = body.map_err(json_error)?;
    let body = body.prepare()?;
    if !(1..=SLOTS).contains(&number) {
        return Err(missing("Défi introuvable."));
    }
    let canonical = body.canonical();
    write(app, move |app, db| {
        let user = user_id(db, &session)?;
        let weekend = current_weekend(app);
        if id != weekend.id { return Err(conflict("Ce tournoi n’est plus disponible.")); }
        let played: Option<String> = db.query_row(
            "SELECT move FROM social_tournament_scores WHERE user_id=?1 AND weekend=?2 AND slot=?3",
            params![user, id, number as i64], |row| row.get(0),
        ).optional()?;
        if let Some(played) = played {
            if played != canonical { return Err(conflict("Vous avez déjà joué ce défi.")); }
        } else {
            if weekend.status != WeekendStatus::Open { return Err(conflict("Le tournoi est fermé.")); }
            let score = body.score(app, weekend.puzzle(number, app))?;
            let deadline = current_weekend(app);
            if deadline.status != WeekendStatus::Open || deadline.id != id {
                return Err(conflict("Le tournoi est fermé."));
            }
            db.execute("INSERT INTO social_tournament_scores(user_id,weekend,slot,score,move) VALUES(?1,?2,?3,?4,?5)",
                params![user,id,number as i64,score,canonical])?;
        }
        Ok(Json(view(app, db, current_weekend(app), &user)?))
    }).await
}

fn view(app: &Social, db: &Connection, weekend: Weekend, user: &str) -> Api<Value> {
    let mut scores = [None; SLOTS];
    let mut slots = Vec::new();
    let mut ranking = Vec::new();
    if weekend.status != WeekendStatus::Upcoming {
        let mut query = db.prepare(
            "SELECT slot,score FROM social_tournament_scores WHERE user_id=?1 AND weekend=?2",
        )?;
        for score in query.query_map(params![user, weekend.id], |row| {
            Ok((row.get::<_, u8>(0)?, row.get::<_, i32>(1)?))
        })? {
            let (number, score) = score?;
            scores[usize::from(number) - 1] = Some(score);
        }
        slots.extend(scores.iter().enumerate().map(|(index, score)| {
            let number = index + 1;
            json!({"number":number,"puzzle":weekend.puzzle(number,app),"score":score})
        }));
        ranking = standings(db, weekend, user)?;
    }
    Ok(json!({
        "id":weekend.id,
        "status":weekend.status,
        "starts_at":weekend.id * DAY_SECONDS,
        "ends_at":(weekend.id + 2) * DAY_SECONDS,
        "next_starts_at":weekend.next_starts_at(),
        "slots":slots,
        "completed":scores.iter().flatten().count(),
        "total":scores.iter().flatten().sum::<i32>(),
        "ranking":ranking,
    }))
}

fn standings(db: &Connection, weekend: Weekend, user: &str) -> Api<Vec<Value>> {
    let mut query = db.prepare("WITH circle(id) AS (
            SELECT ?2 UNION ALL SELECT b FROM social_friends WHERE a=?2 UNION ALL SELECT a FROM social_friends WHERE b=?2
        )
        SELECT u.id,u.nickname,sum(s.score) AS total FROM circle
        JOIN social_users u ON u.id=circle.id
        JOIN social_tournament_scores s ON s.user_id=circle.id AND s.weekend=?1
        GROUP BY u.id,u.nickname HAVING count(*)=?3
        ORDER BY total DESC,u.nickname,u.id")?;
    let rows = query.query_map(params![weekend.id, user, SLOTS as i64], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, i32>(2)?,
        ))
    })?;
    let mut ranking = Vec::new();
    let mut previous = None;
    let mut rank = 0;
    for row in rows {
        let (id, nickname, score) = row?;
        if previous != Some(score) {
            rank = ranking.len() + 1;
        }
        ranking
            .push(json!({"rank":rank,"is_you":id==user,"id":id,"nickname":nickname,"score":score}));
        previous = Some(score);
    }
    Ok(ranking)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::social::tests::{engine, request, server};
    use axum::http::Method;
    use std::sync::atomic::Ordering;

    const START: i64 = FIRST_WEEKEND * DAY_SECONDS;

    #[test]
    fn utc_calendar_changes_only_at_the_weekend_boundaries() {
        for (time, id, status) in [
            (START - 1, FIRST_WEEKEND, WeekendStatus::Upcoming),
            (START, FIRST_WEEKEND, WeekendStatus::Open),
            (
                START + 2 * DAY_SECONDS - 1,
                FIRST_WEEKEND,
                WeekendStatus::Open,
            ),
            (
                START + 2 * DAY_SECONDS,
                FIRST_WEEKEND,
                WeekendStatus::Closed,
            ),
            (
                START + 7 * DAY_SECONDS - 1,
                FIRST_WEEKEND,
                WeekendStatus::Closed,
            ),
            (
                START + 7 * DAY_SECONDS,
                FIRST_WEEKEND + 7,
                WeekendStatus::Open,
            ),
        ] {
            let weekend = Weekend::at(time);
            assert_eq!((weekend.id, weekend.status), (id, status));
        }
    }

    #[tokio::test]
    async fn weekend_ranks_only_complete_friends_and_rejects_new_closed_scores() {
        let app = Arc::new(Social::open(FilePath::new(":memory:"), engine()).unwrap());
        app.tournament_time.store(START - 1, Ordering::Relaxed);
        let (base, server) = server(app.clone()).await;
        let a = "a".repeat(64);
        let b = "b".repeat(64);
        let c = "c".repeat(64);
        let mut ids = Vec::new();
        for (token, name) in [(&a, "Alice"), (&b, "Benoît"), (&c, "Clara")] {
            let session = request(
                &base,
                Method::POST,
                "session",
                "",
                json!({"token":token,"nickname":name}),
                StatusCode::OK,
            )
            .await;
            ids.push(session["profile"]["id"].as_str().unwrap().to_owned());
        }
        let link = request(
            &base,
            Method::POST,
            "invites",
            &a,
            json!({}),
            StatusCode::OK,
        )
        .await;
        request(
            &base,
            Method::POST,
            "invites/accept",
            &b,
            json!({"token":link["token"]}),
            StatusCode::OK,
        )
        .await;

        let endpoint = "tournament";
        let upcoming = request(
            &base,
            Method::GET,
            endpoint,
            &a,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        assert_eq!(upcoming["id"], FIRST_WEEKEND);
        assert_eq!(upcoming["next_starts_at"], START);
        assert_eq!(upcoming["status"], "upcoming");
        assert_eq!(upcoming["slots"], json!([]));
        let post_paths: Vec<_> = (1..=SLOTS)
            .map(|number| format!("tournament/{FIRST_WEEKEND}/slots/{number}/play"))
            .collect();
        let moves = [
            json!({"tiles":[{"position":116,"rack_index":6,"letter":"S"}]}),
            json!({"tiles":[{"position":157,"rack_index":1,"letter":"S"}]}),
            json!({"tiles":[{"position":116,"rack_index":0,"letter":"A"}]}),
        ];
        request(
            &base,
            Method::POST,
            &post_paths[0],
            &a,
            moves[0].clone(),
            StatusCode::CONFLICT,
        )
        .await;
        app.tournament_time.store(START, Ordering::Relaxed);
        let open = request(
            &base,
            Method::GET,
            endpoint,
            &a,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        assert_eq!(open["status"], "open");
        assert_eq!(open["ends_at"], START + 2 * DAY_SECONDS);
        assert_eq!(open["next_starts_at"], START + WEEK_DAYS * DAY_SECONDS);
        let puzzles = open["slots"].as_array().unwrap();
        assert_eq!(
            puzzles
                .iter()
                .map(|slot| slot["puzzle"]["opening_word"].as_str().unwrap())
                .collect::<Vec<_>>(),
            ["CLOCHE", "PASSE", "BOIS"]
        );
        request(
            &base,
            Method::GET,
            endpoint,
            "",
            Value::Null,
            StatusCode::UNAUTHORIZED,
        )
        .await;
        request(
            &base,
            Method::POST,
            &post_paths[0],
            &a,
            json!({"score":999}),
            StatusCode::UNPROCESSABLE_ENTITY,
        )
        .await;
        request(
            &base,
            Method::POST,
            &post_paths[0],
            &a,
            json!({"tiles":[{"position":116,"rack_index":6,"letter":"X"}]}),
            StatusCode::BAD_REQUEST,
        )
        .await;
        let one = request(
            &base,
            Method::POST,
            &post_paths[0],
            &a,
            moves[0].clone(),
            StatusCode::OK,
        )
        .await;
        assert_eq!(one["total"], 15);
        assert_eq!(one["completed"], 1);
        assert_eq!(one["ranking"], json!([]));
        let duplicate = request(
            &base,
            Method::POST,
            &post_paths[0],
            &a,
            moves[0].clone(),
            StatusCode::OK,
        )
        .await;
        assert_eq!(duplicate, one);
        let replacement = json!({"tiles":[{"position":116,"rack_index":0,"letter":"T"},{"position":117,"rack_index":4,"letter":"T"},{"position":118,"rack_index":1,"letter":"E"}]});
        request(
            &base,
            Method::POST,
            &post_paths[0],
            &a,
            replacement,
            StatusCode::CONFLICT,
        )
        .await;

        for token in [&a, &b, &c] {
            for (index, (path, played)) in post_paths.iter().zip(&moves).enumerate() {
                let state = request(
                    &base,
                    Method::POST,
                    path,
                    token,
                    played.clone(),
                    StatusCode::OK,
                )
                .await;
                assert_eq!(state["completed"], index + 1);
                assert_eq!(state["slots"][index]["score"], [15, 8, 8][index]);
            }
        }
        let final_state = request(
            &base,
            Method::GET,
            endpoint,
            &a,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        let ranked = final_state["ranking"].as_array().unwrap();
        assert_eq!(ranked.len(), 2);
        assert_eq!(
            ranked[0],
            json!({"rank":1,"is_you":true,"id":ids[0],"nickname":"Alice","score":31})
        );
        assert_eq!(
            ranked[1],
            json!({"rank":1,"is_you":false,"id":ids[1],"nickname":"Benoît","score":31})
        );
        app.tournament_time
            .store(START + 2 * DAY_SECONDS, Ordering::Relaxed);
        let closed = request(
            &base,
            Method::GET,
            endpoint,
            &a,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        assert_eq!(closed["status"], "closed");
        assert_eq!(closed["ranking"], final_state["ranking"]);
        let d = "d".repeat(64);
        request(
            &base,
            Method::POST,
            "session",
            "",
            json!({"token":d}),
            StatusCode::OK,
        )
        .await;
        request(
            &base,
            Method::POST,
            &post_paths[0],
            &d,
            moves[0].clone(),
            StatusCode::CONFLICT,
        )
        .await;
        let late_retry = request(
            &base,
            Method::POST,
            &post_paths[0],
            &a,
            moves[0].clone(),
            StatusCode::OK,
        )
        .await;
        assert_eq!(late_retry, closed);
        app.tournament_time
            .store(START + WEEK_DAYS * DAY_SECONDS, Ordering::Relaxed);
        let next = request(
            &base,
            Method::GET,
            endpoint,
            &a,
            Value::Null,
            StatusCode::OK,
        )
        .await;
        assert_eq!(next["id"], FIRST_WEEKEND + WEEK_DAYS);
        assert_eq!(next["completed"], 0);
        assert_eq!(next["ranking"], json!([]));
        assert_ne!(next["slots"][0]["puzzle"], open["slots"][0]["puzzle"]);
        request(
            &base,
            Method::POST,
            &post_paths[0],
            &a,
            moves[0].clone(),
            StatusCode::CONFLICT,
        )
        .await;
        server.abort();
    }

    #[tokio::test]
    #[ignore = "serveur QA local en mémoire pour émulateur"]
    async fn serve_weekend_for_android_qa() {
        let app = Arc::new(Social::open(FilePath::new(":memory:"), engine()).unwrap());
        app.tournament_time.store(START, Ordering::Relaxed);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:8794")
            .await
            .unwrap();
        eprintln!("Tournoi QA sur http://127.0.0.1:8794");
        axum::serve(listener, router(app)).await.unwrap();
    }
}
