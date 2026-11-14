//! Host boundary: integer cents, ISO dates, stable errors, and one serialized connection.
use crate::{backup, crypto, model, storage};
use chrono::NaiveDate;
use std::{
    fmt,
    path::PathBuf,
    sync::{Arc, Mutex, MutexGuard},
};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum CoreError {
    Validation,
    NotFound,
    Conflict,
    Authentication,
    IncompatibleBackup,
    IncompatibleDatabase,
    InvalidBackup,
    InvalidRecoveryCode,
    KeyUnavailable,
    Io,
    Database,
    Overflow,
    Internal,
}
impl fmt::Display for CoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Validation => "请检查名称、金额、日期与选项",
            Self::NotFound => "记录不存在，请刷新后重试",
            Self::Conflict => "数据已变化或正在使用，请刷新后重试",
            Self::Authentication => "无法解密：同步密钥不匹配或文件已损坏",
            Self::IncompatibleDatabase => "数据库版本不兼容",
            Self::IncompatibleBackup => "不支持的备份版本或加密格式",
            Self::InvalidBackup => "备份内容不完整或不合法，原数据未改变",
            Self::InvalidRecoveryCode => "恢复码不合法，本机密钥未改变",
            Self::KeyUnavailable => "同步密钥不可用，请检查密钥文件或导入恢复码",
            Self::Io => "无法读写文件，请检查目录与权限",
            Self::Database => "无法读写本机数据库",
            Self::Overflow => "金额合计超出整数范围，请检查数据",
            Self::Internal => "核心状态不可用，请重新打开",
        })
    }
}
impl std::error::Error for CoreError {}
type Result<T> = std::result::Result<T, CoreError>;

fn boundary_error(error: anyhow::Error, fallback: CoreError) -> CoreError {
    if error
        .downcast_ref::<storage::IncompatibleDatabase>()
        .is_some()
    {
        return CoreError::IncompatibleDatabase;
    }
    if error.downcast_ref::<storage::UndoConflict>().is_some() {
        return CoreError::Conflict;
    }
    if let Some(error) = error.downcast_ref::<backup::BackupError>() {
        return match error {
            backup::BackupError::Incompatible => CoreError::IncompatibleBackup,
            backup::BackupError::Invalid => CoreError::InvalidBackup,
        };
    }
    if let Some(error) = error.downcast_ref::<crypto::CryptoError>() {
        return match error {
            crypto::CryptoError::Authentication => CoreError::Authentication,
            crypto::CryptoError::UnsupportedFormat(_)
            | crypto::CryptoError::UnsupportedAlgorithm(_) => CoreError::IncompatibleBackup,
            crypto::CryptoError::Truncated | crypto::CryptoError::BadMagic => {
                CoreError::InvalidBackup
            }
        };
    }
    if error.downcast_ref::<std::io::Error>().is_some() {
        return CoreError::Io;
    }
    if let Some(error) = error.downcast_ref::<rusqlite::Error>() {
        return match error {
            rusqlite::Error::QueryReturnedNoRows => CoreError::NotFound,
            rusqlite::Error::SqliteFailure(code, _)
                if matches!(
                    code.code,
                    rusqlite::ErrorCode::DatabaseBusy
                        | rusqlite::ErrorCode::DatabaseLocked
                        | rusqlite::ErrorCode::ConstraintViolation
                ) =>
            {
                CoreError::Conflict
            }
            _ => CoreError::Database,
        };
    }
    fallback
}
fn db<T>(result: anyhow::Result<T>) -> Result<T> {
    result.map_err(|e| boundary_error(e, CoreError::Database))
}
fn date(value: &str) -> Result<NaiveDate> {
    model::parse_date(value).map_err(|_| CoreError::Validation)
}
fn amount(cents: i64) -> Result<()> {
    if cents < 0 {
        Err(CoreError::Validation)
    } else {
        Ok(())
    }
}
fn name(value: &str) -> Result<()> {
    if value.trim().is_empty() {
        Err(CoreError::Validation)
    } else {
        Ok(())
    }
}

/// Persisted record identifier; keep all host-visible IDs on this alias.
pub type RecordId = String;
fn record_id(value: &str) -> Result<model::Id> {
    value.parse().map_err(|_| CoreError::Validation)
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct AssetRecord {
    pub id: RecordId,
    pub revision_uid: RecordId,
    pub current_history_id: RecordId,
    pub category_code: String,
    pub name: String,
    pub amount_cents: i64,
    pub created_at: String,
    pub updated_at: String,
}
impl From<model::Asset> for AssetRecord {
    fn from(a: model::Asset) -> Self {
        Self {
            id: a.id.to_string(),
            revision_uid: a.revision_uid.to_string(),
            current_history_id: a.current_history_id.to_string(),
            category_code: a.category_code,
            name: a.name,
            amount_cents: a.amount_cents,
            created_at: a.created_at,
            updated_at: a.updated_at,
        }
    }
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct HistoryRecord {
    pub id: RecordId,
    pub asset_id: RecordId,
    pub previous_id: Option<RecordId>,
    pub original_amount_cents: i64,
    pub amount_cents: i64,
    pub recorded_at: String,
}
impl From<model::History> for HistoryRecord {
    fn from(h: model::History) -> Self {
        Self {
            id: h.id.to_string(),
            asset_id: h.asset_id.to_string(),
            previous_id: h.previous_id.map(|id| id.to_string()),
            original_amount_cents: h.original_amount_cents,
            amount_cents: h.amount_cents,
            recorded_at: h.recorded_at,
        }
    }
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct SubscriptionRecord {
    pub id: RecordId,
    pub revision_uid: RecordId,
    pub created_at: String,
    pub updated_at: String,
    pub name: String,
    pub amount_cents: i64,
    pub currency: String,
    pub cycle: String,
    pub next_charge_on: String,
    pub status: String,
}
impl From<model::Subscription> for SubscriptionRecord {
    fn from(s: model::Subscription) -> Self {
        Self {
            id: s.id.to_string(),
            revision_uid: s.revision_uid.to_string(),
            created_at: s.created_at,
            updated_at: s.updated_at,
            name: s.name,
            amount_cents: s.amount_cents,
            currency: s.currency,
            cycle: s.cycle,
            next_charge_on: s.next_charge_on.to_string(),
            status: s.status,
        }
    }
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct SubscriptionInput {
    pub name: String,
    pub amount_cents: i64,
    pub currency: String,
    pub cycle: String,
    pub next_charge_on: String,
    pub status: String,
}
impl SubscriptionInput {
    fn into_model(self) -> Result<model::SubscriptionInput> {
        let input = model::SubscriptionInput {
            name: self.name,
            amount_cents: self.amount_cents,
            currency: self.currency,
            cycle: self.cycle,
            next_charge_on: date(&self.next_charge_on)?,
            status: self.status,
        };
        input.validate().map_err(|_| CoreError::Validation)?;
        Ok(input)
    }
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct HistoryCorrectionRecord {
    pub id: RecordId,
    pub history_id: RecordId,
    pub previous_amount_cents: i64,
    pub amount_cents: i64,
    pub recorded_at: String,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct LedgerData {
    pub assets: Vec<AssetRecord>,
    pub history: Vec<HistoryRecord>,
    pub subscriptions: Vec<SubscriptionRecord>,
}
impl From<model::Ledger> for LedgerData {
    fn from(b: model::Ledger) -> Self {
        Self {
            assets: b.assets.into_iter().map(Into::into).collect(),
            history: b.history.into_iter().map(Into::into).collect(),
            subscriptions: b.subscriptions.into_iter().map(Into::into).collect(),
        }
    }
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct CategoryTotal {
    pub code: String,
    pub name: String,
    pub is_liability: bool,
    pub amount_cents: i64,
    pub count: u64,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct CurrencyTotal {
    pub currency: String,
    pub due_this_month_cents: i64,
    pub monthly_cents: i64,
    pub annual_cents: i64,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct Overview {
    pub total_assets_cents: i64,
    pub total_debt_cents: i64,
    pub net_assets_cents: i64,
    pub categories: Vec<CategoryTotal>,
    pub subscription_totals: Vec<CurrencyTotal>,
    pub active_subscriptions: u64,
    pub paused_subscriptions: u64,
    pub overdue_subscriptions: u64,
    pub next_charge_on: Option<String>,
    pub upcoming: Vec<SubscriptionRecord>,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ThemePreference {
    System,
    Light,
    Dark,
}
impl From<ThemePreference> for storage::Theme {
    fn from(t: ThemePreference) -> Self {
        match t {
            ThemePreference::System => Self::System,
            ThemePreference::Light => Self::Light,
            ThemePreference::Dark => Self::Dark,
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SubscriptionGroup {
    Overdue,
    ThisMonth,
    NextMonth,
    Later,
    Paused,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct UndoTicket {
    pub token: String,
    pub valid_for_ms: u32,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct BackupInfo {
    pub assets: u64,
    pub history: u64,
    pub subscriptions: u64,
}
impl From<&backup::Backup> for BackupInfo {
    fn from(b: &backup::Backup) -> Self {
        Self {
            assets: b.assets.len() as u64,
            history: b.history.iter().filter(|h| h.voided_at.is_none()).count() as u64,
            subscriptions: b.subscriptions.len() as u64,
        }
    }
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct SealedBackupInfo {
    pub exported_at_display: String,
    pub device_id: String,
    pub counts: BackupInfo,
}
impl From<storage::SealedInfo> for SealedBackupInfo {
    fn from(i: storage::SealedInfo) -> Self {
        Self {
            exported_at_display: i.exported_at,
            device_id: i.device_id,
            counts: BackupInfo {
                assets: i.assets as u64,
                history: i.history as u64,
                subscriptions: i.subscriptions as u64,
            },
        }
    }
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct RecoveryCodeInfo {
    pub current_fingerprint: Option<String>,
    pub new_fingerprint: String,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct KeyImportResult {
    pub fingerprint: String,
    pub previous_key_backed_up: bool,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct RenewalResult {
    pub next_charge_on: String,
    pub periods: u32,
}

struct State {
    storage: storage::Storage,
}
impl From<storage::Undo> for UndoTicket {
    fn from(ticket: storage::Undo) -> Self {
        Self {
            token: ticket.token,
            valid_for_ms: ticket.valid_for_ms,
        }
    }
}
impl State {
    fn asset(&self, id: model::Id) -> Result<model::Asset> {
        db(self.storage.data())?
            .assets
            .into_iter()
            .find(|a| a.id == id)
            .ok_or(CoreError::NotFound)
    }
    fn subscription(&self, id: model::Id) -> Result<model::Subscription> {
        db(self.storage.data())?
            .subscriptions
            .into_iter()
            .find(|s| s.id == id)
            .ok_or(CoreError::NotFound)
    }
    fn key_path(&self) -> Result<PathBuf> {
        self.storage
            .data_dir()
            .map(crypto::key_path_for)
            .ok_or(CoreError::Internal)
    }
    fn key(&self) -> Result<crypto::SyncKey> {
        crypto::SyncKey::load_or_create(&self.key_path()?)
            .map_err(|e| boundary_error(e, CoreError::KeyUnavailable))
    }
    fn fingerprint(&self) -> Result<Option<String>> {
        crypto::SyncKey::load(&self.key_path()?)
            .map(|key| key.map(|k| k.fingerprint()))
            .map_err(|e| boundary_error(e, CoreError::KeyUnavailable))
    }
}

#[derive(uniffi::Object)]
pub struct SnowFoldCore {
    state: Mutex<State>,
}
impl SnowFoldCore {
    fn lock(&self) -> Result<MutexGuard<'_, State>> {
        self.state.lock().map_err(|_| CoreError::Internal)
    }
}

fn parse_backup(json: &str) -> Result<backup::Backup> {
    backup::Backup::decode(json).map_err(|e| boundary_error(e, CoreError::InvalidBackup))
}
fn inspect_sealed(bytes: &[u8], key: &crypto::SyncKey) -> Result<SealedBackupInfo> {
    storage::Storage::inspect_sealed(bytes, key)
        .map(Into::into)
        .map_err(|e| boundary_error(e, CoreError::InvalidBackup))
}

#[uniffi::export]
impl SnowFoldCore {
    #[uniffi::constructor]
    pub fn open(data_dir: String) -> Result<Arc<Self>> {
        let directory = PathBuf::from(data_dir);
        if !directory.is_absolute() {
            return Err(CoreError::Validation);
        }
        std::fs::create_dir_all(&directory).map_err(|_| CoreError::Io)?;
        let directory = std::fs::canonicalize(directory).map_err(|_| CoreError::Io)?;
        let storage = db(storage::Storage::open(&directory.join("snowfold.sqlite3")))?;
        Ok(Arc::new(Self {
            state: Mutex::new(State { storage }),
        }))
    }
    pub fn data(&self) -> Result<LedgerData> {
        let s = self.lock()?;
        Ok(db(s.storage.data())?.into())
    }
    pub fn overview(&self, on: String) -> Result<Overview> {
        let s = self.lock()?;
        let on = date(&on)?;
        let data = db(s.storage.data())?;
        let mut categories: Vec<CategoryTotal> = model::CATEGORIES
            .iter()
            .map(|(code, name)| CategoryTotal {
                code: (*code).into(),
                name: (*name).into(),
                is_liability: *code == "debt",
                amount_cents: 0,
                count: 0,
            })
            .collect();
        for asset in &data.assets {
            let c = categories
                .iter_mut()
                .find(|c| c.code == asset.category_code)
                .ok_or(CoreError::Database)?;
            c.amount_cents = c
                .amount_cents
                .checked_add(asset.amount_cents)
                .ok_or(CoreError::Overflow)?;
            c.count += 1;
        }
        let total_assets_cents = categories[..4]
            .iter()
            .try_fold(0i64, |sum, c| sum.checked_add(c.amount_cents))
            .ok_or(CoreError::Overflow)?;
        let total_debt_cents = categories[4].amount_cents;
        let net_assets_cents = total_assets_cents
            .checked_sub(total_debt_cents)
            .ok_or(CoreError::Overflow)?;
        let totals = model::totals(&data.subscriptions, on).map_err(|_| CoreError::Overflow)?;
        let subscription_totals = ["CNY", "USD"]
            .into_iter()
            .enumerate()
            .map(|(i, currency)| CurrencyTotal {
                currency: currency.into(),
                due_this_month_cents: totals[i][0],
                monthly_cents: totals[i][1],
                annual_cents: totals[i][2],
            })
            .collect();
        let active: Vec<_> = data
            .subscriptions
            .iter()
            .filter(|s| s.status == "active")
            .collect();
        let end = on.checked_add_days(chrono::Days::new(30)).unwrap_or(on);
        let upcoming = active
            .iter()
            .filter(|s| s.next_charge_on <= end)
            .take(5)
            .map(|s| SubscriptionRecord::from((*s).clone()))
            .collect();
        Ok(Overview {
            total_assets_cents,
            total_debt_cents,
            net_assets_cents,
            categories,
            subscription_totals,
            active_subscriptions: active.len() as u64,
            paused_subscriptions: (data.subscriptions.len() - active.len()) as u64,
            overdue_subscriptions: active.iter().filter(|s| s.next_charge_on < on).count() as u64,
            next_charge_on: active
                .iter()
                .map(|s| s.next_charge_on)
                .min()
                .map(|d| d.to_string()),
            upcoming,
        })
    }
    pub fn create_asset(
        &self,
        category_code: String,
        asset_name: String,
        amount_cents: i64,
    ) -> Result<RecordId> {
        let mut s = self.lock()?;
        name(&asset_name)?;
        amount(amount_cents)?;
        if !model::CATEGORIES.iter().any(|(c, _)| *c == category_code) {
            return Err(CoreError::Validation);
        }
        db(s.storage
            .create_asset(&category_code, &asset_name, amount_cents))
        .map(|id| id.to_string())
    }
    pub fn rename_asset(&self, id: RecordId, asset_name: String) -> Result<()> {
        let id = record_id(&id)?;
        let mut s = self.lock()?;
        name(&asset_name)?;
        s.asset(id)?;
        db(s.storage.rename_asset(id, &asset_name))
    }
    pub fn change_balance(&self, id: RecordId, amount_cents: i64) -> Result<Option<UndoTicket>> {
        let id = record_id(&id)?;
        let mut s = self.lock()?;
        amount(amount_cents)?;
        s.asset(id)?;
        Ok(db(s.storage.change_balance(id, amount_cents))?.map(Into::into))
    }
    pub fn correct_history(&self, id: RecordId, amount_cents: i64) -> Result<()> {
        let id = record_id(&id)?;
        let mut s = self.lock()?;
        amount(amount_cents)?;
        if !db(s.storage.data())?.history.iter().any(|h| h.id == id) {
            return Err(CoreError::NotFound);
        }
        db(s.storage.correct_history(id, amount_cents))
    }
    pub fn delete_asset(&self, id: RecordId) -> Result<UndoTicket> {
        let id = record_id(&id)?;
        let mut s = self.lock()?;
        s.asset(id)?;
        let action = db(s.storage.delete_asset(id))?;
        Ok(action.into())
    }
    pub fn undo(&self, token: String) -> Result<()> {
        let mut s = self.lock()?;
        s.storage
            .undo_token(&token)
            .map_err(|e| boundary_error(e, CoreError::Conflict))
    }
    pub fn create_subscription(&self, subscription: SubscriptionInput) -> Result<RecordId> {
        let mut s = self.lock()?;
        let input = subscription.into_model()?;
        db(s.storage.create_subscription(&input)).map(|id| id.to_string())
    }
    pub fn update_subscription(&self, id: RecordId, subscription: SubscriptionInput) -> Result<()> {
        let id = record_id(&id)?;
        let mut s = self.lock()?;
        let input = subscription.into_model()?;
        db(s.storage.update_subscription(id, &input))
    }
    pub fn history_corrections(&self, id: RecordId) -> Result<Vec<HistoryCorrectionRecord>> {
        let id = record_id(&id)?;
        let s = self.lock()?;
        Ok(db(s.storage.history_corrections(id))?
            .into_iter()
            .map(|c| HistoryCorrectionRecord {
                id: c.id.to_string(),
                history_id: c.history_id.to_string(),
                previous_amount_cents: c.previous_amount_cents,
                amount_cents: c.amount_cents,
                recorded_at: c.recorded_at,
            })
            .collect())
    }
    pub fn set_subscription_paused(&self, id: RecordId, is_paused: bool) -> Result<()> {
        let id = record_id(&id)?;
        let mut s = self.lock()?;
        let mut subscription = s.subscription(id)?;
        subscription.status = if is_paused { "paused" } else { "active" }.into();
        db(s.storage
            .update_subscription(id, &model::SubscriptionInput::from(&subscription)))?;
        Ok(())
    }
    pub fn renew_subscription(&self, id: RecordId, on: String) -> Result<RenewalResult> {
        let id = record_id(&id)?;
        let mut s = self.lock()?;
        let on = date(&on)?;
        let mut subscription = s.subscription(id)?;
        if subscription.status != "active" {
            return Err(CoreError::Conflict);
        }
        let (next, periods) = subscription.renew(on).map_err(|_| CoreError::Validation)?;
        if periods == 0 {
            return Err(CoreError::Conflict);
        }
        subscription.next_charge_on = next;
        db(s.storage
            .update_subscription(id, &model::SubscriptionInput::from(&subscription)))?;
        Ok(RenewalResult {
            next_charge_on: next.to_string(),
            periods,
        })
    }
    pub fn subscription_group(&self, id: RecordId, on: String) -> Result<SubscriptionGroup> {
        let id = record_id(&id)?;
        let s = self.lock()?;
        let on = date(&on)?;
        Ok(match s.subscription(id)?.group(on) {
            0 => SubscriptionGroup::Overdue,
            1 => SubscriptionGroup::ThisMonth,
            2 => SubscriptionGroup::NextMonth,
            3 => SubscriptionGroup::Later,
            _ => SubscriptionGroup::Paused,
        })
    }
    pub fn subscription_annual(&self, id: RecordId) -> Result<i64> {
        let id = record_id(&id)?;
        let s = self.lock()?;
        s.subscription(id)?
            .annual()
            .map_err(|_| CoreError::Overflow)
    }
    pub fn delete_subscription(&self, id: RecordId) -> Result<UndoTicket> {
        let id = record_id(&id)?;
        let mut s = self.lock()?;
        s.subscription(id)?;
        let action = db(s.storage.delete_subscription(id))?;
        Ok(action.into())
    }
    pub fn theme(&self) -> Result<ThemePreference> {
        let s = self.lock()?;
        Ok(match db(s.storage.theme())? {
            storage::Theme::System => ThemePreference::System,
            storage::Theme::Light => ThemePreference::Light,
            storage::Theme::Dark => ThemePreference::Dark,
        })
    }
    pub fn save_theme(&self, theme: ThemePreference) -> Result<()> {
        let s = self.lock()?;
        db(s.storage.save_theme(theme.into()))
    }
    pub fn export_json(&self) -> Result<String> {
        let s = self.lock()?;
        db(s.storage.export_json())
    }
    pub fn inspect_json(&self, json: String) -> Result<BackupInfo> {
        let _s = self.lock()?;
        Ok(BackupInfo::from(&parse_backup(&json)?))
    }
    pub fn import_json(&self, json: String) -> Result<()> {
        let mut s = self.lock()?;
        let backup = parse_backup(&json)?;
        db(s.storage.replace_data(&backup))?;
        Ok(())
    }
    pub fn export_sealed(&self) -> Result<Vec<u8>> {
        let s = self.lock()?;
        db(s.storage.export_sealed(&s.key()?))
    }
    pub fn inspect_sealed(&self, bytes: Vec<u8>) -> Result<SealedBackupInfo> {
        let s = self.lock()?;
        inspect_sealed(&bytes, &s.key()?)
    }
    pub fn import_sealed(&self, bytes: Vec<u8>) -> Result<SealedBackupInfo> {
        let mut s = self.lock()?;
        let key = s.key()?;
        inspect_sealed(&bytes, &key)?;
        let info = db(s.storage.replace_from_sealed(&bytes, &key))?;
        Ok(info.into())
    }
    pub fn key_fingerprint(&self) -> Result<Option<String>> {
        self.lock()?.fingerprint()
    }
    pub fn export_recovery_code(&self) -> Result<String> {
        let s = self.lock()?;
        let key = s.key()?;
        Ok(format!(
            "# SnowFold 同步密钥恢复码\n# 密钥指纹：{}\n# 任何拿到这个文件的人都能解密你的加密备份；请离线保存，不要上传网盘或发给别人。\n# 换机或重装后，在「设置 · 加密备份 · 导入恢复码」中选择本文件即可。\n{}\n{}\n",
            key.fingerprint(),
            crypto::KEY_FILE_HEADER,
            key.to_hex()
        ))
    }
    pub fn inspect_recovery_code(&self, contents: String) -> Result<RecoveryCodeInfo> {
        let s = self.lock()?;
        let contents = zeroize::Zeroizing::new(contents);
        let key =
            crypto::SyncKey::decode_file(&contents).map_err(|_| CoreError::InvalidRecoveryCode)?;
        let current_fingerprint = s.fingerprint()?;
        Ok(RecoveryCodeInfo {
            current_fingerprint,
            new_fingerprint: key.fingerprint(),
        })
    }
    pub fn import_recovery_code(
        &self,
        contents: String,
        expected_current_fingerprint: Option<String>,
    ) -> Result<KeyImportResult> {
        let s = self.lock()?;
        let contents = zeroize::Zeroizing::new(contents);
        let key =
            crypto::SyncKey::decode_file(&contents).map_err(|_| CoreError::InvalidRecoveryCode)?;
        let current = s.fingerprint()?;
        if current != expected_current_fingerprint {
            return Err(CoreError::Conflict);
        }
        let previous_key_backed_up = crypto::SyncKey::replace_file(&s.key_path()?, &key)
            .map_err(|e| boundary_error(e, CoreError::KeyUnavailable))?;
        Ok(KeyImportResult {
            fingerprint: key.fingerprint(),
            previous_key_backed_up,
        })
    }
    pub fn clear_data(&self) -> Result<()> {
        let mut s = self.lock()?;
        db(s.storage.clear_data())?;
        Ok(())
    }
    pub fn startup_backup(&self) -> Result<()> {
        let s = self.lock()?;
        db(s.storage.startup_backup())
    }
}

#[uniffi::export]
pub fn parse_amount(value: String) -> Result<i64> {
    model::parse_money(&value).map_err(|_| CoreError::Validation)
}
#[uniffi::export]
pub fn format_amount(amount_cents: i64) -> Result<String> {
    amount(amount_cents)?;
    Ok(model::decimal(amount_cents))
}
#[uniffi::export]
pub fn format_money(amount_cents: i64, currency: String) -> Result<String> {
    if !["CNY", "USD"].contains(&currency.as_str()) {
        return Err(CoreError::Validation);
    }
    Ok(model::money(amount_cents, &currency))
}
