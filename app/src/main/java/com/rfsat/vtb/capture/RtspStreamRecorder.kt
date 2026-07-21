package com.rfsat.vtb.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Network
import android.util.Base64
import com.rfsat.vtb.log.Logger
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal RTSP/H.264 stream recorder (v20.23, EXPERIMENTAL) for digital
 * scopes that stream over their own Wi-Fi hotspot (ATN X-Sight / ThOR run
 * an RTSP service on TCP 554 behind the Obsidian core, per community
 * teardowns; the ATN app requests the same stream).
 *
 * Design decisions:
 *  - RTP over TCP (interleaved) only: one socket, no UDP/NAT concerns, and
 *    lossless delivery — right for ANALYSIS-grade recording.
 *  - The stream path varies by model/firmware and is undocumented, so
 *    [PATH_CANDIDATES] are probed with DESCRIBE until one answers 200. The
 *    full handshake is logged: one real session pins the exact endpoint.
 *  - Frames are muxed unmodified into an MP4 in the app cache; the file
 *    then feeds the EXISTING analysis pipeline (FastFrameDecoder reads true
 *    dimensions from the codec, so even a wrong container hint is safe —
 *    but width/height are parsed from the SPS anyway).
 *  - No audio track is recorded: the scope's mic (if any) is not the muzzle
 *    report the shot-break detector expects. The manual shot-break field is
 *    the fallback, as designed.
 */
class RtspStreamRecorder(
    private val urlBase: String,          // e.g. rtsp://192.168.1.1:554 (path optional)
    private val outFile: File,
    private val network: Network?,        // scope-AP Wi-Fi; null = default routing
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "RtspRecorder"
        val PATH_CANDIDATES = listOf("", "/", "/stream0", "/live", "/video0", "/h264", "/stream1", "/ch0", "/main")
        private const val TIMEOUT_MS = 6000
    }

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile var framesWritten = 0; private set
    @Volatile var bytesRead = 0L; private set
    @Volatile var lastError: String? = null; private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ runCatching { run() }.onFailure { fail("stream error: ${it.message}") } }, "RtspRecorder").also { it.start() }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        thread?.join(3000)
    }

    private fun fail(msg: String) {
        lastError = msg
        Logger.w(TAG, msg)
        onStatus(msg)
        running.set(false)
    }

    // ---------------- RTSP session ----------------

    private fun run() {
        val uri = URI(if (urlBase.startsWith("rtsp://")) urlBase else "rtsp://$urlBase")
        val host = uri.host ?: return fail("bad URL")
        val port = if (uri.port > 0) uri.port else 554
        val s = (network?.socketFactory?.createSocket() ?: Socket()).apply {
            soTimeout = TIMEOUT_MS
            connect(InetSocketAddress(host, port), TIMEOUT_MS)
            tcpNoDelay = true
        }
        socket = s
        Logger.i(TAG, "Connected to $host:$port (via ${if (network != null) "bound Wi-Fi network" else "default route"})")
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val output = s.getOutputStream()
        var cseq = 1

        // Probe candidate paths with DESCRIBE (unless the URL already has one).
        val givenPath = uri.path?.takeIf { it.isNotBlank() && it != "/" }
        val candidates = if (givenPath != null) listOf(givenPath) else PATH_CANDIDATES
        var sdp: String? = null
        var contentBase = ""
        for (path in candidates) {
            val url = "rtsp://$host:$port$path"
            val resp = request(output, input, "DESCRIBE $url RTSP/1.0", cseq++, "Accept: application/sdp")
                ?: return fail("no response to DESCRIBE (is the scope's Wi-Fi connected?)")
            Logger.i(TAG, "DESCRIBE $path -> ${resp.statusLine}")
            if (resp.status == 200) {
                sdp = resp.body
                contentBase = resp.header("content-base") ?: url
                onStatus("stream found at ${if (path.isBlank()) "/" else path}")
                break
            }
        }
        val sdpText = sdp ?: return fail("no stream path answered DESCRIBE — send the log; candidates tried: $candidates")
        Logger.i(TAG, "SDP:\n$sdpText")

        // Parse SDP: find the H.264 video media section, control, sprop sets.
        val (control, spsPps) = parseSdp(sdpText, contentBase)
            ?: return fail("SDP has no H.264 video track — send the log")

        val setup = request(output, input, "SETUP $control RTSP/1.0", cseq++,
            "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
            ?: return fail("no response to SETUP")
        Logger.i(TAG, "SETUP -> ${setup.statusLine}")
        if (setup.status != 200) return fail("SETUP refused: ${setup.statusLine}")
        val session = setup.header("session")?.substringBefore(';')?.trim() ?: ""

        val play = request(output, input, "PLAY $contentBase RTSP/1.0", cseq++,
            "Session: $session", "Range: npt=0.000-")
            ?: return fail("no response to PLAY")
        Logger.i(TAG, "PLAY -> ${play.statusLine}")
        if (play.status != 200) return fail("PLAY refused: ${play.statusLine}")
        onStatus("recording\u2026")

        try {
            muxLoop(input, spsPps)
        } finally {
            runCatching {
                writeLine(output, "TEARDOWN $contentBase RTSP/1.0\r\nCSeq: ${cseq}\r\nSession: $session\r\n\r\n")
            }
            runCatching { s.close() }
        }
    }

    private class Resp(val statusLine: String, val status: Int, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers[name.lowercase()]
    }

    private fun writeLine(out: OutputStream, text: String) { out.write(text.toByteArray()); out.flush() }

    private fun request(out: OutputStream, inp: DataInputStream, line: String, cseq: Int, vararg extra: String): Resp? {
        writeLine(out, line + "\r\nCSeq: $cseq\r\nUser-Agent: VTB\r\n" + extra.joinToString("") { it + "\r\n" } + "\r\n")
        // Read a response, skipping any interleaved binary that might precede it.
        return runCatching {
            val head = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b < 0) return null
                if (b == '$'.code && head.isEmpty()) { // interleaved packet before response
                    val ch = inp.read(); val len = inp.readUnsignedShort(); inp.skipBytes(len); continue
                }
                head.append(b.toChar())
                if (head.endsWith("\r\n\r\n")) break
                if (head.length > 16384) return null
            }
            val lines = head.toString().split("\r\n").filter { it.isNotBlank() }
            val status = lines.first().split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            val headers = lines.drop(1).mapNotNull {
                val i = it.indexOf(':'); if (i < 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }.toMap()
            val clen = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (clen > 0) ByteArray(clen).also { inp.readFully(it) }.toString(Charsets.UTF_8) else ""
            Resp(lines.first(), status, headers, body)
        }.getOrNull()
    }

    /** Returns (control URL, Pair(sps, pps) or null if only in-band). */
    private fun parseSdp(sdp: String, base: String): Pair<String, Pair<ByteArray, ByteArray>?>? {
        var inVideo = false
        var control: String? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (raw in sdp.lines()) {
            val line = raw.trim()
            if (line.startsWith("m=")) inVideo = line.startsWith("m=video")
            if (!inVideo) continue
            if (line.startsWith("a=control:")) {
                val c = line.removePrefix("a=control:").trim()
                control = if (c.startsWith("rtsp://")) c else base.trimEnd('/') + "/" + c.trimStart('/')
            }
            val sprop = Regex("sprop-parameter-sets=([^;\\s]+)").find(line)?.groupValues?.get(1)
            if (sprop != null) {
                val parts = sprop.split(",")
                if (parts.isNotEmpty()) sps = runCatching { Base64.decode(parts[0], Base64.DEFAULT) }.getOrNull()
                if (parts.size > 1) pps = runCatching { Base64.decode(parts[1], Base64.DEFAULT) }.getOrNull()
            }
        }
        val ctl = control ?: return null
        val pair = if (sps != null && pps != null) Pair(sps!!, pps!!) else null
        return Pair(ctl, pair)
    }

    // ---------------- RTP -> H.264 access units -> MP4 ----------------

    private fun muxLoop(inp: DataInputStream, sdpSpsPps: Pair<ByteArray, ByteArray>?) {
        var sps = sdpSpsPps?.first
        var pps = sdpSpsPps?.second
        var muxer: MediaMuxer? = null
        var track = -1
        var firstRtpTs = -1L
        var lastRtpTs = 0L
        var rtpTsHigh = 0L
        val START = byteArrayOf(0, 0, 0, 1)
        val au = ArrayList<ByteArray>()   // NALs of the current access unit
        var auTs = 0L
        var fuBuf: java.io.ByteArrayOutputStream? = null
        val info = MediaCodec.BufferInfo()
        var lastLog = System.currentTimeMillis()

        fun startMuxerIfReady() {
            if (muxer != null || sps == null || pps == null) return
            val dims = parseSpsDimensions(sps!!) ?: Pair(1920, 1080)
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dims.first, dims.second).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(START + sps!!))
                setByteBuffer("csd-1", ByteBuffer.wrap(START + pps!!))
            }
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                track = it.addTrack(fmt); it.start()
            }
            Logger.i(TAG, "Muxer started: ${dims.first}x${dims.second} (from SPS) -> ${outFile.name}")
        }

        fun flushAu() {
            if (au.isEmpty()) return
            startMuxerIfReady()
            val m = muxer
            if (m != null) {
                var key = false
                var size = 0
                au.forEach { size += 4 + it.size; if ((it[0].toInt() and 0x1F) == 5) key = true }
                val buf = ByteBuffer.allocate(size)
                au.forEach { buf.put(START); buf.put(it) }
                buf.flip()
                if (firstRtpTs < 0) firstRtpTs = auTs
                info.set(0, size, (auTs - firstRtpTs) * 1000 / 90, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                if (framesWritten > 0 || key) { // MP4 must start on a keyframe
                    m.writeSampleData(track, buf, info)
                    framesWritten++
                }
            }
            au.clear()
        }

        while (running.get()) {
            val b = try { inp.read() } catch (t: Throwable) { break }
            if (b < 0) break
            if (b != '$'.code) continue // stray RTSP keepalive text — skip byte-wise
            val channel = inp.read()
            val len = inp.readUnsignedShort()
            val pkt = ByteArray(len); inp.readFully(pkt)
            bytesRead += len + 4
            if (channel != 0 || len < 13) continue // RTP video is interleaved ch 0
            // RTP header
            val ts32 = ((pkt[4].toLong() and 0xFF) shl 24) or ((pkt[5].toLong() and 0xFF) shl 16) or
                       ((pkt[6].toLong() and 0xFF) shl 8) or (pkt[7].toLong() and 0xFF)
            if (ts32 < (lastRtpTs and 0xFFFFFFFFL) && ((lastRtpTs and 0xFFFFFFFFL) - ts32) > 0x7FFFFFFF) rtpTsHigh += 1L shl 32
            val ts = rtpTsHigh or ts32
            val csrc = pkt[0].toInt() and 0x0F
            var off = 12 + csrc * 4
            if ((pkt[0].toInt() and 0x10) != 0) { // extension
                val extLen = ((pkt[off + 2].toInt() and 0xFF) shl 8) or (pkt[off + 3].toInt() and 0xFF)
                off += 4 + extLen * 4
            }
            if (off >= len) continue
            if (ts != auTs && au.isNotEmpty()) flushAu()
            auTs = ts; lastRtpTs = ts

            when (val nalType = pkt[off].toInt() and 0x1F) {
                in 1..23 -> { // single NAL
                    val nal = pkt.copyOfRange(off, len)
                    when (nalType) { 7 -> sps = nal; 8 -> pps = nal; else -> au.add(nal) }
                }
                24 -> { // STAP-A
                    var p = off + 1
                    while (p + 2 <= len) {
                        val sz = ((pkt[p].toInt() and 0xFF) shl 8) or (pkt[p + 1].toInt() and 0xFF)
                        p += 2
                        if (p + sz > len) break
                        val nal = pkt.copyOfRange(p, p + sz)
                        when (nal[0].toInt() and 0x1F) { 7 -> sps = nal; 8 -> pps = nal; else -> au.add(nal) }
                        p += sz
                    }
                }
                28 -> { // FU-A
                    val fuHeader = pkt[off + 1].toInt()
                    val start = (fuHeader and 0x80) != 0
                    val end = (fuHeader and 0x40) != 0
                    if (start) {
                        fuBuf = java.io.ByteArrayOutputStream().apply {
                            write((pkt[off].toInt() and 0xE0) or (fuHeader and 0x1F))
                        }
                    }
                    fuBuf?.write(pkt, off + 2, len - off - 2)
                    if (end && fuBuf != null) {
                        val nal = fuBuf!!.toByteArray(); fuBuf = null
                        when (nal[0].toInt() and 0x1F) { 7 -> sps = nal; 8 -> pps = nal; else -> au.add(nal) }
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastLog > 5000) {
                lastLog = now
                Logger.i(TAG, "stream: $framesWritten frames, ${bytesRead / 1024} KiB")
                onStatus("recording\u2026 $framesWritten frames")
            }
        }
        flushAu()
        runCatching { muxer?.stop(); muxer?.release() }
        Logger.i(TAG, "Recorder stopped: $framesWritten frames, ${bytesRead / 1024} KiB -> ${outFile.length()} bytes")
    }

    /** Minimal SPS parse for width/height (Exp-Golomb; handles cropping). */
    private fun parseSpsDimensions(spsNal: ByteArray): Pair<Int, Int>? = runCatching {
        // strip emulation-prevention bytes
        val rbsp = ArrayList<Byte>(spsNal.size)
        var i = 1 // skip NAL header
        while (i < spsNal.size) {
            if (i + 2 < spsNal.size && spsNal[i].toInt() == 0 && spsNal[i + 1].toInt() == 0 && spsNal[i + 2].toInt() == 3) {
                rbsp.add(0); rbsp.add(0); i += 3
            } else { rbsp.add(spsNal[i]); i++ }
        }
        val bits = object {
            var pos = 0
            fun bit(): Int { val b = (rbsp[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1; pos++; return b }
            fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
            fun ue(): Int { var z = 0; while (bit() == 0 && z < 32) z++; return (1 shl z) - 1 + bits(z) }
            fun se(): Int { val k = ue(); return if (k % 2 == 0) -(k / 2) else (k + 1) / 2 }
        }
        val profile = bits.bits(8); bits.bits(16) // constraints+level
        bits.ue() // sps id
        var chromaIdc = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
            chromaIdc = bits.ue()
            if (chromaIdc == 3) bits.bit()
            bits.ue(); bits.ue(); bits.bit()
            if (bits.bit() == 1) { // scaling matrices
                val count = if (chromaIdc != 3) 8 else 12
                repeat(count) { idx ->
                    if (bits.bit() == 1) {
                        val size = if (idx < 6) 16 else 64
                        var last = 8; var next = 8
                        repeat(size) { if (next != 0) next = (last + bits.se() + 256) % 256; if (next != 0) last = next }
                    }
                }
            }
        }
        bits.ue() // log2_max_frame_num
        when (bits.ue()) { 0 -> bits.ue(); 1 -> { bits.bit(); bits.se(); bits.se(); repeat(bits.ue()) { bits.se() } } }
        bits.ue(); bits.bit()
        val widthMbs = bits.ue() + 1
        val heightMap = bits.ue() + 1
        val frameMbsOnly = bits.bit()
        if (frameMbsOnly == 0) bits.bit()
        bits.bit()
        var w = widthMbs * 16
        var h = (2 - frameMbsOnly) * heightMap * 16
        if (bits.bit() == 1) { // frame cropping
            val cl = bits.ue(); val cr = bits.ue(); val ct = bits.ue(); val cb = bits.ue()
            val cx = if (chromaIdc == 0) 1 else 2
            val cy = (if (chromaIdc <= 1) 2 else 1) * (2 - frameMbsOnly)
            w -= (cl + cr) * cx
            h -= (ct + cb) * cy
        }
        Pair(w, h)
    }.getOrNull()
}
