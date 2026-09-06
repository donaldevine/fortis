package com.fortis.wallet.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import androidx.datastore.preferences.preferencesDataStore

private val Context.dataStore by preferencesDataStore("fortis")

/** One wallet on this device. `sealed` is the seed, already encrypted — safe at
 *  rest. Each wallet has its own seed, passphrase, encryption password and
 *  per-install edge token; they're fully independent. */
data class WalletConfig(
    val id: String,
    val name: String,
    val chain: String,
    val network: String,
    val sealed: String,
    val salt: String,
    val nextReceive: Int = 0,
    val nextChange: Int = 0,
    /** The per-install token for the hosted edge. */
    val backendToken: String? = null,
) {
    /** e.g. `BTCB2 · Savings` — the label the picker shows. */
    val display: String get() = "${chain.uppercase()} · $name"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("chain", chain); put("network", network)
        put("sealed", sealed); put("salt", salt)
        put("next_receive", nextReceive); put("next_change", nextChange)
        backendToken?.let { put("token", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = WalletConfig(
            id = o.getString("id"),
            name = o.optString("name", "Wallet"),
            chain = o.optString("chain", "btcb2"),
            network = o.optString("network", "mainnet"),
            sealed = o.getString("sealed"),
            salt = o.getString("salt"),
            nextReceive = o.optInt("next_receive", 0),
            nextChange = o.optInt("next_change", 0),
            backendToken = o.optString("token").ifBlank { null },
        )
    }
}

/** The whole persisted picture: every wallet plus which one is selected. */
data class WalletState(val wallets: List<WalletConfig>, val selectedId: String?)

class Store(private val ctx: Context) {
    private object K {
        val wallets = stringPreferencesKey("wallets")
        val selected = stringPreferencesKey("selected")
        // legacy single-wallet keys (pre multi-wallet) — migrated on first load
        val chain = stringPreferencesKey("chain")
        val network = stringPreferencesKey("network")
        val sealed = stringPreferencesKey("sealed")
        val salt = stringPreferencesKey("salt")
        val nextReceive = intPreferencesKey("next_receive")
        val nextChange = intPreferencesKey("next_change")
        val backendToken = stringPreferencesKey("backend_token")
        val backendKind = stringPreferencesKey("backend_kind")
        val backendUrl = stringPreferencesKey("backend_url")
    }

    suspend fun load(): WalletState {
        val p = ctx.dataStore.data.first()

        p[K.wallets]?.let { raw ->
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).map { WalletConfig.fromJson(arr.getJSONObject(it)) }
            val sel = p[K.selected]?.takeIf { id -> list.any { it.id == id } }
            return WalletState(list, sel ?: list.firstOrNull()?.id)
        }

        // Migrate a legacy single wallet into the list, then drop the old keys.
        val legacySealed = p[K.sealed]
        val legacySalt = p[K.salt]
        if (legacySealed != null && legacySalt != null) {
            val w = WalletConfig(
                id = UUID.randomUUID().toString(),
                name = "Wallet",
                chain = p[K.chain] ?: "btcb2",
                network = p[K.network] ?: "mainnet",
                sealed = legacySealed,
                salt = legacySalt,
                nextReceive = p[K.nextReceive] ?: 0,
                nextChange = p[K.nextChange] ?: 0,
                backendToken = p[K.backendToken],
            )
            ctx.dataStore.edit { e ->
                e[K.wallets] = JSONArray().put(w.toJson()).toString()
                e[K.selected] = w.id
                listOf(K.chain, K.network, K.sealed, K.salt, K.backendToken, K.backendKind, K.backendUrl)
                    .forEach { e.remove(it) }
                e.remove(K.nextReceive); e.remove(K.nextChange)
            }
            return WalletState(listOf(w), w.id)
        }

        return WalletState(emptyList(), null)
    }

    /** Insert or replace a wallet by id. */
    suspend fun save(w: WalletConfig) = ctx.dataStore.edit { p ->
        val list = readList(p[K.wallets]).filter { it.id != w.id } + w
        p[K.wallets] = encode(list)
    }

    suspend fun setSelected(id: String?) = ctx.dataStore.edit { p ->
        if (id == null) p.remove(K.selected) else p[K.selected] = id
    }

    /** Forget a single wallet. Returns the state that remains. */
    suspend fun remove(id: String): WalletState {
        lateinit var out: WalletState
        ctx.dataStore.edit { p ->
            val list = readList(p[K.wallets]).filter { it.id != id }
            p[K.wallets] = encode(list)
            val sel = p[K.selected]?.takeIf { s -> list.any { it.id == s } } ?: list.firstOrNull()?.id
            if (sel == null) p.remove(K.selected) else p[K.selected] = sel
            out = WalletState(list, sel)
        }
        return out
    }

    suspend fun wipeAll() = ctx.dataStore.edit { it.clear() }

    private fun readList(raw: String?): List<WalletConfig> {
        raw ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { WalletConfig.fromJson(arr.getJSONObject(it)) }
    }

    private fun encode(list: List<WalletConfig>) =
        JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
}
