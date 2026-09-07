package com.fortis.wallet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fortis.wallet.NavTab
import com.fortis.wallet.PlanPreview
import com.fortis.wallet.WalletViewModel
import com.fortis.wallet.ui.*
import com.fortis.wallet.ui.theme.Fx
import com.fortis.wallet.wallet.EntropyCollector
import com.fortis.wallet.wallet.entropyProgress
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun fmt(sat: Long) = "%.8f".format(sat / 1e8)

/** Parse what the user typed in the Amount field into satoshis.
 *  [sat] true → a plain integer number of sats; false → a decimal coin amount
 *  (BTC / BTCB2). Returns null for anything unparseable or negative. */
private fun amountToSat(text: String, sat: Boolean): Long? = runCatching {
    val t = text.trim().replace(",", "").replace("_", "").replace(" ", "")
    when {
        t.isEmpty() -> null
        sat -> t.toLong().takeIf { it >= 0 }
        else -> t.toBigDecimal()
            .movePointRight(8)
            .setScale(0, java.math.RoundingMode.DOWN)
            .longValueExact()
            .takeIf { it >= 0 }
    }
}.getOrNull()

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
fun OnboardScreen(vm: WalletViewModel) = Screen {
    val adding = vm.wallets.isNotEmpty()
    Spacer(Modifier.weight(1f))
    if (adding) Text("Add a wallet", style = MaterialTheme.typography.titleMedium, color = Fx.text)
    else BrandMark("a non-custodial wallet for Bitcoin and its BLAKE2b fork")
    Spacer(Modifier.weight(1f))
    PrimaryButton("Create a new wallet") { vm.goCreate() }
    GhostButton("Restore from a recovery phrase") { vm.goRestore() }
    if (adding) GhostButton("Cancel", tint = Fx.textDim) { vm.cancelOnboard() }
    Spacer(Modifier.weight(1f))
}

@Composable
fun GenScreen(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val collector = remember { EntropyCollector() }
    var bits by remember { mutableStateOf(0) }
    var words by remember { mutableStateOf(24) }

    // Motion-sensor noise while the user shakes the phone — the novel source.
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                collector.addMotion(e.values)
                bits = collector.bits
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE).forEach { t ->
            sm.getDefaultSensor(t)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        onDispose { sm.unregisterListener(listener) }
    }
    // Passive timing jitter — no interaction needed.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { collector.addJitter() }
        bits = collector.bits
    }

    Screen(scroll = true) {
        Text("Add some randomness", style = MaterialTheme.typography.titleMedium, color = Fx.text)
        Text(
            "Your phone already generated a secure seed. Shake it — or scribble on the pad — " +
                "to stir in extra entropy from the motion sensors and timing jitter. Belt and braces.",
            color = Fx.textDim,
        )
        Segmented(listOf("24" to "24 words", "12" to "12 words"), words.toString(), { words = it.toInt() })
        Text("Both are secure; 12 is easier to write down.", color = Fx.textFaint, fontSize = 12.sp)
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(Fx.rLg)).background(Fx.glass1)
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        collector.addTouch(change.position.x, change.position.y, System.nanoTime())
                        bits = collector.bits
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("shake, or scribble here", color = Fx.textFaint, fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(Fx.pill)).background(Fx.glass1)) {
            Box(
                Modifier.fillMaxWidth(entropyProgress(bits)).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Fx.accent, Fx.accent2))),
            )
        }
        Text(
            if (entropyProgress(bits) >= 1f) "plenty of extra entropy — the base seed is already secure"
            else "~$bits extra bits stirred in",
            color = Fx.textFaint, fontSize = 12.sp,
        )
        ErrorText(vm.error)
        Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
            GhostButton("Back", Modifier.weight(1f)) { vm.cancelOnboard() }
            PrimaryButton("Generate wallet", Modifier.weight(1f)) { vm.generateSeed(collector.bytes(), words) }
        }
    }
}

@Composable
fun CreateScreen(vm: WalletViewModel) {
    var name by remember { mutableStateOf("") }
    var chain by remember { mutableStateOf("btcb2") }
    var passphrase by remember { mutableStateOf("") }
    var ack by remember { mutableStateOf(false) }
    val words = (vm.draftMnemonic ?: "").split(" ")
    val settingUp = vm.settingUp
    val choice = rememberLockChoice()
    val act = rememberFragmentActivity()
    val scope = rememberCoroutineScope()

    Screen(scroll = true) {
        Text("Your recovery phrase", style = MaterialTheme.typography.titleMedium, color = Fx.text)
        Text("Write these ${words.size} words on paper, offline. Anyone with them controls your funds.", color = Fx.textDim)
        GlassCard { PhraseGrid(words) }
        Field(name, { name = it }, "Wallet name")
        ChainRow(chain) { chain = it }
        Field(passphrase, { passphrase = it }, "BIP-39 passphrase (optional)", password = true)
        Text(
            "A \"25th word\" — an extra secret, not stored. If you set one you need both " +
                "the phrase and this to restore. Leave blank if unsure.",
            color = Fx.textFaint, fontSize = 12.sp,
        )
        if (settingUp) LockChoiceFields(choice)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ack, { ack = it })
            Text("I've written the phrase down", color = Fx.text)
        }
        ErrorText(vm.error)
        Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
            GhostButton("Back", Modifier.weight(1f)) { vm.cancelOnboard() }
            PrimaryButton("Continue", Modifier.weight(1f), enabled = ack && (!settingUp || choice.ready)) {
                scope.launch {
                    val lock = if (settingUp) {
                        runCatching { choice.resolve(act) }
                            .getOrElse { vm.error = it.message ?: "app-lock setup failed"; null } ?: return@launch
                    } else null
                    vm.createWallet(name, chain, "mainnet", passphrase, lock)
                }
            }
        }
    }
}

@Composable
fun RestoreScreen(vm: WalletViewModel) {
    var name by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var chain by remember { mutableStateOf("btcb2") }
    val settingUp = vm.settingUp
    val choice = rememberLockChoice()
    val act = rememberFragmentActivity()
    val scope = rememberCoroutineScope()
    Screen(scroll = true) {
        Text("Restore wallet", style = MaterialTheme.typography.titleMedium, color = Fx.text)
        Text("Enter your 12 or 24 words, separated by spaces.", color = Fx.textDim)
        Field(name, { name = it }, "Wallet name")
        MnemonicField(phrase, { phrase = it })
        Field(passphrase, { passphrase = it }, "BIP-39 passphrase (optional)", password = true)
        ChainRow(chain) { chain = it }
        Text(
            "After it's added you can also list this wallet on ${if (chain == "btc") "BTCB2" else "BTC"} " +
                "from Manage wallets — same phrase, same addresses.",
            color = Fx.textFaint, fontSize = 12.sp,
        )
        if (settingUp) LockChoiceFields(choice)
        ErrorText(vm.error)
        Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
            GhostButton("Back", Modifier.weight(1f)) { vm.cancelOnboard() }
            PrimaryButton("Restore", Modifier.weight(1f), enabled = phrase.isNotBlank() && (!settingUp || choice.ready)) {
                scope.launch {
                    val lock = if (settingUp) {
                        runCatching { choice.resolve(act) }
                            .getOrElse { vm.error = it.message ?: "app-lock setup failed"; null } ?: return@launch
                    } else null
                    vm.restoreWallet(name, phrase, passphrase, chain, "mainnet", lock)
                }
            }
        }
    }
}

@Composable
private fun ChainRow(chain: String, onChain: (String) -> Unit) {
    Segmented(listOf("btcb2" to "BTCB2", "btc" to "BTC"), chain, onChain)
}

/** Numbered 3-column grid of mnemonic words. Caller supplies the surrounding card. */
@Composable
fun PhraseGrid(words: List<String>) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    words.chunked(3).forEachIndexed { row, three ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            three.forEachIndexed { col, w ->
                Text(
                    "${row * 3 + col + 1}  $w", color = Fx.text,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.weight(1f).background(Fx.glass1, RoundedCornerShape(8.dp)).padding(6.dp),
                )
            }
        }
    }
}

/** Screenshot-blocked view of one wallet's recovery phrase + passphrase. */
@Composable
fun RevealSeedScreen(vm: WalletViewModel) {
    val id = vm.revealingId ?: return
    val w = vm.wallets.firstOrNull { it.id == id }
    var seed by remember(id) { mutableStateOf<Pair<List<String>, String>?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        seed = runCatching { vm.seedFor(id) }.getOrNull()
        if (seed == null) failed = true
    }
    Screen(scroll = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.closeReveal() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = Fx.text) }
            Text("Recovery phrase", style = MaterialTheme.typography.titleMedium, color = Fx.text)
        }
        if (w != null) Text(w.display, color = Fx.textDim)
        Text(
            "Anyone who sees these words — plus the passphrase, if set — can spend this wallet. " +
                "Only look at them somewhere private. Never type them into a website or app. " +
                "Screenshots are blocked here.",
            color = Fx.bad, fontSize = 13.sp,
        )
        when {
            failed -> Text("Couldn't decrypt this wallet.", color = Fx.bad)
            seed == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            else -> {
                val (words, passphrase) = seed!!
                GlassCard { PhraseGrid(words) }
                if (passphrase.isNotEmpty()) {
                    Text("BIP-39 passphrase", color = Fx.textDim, style = MaterialTheme.typography.labelMedium)
                    Text(
                        passphrase, color = Fx.text, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Fx.rSm)).background(Fx.glass1).padding(Fx.s4),
                    )
                } else {
                    Text("No BIP-39 passphrase on this wallet.", color = Fx.textFaint, fontSize = 12.sp)
                }
            }
        }
        PrimaryButton("Done") { vm.closeReveal() }
    }
}

@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.background(Fx.glass1, RoundedCornerShape(Fx.pill)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(Fx.pill))
                    .background(if (on) Brush.linearGradient(listOf(Fx.accent, Fx.accent2)) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onSelect(v) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) Color(0xFF0A0C16) else Color.White.copy(alpha = 0.75f), fontSize = 13.sp) }
        }
    }
}

/**
 * The unlocked app: a persistent top nav bar (Home · Wallet · Settings) over the
 * three tab bodies.
 */
@Composable
fun Shell(vm: WalletViewModel) {
    val unit = if (vm.config?.chain == "btc") "BTC" else "BTCB2"
    LaunchedEffect(vm.nav, vm.wallets.map { it.id }) {
        while (vm.nav == NavTab.Home || vm.nav == NavTab.Settings) {
            vm.refreshAllBalances()
            kotlinx.coroutines.delay(45_000)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Column(
            Modifier.fillMaxSize().widthIn(max = 460.dp).align(Alignment.TopCenter)
                .systemBarsPadding().imePadding().padding(horizontal = Fx.s4).padding(top = Fx.s3),
            verticalArrangement = Arrangement.spacedBy(Fx.s3),
        ) {
            NavBar(vm.nav) { vm.go(it) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (vm.nav) {
                    NavTab.Home -> HomeTab(vm)
                    NavTab.Wallet -> WalletTab(vm)
                    NavTab.Settings -> SettingsTab(vm)
                }
            }
        }
    }
    vm.pending?.let { ConfirmSheet(vm, it, unit) }
}

@Composable
private fun NavBar(current: NavTab, onSelect: (NavTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Fx.glass1, RoundedCornerShape(Fx.pill)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(NavTab.Home to "Home", NavTab.Wallet to "Wallet", NavTab.Settings to "Settings").forEach { (t, label) ->
            val on = current == t
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(Fx.pill))
                    .background(
                        if (on) Brush.linearGradient(listOf(Fx.accent, Fx.accent2))
                        else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                    )
                    .clickable { onSelect(t) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) Color(0xFF0A0C16) else Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** Home tab — the list of wallets; tap one to open it. */
@Composable
private fun HomeTab(vm: WalletViewModel) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Fx.s2),
    verticalArrangement = Arrangement.spacedBy(Fx.s4),
) {
    Text("Your wallets", style = MaterialTheme.typography.titleMedium, color = Fx.text)
    GlassCard {
        vm.wallets.forEach { w ->
            val current = w.id == vm.selectedId
            val wUnit = if (w.chain == "btc") "BTC" else "BTCB2"
            val bal = vm.walletBalances[w.id]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Fx.rSm))
                    .background(if (current) Fx.glass2 else Fx.glass1)
                    .clickable { vm.selectWallet(w.id) }.padding(Fx.s3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(w.display, color = Fx.text, fontWeight = FontWeight.Medium)
                    Text(
                        bal?.let { "${fmt(it)} $wUnit" } ?: if (current) "open" else "tap to open",
                        color = if (bal != null) Fx.textDim else Fx.textFaint,
                        fontFamily = if (bal != null) FontFamily.Monospace else null,
                        fontSize = 12.sp,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Fx.textDim)
            }
        }
    }
    if (vm.canAddWallet) PrimaryButton("Add wallet") { vm.addWallet() }
    else Text("Maximum of ${com.fortis.wallet.MAX_WALLETS} wallets reached.", color = Fx.textFaint, fontSize = 12.sp)
    ErrorText(vm.error)
}

/** The one gate into the app — fingerprint / device PIN, or a password. */
@Composable
fun AppLockScreen(vm: WalletViewModel) {
    var pw by remember { mutableStateOf("") }
    Screen {
        Spacer(Modifier.weight(1f))
        BrandMark()
        GlassCard {
            if (vm.lockMode == com.fortis.wallet.data.LOCK_BIOMETRIC) {
                BiometricAppUnlock(vm)
            } else {
                Text("Enter your app password.", color = Fx.textDim)
                Field(pw, { pw = it }, "App password", password = true)
                ErrorText(vm.error)
                PrimaryButton("Unlock", enabled = pw.isNotEmpty()) { vm.appUnlockWithPassword(pw) }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Settings tab — manage wallets, security, connection. */
@Composable
private fun SettingsTab(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val st = vm.status
    var renaming by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var revealWarn by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Fx.s2),
        verticalArrangement = Arrangement.spacedBy(Fx.s4),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleMedium, color = Fx.text)

        GlassCard {
            Text("Wallets", color = Fx.text, fontWeight = FontWeight.SemiBold)
            vm.wallets.forEach { w ->
                val current = w.id == vm.selectedId
                val wUnit = if (w.chain == "btc") "BTC" else "BTCB2"
                val bal = vm.walletBalances[w.id]
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Fx.rSm))
                        .background(if (current) Fx.glass2 else Fx.glass1).padding(Fx.s3),
                    verticalArrangement = Arrangement.spacedBy(Fx.s2),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.display, color = Fx.text, fontWeight = FontWeight.Medium)
                            Text(
                                bal?.let { "${fmt(it)} $wUnit" }
                                    ?: listOfNotNull(w.network.takeIf { it != "mainnet" }, if (current) "open" else null)
                                        .joinToString(" · ").ifEmpty { "…" },
                                color = if (bal != null) Fx.textDim else Fx.textFaint,
                                fontFamily = if (bal != null) FontFamily.Monospace else null,
                                fontSize = 12.sp,
                            )
                        }
                        if (!current) TextButton({ vm.selectWallet(w.id) }) { Text("Open", color = Fx.accent) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
                        GhostButton("Rename", Modifier.weight(1f), dense = true) { renaming = w.id }
                        GhostButton("Copy xpub", Modifier.weight(1f), dense = true) {
                            val xpub = vm.accountKey(w.id)
                            if (xpub != null) copyToClipboard(ctx, "xpub", xpub)
                            else Toast.makeText(ctx, "still loading — try again", Toast.LENGTH_SHORT).show()
                        }
                    }
                    GhostButton("Recovery phrase", dense = true) { revealWarn = w.id }
                    val hasOther = vm.wallets.any { it.name == w.name && it.chain == w.otherChain }
                    if (!hasOther && vm.canAddWallet) GhostButton("Also add on ${w.otherChain.uppercase()}", dense = true) {
                        vm.cloneToOtherChain(w.id)
                    }
                    GhostButton("Remove", tint = Fx.bad, dense = true) { removing = w.id }
                }
            }
            if (vm.canAddWallet) PrimaryButton("Add wallet", dense = true) { vm.addWallet() }
            else Text("Maximum of ${com.fortis.wallet.MAX_WALLETS} wallets reached.", color = Fx.textFaint, fontSize = 12.sp)
            ErrorText(vm.error)
        }

        GlassCard {
            Text("Security", color = Fx.text, fontWeight = FontWeight.SemiBold)
            kv("App lock", if (vm.lockMode == com.fortis.wallet.data.LOCK_BIOMETRIC) "Fingerprint / device PIN" else "Password")
            Text("One unlock opens every wallet in the app.", color = Fx.textFaint, fontSize = 12.sp)
        }

        GlassCard {
            Text("Connection", color = Fx.text, fontWeight = FontWeight.SemiBold)
            kv("Status", when {
                st == null -> "offline"
                st.scanningPct != null -> "rescanning ${st.scanningPct}%"
                st.degraded -> "limited service"
                st.synced -> "connected"
                else -> "syncing"
            })
            kv("Chain height", st?.blocks?.toString() ?: "—")
            if (st?.degraded == true) Text(
                "The fortis service is unreachable — using public block data for now. " +
                    "It'll switch back automatically.",
                color = Fx.textFaint, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
                GhostButton("Refresh", Modifier.weight(1f), dense = true) { vm.refresh() }
                GhostButton("Reconnect", Modifier.weight(1f), dense = true) { vm.reconnect() }
            }
        }

        GhostButton("Lock app", tint = Fx.bad, dense = true) { vm.lock() }
        Text("fortis 0.1.0", color = Fx.textFaint, fontSize = 11.sp)
    }

    renaming?.let { id ->
        val w = vm.wallets.firstOrNull { it.id == id }
        var text by remember(id) { mutableStateOf(w?.name ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = Fx.bg1,
            title = { Text("Rename wallet", color = Fx.text) },
            text = { Field(text, { text = it }, "Wallet name") },
            confirmButton = { TextButton({ vm.renameWallet(id, text); renaming = null }) { Text("Save", color = Fx.accent) } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel", color = Fx.text) } },
        )
    }

    revealWarn?.let { id ->
        val w = vm.wallets.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { revealWarn = null },
            containerColor = Fx.bg1,
            title = { Text("Show ${w?.display ?: "wallet"}'s recovery phrase?", color = Fx.text) },
            text = {
                Text(
                    "The recovery phrase (and passphrase, if you set one) is the only thing " +
                        "that can restore this wallet — and anyone who has it can spend your " +
                        "funds. Only reveal it somewhere private, with no one watching. The " +
                        "next screen blocks screenshots.",
                    color = Fx.textDim,
                )
            },
            confirmButton = { TextButton({ vm.startReveal(id); revealWarn = null }) { Text("Show", color = Fx.accent) } },
            dismissButton = { TextButton({ revealWarn = null }) { Text("Cancel", color = Fx.text) } },
        )
    }

    removing?.let { id ->
        val w = vm.wallets.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Fx.bg1,
            title = { Text("Remove ${w?.display ?: "wallet"}?", color = Fx.text) },
            text = {
                Text(
                    "This only removes the wallet from this app. Nothing on the blockchain " +
                        "changes, and you can add it back any time with its recovery phrase " +
                        "(and passphrase, if you set one). Make sure the phrase is written down.",
                    color = Fx.textDim,
                )
            },
            confirmButton = { TextButton({ vm.removeWallet(id); removing = null }) { Text("Remove", color = Fx.bad) } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel", color = Fx.text) } },
        )
    }
}

/** Wallet tab — the selected wallet's balance, Receive / Send / History. */
@Composable
private fun WalletTab(vm: WalletViewModel) {
    val c = vm.config
    if (c == null) {
        Column(
            Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No wallet open", color = Fx.textDim)
            Spacer(Modifier.height(Fx.s3))
            GhostButton("Choose a wallet", Modifier.widthIn(max = 240.dp)) { vm.goHome() }
        }
        return
    }
    var tab by remember { mutableStateOf(0) }
    val unit = if (c.chain == "btc") "BTC" else "BTCB2"
    val b = vm.balances

    LaunchedEffect(vm.selectedId) {
        while (true) {
            vm.refresh()
            kotlinx.coroutines.delay(20_000)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(top = Fx.s2),
        verticalArrangement = Arrangement.spacedBy(Fx.s4),
    ) {
        GlassCard(fill = Fx.glassHero, corner = Fx.rLg) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(c.display, color = Fx.textDim, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (b != null) fmt(b.confirmedSat) else "—",
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 32.sp,
                            color = Fx.text)
                        Spacer(Modifier.width(6.dp))
                        Text(unit, color = Fx.textDim, fontSize = 12.sp)
                    }
                    if (b != null && b.pendingSat != 0L) Text(
                        "${if (b.pendingSat > 0) "+" else ""}${fmt(b.pendingSat)} $unit pending",
                        color = Fx.warn, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                    val hint = vm.status?.let {
                        val head = when {
                            it.scanningPct != null -> "rescanning ${it.scanningPct}%"
                            it.synced -> "block ${it.blocks}"
                            else -> "syncing"
                        }
                        if (it.degraded) "$head  ·  limited service" else head
                    } ?: "connecting…"
                    Text(hint, color = Fx.textFaint, fontSize = 12.sp)
                }
                TextButton({ vm.lock() }) { Text("Lock", color = Fx.textDim) }
            }
        }
        Row(Modifier.background(Fx.glass1, RoundedCornerShape(Fx.pill)).padding(3.dp)) {
            listOf("Receive", "Send", "History").forEachIndexed { i, label ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(Fx.pill))
                    .background(if (tab == i) Brush.linearGradient(listOf(Fx.accent, Fx.accent2)) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { tab = i }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (tab == i) Color(0xFF0A0C16) else Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                }
            }
        }
        vm.lastSentTxid?.let { txid -> SentBanner(vm, txid) }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Fx.s4),
        ) {
            when (tab) {
                0 -> ReceiveTab(vm)
                1 -> SendTab(vm)
                else -> HistoryTab(vm, unit)
            }
        }
    }
}

@Composable
private fun SentBanner(vm: WalletViewModel, txid: String) {
    val ctx = LocalContext.current
    val c = vm.config
    val url = c?.let { explorerTxUrl(it.chain, it.network, txid) }
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Payment sent", color = Fx.good, fontWeight = FontWeight.Medium)
                Text(
                    "${txid.take(12)}… · tap to copy",
                    color = Fx.textFaint, fontSize = 12.sp,
                    modifier = Modifier.clickable { copyToClipboard(ctx, "txid", txid) },
                )
            }
            if (url != null) TextButton({ openInBrowser(ctx, url) }) { Text("View", color = Fx.accent) }
            IconButton({ vm.dismissLastSent() }) { Icon(Icons.Filled.Close, "Dismiss", tint = Fx.textDim) }
        }
    }
}

@Composable
private fun ReceiveTab(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val i = vm.config?.nextReceive ?: 0
    val addr = remember(i, vm.session) { runCatching { vm.session?.receiveAddress(i)?.address }.getOrNull() ?: "…" }
    val copy = { copyToClipboard(ctx, "address", addr) }
    val share = {
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, addr) },
                "Share address",
            ),
        )
    }
    GlassCard {
        Text("Receive", color = Fx.text, fontWeight = FontWeight.SemiBold)
        if (addr.length > 3) {
            val qr = remember(addr) { qrBitmap(addr) }
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "address QR code",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(224.dp)
                    .clip(RoundedCornerShape(Fx.rSm))
                    .background(Color.White)
                    .padding(10.dp),
            )
        }
        Text(
            addr,
            fontFamily = FontFamily.Monospace,
            color = Fx.text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Fx.rSm))
                .background(Fx.glass1)
                .clickable { copy() }
                .padding(Fx.s4),
        )
        Text("address #$i · tap to copy", color = Fx.textFaint, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
            GhostButton("Copy", Modifier.weight(1f)) { copy() }
            GhostButton("Share", Modifier.weight(1f)) { share() }
        }
        GhostButton("New address") { vm.newReceiveAddress() }
    }
}

@Composable
private fun SendTab(vm: WalletViewModel) {
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var amountInSat by remember { mutableStateOf(true) }
    var sweep by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(6) }
    var custom by remember { mutableStateOf("") }
    var replayProtect by remember { mutableStateOf(false) }
    val isBtc = vm.config?.chain == "btc"
    val coinUnit = if (isBtc) "BTC" else "BTCB2"
    val amountSat = amountToSat(amount, amountInSat)
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { to = addressFromScan(it) }
    }
    GlassCard {
        Text("Send", color = Fx.text, fontWeight = FontWeight.SemiBold)
        Field(to, { to = it }, "To address", mono = true)
        GhostButton("Scan QR code") {
            scan.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Scan a payment address")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(sweep, { sweep = it }); Text("Send maximum (sweep)", color = Fx.text)
        }
        if (!sweep) {
            Text("Amount", color = Fx.textDim, style = MaterialTheme.typography.labelMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Fx.s2),
                verticalAlignment = Alignment.Top,
            ) {
                Field(
                    amount, { amount = it }, null,
                    keyboardType = if (amountInSat) KeyboardType.Number else KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
                AmountUnitPicker(amountInSat, coinUnit) { amountInSat = it }
            }
            amountSat?.let {
                Text(
                    if (amountInSat) "= ${fmt(it)} $coinUnit" else "= $it sat",
                    color = Fx.textFaint, fontSize = 12.sp,
                )
            }
        }
        Segmented(listOf("1" to "Fast", "6" to "Normal", "144" to "Slow"), target.toString(),
            { target = it.toInt(); custom = "" })
        Field(custom, { custom = it }, "custom sat/vB (optional)", keyboardType = KeyboardType.Number)
        if (isBtc && !sweep) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(replayProtect, { replayProtect = it })
                Text("BTCB2 replay protection (100-byte OP_RETURN)", color = Fx.text)
            }
            if (replayProtect) Text(
                "Adds ~110 vB of fee. Non-standard on default Bitcoin relay — " +
                    "broadcast via a node/service that accepts large OP_RETURN.",
                color = Fx.textFaint, fontSize = 12.sp,
            )
        }
        vm.status?.pricing?.let {
            Text(
                "Service fee: ${"%.2f".format(it.bps / 100.0)}% of the amount sent " +
                    "(min ${it.floorSat} sat) — supports this hosted node.",
                color = Fx.textFaint, fontSize = 12.sp,
            )
        }
        ErrorText(vm.error)
        PrimaryButton(
            "Review",
            enabled = to.isNotBlank() && (sweep || (amountSat != null && amountSat > 0)),
        ) {
            vm.buildPayment(to, amountSat ?: 0L, sweep, custom.toLongOrNull(), target, replayProtect && isBtc)
        }
    }
}

/** The "sat / BTC" (or "sat / BTCB2") unit picker that sits beside the Amount
 *  field. Styled to match [Field] so the two line up. */
@Composable
private fun AmountUnitPicker(isSat: Boolean, coinUnit: String, onChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(56.dp)
                .clip(RoundedCornerShape(Fx.rSm))
                .background(Fx.glass1)
                .border(1.dp, Fx.hair, RoundedCornerShape(Fx.rSm))
                .clickable { open = true }
                .padding(start = Fx.s3, end = Fx.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (isSat) "sat" else coinUnit, color = Fx.text, fontSize = 14.sp)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "amount unit", tint = Fx.textDim)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Fx.bg1,
        ) {
            DropdownMenuItem(
                text = { Text("sat", color = Fx.text) },
                onClick = { onChange(true); open = false },
            )
            DropdownMenuItem(
                text = { Text(coinUnit, color = Fx.text) },
                onClick = { onChange(false); open = false },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryTab(vm: WalletViewModel, unit: String) {
    val ctx = LocalContext.current
    val chain = vm.config?.chain ?: "btcb2"
    val network = vm.config?.network ?: "mainnet"
    if (vm.history.isEmpty()) {
        GlassCard { Text("no transactions yet", color = Fx.textFaint) }
        return
    }
    GlassCard {
        vm.history.forEach { h ->
            val url = explorerTxUrl(chain, network, h.txid)
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (url != null) openInBrowser(ctx, url) else copyToClipboard(ctx, "txid", h.txid) },
                        onLongClick = { copyToClipboard(ctx, "txid", h.txid) },
                    )
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text((if (h.amountSat > 0) "+" else "") + fmt(h.amountSat) + " " + unit,
                        fontFamily = FontFamily.Monospace, color = if (h.amountSat > 0) Fx.good else Fx.text)
                    Text("${if (h.send) "send" else "receive"} · ${h.txid.take(10)}…", color = Fx.textFaint, fontSize = 12.sp)
                }
                Text(if (h.confirmations < 1) "pending" else if (h.confirmations < 6) "${h.confirmations} conf" else "confirmed",
                    color = if (h.confirmations < 1) Fx.warn else Fx.textDim, fontSize = 12.sp)
            }
        }
        Text(
            "tap a transaction to open it in the explorer · long-press to copy the id",
            color = Fx.textFaint, fontSize = 11.sp, modifier = Modifier.padding(top = Fx.s2),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSheet(vm: WalletViewModel, p: PlanPreview, unit: String) {
    ModalBottomSheet(onDismissRequest = { vm.cancelPending() }, containerColor = Fx.bg1) {
        Column(Modifier.padding(Fx.s4).padding(bottom = Fx.s5), verticalArrangement = Arrangement.spacedBy(Fx.s3)) {
            Text(if (p.sweep) "Confirm sweep" else "Confirm payment", style = MaterialTheme.typography.titleMedium, color = Fx.text)
            val inTotal = p.plan.selected.sumOf { it.valueSat.toLong() }
            val svcFee = p.plan.serviceFeeSat?.toLong() ?: 0L
            val out = inTotal - p.plan.feeSat.toLong() - svcFee - (p.plan.changeSat?.toLong() ?: 0L)
            kv("To", p.to); kv("Amount", "${fmt(out)} $unit")
            kv("Network fee", "${fmt(p.plan.feeSat.toLong())} $unit · ${p.feerate} sat/vB")
            if (svcFee > 0) kv("Service fee", "${fmt(svcFee)} $unit")
            p.plan.changeSat?.let { kv("Change", "${fmt(it.toLong())} $unit") }
            if (p.replayProtected) kv("Replay protection", "on · 100-byte OP_RETURN")
            kv("Total", "${fmt(out + p.plan.feeSat.toLong() + svcFee)} $unit")
            ErrorText(vm.error)
            Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
                GhostButton("Cancel", Modifier.weight(1f)) { vm.cancelPending() }
                PrimaryButton("Sign & send", Modifier.weight(1f)) { vm.confirmSend() }
            }
        }
    }
}

@Composable
private fun kv(k: String, v: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(k, color = Fx.textDim)
    Text(v, color = Fx.text, modifier = Modifier.padding(start = Fx.s4), textAlign = androidx.compose.ui.text.style.TextAlign.End)
}
