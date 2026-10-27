package com.fastreader.app.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MS Compound File Binary(OLE2) 최소 구현 – HWP 5.0 파일의 컨테이너.
 * 외부 라이브러리(Apache POI 등) 없이 필요한 스트림만 읽는다.
 * 손상된 파일에서도 무한루프/OOM 이 나지 않도록 모든 체인과 크기를 검증한다.
 */
class CompoundFile(private val src: RandomAccessSource) {

    class Entry(
        val name: String,
        val type: Int,
        val left: Int,
        val right: Int,
        val child: Int,
        val startSector: Int,
        val size: Long,
    ) {
        val isStream get() = type == TYPE_STREAM
        val isStorage get() = type == TYPE_STORAGE || type == TYPE_ROOT
    }

    private val sectorShift: Int
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniStreamCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private val miniStream: ByteArray

    /** "BodyText/Section0" 같은 경로 → Entry */
    val paths: Map<String, Entry>

    init {
        if (src.size < HEADER_SIZE) throw CorruptDocumentException("파일이 너무 작습니다.")
        val header = ByteArray(HEADER_SIZE)
        src.readFully(0, header)
        if (!isCompoundFile(header)) throw CorruptDocumentException("HWP(OLE) 형식이 아닙니다.")
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        sectorShift = h.getShort(0x1E).toInt() and 0xFFFF
        if (sectorShift != 9 && sectorShift != 12) throw CorruptDocumentException("잘못된 섹터 크기")
        sectorSize = 1 shl sectorShift
        val miniShift = h.getShort(0x20).toInt() and 0xFFFF
        if (miniShift !in 1..sectorShift) throw CorruptDocumentException("잘못된 미니 섹터 크기")
        miniSectorSize = 1 shl miniShift
        val numFatSectors = h.getInt(0x2C)
        val firstDirSector = h.getInt(0x30)
        miniStreamCutoff = h.getInt(0x38).toLong() and 0xFFFFFFFFL
        val firstMiniFat = h.getInt(0x3C)
        val numMiniFat = h.getInt(0x40)
        var difatSector = h.getInt(0x44)
        val numDifat = h.getInt(0x48)

        val maxSectors = ((src.size + sectorSize - 1) / sectorSize).toInt() + 1
        if (numFatSectors < 0 || numFatSectors > maxSectors) throw CorruptDocumentException("잘못된 FAT 정보")

        // DIFAT → FAT 섹터 목록
        val fatSectors = ArrayList<Int>(numFatSectors)
        for (i in 0 until 109) {
            if (fatSectors.size >= numFatSectors) break
            val s = h.getInt(0x4C + i * 4)
            if (s < 0) break
            fatSectors.add(s)
        }
        val perSector = sectorSize / 4
        var difatCount = 0
        val sec = ByteArray(sectorSize)
        while (fatSectors.size < numFatSectors && difatSector >= 0 && difatCount <= numDifat && difatCount < maxSectors) {
            readSector(difatSector, sec)
            val b = ByteBuffer.wrap(sec).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until perSector - 1) {
                if (fatSectors.size >= numFatSectors) break
                val s = b.getInt(i * 4)
                if (s >= 0) fatSectors.add(s)
            }
            difatSector = b.getInt((perSector - 1) * 4)
            difatCount++
        }

        fat = IntArray(fatSectors.size * perSector)
        fatSectors.forEachIndexed { idx, s ->
            readSector(s, sec)
            ByteBuffer.wrap(sec).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(fat, idx * perSector, perSector)
        }

        // 디렉터리
        val dirBytes = readChain(firstDirSector, null)
        val list = ArrayList<Entry>(dirBytes.size / DIR_ENTRY_SIZE)
        val d = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until dirBytes.size / DIR_ENTRY_SIZE) {
            val o = i * DIR_ENTRY_SIZE
            val nameLen = (d.getShort(o + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
            val chars = (nameLen / 2 - 1).coerceAtLeast(0)
            val name = String(dirBytes, o, chars * 2, Charsets.UTF_16LE)
            val type = dirBytes[o + 0x42].toInt() and 0xFF
            val sizeLow = d.getInt(o + 0x78).toLong() and 0xFFFFFFFFL
            // v3(512 바이트 섹터) 파일은 상위 32비트가 쓰레기일 수 있다.
            val size = if (sectorSize == 512) sizeLow else d.getLong(o + 0x78)
            list.add(Entry(name, type, d.getInt(o + 0x44), d.getInt(o + 0x48), d.getInt(o + 0x4C), d.getInt(o + 0x74), size))
        }
        entries = list
        val root = entries.firstOrNull { it.type == TYPE_ROOT } ?: throw CorruptDocumentException("루트 디렉터리가 없습니다.")

        // 미니 FAT / 미니 스트림
        miniFat = if (numMiniFat > 0 && firstMiniFat >= 0) {
            val bytes = readChain(firstMiniFat, null)
            val arr = IntArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(arr)
            arr
        } else IntArray(0)
        miniStream = if (root.startSector >= 0 && root.size > 0 && root.size <= src.size) {
            readChain(root.startSector, root.size)
        } else ByteArray(0)

        paths = buildPaths(root)
    }

    private fun buildPaths(root: Entry): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        val visited = BooleanArray(entries.size)
        // (storage child index, prefix)
        val stack = ArrayDeque<Pair<Int, String>>()
        stack.add(root.child to "")
        while (stack.isNotEmpty()) {
            val (start, prefix) = stack.removeLast()
            // 형제 트리(red-black tree) 순회
            val siblings = ArrayDeque<Int>()
            siblings.add(start)
            while (siblings.isNotEmpty()) {
                val idx = siblings.removeLast()
                if (idx < 0 || idx >= entries.size || visited[idx]) continue
                visited[idx] = true
                val e = entries[idx]
                siblings.add(e.left)
                siblings.add(e.right)
                val path = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                result[path] = e
                if (e.isStorage) stack.add(e.child to path)
            }
        }
        return result
    }

    fun has(path: String) = paths[path]?.isStream == true

    fun read(path: String): ByteArray {
        val e = paths[path] ?: throw CorruptDocumentException("'$path' 스트림이 없습니다.")
        return read(e)
    }

    fun read(e: Entry): ByteArray {
        // 손상된 크기 값으로 거대한 배열을 만들지 않도록 실제로 가능한 최대 크기와 비교
        if (e.size < 0 || e.size > MAX_STREAM || e.size > src.size) throw CorruptDocumentException("스트림 크기가 비정상입니다.")
        if (e.size == 0L) return ByteArray(0)
        return if (e.size < miniStreamCutoff) {
            if (e.size > miniStream.size) throw CorruptDocumentException("스트림 크기가 비정상입니다.")
            readMiniChain(e.startSector, e.size.toInt())
        } else readChain(e.startSector, e.size)
    }

    /** storage 바로 아래의 자식 스트림 이름들 */
    fun children(storage: String): List<String> {
        val p = "$storage/"
        return paths.keys.filter { it.startsWith(p) && !it.substring(p.length).contains('/') }
            .map { it.substring(p.length) }
    }

    private fun readSector(sector: Int, dst: ByteArray) {
        val pos = (sector.toLong() + 1) shl sectorShift
        if (sector < 0 || pos + sectorSize > src.size) {
            // 마지막 섹터가 잘린 파일 허용: 남은 만큼만 읽고 0으로 채움
            if (sector >= 0 && pos < src.size) {
                val n = (src.size - pos).toInt()
                src.readFully(pos, dst, 0, n)
                dst.fill(0, n, dst.size)
                return
            }
            throw CorruptDocumentException("섹터 범위를 벗어났습니다 ($sector)")
        }
        src.readFully(pos, dst, 0, sectorSize)
    }

    private fun readChain(start: Int, size: Long?): ByteArray {
        val maxSectors = fat.size
        if (size != null) {
            val out = ByteArray(size.toInt())
            var sector = start
            var off = 0
            var steps = 0
            val sec = ByteArray(sectorSize)
            while (off < out.size) {
                if (sector < 0 || sector >= maxSectors || steps++ > maxSectors) {
                    throw CorruptDocumentException("스트림 체인이 손상되었습니다.")
                }
                readSector(sector, sec)
                val n = minOf(sectorSize, out.size - off)
                System.arraycopy(sec, 0, out, off, n)
                off += n
                sector = fat[sector]
            }
            return out
        }
        // 크기를 모르는 체인(디렉터리, 미니FAT)
        val parts = ArrayList<ByteArray>()
        var sector = start
        var steps = 0
        while (sector >= 0) {
            if (sector >= maxSectors || steps++ > maxSectors) throw CorruptDocumentException("체인이 손상되었습니다.")
            val sec = ByteArray(sectorSize)
            readSector(sector, sec)
            parts.add(sec)
            sector = fat[sector]
        }
        val out = ByteArray(parts.size * sectorSize)
        parts.forEachIndexed { i, b -> System.arraycopy(b, 0, out, i * sectorSize, sectorSize) }
        return out
    }

    private fun readMiniChain(start: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var sector = start
        var off = 0
        var steps = 0
        while (off < size) {
            if (sector < 0 || sector >= miniFat.size || steps++ > miniFat.size) {
                throw CorruptDocumentException("미니 스트림 체인이 손상되었습니다.")
            }
            val pos = sector * miniSectorSize
            val n = minOf(miniSectorSize, size - off)
            if (pos + n > miniStream.size) throw CorruptDocumentException("미니 스트림 범위 오류")
            System.arraycopy(miniStream, pos, out, off, n)
            off += n
            sector = miniFat[sector]
        }
        return out
    }

    companion object {
        private const val HEADER_SIZE = 512
        private const val DIR_ENTRY_SIZE = 128
        private const val TYPE_STORAGE = 1
        private const val TYPE_STREAM = 2
        private const val TYPE_ROOT = 5
        private const val MAX_STREAM = 512L * 1024 * 1024

        private val SIGNATURE = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )

        fun isCompoundFile(head: ByteArray): Boolean =
            head.size >= 8 && (0 until 8).all { head[it] == SIGNATURE[it] }
    }
}
