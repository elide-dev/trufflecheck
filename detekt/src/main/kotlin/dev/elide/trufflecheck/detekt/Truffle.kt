/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.elide.trufflecheck.Policy
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens

/** Truffle API names. */
internal object Truffle {
  private fun id(fq: String, nested: String? = null): ClassId {
    val base = ClassId.topLevel(FqName(fq))
    return if (nested == null) base else base.createNestedClassId(org.jetbrains.kotlin.name.Name.identifier(nested))
  }

  val NODE = id("com.oracle.truffle.api.nodes.Node")
  val NODE_INTERFACE = id("com.oracle.truffle.api.nodes.NodeInterface")
  val ROOT_NODE = id("com.oracle.truffle.api.nodes.RootNode")
  val CHILD = id("com.oracle.truffle.api.nodes.Node", "Child")
  val CHILDREN = id("com.oracle.truffle.api.nodes.Node", "Children")
  val BOUNDARY = id("com.oracle.truffle.api.CompilerDirectives", "TruffleBoundary")
  val COMPILATION_FINAL = id("com.oracle.truffle.api.CompilerDirectives", "CompilationFinal")
  val EXPORT_MESSAGE = id("com.oracle.truffle.api.library.ExportMessage")
  val SPECIALIZATION = id("com.oracle.truffle.api.dsl.Specialization")
  val FALLBACK = id("com.oracle.truffle.api.dsl.Fallback")
  val SYNCHRONIZED = id("kotlin.jvm.Synchronized")
  val THROWABLE = id("java.lang.Throwable")
  val KOTLIN_THROWABLE = id("kotlin.Throwable")
  const val DIRECTIVES = "com.oracle.truffle.api.CompilerDirectives"
  const val ASSERTS = "com.oracle.truffle.api.CompilerAsserts"
  const val HOST_DIRECTIVES = "com.oracle.truffle.api.HostCompilerDirectives"
}

/**
 * The policy named by this rule's `policy` key, else its rule set's, else the `trufflecheck.policy` system
 * property or `TRUFFLECHECK_POLICY`; the bundled defaults when none is set.
 */
internal fun Config.policy(): Policy =
  Policy.resolve(valueOrNull<String>("policy") ?: parent?.valueOrNull<String>("policy"))

/** Base for trufflecheck rules: resolves the policy and honors `<key>.exclude` source-path patterns. */
abstract class TruffleRule(config: Config, protected val key: String) :
  Rule(config, config.policy().message(key)) {
  protected val policy: Policy = config.policy()
  private val exclude = policy.exclude(key)

  override fun visitKtFile(file: KtFile) {
    if (exclude != null && exclude.matcher(file.virtualFilePath).matches()) return
    super.visitKtFile(file)
  }

  protected fun report(element: KtElement, detail: String? = null) {
    val message = policy.message(key)
    report(Finding(Entity.from(element), if (detail == null) message else "$detail $message"))
  }
}

internal fun KaSession.call(element: KtElement): KaCallableMemberCall<*, *>? =
  element.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>()

/** `pkg.Class.member` for a resolved call, or `pkg.function` for a top-level one. */
internal fun KaSession.callableName(element: KtElement): String? =
  call(element)?.partiallyAppliedSymbol?.symbol?.callableId?.asSingleFqName()?.asString()

/** Class ids for dotted names that may name nested classes (`java.util.Map.Entry`). */
internal fun KaSession.classId(fq: String): ClassId? {
  val parts = fq.split('.')
  for (split in parts.size - 1 downTo 1) {
    val pkg = FqName(parts.subList(0, split).joinToString("."))
    val relative = FqName(parts.subList(split, parts.size).joinToString("."))
    val id = ClassId(pkg, relative, false)
    if (findClass(id) != null) return id
  }
  return null
}

internal fun KaSession.isSubtype(type: KaType?, id: ClassId?): Boolean =
  type != null && id != null && type.isSubtypeOf(id)

/** A class or object (including object expressions, whose own type may not report its supertypes) extending Node. */
internal fun KaSession.extendsNode(declaration: KtClassOrObject?): Boolean {
  val symbol = declaration?.classSymbol ?: return false
  return isSubtype(symbol.defaultType, Truffle.NODE) || symbol.superTypes.any { isSubtype(it, Truffle.NODE) }
}

internal fun KaSession.isNode(type: KaType?): Boolean =
  isSubtype(type, Truffle.NODE) || isSubtype(type, Truffle.NODE_INTERFACE)

/** Matches `Owner#method` and `Owner#<init>` policy entries against resolved calls, including overrides. */
internal class Calls(policy: Policy, key: String) {
  private val entries = policy.calls(key)

  fun matches(session: KaSession, element: KtElement): Boolean =
    with(session) {
      val call = call(element) ?: return false
      val symbol = call.partiallyAppliedSymbol.symbol
      if (symbol is KaConstructorSymbol) {
        val created = symbol.containingClassId ?: return false
        val type = findClass(created)?.defaultType ?: return false
        return entries.any { it.constructor() && isSubtype(type, classId(it.owner())) }
      }
      val id = symbol.callableId ?: return false
      val receiver = call.partiallyAppliedSymbol.dispatchReceiver?.type
      entries.any { entry ->
        !entry.constructor() &&
          id.callableName.asString() == entry.method() &&
          (id.classId?.asSingleFqName()?.asString() == entry.owner() ||
            isSubtype(receiver, classId(entry.owner())))
      }
    }
}

private fun KtExpression.stripped(): KtExpression? {
  var current: KtExpression? = this
  while (current is KtParenthesizedExpression) current = current.expression
  return current
}

private fun KaSession.isDirective(expression: KtExpression?, owner: String, vararg names: String): Boolean {
  val target = (expression?.stripped() as? KtDotQualifiedExpression)?.selectorExpression ?: expression?.stripped()
  val name = (target as? KtCallExpression)?.let { callableName(it) } ?: return false
  return names.any { name == "$owner.$it" }
}

private fun KaSession.interpreterOnly(condition: KtExpression?): Boolean {
  val stripped = condition?.stripped() ?: return false
  if (isDirective(stripped, Truffle.DIRECTIVES, "inInterpreter") ||
    isDirective(stripped, Truffle.HOST_DIRECTIVES, "inInterpreterFastPath")) {
    return true
  }
  return stripped is KtPrefixExpression &&
    stripped.operationToken == KtTokens.EXCL &&
    isDirective(stripped.baseExpression, Truffle.DIRECTIVES, "inCompiledCode")
}

/**
 * Whether partial evaluation may reach [element]: a function or accessor of a `Node` (including its companion) or
 * an `@ExportMessage` function, not a `@TruffleBoundary`, not opening with `neverPartOfCompilation()`, not after
 * `transferToInterpreter*()` in an enclosing block, and not under `if (inInterpreter())`. Lambdas count as part of
 * their enclosing function: idiomatic Kotlin lambdas are mostly inline.
 */
internal fun KaSession.inPartialEvaluation(element: KtElement): Boolean {
  var child: com.intellij.psi.PsiElement = element
  var parent = element.parent
  var function: KtElement? = null
  var owner: KtClassOrObject? = null
  while (parent != null) {
    if (function == null) {
      if (parent is KtBlockExpression) {
        for (statement in parent.statements) {
          if (statement == child) break
          if (isDirective(statement, Truffle.DIRECTIVES, "transferToInterpreter", "transferToInterpreterAndInvalidate")) {
            return false
          }
        }
      }
      if (parent is KtIfExpression) {
        val inThen = parent.then?.let { com.intellij.psi.util.PsiTreeUtil.isAncestor(it, element, false) } == true
        val inElse = parent.`else`?.let { com.intellij.psi.util.PsiTreeUtil.isAncestor(it, element, false) } == true
        if ((inThen && interpreterOnly(parent.condition)) ||
          (inElse && isDirective(parent.condition, Truffle.DIRECTIVES, "inCompiledCode"))) {
          return false
        }
      }
      // Arguments of `shouldNotReachHere(…)`, and lambdas handed to `interpreterOnly(…)`, never compile.
      if (parent is KtCallExpression && parent != element &&
        isDirective(parent, Truffle.DIRECTIVES, "shouldNotReachHere", "interpreterOnly")) {
        return false
      }
      if (parent is KtNamedFunction || parent is KtPropertyAccessor) function = parent as KtElement
    }
    if (parent is KtClassOrObject) {
      owner = parent
      break
    }
    child = parent
    parent = parent.parent
  }
  if (function == null || owner == null) return false
  val symbol = (function as? KtNamedFunction)?.symbol ?: (function as KtPropertyAccessor).symbol
  if (symbol.annotations.contains(Truffle.BOUNDARY)) return false
  val body = (function as? KtNamedFunction)?.bodyBlockExpression ?: (function as? KtPropertyAccessor)?.bodyBlockExpression
  if (isDirective(body?.statements?.firstOrNull(), Truffle.ASSERTS, "neverPartOfCompilation")) return false
  val type =
    if (owner is KtObjectDeclaration && owner.isCompanion()) owner.containingClassOrObject else owner
  if (extendsNode(type)) return true
  // DSL specializations outside Node subclasses (e.g. Bytecode DSL operations) still compile.
  return symbol.annotations.contains(Truffle.EXPORT_MESSAGE) ||
    symbol.annotations.contains(Truffle.SPECIALIZATION) ||
    symbol.annotations.contains(Truffle.FALLBACK)
}

/** Whether a statement before [element], in any enclosing block up to the function, satisfies [test]. */
internal fun precededBy(element: KtElement, test: (KtExpression) -> Boolean): Boolean {
  var child: com.intellij.psi.PsiElement = element
  var parent = element.parent
  while (parent != null && parent !is KtNamedFunction && parent !is KtPropertyAccessor && parent !is KtClassOrObject) {
    if (parent is KtBlockExpression) {
      for (statement in parent.statements) {
        if (statement == child) break
        if (test(statement)) return true
      }
    }
    child = parent
    parent = parent.parent
  }
  return false
}

/** A statement, or an `if` whose then-branch holds one, satisfying [test]. */
internal fun containsStatement(statement: KtExpression, test: (KtExpression) -> Boolean): Boolean {
  if (test(statement)) return true
  if (statement is KtIfExpression) {
    val then = statement.then ?: return false
    return if (then is KtBlockExpression) then.statements.any { containsStatement(it, test) } else containsStatement(then, test)
  }
  return false
}

internal fun KaSession.isDirectiveCall(expression: KtExpression?, owner: String, vararg names: String): Boolean {
  val target = (expression as? KtDotQualifiedExpression)?.selectorExpression ?: expression
  val name = (target as? KtCallExpression)?.let { callableName(it) } ?: return false
  return names.any { name == "$owner.$it" }
}

/** The member property a (possibly `this.`-qualified) reference names, or null. */
internal fun KaSession.propertyOf(expression: KtExpression?): org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol? {
  val target =
    when (expression) {
      is KtDotQualifiedExpression -> expression.selectorExpression
      else -> expression
    } as? org.jetbrains.kotlin.psi.KtNameReferenceExpression ?: return null
  val symbol = target.mainReference.resolveToSymbol() as? org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol ?: return null
  return symbol.takeIf { it.location != org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation.LOCAL }
}

/** Annotations on a property and on its backing field (where Java FIELD-target annotations land). */
internal fun KaSession.annotationsOf(symbol: org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol): List<org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation> =
  symbol.annotations.toList() + (symbol.backingFieldSymbol?.annotations?.toList() ?: emptyList())

internal fun KaSession.hasAnnotation(symbol: org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol, id: ClassId): Boolean =
  annotationsOf(symbol).any { it.classId == id }

/** `@CompilationFinal(dimensions = n)`: n, -1 when omitted (full depth), or null without it. */
internal fun KaSession.compilationFinalDimensions(symbol: org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol): Int? {
  val annotation = annotationsOf(symbol).firstOrNull { it.classId == Truffle.COMPILATION_FINAL } ?: return null
  val value = annotation.arguments.firstOrNull { it.name.asString() == "dimensions" }?.expression
  return ((value as? org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue.ConstantValue)?.value?.value as? Number)?.toInt() ?: -1
}

/** Inside a constructor, `init` block or property initializer of [owner]. */
internal fun inInitializerOf(element: KtElement, owner: KtClassOrObject?): Boolean {
  var parent = element.parent
  while (parent != null) {
    when (parent) {
      is KtNamedFunction, is KtPropertyAccessor, is org.jetbrains.kotlin.psi.KtLambdaExpression -> return false
      is org.jetbrains.kotlin.psi.KtSecondaryConstructor, is org.jetbrains.kotlin.psi.KtAnonymousInitializer -> {
        return parent.getStrictParentOfType<KtClassOrObject>() == owner
      }
      is org.jetbrains.kotlin.psi.KtProperty -> if (!parent.isLocal) return parent.getStrictParentOfType<KtClassOrObject>() == owner
      is KtClassOrObject -> return false
    }
    parent = parent.parent
  }
  return false
}
