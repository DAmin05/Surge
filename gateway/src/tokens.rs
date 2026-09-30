//! Stateless verification of Admission's Ed25519 JWTs: no network call per request.
//!
//! Each token type has its own key pairs and its own JWT header `typ`; a route accepts
//! exactly one type, so a session token can't be replayed as an admission token. The
//! keyset holds the current and previous key per type (zero-downtime rotation) and is
//! re-read periodically. Revoked `jti`s arrive on the `token-revocations` topic.

use std::{
    collections::HashMap,
    fs,
    path::Path,
    sync::{Arc, RwLock},
    time::{SystemTime, UNIX_EPOCH},
};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use dashmap::DashMap;
use ed25519_dalek::{pkcs8::DecodePublicKey, Signature, VerifyingKey};
use serde::Deserialize;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TokenType {
    Session,
    Admission,
}

impl TokenType {
    pub const ALL: [TokenType; 2] = [TokenType::Session, TokenType::Admission];

    /// JWT header `typ` (explicit typing, RFC 8725).
    pub fn header(self) -> &'static str {
        match self {
            TokenType::Session => "surge-session+jwt",
            TokenType::Admission => "surge-admission+jwt",
        }
    }

    fn dir(self) -> &'static str {
        match self {
            TokenType::Session => "session",
            TokenType::Admission => "admission",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Claims {
    pub sub: String,
    pub event_id: Option<i64>,
    pub exp: u64,
    pub jti: String,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Rejection {
    Malformed,
    WrongType,
    UnknownKey,
    BadSignature,
    Expired,
    Revoked,
}

#[derive(Deserialize)]
struct Header {
    alg: String,
    typ: String,
    kid: String,
}

#[derive(Deserialize)]
struct RawClaims {
    sub: String,
    #[serde(rename = "eventId")]
    event_id: Option<i64>,
    exp: u64,
    jti: String,
}

type KeySets = HashMap<TokenType, HashMap<String, VerifyingKey>>;

pub struct Verifier {
    keys: RwLock<Arc<KeySets>>,
    revoked: DashMap<String, u64>,
}

pub fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Public keys as laid out by `scripts/keys.sh`: `<type>/current`, `<type>/previous`,
/// `<type>/<kid>.pub`.
pub fn load_keys(root: &Path) -> Result<KeySets, String> {
    let mut sets = HashMap::new();
    for t in TokenType::ALL {
        let dir = root.join(t.dir());
        let mut keys = HashMap::new();
        for pointer in ["current", "previous"] {
            let Ok(kid) = fs::read_to_string(dir.join(pointer)) else {
                if pointer == "current" {
                    return Err(format!("no current {} key in {}", t.dir(), dir.display()));
                }
                continue;
            };
            let kid = kid.trim().to_string();
            let pem = match fs::read_to_string(dir.join(format!("{kid}.pub"))) {
                Ok(pem) => pem,
                Err(_) if pointer == "previous" => continue,
                Err(e) => return Err(format!("{kid}.pub: {e}")),
            };
            let key =
                VerifyingKey::from_public_key_pem(&pem).map_err(|e| format!("{kid}.pub: {e}"))?;
            keys.insert(kid, key);
        }
        sets.insert(t, keys);
    }
    Ok(sets)
}

impl Verifier {
    pub fn new(keys: KeySets) -> Self {
        Self {
            keys: RwLock::new(Arc::new(keys)),
            revoked: DashMap::new(),
        }
    }

    pub fn replace_keys(&self, keys: KeySets) {
        *self.keys.write().expect("keys lock") = Arc::new(keys);
    }

    pub fn revoke(&self, jti: String, exp: u64) {
        self.revoked.insert(jti, exp);
    }

    /// Denylist entries only matter until the token would have expired anyway.
    pub fn purge_revocations(&self) {
        let now = now_secs();
        self.revoked.retain(|_, exp| *exp > now);
    }

    pub fn verify(&self, typ: TokenType, token: &str) -> Result<Claims, Rejection> {
        let mut parts = token.split('.');
        let (Some(h), Some(p), Some(s), None) =
            (parts.next(), parts.next(), parts.next(), parts.next())
        else {
            return Err(Rejection::Malformed);
        };
        let header: Header = decode_json(h)?;
        if header.alg != "EdDSA" || header.typ != typ.header() {
            return Err(Rejection::WrongType);
        }
        let keys = self.keys.read().expect("keys lock").clone();
        let key = keys
            .get(&typ)
            .and_then(|k| k.get(&header.kid))
            .ok_or(Rejection::UnknownKey)?;
        let sig_bytes = URL_SAFE_NO_PAD
            .decode(s)
            .map_err(|_| Rejection::Malformed)?;
        let sig = Signature::from_slice(&sig_bytes).map_err(|_| Rejection::Malformed)?;
        let signed = &token[..h.len() + 1 + p.len()];
        key.verify_strict(signed.as_bytes(), &sig)
            .map_err(|_| Rejection::BadSignature)?;
        let claims: RawClaims = decode_json(p)?;
        if claims.exp <= now_secs() {
            return Err(Rejection::Expired);
        }
        if self.revoked.contains_key(&claims.jti) {
            return Err(Rejection::Revoked);
        }
        Ok(Claims {
            sub: claims.sub,
            event_id: claims.event_id,
            exp: claims.exp,
            jti: claims.jti,
        })
    }
}

fn decode_json<T: for<'de> Deserialize<'de>>(part: &str) -> Result<T, Rejection> {
    let bytes = URL_SAFE_NO_PAD
        .decode(part)
        .map_err(|_| Rejection::Malformed)?;
    serde_json::from_slice(&bytes).map_err(|_| Rejection::Malformed)
}

#[cfg(test)]
pub(crate) mod testing {
    //! Signs tokens the way Admission does, with keys from the real keys.sh.
    use super::*;
    use ed25519_dalek::{pkcs8::DecodePrivateKey, Signer, SigningKey};
    use std::path::PathBuf;

    pub struct Keys {
        pub dir: tempfile::TempDir,
    }

    impl Keys {
        pub fn generate() -> Self {
            let dir = tempfile::tempdir().unwrap();
            let script = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../scripts/keys.sh");
            let status = std::process::Command::new("sh")
                .arg(script)
                .arg(dir.path())
                .stdout(std::process::Stdio::null())
                .status()
                .unwrap();
            assert!(status.success());
            Keys { dir }
        }

        pub fn sign(&self, typ: TokenType, claims: serde_json::Value) -> String {
            let d = self.dir.path().join(typ.dir());
            let kid = fs::read_to_string(d.join("current"))
                .unwrap()
                .trim()
                .to_string();
            let key = SigningKey::from_pkcs8_pem(
                &fs::read_to_string(d.join(format!("{kid}.key"))).unwrap(),
            )
            .unwrap();
            let header = serde_json::json!({"alg": "EdDSA", "typ": typ.header(), "kid": kid});
            let input = format!(
                "{}.{}",
                URL_SAFE_NO_PAD.encode(header.to_string()),
                URL_SAFE_NO_PAD.encode(claims.to_string())
            );
            let sig = key.sign(input.as_bytes());
            format!("{input}.{}", URL_SAFE_NO_PAD.encode(sig.to_bytes()))
        }

        pub fn verifier(&self) -> Verifier {
            Verifier::new(load_keys(self.dir.path()).unwrap())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::testing::Keys;
    use super::*;
    use serde_json::json;

    fn claims(exp_offset: i64) -> serde_json::Value {
        json!({"sub": "u1", "eventId": 42, "iat": now_secs(), "exp": (now_secs() as i64 + exp_offset), "jti": "j1"})
    }

    #[test]
    fn accepts_a_valid_admission_token() {
        let keys = Keys::generate();
        let v = keys.verifier();
        let token = keys.sign(TokenType::Admission, claims(600));
        let c = v.verify(TokenType::Admission, &token).unwrap();
        assert_eq!((c.sub.as_str(), c.event_id), ("u1", Some(42)));
    }

    #[test]
    fn a_session_token_is_not_an_admission_token() {
        let keys = Keys::generate();
        let v = keys.verifier();
        let session = keys.sign(
            TokenType::Session,
            json!({"sub": "u1", "exp": now_secs() + 60, "jti": "s"}),
        );
        assert_eq!(
            v.verify(TokenType::Admission, &session),
            Err(Rejection::WrongType)
        );
        assert!(v.verify(TokenType::Session, &session).is_ok());
    }

    #[test]
    fn rejects_tampered_expired_and_revoked_tokens() {
        let keys = Keys::generate();
        let v = keys.verifier();
        let token = keys.sign(TokenType::Admission, claims(600));

        let (head, rest) = token.split_once('.').unwrap();
        let (_, sig) = rest.split_once('.').unwrap();
        let forged_claims =
            URL_SAFE_NO_PAD.encode(claims(600).to_string().replace("\"u1\"", "\"u2\""));
        let forged = format!("{head}.{forged_claims}.{sig}");
        assert_eq!(
            v.verify(TokenType::Admission, &forged),
            Err(Rejection::BadSignature)
        );

        let expired = keys.sign(TokenType::Admission, claims(-1));
        assert_eq!(
            v.verify(TokenType::Admission, &expired),
            Err(Rejection::Expired)
        );

        v.revoke("j1".into(), now_secs() + 600);
        assert_eq!(
            v.verify(TokenType::Admission, &token),
            Err(Rejection::Revoked)
        );
        assert_eq!(
            v.verify(TokenType::Admission, "a.b"),
            Err(Rejection::Malformed)
        );
    }

    #[test]
    fn previous_key_stays_valid_after_rotation() {
        let keys = Keys::generate();
        let before = keys.sign(TokenType::Admission, claims(600));
        let script =
            std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../scripts/keys.sh");
        std::thread::sleep(std::time::Duration::from_millis(1100));
        let ok = std::process::Command::new("sh")
            .arg(script)
            .arg("--rotate")
            .arg(keys.dir.path())
            .stdout(std::process::Stdio::null())
            .status()
            .unwrap();
        assert!(ok.success());
        let v = keys.verifier();
        assert!(v.verify(TokenType::Admission, &before).is_ok());
        assert!(v
            .verify(
                TokenType::Admission,
                &keys.sign(TokenType::Admission, claims(600))
            )
            .is_ok());
    }
}
