package com.fortis.wallet.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uniffi.wallet_ffi.WalletUtxo
import uniffi.wallet_ffi.WalletView

private const val GAP = 20

/**
 * Esplora / mempool.space REST backend — no node. Esplora is address-based, so
 * the wallet derives its own addresses (via wallet-ffi's WalletView) and this
 * scans them with a gap limit. Port of web/src/esplora.js.
 */
class EsploraBackend(
    private val http: OkHttpClient,
    baseUrl: String,
    private val view: WalletView,
    private val counters: () -> Pair<Int, Int>,
) : Backend {
    private val base = baseUrl.trimEnd('/')
    override val label: String = Regex("https?://([^/]+)").find(base)?.groupValues?.get(1) ?: base

    private class WatchAddr(val address: String, val spk: String, val branch: UInt, val index: UInt)

    private fun watchSet(): List<WatchAddr> {
        val (nr, nc) = counters()
        val out = ArrayList<WatchAddr>()
        for ((branch, upto) in listOf(0 to nr + GAP, 1 to nc + GAP)) {
            for (i in 0 until upto) {
                val a = view.addressAt(branch.toUInt(), i.toUInt())
                out += WatchAddr(a.address, a.scriptPubkeyHex, branch.toUInt(), i.toUInt())
            }
        }
        return out
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(base + path).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            check(r.isSuccessful) { "explorer ${r.code} on $path" }
            body
        }
    }

    private suspend fun tip(): ULong = get("/blocks/tip/height").trim().toULong()

    private var cachedUtxos: List<WalletUtxo>? = null
    private var cachedAt = 0L

    private suspend fun scan(force: Boolean = false): List<WalletUtxo> {
        val now = System.currentTimeMillis()
        if (!force && cachedUtxos != null && now - cachedAt < 10_000) return cachedUtxos!!
        val t = tip()
        val out = ArrayList<WalletUtxo>()
        for (a in watchSet()) {
            val arr = JSONArray(get("/address/${a.address}/utxo"))
            for (i in 0 until arr.length()) {
                val u = arr.getJSONObject(i)
                val st = u.optJSONObject("status")
                val confirmed = st?.optBoolean("confirmed") == true
                val h = st?.optLong("block_height") ?: 0L
                val conf = if (confirmed) maxOf(1L, t.toLong() - h + 1) else 0L
                out += WalletUtxo(
                    txid = u.getString("txid"),
                    vout = u.getInt("vout").toUInt(),
                    valueSat = u.getLong("value").toULong(),
                    scriptPubkeyHex = a.spk,
                    confirmations = conf.toUInt(),
                    derivationIndex = a.index,
                    isChange = a.branch == 1u,
                )
            }
        }
        cachedUtxos = out
        cachedAt = now
        return out
    }

    override suspend fun status(): ChainStatus {
        val t = tip()
        return ChainStatus(blocks = t, synced = true, via = label)
    }

    override suspend fun balances(): Balances {
        val u = scan()
        return Balances(
            confirmedSat = u.filter { it.confirmations >= 1u }.sumOf { it.valueSat.toLong() },
            pendingSat = u.filter { it.confirmations < 1u }.sumOf { it.valueSat.toLong() },
        )
    }

    override suspend fun utxos(minConf: UInt): List<WalletUtxo> =
        scan(force = true).filter { it.confirmations >= minConf }

    override suspend fun feerateSatVb(confTarget: Int): ULong = try {
        val f = JSONObject(get("/v1/fees/recommended"))
        val pick = when {
            confTarget <= 1 -> f.optDouble("fastestFee")
            confTarget <= 6 -> f.optDouble("halfHourFee")
            else -> f.optDouble("economyFee", f.optDouble("hourFee"))
        }
        maxOf(1L, Math.round(pick)).toULong()
    } catch (e: Exception) { 1uL }

    override suspend fun history(count: Int): List<HistoryEntry> {
        val mine = watchSet().map { it.address }.toHashSet()
        val tipH = tip().toLong()
        val seen = LinkedHashMap<String, HistoryEntry>()
        for (a in watchSet()) {
            val arr = JSONArray(get("/address/${a.address}/txs"))
            for (i in 0 until arr.length()) {
                val tx = arr.getJSONObject(i)
                val id = tx.getString("txid")
                if (seen.containsKey(id)) continue
                var inOurs = 0L; var outOurs = 0L
                tx.getJSONArray("vin").let { vin ->
                    for (j in 0 until vin.length()) {
                        val po = vin.getJSONObject(j).optJSONObject("prevout") ?: continue
                        if (po.optString("scriptpubkey_address") in mine) inOurs += po.optLong("value")
                    }
                }
                tx.getJSONArray("vout").let { vout ->
                    for (j in 0 until vout.length()) {
                        val o = vout.getJSONObject(j)
                        if (o.optString("scriptpubkey_address") in mine) outOurs += o.optLong("value")
                    }
                }
                val delta = outOurs - inOurs
                val send = delta < 0
                val fee = tx.optLong("fee")
                val stTx = tx.optJSONObject("status")
                val confirmed = stTx?.optBoolean("confirmed") == true
                seen[id] = HistoryEntry(
                    txid = id,
                    send = send,
                    amountSat = if (send) delta + fee else delta,
                    confirmations = if (confirmed) maxOf(1L, tipH - stTx!!.optLong("block_height") + 1) else 0L,
                    time = stTx?.optLong("block_time") ?: (System.currentTimeMillis() / 1000),
                )
            }
        }
        return seen.values.sortedByDescending { it.time }.take(count)
    }

    override suspend fun broadcast(rawHex: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/tx")
            .post(rawHex.toRequestBody("text/plain".toMediaTypeOrNull())).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string()?.trim().orEmpty()
            check(r.isSuccessful) { body.ifBlank { "explorer rejected the transaction (${r.code})" } }
            cachedUtxos = null
            body
        }
    }
}
