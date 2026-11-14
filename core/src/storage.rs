use crate::{
    backup::{Backup, BackupAsset, BackupCorrection, BackupHistory, BackupSubscription, Tombstone},
    crypto::{self, SyncKey},
    model::{self, Id, Ledger, SubscriptionInput, now},
};
use anyhow::{Context, Result, bail};
use directories::ProjectDirs;
use rusqlite::{Connection, Transaction, params};
use serde::{Deserialize, Serialize};
use std::{
    path::{Path, PathBuf},
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Theme {
    System,
    Light,
    Dark,
}
impl Theme {
    pub fn code(self) -> &'static str {
        match self {
            Self::System => "system",
            Self::Light => "light",
            Self::Dark => "dark",
        }
    }
}
#[derive(Debug)]
pub struct IncompatibleDatabase;
impl std::fmt::Display for IncompatibleDatabase {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("数据库版本不兼容")
    }
}
impl std::error::Error for IncompatibleDatabase {}
#[derive(Debug)]
pub struct UndoConflict;
impl std::fmt::Display for UndoConflict {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("撤销已过期或对象已变化")
    }
}
impl std::error::Error for UndoConflict {}

/// Opaque, process-local capability. The payload is never passed to a host or backup.
#[derive(Clone, Debug)]
pub struct Undo {
    pub token: String,
    pub valid_for_ms: u32,
}
struct PendingUndo {
    ticket: Undo,
    created: Instant,
    action: UndoAction,
}
enum UndoAction {
    AssetDeleted {
        asset: BackupAsset,
        history: Vec<BackupHistory>,
        corrections: Vec<BackupCorrection>,
        tombstone: Tombstone,
    },
    SubscriptionDeleted {
        subscription: BackupSubscription,
        tombstone: Tombstone,
    },
    BalanceChanged {
        asset: Id,
        history: Id,
        revision: Id,
    },
}

pub struct Storage {
    pub(crate) connection: Connection,
    pub path: PathBuf,
    undo: Option<PendingUndo>,
}
const DATABASE_VERSION: i64 = 3;
struct Migration {
    version: i64,
    sql: &'static str,
}
const MIGRATIONS: &[Migration] = &[
    Migration {
        version: 2,
        sql: include_str!("schema.sql"),
    },
    Migration {
        version: 3,
        sql: include_str!("sync/schema.sql"),
    },
];

/// 业务时间单调大于已观察的所有本机/远端记录，墙钟回拨不覆盖自己的新写入。
fn next_time(db: &Connection) -> Result<String> {
    let high: Option<String> = db.query_row("SELECT max(at) FROM (SELECT updated_at AS at FROM asset_items UNION ALL SELECT updated_at FROM subscriptions UNION ALL SELECT deleted_at FROM tombstones UNION ALL SELECT recorded_at FROM asset_history UNION ALL SELECT voided_at FROM asset_history UNION ALL SELECT recorded_at FROM history_corrections)", [], |r| r.get(0))?;
    let wall = now();
    match high {
        Some(high) if high >= wall => {
            let parsed = chrono::DateTime::parse_from_rfc3339(&high)?;
            let next = parsed
                .checked_add_signed(chrono::Duration::nanoseconds(1))
                .context("业务时间溢出")?;
            let value = next
                .with_timezone(&chrono::Utc)
                .to_rfc3339_opts(chrono::SecondsFormat::Nanos, true);
            model::validate_time(&value)?;
            Ok(value)
        }
        _ => Ok(wall),
    }
}

fn apply_migrations(connection: &mut Connection, migrations: &[Migration]) -> Result<()> {
    for migration in migrations {
        let tx = connection.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let version: i64 = tx.pragma_query_value(None, "user_version", |r| r.get(0))?;
        if version < migration.version {
            tx.execute_batch(migration.sql)?;
            if version == 0 {
                tx.execute("INSERT INTO app_meta(key,value) VALUES('theme','system'),('ledger_id',?1),('replica_id',?2),('device_id',?3)", params![Id::new()?,Id::new()?,crypto::random_hex(8)?])?;
            }
            tx.pragma_update(None, "user_version", migration.version)?;
        }
        tx.commit()?;
    }
    Ok(())
}
fn initialize(connection: &mut Connection) -> Result<()> {
    let version: i64 = connection.pragma_query_value(None, "user_version", |r| r.get(0))?;
    let tables: i64 = connection.query_row(
        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
        [],
        |r| r.get(0),
    )?;
    if version > DATABASE_VERSION || (version != 0 && version < 2) || (version == 0 && tables > 0) {
        bail!(IncompatibleDatabase);
    }
    apply_migrations(connection, MIGRATIONS)
}
impl Storage {
    pub fn open_default() -> Result<Self> {
        let directory = if let Some(path) = std::env::var_os("SNOWFOLD_DATA_DIR") {
            PathBuf::from(path)
        } else {
            ProjectDirs::from("", "", "SnowFold")
                .context("无法定位用户数据目录")?
                .data_local_dir()
                .to_owned()
        };
        std::fs::create_dir_all(&directory)?;
        Self::open(&directory.join("snowfold.sqlite3"))
    }
    pub fn open(path: &Path) -> Result<Self> {
        let mut connection = Connection::open(path).context("无法打开 SQLite 数据库")?;
        connection.pragma_update(None, "foreign_keys", "ON")?;
        connection.busy_timeout(Duration::from_secs(3))?;
        initialize(&mut connection)?;
        let storage = Self {
            connection,
            path: path.to_owned(),
            undo: None,
        };
        storage.backup_snapshot()?;
        Ok(storage)
    }
    pub fn data_dir(&self) -> Option<&Path> {
        self.path.parent().filter(|p| !p.as_os_str().is_empty())
    }
    pub fn theme(&self) -> Result<Theme> {
        match meta(&self.connection, "theme")?.as_str() {
            "system" => Ok(Theme::System),
            "light" => Ok(Theme::Light),
            "dark" => Ok(Theme::Dark),
            _ => bail!("无法识别已保存的主题"),
        }
    }
    pub fn save_theme(&self, theme: Theme) -> Result<()> {
        if self.connection.execute(
            "UPDATE app_meta SET value=?1 WHERE key='theme'",
            [theme.code()],
        )? != 1
        {
            bail!("数据库缺少主题设置");
        }
        Ok(())
    }
    pub fn device_id(&self) -> Result<String> {
        meta(&self.connection, "device_id")
    }
    pub fn ledger_id(&self) -> Result<Id> {
        meta(&self.connection, "ledger_id")?.parse()
    }
    pub fn replica_id(&self) -> Result<Id> {
        meta(&self.connection, "replica_id")?.parse()
    }
    pub fn data(&self) -> Result<Ledger> {
        Ok(self.backup_snapshot()?.into_ledger())
    }
    /// All exports and read models share this transaction and validation boundary.
    pub fn backup_snapshot(&self) -> Result<Backup> {
        self.snapshot_with_hook(|| Ok(()))
    }
    fn snapshot_with_hook(&self, after_assets: impl FnOnce() -> Result<()>) -> Result<Backup> {
        let tx = self.connection.unchecked_transaction()?;
        let backup = read_backup(&tx, after_assets)?;
        backup.validate()?;
        tx.commit()?;
        Ok(backup)
    }
    pub fn history_corrections(&self, id: Id) -> Result<Vec<model::Correction>> {
        let backup = self.backup_snapshot()?;
        if !backup.all_history().any(|h| h.id == id) {
            return Err(rusqlite::Error::QueryReturnedNoRows.into());
        }
        Ok(backup
            .corrections
            .into_iter()
            .filter(|c| c.history_id == id)
            .map(|c| model::Correction {
                id: c.id,
                history_id: c.history_id,
                previous_amount_cents: c.previous_amount_cents,
                amount_cents: c.amount_cents,
                recorded_at: c.recorded_at,
            })
            .collect())
    }
    pub fn create_asset(&mut self, category: &str, name: &str, cents: i64) -> Result<Id> {
        validate_asset(category, name, cents)?;
        let id = Id::new()?;
        let history = Id::new()?;
        let at = next_time(&self.connection)?;
        let asset = BackupAsset {
            id,
            revision_uid: Id::new()?,
            current_history_id: history,
            category_code: category.into(),
            name: name.trim().into(),
            amount_cents: cents,
            created_at: at.clone(),
            updated_at: at.clone(),
        };
        let tx = self.connection.transaction()?;
        insert_asset(&tx, &asset)?;
        insert_history(
            &tx,
            &BackupHistory {
                id: history,
                asset_id: id,
                previous_id: None,
                original_amount_cents: cents,
                amount_cents: cents,
                recorded_at: at,
                voided_at: None,
            },
        )?;
        tx.commit()?;
        Ok(id)
    }
    pub fn rename_asset(&mut self, id: Id, name: &str) -> Result<()> {
        if name.trim().is_empty() {
            bail!("请输入资产名称");
        }
        let changed = self.connection.execute(
            "UPDATE asset_items SET name=?1,revision_uid=?2,updated_at=?3 WHERE uid=?4",
            params![name.trim(), Id::new()?, next_time(&self.connection)?, id],
        )?;
        require_row(changed)
    }
    fn offer_undo(&mut self, token: String, action: UndoAction) -> Undo {
        let ticket = Undo {
            token,
            valid_for_ms: 6000,
        };
        self.undo = Some(PendingUndo {
            ticket: ticket.clone(),
            created: Instant::now(),
            action,
        });
        ticket
    }
    pub fn change_balance(&mut self, id: Id, cents: i64) -> Result<Option<Undo>> {
        if cents < 0 {
            bail!("金额不能为负数");
        }
        let token = crypto::random_hex(16)?;
        let tx = self.connection.transaction()?;
        let (row, previous, current): (i64, i64, Id) = tx.query_row(
            "SELECT id,amount_cents,current_history_uid FROM asset_items WHERE uid=?1",
            [id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )?;
        if previous == cents {
            return Ok(None);
        }
        let history = Id::new()?;
        let revision = Id::new()?;
        let at = next_time(&tx)?;
        let previous_row: i64 = tx.query_row(
            "SELECT id FROM asset_history WHERE uid=?1 AND asset_id=?2 AND voided_at IS NULL",
            params![current, row],
            |r| r.get(0),
        )?;
        tx.execute("INSERT INTO asset_history(uid,asset_id,previous_id,original_amount_cents,amount_cents,recorded_at) VALUES(?1,?2,?3,?4,?4,?5)", params![history,row,previous_row,cents,at])?;
        tx.execute("UPDATE asset_items SET current_history_uid=?1,amount_cents=?2,revision_uid=?3,updated_at=?4 WHERE id=?5", params![history,cents,revision,at,row])?;
        tx.commit()?;
        Ok(Some(self.offer_undo(
            token,
            UndoAction::BalanceChanged {
                asset: id,
                history,
                revision,
            },
        )))
    }
    pub fn correct_history(&mut self, id: Id, cents: i64) -> Result<()> {
        if cents < 0 {
            bail!("金额不能为负数");
        }
        let tx = self.connection.transaction()?;
        let (row, asset, previous): (i64, i64, i64) = tx.query_row(
            "SELECT id,asset_id,amount_cents FROM asset_history WHERE uid=?1 AND voided_at IS NULL",
            [id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )?;
        if previous == cents {
            return Ok(());
        }
        let at = next_time(&tx)?;
        tx.execute("INSERT INTO history_corrections(uid,history_id,previous_amount_cents,amount_cents,recorded_at) VALUES(?1,?2,?3,?4,?5)", params![Id::new()?,row,previous,cents,at])?;
        tx.execute(
            "UPDATE asset_history SET amount_cents=?1 WHERE id=?2",
            params![cents, row],
        )?;
        // Correcting an older snapshot changes history, not the current asset revision.
        tx.execute("UPDATE asset_items SET amount_cents=?1,revision_uid=?2,updated_at=?3 WHERE id=?4 AND current_history_uid=?5", params![cents,Id::new()?,at,asset,id])?;
        tx.commit()?;
        Ok(())
    }
    pub fn delete_asset(&mut self, id: Id) -> Result<Undo> {
        let token = crypto::random_hex(16)?;
        let tx = self.connection.transaction()?;
        let mut backup = read_backup(&tx, || Ok(()))?;
        let index = backup
            .assets
            .iter()
            .position(|a| a.id == id)
            .ok_or(rusqlite::Error::QueryReturnedNoRows)?;
        let asset = backup.assets.remove(index);
        let history: Vec<_> = backup
            .history
            .into_iter()
            .chain(backup.voided_history)
            .filter(|h| h.asset_id == id)
            .collect();
        let corrections = backup
            .corrections
            .into_iter()
            .filter(|c| history.iter().any(|h| h.id == c.history_id))
            .collect();
        let tombstone = Tombstone {
            entity_type: "asset".into(),
            id,
            deleted_at: next_time(&tx)?,
        };
        insert_tombstone(&tx, &tombstone)?;
        require_row(tx.execute("DELETE FROM asset_items WHERE uid=?1", [id])?)?;
        tx.commit()?;
        Ok(self.offer_undo(
            token,
            UndoAction::AssetDeleted {
                asset,
                history,
                corrections,
                tombstone,
            },
        ))
    }
    pub fn create_subscription(&mut self, input: &SubscriptionInput) -> Result<Id> {
        input.validate()?;
        let id = Id::new()?;
        let at = next_time(&self.connection)?;
        let subscription = BackupSubscription {
            id,
            revision_uid: Id::new()?,
            name: input.name.trim().into(),
            amount_cents: input.amount_cents,
            currency: input.currency.clone(),
            cycle: input.cycle.clone(),
            next_charge_on: input.next_charge_on,
            status: input.status.clone(),
            created_at: at.clone(),
            updated_at: at,
        };
        let tx = self.connection.transaction()?;
        insert_subscription(&tx, &subscription)?;
        tx.commit()?;
        Ok(id)
    }
    pub fn update_subscription(&mut self, id: Id, input: &SubscriptionInput) -> Result<()> {
        input.validate()?;
        require_row(self.connection.execute("UPDATE subscriptions SET name=?1,amount_cents=?2,currency=?3,cycle=?4,next_charge_on=?5,status=?6,revision_uid=?7,updated_at=?8 WHERE uid=?9", params![input.name.trim(),input.amount_cents,input.currency,input.cycle,input.next_charge_on.to_string(),input.status,Id::new()?,next_time(&self.connection)?,id])?)
    }
    pub fn delete_subscription(&mut self, id: Id) -> Result<Undo> {
        let token = crypto::random_hex(16)?;
        let tx = self.connection.transaction()?;
        let subscription = read_backup(&tx, || Ok(()))?
            .subscriptions
            .into_iter()
            .find(|s| s.id == id)
            .ok_or(rusqlite::Error::QueryReturnedNoRows)?;
        let tombstone = Tombstone {
            entity_type: "subscription".into(),
            id,
            deleted_at: next_time(&tx)?,
        };
        insert_tombstone(&tx, &tombstone)?;
        require_row(tx.execute("DELETE FROM subscriptions WHERE uid=?1", [id])?)?;
        tx.commit()?;
        Ok(self.offer_undo(
            token,
            UndoAction::SubscriptionDeleted {
                subscription,
                tombstone,
            },
        ))
    }
    pub fn undo(&mut self, ticket: &Undo) -> Result<()> {
        self.undo_token(&ticket.token)
    }
    pub fn undo_token(&mut self, token: &str) -> Result<()> {
        let pending = self.undo.as_ref().ok_or(UndoConflict)?;
        if pending.created.elapsed() >= Duration::from_secs(6) {
            self.undo = None;
            bail!(UndoConflict);
        }
        if pending.ticket.token != token {
            bail!(UndoConflict);
        }
        let tx = self.connection.transaction()?;
        let at = next_time(&tx)?;
        match &pending.action {
            UndoAction::BalanceChanged {
                asset,
                history,
                revision,
            } => {
                let matches: bool = tx.query_row("SELECT EXISTS(SELECT 1 FROM asset_items WHERE uid=?1 AND current_history_uid=?2 AND revision_uid=?3)", params![asset,history,revision], |r| r.get(0))?;
                if !matches {
                    bail!(UndoConflict);
                }
                let (previous_uid, previous_amount): (Id,i64) = tx.query_row("SELECT p.uid,p.amount_cents FROM asset_history h JOIN asset_history p ON p.id=h.previous_id AND p.asset_id=h.asset_id WHERE h.uid=?1 AND h.voided_at IS NULL AND p.voided_at IS NULL", [history], |r| Ok((r.get(0)?,r.get(1)?)))?;
                tx.execute(
                    "UPDATE asset_history SET voided_at=?1 WHERE uid=?2",
                    params![at, history],
                )?;
                tx.execute("UPDATE asset_items SET current_history_uid=?1,amount_cents=?2,revision_uid=?3,updated_at=?4 WHERE uid=?5", params![previous_uid,previous_amount,Id::new()?,at,asset])?;
            }
            UndoAction::AssetDeleted {
                asset,
                history,
                corrections,
                tombstone,
            } => {
                remove_tombstone(&tx, tombstone)?;
                let mut asset = asset.clone();
                asset.revision_uid = Id::new()?;
                asset.updated_at = at.clone();
                insert_asset(&tx, &asset)?;
                insert_history_chain(&tx, history)?;
                for correction in corrections {
                    insert_correction(&tx, correction)?;
                }
            }
            UndoAction::SubscriptionDeleted {
                subscription,
                tombstone,
            } => {
                remove_tombstone(&tx, tombstone)?;
                let mut subscription = subscription.clone();
                subscription.revision_uid = Id::new()?;
                subscription.updated_at = at.clone();
                insert_subscription(&tx, &subscription)?;
            }
        }
        tx.commit()?;
        self.undo = None;
        Ok(())
    }
    pub fn replace_data(&mut self, backup: &Backup) -> Result<()> {
        backup.validate()?;
        let tx = self.connection.transaction()?;
        write_backup(&tx, backup)?;
        tx.commit()?;
        self.undo = None;
        Ok(())
    }
    /// IMMEDIATE 在读最新账本前取得写保留锁，覆盖同进程和其他进程的 SQLite 连接。
    pub(crate) fn finish_sync(
        &mut self,
        at: &str,
        merge: impl FnOnce(&Backup) -> Result<Backup>,
    ) -> Result<()> {
        let tx = self
            .connection
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let latest = read_backup(&tx, || Ok(()))?;
        latest.validate()?;
        let merged = merge(&latest)?;
        merged.validate()?;
        write_backup(&tx, &merged)?;
        tx.execute("UPDATE sync_state SET last_synced_at=?1 WHERE id=1", [at])?;
        tx.commit()?;
        self.undo = None;
        Ok(())
    }
    pub fn clear_data(&mut self) -> Result<()> {
        let tx = self.connection.transaction()?;
        let at = next_time(&tx)?;
        tx.execute("INSERT INTO tombstones(entity_type,uid,deleted_at) SELECT 'asset',uid,?1 FROM asset_items", [&at])?;
        tx.execute("INSERT INTO tombstones(entity_type,uid,deleted_at) SELECT 'subscription',uid,?1 FROM subscriptions", [&at])?;
        tx.execute_batch("DELETE FROM asset_items; DELETE FROM subscriptions;")?;
        tx.commit()?;
        self.undo = None;
        Ok(())
    }
    pub fn export_json(&self) -> Result<String> {
        Ok(serde_json::to_string_pretty(&self.backup_snapshot()?)?)
    }
    pub fn import_json(&mut self, json: &str) -> Result<()> {
        self.replace_data(&Backup::decode(json)?)
    }
    pub fn export_sealed(&self, key: &SyncKey) -> Result<Vec<u8>> {
        let tx = self.connection.unchecked_transaction()?;
        let data = read_backup(&tx, || Ok(()))?;
        data.validate()?;
        let snapshot = SealedSnapshot {
            format: SEALED_FORMAT.into(),
            version: 2,
            exported_at: now(),
            device_id: meta(&tx, "device_id")?,
            data,
        };
        let plaintext = serde_json::to_vec(&snapshot)?;
        tx.commit()?;
        crypto::seal(&plaintext, key, crypto::SNAPSHOT_AAD)
    }
    pub fn inspect_sealed(sealed: &[u8], key: &SyncKey) -> Result<SealedInfo> {
        Ok(parse_sealed(sealed, key)?.1)
    }
    pub fn replace_from_sealed(&mut self, sealed: &[u8], key: &SyncKey) -> Result<SealedInfo> {
        let (data, info) = parse_sealed(sealed, key)?;
        self.replace_data(&data)?;
        Ok(info)
    }
    pub fn startup_backup(&self) -> Result<()> {
        let json = self.export_json()?;
        let directory = self
            .path
            .parent()
            .context("数据库路径无父目录")?
            .join("backups");
        std::fs::create_dir_all(&directory)?;
        std::fs::write(
            directory.join(format!(
                "snowfold-{}.json",
                chrono::Local::now().format("%Y%m%d-%H%M%S-%f")
            )),
            json,
        )?;
        let mut files = std::fs::read_dir(&directory)?
            .map(|e| e.map(|e| e.path()))
            .collect::<std::io::Result<Vec<_>>>()?;
        files.retain(|p| {
            p.file_name()
                .is_some_and(|n| n.to_string_lossy().starts_with("snowfold-"))
                && p.extension().is_some_and(|e| e == "json")
        });
        files.sort();
        let remove = files.len().saturating_sub(10);
        for path in files.into_iter().take(remove) {
            std::fs::remove_file(path)?;
        }
        Ok(())
    }
}
fn meta(db: &Connection, key: &str) -> Result<String> {
    Ok(
        db.query_row("SELECT value FROM app_meta WHERE key=?1", [key], |r| {
            r.get(0)
        })?,
    )
}
fn require_row(count: usize) -> Result<()> {
    if count != 1 {
        return Err(rusqlite::Error::QueryReturnedNoRows.into());
    }
    Ok(())
}
fn validate_asset(category: &str, name: &str, cents: i64) -> Result<()> {
    if name.trim().is_empty() || cents < 0 || !model::CATEGORIES.iter().any(|(c, _)| *c == category)
    {
        bail!("资产名称、金额或分类不合法");
    }
    Ok(())
}
fn write_backup(tx: &Transaction<'_>, backup: &Backup) -> Result<()> {
    tx.execute_batch(
        "DELETE FROM asset_items; DELETE FROM subscriptions; DELETE FROM tombstones;",
    )?;
    for asset in &backup.assets {
        insert_asset(tx, asset)?;
    }
    insert_history_chain(tx, &backup.all_history().cloned().collect::<Vec<_>>())?;
    for correction in &backup.corrections {
        insert_correction(tx, correction)?;
    }
    for subscription in &backup.subscriptions {
        insert_subscription(tx, subscription)?;
    }
    for tombstone in &backup.tombstones {
        insert_tombstone(tx, tombstone)?;
    }
    tx.execute(
        "UPDATE app_meta SET value=?1 WHERE key='ledger_id'",
        [backup.ledger_id],
    )?;
    Ok(())
}

fn read_backup(db: &Connection, after_assets: impl FnOnce() -> Result<()>) -> Result<Backup> {
    let mut backup = Backup::empty(meta(db, "ledger_id")?.parse()?);
    backup.assets = db.prepare("SELECT uid,revision_uid,current_history_uid,category_code,name,amount_cents,created_at,updated_at FROM asset_items ORDER BY id")?.query_map([], |r| Ok(BackupAsset { id: r.get(0)?, revision_uid: r.get(1)?, current_history_id: r.get(2)?, category_code: r.get(3)?, name: r.get(4)?, amount_cents: r.get(5)?, created_at: r.get(6)?, updated_at: r.get(7)? }))?.collect::<rusqlite::Result<_>>()?;
    after_assets()?;
    backup.history = db.prepare("SELECT h.uid,a.uid,p.uid,h.original_amount_cents,h.amount_cents,h.recorded_at,h.voided_at FROM asset_history h JOIN asset_items a ON a.id=h.asset_id LEFT JOIN asset_history p ON p.id=h.previous_id ORDER BY h.id")?.query_map([], |r| Ok(BackupHistory { id: r.get(0)?, asset_id: r.get(1)?, previous_id: r.get(2)?, original_amount_cents: r.get(3)?, amount_cents: r.get(4)?, recorded_at: r.get(5)?, voided_at: r.get(6)? }))?.collect::<rusqlite::Result<_>>()?;
    backup.corrections = db.prepare("SELECT c.uid,h.uid,c.previous_amount_cents,c.amount_cents,c.recorded_at FROM history_corrections c JOIN asset_history h ON h.id=c.history_id ORDER BY c.id")?.query_map([], |r| Ok(BackupCorrection { id: r.get(0)?, history_id: r.get(1)?, previous_amount_cents: r.get(2)?, amount_cents: r.get(3)?, recorded_at: r.get(4)? }))?.collect::<rusqlite::Result<_>>()?;
    let raw = db.prepare("SELECT uid,revision_uid,name,amount_cents,currency,cycle,next_charge_on,status,created_at,updated_at FROM subscriptions ORDER BY next_charge_on,uid")?.query_map([], |r| Ok((r.get::<_,Id>(0)?,r.get::<_,Id>(1)?,r.get::<_,String>(2)?,r.get::<_,i64>(3)?,r.get::<_,String>(4)?,r.get::<_,String>(5)?,r.get::<_,String>(6)?,r.get::<_,String>(7)?,r.get::<_,String>(8)?,r.get::<_,String>(9)?)))?.collect::<rusqlite::Result<Vec<_>>>()?;
    backup.subscriptions = raw
        .into_iter()
        .map(
            |(
                id,
                revision_uid,
                name,
                amount_cents,
                currency,
                cycle,
                date,
                status,
                created_at,
                updated_at,
            )| {
                let next_charge_on = chrono::NaiveDate::parse_from_str(&date, "%Y-%m-%d")?;
                if next_charge_on.to_string() != date {
                    bail!("自然日格式不合法");
                }
                Ok(BackupSubscription {
                    id,
                    revision_uid,
                    name,
                    amount_cents,
                    currency,
                    cycle,
                    next_charge_on,
                    status,
                    created_at,
                    updated_at,
                })
            },
        )
        .collect::<Result<_>>()?;
    backup.tombstones = db
        .prepare("SELECT entity_type,uid,deleted_at FROM tombstones ORDER BY uid")?
        .query_map([], |r| {
            Ok(Tombstone {
                entity_type: r.get(0)?,
                id: r.get(1)?,
                deleted_at: r.get(2)?,
            })
        })?
        .collect::<rusqlite::Result<_>>()?;
    (backup.history, backup.voided_history) = backup
        .history
        .into_iter()
        .partition(|h| h.voided_at.is_none());
    Ok(backup)
}
fn insert_asset(tx: &Transaction<'_>, a: &BackupAsset) -> Result<()> {
    tx.execute("INSERT INTO asset_items(uid,revision_uid,current_history_uid,category_code,name,amount_cents,created_at,updated_at) VALUES(?1,?2,?3,?4,?5,?6,?7,?8)",params![a.id,a.revision_uid,a.current_history_id,a.category_code,a.name,a.amount_cents,a.created_at,a.updated_at])?;
    Ok(())
}
fn insert_history(tx: &Transaction<'_>, h: &BackupHistory) -> Result<()> {
    let owner: i64 = tx.query_row(
        "SELECT id FROM asset_items WHERE uid=?1",
        [h.asset_id],
        |r| r.get(0),
    )?;
    let previous: Option<i64> = h
        .previous_id
        .map(|id| {
            tx.query_row(
                "SELECT id FROM asset_history WHERE uid=?1 AND asset_id=?2",
                params![id, owner],
                |r| r.get(0),
            )
        })
        .transpose()?;
    tx.execute("INSERT INTO asset_history(uid,asset_id,previous_id,original_amount_cents,amount_cents,recorded_at,voided_at) VALUES(?1,?2,?3,?4,?5,?6,?7)",params![h.id,owner,previous,h.original_amount_cents,h.amount_cents,h.recorded_at,h.voided_at])?;
    Ok(())
}
fn insert_history_chain(tx: &Transaction<'_>, history: &[BackupHistory]) -> Result<()> {
    let mut remaining: Vec<_> = history.iter().collect();
    let mut inserted = std::collections::HashSet::new();
    while !remaining.is_empty() {
        let before = remaining.len();
        let mut next = vec![];
        for h in remaining {
            if h.previous_id.is_none_or(|id| inserted.contains(&id)) {
                insert_history(tx, h)?;
                inserted.insert(h.id);
            } else {
                next.push(h);
            }
        }
        if next.len() == before {
            bail!("快照前驱链无效");
        }
        remaining = next;
    }
    Ok(())
}
fn insert_correction(tx: &Transaction<'_>, c: &BackupCorrection) -> Result<()> {
    tx.execute("INSERT INTO history_corrections(uid,history_id,previous_amount_cents,amount_cents,recorded_at) VALUES(?1,(SELECT id FROM asset_history WHERE uid=?2),?3,?4,?5)",params![c.id,c.history_id,c.previous_amount_cents,c.amount_cents,c.recorded_at])?;
    Ok(())
}
fn insert_subscription(tx: &Transaction<'_>, s: &BackupSubscription) -> Result<()> {
    tx.execute("INSERT INTO subscriptions(uid,revision_uid,name,amount_cents,currency,cycle,next_charge_on,status,created_at,updated_at) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",params![s.id,s.revision_uid,s.name,s.amount_cents,s.currency,s.cycle,s.next_charge_on.to_string(),s.status,s.created_at,s.updated_at])?;
    Ok(())
}
fn insert_tombstone(tx: &Transaction<'_>, t: &Tombstone) -> Result<()> {
    tx.execute(
        "INSERT INTO tombstones(entity_type,uid,deleted_at) VALUES(?1,?2,?3)",
        params![t.entity_type, t.id, t.deleted_at],
    )?;
    Ok(())
}
fn remove_tombstone(tx: &Transaction<'_>, t: &Tombstone) -> Result<()> {
    if tx.execute(
        "DELETE FROM tombstones WHERE entity_type=?1 AND uid=?2 AND deleted_at=?3",
        params![t.entity_type, t.id, t.deleted_at],
    )? != 1
    {
        bail!(UndoConflict);
    }
    Ok(())
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SealedSnapshot {
    format: String,
    version: u32,
    exported_at: String,
    device_id: String,
    data: Backup,
}
const SEALED_FORMAT: &str = "snowfold.sealed-snapshot";
#[derive(Clone, Debug)]
pub struct SealedInfo {
    pub exported_at: String,
    pub device_id: String,
    pub assets: usize,
    pub history: usize,
    pub subscriptions: usize,
}
fn parse_sealed(sealed: &[u8], key: &SyncKey) -> Result<(Backup, SealedInfo)> {
    use crate::backup::BackupError;
    let plaintext = crypto::open(sealed, key, crypto::SNAPSHOT_AAD)?;
    let value: serde_json::Value =
        serde_json::from_slice(&plaintext).map_err(|_| BackupError::Invalid)?;
    if value.get("format").and_then(|v| v.as_str()) != Some(SEALED_FORMAT)
        || value.get("version").and_then(|v| v.as_u64()) != Some(2)
    {
        bail!(BackupError::Incompatible);
    }
    Backup::decode(&value.get("data").ok_or(BackupError::Invalid)?.to_string())?;
    let snapshot: SealedSnapshot =
        serde_json::from_value(value).map_err(|_| BackupError::Invalid)?;
    model::validate_time(&snapshot.exported_at).map_err(|_| BackupError::Invalid)?;
    validate_device_id(&snapshot.device_id).map_err(|_| BackupError::Invalid)?;
    let info = SealedInfo {
        exported_at: normalized_export_time(&snapshot.exported_at)?,
        device_id: snapshot.device_id,
        assets: snapshot.data.assets.len(),
        history: snapshot
            .data
            .history
            .iter()
            .filter(|h| h.voided_at.is_none())
            .count(),
        subscriptions: snapshot.data.subscriptions.len(),
    };
    Ok((snapshot.data, info))
}
fn normalized_export_time(value: &str) -> Result<String> {
    Ok(chrono::DateTime::parse_from_rfc3339(value)?
        .with_timezone(&chrono::Local)
        .format("%Y-%m-%d %H:%M")
        .to_string())
}
fn validate_device_id(value: &str) -> Result<()> {
    if value.len() != 16
        || !value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        bail!("设备标识不合法");
    }
    Ok(())
}

impl Storage {
    pub fn example_data() -> Result<Backup> {
        let mut data = Backup::empty(Id::new()?);
        let rows: &[(&str, &str, &[(u64, i64)])] = &[
            (
                "cash",
                "招商银行储蓄卡",
                &[(160, 46000), (95, 50000), (2, 52000)],
            ),
            ("cash", "支付宝余额", &[(120, 21000), (5, 18400)]),
            ("cash", "微信零钱", &[(200, 12000), (18, 16000)]),
            ("fixed", "自住房（市值）", &[(300, 950000), (30, 980000)]),
            ("fixed", "代步车", &[(300, 135000), (45, 120000)]),
            (
                "investment",
                "沪深300指数基金",
                &[(180, 110000), (6, 128000)],
            ),
            ("investment", "纳斯达克100 ETF", &[(180, 88000), (6, 96000)]),
            ("investment", "三年期定期存款", &[(365, 200000)]),
            ("receivable", "借给朋友（王）", &[(75, 50000)]),
            (
                "debt",
                "房贷余额",
                &[(330, 900000), (120, 880000), (10, 860000)],
            ),
            ("debt", "信用卡待还", &[(20, 8600), (1, 12400)]),
        ];
        for (category, name, history) in rows {
            let id = Id::new()?;
            let mut previous_id = None;
            let mut timestamps = Vec::new();
            for (days, yuan) in *history {
                let date = model::today() - chrono::Days::new(*days);
                // Match the demo's local 10:20 snapshot time, then store UTC.
                let at = chrono::TimeZone::from_local_datetime(
                    &chrono::Local,
                    &date.and_hms_opt(10, 20, 0).unwrap(),
                )
                .earliest()
                .unwrap()
                .with_timezone(&chrono::Utc)
                .to_rfc3339_opts(chrono::SecondsFormat::Nanos, true);
                timestamps.push(at.clone());
                let history_id = Id::new()?;
                data.history.push(BackupHistory {
                    id: history_id,
                    previous_id,
                    original_amount_cents: yuan * 100,
                    voided_at: None,
                    asset_id: id,
                    amount_cents: yuan * 100,
                    recorded_at: at,
                });
                previous_id = Some(history_id);
            }
            data.assets.push(BackupAsset {
                id,
                revision_uid: Id::new()?,
                current_history_id: previous_id.unwrap(),
                category_code: (*category).into(),
                name: (*name).into(),
                amount_cents: history.last().unwrap().1 * 100,
                created_at: timestamps.first().unwrap().clone(),
                updated_at: timestamps.last().unwrap().clone(),
            });
        }
        for (name, cents, currency, cycle, offset, status) in [
            ("Netflix", 1599, "USD", "monthly", 2, "active"),
            ("iCloud+", 600, "CNY", "monthly", 1, "active"),
            ("ChatGPT Plus", 2000, "USD", "monthly", 7, "active"),
            ("Notion", 1000, "USD", "monthly", -3, "active"),
            ("Apple Music", 1100, "CNY", "monthly", 12, "active"),
            ("网易云音乐", 8800, "CNY", "yearly", 100, "active"),
            ("1Password", 3588, "USD", "yearly", 45, "active"),
            ("腾讯视频", 2500, "CNY", "monthly", 26, "active"),
            ("Adobe 摄影计划", 999, "USD", "monthly", 5, "paused"),
        ]
        .into_iter()
        {
            data.subscriptions.push(BackupSubscription {
                id: Id::new()?,
                revision_uid: Id::new()?,
                created_at: now(),
                updated_at: now(),
                name: name.into(),
                amount_cents: cents,
                currency: currency.into(),
                cycle: cycle.into(),
                next_charge_on: model::today() + chrono::Duration::days(offset),
                status: status.into(),
            });
        }
        Ok(data)
    }
}

#[cfg(test)]
pub struct Category {
    pub name: String,
    pub liability: bool,
    pub cents: i64,
    pub count: i64,
}
#[cfg(test)]
pub struct Snapshot {
    pub categories: Vec<Category>,
    pub subscriptions: i64,
}
#[cfg(test)]
impl Storage {
    pub fn snapshot(&self) -> Result<Snapshot> {
        let categories = self.connection.prepare("SELECT c.name,c.kind='liability',COALESCE(SUM(a.amount_cents),0),COUNT(a.id) FROM categories c LEFT JOIN asset_items a ON a.category_code=c.code GROUP BY c.code ORDER BY c.sort_order")?.query_map([], |r| Ok(Category { name:r.get(0)?,liability:r.get(1)?,cents:r.get(2)?,count:r.get(3)? }))?.collect::<rusqlite::Result<_>>()?;
        let subscriptions =
            self.connection
                .query_row("SELECT COUNT(*) FROM subscriptions", [], |r| r.get(0))?;
        Ok(Snapshot {
            categories,
            subscriptions,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn repeated_initialization_and_theme_survive_reopen() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = directory.path().join("test.sqlite3");
        let storage = Storage::open(&path)?;
        assert_eq!(storage.theme()?, Theme::System);
        storage.save_theme(Theme::Dark)?;
        drop(storage);
        let storage = Storage::open(&path)?;
        assert_eq!(storage.theme()?, Theme::Dark);
        let snapshot = storage.snapshot()?;
        assert_eq!(snapshot.categories.len(), 5);
        assert!(
            snapshot
                .categories
                .iter()
                .all(|c| c.count == 0 && c.cents == 0)
        );
        assert_eq!(snapshot.subscriptions, 0);
        Ok(())
    }
    #[test]
    fn constraints_reject_invalid_values_without_pollution() -> Result<()> {
        let mut storage = Storage::open(Path::new(":memory:"))?;
        let id = storage.create_asset("cash", "zero", 0)?;
        let db = &storage.connection;
        for value in ["-1", "0.5", "NULL", "''"] {
            assert!(
                db.execute(
                    &format!("UPDATE asset_items SET amount_cents={value} WHERE uid=?1"),
                    [id]
                )
                .is_err()
            );
        }
        assert!(
            db.execute(
                "UPDATE asset_items SET category_code='unknown' WHERE uid=?1",
                [id]
            )
            .is_err()
        );
        assert!(
            db.execute("UPDATE categories SET name='changed' WHERE code='cash'", [])
                .is_err()
        );
        assert!(db.execute("DELETE FROM categories", []).is_err());
        assert!(
            db.execute("UPDATE asset_history SET asset_id=999", [])
                .is_err()
        );
        for (currency, cycle, status, date) in [
            ("EUR", "monthly", "active", "2026-01-01"),
            ("CNY", "daily", "active", "2026-01-01"),
            ("CNY", "monthly", "pending", "2026-01-01"),
            ("CNY", "monthly", "active", "2026-02-30"),
        ] {
            let raw = serde_json::json!({"name":"test","amount_cents":0,"currency":currency,"cycle":cycle,"status":status,"next_charge_on":date});
            let result: Result<()> = (|| {
                let input = SubscriptionInput {
                    name: "test".into(),
                    amount_cents: 0,
                    currency: currency.into(),
                    cycle: cycle.into(),
                    status: status.into(),
                    next_charge_on: chrono::NaiveDate::parse_from_str(
                        raw["next_charge_on"].as_str().unwrap(),
                        "%Y-%m-%d",
                    )?,
                };
                storage.create_subscription(&input)?;
                Ok(())
            })();
            assert!(result.is_err());
        }
        assert!(
            storage
                .connection
                .execute("UPDATE app_meta SET value='invalid' WHERE key='theme'", [])
                .is_err()
        );
        let deleted = storage.delete_asset(id)?;
        assert_eq!(
            storage
                .connection
                .query_row("SELECT COUNT(*) FROM asset_history", [], |r| r
                    .get::<_, i64>(0))?,
            0
        );
        assert_eq!(storage.snapshot()?.subscriptions, 0);
        assert!(storage.snapshot()?.categories.iter().all(|c| c.count == 0));
        storage.undo(&deleted)?;
        assert_eq!(storage.data()?.assets[0].amount_cents, 0);
        Ok(())
    }
    #[test]
    fn failed_theme_write_preserves_saved_theme() -> Result<()> {
        let storage = Storage::open(Path::new(":memory:"))?;
        storage.save_theme(Theme::Light)?;
        storage.connection.pragma_update(None, "query_only", "ON")?;
        assert!(storage.save_theme(Theme::Dark).is_err());
        assert_eq!(storage.theme()?, Theme::Light);
        Ok(())
    }
    #[test]
    fn failed_migration_rolls_back_every_statement() -> Result<()> {
        let mut db = Connection::open_in_memory()?;
        db.execute("CREATE TABLE subscriptions(existing TEXT)", [])?;
        assert!(initialize(&mut db).is_err());
        assert_eq!(
            db.pragma_query_value(None, "user_version", |r| r.get::<_, i64>(0))?,
            0
        );
        assert_eq!(
            db.query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='categories'",
                [],
                |r| r.get::<_, i64>(0)
            )?,
            0
        );
        Ok(())
    }
}
#[cfg(test)]
mod business_tests {
    use super::*;
    fn memory() -> Result<Storage> {
        Storage::open(Path::new(":memory:"))
    }
    #[test]
    fn balance_history_correction_and_delete_undo_are_atomic() -> Result<()> {
        let mut s = memory()?;
        let id = s.create_asset("cash", "A", 10000)?;
        s.rename_asset(id, "Renamed")?;
        assert_eq!(s.data()?.history.len(), 1);
        assert!(s.change_balance(id, 10000)?.is_none());
        let undo = s.change_balance(id, 12000)?.unwrap();
        assert_eq!(s.data()?.history.len(), 2);
        s.undo(&undo)?;
        assert_eq!(s.data()?.assets[0].amount_cents, 10000);
        assert_eq!(s.data()?.history.len(), 1);
        s.change_balance(id, 15000)?;
        let data = s.data()?;
        s.correct_history(data.history[0].id, 9000)?;
        assert_eq!(s.data()?.assets[0].amount_cents, 15000);
        s.correct_history(data.history[1].id, 17000)?;
        assert_eq!(s.data()?.assets[0].amount_cents, 17000);
        assert_eq!(s.data()?.history.len(), 2);
        assert!(s.change_balance(id, -1).is_err());
        assert!(s.correct_history(data.history[1].id, -1).is_err());
        assert_eq!(s.data()?.assets[0].amount_cents, 17000);
        assert_eq!(s.data()?.history.len(), 2);
        let before = s.data()?;
        let deleted = s.delete_asset(id)?;
        assert!(s.data()?.assets.is_empty());
        assert!(s.data()?.history.is_empty());
        s.undo(&deleted)?;
        let restored = s.data()?;
        assert_eq!(restored.assets[0].id, before.assets[0].id);
        assert_eq!(restored.assets[0].name, before.assets[0].name);
        assert_eq!(
            restored.assets[0].amount_cents,
            before.assets[0].amount_cents
        );
        assert_eq!(restored.history, before.history);
        assert_ne!(
            restored.assets[0].revision_uid,
            before.assets[0].revision_uid
        );
        Ok(())
    }
    #[test]
    fn example_seed_totals_and_json_roundtrip() -> Result<()> {
        let data = Storage::example_data()?;
        data.validate()?;
        assert_eq!(
            (
                data.assets.len(),
                data.history.len(),
                data.subscriptions.len()
            ),
            (11, 22, 9)
        );
        let mut totals = [0i64; 5];
        for a in &data.assets {
            let i = crate::model::CATEGORIES
                .iter()
                .position(|(c, _)| *c == a.category_code)
                .unwrap();
            totals[i] += a.amount_cents;
        }
        assert_eq!(totals, [8640000, 110000000, 42400000, 5000000, 87240000]);
        assert_eq!(totals[..4].iter().sum::<i64>() - totals[4], 78800000);
        let stats =
            crate::model::totals(&data.clone().into_ledger().subscriptions, model::today())?;
        assert_eq!(stats[0][1..], [4933, 59200]);
        assert_eq!(stats[1][1..], [4898, 58776]);
        let mut s = memory()?;
        s.replace_data(&data)?;
        let json = s.export_json()?;
        let backup: Backup = serde_json::from_str(&json)?;
        s.clear_data()?;
        assert!(s.data()?.assets.is_empty());
        s.replace_data(&backup)?;
        assert_eq!(s.export_json()?, json);
        let mut invalid = backup.clone();
        invalid.subscriptions[0].cycle = "invalid".into();
        assert!(s.replace_data(&invalid).is_err());
        assert_eq!(s.export_json()?, json);
        // A late SQL failure must also roll back the preceding DELETEs and INSERTs.
        s.connection.execute_batch("CREATE TRIGGER fail_import BEFORE INSERT ON subscriptions BEGIN SELECT RAISE(ABORT,'test'); END;")?;
        assert!(s.replace_data(&backup).is_err());
        assert_eq!(s.export_json()?, json);
        Ok(())
    }
    #[test]
    fn subscriptions_delete_undo_and_missing_history_import() -> Result<()> {
        let mut s = memory()?;
        let data = Storage::example_data()?;
        s.replace_data(&data)?;
        let before = s.export_json()?;
        let mut missing = data.clone();
        missing.history.clear();
        assert!(s.replace_data(&missing).is_err());
        assert_eq!(s.export_json()?, before);
        let original = s.data()?.subscriptions[0].clone();
        let undo = s.delete_subscription(original.id)?;
        assert_eq!(s.data()?.subscriptions.len(), 8);
        s.undo(&undo)?;
        let restored = s
            .data()?
            .subscriptions
            .into_iter()
            .find(|s| s.id == original.id)
            .unwrap();
        assert_eq!(restored.id, original.id);
        assert_eq!(restored.name, original.name);
        assert_eq!(restored.amount_cents, original.amount_cents);
        assert_eq!(restored.created_at, original.created_at);
        assert_ne!(restored.revision_uid, original.revision_uid);
        Ok(())
    }
    #[test]
    fn startup_backups_keep_ten_and_do_not_change_live_data() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let mut s = Storage::open(&dir.path().join("test.sqlite3"))?;
        s.replace_data(&Storage::example_data()?)?;
        let json = s.export_json()?;
        for _ in 0..12 {
            s.startup_backup()?;
        }
        assert_eq!(std::fs::read_dir(dir.path().join("backups"))?.count(), 10);
        assert_eq!(s.export_json()?, json);
        Ok(())
    }
}
#[cfg(test)]
mod undo_regression_tests {
    use super::*;
    #[test]
    fn undo_balance_uses_corrected_previous_snapshot_and_roundtrips() -> Result<()> {
        let mut s = Storage::open(Path::new(":memory:"))?;
        let id = s.create_asset("cash", "A", 10000)?;
        let initial = s.data()?.history[0].id;
        let undo = s.change_balance(id, 20000)?.unwrap();
        s.correct_history(initial, 15000)?;
        s.undo(&undo)?;
        let data = s.data()?;
        assert_eq!(data.assets[0].amount_cents, 15000);
        assert_eq!(data.history.len(), 1);
        assert_eq!(data.history[0].amount_cents, 15000);
        data.validate()?;
        let json = s.export_json()?;
        let backup: Backup = serde_json::from_str(&json)?;
        s.replace_data(&backup)?;
        assert_eq!(s.export_json()?, json);
        Ok(())
    }
    #[test]
    fn undo_rejects_later_balance_change_without_modifying_either_snapshot() -> Result<()> {
        let mut s = Storage::open(Path::new(":memory:"))?;
        let id = s.create_asset("cash", "A", 100)?;
        let undo = s.change_balance(id, 200)?.unwrap();
        s.change_balance(id, 300)?;
        let json = s.export_json()?;
        assert!(s.undo(&undo).is_err());
        assert_eq!(s.export_json()?, json);
        Ok(())
    }
}

#[cfg(test)]
mod sealed_snapshot_tests {
    use super::*;

    fn storage_with_data(path: &Path) -> Result<Storage> {
        let mut storage = Storage::open(path)?;
        storage.replace_data(&Storage::example_data()?)?;
        Ok(storage)
    }

    #[test]
    fn sealed_snapshot_roundtrips_without_exposing_plaintext() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let key = SyncKey::load_or_create(&crypto::key_path_for(directory.path()))?;
        let source = storage_with_data(&directory.path().join("source.sqlite3"))?;
        let sealed = source.export_sealed(&key)?;
        assert!(sealed.starts_with(b"SFS1"));
        let json = source.export_json()?;
        assert!(
            !sealed
                .windows(b"asset_items".len())
                .any(|window| window == b"asset_items"),
            "密文里不应出现明文 JSON 片段"
        );
        assert!(!sealed.windows(json.len().min(16)).any(|window| {
            window.len() == json.len().min(16) && json.as_bytes().starts_with(window)
        }));

        let mut target = Storage::open(&directory.path().join("target.sqlite3"))?;
        target.replace_from_sealed(&sealed, &key)?;
        assert_eq!(target.export_json()?, json);
        Ok(())
    }

    #[test]
    fn every_export_uses_a_fresh_nonce() -> Result<()> {
        let storage = Storage::open(Path::new(":memory:"))?;
        let key = SyncKey::from_bytes([0x11; 32]);
        let first = storage.export_sealed(&key)?;
        let second = storage.export_sealed(&key)?;
        assert_ne!(
            first[6..18],
            second[6..18],
            "同一把密钥的两次加密不能复用 nonce"
        );
        assert_ne!(first[18..], second[18..], "nonce 不同，密文正文也不应相同");
        // 两次密文都能通过认证并还原出同一份业务数据。
        for sealed in [&first, &second] {
            let info = Storage::inspect_sealed(sealed, &key)?;
            assert_eq!(info.assets, 0);
            assert_eq!(info.device_id, storage.device_id()?);
        }
        Ok(())
    }

    #[test]
    fn sealed_backup_metadata_is_authenticated_and_shown_to_the_user() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let key = SyncKey::from_bytes([0x24; 32]);
        let source = storage_with_data(&directory.path().join("source.sqlite3"))?;
        let sealed = source.export_sealed(&key)?;
        let info = Storage::inspect_sealed(&sealed, &key)?;
        assert_eq!(info.assets, 11);
        assert_eq!(info.history, 22);
        assert_eq!(info.subscriptions, 9);
        assert_eq!(info.device_id.len(), 16);
        assert_eq!(info.device_id, source.device_id()?);
        // 导出时间在展示前会被规范化成「YYYY-MM-DD HH:MM」。
        chrono::NaiveDateTime::parse_from_str(&info.exported_at, "%Y-%m-%d %H:%M")?;
        // 元数据在密文里：改动密文任何位置都会在认证阶段失败。
        let mut tampered = sealed.clone();
        let last = tampered.len() - 1;
        tampered[last] ^= 0x01;
        assert!(Storage::inspect_sealed(&tampered, &key).is_err());
        // 换一把密钥也拿不到元数据。
        assert!(Storage::inspect_sealed(&sealed, &SyncKey::from_bytes([0x25; 32])).is_err());
        Ok(())
    }

    #[test]
    fn legacy_bare_backup_sealed_file_is_rejected() -> Result<()> {
        let key = SyncKey::from_bytes([0x31; 32]);
        let mut target = Storage::open(Path::new(":memory:"))?;
        target.create_asset("cash", "existing", 100)?;
        let before = target.export_json()?;
        let legacy = crypto::seal(
            br#"{"schema_version":1,"assets":[],"history":[],"subscriptions":[]}"#,
            &key,
            crypto::SNAPSHOT_AAD,
        )?;
        assert!(Storage::inspect_sealed(&legacy, &key).is_err());
        assert!(target.replace_from_sealed(&legacy, &key).is_err());
        assert_eq!(target.export_json()?, before);
        Ok(())
    }
    #[test]
    fn sealed_metadata_is_validated_before_being_shown() -> Result<()> {
        let key = SyncKey::from_bytes([0x32; 32]);
        let data = Storage::example_data()?;
        let craft = |exported_at: &str, device_id: &str| -> Result<Vec<u8>> {
            let snapshot = SealedSnapshot {
                format: SEALED_FORMAT.to_owned(),
                version: 2,
                exported_at: exported_at.to_owned(),
                device_id: device_id.to_owned(),
                data: data.clone(),
            };
            crypto::seal(&serde_json::to_vec(&snapshot)?, &key, crypto::SNAPSHOT_AAD)
        };
        for sealed in [
            craft("not-a-date\n伪造：此备份来自可信设备", "0123456789abcdef")?,
            craft("2026-01-01T00:00:00+08:00", &"a".repeat(65536))?,
            craft("2026-01-01T00:00:00+08:00", "0123456789ABCDEF")?,
            craft("2026-01-01T00:00:00+08:00", "太短")?,
        ] {
            assert!(
                Storage::inspect_sealed(&sealed, &key).is_err(),
                "非法元数据必须被拒绝"
            );
        }
        let exported = "2026-01-01T00:00:00.000000000Z";
        let good = craft(exported, "0123456789abcdef")?;
        let info = Storage::inspect_sealed(&good, &key)?;
        let expected = chrono::DateTime::parse_from_rfc3339(exported)?
            .with_timezone(&chrono::Local)
            .format("%Y-%m-%d %H:%M")
            .to_string();
        assert_eq!(info.exported_at, expected);
        assert_eq!(info.device_id, "0123456789abcdef");
        Ok(())
    }

    #[test]
    fn imports_with_huge_ids_are_rejected_without_changes() -> Result<()> {
        let mut storage = Storage::open(Path::new(":memory:"))?;
        storage.create_asset("cash", "existing", 100)?;
        let before = storage.export_json()?;
        let mut data: serde_json::Value = serde_json::from_str(&before)?;
        data["assets"][0]["id"] = serde_json::json!(i64::MAX);
        assert!(
            storage.import_json(&data.to_string()).is_err(),
            "整数编号必须被拒绝"
        );
        assert_eq!(storage.export_json()?, before);
        Ok(())
    }

    #[test]
    fn device_id_is_stable_across_reopen() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = directory.path().join("device.sqlite3");
        let first = Storage::open(&path)?.device_id()?;
        assert_eq!(Storage::open(&path)?.device_id()?, first);
        assert_eq!(first.len(), 16);
        Ok(())
    }

    #[test]
    fn wrong_key_never_modifies_existing_data() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let source = storage_with_data(&directory.path().join("source.sqlite3"))?;
        let sealed = source.export_sealed(&SyncKey::from_bytes([0x01; 32]))?;

        let mut target = Storage::open(&directory.path().join("target.sqlite3"))?;
        target.create_asset("cash", "existing", 12300)?;
        let before = target.export_json()?;
        assert!(
            target
                .replace_from_sealed(&sealed, &SyncKey::from_bytes([0x02; 32]))
                .is_err()
        );
        assert_eq!(target.export_json()?, before);
        assert_eq!(target.data()?.assets.len(), 1);
        Ok(())
    }

    #[test]
    fn tampered_or_foreign_payloads_are_rejected_without_changes() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let key = SyncKey::from_bytes([0x33; 32]);
        let source = storage_with_data(&directory.path().join("source.sqlite3"))?;
        let sealed = source.export_sealed(&key)?;
        let mut target = Storage::open(&directory.path().join("target.sqlite3"))?;
        target.create_asset("cash", "existing", 500)?;
        let before = target.export_json()?;

        for index in [0usize, 6, sealed.len() - 1] {
            let mut tampered = sealed.clone();
            tampered[index] ^= 0xff;
            assert!(target.replace_from_sealed(&tampered, &key).is_err());
            assert_eq!(target.export_json()?, before);
        }
        for truncated in [&sealed[..sealed.len() - 1], &sealed[..10]] {
            assert!(target.replace_from_sealed(truncated, &key).is_err());
            assert_eq!(target.export_json()?, before);
        }
        // 用同一把密钥、同一 AAD 封装的非备份内容，必须被结构校验挡住。
        let foreign = crypto::seal(b"{\"hello\":1}", &key, crypto::SNAPSHOT_AAD)?;
        assert!(target.replace_from_sealed(&foreign, &key).is_err());
        assert_eq!(target.export_json()?, before);
        Ok(())
    }

    #[test]
    fn sealed_backup_carries_every_field_of_the_json_backup() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let key = SyncKey::from_bytes([0x77; 32]);
        let source = storage_with_data(&directory.path().join("source.sqlite3"))?;
        let sealed = source.export_sealed(&key)?;
        let mut target = Storage::open(&directory.path().join("target.sqlite3"))?;
        target.replace_from_sealed(&sealed, &key)?;
        assert_eq!(target.data()?.assets.len(), 11);
        assert_eq!(target.data()?.history.len(), 22);
        assert_eq!(target.data()?.subscriptions.len(), 9);
        Ok(())
    }
}

#[cfg(test)]
mod schema_contract_tests {
    use super::*;
    fn memory() -> Result<Storage> {
        Storage::open(Path::new(":memory:"))
    }
    #[test]
    fn corrections_keep_original_values_and_undo_voids_instead_of_deleting() -> Result<()> {
        let mut s = memory()?;
        let asset = s.create_asset("cash", "A", 10000)?;
        let first = s.data()?.history[0].id;
        let ticket = s.change_balance(asset, 20000)?.unwrap();
        let latest = s.data()?.assets[0].current_history_id;
        s.correct_history(first, 15000)?;
        s.correct_history(first, 17000)?;
        let corrections = s.history_corrections(first)?;
        assert_eq!(
            (
                corrections[0].previous_amount_cents,
                corrections[0].amount_cents
            ),
            (10000, 15000)
        );
        assert_eq!(
            (
                corrections[1].previous_amount_cents,
                corrections[1].amount_cents
            ),
            (15000, 17000)
        );
        s.undo(&ticket)?;
        let backup = s.backup_snapshot()?;
        assert_eq!(backup.assets[0].amount_cents, 17000);
        assert_eq!(backup.assets[0].current_history_id, first);
        assert_eq!(backup.all_history().count(), 2);
        assert_eq!(backup.history[0].original_amount_cents, 10000);
        assert!(
            backup
                .voided_history
                .iter()
                .find(|h| h.id == latest)
                .unwrap()
                .voided_at
                .is_some()
        );
        assert_eq!(s.data()?.history.len(), 1);
        let json = s.export_json()?;
        s.import_json(&json)?;
        assert_eq!(s.export_json()?, json);
        Ok(())
    }
    #[test]
    fn delete_erases_content_and_restores_same_uuids_with_new_revisions() -> Result<()> {
        let mut s = memory()?;
        s.replace_data(&Storage::example_data()?)?;
        let asset = s.data()?.assets[0].clone();
        s.correct_history(asset.current_history_id, 1)?;
        let original = s.backup_snapshot()?;
        let ticket = s.delete_asset(asset.id)?;
        let deleted = s.backup_snapshot()?;
        assert!(!deleted.assets.iter().any(|a| a.id == asset.id));
        assert!(!deleted.history.iter().any(|h| h.asset_id == asset.id));
        assert!(!deleted.corrections.iter().any(|c| {
            original
                .history
                .iter()
                .any(|h| h.asset_id == asset.id && h.id == c.history_id)
        }));
        assert_eq!(
            deleted
                .tombstones
                .iter()
                .filter(|t| t.id == asset.id)
                .count(),
            1
        );
        s.undo(&ticket)?;
        let restored = s.backup_snapshot()?;
        let restored_asset = restored.assets.iter().find(|a| a.id == asset.id).unwrap();
        assert_eq!(restored_asset.id, asset.id);
        assert_ne!(restored_asset.revision_uid, asset.revision_uid);
        assert_eq!(restored_asset.amount_cents, 1);
        let old_ids: std::collections::HashSet<_> = original
            .history
            .iter()
            .filter(|h| h.asset_id == asset.id)
            .map(|h| h.id)
            .collect();
        let new_ids = restored
            .history
            .iter()
            .filter(|h| h.asset_id == asset.id)
            .map(|h| h.id)
            .collect();
        assert_eq!(old_ids, new_ids);
        assert!(restored.tombstones.iter().all(|t| t.id != asset.id));
        Ok(())
    }
    #[test]
    fn expired_wrong_and_stale_tokens_do_not_touch_other_objects() -> Result<()> {
        let mut s = memory()?;
        let a = s.create_asset("cash", "A", 100)?;
        let b = s.create_asset("cash", "B", 500)?;
        let ticket = s.change_balance(a, 200)?.unwrap();
        let before = s.export_json()?;
        assert!(s.undo_token("wrong").is_err());
        assert_eq!(s.export_json()?, before);
        s.rename_asset(a, "changed")?;
        let before = s.export_json()?;
        assert!(s.undo(&ticket).is_err());
        assert_eq!(s.export_json()?, before);
        let ticket = s.delete_asset(a)?;
        s.undo.as_mut().unwrap().created = Instant::now() - Duration::from_secs(6);
        let before = s.export_json()?;
        assert!(s.undo(&ticket).is_err());
        assert_eq!(s.export_json()?, before);
        assert_eq!(s.data()?.assets[0].id, b);
        assert_eq!(s.data()?.assets[0].amount_cents, 500);
        let ticket = s.delete_asset(b)?;
        s.clear_data()?;
        assert!(s.undo(&ticket).is_err());
        Ok(())
    }
    #[test]
    fn old_and_future_databases_are_rejected_without_changes() -> Result<()> {
        for version in [0, 1, 4] {
            let directory = tempfile::tempdir()?;
            let path = directory.path().join("old.sqlite3");
            let db = Connection::open(&path)?;
            db.execute("CREATE TABLE original(value TEXT)", [])?;
            db.execute("INSERT INTO original VALUES('keep')", [])?;
            db.pragma_update(None, "user_version", version)?;
            drop(db);
            let before = std::fs::read(&path)?;
            let error = Storage::open(&path).err().unwrap();
            assert!(error.downcast_ref::<IncompatibleDatabase>().is_some());
            assert!(error.to_string().contains("数据库版本不兼容"));
            assert_eq!(std::fs::read(&path)?, before);
        }
        Ok(())
    }
    #[test]
    fn migrations_commit_separately_and_failed_step_rolls_back() -> Result<()> {
        let mut db = Connection::open_in_memory()?;
        let steps = [
            Migration {
                version: 2,
                sql: include_str!("schema.sql"),
            },
            Migration {
                version: 3,
                sql: "CREATE TABLE partial(id INTEGER); INSERT INTO missing VALUES(1);",
            },
        ];
        assert!(apply_migrations(&mut db, &steps).is_err());
        assert_eq!(
            db.pragma_query_value(None, "user_version", |r| r.get::<_, i64>(0))?,
            2
        );
        assert_eq!(
            db.query_row(
                "SELECT count(*) FROM sqlite_master WHERE name='partial'",
                [],
                |r| r.get::<_, i64>(0)
            )?,
            0
        );
        assert_eq!(
            db.query_row("SELECT count(*) FROM categories", [], |r| r
                .get::<_, i64>(0))?,
            5
        );
        initialize(&mut db)?;
        Ok(())
    }
    #[test]
    fn snapshot_is_consistent_when_another_connection_writes_mid_export() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = directory.path().join("snapshot.sqlite3");
        let mut reader = Storage::open(&path)?;
        reader
            .connection
            .pragma_update(None, "journal_mode", "WAL")?;
        let asset = reader.create_asset("cash", "A", 100)?;
        let mut writer = Storage::open(&path)?;
        let snapshot = reader.snapshot_with_hook(|| {
            writer.change_balance(asset, 200)?;
            Ok(())
        })?;
        snapshot.validate()?;
        assert_eq!(snapshot.assets[0].amount_cents, 100);
        assert_eq!(snapshot.history.len(), 1);
        assert_eq!(reader.data()?.assets[0].amount_cents, 200);
        let json = reader.export_json()?;
        Backup::decode(&json)?.validate()?;
        let key = SyncKey::from_bytes([1; 32]);
        Storage::inspect_sealed(&reader.export_sealed(&key)?, &key)?;
        reader.startup_backup()?;
        for entry in std::fs::read_dir(directory.path().join("backups"))? {
            Backup::decode(&std::fs::read_to_string(entry?.path())?)?;
        }
        Ok(())
    }
    #[test]
    fn restore_keeps_local_replica_theme_and_key_but_transfers_ledger() -> Result<()> {
        let mut source = memory()?;
        source.create_asset("cash", "A", 0)?;
        let ticket = source.delete_asset(source.data()?.assets[0].id)?;
        let mut target = memory()?;
        let replica = target.replica_id()?;
        let device = target.device_id()?;
        target.save_theme(Theme::Dark)?;
        target.import_json(&source.export_json()?)?;
        assert_eq!(target.ledger_id()?, source.ledger_id()?);
        assert_eq!(target.replica_id()?, replica);
        assert_eq!(target.device_id()?, device);
        assert_eq!(target.theme()?, Theme::Dark);
        assert_eq!(
            target.backup_snapshot()?.tombstones,
            source.backup_snapshot()?.tombstones
        );
        assert!(target.undo(&ticket).is_err());
        Ok(())
    }
    #[test]
    fn failed_writes_preserve_all_rows_and_release_transactions() -> Result<()> {
        let mut s = memory()?;
        s.replace_data(&Storage::example_data()?)?;
        let before = s.export_json()?;
        let missing = Id::new()?;
        assert!(s.rename_asset(missing, "missing").is_err());
        assert!(s.rename_asset(s.data()?.assets[0].id, " ").is_err());
        assert!(s.delete_asset(missing).is_err());
        assert!(s.delete_subscription(missing).is_err());
        let input = SubscriptionInput::from(&s.data()?.subscriptions[0]);
        assert!(s.update_subscription(missing, &input).is_err());
        let mut invalid = input.clone();
        invalid.currency = "EUR".into();
        assert!(s.create_subscription(&invalid).is_err());
        assert!(
            s.update_subscription(s.data()?.subscriptions[0].id, &invalid)
                .is_err()
        );
        assert_eq!(s.export_json()?, before);
        s.connection.execute_batch("CREATE TRIGGER fail_clear BEFORE DELETE ON subscriptions BEGIN SELECT RAISE(ABORT,'test'); END;")?;
        assert!(s.clear_data().is_err());
        assert_eq!(s.export_json()?, before);
        s.connection.execute_batch("DROP TRIGGER fail_clear; CREATE TRIGGER fail_balance BEFORE UPDATE ON asset_items BEGIN SELECT RAISE(ABORT,'test'); END;")?;
        let asset = s.data()?.assets[0].id;
        assert!(s.change_balance(asset, 1).is_err());
        assert!(
            s.correct_history(s.data()?.assets[0].current_history_id, 1)
                .is_err()
        );
        assert_eq!(s.export_json()?, before);
        s.connection.execute_batch("DROP TRIGGER fail_balance;")?;
        s.clear_data()?;
        assert!(s.data()?.assets.is_empty());
        Ok(())
    }
    #[test]
    fn malformed_ids_times_chains_and_future_backups_are_atomic() -> Result<()> {
        let mut s = memory()?;
        let id = s.create_asset("cash", "A", 100)?;
        s.change_balance(id, 200)?;
        let before = s.export_json()?;
        let original: serde_json::Value = serde_json::from_str(&before)?;
        for (path, value) in [
            (vec!["assets", "0", "id"], serde_json::json!("not-a-uuid")),
            (
                vec!["assets", "0", "created_at"],
                serde_json::json!("2026-02-30T00:00:00.000000000Z"),
            ),
            (
                vec!["history", "0", "recorded_at"],
                serde_json::json!("2026-01-01T00:00:00+08:00"),
            ),
            (vec!["assets", "0", "amount_cents"], serde_json::json!(0)),
        ] {
            let mut value_root = original.clone();
            value_root[path[0]][path[1].parse::<usize>()?][path[2]] = value;
            assert!(s.import_json(&value_root.to_string()).is_err());
            assert_eq!(s.export_json()?, before);
        }
        let mut data = s.backup_snapshot()?;
        data.history[0].previous_id = Some(data.history[1].id);
        assert!(s.replace_data(&data).is_err());
        let mut future = original.clone();
        future["format_version"] = serde_json::json!(999);
        assert!(
            s.import_json(&future.to_string())
                .unwrap_err()
                .downcast_ref::<crate::backup::BackupError>()
                .is_some()
        );
        assert_eq!(s.export_json()?, before);
        // DDL uses built-in SQLite checks only; malformed shapes are rejected directly.
        assert!(
            s.connection
                .execute("UPDATE asset_items SET uid='bad'", [])
                .is_err()
        );
        assert!(
            s.connection
                .execute("UPDATE asset_items SET updated_at='bad'", [])
                .is_err()
        );
        Ok(())
    }
    #[test]
    fn snapshot_order_uses_references_even_when_clock_moves_backwards() -> Result<()> {
        let mut s = memory()?;
        let id = s.create_asset("cash", "A", 100)?;
        let ticket = s.change_balance(id, 200)?.unwrap();
        let mut data = s.backup_snapshot()?;
        data.history[0].recorded_at = "2030-01-01T00:00:00.000000000Z".into();
        data.history[1].recorded_at = "2020-01-01T00:00:00.000000000Z".into();
        s.replace_data(&data)?;
        assert!(s.undo(&ticket).is_err()); // restore invalidates process-local capabilities
        let first = data.history[0].id;
        s.correct_history(first, 150)?;
        assert_eq!(s.data()?.assets[0].amount_cents, 200);
        let ticket = s.change_balance(id, 300)?.unwrap();
        s.undo(&ticket)?;
        assert_eq!(s.data()?.assets[0].amount_cents, 200);
        Ok(())
    }
}

#[cfg(test)]
mod sync_finish_transaction_tests {
    use super::*;

    #[test]
    fn sync_finish_preserves_another_connection_write() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = directory.path().join("snowfold.sqlite3");
        let mut storage = Storage::open(&path)?;
        storage.create_asset("cash", "原始", 1)?;
        let pushed = storage.backup_snapshot()?;
        let mut second = Storage::open(&path)?;
        second.create_asset("cash", "另一连接在收尾窗口提交", 2)?;
        storage.finish_sync(&now(), |latest| {
            Ok(crate::sync::merge::merge(&pushed, latest)?)
        })?;
        assert_eq!(
            storage.data()?.assets.len(),
            2,
            "收尾不得覆盖另一连接的已提交写入"
        );
        Ok(())
    }
    #[test]
    fn sync_finish_holds_sqlite_write_reservation_through_merge_and_releases_on_error() -> Result<()>
    {
        let directory = tempfile::tempdir()?;
        let path = directory.path().join("snowfold.sqlite3");
        let mut storage = Storage::open(&path)?;
        storage.create_asset("cash", "原始", 1)?;
        let second = Connection::open(&path)?;
        second.busy_timeout(Duration::ZERO)?;
        storage.finish_sync(&now(), |latest| {
            let error=second.execute("UPDATE app_meta SET value='dark' WHERE key='theme'", []).unwrap_err();
            assert!(matches!(error,rusqlite::Error::SqliteFailure(code,_) if code.code==rusqlite::ErrorCode::DatabaseBusy));
            Ok(latest.clone())
        })?;
        assert_eq!(
            second.execute("UPDATE app_meta SET value='dark' WHERE key='theme'", [])?,
            1
        );
        assert!(storage.finish_sync(&now(), |_| bail!("拒绝合并")).is_err());
        assert_eq!(
            second.execute("UPDATE app_meta SET value='light' WHERE key='theme'", [])?,
            1
        );
        Ok(())
    }
}
