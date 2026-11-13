use super::*;

pub(super) const SCHEMA: &str = "
    CREATE TABLE IF NOT EXISTS social_referrals(
        sequence INTEGER PRIMARY KEY,
        user_id TEXT NOT NULL UNIQUE REFERENCES social_users(id) ON DELETE CASCADE,
        created_at INTEGER NOT NULL,
        inviter_id TEXT REFERENCES social_users(id) ON DELETE SET NULL,
        attributed_at INTEGER,
        completed_at INTEGER,
        CHECK(inviter_id IS NULL OR inviter_id<>user_id)
    );
    CREATE INDEX IF NOT EXISTS social_referrals_inviter ON social_referrals(inviter_id);
    CREATE TABLE IF NOT EXISTS social_referral_wallets(
        user_id TEXT PRIMARY KEY REFERENCES social_users(id) ON DELETE CASCADE,
        tickets_earned INTEGER NOT NULL CHECK(tickets_earned>0)
    );
    CREATE TABLE IF NOT EXISTS social_referral_months(
        user_id TEXT NOT NULL REFERENCES social_users(id) ON DELETE CASCADE,
        month TEXT NOT NULL,
        grants INTEGER NOT NULL CHECK(grants BETWEEN 1 AND 3),
        PRIMARY KEY(user_id,month)
    );
";

pub(super) fn register(db: &Transaction<'_>, user: &str, time: i64) -> Api<()> {
    db.execute(
        "INSERT INTO social_referrals(user_id,created_at) VALUES(?1,?2)",
        params![user, time],
    )?;
    Ok(())
}

pub(super) fn attribute(db: &Transaction<'_>, inviter: &str, invited: &str, time: i64) -> Api<()> {
    // A new account can only be referred by an older account. Registration order
    // breaks ties when both accounts were created in the same second.
    let (a, b) = pair(inviter, invited);
    db.execute(
        "UPDATE social_referrals AS invited SET inviter_id=?1,attributed_at=?3
        WHERE user_id=?2 AND attributed_at IS NULL AND created_at BETWEEN ?4 AND ?3
        AND NOT EXISTS(SELECT 1 FROM social_referrals AS inviter
                       WHERE inviter.user_id=?1 AND inviter.sequence>=invited.sequence)
        AND NOT EXISTS(SELECT 1 FROM social_duels
                       WHERE a=?5 AND b=?6 AND score_a IS NOT NULL AND score_b IS NOT NULL)",
        params![inviter, invited, time, time - INVITE_SECONDS, a, b],
    )?;
    Ok(())
}

pub(super) fn complete(db: &Transaction<'_>, a: &str, b: &str, time: i64) -> Api<()> {
    let attribution: Option<(String, String)> = db
        .query_row(
            "SELECT user_id,inviter_id FROM social_referrals
            WHERE completed_at IS NULL
            AND ((user_id=?1 AND inviter_id=?2) OR (user_id=?2 AND inviter_id=?1))",
            params![a, b],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    let Some((invited, inviter)) = attribution else {
        return Ok(());
    };
    db.execute(
        "UPDATE social_referrals SET completed_at=?2 WHERE user_id=?1",
        params![invited, time],
    )?;
    grant(db, &invited)?;
    // Keep month totals independently of invitee accounts and invitation expiry.
    let below_cap = db.execute(
        "INSERT INTO social_referral_months(user_id,month,grants)
        VALUES(?1,strftime('%Y-%m',?2,'unixepoch'),1)
        ON CONFLICT(user_id,month) DO UPDATE SET grants=grants+1 WHERE grants<3",
        params![inviter, time],
    )? == 1;
    if below_cap {
        grant(db, &inviter)?;
    }
    Ok(())
}

fn grant(db: &Transaction<'_>, user: &str) -> Api<()> {
    db.execute(
        "INSERT INTO social_referral_wallets(user_id,tickets_earned) VALUES(?1,1)
        ON CONFLICT(user_id) DO UPDATE SET tickets_earned=tickets_earned+1",
        [user],
    )?;
    Ok(())
}

pub(super) fn snapshot(db: &Connection, user: &str) -> Api<Value> {
    let tickets = db
        .query_row(
            "SELECT tickets_earned FROM social_referral_wallets WHERE user_id=?1",
            [user],
            |row| row.get::<_, i64>(0),
        )
        .optional()?
        .unwrap_or(0);
    Ok(json!({"wallet_id":user,"tickets_earned":tickets}))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn database() -> Connection {
        let db = Connection::open_in_memory().unwrap();
        db.execute_batch("PRAGMA foreign_keys=ON;").unwrap();
        db.execute_batch(super::super::SCHEMA).unwrap();
        db.execute_batch(SCHEMA).unwrap();
        db
    }

    fn account(tx: &Transaction<'_>, user: &str, created_at: i64) {
        tx.execute(
            "INSERT INTO social_users(id,nickname) VALUES(?1,?1)",
            [user],
        )
        .unwrap();
        register(tx, user, created_at).unwrap();
    }

    fn tickets(db: &Connection, user: &str) -> i64 {
        snapshot(db, user).unwrap()["tickets_earned"]
            .as_i64()
            .unwrap()
    }

    #[test]
    fn credits_once_per_attribution_and_preserves_inviter_balance_on_account_deletion() {
        let mut db = database();
        let tx = db.transaction().unwrap();
        account(&tx, "a", 1);
        account(&tx, "b", 2);
        account(&tx, "c", 2);
        attribute(&tx, "b", "a", 3).unwrap();
        attribute(&tx, "a", "c", 3).unwrap();
        attribute(&tx, "b", "c", 3).unwrap();
        complete(&tx, "b", "c", 4).unwrap();
        assert_eq!(tickets(&tx, "b"), 0);
        assert_eq!(tickets(&tx, "c"), 0);
        complete(&tx, "a", "c", 4).unwrap();
        complete(&tx, "a", "c", 4).unwrap();
        complete(&tx, "b", "a", 4).unwrap();
        assert_eq!(tickets(&tx, "a"), 1);
        assert_eq!(tickets(&tx, "b"), 0);
        assert_eq!(tickets(&tx, "c"), 1);
        tx.execute("DELETE FROM social_users WHERE id='c'", [])
            .unwrap();
        assert_eq!(tickets(&tx, "a"), 1);
        assert_eq!(tickets(&tx, "c"), 0);
        account(&tx, "new", 5);
        attribute(&tx, "a", "new", 5).unwrap();
        tx.execute("DELETE FROM social_users WHERE id='a'", [])
            .unwrap();
        attribute(&tx, "b", "new", 6).unwrap();
        complete(&tx, "b", "new", 7).unwrap();
        assert_eq!(tickets(&tx, "new"), 0);
        tx.commit().unwrap();
    }

    #[test]
    fn expiry_and_utc_monthly_cap_apply_without_reducing_earned_balances() {
        // February 1, 1970 at midnight UTC.
        const NEXT_MONTH: i64 = 31 * 86_400;
        let mut db = database();
        let tx = db.transaction().unwrap();
        account(&tx, "host", 0);
        account(&tx, "expired", 1);
        attribute(&tx, "host", "expired", NEXT_MONTH - 1).unwrap();
        complete(&tx, "host", "expired", NEXT_MONTH - 1).unwrap();
        assert_eq!(tickets(&tx, "expired"), 0);
        for (user, time, inviter_tickets) in [
            ("a", NEXT_MONTH - 1, 1),
            ("b", NEXT_MONTH - 1, 2),
            ("c", NEXT_MONTH - 1, 3),
            ("d", NEXT_MONTH - 1, 3),
            ("e", NEXT_MONTH, 4),
        ] {
            account(&tx, user, NEXT_MONTH - 2);
            attribute(&tx, "host", user, NEXT_MONTH - 1).unwrap();
            complete(&tx, "host", user, time).unwrap();
            assert_eq!(tickets(&tx, user), 1);
            assert_eq!(tickets(&tx, "host"), inviter_tickets);
        }
        tx.commit().unwrap();
    }
}
