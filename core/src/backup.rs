//! Versioned wire data. Never serialize the UI read model as a backup.
use crate::model::{self, Id};
use anyhow::{Context, Result, bail};
use chrono::NaiveDate;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};

#[derive(Debug)]
pub enum BackupError {
    Incompatible,
    Invalid,
}
impl std::fmt::Display for BackupError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Incompatible => "不支持的备份版本",
            Self::Invalid => "备份内容不合法",
        })
    }
}
impl std::error::Error for BackupError {}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Backup {
    pub format_version: u32,
    pub ledger_id: Id,
    pub assets: Vec<BackupAsset>,
    pub history: Vec<BackupHistory>,
    pub voided_history: Vec<BackupHistory>,
    pub corrections: Vec<BackupCorrection>,
    pub subscriptions: Vec<BackupSubscription>,
    pub tombstones: Vec<Tombstone>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BackupAsset {
    pub id: Id,
    pub revision_uid: Id,
    pub current_history_id: Id,
    pub category_code: String,
    pub name: String,
    pub amount_cents: i64,
    pub created_at: String,
    pub updated_at: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BackupHistory {
    pub id: Id,
    pub asset_id: Id,
    pub previous_id: Option<Id>,
    pub original_amount_cents: i64,
    pub amount_cents: i64,
    pub recorded_at: String,
    pub voided_at: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BackupCorrection {
    pub id: Id,
    pub history_id: Id,
    pub previous_amount_cents: i64,
    pub amount_cents: i64,
    pub recorded_at: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BackupSubscription {
    pub id: Id,
    pub revision_uid: Id,
    pub name: String,
    pub amount_cents: i64,
    pub currency: String,
    pub cycle: String,
    #[serde(deserialize_with = "model::deserialize_date")]
    pub next_charge_on: NaiveDate,
    pub status: String,
    pub created_at: String,
    pub updated_at: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Tombstone {
    pub entity_type: String,
    pub id: Id,
    pub deleted_at: String,
}
impl Backup {
    pub fn empty(ledger_id: Id) -> Self {
        Self {
            format_version: 1,
            ledger_id,
            assets: vec![],
            history: vec![],
            voided_history: vec![],
            corrections: vec![],
            subscriptions: vec![],
            tombstones: vec![],
        }
    }
    /// Dispatch before decoding a version's fields; future versions get their own DTO.
    pub fn decode(json: &str) -> Result<Self> {
        let value: serde_json::Value =
            serde_json::from_str(json).map_err(|_| BackupError::Invalid)?;
        match value.get("format_version").and_then(|v| v.as_u64()) {
            Some(1) => {}
            Some(_) => return Err(BackupError::Incompatible.into()),
            None if value.get("schema_version").is_some() => {
                return Err(BackupError::Incompatible.into());
            }
            None => return Err(BackupError::Invalid.into()),
        }
        let backup: Self = serde_json::from_value(value).map_err(|_| BackupError::Invalid)?;
        backup.validate().map_err(|_| BackupError::Invalid)?;
        Ok(backup)
    }
    pub fn all_history(&self) -> impl Iterator<Item = &BackupHistory> {
        self.history.iter().chain(&self.voided_history)
    }
    pub fn validate(&self) -> Result<()> {
        if self.format_version != 1 {
            bail!(BackupError::Incompatible);
        }
        if self.history.iter().any(|h| h.voided_at.is_some())
            || self.voided_history.iter().any(|h| h.voided_at.is_none())
        {
            bail!("有效与作废快照分组不合法");
        }
        let mut identities = HashSet::new();
        identities.insert(self.ledger_id);
        let mut unique = |id: Id| -> Result<()> {
            if !identities.insert(id) {
                bail!("备份编号重复");
            }
            Ok(())
        };
        let assets: HashMap<_, _> = self.assets.iter().map(|a| (a.id, a)).collect();
        let history: HashMap<_, _> = self.all_history().map(|h| (h.id, h)).collect();
        for a in &self.assets {
            unique(a.id)?;
            unique(a.revision_uid)?;
            if a.name.trim().is_empty()
                || a.amount_cents < 0
                || !model::CATEGORIES.iter().any(|(c, _)| *c == a.category_code)
            {
                bail!("资产字段不合法");
            }
            model::validate_time(&a.created_at)?;
            model::validate_time(&a.updated_at)?;
            let current = history
                .get(&a.current_history_id)
                .context("当前快照不存在")?;
            if current.asset_id != a.id
                || current.voided_at.is_some()
                || current.amount_cents != a.amount_cents
            {
                bail!("当前余额与引用快照不一致");
            }
        }
        for h in self.all_history() {
            unique(h.id)?;
            if !assets.contains_key(&h.asset_id)
                || h.amount_cents < 0
                || h.original_amount_cents < 0
            {
                bail!("快照引用或金额不合法");
            }
            model::validate_time(&h.recorded_at)?;
            if let Some(at) = &h.voided_at {
                model::validate_time(at)?;
            }
            if let Some(id) = h.previous_id {
                let previous = history.get(&id).context("前驱快照不存在")?;
                if previous.asset_id != h.asset_id || previous.voided_at.is_some() {
                    bail!("前驱快照不合法");
                }
            }
        }
        // A single effective chain per asset. Voided branches remain available in backups.
        for a in &self.assets {
            let mut visited = HashSet::new();
            let mut next = Some(a.current_history_id);
            while let Some(id) = next {
                if !visited.insert(id) {
                    bail!("快照链成环");
                }
                next = history[&id].previous_id;
            }
            if self
                .all_history()
                .filter(|h| h.asset_id == a.id && h.voided_at.is_none())
                .any(|h| !visited.contains(&h.id))
            {
                bail!("有效快照不在当前链上");
            }
        }
        let mut effective: HashMap<_, _> = self
            .all_history()
            .map(|h| (h.id, h.original_amount_cents))
            .collect();
        for c in &self.corrections {
            unique(c.id)?;
            model::validate_time(&c.recorded_at)?;
            let amount = effective.get_mut(&c.history_id).context("修正引用不存在")?;
            if c.amount_cents < 0 || c.previous_amount_cents != *amount || c.amount_cents == *amount
            {
                bail!("修正链金额不一致");
            }
            *amount = c.amount_cents;
        }
        for h in self.all_history() {
            if effective[&h.id] != h.amount_cents {
                bail!("快照与修正链不一致");
            }
        }
        for s in &self.subscriptions {
            unique(s.id)?;
            unique(s.revision_uid)?;
            model::validate_time(&s.created_at)?;
            model::validate_time(&s.updated_at)?;
            let input = model::SubscriptionInput {
                name: s.name.clone(),
                amount_cents: s.amount_cents,
                currency: s.currency.clone(),
                cycle: s.cycle.clone(),
                next_charge_on: s.next_charge_on,
                status: s.status.clone(),
            };
            input.validate()?;
        }
        for t in &self.tombstones {
            unique(t.id)?;
            if !["asset", "subscription"].contains(&t.entity_type.as_str()) {
                bail!("墓碑类型不合法");
            }
            model::validate_time(&t.deleted_at)?;
        }
        Ok(())
    }
    pub fn into_ledger(self) -> model::Ledger {
        model::Ledger {
            assets: self
                .assets
                .into_iter()
                .map(|a| model::Asset {
                    id: a.id,
                    revision_uid: a.revision_uid,
                    current_history_id: a.current_history_id,
                    category_code: a.category_code,
                    name: a.name,
                    amount_cents: a.amount_cents,
                    created_at: a.created_at,
                    updated_at: a.updated_at,
                })
                .collect(),
            history: self
                .history
                .into_iter()
                .filter(|h| h.voided_at.is_none())
                .map(|h| model::History {
                    id: h.id,
                    asset_id: h.asset_id,
                    previous_id: h.previous_id,
                    original_amount_cents: h.original_amount_cents,
                    amount_cents: h.amount_cents,
                    recorded_at: h.recorded_at,
                })
                .collect(),
            subscriptions: self
                .subscriptions
                .into_iter()
                .map(|s| model::Subscription {
                    id: s.id,
                    revision_uid: s.revision_uid,
                    name: s.name,
                    amount_cents: s.amount_cents,
                    currency: s.currency,
                    cycle: s.cycle,
                    next_charge_on: s.next_charge_on,
                    status: s.status,
                    created_at: s.created_at,
                    updated_at: s.updated_at,
                })
                .collect(),
        }
    }
}
