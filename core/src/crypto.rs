//! 加密同步与加密备份。
//!
//! 安全边界（这里只声称这一条）：
//! - 离开本机的数据（加密备份、以后同步到服务器的快照）在传输和云端存储中
//!   不可读、不可篡改；
//! - 它**不**保护本机文件系统被攻破的情况：密钥文件就放在数据目录里，能读到
//!   数据库的人也能读到密钥。本机静态保护交给 FileVault / BitLocker。
//!
//! 设计要点：
//! - AES-256-GCM；96 位随机 nonce，每次加密都从操作系统 CSPRNG 重新生成；
//! - 信封头（magic/format/algorithm）参与 AAD 认证，格式降级或改标都会解密失败；
//! - 调用方传入用途 AAD（见 [`SNAPSHOT_AAD`]）做域分隔，别的用途的密文不能被
//!   当成快照导入；
//! - 密钥为 32 字节随机值，进程内用 `Zeroizing` 包裹，离开作用域自动清零；
//! - 密钥文件原子写入，Unix 权限 0600，读取时发现权限过宽会自动收紧。
//!
//! 密文信封布局（大端无关，全部为字节串）：
//!
//! ```text
//! | 0..4  magic "SFS1" | 4 format=0x01 | 5 alg=0x01 | 6..18 nonce | 18.. ciphertext || tag(16) |
//! ```
//!
//! AAD = 信封头 0..6 字节 || 调用方 AAD。

use aes_gcm::{
    Aes256Gcm, Nonce,
    aead::{Aead, AeadCore, KeyInit, Payload},
};
use anyhow::{Context, Result, anyhow, bail};
use sha2::{Digest, Sha256};
use std::fmt;
use std::io::Write as _;
use std::path::{Path, PathBuf};
use zeroize::Zeroizing;

const MAGIC: [u8; 4] = *b"SFS1";
const FORMAT: u8 = 1;
const ALG_AES_256_GCM: u8 = 1;
const HEADER_LEN: usize = 6;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;
const BODY_OFFSET: usize = HEADER_LEN + NONCE_LEN;

type NonceSize = <Aes256Gcm as AeadCore>::NonceSize;

/// 原生 JSON 快照的用途域分隔。以后新增同步、附件等密文时必须使用不同的常量，
/// 避免密文被跨用途替换。
pub const SNAPSHOT_AAD: &[u8] = b"snowfold/snapshot/json/v1";

pub const KEY_FILE_HEADER: &str = "snowfold-sync-key-v1";
/// 同步密钥文件名，与数据库放在同一个数据目录。
pub const KEY_FILE_NAME: &str = "snowfold-sync.key";

/// 解密失败的分类。对外只暴露「认证失败」，不区分密钥错误与数据损坏，
/// 避免给攻击者提供判定依据。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CryptoError {
    /// 长度不足，连信封头或认证标签都不完整。
    Truncated,
    /// 不是 SnowFold 密封包。
    BadMagic,
    /// 未知的信封格式版本。
    UnsupportedFormat(u8),
    /// 未知的加密算法。
    UnsupportedAlgorithm(u8),
    /// 认证失败：密钥不匹配、密文被篡改、AAD 不匹配或数据损坏。
    Authentication,
}

impl fmt::Display for CryptoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Truncated => f.write_str("密封包不完整"),
            Self::BadMagic => f.write_str("不是 SnowFold 加密备份"),
            Self::UnsupportedFormat(version) => {
                write!(f, "不支持的加密备份格式版本 {version}")
            }
            Self::UnsupportedAlgorithm(algorithm) => {
                write!(f, "不支持的加密算法编号 {algorithm}")
            }
            Self::Authentication => f.write_str("无法解密：同步密钥不匹配或文件已损坏"),
        }
    }
}

impl std::error::Error for CryptoError {}

/// 32 字节同步密钥。明文只在内存里保留，离开作用域时清零。
pub struct SyncKey(Zeroizing<[u8; 32]>);

impl SyncKey {
    /// 从操作系统 CSPRNG 生成新密钥。随机源不可用是致命错误，向调用方返回。
    pub fn generate() -> Result<Self> {
        let mut bytes = Zeroizing::new([0u8; 32]);
        getrandom::fill(&mut bytes[..]).map_err(|error| anyhow!("无法获取安全随机数: {error}"))?;
        Ok(Self(bytes))
    }

    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(Zeroizing::new(bytes))
    }

    fn secret(&self) -> &[u8; 32] {
        &self.0
    }

    /// 64 位小写十六进制，用于恢复码文件与跨设备配对。
    pub fn to_hex(&self) -> String {
        hex::encode(self.secret().as_slice())
    }

    pub fn from_hex(text: &str) -> Result<Self> {
        let text = text.trim();
        if text.len() != 64 {
            return Err(anyhow!("恢复码必须是 64 位十六进制字符"));
        }
        let decoded = Zeroizing::new(hex::decode(text).context("恢复码包含非十六进制字符")?);
        if decoded.len() != 32 {
            return Err(anyhow!("恢复码长度不正确"));
        }
        let mut bytes = [0u8; 32];
        bytes.copy_from_slice(decoded.as_slice());
        Ok(Self::from_bytes(bytes))
    }

    /// 密钥指纹（例如 `A1B2-C3D4-E5F6-7890`）。可以安全展示，
    /// 用来核对两台设备或两份备份是否使用同一把密钥。
    pub fn fingerprint(&self) -> String {
        let mut hasher = Sha256::new();
        hasher.update(b"snowfold/key-fingerprint/v1");
        hasher.update(self.secret().as_slice());
        let digest = hasher.finalize();
        let hexed = hex::encode_upper(&digest[..8]);
        char_chunks(&hexed, 4).join("-")
    }

    /// 密钥文件内容：一行版本标记，一行十六进制密钥。
    pub fn encode_file(&self) -> String {
        format!("{KEY_FILE_HEADER}\n{}\n", self.to_hex())
    }

    pub fn decode_file(text: &str) -> Result<Self> {
        // 允许 UTF-8 BOM（Windows 记事本另存后的常见情况）。
        let text = text.strip_prefix('\u{feff}').unwrap_or(text);
        // 允许以 `#` 开头的说明行，方便同一个文件既能当恢复码又能直接当密钥文件。
        let mut comments = Vec::new();
        let mut lines = Vec::new();
        for line in text.lines().map(str::trim) {
            if line.is_empty() {
                continue;
            }
            if let Some(comment) = line.strip_prefix('#') {
                comments.push(comment.trim().to_owned());
            } else {
                lines.push(line);
            }
        }
        let header = lines.first().context("恢复码文件为空")?;
        if *header != KEY_FILE_HEADER {
            return Err(anyhow!("不是 SnowFold 同步密钥文件"));
        }
        let body = lines.get(1).context("恢复码文件缺少密钥")?;
        if lines.len() > 2 {
            return Err(anyhow!("恢复码文件包含多余内容"));
        }
        let key = Self::from_hex(body)?;
        // 恢复码文件里的指纹注释必须与实际密钥一致，防止手改 hex 后导入错误密钥。
        for comment in comments {
            if let Some(value) = comment.split_once("密钥指纹：").map(|(_, v)| v.trim()) {
                let normalized: String = value
                    .chars()
                    .filter(|c| c.is_ascii_hexdigit())
                    .collect::<String>()
                    .to_ascii_uppercase();
                let expected: String = key.fingerprint().replace('-', "");
                if normalized != expected {
                    return Err(anyhow!("恢复码注释里的指纹与密钥不一致（文件可能被改过）"));
                }
            }
        }
        Ok(key)
    }

    /// 读取已有密钥；文件不存在返回 `None`。权限过宽会收紧到 0600。
    pub fn load(path: &Path) -> Result<Option<Self>> {
        match std::fs::read_to_string(path) {
            Ok(text) => {
                let key = Self::decode_file(&text)
                    .with_context(|| format!("无法解析同步密钥 {}", path.display()))?;
                enforce_private_permissions(path)?;
                Ok(Some(key))
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                match std::fs::symlink_metadata(path) {
                    Ok(metadata) if metadata.file_type().is_symlink() => {
                        bail!("同步密钥路径是失效的符号链接: {}", path.display())
                    }
                    _ => Ok(None),
                }
            }
            Err(error) => {
                Err(error).with_context(|| format!("无法读取同步密钥 {}", path.display()))
            }
        }
    }

    /// 读取已有密钥；文件不存在时生成一把并写入数据目录。
    ///
    /// 创建过程先在临时文件里写完整内容并 `fsync`，再用 `hard_link` 原子地
    /// 发布到最终路径（不覆盖已有文件）。这样：
    /// - 崩溃或写入失败只会留下临时文件，不会出现 0 字节的半截密钥；
    /// - 两个实例同时首启时只有一个能发布成功，另一个读取胜出者的密钥。
    pub fn load_or_create(path: &Path) -> Result<Self> {
        if let Some(key) = Self::load(path)? {
            return Ok(key);
        }
        let candidate = Self::generate()?;
        let contents = Zeroizing::new(candidate.encode_file());
        if publish_exclusive(path, contents.as_bytes())? {
            enforce_private_permissions(path)?;
            return Ok(candidate);
        }
        // 另一个实例已经发布：等它把内容写完整。
        for _ in 0..100 {
            if let Ok(text) = std::fs::read_to_string(path) {
                if let Ok(key) = Self::decode_file(&text) {
                    enforce_private_permissions(path)?;
                    return Ok(key);
                }
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        // 文件存在却始终解析不了：给出具体诊断，而不是含糊的“稍后重试”。
        Self::load(path)?;
        bail!("同步密钥文件已存在但无法解析，请从恢复码重建或删除后重试")
    }

    /// 原子写入密钥文件（同目录临时文件 + rename），Unix 下权限 0600。
    pub fn write_to(&self, path: &Path) -> Result<()> {
        let contents = Zeroizing::new(self.encode_file());
        write_private_file(path, contents.as_bytes())
    }

    /// 用新密钥替换现有密钥文件，并把旧密钥保留为 `<文件名>.bak`。
    /// 返回是否真的做了备份（密钥文件原本不存在时为 `false`）。
    pub fn replace_file(path: &Path, key: &SyncKey) -> Result<bool> {
        let backed_up = match std::fs::read(path) {
            Ok(existing) => {
                let existing = Zeroizing::new(existing);
                write_private_file(&backup_path(path)?, existing.as_slice())?;
                true
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
            Err(error) => {
                return Err(error).with_context(|| format!("无法读取旧密钥 {}", path.display()));
            }
        };
        let contents = Zeroizing::new(key.encode_file());
        write_private_file(path, contents.as_bytes())?;
        Ok(backed_up)
    }
}

impl fmt::Debug for SyncKey {
    /// 不打印密钥内容，避免日志或 panic 信息泄露。
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SyncKey")
            .field("fingerprint", &self.fingerprint())
            .finish()
    }
}

/// 把明文封装成可安全外传的密文信封。
pub fn seal(plaintext: &[u8], key: &SyncKey, aad: &[u8]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key.secret().as_slice())
        .map_err(|_| anyhow!("同步密钥长度无效"))?;
    let mut nonce_bytes = [0u8; NONCE_LEN];
    getrandom::fill(&mut nonce_bytes).map_err(|error| anyhow!("无法获取安全随机数: {error}"))?;
    let nonce = Nonce::<NonceSize>::from(nonce_bytes);
    let header = envelope_header(&nonce);
    // 只把固定的 6 字节信封头放进 AAD：nonce 与密文本身由 GCM 的认证标签保护。
    // 加密与解密必须使用完全相同的 AAD 计算方式。
    let authenticated = authentication_data(&header[..HEADER_LEN], aad);
    let body = cipher
        .encrypt(
            &nonce,
            Payload {
                msg: plaintext,
                aad: &authenticated,
            },
        )
        .map_err(|_| anyhow!("加密失败"))?;
    let mut sealed = Vec::with_capacity(header.len() + body.len());
    sealed.extend_from_slice(&header);
    sealed.extend_from_slice(&body);
    Ok(sealed)
}

/// 解密密文信封。返回的明文缓冲离开作用域时清零。
pub fn open(sealed: &[u8], key: &SyncKey, aad: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
    if sealed.len() < BODY_OFFSET + TAG_LEN {
        return Err(CryptoError::Truncated.into());
    }
    if sealed[..4] != MAGIC {
        return Err(CryptoError::BadMagic.into());
    }
    if sealed[4] != FORMAT {
        return Err(CryptoError::UnsupportedFormat(sealed[4]).into());
    }
    if sealed[5] != ALG_AES_256_GCM {
        return Err(CryptoError::UnsupportedAlgorithm(sealed[5]).into());
    }
    let nonce: &Nonce<NonceSize> = sealed[HEADER_LEN..BODY_OFFSET]
        .try_into()
        .map_err(|_| CryptoError::Truncated)?;
    let authenticated = authentication_data(&sealed[..HEADER_LEN], aad);
    let cipher = Aes256Gcm::new_from_slice(key.secret().as_slice())
        .map_err(|_| anyhow!("同步密钥长度无效"))?;
    let plaintext = cipher
        .decrypt(
            nonce,
            Payload {
                msg: &sealed[BODY_OFFSET..],
                aad: &authenticated,
            },
        )
        .map_err(|_| CryptoError::Authentication)?;
    Ok(Zeroizing::new(plaintext))
}

fn envelope_header(nonce: &Nonce<NonceSize>) -> Vec<u8> {
    let mut header = Vec::with_capacity(BODY_OFFSET);
    header.extend_from_slice(&MAGIC);
    header.push(FORMAT);
    header.push(ALG_AES_256_GCM);
    header.extend_from_slice(nonce.as_slice());
    debug_assert_eq!(header.len(), BODY_OFFSET);
    header
}

fn authentication_data(header: &[u8], aad: &[u8]) -> Vec<u8> {
    let mut authenticated = Vec::with_capacity(header.len() + aad.len());
    authenticated.extend_from_slice(header);
    authenticated.extend_from_slice(aad);
    authenticated
}

fn char_chunks(text: &str, size: usize) -> Vec<&str> {
    text.as_bytes()
        .chunks(size)
        .map(|chunk| std::str::from_utf8(chunk).expect("hex 分组只包含 ASCII"))
        .collect()
}

#[cfg(unix)]
fn open_private(path: &Path) -> std::io::Result<std::fs::File> {
    use std::os::unix::fs::OpenOptionsExt;
    std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
}

#[cfg(not(unix))]
fn open_private(path: &Path) -> std::io::Result<std::fs::File> {
    // Windows 下没有 POSIX 权限位；数据目录位于用户配置目录，默认 ACL 只允许
    // 当前用户与管理员访问。加密备份本身不依赖这个文件权限。
    std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
}

/// Unix 下把已经存在的密钥文件权限收紧到 0600；其他平台为空操作。
#[cfg(unix)]
fn enforce_private_permissions(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mode = std::fs::metadata(path)?.permissions().mode() & 0o777;
    if mode & 0o077 != 0 {
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
            .with_context(|| format!("无法把同步密钥权限收紧为 0600: {}", path.display()))?;
    }
    Ok(())
}

#[cfg(not(unix))]
fn enforce_private_permissions(_path: &Path) -> Result<()> {
    Ok(())
}

/// 以 0600 权限原子写入敏感文件：同目录临时文件 + `rename`。
///
/// `rename` 替换的是目标位置的符号链接本身，不会顺着链接写到别处，因此即使
/// 目标路径被预先换成符号链接，也不会把密钥写进攻击者指定的文件。
pub fn write_private_file(path: &Path, contents: &[u8]) -> Result<()> {
    let temp = write_temp_private(path, contents)?;
    let result = (|| -> Result<()> {
        std::fs::rename(&temp, path)?;
        sync_parent_directory(path);
        Ok(())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&temp);
    }
    result
}

/// 写一个内容完整、权限 0600 的临时文件并返回其路径；调用方负责发布或清理。
fn write_temp_private(path: &Path, contents: &[u8]) -> Result<PathBuf> {
    let directory = path.parent().context("文件路径没有父目录")?;
    std::fs::create_dir_all(directory)
        .with_context(|| format!("无法创建目录 {}", directory.display()))?;
    let file_name = path
        .file_name()
        .context("文件路径缺少文件名")?
        .to_string_lossy()
        .into_owned();
    // 随机后缀避免同进程并发写同一路径时互相踩到临时文件。
    let temp = directory.join(format!(
        ".{file_name}.{}.{}.tmp",
        std::process::id(),
        random_hex(6)?
    ));
    let result = (|| -> Result<()> {
        let mut file = open_private(&temp)?;
        file.write_all(contents)?;
        file.sync_all()?;
        Ok(())
    })();
    match result {
        Ok(()) => Ok(temp),
        Err(error) => {
            let _ = std::fs::remove_file(&temp);
            Err(error)
        }
    }
}

/// 不覆盖地发布文件：临时文件写完整后 `hard_link` 到目标路径。
/// 返回 `true` 表示发布成功，`false` 表示目标已存在（另一个实例先到）。
fn publish_exclusive(path: &Path, contents: &[u8]) -> Result<bool> {
    let temp = write_temp_private(path, contents)?;
    let published = match std::fs::hard_link(&temp, path) {
        Ok(()) => Ok(true),
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => Ok(false),
        Err(error) => Err(error).with_context(|| format!("无法创建同步密钥 {}", path.display())),
    };
    let _ = std::fs::remove_file(&temp);
    if let Ok(true) = published {
        sync_parent_directory(path);
    }
    published
}

/// 尽力让目录项的创建/替换落盘；Windows 上打开目录会失败，忽略即可。
fn sync_parent_directory(path: &Path) {
    let Some(directory) = path.parent() else {
        return;
    };
    #[cfg(unix)]
    {
        if let Ok(handle) = std::fs::File::open(directory) {
            let _ = handle.sync_all();
        }
    }
    #[cfg(not(unix))]
    {
        let _ = directory;
    }
}

/// 生成 `bytes` 字节的 OS 安全随机数并编码为十六进制，用于设备标识等非密钥用途。
pub fn random_hex(bytes: usize) -> Result<String> {
    let mut buffer = vec![0u8; bytes];
    getrandom::fill(&mut buffer).map_err(|error| anyhow!("无法获取安全随机数: {error}"))?;
    Ok(hex::encode(buffer))
}

fn backup_path(path: &Path) -> Result<PathBuf> {
    let file_name = path
        .file_name()
        .context("密钥路径缺少文件名")?
        .to_string_lossy()
        .into_owned();
    Ok(path.with_file_name(format!("{file_name}.bak")))
}

/// 数据目录下的同步密钥路径；内存数据库（测试）没有稳定目录。
pub fn key_path_for(data_dir: &Path) -> PathBuf {
    data_dir.join(KEY_FILE_NAME)
}

#[cfg(test)]
mod tests {
    use super::*;

    const AAD: &[u8] = b"snowfold/test/v1";

    fn key() -> SyncKey {
        SyncKey::from_bytes([0x42; 32])
    }

    #[test]
    fn roundtrip_preserves_bytes() -> Result<()> {
        let key = key();
        for plaintext in [&b""[..], b"hello snowfold", &vec![0x5a_u8; 1024 * 1024][..]] {
            let sealed = seal(plaintext, &key, AAD)?;
            assert_eq!(sealed.len(), BODY_OFFSET + plaintext.len() + TAG_LEN);
            assert_eq!(open(&sealed, &key, AAD)?.as_slice(), plaintext);
        }
        Ok(())
    }

    #[test]
    fn each_seal_uses_a_fresh_nonce() -> Result<()> {
        let key = key();
        let mut nonces = std::collections::HashSet::new();
        for _ in 0..1000 {
            let sealed = seal(b"same plaintext", &key, AAD)?;
            assert!(nonces.insert(sealed[HEADER_LEN..BODY_OFFSET].to_vec()));
        }
        Ok(())
    }

    #[test]
    fn wrong_key_and_wrong_aad_are_rejected() -> Result<()> {
        let sealed = seal(b"secret", &key(), AAD)?;
        let other = SyncKey::from_bytes([0x43; 32]);
        assert_eq!(
            open(&sealed, &other, AAD).unwrap_err().to_string(),
            CryptoError::Authentication.to_string()
        );
        assert_eq!(
            open(&sealed, &key(), b"other-purpose")
                .unwrap_err()
                .to_string(),
            CryptoError::Authentication.to_string()
        );
        Ok(())
    }

    #[test]
    fn every_byte_of_the_envelope_is_authenticated() -> Result<()> {
        let key = key();
        let sealed = seal(b"balance 100.00", &key, AAD)?;
        // 信封头 / nonce / 密文 / 标签逐字节翻转，任何一位都必须被发现。
        for index in 0..sealed.len() {
            let mut tampered = sealed.clone();
            tampered[index] ^= 0x01;
            assert!(
                open(&tampered, &key, AAD).is_err(),
                "信封第 {index} 字节被篡改却仍能解密"
            );
        }
        Ok(())
    }

    #[test]
    fn header_problems_are_classified_without_authenticating() -> Result<()> {
        let key = key();
        let sealed = seal(b"data", &key, AAD)?;
        assert_eq!(
            open(&sealed[..BODY_OFFSET + TAG_LEN - 1], &key, AAD)
                .unwrap_err()
                .to_string(),
            CryptoError::Truncated.to_string()
        );
        let mut bad_magic = sealed.clone();
        bad_magic[0] = b'X';
        assert!(open(&bad_magic, &key, AAD).is_err());
        let mut bad_format = sealed.clone();
        bad_format[4] = 2;
        assert_eq!(
            open(&bad_format, &key, AAD).unwrap_err().to_string(),
            CryptoError::UnsupportedFormat(2).to_string()
        );
        let mut bad_algorithm = sealed.clone();
        bad_algorithm[5] = 9;
        assert_eq!(
            open(&bad_algorithm, &key, AAD).unwrap_err().to_string(),
            CryptoError::UnsupportedAlgorithm(9).to_string()
        );
        Ok(())
    }

    #[test]
    fn key_hex_and_fingerprint_are_stable() -> Result<()> {
        let key = SyncKey::from_bytes([0x0f; 32]);
        let hex = key.to_hex();
        assert_eq!(hex.len(), 64);
        assert_eq!(SyncKey::from_hex(&hex)?.to_hex(), hex);
        assert_eq!(key.fingerprint(), key.fingerprint());
        assert_eq!(key.fingerprint().len(), "A1B2-C3D4-E5F6-7890".len());
        assert_ne!(
            SyncKey::from_bytes([0x10; 32]).fingerprint(),
            key.fingerprint()
        );
        assert!(SyncKey::from_hex("zz").is_err());
        assert!(SyncKey::from_hex(&"a".repeat(63)).is_err());
        assert!(SyncKey::from_hex(&"a".repeat(66)).is_err());
        Ok(())
    }

    #[test]
    fn key_file_roundtrips_and_rejects_tampering() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = key_path_for(directory.path());
        let created = SyncKey::load_or_create(&path)?;
        assert_eq!(created.to_hex(), SyncKey::load_or_create(&path)?.to_hex());
        assert_eq!(
            SyncKey::decode_file(&created.encode_file())?.to_hex(),
            created.to_hex()
        );
        for invalid in [
            "",
            "snowfold-sync-key-v1",
            "other-header\n00",
            &format!("{KEY_FILE_HEADER}\n00\n"),
            &format!("{KEY_FILE_HEADER}\n{}\nextra\n", "a".repeat(64)),
        ] {
            assert!(
                SyncKey::decode_file(invalid).is_err(),
                "不应接受密钥文件: {invalid:?}"
            );
        }
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn key_file_is_private_and_loose_permissions_are_tightened() -> Result<()> {
        use std::os::unix::fs::PermissionsExt;
        let directory = tempfile::tempdir()?;
        let path = key_path_for(directory.path());
        let key = SyncKey::load_or_create(&path)?;
        assert_eq!(
            std::fs::metadata(&path)?.permissions().mode() & 0o777,
            0o600
        );
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644))?;
        assert_eq!(SyncKey::load_or_create(&path)?.to_hex(), key.to_hex());
        assert_eq!(
            std::fs::metadata(&path)?.permissions().mode() & 0o777,
            0o600,
            "读取时应把过宽权限收紧"
        );
        Ok(())
    }

    #[test]
    fn key_debug_output_never_contains_the_secret() {
        let key = SyncKey::from_bytes([0xab; 32]);
        let rendered = format!("{key:?}");
        assert!(!rendered.contains(&key.to_hex()));
        assert!(rendered.contains(&key.fingerprint()));
    }

    #[test]
    fn load_reports_missing_key_and_reuses_existing_one() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = key_path_for(directory.path());
        assert!(SyncKey::load(&path)?.is_none());
        let created = SyncKey::load_or_create(&path)?;
        assert_eq!(SyncKey::load(&path)?.unwrap().to_hex(), created.to_hex());
        assert_eq!(SyncKey::load_or_create(&path)?.to_hex(), created.to_hex());
        Ok(())
    }

    #[test]
    fn replace_file_keeps_a_private_backup_of_the_old_key() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = key_path_for(directory.path());
        let old = SyncKey::load_or_create(&path)?;
        let new = SyncKey::generate()?;
        assert!(SyncKey::replace_file(&path, &new)?);
        assert_eq!(SyncKey::load(&path)?.unwrap().to_hex(), new.to_hex());
        let backup = directory.path().join(format!("{KEY_FILE_NAME}.bak"));
        assert_eq!(SyncKey::load(&backup)?.unwrap().to_hex(), old.to_hex());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&backup)?.permissions().mode() & 0o777;
            assert_eq!(mode, 0o600, "旧密钥备份也必须是 0600");
        }
        std::fs::remove_file(&path)?;
        assert!(
            !SyncKey::replace_file(&path, &new)?,
            "没有旧密钥时不应声称做过备份"
        );
        Ok(())
    }

    #[test]
    fn random_hex_is_csprng_backed_and_distinct() -> Result<()> {
        let first = random_hex(8)?;
        assert_eq!(first.len(), 16);
        assert_ne!(first, random_hex(8)?);
        assert!(first.chars().all(|c| c.is_ascii_hexdigit()));
        Ok(())
    }

    #[test]
    fn corrupt_or_partial_key_file_is_not_silently_replaced() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = key_path_for(directory.path());
        std::fs::write(&path, format!("{KEY_FILE_HEADER}\n00\n"))?;
        let before = std::fs::read(&path)?;
        assert!(SyncKey::load_or_create(&path).is_err());
        assert_eq!(std::fs::read(&path)?, before, "不能静默覆盖损坏的密钥文件");
        std::fs::write(&path, b"")?;
        assert!(SyncKey::load(&path).is_err());
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn dangling_key_symlink_is_reported_instead_of_looping() -> Result<()> {
        use std::os::unix::fs::symlink;
        let directory = tempfile::tempdir()?;
        let path = key_path_for(directory.path());
        symlink(directory.path().join("missing-key"), &path)?;
        let error = SyncKey::load_or_create(&path).unwrap_err();
        assert!(
            format!("{error:#}").contains("符号链接"),
            "应给出符号链接诊断: {error:#}"
        );
        Ok(())
    }

    #[test]
    fn decode_file_accepts_bom_and_verifies_fingerprint_comment() -> Result<()> {
        let key = SyncKey::from_bytes([0x5a; 32]);
        let text = format!(
            "\u{feff}# 密钥指纹：{}\n{KEY_FILE_HEADER}\n{}\n",
            key.fingerprint(),
            key.to_hex()
        );
        assert_eq!(SyncKey::decode_file(&text)?.to_hex(), key.to_hex());
        let wrong = format!(
            "# 密钥指纹：0000-0000-0000-0000\n{KEY_FILE_HEADER}\n{}\n",
            key.to_hex()
        );
        assert!(
            SyncKey::decode_file(&wrong).is_err(),
            "注释里的指纹与密钥不一致时必须拒绝"
        );
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn private_file_write_replaces_symlink_instead_of_writing_through_it() -> Result<()> {
        use std::os::unix::fs::{PermissionsExt, symlink};
        let directory = tempfile::tempdir()?;
        let target = directory.path().join("target.txt");
        std::fs::write(&target, b"original")?;
        let path = directory.path().join("secret.txt");
        symlink(&target, &path)?;

        write_private_file(&path, b"secret")?;

        assert_eq!(std::fs::read(&target)?, b"original", "不能顺着符号链接写");
        assert_eq!(std::fs::read(&path)?, b"secret");
        assert!(!std::fs::symlink_metadata(&path)?.file_type().is_symlink());
        assert_eq!(
            std::fs::metadata(&path)?.permissions().mode() & 0o777,
            0o600
        );
        Ok(())
    }
}
