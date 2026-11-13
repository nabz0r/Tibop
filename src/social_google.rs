use jsonwebtoken::{Algorithm, DecodingKey, Validation, decode, decode_header};
use serde::Deserialize;
use std::{
    env,
    sync::OnceLock,
    time::{Duration, Instant},
};
use tokio::sync::Mutex;

const GOOGLE_KEYS: &str = "https://www.googleapis.com/oauth2/v3/certs";

pub(crate) struct SocialGoogleError;

#[derive(Deserialize)]
struct GoogleKeys {
    keys: Vec<GoogleKey>,
}

#[derive(Deserialize)]
struct GoogleKey {
    kid: String,
    kty: String,
    alg: String,
    n: String,
    e: String,
}

#[derive(Deserialize)]
struct Claims {
    sub: String,
    nonce: String,
}

struct CachedKeys {
    expires: Instant,
    keys: GoogleKeys,
}

static CACHE: Mutex<Option<CachedKeys>> = Mutex::const_new(None);
static CLIENT_ID: OnceLock<Option<String>> = OnceLock::new();

pub(crate) fn client_id() -> Option<String> {
    CLIENT_ID
        .get_or_init(|| {
            let value = env::var("LETTRINE_GOOGLE_CLIENT_ID").ok()?;
            let id = value.trim().strip_suffix(".apps.googleusercontent.com")?;
            (!id.is_empty()
                && id.len() <= 100
                && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-'))
            .then(|| value.trim().to_owned())
        })
        .clone()
}

pub(crate) async fn verify(token: &str, nonce: &str) -> Result<String, SocialGoogleError> {
    let audience = client_id().ok_or(SocialGoogleError)?;
    if token.len() > 8192 || nonce.is_empty() || nonce.len() > 128 {
        return Err(SocialGoogleError);
    }
    let header = decode_header(token).map_err(|_| SocialGoogleError)?;
    if header.alg != Algorithm::RS256 {
        return Err(SocialGoogleError);
    }
    let id = header
        .kid
        .filter(|id| !id.is_empty() && id.len() <= 128)
        .ok_or(SocialGoogleError)?;
    let key = google_key(&id).await?;
    validate(token, &key, &audience, nonce)
}

fn validate(
    token: &str,
    key: &DecodingKey,
    audience: &str,
    nonce: &str,
) -> Result<String, SocialGoogleError> {
    let mut validation = Validation::new(Algorithm::RS256);
    validation.set_audience(&[audience]);
    validation.set_issuer(&["https://accounts.google.com", "accounts.google.com"]);
    validation.set_required_spec_claims(&["exp", "aud", "iss", "sub"]);
    validation.validate_nbf = true;
    validation.leeway = 15;
    let claims = decode::<Claims>(token, key, &validation)
        .map_err(|_| SocialGoogleError)?
        .claims;
    if claims.nonce != nonce || claims.sub.is_empty() || claims.sub.len() > 255 {
        return Err(SocialGoogleError);
    }
    Ok(claims.sub)
}

async fn google_key(id: &str) -> Result<DecodingKey, SocialGoogleError> {
    let mut cache = CACHE.lock().await;
    if cache
        .as_ref()
        .is_none_or(|entry| Instant::now() >= entry.expires)
    {
        let response = reqwest::Client::builder()
            .https_only(true)
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(5))
            .build()
            .map_err(|_| SocialGoogleError)?
            .get(GOOGLE_KEYS)
            .send()
            .await
            .and_then(reqwest::Response::error_for_status)
            .map_err(|_| SocialGoogleError)?;
        let max_age = response
            .headers()
            .get(reqwest::header::CACHE_CONTROL)
            .and_then(|header| header.to_str().ok())
            .and_then(|header| {
                header
                    .split(',')
                    .find_map(|part| part.trim().strip_prefix("max-age=")?.parse::<u64>().ok())
            })
            .unwrap_or(300)
            .min(3600);
        let keys = response
            .json::<GoogleKeys>()
            .await
            .map_err(|_| SocialGoogleError)?;
        *cache = Some(CachedKeys {
            expires: Instant::now() + Duration::from_secs(max_age),
            keys,
        });
    }
    let key = cache
        .as_ref()
        .and_then(|entry| {
            entry
                .keys
                .keys
                .iter()
                .find(|key| key.kid == id && key.kty == "RSA" && key.alg == "RS256")
        })
        .ok_or(SocialGoogleError)?;
    DecodingKey::from_rsa_components(&key.n, &key.e).map_err(|_| SocialGoogleError)
}
