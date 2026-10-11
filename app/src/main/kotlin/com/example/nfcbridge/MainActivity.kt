package com.example.nfcbridge

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcA
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * NFC Bridge: holds an NfcA connection to whatever tag is on the phone and lets a
 * computer send raw commands to it over TCP, like "Advanced NFC commands" in NFC Tools.
 *
 * Line protocol (ASCII, one request per line, one reply per line):
 *   AUTH <pin>   -> OK | ERR ...        (must be the first command)
 *   PING         -> OK
 *   INFO         -> OK uid=.. atqa=.. sak=.. tech=..
 *   <hex bytes>  -> OK <response hex> | ERR <reason>
 * The phone adds the CRC itself, so never include one.
 */
class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    private val port = 8765
    private val pin = (1000 + SecureRandom().nextInt(9000)).toString()
    private val lock = Any()

    private var adapter: NfcAdapter? = null
    private var tag: Tag? = null
    private var nfcA: NfcA? = null
    private var serverSocket: ServerSocket? = null

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private var tagStatus = "No tag. Hold a card flat against the back of the phone."
    private var clientStatus = "No computer connected."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusView = TextView(this).apply { textSize = 18f; setPadding(32, 32, 32, 16) }
        logView = TextView(this).apply { textSize = 13f; setPadding(32, 0, 32, 32) }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(statusView)
            scroll = ScrollView(this@MainActivity).apply { addView(logView) }
            addView(scroll)
        }
        setContentView(root)

        adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            tagStatus = "This phone has no NFC."
        }
        refresh()
        startServer()
    }

    override fun onResume() {
        super.onResume()
        val a = adapter ?: return
        if (!a.isEnabled) {
            tagStatus = "NFC is off. Turn it on in Settings."
            refresh()
        }
        a.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        adapter?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { serverSocket?.close() } catch (_: IOException) {}
        synchronized(lock) { closeTag() }
    }

    // Called on a background thread when a tag is found.
    override fun onTagDiscovered(t: Tag) {
        synchronized(lock) {
            closeTag()
            val a = NfcA.get(t)
            if (a == null) {
                tagStatus = "Tag found, but it is not NfcA (ISO 14443-3A). Not supported."
                log("Tag is not NfcA: ${t.techList.joinToString()}")
            } else {
                try {
                    a.connect()
                    a.timeout = 3000
                    tag = t
                    nfcA = a
                    tagStatus = "Tag ready: UID ${hex(t.id)}"
                    log("Tag connected: ${hex(t.id)}")
                } catch (e: IOException) {
                    tagStatus = "Tag found but connect failed. Hold it still and retry."
                }
            }
        }
        refresh()
    }

    private fun closeTag() {
        try { nfcA?.close() } catch (_: IOException) {}
        nfcA = null
        tag = null
    }

    private fun startServer() {
        Thread {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                refresh()
                while (!ss.isClosed) {
                    val s = ss.accept()
                    Thread { handleClient(s) }.start()
                }
            } catch (e: IOException) {
                log("Server stopped: ${e.message}")
            }
        }.start()
    }

    private fun handleClient(s: Socket) {
        val who = s.inetAddress.hostAddress
        try {
            s.soTimeout = 0
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
            val out = PrintWriter(s.getOutputStream(), true)
            var authed = false
            clientStatus = "Computer connected from $who"
            refresh()
            while (true) {
                val line = input.readLine() ?: break
                val cmd = line.trim()
                if (cmd.isEmpty()) continue
                val reply: String = when {
                    cmd.startsWith("AUTH ", ignoreCase = true) -> {
                        if (cmd.substring(5).trim() == pin) { authed = true; "OK" }
                        else "ERR bad pin"
                    }
                    !authed -> "ERR send AUTH <pin> first"
                    cmd.equals("PING", ignoreCase = true) -> "OK"
                    cmd.equals("INFO", ignoreCase = true) -> info()
                    else -> run(cmd)
                }
                log("> $cmd\n< $reply")
                out.println(reply)
            }
        } catch (e: IOException) {
            // client went away
        } finally {
            try { s.close() } catch (_: IOException) {}
            clientStatus = "No computer connected."
            refresh()
        }
    }

    private fun info(): String {
        synchronized(lock) {
            val t = tag
            val a = nfcA
            if (t == null || a == null) return "ERR no tag"
            val sak = "%02X".format(a.sak.toInt() and 0xFF)
            val tech = t.techList.joinToString(",") { it.substringAfterLast('.') }
            return "OK uid=${hex(t.id)} atqa=${hex(a.atqa)} sak=$sak tech=$tech"
        }
    }

    private fun run(cmd: String): String {
        val bytes = parseHex(cmd) ?: return "ERR not valid hex"
        if (bytes.isEmpty()) return "ERR empty command"
        synchronized(lock) {
            val a = nfcA ?: return "ERR no tag - hold a card against the phone"
            return try {
                if (!a.isConnected) a.connect()
                "OK " + hex(a.transceive(bytes))
            } catch (e: TagLostException) {
                closeTag()
                tagStatus = "Tag lost."
                refresh()
                "ERR tag lost - re-tap the card"
            } catch (e: IOException) {
                "ERR ${e.message ?: "transceive failed"} (a NAK often means locked page or bad command)"
            }
        }
    }

    private fun parseHex(s: String): ByteArray? {
        val clean = s.replace(Regex("(0x|[\\s:,-])", RegexOption.IGNORE_CASE), "")
        if (clean.length % 2 != 0 || !clean.matches(Regex("[0-9a-fA-F]*"))) return null
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun localIps(): String {
        val ips = ArrayList<String>()
        try {
            for (ni in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in java.util.Collections.list(ni.inetAddresses)) {
                    if (addr is Inet4Address) ips.add(addr.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) {}
        return if (ips.isEmpty()) "(not on Wi-Fi)" else ips.joinToString(", ")
    }

    private fun refresh() {
        runOnUiThread {
            statusView.text = "PIN: $pin\nPort: $port\nPhone IP: ${localIps()}\n\n$tagStatus\n$clientStatus"
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
