//! 同步密钥存储与旧文件迁移。
//!
//! 安全边界：生产宿主把密钥交给系统安全存储，数据目录只留下平台包裹后的密文。
//! 系统账户失陷或进程内存被读取不在此边界内。FileKeyStore 只供测试与 Mac GPUI 开发。
//! 写入必须读回一致，迁移才可覆写删除旧文件；读取失败绝不按「没有密钥」处理。

use crate::{CoreError, SecureKeyStore, crypto, crypto::SyncKey};
use std::{io::Write as _, path::Path, sync::Mutex};
use zeroize::Zeroizing;

#[cfg(windows)]
mod dpapi;
#[cfg(windows)]
pub use dpapi::DpapiKeyStore;

/// 测试用内存存储，删除或替换时清零原密钥。
#[derive(Default)]
pub struct MemoryKeyStore(Mutex<Option<Zeroizing<Vec<u8>>>>);

impl SecureKeyStore for MemoryKeyStore {
    fn load(&self) -> Result<Option<Vec<u8>>, CoreError> {
        Ok(self
            .0
            .lock()
            .map_err(|_| CoreError::KeyUnavailable)?
            .as_ref()
            .map(|key| key.to_vec()))
    }

    fn store(&self, key: Vec<u8>) -> Result<(), CoreError> {
        let key = Zeroizing::new(key);
        validate(&key)?;
        *self.0.lock().map_err(|_| CoreError::KeyUnavailable)? = Some(key);
        Ok(())
    }

    fn delete(&self) -> Result<(), CoreError> {
        *self.0.lock().map_err(|_| CoreError::KeyUnavailable)? = None;
        Ok(())
    }
}

/// 保留旧明文文件与替换时的 .bak 行为；仅供测试与 Mac GPUI 本机开发。
/// 生产宿主不得使用此实现。
pub struct FileKeyStore(std::path::PathBuf);

impl FileKeyStore {
    pub fn new(data_dir: &Path) -> Self {
        Self(crypto::key_path_for(data_dir))
    }
}

impl SecureKeyStore for FileKeyStore {
    fn load(&self) -> Result<Option<Vec<u8>>, CoreError> {
        SyncKey::load(&self.0)
            .map(|key| key.map(|key| key.as_bytes().to_vec()))
            .map_err(|_| CoreError::KeyUnavailable)
    }

    fn store(&self, key: Vec<u8>) -> Result<(), CoreError> {
        let key = decode(key)?;
        SyncKey::replace_file(&self.0, &key).map_err(|_| CoreError::KeyUnavailable)?;
        Ok(())
    }

    fn delete(&self) -> Result<(), CoreError> {
        overwrite_and_remove(&self.0).map_err(|_| CoreError::KeyUnavailable)
    }
}

fn validate(bytes: &[u8]) -> Result<(), CoreError> {
    if bytes.len() != 32 {
        return Err(CoreError::KeyUnavailable);
    }
    Ok(())
}

fn decode(bytes: Vec<u8>) -> Result<SyncKey, CoreError> {
    let bytes = Zeroizing::new(bytes);
    validate(&bytes)?;
    let mut key = Zeroizing::new([0; 32]);
    key.copy_from_slice(&bytes);
    Ok(SyncKey::from_bytes(*key))
}

/// 只读取安全存储，不生成密钥；宿主错误统一成冻结的密钥失效文案。
pub fn load(store: &dyn SecureKeyStore) -> Result<Option<SyncKey>, CoreError> {
    store
        .load()
        .map_err(|_| CoreError::KeyUnavailable)?
        .map(decode)
        .transpose()
}

/// 写入并读回比较。None、错误、长度不合法或字节不一致都不能视为成功。
pub fn store_verified(store: &dyn SecureKeyStore, key: &SyncKey) -> Result<(), CoreError> {
    store
        .store(key.as_bytes().to_vec())
        .map_err(|_| CoreError::KeyUnavailable)?;
    let read_back = load(store)?.ok_or(CoreError::KeyUnavailable)?;
    if read_back.as_bytes() != key.as_bytes() {
        return Err(CoreError::KeyUnavailable);
    }
    Ok(())
}

/// 惰性取密钥：安全存储 → 旧文件迁移 → 生成，所有存储错误直接终止。
/// 每次取钥都核对旧文件，仅删除同一把密钥的副本；不一致或无法解析时保留并报错。
/// 清理失败只阻止本次密钥操作，不影响惰性打开数据库，下次取钥仍会重试。
pub fn load_or_create(data_dir: &Path, store: &dyn SecureKeyStore) -> Result<SyncKey, CoreError> {
    let path = crypto::key_path_for(data_dir);
    let key = match load(store)? {
        Some(key) => key,
        None => {
            let key = match SyncKey::load(&path).map_err(|_| CoreError::KeyUnavailable)? {
                Some(key) => key,
                None => SyncKey::generate().map_err(|_| CoreError::KeyUnavailable)?,
            };
            store_verified(store, &key)?;
            key
        }
    };
    // FileKeyStore 的后端就是这个明文文件；删除会丢掉它唯一的密钥副本。
    // 仅此内置测试/开发后端豁免，foreign 系统安全存储始终检查并清理残留。
    if store.type_id() == std::any::TypeId::of::<FileKeyStore>() {
        return Ok(key);
    }
    if let Some(legacy) = SyncKey::load(&path).map_err(|_| CoreError::KeyUnavailable)? {
        if legacy.as_bytes() != key.as_bytes() {
            return Err(CoreError::KeyUnavailable);
        }
        overwrite_and_remove(&path).map_err(|_| CoreError::Io)?;
    }
    Ok(key)
}

/// 恢复码用于修复失效存储；失效时没有可供核对的当前指纹，恢复仍需显式导入。
pub fn recovery_fingerprint(
    data_dir: &Path,
    store: &dyn SecureKeyStore,
) -> Result<Option<String>, CoreError> {
    match fingerprint(data_dir, store) {
        Err(CoreError::KeyUnavailable) => Ok(None),
        result => result,
    }
}

/// 读取已有指纹；首次使用且两处均无密钥时保持 None，不生成密钥。
pub fn fingerprint(
    data_dir: &Path,
    store: &dyn SecureKeyStore,
) -> Result<Option<String>, CoreError> {
    if let Some(key) = load(store)? {
        return Ok(Some(key.fingerprint()));
    }
    let path = crypto::key_path_for(data_dir);
    if SyncKey::load(&path)
        .map_err(|_| CoreError::KeyUnavailable)?
        .is_none()
    {
        return Ok(None);
    }
    load_or_create(data_dir, store).map(|key| Some(key.fingerprint()))
}

/// 尽力覆写、同步后删除旧文件。
/// SSD 的磨损均衡与写时复制文件系统可能保留旧数据块，此操作不保证物理擦除。
fn overwrite_and_remove(path: &Path) -> anyhow::Result<()> {
    let mut file = match std::fs::OpenOptions::new().write(true).open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    let mut remaining = file.metadata()?.len();
    let zeros = [0; 4096];
    while remaining > 0 {
        let count = remaining.min(zeros.len() as u64) as usize;
        file.write_all(&zeros[..count])?;
        remaining -= count as u64;
    }
    file.sync_all()?;
    drop(file);
    std::fs::remove_file(path)?;
    Ok(())
}
