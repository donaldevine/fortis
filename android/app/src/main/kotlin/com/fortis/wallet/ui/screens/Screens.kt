package com.fortis.wallet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun fmt(sat: Long) = "%.8f".format(sat / 1e8)

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
fun OnboardScreen(vm: WalletViewModel) = Screen {
    Spacer(Modifier.weight(1f))
    BrandMark("a non-custodial wallet for Bitcoin and its BLAKE2b fork")
    Spacer(Modifier.weight(1f))
    PrimaryButton("Create a new wallet") { vm.goCreate() }
    GhostButton("Restore from a recovery phrase") { vm.goRestore() }
    Spacer(Modifier.weight(1f))
}

@Composable
fun GenScreen(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val collector = remember { EntropyCollector() }
    var bits by remember { mutableStateOf(0) }

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
            GhostButton("Back", Modifier.weight(1f)) { vm.goOnboard() }
            PrimaryButton("Generate wallet", Modifier.weight(1f)) { vm.generateSeed(collector.bytes()) }
        }
    }
}

@Composable
fun CreateScreen(vm: WalletViewModel) {
    var chain by remember { mutableStateOf("btcb2") }
    var network by remember { mutableStateOf("mainnet") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var ack by remember { mutableStateOf(false) }
    val words = (vm.draftMnemonic ?: "").split(" ")

    Screen(scroll = true) {
        Text("Your recovery phrase", style = MaterialTheme.typography.titleMedium, color = Fx.text)
        Text("Write these 24 words on paper, offline. Anyone with them controls your funds.", color = Fx.textDim)
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
        ChainNetworkRow(chain, { chain = it }, network, { network = it })
        Field(pw, { pw = it }, "Encryption password", password = true)
        Field(pw2, { pw2 = it }, "Confirm password", password = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ack, { ack = it })
            Text("I've written the phrase down", color = Fx.text)
        }
        ErrorText(vm.error)
        Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
            GhostButton("Back", Modifier.weight(1f)) { vm.goOnboard() }
            PrimaryButton("Continue", Modifier.weight(1f), enabled = ack && pw.length >= 8 && pw == pw2) {
                vm.createWallet(chain, network, pw)
            }
        }
    }
}

@Composable
fun RestoreScreen(vm: WalletViewModel) {
    var phrase by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var chain by remember { mutableStateOf("btcb2") }
    var network by remember { mutableStateOf("mainnet") }
    var pw by remember { mutableStateOf("") }
    Screen(scroll = true) {
        Text("Restore wallet", style = MaterialTheme.typography.titleMedium, color = Fx.text)
        Field(phrase, { phrase = it }, "Recovery phrase (12 or 24 words)", mono = true)
        Field(passphrase, { passphrase = it }, "BIP-39 passphrase (optional)", password = true)
        ChainNetworkRow(chain, { chain = it }, network, { network = it })
        Field(pw, { pw = it }, "Encryption password for this device", password = true)
        ErrorText(vm.error)
        Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
            GhostButton("Back", Modifier.weight(1f)) { vm.goOnboard() }
            PrimaryButton("Restore", Modifier.weight(1f), enabled = pw.length >= 8 && phrase.isNotBlank()) {
                vm.restoreWallet(phrase, passphrase, chain, network, pw)
            }
        }
    }
}

@Composable
private fun ChainNetworkRow(chain: String, onChain: (String) -> Unit, net: String, onNet: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
        Segmented(listOf("btcb2" to "BTCB2", "btc" to "BTC"), chain, onChain, Modifier.weight(1f))
        Segmented(listOf("mainnet" to "mainnet", "regtest" to "regtest"), net, onNet, Modifier.weight(1f))
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

@Composable
fun UnlockScreen(vm: WalletViewModel) {
    var pw by remember { mutableStateOf("") }
    Screen {
        Spacer(Modifier.weight(1f))
        BrandMark()
        GlassCard {
            Field(pw, { pw = it }, "Password", password = true)
            ErrorText(vm.error)
            PrimaryButton("Unlock", enabled = pw.isNotEmpty()) { vm.unlock(pw) }
        }
        Spacer(Modifier.weight(1f))
        GhostButton("Forget this wallet", tint = Fx.bad) { vm.wipe() }
    }
}

@Composable
fun BackendPickerScreen(vm: WalletViewModel) {
    val default = if (vm.config?.chain == "btc") "https://mempool.space/api" else "https://mempool.guide/api"
    var esploraUrl by remember { mutableStateOf(default) }
    var gwUrl by remember { mutableStateOf("http://127.0.0.1:8088") }
    var gwToken by remember { mutableStateOf("") }
    var showGateway by remember { mutableStateOf(vm.config?.network == "regtest") }
    Screen {
        Spacer(Modifier.weight(1f))
        Text("How should fortis see the chain?", style = MaterialTheme.typography.titleMedium, color = Fx.text)
        if (!showGateway) {
            GlassCard {
                Text("Public explorer", color = Fx.text, fontWeight = FontWeight.SemiBold)
                Text("No node. The explorer sees which addresses you look up; it can never move funds.", color = Fx.textDim)
                Field(esploraUrl, { esploraUrl = it }, "Esplora API URL")
                Text("If it can't connect (no CORS on the phone matters less, but a proxy may still be needed), run fortisd --esplora-proxy.", color = Fx.textFaint, fontSize = 12.sp)
                ErrorText(vm.error)
                PrimaryButton("Use this explorer") { vm.useEsplora(esploraUrl) }
            }
            GhostButton("Connect my own node instead") { showGateway = true }
        } else {
            GlassCard {
                Text("Your own node (fortisd)", color = Fx.text, fontWeight = FontWeight.SemiBold)
                Field(gwUrl, { gwUrl = it }, "Gateway URL")
                Field(gwToken, { gwToken = it }, "API token")
                ErrorText(vm.error)
                PrimaryButton("Connect", enabled = gwToken.isNotBlank()) { vm.useGateway(gwUrl, gwToken) }
            }
            if (vm.config?.network != "regtest") GhostButton("Back") { showGateway = false }
        }
        Spacer(Modifier.weight(1f))
        GhostButton("Lock") { vm.lock() }
    }
}

@Composable
fun HomeScreen(vm: WalletViewModel) {
    var tab by remember { mutableStateOf(0) }
    val unit = if (vm.config?.chain == "btc") "BTC" else "BTCB2"
    val b = vm.balances

    LaunchedEffect(Unit) {
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
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(if (b != null) fmt(b.confirmedSat) else "—",
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 32.sp,
                                color = Fx.text)
                            Spacer(Modifier.width(6.dp))
                            Text(unit, color = Fx.textDim, fontSize = 12.sp)
                        }
                        val hint = vm.status?.let {
                            (if (it.synced) "block ${it.blocks}" else "syncing") + "  ·  ${it.via}"
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
private fun ReceiveTab(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val i = vm.config?.nextReceive ?: 0
    val addr = remember(i, vm.session) { runCatching { vm.session?.receiveAddress(i)?.address }.getOrNull() ?: "…" }
    val copy = { copyToClipboard(ctx, "address", addr) }
    GlassCard {
        Text("Receive", color = Fx.text, fontWeight = FontWeight.SemiBold)
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
            GhostButton("New address", Modifier.weight(1f)) { vm.newReceiveAddress() }
        }
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
    GlassCard {
        Text("Send", color = Fx.text, fontWeight = FontWeight.SemiBold)
        Field(to, { to = it }, "To address", mono = true)
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

@Composable
private fun HistoryTab(vm: WalletViewModel, unit: String) {
    val ctx = LocalContext.current
    if (vm.history.isEmpty()) {
        GlassCard { Text("no transactions yet", color = Fx.textFaint) }
        return
    }
    GlassCard {
        vm.history.forEach { h ->
            Row(
                Modifier.fillMaxWidth().clickable { copyToClipboard(ctx, "txid", h.txid) }.padding(vertical = 10.dp),
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
    var showToken by remember { mutableStateOf(false) }

    Screen(scroll = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.goHome() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Fx.text) }
            Text("Settings", style = MaterialTheme.typography.titleMedium, color = Fx.text)
        }

        // --- wallets (one for now) ---
        GlassCard {
            Text("Wallets", color = Fx.text, fontWeight = FontWeight.SemiBold)
            if (c != null && s != null) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Fx.rSm)).background(Fx.glass1)
                        .clickable { copyToClipboard(ctx, "xpub", s.xpub) }.padding(Fx.s3),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("${c.chain.uppercase()} · ${c.network}", color = Fx.text, fontWeight = FontWeight.Medium)
                    Text(s.xpub, color = Fx.textDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2)
                    Text("fp ${s.fingerprint}  ·  receive #${c.nextReceive}  ·  change #${c.nextChange}  ·  tap to copy xpub",
                        color = Fx.textFaint, fontSize = 11.sp)
                }
            }
            Text("Multiple wallets: not yet — one seed per install.", color = Fx.textFaint, fontSize = 12.sp)
        }

        // --- backend / fortisd ---
        GlassCard {
            Text(if (c?.backendKind == "gateway") "Node gateway (fortisd)" else "Public explorer",
                color = Fx.text, fontWeight = FontWeight.SemiBold)
            kv("URL", c?.backendUrl ?: "—")
            if (c?.backendKind == "gateway") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Token", color = Fx.textDim)
                    Text(
                        if (showToken) c.backendToken ?: "—" else "•".repeat(16),
                        color = Fx.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        modifier = Modifier.clickable { showToken = !showToken }.padding(start = Fx.s4),
                    )
                }
            }
            kv("Status", when {
                st == null -> "not connected"
                st.scanningPct != null -> "rescanning ${st.scanningPct}%"
                st.synced -> "synced"
                else -> "syncing"
            })
            kv("Chain height", st?.blocks?.toString() ?: "—")
            if (st?.chain?.isNotBlank() == true) kv("Network", st.chain)
            if (st?.subversion?.isNotBlank() == true) kv("Node", st.subversion)
            Row(horizontalArrangement = Arrangement.spacedBy(Fx.s2)) {
                GhostButton("Reconnect", Modifier.weight(1f)) { vm.refresh() }
                GhostButton("Change backend", Modifier.weight(1f)) { vm.changeBackend() }
            }
        }

        // --- danger ---
        GlassCard {
            Text("This wallet", color = Fx.text, fontWeight = FontWeight.SemiBold)
            GhostButton("Lock") { vm.lock() }
            GhostButton("Forget this wallet", tint = Fx.bad) { vm.wipe() }
        }
        Text("fortis 0.1.0", color = Fx.textFaint, fontSize = 11.sp)
    }
}
