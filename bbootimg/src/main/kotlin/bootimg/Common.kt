package cfig.bootimg

import cfig.EnvironmentVerifier
import cfig.Helper
import cfig.dtb_util.DTC
import cfig.io.Struct3.InputStreamExt.Companion.getInt
import cfig.kernel_util.KernelExtractor
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.PumpStreamHandler
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.regex.Pattern

@OptIn(ExperimentalUnsignedTypes::class)
class Common {
    data class VeritySignature(
            var type: String = "dm-verity",
            var path: String = "/boot",
            var verity_pk8: String = "",
            var verity_pem: String = "",
            var jarPath: String = "")

    data class Slice(
            var srcFile: String,
            var offset: Int,
            var length: Int,
            var dumpFile: String
    )

    companion object {
        private val log = LoggerFactory.getLogger(Common::class.java)

        @Throws(IllegalArgumentException::class)
        fun packOsVersion(x: String?): Int {
            if (x.isNullOrBlank()) return 0
            val pattern = Pattern.compile("^(\\d{1,3})(?:\\.(\\d{1,3})(?:\\.(\\d{1,3}))?)?")
            val m = pattern.matcher(x)
            if (m.find()) {
                val a = Integer.decode(m.group(1))
                var b = 0
                var c = 0
                if (m.groupCount() >= 2) {
                    b = Integer.decode(m.group(2))
                }
                if (m.groupCount() == 3) {
                    c = Integer.decode(m.group(3))
                }
                assert(a < 128)
                assert(b < 128)
                assert(c < 128)
                return (a shl 14) or (b shl 7) or c
            } else {
                throw IllegalArgumentException("invalid os_version")
            }
        }

        fun parseOsVersion(x: Int): String {
            val a = x shr 14
            val b = x - (a shl 14) shr 7
            val c = x and 0x7f
            return String.format("%d.%d.%d", a, b, c)
        }

        fun packOsPatchLevel(x: String?): Int {
            if (x.isNullOrBlank()) return 0
            val ret: Int
            val pattern = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})")
            val matcher = pattern.matcher(x)
            if (matcher.find()) {
                val y = Integer.parseInt(matcher.group(1), 10) - 2000
                val m = Integer.parseInt(matcher.group(2), 10)
                // 7 bits allocated for the year, 4 bits for the month
                assert(y in 0..127)
                assert(m in 1..12)
                ret = (y shl 4) or m
            } else {
                throw IllegalArgumentException("invalid os_patch_level")
            }

            return ret
        }

        fun parseOsPatchLevel(x: Int): String {
            var y = x shr 4
            val m = x and 0xf
            y += 2000
            return String.format("%d-%02d-%02d", y, m, 0)
        }

        fun parseKernelInfo(kernelFile: String): List<String> {
            KernelExtractor().let { ke ->
                if (ke.envCheck()) {
                    return ke.run(kernelFile, File("."))
                }
            }
            return listOf()
        }

        fun dumpKernel(s: Slice) {
            Helper.extractFile(s.srcFile, s.dumpFile, s.offset.toLong(), s.length)
            parseKernelInfo(s.dumpFile)
        }

        fun dumpRamdisk(s: Slice, root: String) {
            Helper.extractFile(s.srcFile, s.dumpFile, s.offset.toLong(), s.length)
            Helper.unGnuzipFile(s.dumpFile, s.dumpFile.removeSuffix(".gz"))
            unpackRamdisk(s.dumpFile.removeSuffix(".gz"), root)
        }

        fun dumpDtb(s: Slice) {
            Helper.extractFile(s.srcFile, s.dumpFile, s.offset.toLong(), s.length)
            //extract DTB
            if (EnvironmentVerifier().hasDtc) {
                DTC().decompile(s.dumpFile, s.dumpFile + ".src")
            }
        }

        fun getPaddingSize(position: UInt, pageSize: UInt): UInt {
            return (pageSize - (position and pageSize - 1U)) and (pageSize - 1U)
        }

        fun getPaddingSize(position: Int, pageSize: Int): Int {
            return (pageSize - (position and pageSize - 1)) and (pageSize - 1)
        }

        @Throws(CloneNotSupportedException::class)
        fun hashFileAndSize(vararg inFiles: String?): ByteArray {
            val md = MessageDigest.getInstance("SHA1")
            for (item in inFiles) {
                if (null == item) {
                    md.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(0)
                            .array())
                    log.debug("update null $item: " + Helper.toHexString((md.clone() as MessageDigest).digest()))
                } else {
                    val currentFile = File(item)
                    FileInputStream(currentFile).use { iS ->
                        var byteRead: Int
                        val dataRead = ByteArray(1024)
                        while (true) {
                            byteRead = iS.read(dataRead)
                            if (-1 == byteRead) {
                                break
                            }
                            md.update(dataRead, 0, byteRead)
                        }
                        log.debug("update file $item: " + Helper.toHexString((md.clone() as MessageDigest).digest()))
                        md.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(currentFile.length().toInt())
                                .array())
                        log.debug("update SIZE $item: " + Helper.toHexString((md.clone() as MessageDigest).digest()))
                    }
                }
            }

            return md.digest()
        }

        fun assertFileEquals(file1: String, file2: String) {
            val hash1 = hashFileAndSize(file1)
            val hash2 = hashFileAndSize(file2)
            log.info("$file1 hash ${Helper.toHexString(hash1)}, $file2 hash ${Helper.toHexString(hash2)}")
            if (hash1.contentEquals(hash2)) {
                log.info("Hash verification passed: ${Helper.toHexString(hash1)}")
            } else {
                log.error("Hash verification failed")
                throw UnknownError("Do not know why hash verification fails, maybe a bug")
            }
        }

        fun packRootfs(rootDir: String, ramdiskGz: String) {
            val mkbootfs = Helper.prop("mkbootfsBin")
            log.info("Packing rootfs $rootDir ...")
            val outputStream = ByteArrayOutputStream()
            DefaultExecutor().let { exec ->
                exec.streamHandler = PumpStreamHandler(outputStream)
                val cmdline = "$mkbootfs $rootDir"
                log.info(cmdline)
                exec.execute(CommandLine.parse(cmdline))
            }
            Helper.gnuZipFile2(ramdiskGz, ByteArrayInputStream(outputStream.toByteArray()))
            log.info("$ramdiskGz is ready")
        }

        fun padFile(inBF: ByteBuffer, padding: Int) {
            val pad = padding - (inBF.position() and padding - 1) and padding - 1
            inBF.put(ByteArray(pad))
        }

        fun File.deleleIfExists() {
            if (this.exists()) {
                if (!this.isFile) {
                    throw IllegalStateException("${this.canonicalPath} should be regular file")
                }
                log.info("Deleting ${this.path} ...")
                this.delete()
            }
        }

        fun writePaddedFile(inBF: ByteBuffer, srcFile: String, padding: UInt) {
            log.info("adding $srcFile into buffer ...")
            assert(padding < Int.MAX_VALUE.toUInt())
            writePaddedFile(inBF, srcFile, padding.toInt())
        }

        fun writePaddedFile(inBF: ByteBuffer, srcFile: String, padding: Int) {
            FileInputStream(srcFile).use { iS ->
                var byteRead: Int
                val dataRead = ByteArray(128)
                while (true) {
                    byteRead = iS.read(dataRead)
                    if (-1 == byteRead) {
                        break
                    }
                    inBF.put(dataRead, 0, byteRead)
                }
                padFile(inBF, padding)
            }
        }

        fun unpackRamdisk(ramdisk: String, root: String) {
            val rootFile = File(root).apply {
                if (exists()) {
                    log.info("Cleaning [$root] before ramdisk unpacking")
                    deleteRecursively()
                }
                mkdirs()
            }

            DefaultExecutor().let { exe ->
                exe.workingDirectory = rootFile
                exe.execute(CommandLine.parse("cpio -i -m -F " + File(ramdisk).canonicalPath))
                log.info(" ramdisk extracted : $ramdisk -> ${rootFile}")
            }
        }

        fun probeHeaderVersion(fileName: String): Int {
            return FileInputStream(fileName).let { fis ->
                fis.skip(40)
                fis.getInt(ByteOrder.LITTLE_ENDIAN)
            }
        }
    }
}
