package com.fastreader.app.core

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** 파일 전체를 메모리에 올리지 않고 임의 위치를 읽기 위한 추상화. */
interface RandomAccessSource : Closeable {
    val size: Long

    /** [pos] 위치부터 [len] 바이트를 정확히 읽는다. 부족하면 EOFException. */
    fun readFully(pos: Long, dst: ByteArray, off: Int = 0, len: Int = dst.size - off)

    fun openStream(): InputStream = SourceInputStream(this)
}

class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val size: Long get() = data.size.toLong()

    override fun readFully(pos: Long, dst: ByteArray, off: Int, len: Int) {
        if (pos < 0 || pos + len > data.size) throw EOFException("pos=$pos len=$len size=${data.size}")
        System.arraycopy(data, pos.toInt(), dst, off, len)
    }

    override fun close() {}
}

class FileChannelSource(
    private val channel: FileChannel,
    private val onClose: () -> Unit = {},
) : RandomAccessSource {
    override val size: Long = channel.size()

    override fun readFully(pos: Long, dst: ByteArray, off: Int, len: Int) {
        if (pos < 0 || pos + len > size) throw EOFException("pos=$pos len=$len size=$size")
        val buf = ByteBuffer.wrap(dst, off, len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = channel.read(buf, p)
            if (n < 0) throw EOFException()
            p += n
        }
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            onClose()
        }
    }
}

private class SourceInputStream(private val src: RandomAccessSource) : InputStream() {
    private var pos = 0L
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val remain = src.size - pos
        if (remain <= 0) return -1
        val n = minOf(len.toLong(), remain).toInt()
        src.readFully(pos, b, off, n)
        pos += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = n.coerceIn(0, src.size - pos)
        pos += s
        return s
    }

    override fun available(): Int = (src.size - pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
