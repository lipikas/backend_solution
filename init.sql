CREATE TABLE IF NOT EXISTS clients (
  id            INT PRIMARY KEY,
  limit_cents   BIGINT NOT NULL,
  balance_cents BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS transactions (
  id BIGSERIAL PRIMARY KEY,
  client_id INT NOT NULL REFERENCES clients(id),
  value_cents BIGINT NOT NULL,
  type CHAR(1) NOT NULL CHECK (type IN ('c','d')),
  description VARCHAR(10) NOT NULL,
  executed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_transactions_client_time
  ON transactions (client_id, executed_at DESC);

-- Seed ONLY clients 1-5 (client 6 must NOT exist)
INSERT INTO clients (id, limit_cents, balance_cents) VALUES
  (1, 100000, 0),
  (2, 80000, 0),
  (3, 1000000, 0),
  (4, 10000000, 0),
  (5, 500000, 0)
ON CONFLICT (id) DO NOTHING;
