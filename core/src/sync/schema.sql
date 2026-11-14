-- 本机同步配置不进入任何备份；凭证只接受密文 BLOB。
CREATE TABLE sync_state (
    id INTEGER PRIMARY KEY CHECK(id=1),
    config_json TEXT,
    credential BLOB,
    enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN (0,1)),
    recovery_fingerprint TEXT,
    last_synced_at TEXT,
    on_launch INTEGER NOT NULL DEFAULT 1 CHECK(on_launch IN (0,1)),
    interval_minutes INTEGER NOT NULL DEFAULT 30 CHECK(interval_minutes IN (0,15,30,60)),
    after_edit INTEGER NOT NULL DEFAULT 1 CHECK(after_edit IN (0,1)),
    CHECK((config_json IS NULL)=(credential IS NULL)),
    CHECK(enabled=0 OR config_json IS NOT NULL)
) STRICT;
INSERT INTO sync_state(id) VALUES(1);
