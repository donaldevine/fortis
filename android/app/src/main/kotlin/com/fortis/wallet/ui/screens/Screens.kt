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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                words.chunked(3).forEachIndexed { row, three ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        three.forEachIndexed { col, w ->
                            Text("${row * 3 + col + 1}  $w", color = Fx.text,
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                modifier = Modifier.weight(1f).background(Fx.glass1, RoundedCornerShape(8.dp)).padding(6.dp))
                        }
                    }
                }
            }
        }
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

/** The wallet picker: every wallet on this device, prefixed by its chain. */
@Composable
fun WalletListScreen(vm: WalletViewModel) = Screen(scroll = true) {
    val anyUnlocked = vm.wallets.any { vm.isUnlocked(it.id) }
    Text("Wallets", style = MaterialTheme.typography.titleMedium, color = Fx.text)
    GlassCard {
        vm.wallets.forEach { w ->
            val current = w.id == vm.selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Fx.rSm))
                    .background(if (current) Fx.glass2 else Fx.glass1)
                    .clickable { vm.selectWallet(w.id) }
                    .padding(Fx.s3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(w.display, color = Fx.text, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(
                            w.network.takeIf { it != "mainnet" },
                            if (vm.isUnlocked(w.id)) "unlocked" else "locked",
                        ).joinToString(" · "),
                        color = Fx.textFaint, fontSize = 12.sp,
                    )
                }
                if (current) Text("current", color = Fx.accent, fontSize = 12.sp)
            }
        }
    }
    if (vm.canAddWallet) PrimaryButton("Add wallet") { vm.addWallet() }
    else Text("Maximum of ${com.fortis.wallet.MAX_WALLETS} wallets reached.", color = Fx.textFaint, fontSize = 12.sp)
    ErrorText(vm.error)
    Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
        GhostButton("Manage wallets", Modifier.weight(1f), tint = Fx.textDim) { vm.goManageWallets() }
        if (anyUnlocked) GhostButton("Lock", Modifier.weight(1f), tint = Fx.textDim) { vm.lock() }
    }
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

/** Add / rename / remove wallets, and list a wallet on the other chain. */
@Composable
fun ManageWalletsScreen(vm: WalletViewModel) {
    var renaming by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    Screen(scroll = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.goWalletList() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Fx.text) }
            Text("Manage wallets", style = MaterialTheme.typography.titleMedium, color = Fx.text)
        }
        vm.wallets.forEach { w ->
            GlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.display, color = Fx.text, fontWeight = FontWeight.Medium)
                        Text(if (w.id == vm.selectedId) "current" else "tap Switch to use", color = Fx.textFaint, fontSize = 12.sp)
                    }
                    if (w.id != vm.selectedId) TextButton({ vm.selectWallet(w.id) }) { Text("Switch", color = Fx.accent) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
                    GhostButton("Rename", Modifier.weight(1f)) { renaming = w.id }
                    GhostButton("Remove", Modifier.weight(1f), tint = Fx.bad) { removing = w.id }
                }
                val hasOther = vm.wallets.any { it.name == w.name && it.chain == w.otherChain }
                if (!hasOther && vm.canAddWallet) GhostButton("Also add on ${w.otherChain.uppercase()}") {
                    vm.cloneToOtherChain(w.id)
                }
            }
        }
        if (vm.canAddWallet) PrimaryButton("Add another wallet") { vm.addWallet() }
        else Text("Maximum of ${com.fortis.wallet.MAX_WALLETS} wallets reached.", color = Fx.textFaint, fontSize = 12.sp)
        ErrorText(vm.error)
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

@Composable
fun HomeScreen(vm: WalletViewModel) {
    var tab by remember { mutableStateOf(0) }
    val unit = if (vm.config?.chain == "btc") "BTC" else "BTCB2"
    val b = vm.balances

    LaunchedEffect(vm.selectedId) {
        while (true) {
            vm.refresh()
            kotlinx.coroutines.delay(20_000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Column(
            Modifier.fillMaxSize().widthIn(max = 460.dp).align(Alignment.TopCenter).systemBarsPadding().padding(Fx.s4),
            verticalArrangement = Arrangement.spacedBy(Fx.s4),
        ) {
            // hero
            GlassCard(fill = Fx.glassHero, corner = Fx.rLg) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        vm.config?.let { c ->
                            Text(
                                (if (vm.wallets.size > 1) "$unit · ${c.name}  ▾" else "$unit · ${c.name}"),
                                color = Fx.textDim, fontSize = 12.sp,
                                modifier = Modifier.clickable(enabled = vm.wallets.size > 1) { vm.goWalletList() },
                            )
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(if (b != null) fmt(b.confirmedSat) else "—",
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 32.sp,
                                color = Fx.text)
                            Spacer(Modifier.width(6.dp))
                            Text(unit, color = Fx.textDim, fontSize = 12.sp)
                        }
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
                    Row {
                        IconButton({ vm.goSettings() }) { Icon(Icons.Outlined.Settings, "Settings", tint = Fx.textDim) }
                        TextButton({ vm.lock() }) { Text("Lock", color = Fx.textDim) }
                    }
                }
            }
            // tabs
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
    vm.pending?.let { ConfirmSheet(vm, it, unit) }
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
    var sweep by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(6) }
    var custom by remember { mutableStateOf("") }
    var replayProtect by remember { mutableStateOf(false) }
    val isBtc = vm.config?.chain == "btc"
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
        if (!sweep) Field(amount, { amount = it }, "Amount")
        Segmented(listOf("1" to "Fast", "6" to "Normal", "144" to "Slow"), target.toString(),
            { target = it.toInt(); custom = "" })
        Field(custom, { custom = it }, "custom sat/vB (optional)")
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
        PrimaryButton("Review", enabled = to.isNotBlank() && (sweep || amount.isNotBlank())) {
            vm.buildPayment(to, amount, sweep, custom.toLongOrNull(), target, replayProtect && isBtc)
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

@Composable
fun SettingsScreen(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val c = vm.config
    val s = vm.session
    val st = vm.status

    Screen(scroll = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.goHome() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Fx.text) }
            Text("Settings", style = MaterialTheme.typography.titleMedium, color = Fx.text)
        }

        // --- wallet ---
        GlassCard {
            Text("Wallet", color = Fx.text, fontWeight = FontWeight.SemiBold)
            if (c != null) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Fx.rSm)).background(Fx.glass1)
                        .clickable(enabled = s != null) { s?.let { copyToClipboard(ctx, "xpub", it.xpub) } }
                        .padding(Fx.s3),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(c.display, color = Fx.text, fontWeight = FontWeight.Medium)
                    if (s != null) {
                        Text(s.xpub, color = Fx.textDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2)
                        Text("fp ${s.fingerprint}  ·  receive #${c.nextReceive}  ·  change #${c.nextChange}  ·  tap to copy xpub",
                            color = Fx.textFaint, fontSize = 11.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
                if (vm.wallets.size > 1) GhostButton("Switch wallet", Modifier.weight(1f)) { vm.goWalletList() }
                GhostButton("Manage wallets", Modifier.weight(1f)) { vm.goManageWallets() }
            }
            Text(
                "App lock: ${if (vm.lockMode == com.fortis.wallet.data.LOCK_BIOMETRIC) "fingerprint / device PIN" else "password"}",
                color = Fx.textFaint, fontSize = 11.sp,
            )
        }

        // --- connection ---
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
                GhostButton("Refresh", Modifier.weight(1f)) { vm.refresh() }
                GhostButton("Reconnect", Modifier.weight(1f)) { vm.reconnect() }
            }
        }

        GhostButton("Lock app", tint = Fx.bad) { vm.lock() }
        Text("fortis 0.1.0", color = Fx.textFaint, fontSize = 11.sp)
    }
}
