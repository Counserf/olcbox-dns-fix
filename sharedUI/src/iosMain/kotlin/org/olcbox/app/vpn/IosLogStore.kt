package org.olcbox.app.vpn

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData

/**
 * Keeps the log on disk so a test that runs for days survives every restart:
 * the in-memory list only holds what the logs sheet shows, and iOS kills a
 * backgrounded app whenever it likes.
 *
 * Two files: the current one and the one before it. When the current file grows
 * past [MAX_FILE_BYTES] it becomes the previous one and the older previous is
 * dropped, so the log takes at most twice that and never fills the phone.
 *
 * Every failure is swallowed: losing a log line must never break the connection
 * the log is about.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosLogStore(appDirectoryName: String = "Olcbox") {

    private val lock = NSLock()
    private val directory: String? = runCatching { logDirectory(appDirectoryName) }.getOrNull()
    private val current: String? = directory?.let { "$it/olcbox.log" }
    private val previous: String? = directory?.let { "$it/olcbox.1.log" }

    /** The whole kept history, oldest line first. */
    fun history(): String {
        val previous = previous ?: return ""
        val current = current ?: return ""
        lock.lock()
        try {
            return read(previous) + read(current)
        } finally {
            lock.unlock()
        }
    }

    /** The last [limit] lines, to refill the logs sheet after a restart. */
    fun tail(limit: Int): List<String> =
        history().split("\n").filter { it.isNotBlank() }.takeLast(limit)

    fun append(line: String) {
        val path = current ?: return
        lock.lock()
        try {
            runCatching {
                val manager = NSFileManager.defaultManager
                if (!manager.fileExistsAtPath(path)) {
                    manager.createFileAtPath(path, contents = null, attributes = null)
                }
                val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return
                val data = NSString.create(string = line + "\n")
                    .dataUsingEncoding(NSUTF8StringEncoding)
                handle.seekToEndOfFile()
                if (data != null) handle.writeData(data)
                val size = handle.offsetInFile
                handle.closeFile()
                if (size > MAX_FILE_BYTES) rotate()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun rotate() {
        val current = current ?: return
        val previous = previous ?: return
        runCatching {
            val manager = NSFileManager.defaultManager
            if (manager.fileExistsAtPath(previous)) {
                manager.removeItemAtPath(previous, error = null)
            }
            manager.moveItemAtPath(current, toPath = previous, error = null)
        }
    }

    private fun read(path: String): String {
        val data = NSData.dataWithContentsOfFile(path) ?: return ""
        return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString() ?: ""
    }

    private fun logDirectory(appDirectoryName: String): String {
        val base = NSFileManager.defaultManager
            .URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
            .firstOrNull()
            ?.let { it as? NSURL }
            ?.path
            ?: error("Application Support directory is unavailable")
        val path = base.trimEnd('/') + "/" + appDirectoryName
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        return path
    }

    private companion object {
        const val MAX_FILE_BYTES = 12UL * 1024UL * 1024UL
    }
}
