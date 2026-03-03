# Audio Summary Backend — Local run instructions

Quick steps to run this Spring Boot backend locally.

Prerequisites
- Java 11+
- Maven
- PostgreSQL (or Supabase) running and reachable

1) Create the DB table (example):

```sql
CREATE TABLE processing_jobs (
  id BIGSERIAL PRIMARY KEY,
  email VARCHAR(255) NOT NULL,
  status VARCHAR(50) NOT NULL,
  mini_summary TEXT,
  created_at TIMESTAMP,
  completed_at TIMESTAMP,
  processing_time_ms INTEGER,
  error_message TEXT,
  updated_at TIMESTAMP
);
```

2) Provide configuration
- Fill `src/main/resources/application.properties` with your DB, SMTP and API keys OR set equivalent environment variables:
  - `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`
  - `spring.mail.*` (host, port, username, password)
  - `assemblyai.api.key`, `groq.api.key`, `groq.api.url`

3) Build and run

```bash
mvn clean package -DskipTests
# or run directly
mvn spring-boot:run
```

4) If packaged:

```bash
java -jar target/audio-summary-backend-1.0.0.jar
```

Notes
- The repo did not previously include a Spring Boot main class — one was added at `src/main/java/com/audiosummary/AudioSummaryApplication.java`.
- The app expects the `processing_jobs` table shown above.
- Endpoints:
  - `POST /v1/jobs/upload` multipart form: `file`, `email`
  - `GET /v1/jobs/{jobId}/status`
  - `GET /v1/jobs/health`

  Database indexes
  - Run the SQL in `db/create_indexes.sql` to add recommended indexes that speed up common queries (history by email, status filtering and recent-first ordering):

  ```sql
  -- from db/create_indexes.sql
  CREATE INDEX IF NOT EXISTS idx_processing_jobs_email_created_at
    ON processing_jobs (email, created_at DESC);

  CREATE INDEX IF NOT EXISTS idx_processing_jobs_status_created_at
    ON processing_jobs (status, created_at DESC);

  CREATE INDEX IF NOT EXISTS idx_processing_jobs_created_at
    ON processing_jobs (created_at);

  CREATE INDEX IF NOT EXISTS idx_processing_jobs_pending
    ON processing_jobs (created_at)
    WHERE status IN ('PENDING', 'PROCESSING');
  ```
