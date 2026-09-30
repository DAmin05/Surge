//! In-process token buckets (GCRA via `governor`), keyed per client IP and per user.

use std::{net::IpAddr, num::NonZeroU32};

use governor::{DefaultKeyedRateLimiter, Quota, RateLimiter};

pub struct Limits {
    ip: DefaultKeyedRateLimiter<IpAddr>,
    user: DefaultKeyedRateLimiter<String>,
    ws_connect: DefaultKeyedRateLimiter<IpAddr>,
}

/// `rate` per second, bursts of up to twice that.
fn quota(rate: u32) -> Quota {
    let r = NonZeroU32::new(rate.max(1)).expect("non-zero");
    let burst = NonZeroU32::new(rate.max(1).saturating_mul(2)).expect("non-zero");
    Quota::per_second(r).allow_burst(burst)
}

impl Limits {
    pub fn new(ip_rate: u32, user_rate: u32, ws_connect_rate: u32) -> Self {
        Self {
            ip: RateLimiter::keyed(quota(ip_rate)),
            user: RateLimiter::keyed(quota(user_rate)),
            ws_connect: RateLimiter::keyed(quota(ws_connect_rate)),
        }
    }

    pub fn ip(&self, ip: IpAddr) -> bool {
        self.ip.check_key(&ip).is_ok()
    }

    pub fn user(&self, user: &str) -> bool {
        self.user.check_key(&user.to_string()).is_ok()
    }

    pub fn ws_connect(&self, ip: IpAddr) -> bool {
        self.ws_connect.check_key(&ip).is_ok()
    }

    /// Forgets keys that have been idle long enough to be back at full burst.
    pub fn prune(&self) {
        self.ip.retain_recent();
        self.user.retain_recent();
        self.ws_connect.retain_recent();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allows_a_burst_then_limits() {
        let l = Limits::new(5, 5, 5);
        let ip: IpAddr = "10.0.0.1".parse().unwrap();
        let allowed = (0..20).filter(|_| l.ip(ip)).count();
        assert_eq!(allowed, 10);
        // Other clients are unaffected.
        assert!(l.ip("10.0.0.2".parse().unwrap()));
        assert!(l.user("alice"));
    }
}
