-- Recommended indexes for the processing_jobs table
-- These indexes improve queries by job id, email history lookups, status filtering and recent-first ordering.

-- 1) Fast lookup of jobs by email ordered by created_at DESC (used by history queries)
CREATE INDEX IF NOT EXISTS idx_processing_jobs_email_created_at
  ON processing_jobs (email, created_at DESC);

-- 2) Filter by status and order by creation time (useful to find recent completed or in-progress jobs)
CREATE INDEX IF NOT EXISTS idx_processing_jobs_status_created_at
  ON processing_jobs (status, created_at DESC);

-- 3) Simple index on created_at for range queries or cleanup tasks
CREATE INDEX IF NOT EXISTS idx_processing_jobs_created_at
  ON processing_jobs (created_at);

-- 4) Partial index to quickly find pending/processing jobs (helpful for background workers)
CREATE INDEX IF NOT EXISTS idx_processing_jobs_pending
  ON processing_jobs (created_at)
  WHERE status IN ('PENDING', 'PROCESSING');

-- Note: The primary key on `id` already exists (SERIAL PRIMARY KEY), no index needed there.
