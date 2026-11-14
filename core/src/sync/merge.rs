//! 确定性逐记录合并；输入、输出都经过同一 Backup 校验。
use crate::{
    CoreError,
    backup::{Backup, BackupAsset, BackupCorrection, BackupHistory, BackupSubscription, Tombstone},
    model::Id,
};
use serde::Serialize;
use std::collections::{BTreeMap, HashSet};

type Result<T> = std::result::Result<T, CoreError>;
fn bytes<T: Serialize>(value: &T) -> Result<Vec<u8>> {
    serde_json::to_vec(value).map_err(|_| CoreError::Internal)
}
fn same<T: Serialize>(a: &T, b: &T) -> Result<bool> {
    Ok(bytes(a)? == bytes(b)?)
}

fn assets(a: &Backup, b: &Backup) -> Result<BTreeMap<Id, BackupAsset>> {
    let mut result: BTreeMap<Id, BackupAsset> = BTreeMap::new();
    for value in a.assets.iter().chain(&b.assets) {
        if let Some(old) = result.get(&value.id) {
            let rank =
                (&value.updated_at, value.revision_uid).cmp(&(&old.updated_at, old.revision_uid));
            if rank.is_eq() {
                let mut projected = value.clone();
                projected.amount_cents = old.amount_cents;
                if !same(&projected, old)? {
                    return Err(CoreError::SyncInvalidRemote);
                }
            }
            if !rank.is_gt() {
                continue;
            }
        }
        result.insert(value.id, value.clone());
    }
    Ok(result)
}
fn subscriptions(a: &Backup, b: &Backup) -> Result<BTreeMap<Id, BackupSubscription>> {
    let mut result: BTreeMap<Id, BackupSubscription> = BTreeMap::new();
    for value in a.subscriptions.iter().chain(&b.subscriptions) {
        if let Some(old) = result.get(&value.id) {
            let rank =
                (&value.updated_at, value.revision_uid).cmp(&(&old.updated_at, old.revision_uid));
            if rank.is_eq() && !same(value, old)? {
                return Err(CoreError::SyncInvalidRemote);
            }
            if !rank.is_gt() {
                continue;
            }
        }
        result.insert(value.id, value.clone());
    }
    Ok(result)
}

pub fn merge(a: &Backup, b: &Backup) -> Result<Backup> {
    a.validate().map_err(|_| CoreError::SyncInvalidRemote)?;
    b.validate().map_err(|_| CoreError::SyncInvalidRemote)?;
    let mut asset_map = assets(a, b)?;
    let mut subscription_map = subscriptions(a, b)?;
    let mut tombstones: BTreeMap<Id, Tombstone> = BTreeMap::new();
    for value in a.tombstones.iter().chain(&b.tombstones) {
        if let Some(old) = tombstones.get(&value.id) {
            if old.entity_type != value.entity_type {
                return Err(CoreError::SyncInvalidRemote);
            }
            if old.deleted_at >= value.deleted_at {
                continue;
            }
        }
        tombstones.insert(value.id, value.clone());
    }
    tombstones.retain(|id, t| {
        if t.entity_type == "asset" {
            if let Some(asset) = asset_map.get(id) {
                if asset.updated_at > t.deleted_at {
                    return false;
                }
                asset_map.remove(id);
            }
        } else if let Some(subscription) = subscription_map.get(id) {
            if subscription.updated_at > t.deleted_at {
                return false;
            }
            subscription_map.remove(id);
        }
        true
    });
    let mut history: BTreeMap<Id, BackupHistory> = BTreeMap::new();
    for value in a.all_history().chain(b.all_history()) {
        if !asset_map.contains_key(&value.asset_id) {
            continue;
        }
        if let Some(old) = history.get_mut(&value.id) {
            if (
                old.asset_id,
                old.previous_id,
                old.original_amount_cents,
                &old.recorded_at,
            ) != (
                value.asset_id,
                value.previous_id,
                value.original_amount_cents,
                &value.recorded_at,
            ) {
                return Err(CoreError::SyncInvalidRemote);
            }
            old.voided_at = old.voided_at.clone().max(value.voided_at.clone());
        } else {
            history.insert(value.id, value.clone());
        }
    }
    let mut corrections: BTreeMap<Id, BackupCorrection> = BTreeMap::new();
    for value in a.corrections.iter().chain(&b.corrections) {
        if !history.contains_key(&value.history_id) {
            continue;
        }
        if let Some(old) = corrections.get(&value.id) {
            if !same(value, old)? {
                return Err(CoreError::SyncInvalidRemote);
            }
        } else {
            corrections.insert(value.id, value.clone());
        }
    }
    let mut corrections: Vec<_> = corrections.into_values().collect();
    corrections.sort_by(|a, b| (&a.recorded_at, a.id).cmp(&(&b.recorded_at, b.id)));
    for value in history.values_mut() {
        value.amount_cents = value.original_amount_cents;
    }
    for correction in &corrections {
        history
            .get_mut(&correction.history_id)
            .ok_or(CoreError::SyncInvalidRemote)?
            .amount_cents = correction.amount_cents;
    }
    for asset in asset_map.values_mut() {
        let current = history
            .get_mut(&asset.current_history_id)
            .ok_or(CoreError::SyncInvalidRemote)?;
        current.voided_at = None;
        asset.amount_cents = current.amount_cents;
    }
    let (voided_history, active_history): (Vec<_>, Vec<_>) =
        history.into_values().partition(|h| h.voided_at.is_some());
    let merged = Backup {
        format_version: 1,
        ledger_id: a.ledger_id.min(b.ledger_id),
        assets: asset_map.into_values().collect(),
        history: active_history,
        voided_history,
        corrections,
        subscriptions: subscription_map.into_values().collect(),
        tombstones: tombstones.into_values().collect(),
    };
    merged
        .validate()
        .map_err(|_| CoreError::SyncInvalidRemote)?;
    Ok(merged)
}

pub fn count(data: &Backup) -> u64 {
    (data.assets.len()
        + data.history.len()
        + data.voided_history.len()
        + data.corrections.len()
        + data.subscriptions.len()
        + data.tombstones.len()) as u64
}
fn rows(data: &Backup) -> Result<BTreeMap<Id, Vec<u8>>> {
    let mut rows = BTreeMap::new();
    for v in &data.assets {
        rows.insert(v.id, bytes(v)?);
    }
    for v in data.all_history() {
        rows.insert(v.id, bytes(v)?);
    }
    for v in &data.corrections {
        rows.insert(v.id, bytes(v)?);
    }
    for v in &data.subscriptions {
        rows.insert(v.id, bytes(v)?);
    }
    for v in &data.tombstones {
        rows.insert(v.id, bytes(v)?);
    }
    Ok(rows)
}
pub fn changed(a: &Backup, b: &Backup) -> Result<u64> {
    let a = rows(a)?;
    let b = rows(b)?;
    let ids: HashSet<_> = a.keys().chain(b.keys()).collect();
    Ok(ids.into_iter().filter(|id| a.get(id) != b.get(id)).count() as u64)
}
pub fn equivalent(a: &Backup, b: &Backup) -> Result<bool> {
    Ok(a.ledger_id == b.ledger_id && changed(a, b)? == 0)
}
