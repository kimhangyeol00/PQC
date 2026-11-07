CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS contracts (
 id TEXT PRIMARY KEY, author TEXT NOT NULL, recipient TEXT NOT NULL,
 latest_version INTEGER NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS versions (
 contract_id TEXT NOT NULL REFERENCES contracts(id), number INTEGER NOT NULL,
 title TEXT NOT NULL, created_at TEXT NOT NULL, envelope TEXT NOT NULL,
 PRIMARY KEY (contract_id, number)
);
CREATE TABLE IF NOT EXISTS deliveries (
 id TEXT PRIMARY KEY, contract_id TEXT NOT NULL, version INTEGER NOT NULL,
 created_at TEXT NOT NULL, UNIQUE(contract_id, version),
 FOREIGN KEY (contract_id, version) REFERENCES versions(contract_id, number)
);
CREATE TABLE IF NOT EXISTS comments (
 id TEXT PRIMARY KEY, contract_id TEXT NOT NULL, version INTEGER NOT NULL,
 author TEXT NOT NULL, content TEXT NOT NULL, created_at TEXT NOT NULL,
 FOREIGN KEY (contract_id, version) REFERENCES versions(contract_id, number)
);
CREATE TABLE IF NOT EXISTS approvals (
 id TEXT PRIMARY KEY, contract_id TEXT NOT NULL, version INTEGER NOT NULL,
 approver TEXT NOT NULL, created_at TEXT NOT NULL, scheme TEXT NOT NULL, signature TEXT NOT NULL,
 UNIQUE(contract_id, version),
 FOREIGN KEY (contract_id, version) REFERENCES versions(contract_id, number)
);
