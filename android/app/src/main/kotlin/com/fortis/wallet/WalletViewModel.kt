package com.fortis.wallet

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

enum class Phase { Loading, AppLock, WalletList, ManageWallets, Onboard, Gen, Create, Restore, Home, Settings }

/** How many wallets one install can hold. */
const val MAX_WALLETS = 10

/** The one backend the mobile app talks to. Not user-configurable, not shown. */
const val HOSTED_EDGE = "https://api.fortis.rest"

/** Public Esplora fallbacks, used when [HOSTED_EDGE] is unreachable. */
const val PUBLIC_BTC_ESPLORA = "https://mempool.space/api"
const val PUBLIC_BTCB2_ESPLORA = "https://mempool.guide/api"

data class PlanPreview(
    val plan: FundingPlan, val feerate: ULong, val to: String,
    val sweep: Boolean, val replayProtected: Boolean = false,
)

/** The result of setting up the app lock, produced by the UI for the first wallet. */
data class LockSetup(val mode: String, val secret: String, val appWrapped: String?)

class WalletViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val http = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY) // ignore any Wi-Fi/Studio proxy — local hosts must be direct
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var phase by mutableStateOf(Phase.Loading); private set

    // --- wallets ---
    var wallets by mutableStateOf<List<WalletConfig>>(emptyList()); private set
    var selectedId by mutableStateOf<String?>(null); private set
    private val sessions = mutableStateMapOf<String, WalletSession>()

    // --- app lock ---
    var lockMode by mutableStateOf<String?>(null); private set
    private var appWrapped: String? = null
    /** The seal secret for every wallet, held only while the app is unlocked. */
    private var appSecret by mutableStateOf<String?>(null)
    val locked: Boolean get() = appSecret == null && wallets.isNotEmpty()
    val appWrappedSecret: String? get() = appWrapped

    /** The wallet currently in view. */
    val config: WalletConfig? get() = wallets.firstOrNull { it.id == selectedId }
    val session: WalletSession? get() = selectedId?.let { sessions[it] }
    fun isUnlocked(id: String) = sessions.containsKey(id)

    // --- the selected wallet's backends + view state ---
    var backend: Backend? = null; private set
    private var fallback: Backend? = null
    private var usingFallback by mutableStateOf(false)

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

    init {
        viewModelScope.launch {
            val s = store.load()
            wallets = s.wallets
            selectedId = s.selectedId
            lockMode = s.lockMode
            appWrapped = s.appWrapped
            resolvePhase()
        }
    }

    private fun resolvePhase() {
        phase = when {
            wallets.isEmpty() -> Phase.Onboard
            appSecret == null -> Phase.AppLock
            selectedId == null -> Phase.WalletList
            ensureSession(selectedId!!) == null -> Phase.WalletList
            else -> { ensureBackend(); Phase.Home }
        }
        if (phase == Phase.Home) refresh()
    }

    /** Unseal a wallet with the in-memory app secret (no extra prompt). */
    private fun ensureSession(id: String): WalletSession? {
        sessions[id]?.let { return it }
        val secret = appSecret ?: return null
        val c = wallets.firstOrNull { it.id == id } ?: return null
        return try {
            val (mnemonic, passphrase) = unsealSeed(c.sealed, c.salt, secret)
            WalletSession(c.chain, c.network, mnemonic, passphrase).also {
                it.setIndices(c.nextReceive, c.nextChange)
                sessions[id] = it
            }
        } catch (e: Exception) {
            error = e.message ?: "could not open wallet"
            null
        }
    }

    /** Drop the in-view backend + derived state — call when the selected wallet changes. */
    private fun resetView() {
        backend = null; fallback = null; usingFallback = false
        status = null; balances = null; history = emptyList(); feerates = emptyMap()
        pending = null; lastSentTxid = null
    }

    // --- navigation ---

    fun goWalletList() { error = null; draftMnemonic = null; phase = Phase.WalletList }
    fun goManageWallets() { error = null; phase = Phase.ManageWallets }
    val canAddWallet: Boolean get() = wallets.size < MAX_WALLETS
    fun addWallet() {
        if (!canAddWallet) { error = "You can keep up to $MAX_WALLETS wallets on one device."; return }
        error = null; draftMnemonic = null; phase = Phase.Onboard
    }
    fun cancelOnboard() {
        draftMnemonic = null
        phase = if (wallets.isEmpty()) Phase.Onboard else Phase.WalletList
    }

    fun goCreate() { phase = if (draftMnemonic != null) Phase.Create else Phase.Gen }
    fun generateSeed(extra: ByteArray, words: Int) = wrap {
        draftMnemonic = newMnemonic(extra, words)
        phase = Phase.Create
    }
    fun goRestore() { phase = Phase.Restore }
    fun goSettings() { phase = Phase.Settings }
    fun goHome() { resolvePhase() }

    /** Is the first wallet still to be made? (The app lock is set up alongside it.) */
    val settingUp: Boolean get() = wallets.isEmpty()

    fun selectWallet(id: String) {
        if (id != selectedId) {
            selectedId = id
            viewModelScope.launch { store.setSelected(id) }
            resetView()
        }
        resolvePhase()
    }

    // --- app unlock ---

    /** Biometric mode: the UI unwrapped the app secret via the Keystore. */
    fun appUnlockWithSecret(secret: String) = wrap {
        appSecret = secret
        resolvePhase()
    }

    /** Password mode: verify against a wallet, then hold the secret. */
    fun appUnlockWithPassword(pw: String) = wrap {
        val c = config ?: wallets.first()
        unsealSeed(c.sealed, c.salt, pw) // throws on a wrong password
        appSecret = pw
        resolvePhase()
    }

    /** Lock the whole app — clears every seed and the app secret from memory. */
    fun lock() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        appSecret = null
        resetView()
        phase = if (wallets.isEmpty()) Phase.Onboard else Phase.AppLock
    }

    // --- create / restore ---

    fun createWallet(name: String, chain: String, network: String, passphrase: String, lock: LockSetup? = null) = wrap {
        finishOnboard(name, chain, network, draftMnemonic!!, passphrase, lock)
    }

    fun restoreWallet(name: String, phrase: String, passphrase: String, chain: String, network: String, lock: LockSetup? = null) = wrap {
        val words = phrase.trim().split(Regex("\\s+")).joinToString(" ")
        finishOnboard(name, chain, network, words, passphrase, lock)
    }

    private suspend fun finishOnboard(
        name: String, chain: String, network: String,
        mnemonic: String, passphrase: String, lock: LockSetup?,
    ) {
        if (settingUp) {
            requireNotNull(lock) { "the first wallet sets up the app lock" }
            appSecret = lock.secret
            appWrapped = lock.appWrapped
            lockMode = lock.mode
            store.setLock(lock.mode, lock.appWrapped)
        }
        val secret = appSecret ?: error("the app is locked")
        val s = WalletSession(chain, network, mnemonic, passphrase)
        val sealed = sealSeed(mnemonic, passphrase, secret)
        val id = UUID.randomUUID().toString()
        val c = WalletConfig(id, name.trim().ifBlank { "Wallet" }, chain, network, sealed.blobHex, sealed.saltHex)
        store.save(c); store.setSelected(id)
        wallets = wallets + c
        selectedId = id
        resetView()
        sessions[id] = s
        draftMnemonic = null
        resolvePhase()
    }

    // --- manage ---

    fun renameWallet(id: String, name: String) = wrap {
        val clean = name.trim()
        if (clean.isNotEmpty()) updateConfig(id) { it.copy(name = clean) }
    }

    /** Add a wallet to the other chain under the same name (same seed, same
     *  addresses). Doesn't change the selection. */
    fun cloneToOtherChain(id: String) = wrap {
        val c = wallets.firstOrNull { it.id == id } ?: return@wrap
        if (!canAddWallet) { error = "You can keep up to $MAX_WALLETS wallets on one device."; return@wrap }
        if (wallets.any { it.name == c.name && it.chain == c.otherChain }) {
            error = "“${c.name}” is already on ${c.otherChain.uppercase()}."
            return@wrap
        }
        val clone = c.copy(id = UUID.randomUUID().toString(), chain = c.otherChain, backendToken = null)
        store.save(clone)
        wallets = wallets + clone
    }

    /** Remove a wallet from the app. The seed is not destroyed — its recovery
     *  phrase still restores it. Stays on the current screen unless the wallet
     *  in view was the one removed. */
    fun removeWallet(id: String) = viewModelScope.launch {
        val wasCurrent = id == selectedId
        sessions.remove(id)?.close()
        val remaining = store.remove(id)
        wallets = remaining.wallets
        selectedId = remaining.selectedId
        lockMode = remaining.lockMode
        appWrapped = remaining.appWrapped
        if (wallets.isEmpty()) {
            appSecret = null
            com.fortis.wallet.data.SeedKeystore.deleteKey()
            resetView()
            resolvePhase()
        } else if (wasCurrent) {
            resetView()
            resolvePhase()
        }
    }

    // --- backend ---

    /** Drop the stored token and mint a fresh one (Settings → Reconnect). */
    fun reconnect() = wrap {
        val id = selectedId ?: return@wrap
        val token = edgeRegister(http, HOSTED_EDGE)
        updateConfig(id) { it.copy(backendToken = token) }
        backend = edgeBackend(token)
        usingFallback = false
        backend!!.status()
        status = null; balances = null; history = emptyList()
        resolvePhase()
    }

    /** The hosted fortis-edge as an Esplora backend at `{HOSTED_EDGE}/{chain}`. */
    private fun edgeBackend(token: String): EsploraBackend {
        val c = config!!
        val id = c.id
        return EsploraBackend(
            http, "$HOSTED_EDGE/${c.chain}", session!!.view,
            { (wallets.firstOrNull { it.id == id } ?: c).let { w -> w.nextReceive to w.nextChange } },
            token,
            "$HOSTED_EDGE/pricing",
        ) {
            val fresh = edgeRegister(http, HOSTED_EDGE)
            updateConfig(id) { it.copy(backendToken = fresh) }
            fresh
        }
    }

    private suspend fun updateConfig(id: String, f: (WalletConfig) -> WalletConfig) {
        val cur = wallets.firstOrNull { it.id == id } ?: return
        val next = f(cur)
        store.save(next)
        wallets = wallets.map { if (it.id == id) next else it }
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
        runCatching { load(edge, false) }
            .recoverCatching { e -> fallback?.let { load(it, true) } ?: throw e }
            .onFailure { status = null }
    }

    fun newReceiveAddress() = viewModelScope.launch {
        val id = selectedId ?: return@launch
        updateConfig(id) { it.copy(nextReceive = it.nextReceive + 1) }
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
        updateConfig(c.id) { it.copy(nextChange = maxOf(it.nextChange, idx.nextChange.toInt())) }
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
