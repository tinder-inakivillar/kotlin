/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.executeOnPooledThreadInReadAction
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getOrBuildFir
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getResolveState
import org.jetbrains.kotlin.idea.jsonUtils.getString
import org.jetbrains.kotlin.idea.stubs.AbstractMultiModuleTest
import org.jetbrains.kotlin.idea.util.sourceRoots
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.util.KtTestUtil
import org.jetbrains.kotlin.types.typeUtil.closure
import java.io.File
import java.nio.file.Paths

/**
 * The idea behind this test is to check that [FirIdeSealedHierarchyProcessor] finds all direct inheritors of sealed classes and interfaces.
 * We use the fact that [SealedClassInheritorsKt#getSealedInheritors] property gets its value thanks to the class activity.
 *
 * Inheritors are collected for every sealed declaration of the 'fileToResolve' (see test data 'structure.json'). Resulting collection is
 * compared with 'expected.txt' ignoring the order.
 */
abstract class AbstractFirSealedInheritorsTest : AbstractMultiModuleTest() {
    override fun getTestDataPath(): String =
        "${KtTestUtil.getHomeDirectory()}/idea/idea-frontend-fir/idea-fir-low-level-api/testdata/resolveSealed/"

    fun doTest(path: String) {
        val testStructure = TestProjectStructureForSealed.fromTestProjectStructure(
            TestProjectStructureReader.read(Paths.get(path))
        )

        val testProjectModule = testStructure.module
        val moduleToResolve = module(testProjectModule.name)

        val fileToAnalysePath = moduleToResolve.sourceRoots.first().url + "/" + testStructure.fileToResolve.relativeFilePath

        val virtualFileToAnalyse = VirtualFileManager.getInstance().findFileByUrl(fileToAnalysePath)
            ?: error("File ${testStructure.fileToResolve.filePath} not found")
        val ktFileToAnalyse = PsiManager.getInstance(project).findFile(virtualFileToAnalyse) as KtFile
        val resolveState = ktFileToAnalyse.getResolveState()

        val inheritorIds = executeOnPooledThreadInReadAction {
            val firFile = ktFileToAnalyse.getOrBuildFir(resolveState) as FirFile
            val allClasses = firFile.listNestedClasses().closure { it.listNestedClasses() }
            allClasses.flatMap { it.sealedInheritors ?: emptyList() }.toList()
        }

        val inheritorNames = inheritorIds?.map { it.asString() }?.toList() ?: emptyList()
        KotlinTestUtils.assertEqualsToFileIgnoreOrder(File("$path/expected.txt"), inheritorNames)
    }
}

private fun FirDeclaration.listNestedClasses(): List<FirRegularClass> {
    return when (this) {
        is FirFile -> declarations.filterIsInstance<FirRegularClass>()
        is FirRegularClass -> declarations.filterIsInstance<FirRegularClass>()
        else -> emptyList()
    }
}

private data class FileToResolveForSealed(val moduleName: String, val relativeFilePath: String) {
    val filePath get() = "$moduleName/$relativeFilePath"

    companion object {
        fun parse(json: JsonElement): FileToResolveForSealed {
            require(json is JsonObject)
            return FileToResolveForSealed(
                moduleName = json.getString("module"),
                relativeFilePath = json.getString("file")
            )
        }
    }
}

private data class TestProjectStructureForSealed(
    val module: TestProjectModule,
    val fileToResolve: FileToResolveForSealed
) {
    companion object {
        fun fromTestProjectStructure(testProjectStructure: TestProjectStructure): TestProjectStructureForSealed {
            if (testProjectStructure.modules.size > 1)
                throw IllegalStateException("Multi module projects for sealed resolution are not supported so far")

            val module = testProjectStructure.modules.first()
            val json = testProjectStructure.json
            val fileToResolve = FileToResolveForSealed.parse(json.getAsJsonObject("fileToResolve"))

            return TestProjectStructureForSealed(module, fileToResolve)
        }
    }
}