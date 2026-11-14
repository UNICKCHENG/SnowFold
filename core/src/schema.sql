-- Baseline 2. UUID/calendar/time semantics are validated at the core boundary.
CREATE TABLE categories (
    code TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('asset','liability')),
    sort_order INTEGER NOT NULL UNIQUE
) STRICT;
INSERT INTO categories VALUES
('cash','流动资金','asset',0),('fixed','固定资产','asset',1),
('investment','投资理财','asset',2),('receivable','应收款','asset',3),('debt','负债','liability',4);
CREATE TRIGGER categories_fixed_insert BEFORE INSERT ON categories BEGIN SELECT RAISE(ABORT,'categories are fixed'); END;
CREATE TRIGGER categories_fixed_update BEFORE UPDATE ON categories BEGIN SELECT RAISE(ABORT,'categories are fixed'); END;
CREATE TRIGGER categories_fixed_delete BEFORE DELETE ON categories BEGIN SELECT RAISE(ABORT,'categories are fixed'); END;
CREATE TABLE app_meta (
    key TEXT PRIMARY KEY NOT NULL CHECK(key IN ('ledger_id','replica_id','device_id','theme')),
    value TEXT NOT NULL,
    CHECK(key!='theme' OR value IN ('system','light','dark')),
    CHECK(key NOT IN ('ledger_id','replica_id') OR (length(value)=36 AND substr(value,9,1)='-' AND substr(value,14,1)='-' AND substr(value,19,1)='-' AND substr(value,24,1)='-' AND length(replace(value,'-',''))=32 AND replace(value,'-','') NOT GLOB '*[^0-9a-f]*' AND value!='00000000-0000-0000-0000-000000000000')),
    CHECK(key!='device_id' OR (length(value)=16 AND value NOT GLOB '*[^0-9a-f]*'))
) STRICT;
-- Amount projection and current_history_uid are atomically maintained by the core.
-- No circular deferred FK: validate checks the pointer, owner and effective amount.
CREATE TABLE asset_items (
    id INTEGER PRIMARY KEY,
    uid TEXT NOT NULL UNIQUE CHECK(length(uid)=36 AND substr(uid,9,1)='-' AND substr(uid,14,1)='-' AND substr(uid,19,1)='-' AND substr(uid,24,1)='-' AND length(replace(uid,'-',''))=32 AND replace(uid,'-','') NOT GLOB '*[^0-9a-f]*' AND uid!='00000000-0000-0000-0000-000000000000'),
    revision_uid TEXT NOT NULL CHECK(length(revision_uid)=36 AND substr(revision_uid,9,1)='-' AND substr(revision_uid,14,1)='-' AND substr(revision_uid,19,1)='-' AND substr(revision_uid,24,1)='-' AND length(replace(revision_uid,'-',''))=32 AND replace(revision_uid,'-','') NOT GLOB '*[^0-9a-f]*' AND revision_uid!='00000000-0000-0000-0000-000000000000'),
    current_history_uid TEXT NOT NULL CHECK(length(current_history_uid)=36 AND substr(current_history_uid,9,1)='-' AND substr(current_history_uid,14,1)='-' AND substr(current_history_uid,19,1)='-' AND substr(current_history_uid,24,1)='-' AND length(replace(current_history_uid,'-',''))=32 AND replace(current_history_uid,'-','') NOT GLOB '*[^0-9a-f]*' AND current_history_uid!='00000000-0000-0000-0000-000000000000'),
    category_code TEXT NOT NULL REFERENCES categories(code) ON DELETE RESTRICT,
    name TEXT NOT NULL CHECK(length(trim(name))>0),
    amount_cents INTEGER NOT NULL CHECK(amount_cents>=0), -- CNY cents
    created_at TEXT NOT NULL CHECK(length(created_at)=30 AND created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'),
    updated_at TEXT NOT NULL CHECK(length(updated_at)=30 AND updated_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z')
) STRICT;
-- Snapshots belong to the asset and are erased with it by explicit product policy.
-- original_amount_cents never changes; corrections append their before/after values.
CREATE TABLE asset_history (
    id INTEGER PRIMARY KEY,
    uid TEXT NOT NULL UNIQUE CHECK(length(uid)=36 AND substr(uid,9,1)='-' AND substr(uid,14,1)='-' AND substr(uid,19,1)='-' AND substr(uid,24,1)='-' AND length(replace(uid,'-',''))=32 AND replace(uid,'-','') NOT GLOB '*[^0-9a-f]*' AND uid!='00000000-0000-0000-0000-000000000000'),
    asset_id INTEGER NOT NULL REFERENCES asset_items(id) ON DELETE CASCADE,
    previous_id INTEGER REFERENCES asset_history(id) ON DELETE CASCADE, -- NULL = initial snapshot
    original_amount_cents INTEGER NOT NULL CHECK(original_amount_cents>=0),
    amount_cents INTEGER NOT NULL CHECK(amount_cents>=0),
    recorded_at TEXT NOT NULL CHECK(length(recorded_at)=30 AND recorded_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'),
    voided_at TEXT CHECK(voided_at IS NULL OR (length(voided_at)=30 AND voided_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z')) -- NULL = effective
) STRICT;
CREATE INDEX asset_history_asset ON asset_history(asset_id,id);
CREATE TRIGGER asset_history_original_immutable BEFORE UPDATE OF uid,asset_id,previous_id,original_amount_cents,recorded_at ON asset_history
BEGIN SELECT RAISE(ABORT,'original snapshot is immutable'); END;
CREATE TABLE history_corrections (
    id INTEGER PRIMARY KEY,
    uid TEXT NOT NULL UNIQUE CHECK(length(uid)=36 AND substr(uid,9,1)='-' AND substr(uid,14,1)='-' AND substr(uid,19,1)='-' AND substr(uid,24,1)='-' AND length(replace(uid,'-',''))=32 AND replace(uid,'-','') NOT GLOB '*[^0-9a-f]*' AND uid!='00000000-0000-0000-0000-000000000000'),
    history_id INTEGER NOT NULL REFERENCES asset_history(id) ON DELETE CASCADE,
    previous_amount_cents INTEGER NOT NULL CHECK(previous_amount_cents>=0),
    amount_cents INTEGER NOT NULL CHECK(amount_cents>=0 AND amount_cents!=previous_amount_cents),
    recorded_at TEXT NOT NULL CHECK(length(recorded_at)=30 AND recorded_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z')
) STRICT;
CREATE INDEX history_corrections_history ON history_corrections(history_id,id);
CREATE TRIGGER history_corrections_immutable BEFORE UPDATE ON history_corrections
BEGIN SELECT RAISE(ABORT,'corrections are immutable'); END;
CREATE TABLE subscriptions (
    id INTEGER PRIMARY KEY,
    uid TEXT NOT NULL UNIQUE CHECK(length(uid)=36 AND substr(uid,9,1)='-' AND substr(uid,14,1)='-' AND substr(uid,19,1)='-' AND substr(uid,24,1)='-' AND length(replace(uid,'-',''))=32 AND replace(uid,'-','') NOT GLOB '*[^0-9a-f]*' AND uid!='00000000-0000-0000-0000-000000000000'),
    revision_uid TEXT NOT NULL CHECK(length(revision_uid)=36 AND substr(revision_uid,9,1)='-' AND substr(revision_uid,14,1)='-' AND substr(revision_uid,19,1)='-' AND substr(revision_uid,24,1)='-' AND length(replace(revision_uid,'-',''))=32 AND replace(revision_uid,'-','') NOT GLOB '*[^0-9a-f]*' AND revision_uid!='00000000-0000-0000-0000-000000000000'),
    name TEXT NOT NULL CHECK(length(trim(name))>0),
    amount_cents INTEGER NOT NULL CHECK(amount_cents>=0),
    currency TEXT NOT NULL CHECK(currency IN ('CNY','USD')),
    cycle TEXT NOT NULL CHECK(cycle IN ('weekly','monthly','quarterly','yearly')),
    next_charge_on TEXT NOT NULL CHECK(length(next_charge_on)=10 AND next_charge_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    status TEXT NOT NULL CHECK(status IN ('active','paused')),
    created_at TEXT NOT NULL CHECK(length(created_at)=30 AND created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'),
    updated_at TEXT NOT NULL CHECK(length(updated_at)=30 AND updated_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z')
) STRICT;
-- Only these three fields survive deletion. No name, amount or history payload.
CREATE TABLE tombstones (
    entity_type TEXT NOT NULL CHECK(entity_type IN ('asset','subscription')),
    uid TEXT PRIMARY KEY NOT NULL CHECK(length(uid)=36 AND substr(uid,9,1)='-' AND substr(uid,14,1)='-' AND substr(uid,19,1)='-' AND substr(uid,24,1)='-' AND length(replace(uid,'-',''))=32 AND replace(uid,'-','') NOT GLOB '*[^0-9a-f]*' AND uid!='00000000-0000-0000-0000-000000000000'),
    deleted_at TEXT NOT NULL CHECK(length(deleted_at)=30 AND deleted_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z')
) STRICT;
