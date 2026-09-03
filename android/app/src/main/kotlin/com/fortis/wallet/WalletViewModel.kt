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

enum class Phase { Loading, Onboard, Create, Restore, Locked, BackendPicker, Home, Settings }

data class PlanPreview(val plan: FundingPlan, val feerate: ULong, val to: String, val sweep: Boolean)

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
    var backend: Backend? = null; private set

    var draftMnemonic by mutableStateOf<String?>(null); private set
    var status by mutableStateOf<ChainStatus?>(null); private set
    var balances by mutableStateOf<Balances?>(null); private set
    var history by mutableStateOf<List<HistoryEntry>>(emptyList()); private set
    var feerates by mutableStateOf<Map<Int, Long>>(emptyMap()); private set
    var pending by mutableStateOf<PlanPreview?>(null); private set
    var error by mutableStateOf<String?>(null)

    init { viewModelScope.launch { config = store.load(); resolvePhase() } }

    private fun resolvePhase() {
        phase = when {
            config == null -> Phase.Onboard
            session == null -> Phase.Locked
            config!!.backendKind == null -> Phase.BackendPicker
            else -> { ensureBackend(); Phase.Home }
        }
        if (phase == Phase.Home) refresh()
    }

    fun goCreate() { draftMnemonic = draftMnemonic ?: newMnemonic(); phase = Phase.Create }
    fun goRestore() { phase = Phase.Restore }
    fun goOnboard() { draftMnemonic = null; phase = Phase.Onboard }
    fun goSettings() { phase = Phase.Settings }
    fun goHome() { resolvePhase() }

    /** Forget the current backend and return to the picker (wallet + seed kept). */
    fun changeBackend() = viewModelScope.launch {
        val c = config ?: return@launch
        val updated = c.copy(backendKind = null, backendUrl = null, backendToken = null)
        store.save(updated); config = updated; backend = null
        status = null; balances = null; history = emptyList()
        resolvePhase()
    }

    fun createWallet(chain: String, network: String, password: String) = wrap {
        finishOnboard(chain, network, draftMnemonic!!, "", password)
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

    fun lock() { session?.close(); session = null; backend = null; phase = Phase.Locked }

    fun wipe() = viewModelScope.launch {
        store.wipe(); session?.close(); session = null; backend = null; config = null; phase = Phase.Onboard
    }

    fun useEsplora(url: String) = wrap {
        val b = EsploraBackend(http, url, session!!.view) { config!!.nextReceive to config!!.nextChange }
        b.status() // probe
        persistBackend("esplora", url, null)
        backend = b; resolvePhase()
    }

    fun useGateway(url: String, token: String) = wrap {
        val b = GatewayBackend(http, url, token)
        b.status()
        b.connect(config!!.chain, config!!.network, session!!.xpub, session!!.fingerprint)
        persistBackend("gateway", url, token)
        backend = b; resolvePhase()
    }

    private suspend fun persistBackend(kind: String, url: String, token: String?) {
        val c = config!!.copy(backendKind = kind, backendUrl = url, backendToken = token)
        store.save(c); config = c
    }

    private fun ensureBackend() {
        if (backend != null) return
        val c = config ?: return
        val s = session ?: return
        backend = when (c.backendKind) {
            "gateway" -> GatewayBackend(http, c.backendUrl!!, c.backendToken ?: "")
            else -> EsploraBackend(http, c.backendUrl!!, s.view) { c.nextReceive to c.nextChange }
        }
    }

    fun refresh() = viewModelScope.launch {
        val b = backend ?: return@launch
        runCatching {
            status = b.status()
            balances = b.balances()
            history = b.history(50)
            if (feerates.isEmpty()) feerates = listOf(1, 6, 144).associateWith { b.feerateSatVb(it).toLong() }
        }.onFailure { status = null }
    }

    fun newReceiveAddress() = viewModelScope.launch {
        val c = config ?: return@launch
        val updated = c.copy(nextReceive = c.nextReceive + 1)
        store.save(updated); config = updated
    }

    fun buildPayment(to: String, amountBlk: String, sweep: Boolean, feerateOverride: Long?, confTarget: Int) = wrap {
        val b = backend!!; val s = session!!; val c = config!!
        val feerate = (feerateOverride ?: b.feerateSatVb(confTarget).toLong()).coerceAtLeast(1)
        val utxos = b.utxos(1u)
        require(utxos.isNotEmpty()) { "no confirmed coins to spend" }
        s.setIndices(c.nextReceive, c.nextChange)
        val plan = if (sweep) s.view.planSweep(utxos, to, feerate.toULong(), 1u)
        else {
            val sat = (amountBlk.trim().toDouble() * 1e8).roundToLong()
            s.view.planPayment(utxos, listOf(uniffi.wallet_ffi.PayTo(to, sat.toULong())), feerate.toULong(), 1u)
        }
        pending = PlanPreview(plan, feerate.toULong(), to, sweep)
    }

    fun cancelPending() { pending = null }

    fun confirmSend() = wrap {
        val p = pending!!; val b = backend!!; val s = session!!; val c = config!!
        val signed = s.sign(p.plan.txHex, p.plan.selected)
        b.broadcast(signed)
        val idx = s.view.nextIndices()
        val updated = c.copy(nextChange = maxOf(c.nextChange, idx.nextChange.toInt()))
        store.save(updated); config = updated
        pending = null
        refresh()
    }

    private fun wrap(block: suspend () -> Unit) = viewModelScope.launch {
        error = null
        runCatching { block() }.onFailure { error = it.message ?: it.toString() }
    }
}
