//! UniFFI bindings for the fortis wallet core. Generates a Swift package (iOS) and
//! a Kotlin library (Android) from the same `wallet-core`.
//!
//! Scope mirrors `wallet-wasm`: key handling and swap-leg signing. The native
//! shells add secure key storage (Keychain / Secure Enclave, Keystore / StrongBox),
//! biometrics, and the UI.

uniffi::setup_scaffolding!();

use wallet_core::{ChainParams, MasterKey};

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiError {
    #[error("{0}")]
    Wallet(String),
}

impl From<wallet_core::WalletError> for FfiError {
    fn from(e: wallet_core::WalletError) -> Self {
        FfiError::Wallet(e.to_string())
    }
}

fn params(chain: &str) -> Result<ChainParams, FfiError> {
    ChainParams::resolve(
        match chain {
            "btc" => wallet_core::Chain::Btc,
            "blk" => wallet_core::Chain::Blk,
            _ => return Err(FfiError::Wallet("chain must be \"btc\" or \"blk\"".into())),
        },
        "mainnet",
    )
    .ok_or_else(|| FfiError::Wallet("unknown network".into()))
}

/// A 24-word mnemonic from 32 bytes of platform entropy.
#[uniffi::export]
pub fn generate_mnemonic(entropy: Vec<u8>) -> Result<String, FfiError> {
    let e: [u8; 32] = entropy
        .try_into()
        .map_err(|_| FfiError::Wallet("entropy must be 32 bytes".into()))?;
    let (mnemonic, _key) = MasterKey::generate(&e).map_err(FfiError::from)?;
    Ok(mnemonic.to_string())
}

/// Holds the master key. The native shell keeps the sealed seed in the platform
/// keystore and constructs this after an unlock.
#[derive(uniffi::Object)]
pub struct Wallet {
    key: MasterKey,
}

#[uniffi::export]
impl Wallet {
    #[uniffi::constructor]
    pub fn from_mnemonic(mnemonic: String, passphrase: String) -> Result<std::sync::Arc<Self>, FfiError> {
        Ok(std::sync::Arc::new(Wallet {
            key: MasterKey::from_phrase(&mnemonic, &passphrase).map_err(FfiError::from)?,
        }))
    }

    pub fn account_xpub(&self, chain: String, account: u32) -> Result<String, FfiError> {
        Ok(self.key.account_xpub(&params(&chain)?, account).map_err(FfiError::from)?.to_string())
    }

    pub fn swap_pubkey(&self, chain: String, account: u32, swap_index: u32) -> Result<String, FfiError> {
        let pk = self
            .key
            .swap_pubkey(&params(&chain)?, account, swap_index)
            .map_err(FfiError::from)?;
        Ok(hex::encode(pk.serialize()))
    }

    /// DER signature + sighash flag byte (hex).
    pub fn sign_swap(
        &self,
        chain: String,
        account: u32,
        swap_index: u32,
        sighash_hex: String,
    ) -> Result<String, FfiError> {
        let msg: [u8; 32] = hex::decode(&sighash_hex)
            .map_err(|e| FfiError::Wallet(e.to_string()))?
            .try_into()
            .map_err(|_| FfiError::Wallet("sighash must be 32 bytes".into()))?;
        let sig = self
            .key
            .sign_swap(&params(&chain)?, account, swap_index, &msg)
            .map_err(FfiError::from)?;
        Ok(hex::encode(sig))
    }
}
