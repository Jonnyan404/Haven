package sh.haven.core.mosh

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

class MoshConnectionTest {

    @Test
    fun `zlib compress decompress roundtrip`() {
        val original = "Hello, mosh! This is a test of zlib compression.".toByteArray()
        val compressed = testZlibCompress(original)
        val decompressed = testZlibDecompress(compressed)
        assertArrayEquals(original, decompressed)
    }

    @Test
    fun `zlib roundtrip with empty data`() {
        val original = ByteArray(0)
        val compressed = testZlibCompress(original)
        val decompressed = testZlibDecompress(compressed)
        assertArrayEquals(original, decompressed)
    }

    @Test
    fun `zlib roundtrip with binary data`() {
        val original = ByteArray(1024) { (it % 256).toByte() }
        val compressed = testZlibCompress(original)
        val decompressed = testZlibDecompress(compressed)
        assertArrayEquals(original, decompressed)
    }

    @Test
    fun `zlib roundtrip with protobuf-like data`() {
        val original = byteArrayOf(
            0x08, 0x02,             // field 1 varint: protocol_version=2
            0x10, 0x00,             // field 2 varint: old_num=0
            0x18, 0x05,             // field 3 varint: new_num=5
            0x20, 0x03,             // field 4 varint: ack_num=3
            0x32, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F, // field 6 bytes: "Hello"
        )
        val compressed = testZlibCompress(original)
        val decompressed = testZlibDecompress(compressed)
        assertArrayEquals(original, decompressed)
    }

    private fun testZlibCompress(data: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val buf = ByteArray(1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun testZlibDecompress(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 2)
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
