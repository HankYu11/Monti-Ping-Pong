// What the `core` tests call beyond the main code's JVM stand-ins: a resource file, File's walk up
// to the repository root, and readText.
package com.monti.servecoach.core

import java.io.readBytesOf

class Resource(private val path: String) { fun readText(): String = java.io.File(path).readText() }
class ResourceLoader { fun getResource(name: String): Resource? = java.io.File(TEST_RESOURCES + name).takeIf { it.exists() }?.let { Resource(it.path) } }
@Suppress("unused") val Any.javaClass: ResourceLoader get() = ResourceLoader()
fun java.io.File.readText(): String = readBytesOf(path).decodeToString()
