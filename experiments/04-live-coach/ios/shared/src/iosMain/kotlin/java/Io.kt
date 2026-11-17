// java.io, as `core` uses it to write a capture file: File, FileOutputStream(...).buffered(n)
// and DataOutputStream's write and big-endian writeInt.
package java.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.toKString

class File(val path: String) {
    constructor(dir: File, name: String) : this(if (dir.path.isEmpty()) name else dir.path.trimEnd('/') + "/" + name)
    val absoluteFile: File get() = if (path.startsWith("/")) this else File(cwd().trimEnd('/') + (if (path.isEmpty()) "" else "/" + path))
    val parentFile: File? get() = path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty() || it == path) null else File(it) }
    @OptIn(ExperimentalForeignApi::class)
    fun exists(): Boolean = platform.posix.access(path, platform.posix.F_OK) == 0
    fun delete(): Boolean = platform.posix.remove(path) == 0
    override fun toString() = path
}

@OptIn(ExperimentalForeignApi::class)
private fun cwd(): String = kotlinx.cinterop.memScoped {
    val b = allocArray<kotlinx.cinterop.ByteVar>(4096)
    platform.posix.getcwd(b, 4096u)?.toKString() ?: "/"
}

/** the whole file */
@OptIn(ExperimentalForeignApi::class)
fun readBytesOf(path: String): ByteArray {
    val f = fopen(path, "rb") ?: throw IllegalStateException("can't read $path")
    try {
        platform.posix.fseek(f, 0, platform.posix.SEEK_END); val n = platform.posix.ftell(f).toInt(); platform.posix.fseek(f, 0, platform.posix.SEEK_SET)
        val out = ByteArray(n)
        if (n > 0) out.usePinned { p -> platform.posix.fread(p.addressOf(0), 1u, n.toULong(), f) }
        return out
    } finally { fclose(f) }
}

class ByteArrayOutputStream : OutputStream() {
    private var buf = ByteArray(256); private var n = 0
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (n + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + len))
        b.copyInto(buf, n, off, off + len); n += len
    }
    fun toByteArray(): ByteArray = buf.copyOf(n)
    fun size() = n
    override fun close() {}
}

abstract class OutputStream : AutoCloseable {
    abstract fun write(b: ByteArray, off: Int, len: Int)
    fun write(b: ByteArray) = write(b, 0, b.size)
}

@OptIn(ExperimentalForeignApi::class)
class FileOutputStream(file: File) : OutputStream() {
    private val f: CPointer<FILE> = fopen(file.path, "wb") ?: throw IllegalStateException("can't write ${file.path}")
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        b.usePinned { p -> if (fwrite(p.addressOf(off), 1u, len.toULong(), f) != len.toULong()) throw IllegalStateException("write failed") }
    }
    override fun close() { fclose(f) }
}

class DataOutputStream(private val out: OutputStream) : OutputStream() {
    private val four = ByteArray(4)
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    fun writeInt(v: Int) {
        four[0] = (v ushr 24).toByte(); four[1] = (v ushr 16).toByte(); four[2] = (v ushr 8).toByte(); four[3] = v.toByte()
        out.write(four, 0, 4)
    }
    override fun close() = out.close()
}
