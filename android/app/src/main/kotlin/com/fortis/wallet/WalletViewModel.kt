package com.fortis.wallet

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fortis.wallet.data.*
import com.fortis.wallet.wallet.WalletSession
import com.fortis.wallet.wallet.newMnemonic
import com.fortis.wallet.wallet.sealSeed
import com.fortis.wallet.wallet.unsealSeed
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import uniffi.wallet_ffi.FundingPlan
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

enum class Phase { Loading, Onboard, Gen, Create, Restore, Locked, Home, Settings }

/** The one backend the mobile app talks to. Not user-configurable, not shown. */
const val HOSTED_EDGE = "https://api.fortis.rest"

/** Public Esplora fallbacks, used when [HOSTED_EDGE] is unreachable. */
const val PUBLIC_BTC_ESPLORA = "https://mempool.space/api"
const val PUBLIC_BTCB2_ESPLORA = "https://mempool.guide/api"

data class PlanPreview(
    val plan: FundingPlan, val feerate: ULong, val to: String,
    val sweep: Boolean, val replayProtected: Boolean = false,
)

class WalletViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val http = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY) // ignore any Wi-Fi/Studio proxy — local hosts must be direct
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var phase by mutableStateOf(Phase.Loading); private set
    var config by mutableStateOf<WalletConfig?>(null); private set
    var session by mutableStateOf<WalletSession?>(null); private set
    var backend: Backend? = null; private set          // the hosted edge (primary)
    private var fallback: Backend? = null              // public explorer, btc only
    private var usingFallback by mutableStateOf(false)

    /** Whichever backend last answered — what sends and coin queries must use. */
    private fun active(): Backend = (if (usingFallback) fallback else backend) ?: backend
        ?: error("no backend")

    var draftMnemonic by mutableStateOf<String?>(null); private set
    var status by mutableStateOf<ChainStatus?>(null); private set
    var balances by mutableStateOf<Balances?>(null); private set
    var history by mutableStateOf<List<HistoryEntry>>(emptyList()); private set
    var feerates by mutableStateOf<Map<Int, Long>>(emptyMap()); private set
    var pending by mutableStateOf<PlanPreview?>(null); private set
    var lastSentTxid by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)

    init { viewModelScope.launch { config = store.load(); resolvePhase() } }

    private fun resolvePhase() {
        phase = when {
            config == null -> Phase.Onboard
            session == null -> Phase.Locked
            else -> { ensureBackend(); Phase.Home }
        }
        if (phase == Phase.Home) refresh()
    }

    fun goCreate() {
        // Existing draft (came back from the phrase screen) → skip re-collecting.
        phase = if (draftMnemonic != null) Phase.Create else Phase.Gen
    }
    /** Finish the entropy step: mix `extra` into the CSPRNG and show the phrase. */
    fun generateSeed(extra: ByteArray, words: Int) = wrap {
        draftMnemonic = newMnemonic(extra, words)
        phase = Phase.Create
    }
    fun goRestore() { phase = Phase.Restore }
    fun goOnboard() { draftMnemonic = null; phase = Phase.Onboard }
    fun goSettings() { phase = Phase.Settings }
    fun goHome() { resolvePhase() }

    /** Drop the stored token and mint a fresh one (Settings → Reconnect). */
    fun reconnect() = wrap {
        val token = edgeRegister(http, HOSTED_EDGE)
        persistToken(token)
        backend = edgeBackend(token)
        usingFallback = false
        backend!!.status()
        status = null; balances = null; history = emptyList()
        resolvePhase()
    }

    fun createWallet(chain: String, network: String, passphrase: String, password: String) = wrap {
        finishOnboard(chain, network, draftMnemonic!!, passphrase, password)
    }

    fun restoreWallet(phrase: String, passphrase: String, chain: String, network: String, password: String) = wrap {
        val words = phrase.trim().split(Regex("\\s+")).joinToString(" ")
        // constructing a session validates the words
        finishOnboard(chain, network, words, passphrase, password)
    }

    private suspend fun finishOnboard(chain: String, network: String, mnemonic: String, passphrase: String, password: String) {
        val s = WalletSession(chain, network, mnemonic, passphrase)
        val sealed = sealSeed(mnemonic, passphrase, password)
        val c = WalletConfig(chain, network, sealed.blobHex, sealed.saltHex)
        store.save(c)
        config = c; session = s; draftMnemonic = null
        resolvePhase()
    }

    fun unlock(password: String) = wrap {
        val c = config!!
        val (mnemonic, passphrase) = unsealSeed(c.sealed, c.salt, password)
        session = WalletSession(c.chain, c.network, mnemonic, passphrase)
        session!!.setIndices(c.nextReceive, c.nextChange)
        resolvePhase()
    }

    fun lock() {
        session?.close(); session = null
        backend = null; fallback = null; usingFallback = false
        lastSentTxid = null; pending = null
        phase = Phase.Locked
    }

    fun wipe() = viewModelScope.launch {
        store.wipe(); session?.close(); session = null
        backend = null; fallback = null; usingFallback = false
        lastSentTxid = null; pending = null
        config = null; phase = Phase.Onboard
    }

    /** The hosted fortis-edge as an Esplora backend at `{HOSTED_EDGE}/{chain}`.
     *  An empty token 401s on the first call, which the `refresh` callback turns
     *  into a `POST /register` + retry — so no explicit sign-up step. */
    private fun edgeBackend(token: String): EsploraBackend {
        val c = config!!
        return EsploraBackend(
            http, "$HOSTED_EDGE/${c.chain}", session!!.view,
            { config!!.nextReceive to config!!.nextChange },
            token,
            "$HOSTED_EDGE/pricing",
        ) {
            val fresh = edgeRegister(http, HOSTED_EDGE)
            persistToken(fresh)
            fresh
        }
    }

    private suspend fun persistToken(token: String) {
        val c = config!!.copy(backendKind = "edge", backendUrl = HOSTED_EDGE, backendToken = token)
        store.save(c); config = c
    }

    private fun ensureBackend() {
        val c = config ?: return
        val view = session?.view ?: return
        if (backend == null) backend = edgeBackend(c.backendToken ?: "")
        if (fallback == null) {
            val esplora = if (c.chain == "btc") PUBLIC_BTC_ESPLORA else PUBLIC_BTCB2_ESPLORA
            fallback = EsploraBackend(http, esplora, view, { config!!.nextReceive to config!!.nextChange })
        }
    }

    fun refresh() = viewModelScope.launch {
        val edge = backend ?: return@launch
        suspend fun load(b: Backend, degraded: Boolean) {
            status = b.status().copy(degraded = degraded)
            usingFallback = degraded
            balances = b.balances()
            history = b.history(50)
            if (feerates.isEmpty()) feerates = listOf(1, 6, 144).associateWith { b.feerateSatVb(it).toLong() }
        }
        // Always try the hosted service first, so we recover automatically when
        // it comes back; drop to the public explorer (btc only) meanwhile.
        runCatching { load(edge, false) }
            .recoverCatching { e -> fallback?.let { load(it, true) } ?: throw e }
            .onFailure { status = null }
    }

    fun newReceiveAddress() = viewModelScope.launch {
        val c = config ?: return@launch
        val updated = c.copy(nextReceive = c.nextReceive + 1)
        store.save(updated); config = updated
    }

    fun buildPayment(
        to: String, amountBtcb2: String, sweep: Boolean,
        feerateOverride: Long?, confTarget: Int, replayProtect: Boolean,
    ) = wrap {
        val b = active(); val s = session!!; val c = config!!
        val feerate = (feerateOverride ?: b.feerateSatVb(confTarget).toLong()).coerceAtLeast(1)
        val utxos = b.utxos(1u)
        require(utxos.isNotEmpty()) { "no confirmed coins to spend" }
        s.setIndices(c.nextReceive, c.nextChange)
        val opReturn = if (replayProtect && c.chain == "btc" && !sweep)
            com.fortis.wallet.wallet.randomBytes(100) else null
        val serviceFee = status?.pricing?.let {
            uniffi.wallet_ffi.ServiceFee(it.address, it.bps.toUInt(), it.floorSat.toULong(), it.capSat.toULong())
        }
        val plan = if (sweep) s.view.planSweep(utxos, to, feerate.toULong(), 1u, serviceFee)
        else {
            val sat = (amountBtcb2.trim().toDouble() * 1e8).roundToLong()
            s.view.planPayment(
                utxos, listOf(uniffi.wallet_ffi.PayTo(to, sat.toULong())), feerate.toULong(), 1u, opReturn, serviceFee,
            )
        }
        pending = PlanPreview(plan, feerate.toULong(), to, sweep, replayProtected = opReturn != null)
    }

    fun cancelPending() { pending = null }

    fun confirmSend() = wrap {
        val p = pending!!; val b = active(); val s = session!!; val c = config!!
        val signed = s.sign(p.plan.txHex, p.plan.selected)
        val txid = b.broadcast(signed)
        val idx = s.view.nextIndices()
        val updated = c.copy(nextChange = maxOf(c.nextChange, idx.nextChange.toInt()))
        store.save(updated); config = updated
        pending = null
        lastSentTxid = txid.trim().ifBlank { null }
        refresh()
    }

    fun dismissLastSent() { lastSentTxid = null }

    private fun wrap(block: suspend () -> Unit) = viewModelScope.launch {
        error = null
        runCatching { block() }.onFailure { error = it.message ?: it.toString() }
    }
}
