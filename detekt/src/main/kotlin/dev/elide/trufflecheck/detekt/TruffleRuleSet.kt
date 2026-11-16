/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtFile

class TruffleRules : RuleSetProvider {
  override val ruleSetId = RuleSetId("Truffle")

  override fun instance() =
    RuleSet(
      ruleSetId,
      listOf(
        ::TruffleAnalysisError,
        ::TruffleStackfulException,
        ::TruffleForbiddenCollection,
        ::TruffleHostInterop,
        ::TruffleBlockingInNode,
        ::TruffleHostValueInImageHeap,
        ::TruffleNodeChildren,
        ::TruffleCompilationFinalDimensions,
        ::TruffleCompilationFinalWrite,
        ::TruffleMissingBoundary,
        ::TruffleSkippedException,
        ::TruffleChildWriteWithoutInsert,
        ::TruffleChildrenLoop,
        ::TruffleDeoptLoop,
        ::TruffleStaticContextState,
        ::TruffleFrameEscape,
        ::TruffleNonConstantNodeState,
        ::TruffleGenericCatch,
        ::TruffleGuestLoop,
        ::TruffleFastPathRecursion,
        ::TruffleDeprecatedApi,
        ::TruffleDslSuppressions,
        ::TruffleLegacyProfile,
        ::TruffleUncachedOnFastPath,
        ::TruffleBoundaryExceptionPolicy,
        ::TruffleSlowPathInline,
        ::TruffleBytecodeSwitch,
        ::TruffleFieldUpdaterConstant,
        ::TruffleDeclaredType,
        ::TruffleKotlinNodeHazards,
      ),
    )
}

/** Message prefix elide-dev/runtime's check.mts uses to recognize compiler-plugin gaps in detekt's analysis. */
const val TOLERATED = "Tolerated: "

class TruffleAnalysisError(config: Config) :
  Rule(config, "Kotlin analysis must resolve the selected sources"), RequiresAnalysisApi {
  override fun visitKtFile(file: KtFile) {
    analyze(file) {
      val errors =
        file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS).filter {
          it.severity == KaSeverity.ERROR
        }
      // detekt's standalone analysis runs no compiler plugins: kotlinx.serialization's generated
      // serializer() is unresolved and inference fails around it. kotlinc already compiles these,
      // so consumers may accept them as long as every compiler error detekt counts is accounted for.
      val prefix =
        if (errors.any { it.defaultMessage == "Unresolved reference 'serializer'." }) TOLERATED
        else ""
      for (diagnostic in errors) {
        report(Finding(Entity.from(diagnostic.psi), prefix + diagnostic.defaultMessage))
      }
    }
  }
}
