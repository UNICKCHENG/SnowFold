use snowfold_core::{
    CoreError, SecureKeyStore, SnowFoldCore,
    crypto::{self, SyncKey},
    keystore::MemoryKeyStore,
    storage::Storage,
};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, AtomicUsize, Ordering},
};

fn open(directory: &tempfile::TempDir, store: Arc<dyn SecureKeyStore>) -> Arc<SnowFoldCore> {
    SnowFoldCore::open(directory.path().to_string_lossy().into_owned(), store).unwrap()
}

fn legacy(directory: &tempfile::TempDir) -> anyhow::Result<SyncKey> {
    SyncKey::load_or_create(&crypto::key_path_for(directory.path()))
}

#[test]
fn migration_imports_bytes_and_json_exported_before_migration() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let old = legacy(&directory)?;
    // 先经旧 Storage/SyncKey 路径导出并保存字节，之后才构造新内核触发迁移。
    let mut source = Storage::open(&directory.path().join("snowfold.sqlite3"))?;
    source.replace_data(&Storage::example_data()?)?;
    let sealed_before_migration = source.export_sealed(&old)?;
    let json_before_migration = source.export_json()?;
    let snapshot = directory.path().join("before-migration.bfseal");
    std::fs::write(&snapshot, &sealed_before_migration)?;
    drop(source);
    let core = open(&directory, Arc::new(MemoryKeyStore::default()));
    core.export_recovery_code()?;
    assert!(!crypto::key_path_for(directory.path()).exists());
    core.clear_data()?;
    core.import_sealed(std::fs::read(snapshot)?)?;
    assert_eq!(core.export_json()?, json_before_migration);
    core.clear_data()?;
    core.import_json(json_before_migration.clone())?;
    assert_eq!(core.export_json()?, json_before_migration);
    Ok(())
}

#[test]
fn migration_preserves_recovery_code_character_for_character() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let old = legacy(&directory)?;
    let before = format!(
        "# SnowFold 同步密钥恢复码\n# 密钥指纹：{}\n# 任何拿到这个文件的人都能解密你的加密备份；请离线保存，不要上传网盘或发给别人。\n# 换机或重装后，在「设置 · 加密备份 · 导入恢复码」中选择本文件即可。\n{}\n{}\n",
        old.fingerprint(),
        crypto::KEY_FILE_HEADER,
        old.to_hex()
    );
    let core = open(&directory, Arc::new(MemoryKeyStore::default()));
    assert_eq!(core.export_recovery_code()?, before);
    assert_eq!(core.key_fingerprint()?, Some(old.fingerprint()));
    assert_eq!(SyncKey::decode_file(&before)?.to_hex(), old.to_hex());
    Ok(())
}

#[test]
fn migration_removes_old_plaintext_file_after_verified_readback() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let old = legacy(&directory)?;
    let store = Arc::new(MemoryKeyStore::default());
    let core = open(&directory, store.clone());
    #[cfg(unix)]
    let mut old_handle = std::fs::File::open(crypto::key_path_for(directory.path()))?;
    core.export_recovery_code()?;
    #[cfg(unix)]
    {
        use std::io::Read as _;
        let mut overwritten = Vec::new();
        old_handle.read_to_end(&mut overwritten)?;
        assert!(!overwritten.is_empty());
        assert!(overwritten.iter().all(|byte| *byte == 0));
    }
    assert_eq!(store.load()?, Some(hex::decode(old.to_hex())?));
    assert!(!crypto::key_path_for(directory.path()).exists());
    assert!(!directory.path().join("snowfold-sync.key.bak").exists());
    Ok(())
}

#[test]
fn migration_is_idempotent_across_two_starts() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    legacy(&directory)?;
    let store = Arc::new(MemoryKeyStore::default());
    let first = open(&directory, store.clone());
    let before = first.export_recovery_code()?;
    drop(first);
    let second = open(&directory, store);
    assert_eq!(second.export_recovery_code()?, before);
    assert!(!crypto::key_path_for(directory.path()).exists());
    Ok(())
}

#[derive(Default)]
struct FaultStore {
    memory: MemoryKeyStore,
    fail_load: AtomicBool,
    fail_store: AtomicBool,
    fail_readback: AtomicBool,
    writes: AtomicUsize,
}
impl SecureKeyStore for FaultStore {
    fn load(&self) -> Result<Option<Vec<u8>>, CoreError> {
        if self.fail_load.load(Ordering::SeqCst)
            || (self.fail_readback.load(Ordering::SeqCst) && self.writes.load(Ordering::SeqCst) > 0)
        {
            return Err(CoreError::Io);
        }
        self.memory.load()
    }
    fn store(&self, key: Vec<u8>) -> Result<(), CoreError> {
        self.writes.fetch_add(1, Ordering::SeqCst);
        if self.fail_store.load(Ordering::SeqCst) {
            let _key = zeroize::Zeroizing::new(key);
            return Err(CoreError::Io);
        }
        self.memory.store(key)
    }
    fn delete(&self) -> Result<(), CoreError> {
        self.memory.delete()
    }
}

#[test]
fn interrupted_migration_keeps_old_file_and_key_usable() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let old = legacy(&directory)?;
    let path = crypto::key_path_for(directory.path());
    let bytes = std::fs::read(&path)?;
    let old_sealed = crypto::seal(b"old backup", &old, crypto::SNAPSHOT_AAD)?;
    let store = Arc::new(FaultStore::default());
    store.fail_store.store(true, Ordering::SeqCst);
    let core = open(&directory, store.clone());
    assert_eq!(core.export_recovery_code(), Err(CoreError::KeyUnavailable));
    assert_eq!(std::fs::read(&path)?, bytes);
    assert_eq!(
        crypto::open(
            &old_sealed,
            &SyncKey::load(&path)?.unwrap(),
            crypto::SNAPSHOT_AAD
        )?
        .as_slice(),
        b"old backup"
    );
    store.fail_store.store(false, Ordering::SeqCst);
    assert_eq!(
        SyncKey::decode_file(&core.export_recovery_code()?)?.to_hex(),
        old.to_hex()
    );
    assert!(!path.exists());
    Ok(())
}

#[test]
fn store_load_error_never_generates_or_stores_a_new_key() -> anyhow::Result<()> {
    for with_legacy in [false, true] {
        let directory = tempfile::tempdir()?;
        if with_legacy {
            legacy(&directory)?;
        }
        let path = crypto::key_path_for(directory.path());
        let before = std::fs::read(&path).ok();
        let store = Arc::new(FaultStore::default());
        store.fail_load.store(true, Ordering::SeqCst);
        let core = open(&directory, store.clone());
        let error = core.export_recovery_code().unwrap_err();
        assert_eq!(error, CoreError::KeyUnavailable);
        assert_eq!(error.to_string(), "本机密钥已失效，请用恢复码恢复");
        assert_eq!(core.export_sealed(), Err(CoreError::KeyUnavailable));
        assert_eq!(store.writes.load(Ordering::SeqCst), 0);
        assert_eq!(std::fs::read(path).ok(), before);
    }
    Ok(())
}

struct ReadbackStore {
    response: Option<Vec<u8>>,
    written: AtomicBool,
}
impl SecureKeyStore for ReadbackStore {
    fn load(&self) -> Result<Option<Vec<u8>>, CoreError> {
        if self.written.load(Ordering::SeqCst) {
            Ok(self.response.clone())
        } else {
            Ok(None)
        }
    }
    fn store(&self, key: Vec<u8>) -> Result<(), CoreError> {
        let _key = zeroize::Zeroizing::new(key);
        self.written.store(true, Ordering::SeqCst);
        Ok(())
    }
    fn delete(&self) -> Result<(), CoreError> {
        Ok(())
    }
}

#[test]
fn missing_wrong_or_invalid_readback_never_deletes_legacy_file() -> anyhow::Result<()> {
    for response in [None, Some(vec![0; 32]), Some(vec![0; 31]), Some(vec![])] {
        let directory = tempfile::tempdir()?;
        let key = SyncKey::from_bytes([0x5a; 32]);
        let path = crypto::key_path_for(directory.path());
        key.write_to(&path)?;
        let before = std::fs::read(&path)?;
        let store = Arc::new(ReadbackStore {
            response,
            written: AtomicBool::new(false),
        });
        let core = open(&directory, store);
        assert_eq!(core.export_recovery_code(), Err(CoreError::KeyUnavailable));
        assert_eq!(std::fs::read(&path)?, before);
    }
    Ok(())
}

#[test]
fn invalid_loaded_lengths_are_errors_without_writing() -> anyhow::Result<()> {
    for length in [0, 31, 33] {
        let directory = tempfile::tempdir()?;
        let store = Arc::new(ReadbackStore {
            response: Some(vec![1; length]),
            written: AtomicBool::new(true),
        });
        let core = open(&directory, store);
        assert_eq!(core.export_recovery_code(), Err(CoreError::KeyUnavailable));
        assert!(!crypto::key_path_for(directory.path()).exists());
    }
    Ok(())
}

#[test]
fn new_keys_and_recovery_import_use_only_the_store() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let store = Arc::new(MemoryKeyStore::default());
    let core = open(&directory, store.clone());
    assert_eq!(core.key_fingerprint()?, None);
    assert_eq!(store.load()?, None);
    let first = core.export_recovery_code()?;
    assert_eq!(store.load()?.unwrap().len(), 32);
    assert_eq!(core.export_recovery_code()?, first);
    let replacement = SyncKey::from_bytes([0x2a; 32]);
    let info = core.inspect_recovery_code(replacement.encode_file())?;
    let result = core.import_recovery_code(replacement.encode_file(), info.current_fingerprint)?;
    assert!(!result.previous_key_backed_up);
    assert_eq!(store.load()?, Some(vec![0x2a; 32]));
    assert!(!crypto::key_path_for(directory.path()).exists());
    assert!(!directory.path().join("snowfold-sync.key.bak").exists());
    store.delete()?;
    store.delete()?;
    assert_eq!(store.load()?, None);
    Ok(())
}

#[test]
fn unreadable_store_can_be_explicitly_repaired_with_recovery_code() -> anyhow::Result<()> {
    struct RepairStore(Mutex<bool>, MemoryKeyStore);
    impl SecureKeyStore for RepairStore {
        fn load(&self) -> Result<Option<Vec<u8>>, CoreError> {
            if !*self.0.lock().unwrap() {
                return Err(CoreError::KeyUnavailable);
            }
            self.1.load()
        }
        fn store(&self, key: Vec<u8>) -> Result<(), CoreError> {
            self.1.store(key)?;
            *self.0.lock().unwrap() = true;
            Ok(())
        }
        fn delete(&self) -> Result<(), CoreError> {
            self.1.delete()
        }
    }
    let directory = tempfile::tempdir()?;
    let core = open(
        &directory,
        Arc::new(RepairStore(Mutex::new(false), MemoryKeyStore::default())),
    );
    assert_eq!(core.export_recovery_code(), Err(CoreError::KeyUnavailable));
    let key = SyncKey::from_bytes([0x3b; 32]);
    let info = core.inspect_recovery_code(key.encode_file())?;
    assert_eq!(info.current_fingerprint, None);
    core.import_recovery_code(key.encode_file(), None)?;
    assert_eq!(
        SyncKey::decode_file(&core.export_recovery_code()?)?.to_hex(),
        key.to_hex()
    );
    Ok(())
}

#[test]
fn readback_error_preserves_legacy_bytes() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    legacy(&directory)?;
    let path = crypto::key_path_for(directory.path());
    let before = std::fs::read(&path)?;
    let store = Arc::new(FaultStore::default());
    store.fail_readback.store(true, Ordering::SeqCst);
    let core = open(&directory, store.clone());
    assert_eq!(core.export_recovery_code(), Err(CoreError::KeyUnavailable));
    assert_eq!(store.writes.load(Ordering::SeqCst), 1);
    assert_eq!(std::fs::read(&path)?, before);
    Ok(())
}

#[test]
fn corrupt_empty_or_partial_legacy_files_are_never_replaced() -> anyhow::Result<()> {
    for contents in [
        "",
        "\n",
        "snowfold-sync-key-v1\n",
        "snowfold-sync-key-v1\n00\n",
        "corrupt",
    ] {
        let directory = tempfile::tempdir()?;
        let path = crypto::key_path_for(directory.path());
        std::fs::write(&path, contents)?;
        let store = Arc::new(FaultStore::default());
        let core = open(&directory, store.clone());
        assert_eq!(core.export_recovery_code(), Err(CoreError::KeyUnavailable));
        assert_eq!(std::fs::read_to_string(&path)?, contents);
        assert_eq!(store.writes.load(Ordering::SeqCst), 0);
    }
    Ok(())
}

#[test]
fn concurrent_calls_share_one_key_and_errors_release_the_core_lock() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let store = Arc::new(FaultStore::default());
    let core = open(&directory, store.clone());
    store.fail_load.store(true, Ordering::SeqCst);
    assert_eq!(core.export_recovery_code(), Err(CoreError::KeyUnavailable));
    store.fail_load.store(false, Ordering::SeqCst);
    let barrier = Arc::new(std::sync::Barrier::new(8));
    let threads: Vec<_> = (0..8)
        .map(|_| {
            let core = core.clone();
            let barrier = barrier.clone();
            std::thread::spawn(move || {
                barrier.wait();
                core.export_recovery_code().unwrap()
            })
        })
        .collect();
    let codes: std::collections::HashSet<_> = threads
        .into_iter()
        .map(|thread| thread.join().unwrap())
        .collect();
    assert_eq!(codes.len(), 1);
    assert_eq!(store.writes.load(Ordering::SeqCst), 1);
    core.create_asset("cash".into(), "锁已释放".into(), 0)?;
    assert_eq!(core.data()?.assets.len(), 1);
    Ok(())
}

#[test]
fn existing_store_cleans_matching_legacy_residue_on_every_key_use() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let key = legacy(&directory)?;
    let store = Arc::new(MemoryKeyStore::default());
    store.store(hex::decode(key.to_hex())?)?;
    let core = open(&directory, store.clone());
    for _ in 0..2 {
        key.write_to(&crypto::key_path_for(directory.path()))?;
        assert_eq!(
            SyncKey::decode_file(&core.export_recovery_code()?)?.to_hex(),
            key.to_hex()
        );
        assert!(!crypto::key_path_for(directory.path()).exists());
        assert_eq!(store.load()?, Some(hex::decode(key.to_hex())?));
    }
    Ok(())
}

#[test]
fn existing_store_preserves_and_reports_a_different_legacy_key() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let old = legacy(&directory)?;
    let sealed_before = crypto::seal(b"another key's backup", &old, crypto::SNAPSHOT_AAD)?;
    let path = crypto::key_path_for(directory.path());
    let before = std::fs::read(&path)?;
    let current = SyncKey::from_bytes([0x61; 32]);
    let store = Arc::new(MemoryKeyStore::default());
    store.store(hex::decode(current.to_hex())?)?;
    let core = open(&directory, store.clone());
    assert_eq!(core.export_recovery_code(), Err(CoreError::KeyUnavailable));
    assert_eq!(std::fs::read(&path)?, before);
    assert_eq!(store.load()?, Some(hex::decode(current.to_hex())?));
    assert_eq!(
        crypto::open(
            &sealed_before,
            &SyncKey::load(&path)?.unwrap(),
            crypto::SNAPSHOT_AAD
        )?
        .as_slice(),
        b"another key's backup"
    );
    let info = core.inspect_recovery_code(old.encode_file())?;
    assert_eq!(info.current_fingerprint, Some(current.fingerprint()));
    core.import_recovery_code(old.encode_file(), info.current_fingerprint)?;
    assert_eq!(
        SyncKey::decode_file(&core.export_recovery_code()?)?.to_hex(),
        old.to_hex()
    );
    assert!(!path.exists());
    Ok(())
}

#[test]
fn existing_store_preserves_and_reports_an_unparseable_legacy_file() -> anyhow::Result<()> {
    for contents in ["", "corrupt", "snowfold-sync-key-v1\n00\n"] {
        let directory = tempfile::tempdir()?;
        let path = crypto::key_path_for(directory.path());
        std::fs::write(&path, contents)?;
        let store = Arc::new(MemoryKeyStore::default());
        store.store(vec![0x62; 32])?;
        let core = open(&directory, store.clone());
        assert_eq!(core.export_recovery_code(), Err(CoreError::KeyUnavailable));
        assert_eq!(std::fs::read_to_string(&path)?, contents);
        assert_eq!(store.load()?, Some(vec![0x62; 32]));
        core.create_asset("cash".into(), "数据库仍可用".into(), 0)?;
        assert_eq!(core.data()?.assets.len(), 1);
    }
    Ok(())
}
