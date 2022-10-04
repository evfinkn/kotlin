/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.konan.serialization.*
import org.jetbrains.kotlin.backend.konan.serialization.ClassFieldsSerializer
import org.jetbrains.kotlin.backend.konan.serialization.InlineFunctionBodyReferenceSerializer
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.uniqueName

private fun getArtifactName(target: KonanTarget, baseName: String, kind: CompilerOutputKind) =
        "${kind.prefix(target)}$baseName${kind.suffix(target)}"

class CachedLibraries(
        private val target: KonanTarget,
        allLibraries: List<KotlinLibrary>,
        explicitCaches: Map<KotlinLibrary, String>,
        implicitCacheDirectories: List<File>
) {
    enum class Kind { DYNAMIC, STATIC }

    sealed class BitcodeDependency(val libName: String) {
        class WholeModule(libName: String) : BitcodeDependency(libName)

        class CertainFiles(libName: String, val files: List<String>) : BitcodeDependency(libName)
    }

    sealed class Cache(protected val target: KonanTarget, val kind: Kind, val path: String) {
        val bitcodeDependencies by lazy { computeBitcodeDependencies() }
        val binariesPaths by lazy { computeBinariesPaths() }
        val serializedInlineFunctionBodies by lazy { computeSerializedInlineFunctionBodies() }
        val serializedClassFields by lazy { computeSerializedClassFields() }
        val serializedEagerInitializedFiles by lazy { computeSerializedEagerInitializedFiles() }

        protected abstract fun computeBitcodeDependencies(): List<BitcodeDependency>
        protected abstract fun computeBinariesPaths(): List<String>
        protected abstract fun computeSerializedInlineFunctionBodies(): List<SerializedInlineFunctionReference>
        protected abstract fun computeSerializedClassFields(): List<SerializedClassFields>
        protected abstract fun computeSerializedEagerInitializedFiles(): List<SerializedEagerInitializedFile>

        protected fun Kind.toCompilerOutputKind(): CompilerOutputKind = when (this) {
            Kind.DYNAMIC -> CompilerOutputKind.DYNAMIC_CACHE
            Kind.STATIC -> CompilerOutputKind.STATIC_CACHE
        }

        protected fun parseDependencies(dependencies: List<String>): List<BitcodeDependency> {
            val wholeModuleDependencies = mutableListOf<String>()
            val fileDependencies = mutableMapOf<String, MutableList<String>>()
            for (dependency in dependencies) {
                val delimiterIndex = dependency.lastIndexOf(DEPENDENCIES_DELIMITER)
                require(delimiterIndex >= 0) { "Invalid dependency $dependency of library $path" }
                val libName = dependency.substring(0, delimiterIndex)
                val file = dependency.substring(delimiterIndex + 1, dependency.length)
                if (file.isEmpty())
                    wholeModuleDependencies.add(libName)
                else
                    fileDependencies.getOrPut(libName) { mutableListOf() }.add(file)
            }
            return wholeModuleDependencies.map { BitcodeDependency.WholeModule(it) } +
                    fileDependencies.map { (libName, files) -> BitcodeDependency.CertainFiles(libName, files) }
        }

        class Monolithic(target: KonanTarget, kind: Kind, path: String) : Cache(target, kind, path) {
            override fun computeBitcodeDependencies() =
                    parseDependencies(File(path).parentFile.child(BITCODE_DEPENDENCIES_FILE_NAME).readStrings())

            override fun computeBinariesPaths() = listOf(path)

            override fun computeSerializedInlineFunctionBodies() = mutableListOf<SerializedInlineFunctionReference>().also {
                val directory = File(path).absoluteFile.parentFile.parentFile
                val data = directory.child(PER_FILE_CACHE_IR_LEVEL_DIR_NAME).child(INLINE_FUNCTION_BODIES_FILE_NAME).readBytes()
                InlineFunctionBodyReferenceSerializer.deserializeTo(data, it)
            }

            override fun computeSerializedClassFields() = mutableListOf<SerializedClassFields>().also {
                val directory = File(path).absoluteFile.parentFile.parentFile
                val data = directory.child(PER_FILE_CACHE_IR_LEVEL_DIR_NAME).child(CLASS_FIELDS_FILE_NAME).readBytes()
                ClassFieldsSerializer.deserializeTo(data, it)
            }

            override fun computeSerializedEagerInitializedFiles() = mutableListOf<SerializedEagerInitializedFile>().also {
                val directory = File(path).absoluteFile.parentFile.parentFile
                val data = directory.child(PER_FILE_CACHE_IR_LEVEL_DIR_NAME).child(EAGER_INITIALIZED_PROPERTIES_FILE_NAME).readBytes()
                EagerInitializedPropertySerializer.deserializeTo(data, it)
            }
        }

        class PerFile(target: KonanTarget, kind: Kind, path: String) : Cache(target, kind, path) {
            private val fileDirs by lazy { File(path).listFiles.filter { it.isDirectory }.sortedBy { it.name } }

            private val perFileBitcodeDependencies by lazy {
                fileDirs.associate {
                    it.name to parseDependencies(it.child(PER_FILE_CACHE_BINARY_LEVEL_DIR_NAME).child(BITCODE_DEPENDENCIES_FILE_NAME).readStrings())
                }
            }

            fun getFileDependencies(file: String) =
                    perFileBitcodeDependencies[file] ?: error("File $file is not found in cache $path")

            fun getFileBinaryPath(file: String) =
                    File(path).child(file).child(PER_FILE_CACHE_BINARY_LEVEL_DIR_NAME).child(getArtifactName(target, file, kind.toCompilerOutputKind())).let {
                        require(it.exists) { "File $file is not found in cache $path" }
                        it.absolutePath
                    }

            override fun computeBitcodeDependencies() = perFileBitcodeDependencies.values.flatten()

            override fun computeBinariesPaths() = fileDirs.map {
                it.child(PER_FILE_CACHE_BINARY_LEVEL_DIR_NAME).child(getArtifactName(target, it.name, kind.toCompilerOutputKind())).absolutePath
            }

            override fun computeSerializedInlineFunctionBodies() = mutableListOf<SerializedInlineFunctionReference>().also {
                fileDirs.forEach { fileDir ->
                    val data = fileDir.child(PER_FILE_CACHE_IR_LEVEL_DIR_NAME).child(INLINE_FUNCTION_BODIES_FILE_NAME).readBytes()
                    InlineFunctionBodyReferenceSerializer.deserializeTo(data, it)
                }
            }

            override fun computeSerializedClassFields() = mutableListOf<SerializedClassFields>().also {
                fileDirs.forEach { fileDir ->
                    val data = fileDir.child(PER_FILE_CACHE_IR_LEVEL_DIR_NAME).child(CLASS_FIELDS_FILE_NAME).readBytes()
                    ClassFieldsSerializer.deserializeTo(data, it)
                }
            }

            override fun computeSerializedEagerInitializedFiles() = mutableListOf<SerializedEagerInitializedFile>().also {
                fileDirs.forEach { fileDir ->
                    val data = fileDir.child(PER_FILE_CACHE_IR_LEVEL_DIR_NAME).child(EAGER_INITIALIZED_PROPERTIES_FILE_NAME).readBytes()
                    EagerInitializedPropertySerializer.deserializeTo(data, it)
                }
            }
        }
    }

    private val cacheDirsContents = mutableMapOf<String, Set<String>>()

    private fun selectCache(library: KotlinLibrary, cacheDir: File): Cache? {
        // See Linker.renameOutput why is it ok to have an empty cache directory.
        val cacheDirContents = cacheDirsContents.getOrPut(cacheDir.absolutePath) {
            cacheDir.listFilesOrEmpty.map { it.absolutePath }.toSet()
        }
        if (cacheDirContents.isEmpty()) return null
        val cacheBinaryPartDir = cacheDir.child(PER_FILE_CACHE_BINARY_LEVEL_DIR_NAME)
        val cacheBinaryPartDirContents = cacheDirsContents.getOrPut(cacheBinaryPartDir.absolutePath) {
            cacheBinaryPartDir.listFilesOrEmpty.map { it.absolutePath }.toSet()
        }
        val baseName = getCachedLibraryName(library)
        val dynamicFile = cacheBinaryPartDir.child(getArtifactName(target, baseName, CompilerOutputKind.DYNAMIC_CACHE))
        val staticFile = cacheBinaryPartDir.child(getArtifactName(target, baseName, CompilerOutputKind.STATIC_CACHE))

        if (dynamicFile.absolutePath in cacheBinaryPartDirContents && staticFile.absolutePath in cacheBinaryPartDirContents)
            error("Both dynamic and static caches files cannot be in the same directory." +
                    " Library: ${library.libraryName}, path to cache: ${cacheDir.absolutePath}")
        return when {
            dynamicFile.absolutePath in cacheBinaryPartDirContents -> Cache.Monolithic(target, Kind.DYNAMIC, dynamicFile.absolutePath)
            staticFile.absolutePath in cacheBinaryPartDirContents -> Cache.Monolithic(target, Kind.STATIC, staticFile.absolutePath)
            else -> Cache.PerFile(target, Kind.STATIC, cacheDir.absolutePath)
        }
    }

    private val allCaches: Map<KotlinLibrary, Cache> = allLibraries.mapNotNull { library ->
        val explicitPath = explicitCaches[library]

        val cache = if (explicitPath != null) {
            selectCache(library, File(explicitPath))
                    ?: error("No cache found for library ${library.libraryName} at $explicitPath")
        } else {
            implicitCacheDirectories.firstNotNullOfOrNull { dir ->
                selectCache(library, dir.child(getPerFileCachedLibraryName(library)))
                        ?: selectCache(library, dir.child(getCachedLibraryName(library)))
            }
        }

        cache?.let { library to it }
    }.toMap()

    fun isLibraryCached(library: KotlinLibrary): Boolean =
            getLibraryCache(library) != null

    fun getLibraryCache(library: KotlinLibrary): Cache? =
            allCaches[library]

    val hasStaticCaches = allCaches.values.any {
        when (it.kind) {
            Kind.STATIC -> true
            Kind.DYNAMIC -> false
        }
    }

    val hasDynamicCaches = allCaches.values.any {
        when (it.kind) {
            Kind.STATIC -> false
            Kind.DYNAMIC -> true
        }
    }

    companion object {
        fun getPerFileCachedLibraryName(library: KotlinLibrary): String = "${library.uniqueName}-per-file-cache"
        fun getCachedLibraryName(library: KotlinLibrary): String = getCachedLibraryName(library.uniqueName)
        fun getCachedLibraryName(libraryName: String): String = "$libraryName-cache"

        const val PER_FILE_CACHE_IR_LEVEL_DIR_NAME = "ir"
        const val PER_FILE_CACHE_BINARY_LEVEL_DIR_NAME = "bin"

        const val BITCODE_DEPENDENCIES_FILE_NAME = "bitcode_deps"
        const val INLINE_FUNCTION_BODIES_FILE_NAME = "inline_bodies"
        const val CLASS_FIELDS_FILE_NAME = "class_fields"
        const val EAGER_INITIALIZED_PROPERTIES_FILE_NAME = "eager_init"

        const val DEPENDENCIES_DELIMITER = '|'
    }
}
