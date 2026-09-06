package com.fortis.wallet.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("fortis")

/** Persisted wallet config. `sealed` is the seed, already encrypted — safe at rest. */
data class WalletConfig(
    val chain: String,
    val network: String,
    val sealed: String,
    val salt: String,
    val nextReceive: Int = 0,
    val nextChange: Int = 0,
    val backendKind: String? = null,   // "esplora" | "gateway"
    val backendUrl: String? = null,
    val backendToken: String? = null,
)

class Store(private val ctx: Context) {
    private object K {
        val chain = stringPreferencesKey("chain")
        val network = stringPreferencesKey("network")
        val sealed = stringPreferencesKey("sealed")
        val salt = stringPreferencesKey("salt")
        val nextReceive = intPreferencesKey("next_receive")
        val nextChange = intPreferencesKey("next_change")
        val backendKind = stringPreferencesKey("backend_kind")
        val backendUrl = stringPreferencesKey("backend_url")
        val backendToken = stringPreferencesKey("backend_token")
    }

    suspend fun load(): WalletConfig? {
        val p = ctx.dataStore.data.first()
        val sealed = p[K.sealed] ?: return null
        return WalletConfig(
            chain = p[K.chain] ?: "btcb2",
            network = p[K.network] ?: "mainnet",
            sealed = sealed,
            salt = p[K.salt] ?: return null,
            nextReceive = p[K.nextReceive] ?: 0,
            nextChange = p[K.nextChange] ?: 0,
            backendKind = p[K.backendKind],
            backendUrl = p[K.backendUrl],
            backendToken = p[K.backendToken],
        )
    }

    suspend fun save(c: WalletConfig) {
        ctx.dataStore.edit { p ->
            p[K.chain] = c.chain
            p[K.network] = c.network
            p[K.sealed] = c.sealed
            p[K.salt] = c.salt
            p[K.nextReceive] = c.nextReceive
            p[K.nextChange] = c.nextChange
            c.backendKind?.let { p[K.backendKind] = it } ?: p.remove(K.backendKind)
            c.backendUrl?.let { p[K.backendUrl] = it } ?: p.remove(K.backendUrl)
            c.backendToken?.let { p[K.backendToken] = it } ?: p.remove(K.backendToken)
        }
    }

    suspend fun wipe() = ctx.dataStore.edit { it.clear() }
}
