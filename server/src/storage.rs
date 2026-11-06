use chrono::{DateTime, Duration, Utc};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::config::S3Config;

type HmacSha256 = Hmac<Sha256>;

fn validate_copy_response(body: &[u8]) -> anyhow::Result<()> {
    let mut reader = quick_xml::Reader::from_reader(body);
    loop {
        match reader.read_event()? {
            quick_xml::events::Event::Decl(_) | quick_xml::events::Event::Text(_) => continue,
            quick_xml::events::Event::Start(root) => {
                anyhow::ensure!(root.name().as_ref() == b"CopyObjectResult", "S3 copy failed");
                break;
            }
            _ => anyhow::bail!("invalid S3 copy response"),
        }
    }
    #[derive(serde::Deserialize)]
    struct CopyResult { #[serde(rename="ETag")] etag: String }
    let result: CopyResult = quick_xml::de::from_reader(body)?;
    anyhow::ensure!(!result.etag.is_empty(), "missing copy ETag");
    Ok(())
}

/// Minimal, dependency-light AWS SigV4 client for a private S3-compatible
/// bucket (MinIO locally, any real S3-compatible provider in production -
/// nothing here is MinIO-specific, it's the standard S3 REST API). The
/// server NEVER proxies attachment bytes: it only signs short-lived
/// presigned PUT/GET URLs that Android talks to directly, and makes one
/// authenticated HEAD request itself to verify an upload actually landed
/// (see `head_object`, used by `POST /attachments/{id}/complete`).
#[derive(Clone)]
pub struct S3Client {
    config: S3Config,
    http: reqwest::Client,
    upload_bucket_verified: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

impl S3Client {
    pub fn new(config: S3Config) -> Self {
        // Bounded HTTP client (mirrors the FCM transport): a stalled or
        // unreachable S3 endpoint must fail fast instead of hanging the
        // attachment/avatar completion requests that await `head_object`
        // synchronously. This client only ever performs lightweight control
        // requests (HEAD to verify an upload, DELETE for orphan cleanup,
        // bucket existence/creation) - it NEVER transfers attachment bytes
        // (those go client -> S3 directly via presigned URLs), so a 30s
        // total timeout cannot break a legitimate upload.
        let http = reqwest::Client::builder()
            .connect_timeout(std::time::Duration::from_secs(10))
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .expect("reqwest client builder cannot fail with these options");
        Self { config, http, upload_bucket_verified: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)) }
    }

    /// Opaque, random, non-guessable object key - deliberately carries no
    /// user id/chat id/filename (see Этап H п.7).
    pub fn generate_object_key(&self) -> String {
        format!("attachments/{}", Uuid::new_v4())
    }

    fn upload_signer(&self) -> Result<Self, crate::error::AppError> {
        if !self.upload_bucket_verified.load(std::sync::atomic::Ordering::Acquire)
            || !(1..=7200).contains(&self.config.max_upload_duration_seconds)
            || self.config.upload_access_key_id.is_empty()
            || self.config.upload_secret_access_key.is_empty()
            || self.config.upload_access_key_id == self.config.access_key_id {
            return Err(crate::error::AppError::Unavailable("Uploads require a verified unversioned bucket, a bounded upload ingress and a separate write-only signing principal.".into()));
        }
        let mut signer=self.clone();
        signer.config.access_key_id=self.config.upload_access_key_id.clone();
        signer.config.secret_access_key=self.config.upload_secret_access_key.clone();
        Ok(signer)
    }

    pub fn require_client_uploads(&self) -> Result<(),crate::error::AppError> {
        self.upload_signer().map(|_|())
    }

    pub fn presign_put_sized(&self, object_key: &str, size: i64) -> Result<(String, DateTime<Utc>),crate::error::AppError> {
        Ok(self.upload_signer()?.presign_query_sized("PUT", &self.object_uri(object_key), self.config.presign_ttl_seconds,
            &self.config.public_endpoint_url, &[], Some(size)))
    }

    pub fn presign_get(&self, object_key: &str) -> (String, DateTime<Utc>) {
        self.presign(
            "GET",
            &self.object_uri(object_key),
            self.config.presign_ttl_seconds,
            &self.config.public_endpoint_url,
        )
    }

    /// Real content-length of the object if it exists, `None` on 404. Used
    /// to verify an upload actually completed with the declared size before
    /// the attachment becomes referenceable by a message.
    pub async fn head_object(&self, object_key: &str) -> anyhow::Result<Option<u64>> {
        let (url, _) = self.presign("HEAD", &self.object_uri(object_key), 60, &self.config.endpoint_url);
        let response = self.http.head(url).send().await?;
        if response.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        if !response.status().is_success() {
            anyhow::bail!("unexpected S3 status checking object: {}", response.status());
        }
        let length = response
            .headers()
            .get(reqwest::header::CONTENT_LENGTH)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<u64>().ok());
        Ok(length)
    }

    /// Avatar processing uses the private endpoint, never an arbitrary user URL.
    pub async fn read_avatar(&self, key: &str) -> anyhow::Result<Vec<u8>> {
        const MAX: usize = 3 * 1024 * 1024;
        let (url, _) = self.presign("GET", &self.object_uri(key), 60, &self.config.endpoint_url);
        let mut response = self.http.get(url).send().await?.error_for_status()?;
        if response.content_length().is_some_and(|n| n > MAX as u64) { anyhow::bail!("avatar too large"); }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            if chunk.len() > MAX.saturating_sub(bytes.len()) { anyhow::bail!("avatar too large"); }
            bytes.extend_from_slice(&chunk);
        }
        Ok(bytes)
    }

    pub async fn write_avatar(&self, key: &str, bytes: Vec<u8>) -> anyhow::Result<()> {
        anyhow::ensure!(self.upload_bucket_verified.load(std::sync::atomic::Ordering::Acquire),"unverified upload bucket");
        if bytes.is_empty() || bytes.len()>3*1024*1024 {anyhow::bail!("avatar rendition exceeds bounded size");}
        let (url, _) = self.presign_query_sized("PUT", &self.object_uri(key), 60, &self.config.endpoint_url,&[],Some(bytes.len() as i64));
        self.http.put(url).header("Content-Type", "image/jpeg").body(bytes)
            .send().await?.error_for_status()?;
        Ok(())
    }

    /// Best-effort delete, used by the orphan-cleanup sweep only.
    pub async fn delete_object(&self, object_key: &str) -> anyhow::Result<()> {
        // A versionless DELETE must not release a ledger entry while hidden
        // versions still consume storage. Recheck at the destructive boundary.
        self.verify_unversioned_bucket().await?;
        let (url, _) = self.presign("DELETE", &self.object_uri(object_key), 60, &self.config.endpoint_url);
        let response = self.http.delete(url).send().await?;
        if !response.status().is_success() && response.status() != reqwest::StatusCode::NOT_FOUND {
            anyhow::bail!("unexpected S3 status deleting object: {}", response.status());
        }
        if let Some(id) = object_key.strip_prefix("avatars/").filter(|id| Uuid::parse_str(id).is_ok()) {
            for size in [96, 256, 640] {
                let derived = format!("avatar-renditions/{id}/size-{size}.jpg");
                let (url, _) = self.presign("DELETE", &self.object_uri(&derived), 60, &self.config.endpoint_url);
                let result = self.http.delete(url).send().await?;
                if !result.status().is_success() && result.status() != reqwest::StatusCode::NOT_FOUND {
                    anyhow::bail!("failed to delete avatar rendition");
                }
            }
        }
        Ok(())
    }

    /// Self-bootstrapping: creates the private bucket if it doesn't exist
    /// yet (dev convenience - see Этап H п.6, "либо документировать
    /// bootstrap"). Best-effort/idempotent - a bucket that already exists
    /// is left alone, any other failure is logged but never fatal (the
    /// server can still start; attachment calls will just fail until the
    /// bucket is created some other way).
    pub async fn ensure_bucket(&self) -> anyhow::Result<()> {
        let bucket_uri = format!("/{}", self.config.bucket);
        let (head_url, _) = self.presign("HEAD", &bucket_uri, 60, &self.config.endpoint_url);
        if self.http.head(&head_url).send().await?.status().is_success() {
            return self.verify_unversioned_bucket().await;
        }
        let (put_url, _) = self.presign("PUT", &bucket_uri, 60, &self.config.endpoint_url);
        let response = self.http.put(&put_url).send().await?;
        // 409 Conflict = BucketAlreadyOwnedByYou/BucketAlreadyExists - the
        // HEAD check above is best-effort only, this is the real source of
        // truth for "does it already exist".
        if !response.status().is_success() && response.status() != reqwest::StatusCode::CONFLICT {
            anyhow::bail!("failed to create bucket: {}", response.status());
        }
        self.verify_unversioned_bucket().await
    }

    pub async fn verify_unversioned_bucket(&self) -> anyhow::Result<()> {
        let result=self.check_unversioned_bucket().await;
        self.upload_bucket_verified.store(result.is_ok(),std::sync::atomic::Ordering::Release);
        result
    }

    async fn check_unversioned_bucket(&self) -> anyhow::Result<()> {
        let (url,_)=self.presign_query("GET", &format!("/{}",self.config.bucket),60,&self.config.endpoint_url,&[("versioning","")]);
        let mut response=self.http.get(url).send().await?.error_for_status()?;
        let mut body=Vec::new();
        while let Some(chunk)=response.chunk().await? {
            anyhow::ensure!(chunk.len()<=4096usize.saturating_sub(body.len()),"invalid bucket versioning response");
            body.extend_from_slice(&chunk);
        }
        let mut reader=quick_xml::Reader::from_reader(body.as_slice());
        loop {match reader.read_event()? {
            quick_xml::events::Event::Decl(_)|quick_xml::events::Event::Text(_)=>continue,
            quick_xml::events::Event::Start(e)|quick_xml::events::Event::Empty(e)=> {anyhow::ensure!(e.local_name().as_ref()==b"VersioningConfiguration","invalid bucket versioning response");break;},
            _=>anyhow::bail!("invalid bucket versioning response"),
        }}
        #[derive(serde::Deserialize)]
        struct Versioning { #[serde(rename="Status")] status: Option<String> }
        let configuration:Versioning=quick_xml::de::from_reader(body.as_slice())?;
        anyhow::ensure!(configuration.status.is_none(),"UNYRI storage requires a bucket that has never enabled versioning; Enabled and Suspended are unsupported");
        Ok(())
    }

    fn object_uri(&self, object_key: &str) -> String {
        format!("/{}/{}", self.config.bucket, uri_encode(object_key, false))
    }

    fn presign(&self, method: &str, canonical_uri: &str, ttl_seconds: i64, endpoint_url: &str) -> (String, DateTime<Utc>) {
        self.presign_query(method, canonical_uri, ttl_seconds, endpoint_url, &[])
    }

    fn presign_query(&self, method: &str, canonical_uri: &str, ttl_seconds: i64, endpoint_url: &str, extra: &[(&str, &str)]) -> (String, DateTime<Utc>) {
        self.presign_query_sized(method, canonical_uri, ttl_seconds, endpoint_url, extra, None)
    }
    fn presign_query_sized(&self, method: &str, canonical_uri: &str, ttl_seconds: i64, endpoint_url: &str, extra: &[(&str, &str)], size: Option<i64>) -> (String, DateTime<Utc>) {
        self.presign_headers(method, canonical_uri, ttl_seconds, endpoint_url, extra, size, None)
    }
    fn presign_headers(&self, method: &str, canonical_uri: &str, ttl_seconds: i64, endpoint_url: &str, extra: &[(&str, &str)], size: Option<i64>, copy_source: Option<&str>) -> (String, DateTime<Utc>) {
        let signed_headers = if copy_source.is_some() { "host;x-amz-copy-source" } else if size.is_some() { "content-length;host" } else { "host" };
        let now = Utc::now();
        let amz_date = now.format("%Y%m%dT%H%M%SZ").to_string();
        let date_stamp = now.format("%Y%m%d").to_string();
        let credential_scope = format!("{date_stamp}/{}/s3/aws4_request", self.config.region);
        let credential = format!("{}/{credential_scope}", self.config.access_key_id);

        let (scheme, host) = scheme_and_host(endpoint_url);

        let mut query_pairs = vec![
            ("X-Amz-Algorithm".to_string(), "AWS4-HMAC-SHA256".to_string()),
            ("X-Amz-Credential".to_string(), credential),
            ("X-Amz-Date".to_string(), amz_date.clone()),
            ("X-Amz-Expires".to_string(), ttl_seconds.to_string()),
            ("X-Amz-SignedHeaders".to_string(), signed_headers.to_string()),
        ];
        query_pairs.extend(extra.iter().map(|(k,v)| (k.to_string(), v.to_string())));
        query_pairs.sort();
        let canonical_query_string = query_pairs
            .iter()
            .map(|(k, v)| format!("{}={}", uri_encode(k, true), uri_encode(v, true)))
            .collect::<Vec<_>>()
            .join("&");

        let canonical_headers = match (size,copy_source) { (_,Some(source)) => format!("host:{host}\nx-amz-copy-source:{source}\n"), (Some(n),_) => format!("content-length:{n}\nhost:{host}\n"), _ => format!("host:{host}\n") };
        let canonical_request = format!(
            "{method}\n{canonical_uri}\n{canonical_query_string}\n{canonical_headers}\n{signed_headers}\nUNSIGNED-PAYLOAD"
        );
        let string_to_sign = format!(
            "AWS4-HMAC-SHA256\n{amz_date}\n{credential_scope}\n{}",
            sha256_hex(canonical_request.as_bytes())
        );

        let k_date = hmac_sha256(format!("AWS4{}", self.config.secret_access_key).as_bytes(), date_stamp.as_bytes());
        let k_region = hmac_sha256(&k_date, self.config.region.as_bytes());
        let k_service = hmac_sha256(&k_region, b"s3");
        let k_signing = hmac_sha256(&k_service, b"aws4_request");
        let signature = hex::encode(hmac_sha256(&k_signing, string_to_sign.as_bytes()));

        let url = format!("{scheme}://{host}{canonical_uri}?{canonical_query_string}&X-Amz-Signature={signature}");
        (url, now + Duration::seconds(ttl_seconds))
    }
}

impl S3Client {
    /// Copies into a key for which no public PUT URL has ever been issued.
    /// S3 can return an Error document with status 200; require the result root
    /// and ETag, and bound the XML body before parsing.
    pub async fn copy_object(&self, source: &str, destination: &str) -> anyhow::Result<()> {
        anyhow::ensure!(self.upload_bucket_verified.load(std::sync::atomic::Ordering::Acquire),"unverified upload bucket");
        let source = self.object_uri(source);
        let (url, _) = self.presign_headers("PUT", &self.object_uri(destination), 60,
            &self.config.endpoint_url, &[], None, Some(&source));
        let mut response = self.http.put(url).header("x-amz-copy-source", source)
            .body(Vec::new()).send().await?.error_for_status()?;
        let mut body = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            anyhow::ensure!(chunk.len() <= 16384usize.saturating_sub(body.len()), "copy response too large");
            body.extend_from_slice(&chunk);
        }
        validate_copy_response(&body)
    }

    /// Constant-memory ciphertext verification. No plaintext or media key is read.
    pub async fn verify_object_sha256(&self, key: &str, expected_bytes: i64, expected_hash: &str) -> anyhow::Result<()> {
        let (url, _) = self.presign("GET", &self.object_uri(key), 60, &self.config.endpoint_url);
        let mut response = self.http.get(url).send().await?.error_for_status()?;
        anyhow::ensure!(response.content_length() == Some(expected_bytes as u64), "backup media size mismatch");
        let mut received = 0u64;
        let mut hash = Sha256::new();
        while let Some(chunk) = response.chunk().await? {
            received += chunk.len() as u64;
            anyhow::ensure!(received <= expected_bytes as u64, "backup media too large");
            hash.update(&chunk);
        }
        anyhow::ensure!(received == expected_bytes as u64 && hex::encode(hash.finalize()) == expected_hash,
            "backup media checksum mismatch");
        Ok(())
    }

    pub async fn begin_multipart(&self, key: &str) -> anyhow::Result<String> {
        let (url, _) = self.presign_query("POST", &self.object_uri(key), 60, &self.config.endpoint_url, &[("uploads", "")]);
        let text = self.http.post(url).send().await?.error_for_status()?.text().await?;
        #[derive(serde::Deserialize)]
        struct Result { #[serde(rename="UploadId")] id: String }
        let result: Result = quick_xml::de::from_str(&text)?;
        anyhow::ensure!(!result.id.is_empty() && result.id.len() <= 4096, "invalid multipart id");
        Ok(result.id)
    }
    pub fn presign_part(&self, key: &str, upload: &str, part: i32, size: i64) -> Result<String,crate::error::AppError> {
        Ok(self.upload_signer()?.presign_query_sized("PUT", &self.object_uri(key), self.config.presign_ttl_seconds,
            &self.config.public_endpoint_url, &[("uploadId", upload), ("partNumber", &part.to_string())], Some(size)).0)
    }
    pub async fn finish_multipart(&self, key: &str, upload: &str, parts: Vec<(i32, String)>) -> anyhow::Result<()> {
        anyhow::ensure!(self.upload_bucket_verified.load(std::sync::atomic::Ordering::Acquire),"unverified upload bucket");
        #[derive(serde::Serialize)]
        struct Part { #[serde(rename="PartNumber")] number: i32, #[serde(rename="ETag")] etag: String }
        #[derive(serde::Serialize)]
        #[serde(rename="CompleteMultipartUpload")]
        struct Complete { #[serde(rename="Part")] parts: Vec<Part> }
        let body = quick_xml::se::to_string(&Complete { parts: parts.into_iter().map(|(number,etag)| Part { number,etag }).collect() })?;
        let (url, _) = self.presign_query("POST", &self.object_uri(key), 60, &self.config.endpoint_url, &[("uploadId", upload)]);
        let text = self.http.post(url).header("content-type", "application/xml").body(body).send().await?.error_for_status()?.text().await?;
        // S3 may send HTTP 200 with an Error XML body. Require a result ETag.
        #[derive(serde::Deserialize)]
        struct Result { #[serde(rename="ETag")] etag: String }
        let result: Result = quick_xml::de::from_str(&text)?;
        anyhow::ensure!(!result.etag.is_empty(), "multipart completion failed");
        Ok(())
    }
    pub async fn abort_multipart(&self, key: &str, upload: &str) -> anyhow::Result<()> {
        let (url, _) = self.presign_query("DELETE", &self.object_uri(key), 60, &self.config.endpoint_url, &[("uploadId", upload)]);
        let response = self.http.delete(url).send().await?;
        if response.status() != reqwest::StatusCode::NOT_FOUND { response.error_for_status()?; }
        Ok(())
    }
}

fn scheme_and_host(endpoint: &str) -> (&str, &str) {
    if let Some(rest) = endpoint.strip_prefix("https://") {
        ("https", rest)
    } else if let Some(rest) = endpoint.strip_prefix("http://") {
        ("http", rest)
    } else {
        ("http", endpoint)
    }
}

fn hmac_sha256(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("HMAC accepts a key of any length");
    mac.update(data);
    mac.finalize().into_bytes().to_vec()
}

fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}

/// AWS URI-encoding rules: unreserved chars (`A-Za-z0-9-_.~`) pass through;
/// everything else (including `/`) is percent-encoded UNLESS `keep_slash` is
/// false and the char is `/` (object keys keep their path separators
/// unescaped in the canonical URI, but query keys/values escape `/` too).
fn uri_encode(s: &str, encode_slash: bool) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        let c = b as char;
        if c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | '~') {
            out.push(c);
        } else if c == '/' && !encode_slash {
            out.push(c);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

#[cfg(test)]
mod backup_copy_tests {
    use super::validate_copy_response;
    #[test]
    fn requires_copy_result_not_http_200_error_xml() {
        assert!(validate_copy_response(b"<?xml version=\"1.0\"?><CopyObjectResult><ETag>\"test\"</ETag><LastModified>2026-09-26</LastModified></CopyObjectResult>").is_ok());
        assert!(validate_copy_response(b"<Error><Code>InternalError</Code><ETag>fake</ETag></Error>").is_err());
        assert!(validate_copy_response(b"<CopyObjectResult><ETag></ETag></CopyObjectResult>").is_err());
        assert!(validate_copy_response(b"<CopyObjectResult><LastModified>today</LastModified></CopyObjectResult>").is_err());
        assert!(validate_copy_response(b"").is_err());
    }
}

#[cfg(test)]
mod upload_principal_tests {
    use super::*;
    #[test]
    fn client_puts_never_fall_back_to_the_privileged_reader() {
        let config=S3Config {max_upload_duration_seconds:30,upload_access_key_id:String::new(),upload_secret_access_key:String::new(),endpoint_url:"http://127.0.0.1:9000".into(),public_endpoint_url:"http://127.0.0.1:9000".into(),region:"us-east-1".into(),bucket:"fixture".into(),access_key_id:"privileged-reader".into(),secret_access_key:"reader-secret".into(),presign_ttl_seconds:60};
        let client=S3Client::new(config.clone());
        assert!(client.presign_put_sized("avatars/fixture",32).is_err());
        assert!(client.presign_part("attachments/fixture","upload",1,32).is_err());
        assert!(client.presign_get("avatars/fixture").0.contains("privileged-reader"));
        let mut upload=config;upload.upload_access_key_id="upload-only".into();upload.upload_secret_access_key="upload-secret".into();
        let client=S3Client::new(upload.clone());
        assert!(client.presign_put_sized("avatars/fixture",32).is_err());
        client.upload_bucket_verified.store(true,std::sync::atomic::Ordering::Release);
        assert!(client.presign_put_sized("avatars/fixture",32).unwrap().0.contains("upload-only"));
        assert!(client.presign_part("attachments/fixture","upload",1,32).unwrap().contains("upload-only"));
        upload.upload_access_key_id=upload.access_key_id.clone();
        let client=S3Client::new(upload);client.upload_bucket_verified.store(true,std::sync::atomic::Ordering::Release);
        assert!(client.presign_put_sized("avatars/fixture",32).is_err());
    }
}
