# FX Exchange System — Enterprise API Specification

## 1. System Overview

### 1.1 Purpose

Hệ thống cung cấp nền tảng xử lý **giao dịch ngoại tệ** (**Foreign Exchange - FX**) theo mô hình **Microservices** và **Event-Driven Architecture**, hỗ trợ:

- Mua bán ngoại tệ
- Tạm giữ tiền (**Hold Balance**)
- Hạch toán kép (**Double Entry Accounting**)
- Xử lý bất đồng bộ qua **Message Queue**

### 1.2 Service Map

| Service                  | Responsibility                                                                          |
| ------------------------ | --------------------------------------------------------------------------------------- |
| **SF Service**           | Receive request, validate basic request, check idempotency, push MQ (Gateway)           |
| **SF Processor Service** | Execute FX workflow, store transaction result, expose GET transaction query API          |
| **Treasury Service**     | Provide exchange rate                                                                   |
| **Core Service**         | Account validation, hold, ledger posting                                                |

---

## 2. Business Flow

> NOTE:
> Xem sequence diagram đầy đủ tại: `sell-foreign-sequence-diagram.drawio`

1. Client gửi yêu cầu đổi ngoại tệ
2. SF Service validate request + check idempotency
3. Generate tx_id, save transaction (PENDING), trả response
4. Push message vào MQ
5. Trả API accepted 202
6. SF Processor consume message
7. Init transaction (PROCESSING) + transaction detail
8. Check balance + Create HOLD (ghi nợ sổ cái)
9. SF Processor check tỉ giá qua Treasury
10. Treasury trả về rate_exchange cho SF Processor cập nhật
11. SF Processor gọi Core Banking thực hiện quy trình cộng trừ tiền
12. Sau khi hoàn tất, Core Banking trả về SF Processor
13. SF Processor Update transaction SUCCESS
14. Publish NotificationEvent → Notification Service
15. Client nhận thông báo → tự gọi GET /api/v1/fx/transactions/{tx_id} vào **SF Processor** để lấy kết quả


---

## 3. Data Entity

### 3.A Table: `transaction` (SF Service quản lý)

- **`tx_id`** (`UUID`, PK): Mã giao dịch hệ thống
- **`idempotency_key`** (`UUID`): Mã định danh từ Client để chống trùng lặp
- **`owner_id`** (`UUID`): ID khách hàng
- **`status`** (`Enum`): `PROCESSING`, `SUCCESS`, `FAILED`

### 3.B Table: `transaction_detail` (SF Processor quản lý)

- **`tx_detail_id`** (`UUID`, PK): ID chi tiết
- **`tx_id`** (`UUID`, FK): Liên kết bảng `transaction`
- **`currency_base`** (`Enum`): Tiền tệ gốc (VD: `USD`)
- **`currency_target`** (`Enum`): Tiền tệ đích (VD: `VND`)
- **`amount`** (`BigDecimal`): Số tiền gốc
- **`rate_exchange`** (`BigDecimal`): Tỷ giá tại thời điểm giao dịch (từ Treasury). Hiệu lực trong 30 giây.
- **`converted_amount`** (`BigDecimal`): Số tiền sau quy đổi

### 3.C Table: `account` (Core Banking Service quản lý)

- **`account_number_id`** (`UUID`): Số tài khoản
- **`owner_id`** (`UUID`): Chủ tài khoản
- **`currency`** (`Enum`): Currency của account
- **`available_balance`** (`BigDecimal`): Số dư khả dụng
- **`held_balance`** (`BigDecimal`): Số dư bị HOLD

### 3.D Table: `entry` (Core Banking Service quản lý)

- **`entry_id`** (`UUID`): Entry ID
- **`tx_id`** (`UUID`): Transaction ID
- **`account_number_id`** (`UUID`): Account number
- **`owner_id`** (`UUID`): Chủ tài khoản
- **`currency`** (`Enum`): Currency của account
- **`amount`** (`BigDecimal`): Số tiền xử lý
- **`type`** (`Enum`): `HOLD`, `RELEASE`, `DEBIT`, `CREDIT`
- **`created_at`** (`LocalDateTime`): Thời gian tạo

---

## 4. API Specifications

### 4.1 Execute FX Transaction

- **Endpoint:** `POST /api/v1/fx/exchange`
- **Description:** Khởi tạo giao dịch đổi ngoại tệ.

#### Request Body

```json
{
  "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
  "owner_id": 882291,
  "account_number_id": "0900231231",
  "base_currency": "USD",
  "target_currency": "VND",
  "amount": 500.0
}
```

#### Request Fields

| Field               | Type         | Required | Mô tả                                               |
| ------------------- | ------------ | -------- | --------------------------------------------------- |
| `idempotency_key`   | `UUID`       | Required | Client tự generate. Dùng để check duplicate request |
| `owner_id`          | `Long`       | Required | ID số của chủ tài khoản                             |
| `account_number_id` | `String`     | Required | Số tài khoản nguồn                                  |
| `base_currency`     | `Enum`       | Required | Tiền tệ đang bán                                    |
| `target_currency`   | `Enum`       | Required | Tiền tệ nhận về                                     |
| `amount`            | `BigDecimal` | Required | Số lượng ngoại tệ muốn bán                          |

#### Success Response — `202 Accepted`

```json
{
  "timestamp": "2026-05-11T10:00:00Z",
  "status": "PROCESSING",
  "data": {
    "tx_id": "FX-UUID-12345",
    "message": "Transaction is being processed"
  }
}
```

---

### 4.2 Get Transaction Status

- **Endpoint:** `GET /api/v1/fx/transactions/{tx_id}`
- **Service:** SF Processor Service (port 8082)
- **Lưu ý:** Client gọi API này sau khi nhận notification từ hệ thống, hoặc chủ động polling.

#### Success Response — `200 OK`

```json
{
  "tx_id": "FX-UUID-12345",
  "status": "SUCCESS",
  "base_currency": "USD",
  "source_amount": 500,
  "rate_exchange": 25450,
  "target_currency": "VND",
  "converted_amount": 12725000,
  "created_at": "2026-05-11T10:00:00Z",
  "completed_at": "2026-05-11T10:00:05Z"
}
```

#### Response Fields

| Field              | Type                | Required      | Mô tả                                             |
| ------------------ | ------------------- | ------------- | ------------------------------------------------- |
| `tx_id`            | `String (UUID)`     | Always        | Mã giao dịch hệ thống                             |
| `status`           | `String (Enum)`     | Always        | Trạng thái: `PROCESSING` \| `SUCCESS` \| `FAILED` |
| `base_currency`    | `String`            | Always        | Tiền tệ đang bán                                  |
| `source_amount`    | `BigDecimal`        | Always        | Số lượng ngoại tệ đã bán                          |
| `rate_exchange`    | `BigDecimal`        | Khi `SUCCESS` | Tỷ giá áp dụng từ Treasury                        |
| `target_currency`  | `String`            | Always        | Tiền tệ nhận về                                   |
| `converted_amount` | `BigDecimal`        | Khi `SUCCESS` | Số tiền VND nhận được                             |
| `created_at`       | `String (ISO 8601)` | Always        | Thời điểm tạo giao dịch (UTC)                     |
| `completed_at`     | `String (ISO 8601)` | Nullable      | Thời điểm hoàn tất. `null` khi `PROCESSING`       |

---

## 5. Validation Rules

### 5.1 Request Validation

| Field                              | Rule                                     | Error Code                  | HTTP |
| ---------------------------------- | ---------------------------------------- | --------------------------- | ---- |
| `owner_id`                         | Required, Not Blank                      | `MISSING_FIELD`             | 400  |
| `account_number_id`                | Required, Not Blank                      | `MISSING_FIELD`             | 400  |
| `amount`                           | Required, Not Null                       | `MISSING_FIELD`             | 400  |
| `amount`                           | Must be > 0                              | `AMOUNT_NOT_POSITIVE`       | 400  |
| `amount`                           | Minimum 1.00 currency unit               | `AMOUNT_TOO_SMALL`          | 400  |
| `amount`                           | Based on Daily Limit / Account Limit     | `AMOUNT_EXCEEDS_LIMIT`      | 400  |
| `base_currency`                    | Required, supported: `VND`, `USD`, `JPY` | `UNSUPPORTED_CURRENCY_PAIR` | 400  |
| `target_currency`                  | Required, supported: `VND`, `USD`, `JPY` | `UNSUPPORTED_CURRENCY_PAIR` | 400  |
| `base_currency != target_currency` | Not the same currency                    | `SAME_CURRENCY`             | 400  |

### 5.2 Currency Validation

| Rule                               | Description                   |
| ---------------------------------- | ----------------------------- |
| `base_currency != target_currency` | Cannot exchange same currency |
| Supported pair                     | Must exist in Treasury        |

### 5.3 Account Validation

| Rule                | Description               |
| ------------------- | ------------------------- |
| Account exists      | Must exist in system      |
| Account active      | Cannot be closed          |
| Account not frozen  | Must be usable            |
| Account owner match | Must belong to `owner_id` |

### 5.4 Balance Validation

> IMPORTANT:
> Điều kiện hợp lệ: `available_balance >= amount + fee`

### 5.5 Rate Validation

| Rule             | Description        |
| ---------------- | ------------------ |
| Rate not expired | Within 30 seconds  |
| Rate exists      | Treasury available |

---

## 6. Business Rules

### 6.1 Cut-off Time

FX transaction chỉ được thực hiện trong khung giờ:

- **Giờ:** `08:00 → 16:30`
- **Ngày:** `Monday → Friday`

> WARNING:
> Ngoài thời gian này: `403 Forbidden` — `FX_ERR_005`

### 6.2 FX Daily Limit

| Customer Type | Daily Limit |
| ------------- | ----------- |
| Cá nhân       | 5,000 USD   |
| Tổ chức       | 100,000 USD |

### 6.3 Rate Expiry

> IMPORTANT:
> FX rate chỉ có hiệu lực trong **30 giây** kể từ thời điểm lấy từ Treasury.

### 6.4 Transaction Fee

| Scenario   | Fee |
| ---------- | --- |
| Same owner | 0%  |

---

## 7. HTTP Status Codes

| HTTP  | Meaning                    |
| ----- | -------------------------- |
| `200` | Success                    |
| `202` | Accepted                   |
| `400` | Invalid request            |
| `401` | Unauthorized               |
| `403` | Forbidden                  |
| `404` | Not found                  |
| `409` | Duplicate request          |
| `422` | Business validation failed |
| `500` | Internal error             |
| `503` | Dependency unavailable     |
| `504` | Timeout                    |

---

## 8. Exception Codes

| Code               | HTTP | Description                      |
| ------------------ | ---- | -------------------------------- |
| `FX_ERR_001`       | 400  | Invalid currency pair            |
| `FX_ERR_002`       | 422  | Insufficient funds               |
| `FX_ERR_003`       | 409  | Duplicate request                |
| `FX_ERR_004`       | 403  | Daily limit exceeded             |
| `FX_ERR_005`       | 403  | Outside trading time             |
| `FX_ERR_006`       | 409  | Exchange rate expired            |
| `FX_ERR_007`       | 422  | Source account currency mismatch |
| `FX_ERR_008`       | 422  | Target account currency mismatch |
| `FX_ERR_009`       | 404  | Account not found                |
| `FX_ERR_010`       | 423  | Account locked                   |
| `TREASURY_ERR_001` | 503  | Treasury unavailable             |
| `CORE_ERR_001`     | 503  | Core banking unavailable         |
| `MQ_ERR_001`       | 500  | Message queue failed             |
| `SYS_ERR_001`      | 500  | Internal error                   |

---

## 9. Kịch Bản Test

### 9.1 Successful FX Exchange

**Scenario:** Khách hàng đổi 500 USD sang VND.

#### Request

```json
POST /api/v1/fx/exchange
{
  "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
  "owner_id": 882291,
  "account_number_id": "0900231231",
  "base_currency": "USD",
  "target_currency": "VND",
  "amount": 500.00
}
```

#### Preconditions

| Condition         | Value     |
| ----------------- | --------- |
| Account status    | `ACTIVE`  |
| Available balance | 1,000 USD |
| Treasury rate     | 25,450    |
| Trading time      | 10:00 AM  |

#### Expected Steps

| Step                  | Expected Result |
| --------------------- | --------------- |
| Validate request      | SUCCESS         |
| Check idempotency     | SUCCESS         |
| Push MQ               | SUCCESS         |
| Response 202          | SUCCESS         |
| Consume MQ            | SUCCESS         |
| Get FX rate           | SUCCESS         |
| Validate account      | SUCCESS         |
| Check balance         | SUCCESS         |
| Create HOLD entry     | SUCCESS         |
| Create DEBIT entry    | SUCCESS         |
| Create CREDIT entry   | SUCCESS         |
| Update transaction    | SUCCESS         |
| Call API Notification | SUCCESS         |

#### Expected API Response

**POST** `POST /api/v1/fx/exchange` → `202 Accepted`

```json
{
  "timestamp": "2026-05-11T10:00:00Z",
  "status": "PROCESSING",
  "data": {
    "tx_id": "FX-UUID-12345",
    "message": "Transaction is being processed"
  }
}
```

**GET** `GET /api/v1/fx/{tx_id}` → `200 OK`

```json
{
  "tx_id": "FX-UUID-12345",
  "status": "SUCCESS",
  "base_currency": "USD",
  "source_amount": 500,
  "rate_exchange": 25450,
  "target_currency": "VND",
  "converted_amount": 12725000,
  "created_at": "2026-05-11T10:00:00Z",
  "completed_at": "2026-05-11T10:00:05Z"
}
```

---

### 9.2 Insufficient Balance

**Scenario:** Khách hàng có 100 USD nhưng muốn đổi 500 USD.

#### Expected Response — `422 Unprocessable Entity`

```json
{
  "timestamp": "2026-05-11T10:05:00Z",
  "status": "ERROR",
  "error": {
    "code": "FX_ERR_002",
    "message": "Insufficient funds"
  }
}
```

---

### 9.3 Duplicate Request

**Scenario:** Gửi 2 request có cùng `idempotency_key`.

#### Request

```json
{
  "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
  "owner_id": 882291,
  "account_number_id": "0900231231",
  "base_currency": "USD",
  "target_currency": "VND",
  "amount": 500.0
}
```

#### Expected Steps

| Step              | Result       |
| ----------------- | ------------ |
| Check idempotency | FAILED       |
| MQ publish        | NOT EXECUTED |
| Transaction       | REJECTED     |

#### Expected Response — `409 Conflict`

```json
{
  "timestamp": "2026-05-11T10:06:00Z",
  "status": "ERROR",
  "error": {
    "code": "FX_ERR_003",
    "message": "Duplicate request"
  }
}
```

---

### 9.4 Invalid Currency Pair

**Scenario:** Khách hàng tạo giao dịch đổi từ USD sang ABC.

#### Request

```json
{
  "idempotency_key": "77777777-e29b-41d4-a716-446655440000",
  "owner_id": 882291,
  "account_number_id": "0900231231",
  "base_currency": "USD",
  "target_currency": "ABC",
  "amount": 500.0
}
```

#### Expected Steps

| Step                | Result |
| ------------------- | ------ |
| Treasury validation | FAILED |
| Transaction         | FAILED |

#### Expected Response — `400 Bad Request`

```json
{
  "timestamp": "2026-05-11T10:10:00Z",
  "status": "ERROR",
  "error": {
    "code": "FX_ERR_001",
    "message": "Invalid currency pair"
  }
}
```

---

### 9.5 Outside Trading Time

**Scenario:** Khách hàng request lúc 22:00 PM.

#### Expected Steps

| Validation         | Result       |
| ------------------ | ------------ |
| Trading time check | FAILED       |
| MQ publish         | NOT EXECUTED |

#### Expected Response — `403 Forbidden`

```json
{
  "timestamp": "2026-05-11T22:00:00Z",
  "status": "ERROR",
  "error": {
    "code": "FX_ERR_005",
    "message": "Outside trading time"
  }
}
```

---

### 9.6 Daily Limit Exceeded

**Scenario:** Khách hàng đã giao dịch 4,900 USD, sau đó giao dịch thêm 500 USD (vượt limit 5,000 USD/ngày).

#### Expected Steps

| Validation             | Result      |
| ---------------------- | ----------- |
| Daily limit validation | FAILED      |
| HOLD                   | NOT CREATED |

#### Expected Response — `403 Forbidden`

```json
{
  "timestamp": "2026-05-11T13:00:00Z",
  "status": "ERROR",
  "error": {
    "code": "FX_ERR_004",
    "message": "Daily limit exceeded"
  }
}
```

---

## 10. Logging & Monitoring Setup

### 10.1 Overview

The system uses **ELK Stack** (Elasticsearch, Logstash, Kibana) for centralized logging with **Logback** for application-level logging.

**Logging Pipeline:**
```
Application (Logback) → Logstash (TCP:5000) → Elasticsearch → Kibana (UI)
```

### 10.2 Starting ELK Stack

Start Elasticsearch, Logstash, and Kibana using Docker Compose:

```bash
cd /home/ubuntu/IdeaProjects/Omni-Bank-Messaging-Hub
docker-compose -f docker-compose-elk-stack.yml up -d
```

Verify services are running:

```bash
# Check Elasticsearch
curl http://localhost:9200

# Check Kibana
curl http://localhost:5601

# Check Logstash logs
docker logs logstash
```

### 10.3 Services with Logging Enabled

All 4 microservices are configured to log via Logback:

| Service | Logback Config | App Name | Port |
|---------|---|---|---|
| **core-banking** | `core-banking/src/main/resources/logback-spring.xml` | `core-banking` | 8080 |
| **sell-foreign-service** | `sell-foreign-service/src/main/resources/logback-spring.xml` | `sell-foreign-service` | 8081 |
| **sell-foreign-processor-service** | `sell-foreign-processor-service/src/main/resources/logback-spring.xml` | `sell-foreign-processor-service` | 8082 |
| **treasury-service** | `treasury-service/src/main/resources/logback-spring.xml` | `treasury-service` | 8083 |

### 10.4 Logback Configuration Features

- ✅ **Async Logging** - Non-blocking log processing (1024 event queue)
- ✅ **JSON Format** - Structured logs sent to Logstash
- ✅ **Console Output** - Real-time logs in terminal for development
- ✅ **Error Logs** - Separate local backup with rotation (10MB/30 days)
- ✅ **Environment Profiles** - `dev` (DEBUG) and `prod` (INFO) log levels
- ✅ **MDC Support** - For correlation IDs and distributed tracing
- ✅ **Noise Suppression** - Reduced verbosity from Spring, Hibernate, Catalina

**Daily Index Pattern:**

```
spring-logs-YYYY.MM.dd
```

Examples: `spring-logs-2026-05-19`, `spring-logs-2026-05-20`

### 10.5 Logstash Pipeline

**Config File:** `logstash/pipeline/logstash.conf`

**Flow:**
```
Input:  TCP Port 5000 (JSON Lines codec)
   ↓
Filter: Parse timestamp, extract fields
   ↓
Output: Elasticsearch (spring-logs-YYYY.MM.dd indices)
```

### 10.6 Accessing Logs in Kibana

1. Open **Kibana:** http://localhost:5601
2. Go to **Management** → **Index Patterns**
3. Create new index pattern: `spring-logs-*`
4. Go to **Discover** tab to view real-time logs

**Filter Examples:**

```
# By Service
app_name: "core-banking"

# By Log Level
level: "ERROR"
level: "WARN"

# By Time Range
@timestamp: [now-1h TO now]
```

### 10.7 Log Levels by Environment

**Development (DEBUG):**
```bash
cd core-banking && ./mvnw spring-boot:run --spring.profiles.active=dev
```
- All DEBUG, INFO, WARN, ERROR logs visible
- Detailed method entry/exit
- Variable values and states

**Production (INFO):**
```bash
cd core-banking && ./mvnw spring-boot:run --spring.profiles.active=prod
```
- Only INFO, WARN, ERROR logs
- Better performance
- No sensitive debug information

### 10.8 Logging Throughout the System

#### Controllers - API Request/Response

```java
log.info("[ENDPOINT] Received POST /exchange - idempotencyKey: {}, from: {} to: {}, amount: {}",
    request.getIdempotencyKey(), request.getBaseCurrency(),
    request.getTargetCurrency(), request.getAmount());
```

#### Services - Business Logic

```java
log.info("Processing Hold for txId: {}, amount: {}", txId, amount);
log.debug("Validating account - accountId: {}", accountId);
log.warn("Insufficient funds - available: {}, requested: {}", available, requested);
log.error("Hold operation failed", exception);
```

#### Message Listeners - Queue Processing

```java
log.info("[MESSAGE_LISTENER] Received message - idempotencyKey: {}",
    message.getIdempotencyKey());
```

### 10.9 Example Log Flow

When a user initiates an FX transaction:

```
[ENDPOINT] POST /exchange - idempotencyKey: abc-123, USD → VND, 500
  ↓
[INFO] Publishing message to RabbitMQ
  ↓
[MESSAGE_LISTENER] Received message - idempotencyKey: abc-123
  ↓
[INFO] Step 1: Holding balance - holdId: ENTRY-HOLD-xyz789
  ↓
[INFO] Step 2: Getting exchange rate - rate: 25,450
  ↓
[INFO] Step 3: Calculating converted amount - 500 USD → 12,725,000 VND
  ↓
[INFO] Transaction completed successfully
  ↓
[MESSAGE_LISTENER] Message acknowledged
  ↓
All logs visible in Kibana
```

### 10.10 Troubleshooting Logging

**Problem:** No logs appearing in Elasticsearch?

1. Check Logstash is running:
   ```bash
   docker ps | grep logstash
   ```

2. Check Logstash logs for errors:
   ```bash
   docker logs <logstash-container-id>
   ```

3. Verify Elasticsearch is running:
   ```bash
   curl http://localhost:9200
   ```

4. Check TCP connection to Logstash:
   ```bash
   telnet localhost 5000
   ```

5. Verify index pattern is created in Kibana:
   - Management → Index Patterns → Check `spring-logs-*` exists

**Problem:** High log volume?

Update log level in `logback-spring.xml`:

```xml
<logger name="org.springframework.web" level="WARN"/>
<logger name="org.hibernate" level="WARN"/>
```

---
