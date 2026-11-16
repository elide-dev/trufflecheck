/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.detekt

import dev.detekt.api.Config
import dev.detekt.api.RequiresAnalysisApi
import dev.elide.trufflecheck.Policy
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

private val EXPLODE_LOOP = ClassId.topLevel(FqName("com.oracle.truffle.api.nodes.ExplodeLoop"))
private val ASSUMPTION = ClassId.topLevel(FqName("com.oracle.truffle.api.Assumption"))

private fun KtExpression.unwrapped(): KtExpression {
  var current: KtExpression = this
  while (current is KtParenthesizedExpression) current = current.expression ?: return current
  return current
}

/** For `a[i][j]`: the array expression at the root and the element depth. */
private fun arrayRoot(expression: KtExpression): Pair<KtExpression, Int> {
  var current = expression.unwrapped()
  var depth = 0
  while (current is KtArrayAccessExpression) {
    depth++
    current = current.arrayExpression?.unwrapped() ?: break
  }
  return current to depth
}

private fun KaSession.functionSymbol(element: KtElement) =
  element.getStrictParentOfType<KtNamedFunction>()?.symbol

private fun KaSession.hasBoundary(element: KtElement): Boolean =
  functionSymbol(element)?.annotations?.contains(Truffle.BOUNDARY) == true

// ---------------------------------------------------------------------------------------------------------------------
// TruffleCompilationFinalWrite

class TruffleCompilationFinalWrite(config: Config) : TruffleRule(config, "cfwrite"), RequiresAnalysisApi {
  override fun visitBinaryExpression(expression: KtBinaryExpression) {
    super.visitBinaryExpression(expression)
    if (expression.operationToken !in KtTokens.ALL_ASSIGNMENTS) return
    expression.left?.let { check(expression, it) }
  }

  override fun visitUnaryExpression(expression: KtUnaryExpression) {
    super.visitUnaryExpression(expression)
    if (expression.operationToken != KtTokens.PLUSPLUS && expression.operationToken != KtTokens.MINUSMINUS) return
    expression.baseExpression?.let { check(expression, it) }
  }

  private fun check(write: KtExpression, target: KtExpression) {
    val (root, depth) = arrayRoot(target)
    analyze(write) {
      val property = propertyOf(root) ?: return
      val dimensions = compilationFinalDimensions(property) ?: return
      if (depth > 0 && dimensions >= 0 && depth > dimensions) return
      val owner = (property.psi as? KtElement)?.getStrictParentOfType<KtClassOrObject>()
      if (inInitializerOf(write, owner) || hasBoundary(write)) return
      val body = write.getStrictParentOfType<KtNamedFunction>()?.bodyBlockExpression
      if (isDirectiveCall(body?.statements?.firstOrNull(), Truffle.ASSERTS, "neverPartOfCompilation")) return
      if (precededBy(write) { statement -> containsStatement(statement) { invalidates(it) } }) return
      val plain = precededBy(write) { isDirectiveCall(it, Truffle.DIRECTIVES, "transferToInterpreter") }
      report(
        write,
        if (plain) "transferToInterpreter() deoptimizes but doesn't invalidate; use transferToInterpreterAndInvalidate()."
        else "'${property.name}' is @CompilationFinal.",
      )
    }
  }

  private fun KaSession.invalidates(statement: KtExpression): Boolean {
    if (isDirectiveCall(statement, Truffle.DIRECTIVES, "transferToInterpreterAndInvalidate")) return true
    val selector = (statement as? KtDotQualifiedExpression)?.selectorExpression as? KtCallExpression ?: return false
    val resolved = call(selector) ?: return false
    return resolved.partiallyAppliedSymbol.symbol.callableId?.callableName?.asString() == "invalidate" &&
      isSubtype(resolved.partiallyAppliedSymbol.dispatchReceiver?.type, ASSUMPTION)
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleMissingBoundary

class TruffleMissingBoundary(config: Config) : TruffleRule(config, "boundary"), RequiresAnalysisApi {
  private val types = mutableSetOf<String>()
  private val packages = mutableSetOf<String>()
  private val calls = policy.calls("boundary.calls").map { "${it.owner()}#${it.method()}" }.toSet()
  private val allow = policy.calls("boundary.allow").map { "${it.owner()}#${it.method()}" }.toSet()

  init {
    for (entry in policy.list("boundary.types")) {
      if (entry.endsWith(".*")) packages += entry.removeSuffix(".*") else types += entry
    }
  }

  private fun KaSession.hostile(element: KtElement): String? {
    val symbol = call(element)?.partiallyAppliedSymbol?.symbol ?: return null
    val id = symbol.callableId ?: return null
    val owner = id.classId?.asSingleFqName()?.asString() ?: id.packageName.asString()
    val name = if (symbol is KaConstructorSymbol) "<init>" else id.callableName.asString()
    val key = "$owner#$name"
    if (key in allow) return null
    val pkg = id.packageName.asString()
    return if (key in calls || owner in types || (id.classId != null && Policy.inPackages(pkg, packages))) {
      "${owner.substringAfterLast('.')}.$name"
    } else null
  }

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    analyze(expression) {
      val name = hostile(expression) ?: return
      if (inPartialEvaluation(expression)) report(expression, "'$name' must not be inlined into compiled code.")
    }
  }

  /** `map[key]`: an operator call. */
  override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
    super.visitArrayAccessExpression(expression)
    analyze(expression) {
      val name = hostile(expression) ?: return
      if (inPartialEvaluation(expression)) report(expression, "'$name' must not be inlined into compiled code.")
    }
  }

  /** String templates with interpolation, and non-constant `+` on strings, build strings through StringBuilder. */
  override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
    super.visitStringTemplateExpression(expression)
    if (!expression.hasInterpolation()) return
    analyze(expression) {
      if (inPartialEvaluation(expression)) report(expression, "String interpolation must not be inlined into compiled code.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleSkippedException

class TruffleSkippedException(config: Config) : TruffleRule(config, "skipped"), RequiresAnalysisApi {
  private val skipped = policy.owners("skipped")
  private val throwers = policy.list("stackful.kotlin")
  private val unexpected = ClassId.topLevel(FqName("com.oracle.truffle.api.nodes.UnexpectedResultException"))

  override fun visitThrowExpression(expression: KtThrowExpression) {
    super.visitThrowExpression(expression)
    val thrown = expression.thrownExpression?.unwrapped() as? KtCallExpression ?: return
    analyze(expression) {
      val symbol = call(thrown)?.partiallyAppliedSymbol?.symbol as? KaConstructorSymbol ?: return
      val type = symbol.returnType
      if (skipped.none { isSubtype(type, classId(it)) }) return
      if (isSubtype(type, unexpected) &&
        expression.getStrictParentOfType<KtNamedFunction>()?.name?.startsWith("execute") == true) {
        return
      }
      if (inPartialEvaluation(expression)) {
        report(expression, "'${(type as? KaClassType)?.classId?.shortClassName}' is never handled in compiled code.")
      }
    }
  }

  /** `require`/`check`/`error` throw IllegalArgumentException/IllegalStateException. */
  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    analyze(expression) {
      if (callableName(expression) in throwers && inPartialEvaluation(expression)) {
        report(expression, "'${expression.calleeExpression?.text}' throws an exception compiled code never handles.")
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleChildWriteWithoutInsert

class TruffleChildWriteWithoutInsert(config: Config) : TruffleRule(config, "insert"), RequiresAnalysisApi {
  override fun visitBinaryExpression(expression: KtBinaryExpression) {
    super.visitBinaryExpression(expression)
    if (expression.operationToken != KtTokens.EQ) return
    val (root, depth) = arrayRoot(expression.left ?: return)
    if (depth > 1) return
    analyze(expression) {
      val property = propertyOf(root) ?: return
      if (!hasAnnotation(property, if (depth == 0) Truffle.CHILD else Truffle.CHILDREN)) return
      val owner = (property.psi as? KtElement)?.getStrictParentOfType<KtClassOrObject>()
      if (inInitializerOf(expression, owner) || adopted(expression.right)) return
      report(expression)
    }
  }

  private fun KaSession.adopted(value: KtExpression?): Boolean {
    val unwrapped = value?.unwrapped() ?: return true
    if (unwrapped is KtConstantExpression && unwrapped.text == "null") return true
    if (unwrapped is KtIfExpression) return adopted(unwrapped.then) && adopted(unwrapped.`else`)
    val selector = (unwrapped as? KtDotQualifiedExpression)?.selectorExpression ?: unwrapped
    val resolved = (selector as? KtCallExpression)?.let { call(it) } ?: return false
    val id = resolved.partiallyAppliedSymbol.symbol.callableId ?: return false
    return id.callableName.asString() == "insert" && id.classId?.let { findClass(it)?.defaultType }?.let { isSubtype(it, Truffle.NODE) } == true
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleChildrenLoop

class TruffleChildrenLoop(config: Config) : TruffleRule(config, "loop"), RequiresAnalysisApi {
  private fun KaSession.exploded(element: KtElement): Boolean =
    functionSymbol(element)?.annotations?.contains(EXPLODE_LOOP) == true

  /** The array a loop range iterates: `xs`, `xs.indices`, `0 until xs.size`, `xs.forEach`. */
  private fun rangeArray(range: KtExpression?): KtExpression? {
    val unwrapped = range?.unwrapped() ?: return null
    if (unwrapped is KtDotQualifiedExpression) {
      val selector = unwrapped.selectorExpression?.text
      if (selector == "indices" || selector == "size" || selector == "lastIndex") return unwrapped.receiverExpression
    }
    if (unwrapped is KtBinaryExpression) return rangeArray(unwrapped.right)
    return unwrapped
  }

  override fun visitForExpression(expression: KtForExpression) {
    super.visitForExpression(expression)
    check(expression, rangeArray(expression.loopRange))
  }

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    val qualified = expression.parent as? KtDotQualifiedExpression ?: return
    if (qualified.selectorExpression != expression) return
    if (expression.calleeExpression?.text !in setOf("forEach", "forEachIndexed", "map")) return
    check(expression, qualified.receiverExpression)
  }

  private fun check(loop: KtExpression, array: KtExpression?) {
    array ?: return
    analyze(loop) {
      if (exploded(loop)) {
        val reference = array.unwrapped() as? KtNameReferenceExpression ?: return
        val symbol = reference.mainReference.resolveToSymbol() as? KaVariableSymbol ?: return
        if (symbol is KaPropertySymbol && symbol.location != KaSymbolLocation.LOCAL) return
        if (!asserted(loop, reference.getReferencedName())) {
          report(loop, "'${reference.getReferencedName()}' bounds an exploded loop but isn't asserted constant.")
        }
        return
      }
      val property = propertyOf(array.unwrapped()) ?: return
      if (hasAnnotation(property, Truffle.CHILDREN) && inPartialEvaluation(loop)) {
        report(loop, "Loops over @Children must be @ExplodeLoop.")
      }
    }
  }

  /** `CompilerAsserts.partialEvaluationConstant(name…)` somewhere in the enclosing function. */
  private fun KaSession.asserted(element: KtElement, name: String): Boolean {
    val function = element.getStrictParentOfType<KtNamedFunction>() ?: return false
    var found = false
    function.accept(
      object : KtTreeVisitorVoid() {
        override fun visitCallExpression(expression: KtCallExpression) {
          if (callableName(expression) == "${Truffle.ASSERTS}.partialEvaluationConstant" &&
            expression.valueArguments.any { it.getArgumentExpression()?.text?.substringBefore('.') == name }) {
            found = true
          }
          super.visitCallExpression(expression)
        }
      },
    )
    return found
  }

  override fun visitNamedFunction(function: KtNamedFunction) {
    super.visitNamedFunction(function)
    val entry = function.annotationEntries.firstOrNull { it.shortName?.asString() == "ExplodeLoop" } ?: return
    if (entry.valueArguments.none { it.getArgumentExpression()?.text?.endsWith("MERGE_EXPLODE") == true }) return
    analyze(function) {
      var keyed = false
      function.accept(
        object : KtTreeVisitorVoid() {
          override fun visitCallExpression(expression: KtCallExpression) {
            if (callableName(expression) == "${Truffle.DIRECTIVES}.mergeExplodeKey") keyed = true
            super.visitCallExpression(expression)
          }
        },
      )
      if (!keyed) {
        report(function, "MERGE_EXPLODE loops must mark their merge key with CompilerDirectives.mergeExplodeKey(…).")
      }
    }
  }
}
