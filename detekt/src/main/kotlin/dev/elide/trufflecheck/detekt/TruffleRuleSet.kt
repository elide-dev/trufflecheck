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
import dev.detekt.psi.FunctionMatcher.Companion.fromFunctionSignature
import dev.elide.trufflecheck.Policy
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtConstructorDelegationCall
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.ValueArgument

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
      ),
    )
}

/** Message prefix check.mts uses to recognize compiler-plugin gaps in detekt's analysis. */
const val TOLERATED = "Tolerated: "

/**
 * The policy named by this rule's `policy` key, else its rule set's, else the `trufflecheck.policy` system
 * property or `TRUFFLECHECK_POLICY`; the bundled defaults when none is set.
 */
internal fun Config.policy(): Policy =
  Policy.resolve(valueOrNull<String>("policy") ?: parent?.valueOrNull<String>("policy"))

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
      // so check.mts accepts them as long as every compiler error detekt counts is accounted for.
      val prefix =
        if (errors.any { it.defaultMessage == "Unresolved reference 'serializer'." }) TOLERATED
        else ""
      for (diagnostic in errors) {
        report(Finding(Entity.from(diagnostic.psi), prefix + diagnostic.defaultMessage))
      }
    }
  }
}

class TruffleStackfulException(config: Config) : ConstructorRule(config, "stackful")

class TruffleForbiddenCollection(config: Config) : ConstructorRule(config, "collections")

abstract class ConstructorRule(config: Config, private val rule: String) :
  Rule(config, config.policy().message(rule)), RequiresAnalysisApi {
  private val policy = config.policy()
  private val constructors =
    policy.owners(rule).flatMap { owner ->
      // Kotlin's built-in exception aliases resolve to kotlin.* symbols.
      listOf(owner, owner.replace("java.lang.", "kotlin.")).distinct().map {
        fromFunctionSignature("$it.<init>")
      }
    }

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    check(expression, expression.valueArguments)
  }

  override fun visitSuperTypeCallEntry(call: KtSuperTypeCallEntry) {
    super.visitSuperTypeCallEntry(call)
    check(call, call.valueArguments)
  }

  override fun visitConstructorDelegationCall(call: KtConstructorDelegationCall) {
    super.visitConstructorDelegationCall(call)
    check(call, call.valueArguments)
  }

  override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
    super.visitCallableReferenceExpression(expression)
    check(expression, emptyList())
  }

  private fun check(element: KtElement, arguments: List<ValueArgument>) {
    analyze(element) {
      val call =
        element.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>() ?: return
      val symbol = call.partiallyAppliedSymbol.symbol
      if (constructors.none { it.match(symbol) }) return
      if (
        rule == "stackful" &&
          arguments.size == 4 &&
          arguments[3].getArgumentExpression()?.evaluate()?.value == false
      )
        return
      report(Finding(Entity.from(element), policy.message(rule)))
    }
  }
}
