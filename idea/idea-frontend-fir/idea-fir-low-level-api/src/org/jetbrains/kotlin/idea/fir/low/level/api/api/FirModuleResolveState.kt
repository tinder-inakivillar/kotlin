/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.api

import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.resolve.providers.FirProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.PossiblyFirFakeOverrideSymbol
import org.jetbrains.kotlin.idea.caches.project.IdeaModuleInfo
import org.jetbrains.kotlin.idea.caches.project.getModuleInfo
import org.jetbrains.kotlin.idea.fir.low.level.api.FirIdeResolveStateService
import org.jetbrains.kotlin.idea.fir.low.level.api.FirTransformerProvider
import org.jetbrains.kotlin.idea.fir.low.level.api.element.builder.FirTowerDataContextCollector
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.FirFileBuilder
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.ModuleFileCache
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.withReadLock
import org.jetbrains.kotlin.idea.fir.low.level.api.sessions.FirIdeSourcesSession
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ktDeclaration
import org.jetbrains.kotlin.idea.util.getElementTextInContext
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression

abstract class FirModuleResolveState {
    abstract val rootModuleSession: FirSession

    internal abstract val moduleInfo: IdeaModuleInfo

    abstract val firTransformerProvider: FirTransformerProvider

    internal abstract fun getSessionFor(moduleInfo: IdeaModuleInfo): FirSession

    internal abstract fun getOrBuildFirFor(element: KtElement): FirElement

    internal abstract fun getFirFile(ktFile: KtFile): FirFile

    internal abstract fun isFirFileBuilt(ktFile: KtFile): Boolean

    internal abstract fun getDiagnostics(element: KtElement): List<Diagnostic>

    internal abstract fun collectDiagnosticsForFile(ktFile: KtFile): Collection<Diagnostic>

    @TestOnly
    internal abstract fun getBuiltFirFileOrNull(ktFile: KtFile): FirFile?

    abstract fun findNonLocalSourceFirDeclaration(
        ktDeclaration: KtDeclaration,
    ): FirDeclaration

    abstract fun findSourceFirDeclaration(
        ktDeclaration: KtDeclaration,
    ): FirDeclaration

    abstract fun findSourceFirDeclaration(
        ktDeclaration: KtLambdaExpression,
    ): FirDeclaration


    internal abstract fun <D : FirDeclaration> resolvedFirToPhase(declaration: D, toPhase: FirResolvePhase): D

    // todo temporary, used only in completion
    internal abstract fun lazyResolveDeclarationForCompletion(
        firFunction: FirDeclaration,
        containerFirFile: FirFile,
        firIdeProvider: FirProvider,
        towerDataContextCollector: FirTowerDataContextCollector
    )

    internal abstract fun getFirFile(declaration: FirDeclaration, cache: ModuleFileCache): FirFile?

    fun <D : FirDeclaration, R> withFirDeclaration(declaration: D, action: (D) -> R): R {
        val originalDeclaration = declaration.unrollFakeOverrides()
        val session = originalDeclaration.session
        return when {
            originalDeclaration.origin == FirDeclarationOrigin.Source
                    && session is FirIdeSourcesSession
            -> {
                val cache = session.cache
                val file = getFirFile(declaration, cache)
                    ?: error("Fir file was not found for\n${declaration.render()}\n${declaration.ktDeclaration.getElementTextInContext()}")
                cache.firFileLockProvider.withReadLock(file) { action(declaration) }
            }
            else -> action(declaration)
        }
    }
}

internal fun <D> D.unrollFakeOverrides(): D where D : FirDeclaration {
    check(this is FirSymbolOwner<*>)
    val symbol = symbol
    if (symbol !is PossiblyFirFakeOverrideSymbol<*, *>) return this
    if (!symbol.isFakeOrIntersectionOverride) return this
    var current: FirBasedSymbol<*>? = symbol.overriddenSymbol
    while (current is PossiblyFirFakeOverrideSymbol<*, *> && current.isFakeOrIntersectionOverride) {
        current = current.overriddenSymbol
    }
    return current?.fir as D
}

private inline val FirBasedSymbol<*>.isFakeOrIntersectionOverride: Boolean
    get() {
        val origin = (fir as? FirDeclaration)?.origin ?: return false
        return origin == FirDeclarationOrigin.SubstitutionOverride || origin == FirDeclarationOrigin.IntersectionOverride
    }
