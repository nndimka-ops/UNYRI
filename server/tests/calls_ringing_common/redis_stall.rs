//! A private RESP proxy; never pauses or reconfigures the shared Redis service.
use std::time::Duration;

use redis::aio::ConnectionManager;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::sync::{mpsc, watch};

pub struct RedisStall {
    pub client: ConnectionManager,
    observed: mpsc::UnboundedReceiver<()>,
    gate: watch::Sender<bool>,
    task: tokio::task::JoinHandle<()>,
}

impl RedisStall {
    /// Hold matching commands before execution, or execute them and withhold
    /// their replies. Other commands (including connection setup) pass through.
    pub async fn new(mut upstream: ConnectionManager, needle: &str, after_execution: bool) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (gate, mut paused) = watch::channel(true);
        let (observed_tx, observed) = mpsc::unbounded_channel();
        let needle = needle.as_bytes().to_vec();
        let task = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut stream = BufReader::new(stream);
            loop {
                let mut line = String::new();
                if stream.read_line(&mut line).await.unwrap() == 0 { break; }
                assert!(line.starts_with('*'), "RESP array: {line:?}");
                let count: usize = line[1..].trim().parse().unwrap();
                let mut args = Vec::new();
                for _ in 0..count {
                    line.clear();
                    stream.read_line(&mut line).await.unwrap();
                    assert!(line.starts_with('$'));
                    let len: usize = line[1..].trim().parse().unwrap();
                    let mut bytes = vec![0; len + 2];
                    stream.read_exact(&mut bytes).await.unwrap();
                    bytes.truncate(len);
                    args.push(bytes);
                }
                let matched = args.iter().any(|arg| arg.windows(needle.len()).any(|part| part == needle));
                if matched && !after_execution {
                    observed_tx.send(()).ok();
                    while *paused.borrow_and_update() {
                        if paused.changed().await.is_err() { return; }
                    }
                }
                let mut command = redis::cmd(std::str::from_utf8(&args[0]).unwrap());
                for arg in &args[1..] { command.arg(arg); }
                let response: redis::RedisResult<redis::Value> = command.query_async(&mut upstream).await;
                if matched && after_execution {
                    observed_tx.send(()).ok();
                    while *paused.borrow_and_update() {
                        if paused.changed().await.is_err() { return; }
                    }
                }
                let mut bytes = Vec::new();
                match response {
                    Ok(value) => encode(value, &mut bytes),
                    Err(err) => bytes.extend_from_slice(format!("-{} {}\r\n", err.code().unwrap_or("ERR"), err.detail().unwrap_or("proxy error")).as_bytes()),
                }
                if stream.get_mut().write_all(&bytes).await.is_err() { break; }
            }
        });
        let client = redis::Client::open(format!("redis://{addr}/")).unwrap()
            .get_connection_manager().await.unwrap();
        Self { client, observed, gate, task }
    }

    pub async fn wait_stalled(&mut self) {
        tokio::time::timeout(Duration::from_secs(2), self.observed.recv()).await
            .expect("request reached the controlled Redis stall").expect("proxy is alive");
    }

    pub async fn release(&self) {
        self.gate.send(false).unwrap();
        let pong: String = tokio::time::timeout(Duration::from_secs(2),
            redis::cmd("PING").query_async(&mut self.client.clone())).await.unwrap().unwrap();
        assert_eq!(pong, "PONG", "all delayed commands have now executed");
    }
}

impl Drop for RedisStall {
    fn drop(&mut self) { self.task.abort(); }
}

fn encode(value: redis::Value, out: &mut Vec<u8>) {
    match value {
        redis::Value::Nil => out.extend_from_slice(b"$-1\r\n"),
        redis::Value::Int(n) => out.extend_from_slice(format!(":{n}\r\n").as_bytes()),
        redis::Value::Data(data) => {
            out.extend_from_slice(format!("${}\r\n", data.len()).as_bytes());
            out.extend_from_slice(&data);
            out.extend_from_slice(b"\r\n");
        }
        redis::Value::Bulk(values) => {
            out.extend_from_slice(format!("*{}\r\n", values.len()).as_bytes());
            for value in values { encode(value, out); }
        }
        redis::Value::Status(text) => out.extend_from_slice(format!("+{text}\r\n").as_bytes()),
        redis::Value::Okay => out.extend_from_slice(b"+OK\r\n"),
    }
}
