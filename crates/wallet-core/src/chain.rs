//! Per-chain parameters. The fork changed only proof-of-work, the 164-byte header,
//! and added opt-in `SIGHASH_UNIFIED` — transactions, addresses and Script are
//! otherwise identical to Bitcoin, so the wallet is chain-agnostic apart from these
//! values.

use bitcoin::Network;

use crate::sighash::SighashVariant;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Chain {
    /// Canonical Bitcoin (SHA256d proof-of-work).
    Btc,
    /// Bitcoin Knots BLAKE2b hard fork.
    Blk,
}

impl Chain {
    pub const ALL: [Chain; 2] = [Chain::Btc, Chain::Blk];

    pub fn as_str(self) -> &'static str {
        match self {
            Chain::Btc => "btc",
            Chain::Blk => "blk",
        }
    }
}

#[derive(Debug, Clone)]
pub struct ChainParams {
    pub chain: Chain,
    pub network: Network,
    /// BIP-44 coin type for HD derivation: `m/84'/<coin_type>'/...`
    pub bip44_coin_type: u32,
    /// Refund CLTV offset (seconds after funding) for the participant leg.
    pub htlc_participant_locktime_secs: u32,
    /// Refund CLTV offset (seconds) for the initiator leg — always the longer one.
    pub htlc_initiator_locktime_secs: u32,
    /// Spends on this chain must use `SIGHASH_UNIFIED` to be replay-safe.
    pub require_unified_sighash: bool,
}

impl ChainParams {
    pub fn bitcoin() -> Self {
        Self {
            chain: Chain::Btc,
            network: Network::Bitcoin,
            bip44_coin_type: 0,
            htlc_participant_locktime_secs: 24 * 3600,
            htlc_initiator_locktime_secs: 48 * 3600,
            require_unified_sighash: false,
        }
    }

    /// NOTE: verify coin type / HRP against the Knots BLAKE2b release before mainnet.
    pub fn blake2b() -> Self {
        Self {
            chain: Chain::Blk,
            network: Network::Bitcoin,
            bip44_coin_type: 0,
            // Wider than Bitcoin: BLAKE2b confirmations are slow and reorg-prone.
            htlc_participant_locktime_secs: 72 * 3600,
            htlc_initiator_locktime_secs: 144 * 3600,
            require_unified_sighash: true,
        }
    }

    /// Regtest variant of [`Self::bitcoin`] — `bcrt1` addresses, BIP-44 coin type 1,
    /// short timelocks so a test can step past them with `setmocktime`.
    pub fn bitcoin_regtest() -> Self {
        Self {
            network: Network::Regtest,
            bip44_coin_type: 1,
            htlc_participant_locktime_secs: 600,
            htlc_initiator_locktime_secs: 1200,
            ..Self::bitcoin()
        }
    }

    /// Regtest variant of [`Self::blake2b`]. Requires the node to have
    /// `DEPLOYMENT_BLAKE2B` (and thus `SIGHASH_UNIFIED`) active on regtest.
    pub fn blake2b_regtest() -> Self {
        Self {
            network: Network::Regtest,
            bip44_coin_type: 1,
            htlc_participant_locktime_secs: 900,
            htlc_initiator_locktime_secs: 1800,
            ..Self::blake2b()
        }
    }

    pub fn for_chain(chain: Chain) -> Self {
        match chain {
            Chain::Btc => Self::bitcoin(),
            Chain::Blk => Self::blake2b(),
        }
    }

    /// `network` accepts `"mainnet"` / `"bitcoin"`, `"regtest"`, or
    /// `"regtest-legacy"` (regtest but the BLK leg signs plain segwit-v0 rather than
    /// `SIGHASH_UNIFIED` — for smoke-testing swap mechanics on two vanilla nodes
    /// before a real BLAKE2b node is available).
    pub fn resolve(chain: Chain, network: &str) -> Option<Self> {
        Some(match (chain, network) {
            (Chain::Btc, "mainnet" | "bitcoin") => Self::bitcoin(),
            (Chain::Btc, "regtest" | "regtest-legacy") => Self::bitcoin_regtest(),
            (Chain::Blk, "mainnet" | "bitcoin") => Self::blake2b(),
            (Chain::Blk, "regtest") => Self::blake2b_regtest(),
            (Chain::Blk, "regtest-legacy") => Self {
                require_unified_sighash: false,
                ..Self::blake2b_regtest()
            },
            _ => return None,
        })
    }

    pub fn sighash_variant(&self) -> SighashVariant {
        if self.require_unified_sighash {
            SighashVariant::Unified
        } else {
            SighashVariant::SegwitV0
        }
    }
}
