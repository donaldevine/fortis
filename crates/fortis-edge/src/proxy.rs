//! Forward a request to a chain's upstream (a `fortis-index` instance for BTCB2, an
//! Esplora API for BTC) and return the raw response.

use anyhow::Result;
use tiny_http::Method;

pub struct Upstream {
    base: String,
    agent: ureq::Agent,
}

pub struct UpstreamResponse {
    pub status: u16,
    pub content_type: String,
    pub body: Vec<u8>,
}

impl Upstream {
    pub fn new(base: &str) -> Self {
        let mut b = ureq::AgentBuilder::new().timeout(std::time::Duration::from_secs(20));
        if let Ok(tls) = native_tls::TlsConnector::new() {
            b = b.tls_connector(std::sync::Arc::new(tls));
        }
        Self { base: base.trim_end_matches('/').to_string(), agent: b.build() }
    }

    /// `rest` is the path after `/{chain}` (no leading slash), `query` without `?`.
    pub fn forward(
        &self,
        method: &Method,
        rest: &str,
        query: &str,
        body: &[u8],
    ) -> Result<UpstreamResponse> {
        let url = if query.is_empty() {
            format!("{}/{}", self.base, rest)
        } else {
            format!("{}/{}?{}", self.base, rest, query)
        };
        let resp = match method {
            Method::Get => self.agent.get(&url).call(),
            Method::Post => self
                .agent
                .post(&url)
                .set("Content-Type", "text/plain")
                .send_bytes(body),
            _ => return Ok(UpstreamResponse { status: 405, content_type: "text/plain".into(), body: b"method not allowed".to_vec() }),
        };
        let (status, r) = match resp {
            Ok(r) => (r.status(), r),
            Err(ureq::Error::Status(code, r)) => (code, r),
            Err(e) => return Err(e.into()),
        };
        let content_type = r.content_type().to_string();
        let mut body = Vec::new();
        r.into_reader().read_to_end(&mut body)?;
        Ok(UpstreamResponse { status, content_type, body })
    }
}
