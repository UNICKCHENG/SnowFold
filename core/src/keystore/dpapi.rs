//! Windows 当前账户 DPAPI 包裹。固定 entropy 用于应用域分隔，不是机密。
//! 不使用机器范围保护、不允许系统 UI；账户失陷不在此保护边界内。

use super::validate;
use crate::{CoreError, SecureKeyStore, crypto};
use std::{
    path::{Path, PathBuf},
    ptr,
};
use windows_sys::Win32::{
    Foundation::LocalFree,
    Security::Cryptography::{
        CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
    },
};
use zeroize::{Zeroize, Zeroizing};

const ENTROPY: &[u8] = b"app.snowfold/sync-key/dpapi/v1";
pub const DPAPI_KEY_FILE_NAME: &str = "snowfold-sync.key.dpapi";

pub struct DpapiKeyStore(PathBuf);

impl DpapiKeyStore {
    pub fn new(data_dir: &Path) -> Self {
        Self(data_dir.join(DPAPI_KEY_FILE_NAME))
    }
}

impl SecureKeyStore for DpapiKeyStore {
    fn load(&self) -> Result<Option<Vec<u8>>, CoreError> {
        let mut ciphertext = match std::fs::read(&self.0) {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                // 失效符号链接不能伪装成「从未存过」。
                return match std::fs::symlink_metadata(&self.0) {
                    Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
                    _ => Err(CoreError::KeyUnavailable),
                };
            }
            Err(_) => return Err(CoreError::KeyUnavailable),
        };
        let mut plaintext = transform(&mut ciphertext, false)?;
        validate(&plaintext)?;
        Ok(Some(std::mem::take(&mut *plaintext)))
    }

    fn store(&self, key: Vec<u8>) -> Result<(), CoreError> {
        let mut key = Zeroizing::new(key);
        validate(&key)?;
        let ciphertext = transform(&mut key, true)?;
        crypto::write_private_file(&self.0, &ciphertext).map_err(|_| CoreError::KeyUnavailable)
    }

    fn delete(&self) -> Result<(), CoreError> {
        match std::fs::remove_file(&self.0) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err(CoreError::KeyUnavailable),
        }
    }
}

/// DPAPI 返回的 LocalAlloc 缓冲必须清零再 LocalFree，包含所有提前返回路径。
struct LocalBlob(CRYPT_INTEGER_BLOB);
impl Drop for LocalBlob {
    fn drop(&mut self) {
        if !self.0.pbData.is_null() {
            // SAFETY: 指针和长度由 DPAPI 成功调用分配，缓冲归本对象独占。
            unsafe {
                std::slice::from_raw_parts_mut(self.0.pbData, self.0.cbData as usize).zeroize();
                LocalFree(self.0.pbData.cast());
            }
        }
    }
}

fn transform(bytes: &mut [u8], protect: bool) -> Result<Zeroizing<Vec<u8>>, CoreError> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes
            .len()
            .try_into()
            .map_err(|_| CoreError::KeyUnavailable)?,
        pbData: bytes.as_mut_ptr(),
    };
    let mut entropy = ENTROPY.to_vec();
    let entropy = CRYPT_INTEGER_BLOB {
        cbData: entropy.len() as u32,
        pbData: entropy.as_mut_ptr(),
    };
    let mut output = LocalBlob(CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: ptr::null_mut(),
    });
    // SAFETY: 输入与 entropy 在调用期间有效；可选指针为空；输出由 LocalBlob 释放。
    let succeeded = unsafe {
        if protect {
            CryptProtectData(
                &input,
                ptr::null(),
                &entropy,
                ptr::null(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output.0,
            )
        } else {
            CryptUnprotectData(
                &input,
                ptr::null_mut(),
                &entropy,
                ptr::null(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output.0,
            )
        }
    };
    if succeeded == 0 || output.0.pbData.is_null() || output.0.cbData == 0 {
        return Err(CoreError::KeyUnavailable);
    }
    // SAFETY: 成功返回的输出缓冲在 LocalBlob 析构前有效；复制品由 Zeroizing 管理。
    Ok(Zeroizing::new(unsafe {
        std::slice::from_raw_parts(output.0.pbData, output.0.cbData as usize).to_vec()
    }))
}

#[cfg(test)]
mod tests {
    use super::super::decode;
    use super::*;

    #[test]
    fn dpapi_roundtrip_migration_recovery_and_corruption() -> anyhow::Result<()> {
        let directory = tempfile::tempdir()?;
        let path = crypto::key_path_for(directory.path());
        let old = crypto::SyncKey::load_or_create(&path)?;
        let before = crypto::seal(b"old backup", &old, crypto::SNAPSHOT_AAD)?;
        let store = DpapiKeyStore::new(directory.path());
        let migrated = super::super::load_or_create(directory.path(), &store)?;
        assert_eq!(
            crypto::open(&before, &migrated, crypto::SNAPSHOT_AAD)?.as_slice(),
            b"old backup"
        );
        assert_eq!(migrated.to_hex(), old.to_hex());
        assert!(!path.exists());
        assert!(directory.path().join(DPAPI_KEY_FILE_NAME).is_file());
        assert_eq!(
            super::super::load_or_create(directory.path(), &store)?.to_hex(),
            old.to_hex()
        );
        let replacement = crypto::SyncKey::generate()?;
        super::super::store_verified(&store, &replacement)?;
        assert_eq!(
            decode(store.load()?.unwrap())?.to_hex(),
            replacement.to_hex()
        );
        assert!(!path.exists());
        assert!(!directory.path().join("snowfold-sync.key.bak").exists());
        std::fs::write(directory.path().join(DPAPI_KEY_FILE_NAME), b"corrupt")?;
        assert_eq!(store.load(), Err(CoreError::KeyUnavailable));
        store.delete()?;
        store.delete()?;
        assert_eq!(store.load()?, None);
        Ok(())
    }
}
