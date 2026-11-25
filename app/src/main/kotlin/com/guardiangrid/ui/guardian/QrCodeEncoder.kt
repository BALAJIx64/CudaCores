package com.guardiangrid.ui.guardian

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin ISO/IEC 18004 Byte-mode QR Code matrix generator (Versions 1..12, ECC Level L).
 * Enables zero-dependency, offline scannable QR generation for login-free device pairing invitations.
 */
object QrCodeEncoder {

    private data class VersionSpec(
        val version: Int,
        val totalCodewords: Int,
        val dataCodewords: Int,
        val ecPerBlock: Int,
        val blocksGroup1: Int,
        val dataPerBlock1: Int,
        val blocksGroup2: Int,
        val dataPerBlock2: Int,
        val alignments: IntArray
    )

    // ISO/IEC 18004 EC Level L table for Versions 1..12 (up to 367 UTF-8 bytes)
    private val VERSION_SPECS = listOf(
        VersionSpec(1, 26, 19, 7, 1, 19, 0, 0, intArrayOf()),
        VersionSpec(2, 44, 34, 10, 1, 34, 0, 0, intArrayOf(6, 18)),
        VersionSpec(3, 70, 55, 15, 1, 55, 0, 0, intArrayOf(6, 22)),
        VersionSpec(4, 100, 80, 20, 1, 80, 0, 0, intArrayOf(6, 26)),
        VersionSpec(5, 134, 108, 26, 1, 108, 0, 0, intArrayOf(6, 30)),
        VersionSpec(6, 172, 136, 18, 2, 68, 0, 0, intArrayOf(6, 34)),
        VersionSpec(7, 196, 156, 20, 2, 78, 0, 0, intArrayOf(6, 22, 38)),
        VersionSpec(8, 242, 194, 24, 2, 97, 0, 0, intArrayOf(6, 24, 42)),
        VersionSpec(9, 292, 232, 30, 2, 116, 0, 0, intArrayOf(6, 26, 46)),
        VersionSpec(10, 346, 274, 18, 2, 68, 2, 69, intArrayOf(6, 28, 50)),
        VersionSpec(11, 404, 324, 20, 4, 81, 0, 0, intArrayOf(6, 30, 54)),
        VersionSpec(12, 466, 370, 24, 2, 92, 2, 93, intArrayOf(6, 32, 58))
    )

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        for (i in 255 until 512) {
            GF_EXP[i] = GF_EXP[i - 255]
        }
    }

    fun encode(content: String): Array<BooleanArray> {
        val rawBytes = content.toByteArray(StandardCharsets.UTF_8)
        val spec = VERSION_SPECS.firstOrNull { spec ->
            val countBits = if (spec.version >= 10) 16 else 8
            val overheadBytes = (4 + countBits + 7) / 8
            rawBytes.size + overheadBytes <= spec.dataCodewords
        } ?: VERSION_SPECS.last()

        val maxPayload = spec.dataCodewords - if (spec.version >= 10) 3 else 2
        val payload = if (rawBytes.size > maxPayload) rawBytes.copyOf(maxPayload) else rawBytes

        val dataCodewords = encodeByteModeData(payload, spec)
        val finalCodewords = interleaveWithEcc(dataCodewords, spec)

        val size = 17 + 4 * spec.version
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        placeFinderPattern(modules, isFunction, 0, 0)
        placeFinderPattern(modules, isFunction, size - 7, 0)
        placeFinderPattern(modules, isFunction, 0, size - 7)
        placeSeparators(modules, isFunction, size)
        placeTimingPatterns(modules, isFunction, size)
        placeAlignmentPatterns(modules, isFunction, spec.alignments)
        // Dark module + reserve format info strips
        modules[size - 8][8] = true
        isFunction[size - 8][8] = true
        reserveFormatAndVersionAreas(isFunction, size, spec.version)
        if (spec.version >= 7) {
            writeVersionInfo(modules, size, spec.version)
        }
        placeDataBits(modules, isFunction, finalCodewords, size)

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            val candidate = Array(size) { r -> modules[r].clone() }
            applyMask(candidate, isFunction, size, mask)
            writeFormatInfo(candidate, size, mask) // EC Level L = 01
            val penalty = evaluatePenalty(candidate, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
            }
        }

        applyMask(modules, isFunction, size, bestMask)
        writeFormatInfo(modules, size, bestMask)
        return modules
    }

    private fun encodeByteModeData(bytes: ByteArray, spec: VersionSpec): IntArray {
        val totalBits = spec.dataCodewords * 8
        val bits = ArrayList<Int>(totalBits)
        fun appendBits(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits.add((value ushr i) and 1)
        }
        appendBits(0b0100, 4) // Byte mode indicator
        appendBits(bytes.size, if (spec.version >= 10) 16 else 8)
        for (b in bytes) appendBits(b.toInt() and 0xFF, 8)
        val terminator = minOf(4, totalBits - bits.size)
        repeat(terminator) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)
        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bits.size < totalBits) {
            appendBits(padBytes[padIdx % 2], 8)
            padIdx++
        }
        return IntArray(spec.dataCodewords) { idx ->
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or bits[idx * 8 + b]
            v
        }
    }

    private fun interleaveWithEcc(data: IntArray, spec: VersionSpec): IntArray {
        val totalBlocks = spec.blocksGroup1 + spec.blocksGroup2
        val dataBlocks = ArrayList<IntArray>(totalBlocks)
        val ecBlocks = ArrayList<IntArray>(totalBlocks)
        val generator = rsGeneratorPoly(spec.ecPerBlock)
        var offset = 0

        repeat(spec.blocksGroup1) {
            val block = data.copyOfRange(offset, offset + spec.dataPerBlock1)
            offset += spec.dataPerBlock1
            dataBlocks.add(block)
            ecBlocks.add(rsRemainder(block, generator, spec.ecPerBlock))
        }
        repeat(spec.blocksGroup2) {
            val block = data.copyOfRange(offset, offset + spec.dataPerBlock2)
            offset += spec.dataPerBlock2
            dataBlocks.add(block)
            ecBlocks.add(rsRemainder(block, generator, spec.ecPerBlock))
        }

        val out = IntArray(spec.totalCodewords)
        var outIdx = 0
        val maxDataLen = maxOf(spec.dataPerBlock1, spec.dataPerBlock2)
        for (i in 0 until maxDataLen) {
            for (b in 0 until totalBlocks) {
                if (i < dataBlocks[b].size) out[outIdx++] = dataBlocks[b][i]
            }
        }
        for (i in 0 until spec.ecPerBlock) {
            for (b in 0 until totalBlocks) {
                out[outIdx++] = ecBlocks[b][i]
            }
        }
        return out
    }

    private fun rsGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val factor = intArrayOf(1, GF_EXP[i])
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                for (k in factor.indices) {
                    next[j + k] = next[j + k] xor gfMul(poly[j], factor[k])
                }
            }
            poly = next
        }
        return poly
    }

    private fun rsRemainder(data: IntArray, generator: IntArray, ecLen: Int): IntArray {
        val rem = IntArray(ecLen)
        for (b in data) {
            val factor = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecLen - 1)
            rem[ecLen - 1] = 0
            for (i in 0 until ecLen) {
                rem[i] = rem[i] xor gfMul(generator[i + 1], factor)
            }
        }
        return rem
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else GF_EXP[GF_LOG[a] + GF_LOG[b]]

    private fun placeFinderPattern(m: Array<BooleanArray>, fn: Array<BooleanArray>, r0: Int, c0: Int) {
        for (dr in 0..6) {
            for (dc in 0..6) {
                val dark = dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr in 2..4 && dc in 2..4)
                m[r0 + dr][c0 + dc] = dark
                fn[r0 + dr][c0 + dc] = true
            }
        }
    }

    private fun placeSeparators(m: Array<BooleanArray>, fn: Array<BooleanArray>, size: Int) {
        for (i in 0..7) {
            setFn(m, fn, 7, i, false); setFn(m, fn, i, 7, false)
            setFn(m, fn, 7, size - 1 - i, false); setFn(m, fn, i, size - 8, false)
            setFn(m, fn, size - 8, i, false); setFn(m, fn, size - 1 - i, 7, false)
        }
    }

    private fun placeTimingPatterns(m: Array<BooleanArray>, fn: Array<BooleanArray>, size: Int) {
        for (i in 8 until size - 8) {
            val dark = (i % 2 == 0)
            if (!fn[6][i]) setFn(m, fn, 6, i, dark)
            if (!fn[i][6]) setFn(m, fn, i, 6, dark)
        }
    }

    private fun placeAlignmentPatterns(m: Array<BooleanArray>, fn: Array<BooleanArray>, pos: IntArray) {
        if (pos.isEmpty()) return
        for (rCenter in pos) {
            for (cCenter in pos) {
                if ((rCenter == pos.first() && cCenter == pos.first()) ||
                    (rCenter == pos.first() && cCenter == pos.last()) ||
                    (rCenter == pos.last() && cCenter == pos.first())
                ) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val dark = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != 1
                        setFn(m, fn, rCenter + dr, cCenter + dc, dark)
                    }
                }
            }
        }
    }

    private fun reserveFormatAndVersionAreas(fn: Array<BooleanArray>, size: Int, version: Int) {
        for (i in 0..8) {
            if (i != 6) {
                fn[8][i] = true
                fn[i][8] = true
            }
        }
        for (i in size - 8 until size) {
            fn[8][i] = true
            fn[i][8] = true
        }
        if (version >= 7) {
            for (i in 0..5) {
                for (j in 0..2) {
                    fn[size - 11 + j][i] = true
                    fn[i][size - 11 + j] = true
                }
            }
        }
    }

    private fun placeDataBits(m: Array<BooleanArray>, fn: Array<BooleanArray>, codewords: IntArray, size: Int) {
        var bitIdx = 0
        val totalBits = codewords.size * 8
        var col = size - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            val rows = if (upward) (size - 1 downTo 0) else (0 until size)
            for (r in rows) {
                for (cOffset in 0..1) {
                    val c = col - cOffset
                    if (!fn[r][c]) {
                        val bit = if (bitIdx < totalBits) ((codewords[bitIdx / 8] ushr (7 - (bitIdx % 8))) and 1) == 1 else false
                        m[r][c] = bit
                        bitIdx++
                    }
                }
            }
            col -= 2
            upward = !upward
        }
    }

    private fun applyMask(m: Array<BooleanArray>, fn: Array<BooleanArray>, size: Int, mask: Int) {
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (!fn[r][c]) {
                    val invert = when (mask) {
                        0 -> (r + c) % 2 == 0
                        1 -> r % 2 == 0
                        2 -> c % 3 == 0
                        3 -> (r + c) % 3 == 0
                        4 -> (r / 2 + c / 3) % 2 == 0
                        5 -> (r * c) % 2 + (r * c) % 3 == 0
                        6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
                        else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
                    }
                    if (invert) m[r][c] = !m[r][c]
                }
            }
        }
    }

    private fun writeFormatInfo(m: Array<BooleanArray>, size: Int, mask: Int) {
        val data = (0b01 shl 3) or mask // EC Level L (01)
        var rem = data shl 10
        for (i in 4 downTo 0) {
            if (((rem ushr (i + 10)) and 1) != 0) rem = rem xor (0x537 shl i)
        }
        val bits = ((data shl 10) or rem) xor 0x5412
        for (i in 0..5) m[8][i] = ((bits ushr i) and 1) == 1
        m[8][7] = ((bits ushr 6) and 1) == 1
        m[8][8] = ((bits ushr 7) and 1) == 1
        m[7][8] = ((bits ushr 8) and 1) == 1
        for (i in 9..14) m[14 - i][8] = ((bits ushr i) and 1) == 1

        for (i in 0..7) m[size - 1 - i][8] = ((bits ushr i) and 1) == 1
        for (i in 8..14) m[8][size - 15 + i] = ((bits ushr i) and 1) == 1
    }

    private fun writeVersionInfo(m: Array<BooleanArray>, size: Int, version: Int) {
        var rem = version shl 12
        for (i in 5 downTo 0) {
            if (((rem ushr (i + 12)) and 1) != 0) rem = rem xor (0x1F25 shl i)
        }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val dark = ((bits ushr i) and 1) == 1
            val r = i / 3
            val c = size - 11 + (i % 3)
            m[r][c] = dark
            m[c][r] = dark
        }
    }

    private fun evaluatePenalty(m: Array<BooleanArray>, size: Int): Int {
        var score = 0
        for (r in 0 until size) {
            var runColor = m[r][0]
            var runLen = 1
            for (c in 1 until size) {
                if (m[r][c] == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) score += 3 + (runLen - 5)
                    runColor = m[r][c]
                    runLen = 1
                }
            }
            if (runLen >= 5) score += 3 + (runLen - 5)
        }
        for (c in 0 until size) {
            var runColor = m[0][c]
            var runLen = 1
            for (r in 1 until size) {
                if (m[r][c] == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) score += 3 + (runLen - 5)
                    runColor = m[r][c]
                    runLen = 1
                }
            }
            if (runLen >= 5) score += 3 + (runLen - 5)
        }
        return score
    }

    private fun setFn(m: Array<BooleanArray>, fn: Array<BooleanArray>, r: Int, c: Int, dark: Boolean) {
        m[r][c] = dark
        fn[r][c] = true
    }
}

@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier
) {
    val matrix = remember(content) { QrCodeEncoder.encode(content) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val n = matrix.size
            if (n <= 0) return@Canvas
            val cellSize = minOf(size.width, size.height) / n.toFloat()
            val offsetX = (size.width - cellSize * n) / 2f
            val offsetY = (size.height - cellSize * n) / 2f
            for (r in 0 until n) {
                for (c in 0 until n) {
                    if (matrix[r][c]) {
                        drawRect(
                            color = Color(0xFF0D0E11),
                            topLeft = Offset(offsetX + c * cellSize, offsetY + r * cellSize),
                            size = Size(cellSize + 0.4f, cellSize + 0.4f)
                        )
                    }
                }
            }
        }
    }
}
