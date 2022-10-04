/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.library.metadata

import org.jetbrains.kotlin.descriptors.ModuleCapability
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.isInterop

sealed class KlibModuleOrigin {

    companion object {
        val CAPABILITY = ModuleCapability<KlibModuleOrigin>("KlibModuleOrigin")
    }
}

sealed class CompiledKlibModuleOrigin: KlibModuleOrigin()

class DeserializedKlibModuleOrigin(val library: KotlinLibrary) : CompiledKlibModuleOrigin()

object CurrentKlibModuleOrigin: CompiledKlibModuleOrigin()

object SyntheticModulesOrigin : KlibModuleOrigin()

internal fun KlibModuleOrigin.isInteropLibrary(): Boolean = when (this) {
    is DeserializedKlibModuleOrigin -> this.library.isInterop
    CurrentKlibModuleOrigin, SyntheticModulesOrigin -> false
}

val ModuleDescriptor.klibModuleOrigin get() = this.getCapability(KlibModuleOrigin.CAPABILITY)!!

val ModuleDescriptor.kotlinLibrary get() =
    (this.klibModuleOrigin as DeserializedKlibModuleOrigin)
        .library

sealed class CompiledKlibFileOrigin {
    object CurrentFile : CompiledKlibFileOrigin() // No dependency should be added.

    object StdlibRuntime : CompiledKlibFileOrigin()

    object StdlibKFunctionImpl : CompiledKlibFileOrigin()

    class EntireModule(val library: KotlinLibrary) : CompiledKlibFileOrigin()

    class CertainFile(val library: KotlinLibrary, val fqName: String, val filePath: String) : CompiledKlibFileOrigin()
}