/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.detekt

import dev.detekt.api.Config
import dev.detekt.api.RequiresAnalysisApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

private fun top(fq: String) = ClassId.topLevel(FqName(fq))

private val VIRTUAL_FRAME = top("com.oracle.truffle.api.frame.VirtualFrame")
private val MATERIALIZED_FRAME = top("com.oracle.truffle.api.frame.MaterializedFrame")
private val UNADOPTABLE = top("com.oracle.truffle.api.nodes.UnadoptableNode")
private val PROFILE = top("com.oracle.truffle.api.profiles.Profile")
private val INLINED_PROFILE = top("com.oracle.truffle.api.profiles.InlinedProfile")
private val REPEATING = top("com.oracle.truffle.api.nodes.RepeatingNode")
private val EXPORT_LIBRARY = top("com.oracle.truffle.api.library.ExportLibrary")
private val EXPLODE = top("com.oracle.truffle.api.nodes.ExplodeLoop")

private fun KaSession.isVirtualFrame(type: KaType?): Boolean =
  type != null && type.isSubtypeOf(VIRTUAL_FRAME) && !type.isSubtypeOf(MATERIALIZED_FRAME)

/** Properties evaluated once per class: `object`/companion members and top-level properties. */
private fun isStatic(property: KtProperty): Boolean {
  if (property.isLocal) return false
  if (property.isTopLevel) return true
  return property.getStrictParentOfType<KtClassOrObject>() is KtObjectDeclaration &&
    property.getStrictParentOfType<KtObjectLiteralExpression>() == null
}

private fun KaSession.isInvalidate(statement: KtExpression?): Boolean =
  isDirectiveCall(statement, Truffle.DIRECTIVES, "transferToInterpreterAndInvalidate")

// ---------------------------------------------------------------------------------------------------------------------
// TruffleDeoptLoop

class TruffleDeoptLoop(config: Config) : TruffleRule(config, "deopt"), RequiresAnalysisApi {
  private fun inert(statement: KtExpression): Boolean {
    if (statement is KtThrowExpression) return true
    if (statement !is KtReturnExpression) return false
    val value = statement.returnedExpression ?: return true
    var calls = false
    value.accept(
      object : KtTreeVisitorVoid() {
        override fun visitCallExpression(expression: KtCallExpression) {
          calls = true
        }
      },
    )
    return !calls
  }

  override fun visitBlockExpression(expression: KtBlockExpression) {
    super.visitBlockExpression(expression)
    analyze(expression) {
      val statements = expression.statements
      for ((index, statement) in statements.withIndex()) {
        if (!isInvalidate(statement)) continue
        val rest = statements.drop(index + 1)
        if (rest.isNotEmpty() && rest.all(::inert)) {
          report(statement, "Nothing changes between invalidating and leaving.")
          return
        }
      }
    }
  }

  override fun visitIfExpression(expression: KtIfExpression) {
    super.visitIfExpression(expression)
    val condition = expression.condition as? KtBinaryExpression ?: return
    if (condition.operationToken !in setOf(KtTokens.EXCLEQ, KtTokens.EQEQ, KtTokens.EXCLEQEQEQ, KtTokens.EQEQEQ)) return
    val then = expression.then as? KtBlockExpression ?: return
    if (expression.`else` != null) return
    analyze(expression) {
      val property = propertyOf(condition.left) ?: propertyOf(condition.right) ?: return
      if (compilationFinalDimensions(property) == null) return
      var invalidates = false
      var writes = 0
      var writesCached = false
      for (statement in then.statements) {
        if (isInvalidate(statement)) invalidates = true
        if (statement is KtBinaryExpression && statement.operationToken == KtTokens.EQ) {
          writes++
          if (propertyOf(statement.left) == property) writesCached = true
        }
      }
      if (invalidates && writesCached && writes == 1) {
        report(
          expression,
          "'${property.name}' caches one value and re-specializes on every new one; add a generic state" +
            " (or a bounded cache) so it stops invalidating.",
        )
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleStaticContextState

class TruffleStaticContextState(config: Config) : TruffleRule(config, "contextstate"), RequiresAnalysisApi {
  private val types = policy.owners("contextstate")
  private val allow = policy.list("contextstate.allow")

  override fun visitProperty(property: KtProperty) {
    super.visitProperty(property)
    if (!isStatic(property)) return
    analyze(property) {
      val type = property.symbol.returnType
      if (allow.any { isSubtype(type, classId(it)) }) return
      if (isSubtype(type, Truffle.NODE) && isSubtype(type, UNADOPTABLE)) return
      val banned = types.firstOrNull { isSubtype(type, classId(it)) } ?: return
      report(property, "'${property.name}' holds a ${banned.substringAfterLast('.')}.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleFrameEscape

class TruffleFrameEscape(config: Config) : TruffleRule(config, "frame"), RequiresAnalysisApi {
  override fun visitProperty(property: KtProperty) {
    super.visitProperty(property)
    if (property.isLocal) return
    analyze(property) {
      if (isVirtualFrame(property.symbol.returnType)) {
        report(property, "A VirtualFrame property escapes the frame.")
      }
    }
  }

  override fun visitBinaryExpression(expression: KtBinaryExpression) {
    super.visitBinaryExpression(expression)
    if (expression.operationToken != KtTokens.EQ) return
    analyze(expression) {
      if (propertyOf(expression.left) == null) return
      if (isVirtualFrame(expression.right?.expressionType)) report(expression, "Storing the frame in a property escapes it.")
    }
  }

  override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
    super.visitSimpleNameExpression(expression)
    if (expression !is KtNameReferenceExpression) return
    analyze(expression) {
      val target = expression.mainReference.resolveToSymbol() as? KaVariableSymbol ?: return
      if (target !is KaValueParameterSymbol && target !is KaLocalVariableSymbol) return
      if (!isVirtualFrame(target.returnType)) return
      val declaration = target.psi ?: return
      var parent = expression.parent
      while (parent != null && parent != declaration.parent) {
        when (parent) {
          is KtObjectLiteralExpression, is KtClass -> {
            if (declaration.getStrictParentOfType<KtClassOrObject>()?.let { com.intellij.psi.util.PsiTreeUtil.isAncestor(parent, it, false) } != true) {
              report(expression, "Capturing the frame in an object or local class escapes it.")
              return
            }
          }
          is KtLambdaExpression -> if (escapes(parent)) {
            report(expression, "Capturing the frame in a non-inline lambda escapes it.")
            return
          }
          // The function declaring the variable: no capture on the way.
          is KtNamedFunction -> if (declaration.getStrictParentOfType<KtNamedFunction>() == parent) return
        }
        parent = parent.parent
      }
    }
  }

  /** A lambda passed to anything but an inline function (e.g. a SAM constructor or Java method) escapes. */
  private fun KaSession.escapes(lambda: KtLambdaExpression): Boolean {
    val call = lambda.getStrictParentOfType<KtCallExpression>() ?: return true
    val symbol = call(call)?.partiallyAppliedSymbol?.symbol
    return !(symbol is KaNamedFunctionSymbol && symbol.isInline)
  }

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    if (expression.calleeExpression?.text != "getFrame") return
    val argument = expression.valueArguments.singleOrNull()?.getArgumentExpression()?.text ?: return
    if (!argument.endsWith("READ_WRITE") && !argument.endsWith("MATERIALIZE")) return
    analyze(expression) {
      if (hasBoundaryAbove(expression)) return
      val owner = expression.getStrictParentOfType<KtClass>() ?: expression.getStrictParentOfType<KtClassOrObject>()
      if (extendsNode(owner)) {
        report(expression, "Requesting a writable or materialized caller frame materializes it (and pins virtual threads).")
      }
    }
  }

  private fun KaSession.hasBoundaryAbove(element: KtElement): Boolean {
    var function = element.getStrictParentOfType<KtNamedFunction>()
    while (function != null) {
      if (function.symbol.annotations.contains(Truffle.BOUNDARY)) return true
      function = function.getStrictParentOfType()
    }
    return false
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleNonConstantNodeState

class TruffleNonConstantNodeState(config: Config) : TruffleRule(config, "nodestate"), RequiresAnalysisApi {
  private val types = policy.owners("nodestate")

  override fun visitProperty(property: KtProperty) {
    super.visitProperty(property)
    if (property.isLocal || property.isTopLevel) return
    analyze(property) {
      val symbol = property.symbol as? KaPropertySymbol ?: return
      val type = symbol.returnType
      val owner = property.getStrictParentOfType<KtClassOrObject>()
      val companionOf = (owner as? KtObjectDeclaration)?.takeIf { it.isCompanion() }?.getStrictParentOfType<KtClassOrObject>()
      val holder = companionOf ?: owner
      val inNode =
        extendsNode(holder) || holder?.annotationEntries?.any { it.shortName?.asString() == "ExportLibrary" } == true
      if (!inNode) return
      if (companionOf != null) {
        if (!isSubtype(type, PROFILE) || isSubtype(type, INLINED_PROFILE)) return
        if (property.initializer?.text?.contains("getUncached") == true) return
        report(property, "A static profile is shared by every node and profiles nothing; make it an instance property.")
        return
      }
      if (!property.isVar || hasAnnotation(symbol, Truffle.COMPILATION_FINAL)) return
      val banned = types.firstOrNull { isSubtype(type, classId(it)) } ?: return
      report(property, "'${property.name}' is a ${banned.substringAfterLast('.')}.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleGenericCatch

class TruffleGenericCatch(config: Config) : TruffleRule(config, "catch"), RequiresAnalysisApi {
  private val generic =
    setOf("java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException", "java.lang.Error", "java.lang.StackOverflowError")
  private val passThrough =
    listOf(top("com.oracle.truffle.api.nodes.ControlFlowException"), top("com.oracle.truffle.api.exception.AbstractTruffleException"))

  override fun visitTryExpression(expression: KtTryExpression) {
    super.visitTryExpression(expression)
    analyze(expression) {
      var handled = false
      for (clause in expression.catchClauses) {
        val type = clause.catchParameter?.symbol?.returnType ?: continue
        if (passThrough.any { isSubtype(type, it) }) handled = true
        val name = type.expandedSymbol?.classId?.asSingleFqName()?.asString()
        if (name in generic && !handled && !rethrows(clause) && inPartialEvaluation(clause)) {
          report(clause, "This catch-all swallows ControlFlowException and guest exceptions.")
        }
      }
    }
  }

  private fun rethrows(clause: KtCatchClause): Boolean {
    val name = clause.catchParameter?.name ?: return false
    val statements = (clause.catchBody as? KtBlockExpression)?.statements ?: return false
    return statements.any { statement ->
      (statement is KtThrowExpression && statement.thrownExpression?.text == name) ||
        (statement is KtIfExpression && statement.condition?.text?.contains(" is ") == true &&
          statement.then?.text?.contains("throw $name") == true)
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleGuestLoop

class TruffleGuestLoop(config: Config) : TruffleRule(config, "guestloop"), RequiresAnalysisApi {
  override fun visitWhileExpression(expression: KtWhileExpression) {
    super.visitWhileExpression(expression)
    check(expression)
  }

  override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
    super.visitDoWhileExpression(expression)
    check(expression)
  }

  private fun check(loop: KtLoopExpression) {
    analyze(loop) {
      val function = loop.getStrictParentOfType<KtNamedFunction>() ?: return
      if (function.symbol.annotations.contains(EXPLODE)) return
      val owner = loop.getStrictParentOfType<KtClassOrObject>()
      if (isSubtype(owner?.classSymbol?.defaultType, REPEATING)) return
      var executes = false
      var polls = false
      loop.accept(
        object : KtTreeVisitorVoid() {
          override fun visitCallExpression(expression: KtCallExpression) {
            val id = call(expression)?.partiallyAppliedSymbol?.symbol?.callableId
            val name = id?.callableName?.asString().orEmpty()
            val ownerName = id?.classId?.asSingleFqName()?.asString().orEmpty()
            if (name.startsWith("execute") && id?.classId?.let { findClass(it)?.defaultType }?.let { isNode(it) } == true) {
              executes = true
            }
            if ((ownerName == "com.oracle.truffle.api.TruffleSafepoint" && name == "poll") ||
              (ownerName == "com.oracle.truffle.api.nodes.LoopNode" && name == "reportLoopCount") ||
              (ownerName == "com.oracle.truffle.api.nodes.BytecodeOSRNode" && name.startsWith("pollOSRBackEdge"))) {
              polls = true
            }
            super.visitCallExpression(expression)
          }
        },
      )
      if (executes && !polls && inPartialEvaluation(loop)) report(loop, "This loop executes guest code.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleFastPathRecursion

class TruffleFastPathRecursion(config: Config) : TruffleRule(config, "recursion"), RequiresAnalysisApi {
  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    val function = expression.getStrictParentOfType<KtNamedFunction>() ?: return
    val qualified = expression.parent as? KtDotQualifiedExpression
    if (qualified != null && qualified.selectorExpression == expression && qualified.receiverExpression !is KtThisExpression) return
    analyze(expression) {
      val target = call(expression)?.partiallyAppliedSymbol?.symbol ?: return
      if (target == function.symbol && inPartialEvaluation(expression)) report(expression)
    }
  }
}
