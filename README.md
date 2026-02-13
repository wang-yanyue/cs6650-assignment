## CS6650 Assignment 1 – ChatFlow

This repository contains my solution for **CS6650 Assignment 1: Building a WebSocket Chat Server and Client**.

### Repository Layout

- `server/`  
  Spring Boot WebSocket server (`/chat/{roomId}`) plus `/health` REST endpoint. Deployed to AWS EC2.

- `client-part1/`  
  Basic multithreaded WebSocket load-testing client (Part 2). Focused on throughput.

- `client-part2/`  
  Copy of `client-part1` with an additional metrics client (`LoadTestClientPart3`) that collects detailed per‑message latency and writes CSVs (For Performance Analysis Chart in Part 3).

- `results/`  
  Test results and analysis: console screenshots, charts, and CSV files.


---

## 1. Server (`server/`)

### Tech Stack
- Java 17
- Spring Boot 4.x
- Spring WebSocket

### Build

From the `server` folder:

```bash
cd server
mvn clean package -DskipTests
```

This creates `target/ChatFlow-0.0.1-SNAPSHOT.jar` (or similar).

### Run Locally

```bash
cd server
mvn spring-boot:run
```

or

```bash
cd server
java -jar target/ChatFlow-0.0.1-SNAPSHOT.jar
```

The server listens on **port 8081** (configured in `src/main/resources/application.properties`).

- WebSocket endpoint: `ws://localhost:8081/chat/{roomId}`
- Health endpoint: `http://localhost:8081/health`

### Deploy on EC2 (high level)

On local machine:

```bash
cd server
mvn clean package -DskipTests
scp -i "<path-to-key>.pem" target/ChatFlow-0.0.1-SNAPSHOT.jar ec2-user@<EC2_IP>:~/ChatFlow.jar
```

On EC2:

```bash
nohup java -jar ChatFlow.jar > chatflow.log 2>&1 &
```

Verify:

```bash
ss -tulpn | grep 8081
curl http://localhost:8081/health
```

---

## 2. Basic Client (`client-part1/`) – Part 2

Main class: `org.example.chatclient.LoadTestClient`

### Build

```bash
cd client-part1
mvn clean package
```

This produces a fat JAR in `target/`, e.g. `client-part1-0.0.1-SNAPSHOT.jar`.

### Run Against Local Server

```bash
cd client-part1
java -jar target/client-part1-0.0.1-SNAPSHOT.jar ws://localhost:8081/chat
```

### Run Against EC2 Server

```bash
cd client-part1
java -jar target/client-part1-0.0.1-SNAPSHOT.jar ws://<EC2_IP>:8081/chat
```

### Behavior

- **Warmup phase**: 32 threads × 1,000 messages each (32,000 messages).
- **Main phase**: 64 threads until 500,000 messages total are sent.
- Single `MessageGenerator` thread creates all messages and pushes them into a shared `BlockingQueue`.
- Each sender thread:
  - Opens one WebSocket connection to `/chat/1`
  - Sends a JOIN message once
  - Sends many TEXT messages from the queue
  - Sends a LEAVE message once
- Retries failed sends up to 5 times with exponential backoff.
- Prints:
  - Successful / failed messages
  - Total runtime and throughput
  - Connection / reconnect counts

---

## 3. Metrics Client (`client-part2/`) – Part 3

`client-part2` is a copy of `client-part1`, with the **metrics client**:

- `org.example.chatclient.LoadTestClientPart3`
  - Same warmup / main thread model as Part 2.
  - Tracks per‑message latency based on server echoes.
  - Writes detailed CSVs under `client-part2/results/`:
    - `part3-metrics.csv` – one row per message:
      `{timestamp, messageType, latencyMs, statusCode, roomId}`
    - `part3-throughput-buckets.csv` – throughput over time in 1‑second buckets.
  - Prints:
    - Mean / median / p95 / p99 / min / max latency
    - Throughput per room
    - Message type distribution

### Build

```bash
cd client-part2
mvn clean package
```

### Run

```bash
cd client-part2
java -cp target/client-part1-0.0.1-SNAPSHOT.jar org.example.chatclient.LoadTestClientPart3 ws://<EC2_IP>:8081/chat
```

After the run, open `results/part3-throughput-buckets.csv` in Excel/Sheets to create the throughput‑over‑time line chart.

---

## 4. Results (`results/`)

Top‑level `results/` is intended to store:

- Screenshots of:
  - Part 1 output (basic client summary)
  - Part 2 / Part 3 output (detailed metrics summary)
  - EC2 console showing deployment
- Exported charts:
  - Throughput over time (line chart)
  - Any additional latency charts
- Copies of key CSVs (optional) for easy access when grading.

Filenames:

- `part1-basic-output.png`
- `part2-detailed-metrics.png`
- `throughput-over-time.png`
- `ec2-instance.png`

---

## 5. Little’s Law Summary

For the main Part 2 run:

- Concurrency **L**: 64 WebSocket connections (64 sender threads).
- Measured throughput **λ**: ≈ 213,238 msg/s.
- Implied average time in system **W**: `W = L / λ ≈ 0.00030 s ≈ 0.30 ms/message`.

This closely matches the predicted throughput from Little’s Law, indicating the implementation behaves as expected under load.
