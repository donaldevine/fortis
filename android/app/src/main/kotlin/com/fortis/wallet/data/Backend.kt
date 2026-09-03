package com.fortis.wallet.data

import uniffi.wallet_ffi.WalletUtxo

data class ChainStatus(
    val blocks: ULong,
    val synced: Boolean,
    val via: String,
)

data class Balances(val confirmedSat: Long, val pendingSat: Long)

data class HistoryEntry(
    val txid: String,
    val send: Boolean,
    val amountSat: Long,
    val confirmations: Long,
    val time: Long,
)

/** Same surface for both chain backends. Coin selection and signing happen in
 *  wallet-ffi; a backend only ever handles public data + finished transactions. */
interface Backend {
    val label: String
    suspend fun status(): ChainStatus
    suspend fun balances(): Balances
    suspend fun utxos(minConf: UInt): List<WalletUtxo>
    suspend fun feerateSatVb(confTarget: Int): ULong
    suspend fun history(count: Int): List<HistoryEntry>
    suspend fun broadcast(rawHex: String): String
}
