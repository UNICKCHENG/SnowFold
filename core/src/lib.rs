//! Shared ledger rules and storage, with a serialized UniFFI host API.
pub mod backup;
pub mod crypto;
pub mod ffi;
pub mod model;
pub mod storage;

pub use ffi::*;
uniffi::setup_scaffolding!();
