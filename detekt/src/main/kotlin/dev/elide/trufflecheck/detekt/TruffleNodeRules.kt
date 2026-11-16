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
import dev.elide.trufflecheck.Policy
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

private val NODE = ClassId.topLevel(FqName("com.oracle.truffle.api.nodes.Node"))
private val BOUNDARY = FqName("com.oracle.truffle.api.CompilerDirectives.TruffleBoundary")
private val SYNCHRONIZED = FqName("kotlin.jvm.Synchronized")

private fun classId(owner: String): ClassId {
  val fq = FqName(owner)
  return ClassId.topLevel(fq)
}

/** Matches `Owner#method` policy entries against a resolved call, including overrides. */
private class Calls(policy: Policy, rule: String) {
  private val entries =
    policy.owners(rule).map { it.substringBefore('#') to it.substringAfter('#') }

  fun matches(session: KaSession, call: KaCallableMemberCall<*, *>): Boolean =
    with(session) {
      val id = call.partiallyAppliedSymbol.symbol.callableId ?: return false
      val receiver = call.partiallyAppliedSymbol.dispatchReceiver?.type
      entries.any { (owner, name) ->
        id.callableName.asString() == name &&
          (id.classId?.asSingleFqName()?.asString() == owner ||
            receiver?.isSubtypeOf(classId(owner)) == true)
      }
    }
}

private fun KtElement.call(session: KaSession): KaCallableMemberCall<*, *>? =
  with(session) { resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>() }

private fun KtFile.binaryPrefix(): String =
  packageFqName.asString().let { if (it.isEmpty()) "" else "$it." }

class TruffleHostInterop(config: Config) : Rule(config, config.policy().message("hostinterop")) {
  private val policy = config.policy()
  private val owners = policy.owners("hostinterop")
  private val scope = policy.scope("hostinterop")

  override fun visitImportDirective(importDirective: KtImportDirective) {
    super.visitImportDirective(importDirective)
    val pkg = importDirective.containingKtFile.packageFqName.asString()
    val name = importDirective.importedFqName?.asString() ?: return
    if (Policy.inPackages(pkg, scope) && Policy.inPackages(name, owners)) {
      report(Finding(Entity.from(importDirective), policy.message("hostinterop")))
    }
  }
}

class TruffleBlockingInNode(config: Config) :
  Rule(config, config.policy().message("blocking")), RequiresAnalysisApi {
  private val policy = config.policy()
  private val calls = Calls(policy, "blocking")

  override fun visitNamedFunction(function: KtNamedFunction) {
    super.visitNamedFunction(function)
    if (function.hasAnnotation(SYNCHRONIZED)) check(function, function)
  }

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    val function = expression.getStrictParentOfType<KtNamedFunction>()
    analyze(expression) {
      val call = expression.call(this) ?: return
      val id = call.partiallyAppliedSymbol.symbol.callableId
      val lock = id?.asSingleFqName()?.asString() == "kotlin.synchronized"
      if (lock || calls.matches(this, call)) check(expression, function)
    }
  }

  private fun KtNamedFunction.hasAnnotation(name: FqName): Boolean =
    annotationEntries.any { entry ->
      val short = entry.shortName?.asString()
      short == name.shortName().asString() ||
        entry.typeReference?.text == name.asString() ||
        entry.typeReference?.text?.endsWith(".${name.shortName()}") == true
    }

  private fun check(element: KtElement, function: KtNamedFunction?) {
    if (function?.hasAnnotation(BOUNDARY) == true) return
    val owner = element.getStrictParentOfType<KtClassOrObject>() ?: return
    analyze(owner) {
      val type = owner.classSymbol?.defaultType ?: return
      if (type.isSubtypeOf(NODE)) {
        report(Finding(Entity.from(element), policy.message("blocking")))
      }
    }
  }
}

class TruffleHostValueInImageHeap(config: Config) :
  Rule(config, config.policy().message("hostvalue")), RequiresAnalysisApi {
  private val policy = config.policy()
  private val calls = Calls(policy, "hostvalue")

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    val owner = staticOwner(expression) ?: return
    if (!policy.initializedAtBuildTime(owner)) return
    analyze(expression) {
      val call = expression.call(this) ?: return
      if (calls.matches(this, call)) {
        report(Finding(Entity.from(expression), policy.message("hostvalue")))
      }
    }
  }

  /** Binary name of the class whose static initializer evaluates [element], or null. */
  private fun staticOwner(element: KtElement): String? {
    var current = element.parent
    while (current != null) {
      when (current) {
        is KtFunction,
        is KtPropertyAccessor,
        is KtClass -> return null
        is KtAnonymousInitializer -> {
          val holder = current.getStrictParentOfType<KtClassOrObject>()
          return (holder as? KtObjectDeclaration)
            ?.takeUnless { it.isObjectLiteral() }
            ?.let(::binaryName)
        }
        is KtProperty -> {
          if (current.isLocal) {
            current = current.parent
            continue
          }
          if (current.isTopLevel) {
            val file = current.containingKtFile
            val facade = file.name.removeSuffix(".kt").replaceFirstChar { it.uppercaseChar() }
            return file.binaryPrefix() + facade + "Kt"
          }
          val holder = current.getStrictParentOfType<KtClassOrObject>()
          return (holder as? KtObjectDeclaration)
            ?.takeUnless { it.isObjectLiteral() }
            ?.let(::binaryName)
        }
      }
      current = current.parent
    }
    return null
  }

  /** Companion statics live on the outer class; named objects are their own class. */
  private fun binaryName(declaration: KtObjectDeclaration): String? {
    val target =
      if (declaration.isCompanion()) declaration.getStrictParentOfType<KtClassOrObject>()
      else declaration
    val chain = generateSequence(target) { it.getStrictParentOfType<KtClassOrObject>() }.toList()
    if (target == null || chain.any { it.name == null }) return null
    return target.containingKtFile.binaryPrefix() +
      chain.asReversed().joinToString("$") { it.name!! }
  }
}
