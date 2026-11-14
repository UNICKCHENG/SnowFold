use super::state::{Confirm, SnowFold};
use crate::{
    crypto::{self, SyncKey},
    storage::Storage,
};
use anyhow::{Context as _, Result, bail};
use gpui::*;
use snowfold_core::{SecureKeyStore, backup::Backup, keystore};
use std::sync::Arc;

impl SnowFold {
    pub(super) fn confirm_action(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        let Some(action) = self.confirm.clone() else {
            return;
        };
        if matches!(action, Confirm::ClearSync) {
            self.clear_sync(cx);
            return;
        }
        if self.sync.busy {
            self.error = Some(super::sync::sync_error(snowfold_core::CoreError::Conflict).into());
            cx.notify();
            return;
        }
        let mut replaced_key: Option<(String, bool)> = None;
        let result = match action {
            Confirm::ClearSync => unreachable!(),
            Confirm::Clear => self
                .storage
                .as_mut()
                .context("数据库不可用")
                .and_then(|storage| storage.clear_data()),
            Confirm::Import(data) => self
                .storage
                .as_mut()
                .context("数据库不可用")
                .and_then(|storage| storage.replace_data(&data)),
            Confirm::ImportSealed { bytes, .. } => self.load_key().and_then(|key| {
                self.storage
                    .as_mut()
                    .context("数据库不可用")
                    .and_then(|storage| storage.replace_from_sealed(&bytes, &key))
                    .map(|_| ())
            }),
            Confirm::ReplaceKey {
                text,
                new_fingerprint,
                old_fingerprint,
            } => {
                let outcome = (|| -> Result<bool> {
                    let core = self.sync.core.as_ref().context("数据库不可用")?;
                    let result = core.import_recovery_code(text.to_string(), old_fingerprint)?;
                    Ok(result.previous_key_backed_up)
                })();
                match outcome {
                    Ok(backed_up) => {
                        replaced_key = Some((new_fingerprint, backed_up));
                        Ok(())
                    }
                    Err(error) => Err(error),
                }
            }
        };
        if result.is_ok() {
            self.confirm = None;
            self.undo = None;
            self.history = None;
            match replaced_key {
                Some((fingerprint, true)) => {
                    self.key_fingerprint = Some(fingerprint.clone());
                    self.notice = Some(format!(
                        "同步密钥已替换 · 指纹 {fingerprint}（旧密钥已备份为 .bak）"
                    ));
                }
                Some((fingerprint, false)) => {
                    self.key_fingerprint = Some(fingerprint.clone());
                    self.notice = Some(format!("同步密钥已导入 · 指纹 {fingerprint}"));
                }
                None => self.notice = Some("数据已更新".into()),
            }
        }
        self.refresh_sync();
        self.finish(result, window, cx);
    }
    pub(super) fn export(&mut self, cx: &mut gpui::Context<Self>) {
        let json = match self
            .storage
            .as_ref()
            .context("数据库不可用")
            .and_then(|s| s.export_json())
        {
            Ok(json) => json,
            Err(e) => {
                self.error = Some(format!("导出失败：{e:#}"));
                cx.notify();
                return;
            }
        };
        let directory = self
            .storage
            .as_ref()
            .and_then(|s| s.path.parent())
            .unwrap_or(std::path::Path::new("."));
        let protected = self.protected_targets();
        let receiver = cx.prompt_for_new_path(directory, Some("snowfold-backup.json"));
        cx.spawn(async move |this, cx| {
            let result = async {
                let Some(path) = receiver.await?? else {
                    return Ok(false);
                };
                if protected.iter().any(|item| item == &path) {
                    bail!("不能覆盖 SnowFold 自己的数据文件，请换一个保存位置");
                }
                std::fs::write(path, json)?;
                Ok::<_, anyhow::Error>(true)
            }
            .await;
            let _ = this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {
                        this.notice = Some("备份已导出".into());
                        this.error = None;
                    }
                    Ok(false) => {}
                    Err(e) => this.error = Some(format!("导出失败：{e:#}")),
                }
                cx.notify();
            });
        })
        .detach();
    }
    pub(super) fn import(&mut self, cx: &mut gpui::Context<Self>) {
        let receiver = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some("选择 SnowFold JSON 备份".into()),
        });
        cx.spawn(async move |this, cx| {
            let result = async {
                let Some(paths) = receiver.await?? else {
                    return Ok(None);
                };
                let path = paths.first().context("没有选择文件")?;
                let text = std::fs::read_to_string(path)?;
                let data = Backup::decode(&text)?;
                Ok::<_, anyhow::Error>(Some(data))
            }
            .await;
            let _ = this.update(cx, |this, cx| {
                match result {
                    Ok(Some(data)) => {
                        this.confirm = Some(Confirm::Import(data));
                        this.error = None;
                    }
                    Ok(None) => {}
                    Err(e) => this.error = Some(format!("导入失败，原数据未改变：{e:#}")),
                }
                cx.notify();
            });
        })
        .detach();
    }
    /// 数据目录：数据库与平台包裹后的密钥密文。
    pub(super) fn data_directory(&self) -> Result<std::path::PathBuf> {
        self.storage
            .as_ref()
            .and_then(|storage| storage.data_dir())
            .map(|directory| directory.to_path_buf())
            .context("数据库不可用")
    }
    pub(super) fn key_store(&self) -> Result<Arc<dyn SecureKeyStore>> {
        let directory = self.data_directory()?;
        #[cfg(all(windows, not(test)))]
        {
            Ok(Arc::new(keystore::DpapiKeyStore::new(&directory)))
        }
        #[cfg(any(test, target_os = "macos"))]
        {
            // Mac GPUI 仅供本机开发，发布的 Mac 应用由 SwiftUI + Keychain 提供。
            // 测试保留旧文件行为，既有交互断言无需依赖操作系统账户。
            Ok(Arc::new(keystore::FileKeyStore::new(&directory)))
        }
    }
    /// 取密钥与迁移由内核统一执行，Windows 不写明文密钥。
    pub(super) fn load_key(&self) -> Result<SyncKey> {
        let directory = self.data_directory()?;
        Ok(keystore::load_or_create(
            &directory,
            self.key_store()?.as_ref(),
        )?)
    }
    /// 不允许导出覆盖的文件：数据库、同步密钥及其备份。
    fn protected_targets(&self) -> Vec<std::path::PathBuf> {
        let Some(storage) = self.storage.as_ref() else {
            return Vec::new();
        };
        let mut paths = vec![storage.path.clone()];
        if let Some(directory) = storage.data_dir() {
            let key = crypto::key_path_for(directory);
            paths.push(key.clone());
            paths.push(key.with_file_name(format!("{}.bak", crypto::KEY_FILE_NAME)));
            paths.push(directory.join("snowfold-sync.key.dpapi"));
        }
        paths
    }
    /// 导出加密备份：服务器/网盘只能看到密文。
    pub(super) fn export_sealed(&mut self, cx: &mut gpui::Context<Self>) {
        let prepared = self.load_key().and_then(|key| {
            let sealed = self
                .storage
                .as_ref()
                .context("数据库不可用")?
                .export_sealed(&key)?;
            Ok((sealed, key.fingerprint()))
        });
        let (sealed, fingerprint) = match prepared {
            Ok(value) => value,
            Err(e) => {
                self.error = Some(e.to_string());
                cx.notify();
                return;
            }
        };
        let directory = self
            .data_directory()
            .unwrap_or_else(|_| std::path::PathBuf::from("."));
        let protected = self.protected_targets();
        let receiver = cx.prompt_for_new_path(&directory, Some("snowfold-backup.sfseal"));
        cx.spawn(async move |this, cx| {
            let result = async {
                let Some(path) = receiver.await?? else {
                    return Ok(false);
                };
                if protected.iter().any(|item| item == &path) {
                    bail!("不能覆盖 SnowFold 自己的数据文件，请换一个保存位置");
                }
                std::fs::write(path, sealed)?;
                Ok::<_, anyhow::Error>(true)
            }
            .await;
            let _ = this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {
                        this.notice = Some(format!("加密备份已导出 · 密钥指纹 {fingerprint}"));
                        this.error = None;
                    }
                    Ok(false) => {}
                    Err(e) => this.error = Some(format!("加密备份导出失败：{e:#}")),
                }
                cx.notify();
            });
        })
        .detach();
    }
    /// 导入加密备份：先认证解密与校验，拿到用户确认后才覆盖数据。
    pub(super) fn import_sealed(&mut self, cx: &mut gpui::Context<Self>) {
        let key = match self.load_key() {
            Ok(key) => key,
            Err(e) => {
                self.error = Some(e.to_string());
                cx.notify();
                return;
            }
        };
        let receiver = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some("选择 SnowFold 加密备份".into()),
        });
        cx.spawn(async move |this, cx| {
            let result = async {
                let Some(paths) = receiver.await?? else {
                    return Ok(None);
                };
                let path = paths.first().context("没有选择文件")?;
                let sealed = std::fs::read(path)?;
                let info = Storage::inspect_sealed(&sealed, &key)?;
                Ok::<_, anyhow::Error>(Some((sealed, info)))
            }
            .await;
            let _ = this.update(cx, |this, cx| {
                match result {
                    Ok(Some((bytes, info))) => {
                        this.confirm = Some(Confirm::ImportSealed { bytes, info });
                        this.error = None;
                    }
                    Ok(None) => {}
                    Err(e) => this.error = Some(format!("加密备份导入失败，原数据未改变：{e:#}")),
                }
                cx.notify();
            });
        })
        .detach();
    }
    /// 导出同步密钥恢复码，换机或重装后用它恢复加密备份。
    pub(super) fn export_recovery_code(&mut self, cx: &mut gpui::Context<Self>) {
        let prepared = (|| -> Result<_> {
            let core = self.sync.core.as_ref().context("数据库不可用")?;
            let contents = zeroize::Zeroizing::new(core.export_recovery_code()?);
            let fingerprint = core.key_fingerprint()?.context("同步密钥不可用")?;
            Ok((contents, fingerprint))
        })();
        let (contents, fingerprint) = match prepared {
            Ok(value) => value,
            Err(e) => {
                self.error = Some(e.to_string());
                cx.notify();
                return;
            }
        };
        let directory = self
            .data_directory()
            .unwrap_or_else(|_| std::path::PathBuf::from("."));
        let protected = self.protected_targets();
        let receiver = cx.prompt_for_new_path(&directory, Some("snowfold-recovery-code.txt"));
        cx.spawn(async move |this, cx| {
            let result = async {
                let Some(path) = receiver.await?? else {
                    return Ok(false);
                };
                if protected.iter().any(|item| item == &path) {
                    bail!("不能覆盖 SnowFold 自己的数据文件，请换一个保存位置");
                }
                // 恢复码是主密钥明文：必须 0600 写入，且不跟随目标符号链接。
                crypto::write_private_file(&path, contents.as_bytes())?;
                Ok::<_, anyhow::Error>(true)
            }
            .await;
            let _ = this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {
                        this.notice = Some(format!("恢复码已导出 · 密钥指纹 {fingerprint}"));
                        this.key_fingerprint = Some(fingerprint.clone());
                        this.refresh_sync();
                        this.error = None;
                    }
                    Ok(false) => {}
                    Err(e) => this.error = Some(format!("恢复码导出失败：{e:#}")),
                }
                cx.notify();
            });
        })
        .detach();
    }
    /// 导入同步密钥恢复码：先校验并展示新旧指纹，用户确认后才替换本机密钥。
    pub(super) fn import_recovery_code(&mut self, cx: &mut gpui::Context<Self>) {
        let old_fingerprint = match self.data_directory().and_then(|directory| {
            Ok(keystore::recovery_fingerprint(
                &directory,
                self.key_store()?.as_ref(),
            )?)
        }) {
            Ok(fingerprint) => fingerprint,
            Err(e) => {
                self.error = Some(format!("恢复码导入失败：{e:#}"));
                cx.notify();
                return;
            }
        };
        let receiver = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some("选择 SnowFold 恢复码文件".into()),
        });
        cx.spawn(async move |this, cx| {
            let result = async {
                let Some(paths) = receiver.await?? else {
                    return Ok(None);
                };
                let path = paths.first().context("没有选择文件")?;
                let text = std::fs::read_to_string(path)?;
                let key = SyncKey::decode_file(&text)?;
                Ok::<_, anyhow::Error>(Some((text, key.fingerprint())))
            }
            .await;
            let _ = this.update(cx, |this, cx| {
                match result {
                    Ok(Some((text, new_fingerprint))) => {
                        this.confirm = Some(Confirm::ReplaceKey {
                            text: zeroize::Zeroizing::new(text),
                            new_fingerprint,
                            old_fingerprint: old_fingerprint.clone(),
                        });
                        this.error = None;
                    }
                    Ok(None) => {}
                    Err(e) => this.error = Some(format!("恢复码导入失败，本机密钥未改变：{e:#}")),
                }
                cx.notify();
            });
        })
        .detach();
    }
}
