//! Read-only wallet view for one chain: derives addresses from an account xpub and
//! plans unsigned transactions from UTXOs the shell provides. Holds no secret keys.

use bitcoin::bip32::{ChildNumber, Xpub};
use bitcoin::script::PushBytesBuf;
use bitcoin::secp256k1::Secp256k1;
use bitcoin::{
    absolute, transaction, Address, Amount, CompressedPublicKey, OutPoint, Script, ScriptBuf,
    Sequence, Transaction, TxIn, TxOut, Witness,
};

use crate::chain::ChainParams;
use crate::error::{Result, WalletError};
use crate::htlc::HtlcContract;

/// A 0-value `OP_RETURN` output carrying `data`.
///
/// Opt-in BTC-side replay protection: the BLAKE2b fork caps datacarrier data at
/// **82 bytes at consensus** (RDTS), so a Bitcoin transaction that includes an
/// `OP_RETURN` larger than that is consensus-invalid on the fork and cannot be
/// replayed there. Pass ~100 fresh random bytes. (Such an output is non-standard
/// on default Bitcoin relay too — broadcast via a node/service that accepts it.)
pub fn op_return_output(data: &[u8]) -> Result<TxOut> {
    let push = PushBytesBuf::try_from(data.to_vec())
        .map_err(|_| WalletError::Bitcoin("OP_RETURN data too large".into()))?;
    Ok(TxOut { value: Amount::ZERO, script_pubkey: ScriptBuf::new_op_return(push) })
}

/// A service fee for a hosted backend, charged as a percentage of the amount
/// moved and added as an extra output on `plan_payment` / `plan_sweep`. Clamped to
/// `[floor_sat, cap_sat]` (`cap_sat == 0` means uncapped). Self-hosted backends
/// pass `None` and charge nothing.
#[derive(Debug, Clone)]
pub struct ServiceFee {
    pub bps: u32,
    pub floor_sat: u64,
    pub cap_sat: u64,
    pub fee_spk: ScriptBuf,
}

impl ServiceFee {
    fn amount_sat(&self, send_amount_sat: u64) -> u64 {
        let pct = (u128::from(send_amount_sat) * u128::from(self.bps) / 10_000) as u64;
        let fee = pct.max(self.floor_sat);
        if self.cap_sat > 0 {
            fee.min(self.cap_sat)
        } else {
            fee
        }
    }

    fn output(&self, send_amount_sat: u64) -> Option<TxOut> {
        let sat = self.amount_sat(send_amount_sat);
        if sat == 0 {
            return None;
        }
        // A fee below the dust threshold for the fee address makes the whole
        // transaction non-standard — bump it up to a spendable output.
        let value = Amount::from_sat(sat).max(self.fee_spk.minimal_non_dust());
        Some(TxOut { value, script_pubkey: self.fee_spk.clone() })
    }
}

/// A coin the shell reports to the core (from the platform indexer or an Electrum
/// server). Every wallet UTXO is assumed P2WPKH (BIP-84).
#[derive(Debug, Clone)]
pub struct Utxo {
    pub outpoint: OutPoint,
    pub value: Amount,
    pub script_pubkey: ScriptBuf,
    pub confirmations: u32,
    pub derivation_index: u32,
    pub is_change: bool,
}

/// The result of coin selection: an unsigned transaction plus what was chosen.
#[derive(Debug, Clone)]
pub struct FundingPlan {
    pub tx: Transaction,
    pub selected: Vec<Utxo>,
    pub fee: Amount,
    pub change: Option<Amount>,
    /// The service fee (if any), also present as an output in `tx`.
    pub service_fee: Option<Amount>,
}

// Rough vsize model. P2WPKH spends, segwit tx. Estimates run 1–2 vB high per input
// (low-s signature length varies), which errs toward slightly overpaying fees.
const TX_OVERHEAD_VB: u64 = 11;
const P2WPKH_INPUT_VB: u64 = 68;
/// Below this a change output costs more to spend than it's worth; fold it into fee.
const CHANGE_DUST_SAT: u64 = 294;

fn varint_len(n: usize) -> u64 {
    match n {
        0..=0xFC => 1,
        0xFD..=0xFFFF => 3,
        0x1_0000..=0xFFFF_FFFF => 5,
        _ => 9,
    }
}

fn output_vb(spk: &Script) -> u64 {
    8 + varint_len(spk.len()) + spk.len() as u64
}

pub struct WalletView {
    params: ChainParams,
    xpub: Xpub,
    next_receive: u32,
    next_change: u32,
}

impl WalletView {
    pub fn new(params: ChainParams, xpub: Xpub) -> Self {
        Self { params, xpub, next_receive: 0, next_change: 0 }
    }

    /// Resume address derivation from persisted counters after a shell restart, so
    /// the next receive / change address is not one already handed out.
    pub fn set_next_indices(&mut self, next_receive: u32, next_change: u32) {
        self.next_receive = next_receive;
        self.next_change = next_change;
    }

    /// `(next_receive, next_change)` — read back after planning to persist the
    /// change index that a payment consumed.
    pub fn next_indices(&self) -> (u32, u32) {
        (self.next_receive, self.next_change)
    }

    pub fn balance(&self, utxos: &[Utxo]) -> Amount {
        utxos.iter().map(|u| u.value).fold(Amount::ZERO, |a, b| a + b)
    }

    /// Next unused external (receive) address, BIP-84 `.../0/<i>`.
    pub fn next_receive_address(&mut self) -> Result<Address> {
        let addr = self.address_at(0, self.next_receive)?;
        self.next_receive += 1;
        Ok(addr)
    }

    /// Next unused internal (change) address, BIP-84 `.../1/<i>`.
    pub fn next_change_address(&mut self) -> Result<Address> {
        let addr = self.address_at(1, self.next_change)?;
        self.next_change += 1;
        Ok(addr)
    }

    /// The P2WPKH (BIP-84) address at `.../<branch>/<index>` — `branch` 0 for
    /// receive, 1 for change. Lets a shell re-derive a specific address (e.g. to
    /// cross-check one the node reported) without reimplementing derivation.
    pub fn address_at(&self, branch: u32, index: u32) -> Result<Address> {
        let secp = Secp256k1::verification_only();
        let child = self
            .xpub
            .derive_pub(
                &secp,
                &[
                    ChildNumber::from_normal_idx(branch)?,
                    ChildNumber::from_normal_idx(index)?,
                ],
            )
            .map_err(|e| WalletError::Derivation(e.to_string()))?;
        let pk = CompressedPublicKey(child.public_key);
        Ok(Address::p2wpkh(&pk, self.params.network))
    }

    /// Coin-select from `utxos` (largest-first), add a change output when the leftover
    /// is economically spendable, and return an unsigned transaction.
    ///
    /// TODO(phase-1): branch-and-bound selection, BIP-69 / random output ordering.
    pub fn plan_payment(
        &mut self,
        utxos: &[Utxo],
        mut outputs: Vec<TxOut>,
        feerate_sat_vb: u64,
        min_confirmations: u32,
        service_fee: Option<&ServiceFee>,
    ) -> Result<FundingPlan> {
        let service_fee_amt = service_fee.and_then(|sf| {
            let send_amount_sat: u64 = outputs.iter().map(|o| o.value.to_sat()).sum();
            sf.output(send_amount_sat)
        });
        if let Some(out) = &service_fee_amt {
            outputs.push(out.clone());
        }
        let service_fee_sat = service_fee_amt.map(|o| o.value);

        let target = outputs.iter().map(|o| o.value).fold(Amount::ZERO, |a, b| a + b);
        let outputs_vb: u64 = outputs.iter().map(|o| output_vb(&o.script_pubkey)).sum();

        let mut eligible: Vec<&Utxo> =
            utxos.iter().filter(|u| u.confirmations >= min_confirmations).collect();
        eligible.sort_by(|a, b| b.value.cmp(&a.value));

        let change_spk = self.address_at(1, self.next_change)?.script_pubkey();
        let change_vb = output_vb(&change_spk);

        let mut selected: Vec<Utxo> = Vec::new();
        let mut acc = Amount::ZERO;

        for u in eligible {
            selected.push(u.clone());
            acc += u.value;
            let n = selected.len() as u64;
            let base_vb = TX_OVERHEAD_VB + n * P2WPKH_INPUT_VB + outputs_vb;
            let fee_no_change = Amount::from_sat(base_vb * feerate_sat_vb);
            let fee_with_change = Amount::from_sat((base_vb + change_vb) * feerate_sat_vb);

            if acc >= target + fee_with_change {
                let change = acc - target - fee_with_change;
                if change.to_sat() >= CHANGE_DUST_SAT {
                    let mut outs = outputs.clone();
                    outs.push(TxOut { value: change, script_pubkey: change_spk });
                    self.next_change += 1;
                    return Ok(assemble(selected, outs, fee_with_change, Some(change), service_fee_sat));
                }
            }
            if acc >= target + fee_no_change {
                // No change output — the surplus (< a change output's cost) is fee.
                return Ok(assemble(selected, outputs, acc - target, None, service_fee_sat));
            }
        }

        Err(WalletError::InsufficientFunds { need: target.to_sat(), have: acc.to_sat() })
    }

    /// Send every input confirmed at least `min_confirmations` deep to a single
    /// destination, with the fee taken from the total (no change output). For
    /// "empty this wallet" / sweeps.
    pub fn plan_sweep(
        &self,
        utxos: &[Utxo],
        dest: ScriptBuf,
        feerate_sat_vb: u64,
        min_confirmations: u32,
        service_fee: Option<&ServiceFee>,
    ) -> Result<FundingPlan> {
        let selected: Vec<Utxo> = utxos
            .iter()
            .filter(|u| u.confirmations >= min_confirmations)
            .cloned()
            .collect();
        if selected.is_empty() {
            return Err(WalletError::InsufficientFunds { need: 1, have: 0 });
        }
        let total = selected.iter().map(|u| u.value).fold(Amount::ZERO, |a, b| a + b);
        let fee_out = service_fee.and_then(|sf| sf.output(total.to_sat()));
        let fee_out_value = fee_out.as_ref().map_or(Amount::ZERO, |o| o.value);
        let fee_out_vb = fee_out.as_ref().map_or(0, |o| output_vb(&o.script_pubkey));

        let vb = TX_OVERHEAD_VB + selected.len() as u64 * P2WPKH_INPUT_VB + output_vb(&dest) + fee_out_vb;
        let fee = Amount::from_sat(vb * feerate_sat_vb);
        let value = total
            .checked_sub(fee)
            .and_then(|v| v.checked_sub(fee_out_value))
            .filter(|v| v.to_sat() >= CHANGE_DUST_SAT)
            .ok_or(WalletError::InsufficientFunds {
                need: (fee + fee_out_value).to_sat() + CHANGE_DUST_SAT,
                have: total.to_sat(),
            })?;
        let service_fee_sat = fee_out.as_ref().map(|o| o.value);
        let mut outs = vec![TxOut { value, script_pubkey: dest }];
        if let Some(o) = fee_out {
            outs.push(o);
        }
        Ok(assemble(selected, outs, fee, None, service_fee_sat))
    }

    /// Plan a transaction that funds `contract`'s HTLC output.
    pub fn plan_htlc_funding(
        &mut self,
        utxos: &[Utxo],
        contract: &HtlcContract,
        feerate_sat_vb: u64,
        min_confirmations: u32,
    ) -> Result<FundingPlan> {
        self.plan_payment(utxos, vec![contract.funding_output()], feerate_sat_vb, min_confirmations, None)
    }
}

fn assemble(
    selected: Vec<Utxo>,
    outputs: Vec<TxOut>,
    fee: Amount,
    change: Option<Amount>,
    service_fee: Option<Amount>,
) -> FundingPlan {
    let input = selected
        .iter()
        .map(|u| TxIn {
            previous_output: u.outpoint,
            script_sig: ScriptBuf::new(),
            sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
            witness: Witness::new(),
        })
        .collect();
    let tx = Transaction {
        version: transaction::Version::TWO,
        lock_time: absolute::LockTime::ZERO,
        input,
        output: outputs,
    };
    FundingPlan { tx, selected, fee, change, service_fee }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys::MasterKey;
    use bitcoin::hashes::Hash;
    use bitcoin::Txid;

    fn view() -> WalletView {
        let (_m, key) = MasterKey::generate(&[3u8; 32]).unwrap();
        let params = ChainParams::bitcoin();
        let xpub = key.account_xpub(&params, 0).unwrap();
        WalletView::new(params, xpub)
    }

    fn utxo(sats: u64, confs: u32, tag: u8) -> Utxo {
        Utxo {
            outpoint: OutPoint::new(Txid::from_byte_array([tag; 32]), 0),
            value: Amount::from_sat(sats),
            script_pubkey: ScriptBuf::new(),
            confirmations: confs,
            derivation_index: tag as u32,
            is_change: false,
        }
    }

    fn htlc_out(sats: u64) -> TxOut {
        // 34-byte P2WSH-shaped placeholder scriptPubKey.
        TxOut {
            value: Amount::from_sat(sats),
            script_pubkey: ScriptBuf::from(vec![0u8; 34]),
        }
    }

    #[test]
    fn derives_distinct_bech32_addresses() {
        let mut v = view();
        let a0 = v.next_receive_address().unwrap();
        let a1 = v.next_receive_address().unwrap();
        let c0 = v.next_change_address().unwrap();
        assert_ne!(a0, a1);
        assert_ne!(a0, c0);
        assert!(a0.to_string().starts_with("bc1q"));
    }

    #[test]
    fn funds_with_change() {
        let mut v = view();
        let utxos = [utxo(1_000_000, 3, 1)];
        let plan = v.plan_payment(&utxos, vec![htlc_out(200_000)], 10, 1, None).unwrap();
        assert_eq!(plan.selected.len(), 1);
        assert_eq!(plan.tx.output.len(), 2); // htlc + change
        let change = plan.change.unwrap();
        assert_eq!(
            (Amount::from_sat(200_000) + plan.fee + change).to_sat(),
            1_000_000
        );
        // ~ (11 + 68 + 43 + 31) vB * 10 = 1530 sat
        assert!((1400..1700).contains(&plan.fee.to_sat()), "fee was {}", plan.fee);
    }

    #[test]
    fn no_change_when_surplus_is_small() {
        let mut v = view();
        // base vsize = 11 + 68 + 43 = 122; no-change fee @10 sat/vB = 1220.
        // A change output would cost ~310 more, so a ~100 sat leftover folds into fee.
        let utxos = [utxo(200_000 + 1_320, 3, 1)];
        let plan = v.plan_payment(&utxos, vec![htlc_out(200_000)], 10, 1, None).unwrap();
        assert_eq!(plan.tx.output.len(), 1);
        assert!(plan.change.is_none());
        assert_eq!(plan.fee.to_sat(), 1_320);
    }

    #[test]
    fn accumulates_multiple_inputs() {
        let mut v = view();
        let utxos = [utxo(100_000, 3, 1), utxo(90_000, 3, 2), utxo(80_000, 3, 3)];
        let plan = v.plan_payment(&utxos, vec![htlc_out(200_000)], 5, 1, None).unwrap();
        assert_eq!(plan.selected.len(), 3);
    }

    #[test]
    fn rejects_insufficient_funds() {
        let mut v = view();
        let utxos = [utxo(50_000, 3, 1)];
        assert!(v.plan_payment(&utxos, vec![htlc_out(200_000)], 5, 1, None).is_err());
    }

    #[test]
    fn excludes_unconfirmed_below_threshold() {
        let mut v = view();
        let utxos = [utxo(1_000_000, 0, 1)];
        assert!(v.plan_payment(&utxos, vec![htlc_out(200_000)], 5, 1, None).is_err());
    }

    #[test]
    fn sweep_spends_everything_minus_fee() {
        let v = view();
        let utxos = [utxo(400_000, 3, 1), utxo(600_000, 3, 2), utxo(9_999, 0, 3)];
        let dest = ScriptBuf::from(vec![0u8; 22]); // P2WPKH-shaped
        let plan = v.plan_sweep(&utxos, dest, 10, 1, None).unwrap();
        assert_eq!(plan.selected.len(), 2); // the 0-conf utxo is excluded
        assert_eq!(plan.tx.output.len(), 1);
        assert!(plan.change.is_none());
        assert_eq!(plan.tx.output[0].value + plan.fee, Amount::from_sat(1_000_000));
        // vsize = 11 + 2*68 + 31 = 178; fee @10 = 1780
        assert_eq!(plan.fee.to_sat(), 1_780);
    }

    #[test]
    fn sweep_rejects_when_fee_exceeds_funds() {
        let v = view();
        let utxos = [utxo(500, 3, 1)];
        assert!(v.plan_sweep(&utxos, ScriptBuf::from(vec![0u8; 22]), 10, 1, None).is_err());
    }

    #[test]
    fn op_return_output_is_a_zero_value_data_push() {
        let out = super::op_return_output(&[7u8; 100]).unwrap();
        assert_eq!(out.value, Amount::ZERO);
        assert!(out.script_pubkey.is_op_return());
        // OP_RETURN + OP_PUSHDATA1 + len byte + 100 data
        assert_eq!(out.script_pubkey.len(), 103);
    }

    #[test]
    fn payment_with_op_return_pays_for_the_extra_bytes() {
        let mut v = view();
        let utxos = [utxo(1_000_000, 3, 1)];
        let mut outs = vec![htlc_out(200_000)];
        outs.push(super::op_return_output(&[9u8; 100]).unwrap());
        let plan = v.plan_payment(&utxos, outs, 10, 1, None).unwrap();
        assert_eq!(plan.tx.output.iter().filter(|o| o.script_pubkey.is_op_return()).count(), 1);
        // fee covers the ~112 vB OP_RETURN output on top of the base tx
        assert!(plan.fee.to_sat() >= (11 + 68 + 43 + 31 + 112) * 10 - 20);
    }

    #[test]
    fn resumes_change_index_from_persisted_counter() {
        let mut v = view();
        v.set_next_indices(7, 4);
        assert_eq!(v.next_indices(), (7, 4));
        let utxos = [utxo(1_000_000, 3, 1)];
        let plan = v.plan_payment(&utxos, vec![htlc_out(200_000)], 10, 1, None).unwrap();
        assert!(plan.change.is_some());
        assert_eq!(v.next_indices().1, 5); // change index advanced 4 -> 5
    }

    fn fee_addr_spk() -> ScriptBuf {
        ScriptBuf::from(vec![0u8; 22]) // P2WPKH-shaped
    }

    #[test]
    fn service_fee_is_a_percentage_with_floor_and_cap() {
        let sf = ServiceFee { bps: 25, floor_sat: 200, cap_sat: 5_000, fee_spk: fee_addr_spk() };
        assert_eq!(sf.amount_sat(10_000), 200); // 0.25% of 10k = 25, below floor
        assert_eq!(sf.amount_sat(1_000_000), 2_500); // 0.25% of 1,000,000 = 2,500
        assert_eq!(sf.amount_sat(10_000_000), 5_000); // 0.25% of 10M = 25,000, capped
    }

    #[test]
    fn service_fee_uncapped_when_cap_is_zero() {
        let sf = ServiceFee { bps: 25, floor_sat: 0, cap_sat: 0, fee_spk: fee_addr_spk() };
        assert_eq!(sf.amount_sat(10_000_000), 25_000);
    }

    #[test]
    fn service_fee_output_is_never_dust() {
        // 1% of a small send (and even a low floor) would be a dust output that
        // makes the whole tx non-standard — the output must be bumped up.
        let sf = ServiceFee { bps: 100, floor_sat: 100, cap_sat: 0, fee_spk: fee_addr_spk() };
        let out = sf.output(5_000).unwrap();
        assert!(out.value >= fee_addr_spk().minimal_non_dust());
        assert!(out.value.to_sat() > 100);
    }

    #[test]
    fn payment_adds_a_service_fee_output_paid_from_the_inputs() {
        let mut v = view();
        let utxos = [utxo(1_000_000, 3, 1)];
        let sf = ServiceFee { bps: 100, floor_sat: 200, cap_sat: 0, fee_spk: fee_addr_spk() };
        let plan = v.plan_payment(&utxos, vec![htlc_out(200_000)], 10, 1, Some(&sf)).unwrap();
        // 1% of 200,000 = 2,000
        assert_eq!(plan.service_fee, Some(Amount::from_sat(2_000)));
        assert_eq!(
            plan.tx.output.iter().filter(|o| o.script_pubkey == fee_addr_spk()).count(),
            1
        );
        let change = plan.change.unwrap();
        assert_eq!(
            (Amount::from_sat(200_000) + Amount::from_sat(2_000) + plan.fee + change).to_sat(),
            1_000_000
        );
    }

    #[test]
    fn sweep_carves_the_service_fee_out_of_the_swept_total() {
        let v = view();
        let utxos = [utxo(1_000_000, 3, 1)];
        let dest = ScriptBuf::from(vec![1u8; 22]);
        let sf = ServiceFee { bps: 100, floor_sat: 200, cap_sat: 0, fee_spk: fee_addr_spk() };
        let plan = v.plan_sweep(&utxos, dest, 10, 1, Some(&sf)).unwrap();
        // 1% of the swept 1,000,000 = 10,000
        assert_eq!(plan.service_fee, Some(Amount::from_sat(10_000)));
        assert_eq!(plan.tx.output.len(), 2); // destination + fee
        let dest_out = plan.tx.output.iter().find(|o| o.script_pubkey != fee_addr_spk()).unwrap();
        assert_eq!(
            (dest_out.value + Amount::from_sat(10_000) + plan.fee).to_sat(),
            1_000_000
        );
    }
}
