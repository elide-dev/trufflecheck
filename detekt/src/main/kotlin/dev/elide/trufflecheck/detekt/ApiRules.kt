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
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

private fun id(fq: String) = ClassId.topLevel(FqName(fq))

private val PROFILE_TYPE = id("com.oracle.truffle.api.profiles.Profile")
private val INLINED_PROFILE_TYPE = id("com.oracle.truffle.api.profiles.InlinedProfile")
private val CALL_TARGET = id("com.oracle.truffle.api.CallTarget")
private val GUEST_EXCEPTION = id("com.oracle.truffle.api.exception.AbstractTruffleException")
private val BYTECODE_SWITCH =
  id("com.oracle.truffle.api.HostCompilerDirectives").createNestedClassId(org.jetbrains.kotlin.name.Name.identifier("BytecodeInterpreterSwitch"))

// ---------------------------------------------------------------------------------------------------------------------
// TruffleDeprecatedApi

@OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
class TruffleDeprecatedApi(config: Config) : TruffleRule(config, "deprecated"), RequiresAnalysisApi {
  private val scope = policy.scope("deprecated")

  private fun KaSession.check(element: KtElement, owner: ClassId?, member: String?, deprecated: Boolean) {
    if (!deprecated || owner == null) return
    if (!Policy.inPackages(owner.packageFqName.asString(), scope)) return
    val type = owner.asSingleFqName().asString()
    val hint =
      (member?.let { policy.list("deprecated.hint.$type#$it") }?.takeIf { it.isNotEmpty() }) ?: policy.list("deprecated.hint.$type")
    val name = owner.shortClassName.asString() + (member?.let { ".$it" } ?: "")
    report(element, "'$name' is deprecated" + if (hint.isEmpty()) "." else ": ${hint.joinToString(", ")}.")
  }

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    analyze(expression) {
      val symbol = call(expression)?.partiallyAppliedSymbol?.symbol ?: return
      val id = symbol.callableId ?: return
      check(expression, id.classId, id.callableName.asString().takeIf { it != "<init>" }, symbol.deprecationStatus != null)
    }
  }

  override fun visitTypeReference(typeReference: KtTypeReference) {
    super.visitTypeReference(typeReference)
    if (typeReference.getStrictParentOfType<org.jetbrains.kotlin.psi.KtImportDirective>() != null) return
    analyze(typeReference) {
      val symbol = (typeReference.type as? KaClassType)?.symbol ?: return
      check(typeReference, symbol.classId, null, symbol.deprecationStatus != null)
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleDslSuppressions

class TruffleDslSuppressions(config: Config) : TruffleRule(config, "suppressions") {
  override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
    super.visitAnnotationEntry(annotationEntry)
    val name = annotationEntry.shortName?.asString() ?: return
    if (name != "Suppress" && name != "SuppressWarnings") return
    val file = annotationEntry.containingKtFile
    val truffleImport =
      file.importDirectives.firstOrNull { it.importedFqName?.asString()?.startsWith("com.oracle.truffle.") == true } ?: return
    val values = annotationEntry.valueArguments.mapNotNull { it.getArgumentExpression()?.text?.trim('"') }
    val blanket = values.firstOrNull { it == "truffle" || it == "all" } ?: return
    if (blanket == "all") {
      // detekt honors "all" on the annotated element itself, which would hide this finding; report at the import.
      val line = file.viewProvider.document?.getLineNumber(annotationEntry.textOffset)?.plus(1)
      report(truffleImport, "@$name(\"all\") on line $line hides every Truffle DSL warning.")
    } else {
      report(annotationEntry, "@$name(\"$blanket\") hides every Truffle DSL warning.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleLegacyProfile

class TruffleLegacyProfile(config: Config) : TruffleRule(config, "legacyprofile"), RequiresAnalysisApi {
  override fun visitProperty(property: KtProperty) {
    super.visitProperty(property)
    if (property.isLocal || property.isTopLevel) return
    val owner = property.getStrictParentOfType<KtClassOrObject>() ?: return
    analyze(property) {
      val type = property.symbol.returnType
      if (!isSubtype(type, PROFILE_TYPE) || isSubtype(type, INLINED_PROFILE_TYPE)) return
      val dsl = owner.declarations.filterIsInstance<KtNamedFunction>().any { function ->
        function.symbol.annotations.contains(Truffle.SPECIALIZATION) || function.symbol.annotations.contains(Truffle.FALLBACK)
      }
      if (!dsl) return
      val simple = (type as? KaClassType)?.classId?.shortClassName?.asString()
      report(property, "'${property.name}' is a legacy $simple; use @Cached Inlined$simple with @Bind Node.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleUncachedOnFastPath

class TruffleUncachedOnFastPath(config: Config) : TruffleRule(config, "uncached"), RequiresAnalysisApi {
  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    analyze(expression) {
      val resolved = call(expression) ?: return
      val id = resolved.partiallyAppliedSymbol.symbol.callableId ?: return
      val name = id.callableName.asString()
      val detail =
        when {
          name == "getUncached" &&
            (id.packageName.asString().startsWith("com.oracle.truffle.") || isNode(resolved.partiallyAppliedSymbol.symbol.returnType)) ->
            "'${id.classId?.shortClassName}.getUncached()' doesn't specialize."
          name == "call" && isSubtype(resolved.partiallyAppliedSymbol.dispatchReceiver?.type, CALL_TARGET) ->
            "A direct CallTarget.call gets no inlining or splitting."
          else -> return
        }
      if (inPartialEvaluation(expression)) report(expression, detail)
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleBoundaryExceptionPolicy

class TruffleBoundaryExceptionPolicy(config: Config) : TruffleRule(config, "boundaryexception"), RequiresAnalysisApi {
  override fun visitNamedFunction(function: KtNamedFunction) {
    super.visitNamedFunction(function)
    analyze(function) {
      val annotation = function.symbol.annotations[Truffle.BOUNDARY].firstOrNull() ?: return
      if (annotation.arguments.any { it.name.asString() == "transferToInterpreterOnException" }) return
      var throwsGuest = false
      function.accept(
        object : KtTreeVisitorVoid() {
          override fun visitThrowExpression(expression: KtThrowExpression) {
            val thrown = expression.thrownExpression as? KtCallExpression
            if (thrown != null && isSubtype(thrown.expressionType, GUEST_EXCEPTION)) throwsGuest = true
            super.visitThrowExpression(expression)
          }
        },
      )
      if (throwsGuest) report(function, "This boundary throws guest exceptions.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleSlowPathInline

class TruffleSlowPathInline(config: Config) : TruffleRule(config, "slowpath"), RequiresAnalysisApi {
  override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
    super.visitStringTemplateExpression(expression)
    if (!expression.hasInterpolation()) return
    analyze(expression) {
      val function = expression.getStrictParentOfType<KtNamedFunction>() ?: return
      if (function.symbol.annotations.contains(Truffle.BOUNDARY)) return
      if (!extendsNode(function.getStrictParentOfType())) return
      val afterTransfer =
        precededBy(expression) { isDirectiveCall(it, Truffle.DIRECTIVES, "transferToInterpreter", "transferToInterpreterAndInvalidate") }
      if (afterTransfer) report(expression, "This slow-path message is inlined into '${function.name}'.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleBytecodeSwitch

class TruffleBytecodeSwitch(config: Config) : TruffleRule(config, "bytecodeswitch"), RequiresAnalysisApi {
  override fun visitNamedFunction(function: KtNamedFunction) {
    super.visitNamedFunction(function)
    analyze(function) {
      if (function.symbol.annotations.contains(BYTECODE_SWITCH)) return
      val opcodes = mutableSetOf<String>()
      var dispatch = false
      fun bytecode(subject: KtExpression?): Boolean {
        val target = subject ?: return false
        if (target is KtArrayAccessExpression) {
          val property = propertyOf(target.arrayExpression) ?: return false
          return compilationFinalDimensions(property) != null
        }
        return (target as? KtNameReferenceExpression)?.getReferencedName() in opcodes
      }
      function.accept(
        object : KtTreeVisitorVoid() {
          var loops = 0

          override fun visitProperty(property: KtProperty) {
            if (property.isLocal && bytecode(property.initializer)) property.name?.let { opcodes += it }
            super.visitProperty(property)
          }

          override fun visitWhileExpression(expression: KtWhileExpression) {
            loops++
            super.visitWhileExpression(expression)
            loops--
          }

          override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
            loops++
            super.visitDoWhileExpression(expression)
            loops--
          }

          override fun visitForExpression(expression: KtForExpression) {
            loops++
            super.visitForExpression(expression)
            loops--
          }

          override fun visitWhenExpression(expression: KtWhenExpression) {
            if (loops > 0 && bytecode(expression.subjectExpression)) dispatch = true
            super.visitWhenExpression(expression)
          }
        },
      )
      if (dispatch) report(function, "'${function.name}' dispatches bytecodes.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleFieldUpdaterConstant

class TruffleFieldUpdaterConstant(config: Config) : TruffleRule(config, "updater"), RequiresAnalysisApi {
  private val updaters =
    setOf(
      "java.util.concurrent.atomic.AtomicIntegerFieldUpdater",
      "java.util.concurrent.atomic.AtomicLongFieldUpdater",
      "java.util.concurrent.atomic.AtomicReferenceFieldUpdater",
    )
  private val unsafe = setOf("sun.misc.Unsafe", "jdk.internal.misc.Unsafe")
  private val offsets = setOf("objectFieldOffset", "staticFieldOffset", "arrayBaseOffset", "arrayIndexScale")

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    analyze(expression) {
      val id = call(expression)?.partiallyAppliedSymbol?.symbol?.callableId ?: return
      val owner = id.classId?.asSingleFqName()?.asString() ?: return
      val name = id.callableName.asString()
      if (!((owner in updaters && name == "newUpdater") || (owner in unsafe && name in offsets))) return
      // The direct initializer of an object/companion/top-level `val` compiles to a static final field.
      val qualified = expression.parent as? org.jetbrains.kotlin.psi.KtDotQualifiedExpression
      val value = if (qualified?.selectorExpression == expression) qualified else expression
      val property = value.parent as? KtProperty
      if (property != null && property.initializer == value && !property.isVar && !property.isLocal &&
        (property.isTopLevel || property.getStrictParentOfType<KtClassOrObject>() is org.jetbrains.kotlin.psi.KtObjectDeclaration)) {
        return
      }
      report(expression, "'$name' isn't a static final field's initializer.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleDeclaredType

class TruffleDeclaredType(config: Config) : TruffleRule(config, "declaredtypes"), RequiresAnalysisApi {
  private val banned = policy.list("declaredtypes")

  /** Declared types of properties, parameters and return values; not supertypes. */
  override fun visitTypeReference(typeReference: KtTypeReference) {
    super.visitTypeReference(typeReference)
    val parent = typeReference.parent
    val declared =
      (parent is KtProperty && parent.typeReference == typeReference) ||
        (parent is KtParameter && parent.typeReference == typeReference) ||
        (parent is KtNamedFunction && parent.typeReference == typeReference)
    if (!declared) return
    analyze(typeReference) {
      val name = (typeReference.type as? KaClassType)?.classId?.asSingleFqName()?.asString() ?: return
      if (name in banned) report(typeReference, "Declared as '${name.substringAfterLast('.')}'; declare it as Any.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleKotlinNodeHazards

class TruffleKotlinNodeHazards(config: Config) : TruffleRule(config, "kotlinnode"), RequiresAnalysisApi {
  override fun visitProperty(property: KtProperty) {
    super.visitProperty(property)
    if (property.isLocal || property.isTopLevel) return
    val owner = property.getStrictParentOfType<KtClassOrObject>() ?: return
    analyze(property) {
      if (!extendsNode(owner)) return
      if (property.delegate != null) {
        report(property, "Delegated properties (by lazy, …) go through a Lazy/delegate object, a lock and a lambda on every read.")
        return
      }
      val symbol = property.symbol as? KaPropertySymbol ?: return
      if (!property.isVar && hasAnnotation(symbol, Truffle.CHILD)) {
        report(property, "@Child on a val compiles to a final field, which Truffle rejects; make it a var.")
      }
    }
  }

  /** Constructor properties: `class N(@Child val c: X) : Node()`. */
  override fun visitParameter(parameter: KtParameter) {
    super.visitParameter(parameter)
    if (!parameter.hasValOrVar() || parameter.isMutable) return
    val owner = parameter.getStrictParentOfType<KtClassOrObject>() ?: return
    analyze(parameter) {
      if (!extendsNode(owner)) return
      val annotated = parameter.annotationEntries.any { it.shortName?.asString() == "Child" }
      if (annotated) report(parameter, "@Child on a val compiles to a final field, which Truffle rejects; make it a var.")
    }
  }

  override fun visitArgument(argument: KtValueArgument) {
    super.visitArgument(argument)
    if (argument.getSpreadElement() == null) return
    analyze(argument) {
      if (inPartialEvaluation(argument)) report(argument, "The spread operator copies the array on every call.")
    }
  }
}
