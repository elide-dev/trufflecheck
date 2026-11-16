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
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeArgumentWithVariance
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.ValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.idea.references.mainReference

// ---------------------------------------------------------------------------------------------------------------------
// TruffleStackfulException

class TruffleStackfulException(config: Config) : TruffleRule(config, "stackful"), RequiresAnalysisApi {
  private val library = policy.list("stackful.library")
  private val exempt = policy.list("stackful.exempt")
  private val throwers = policy.list("stackful.kotlin")

  private fun KaSession.isLibrary(id: ClassId): Boolean =
    Policy.inPackages(id.packageFqName.asString(), library)

  private fun KaSession.declaresFillIn(symbol: KaClassSymbol): Boolean =
    symbol.declaredMemberScope.callables(Name.identifier("fillInStackTrace")).any {
      it is KaNamedFunctionSymbol && it.valueParameters.isEmpty()
    }

  /** Throwable, with no stackless opt-out (exempt base or fillInStackTrace override) in the class chain. */
  private fun KaSession.stackful(symbol: KaClassSymbol?, depth: Int = 0): Boolean {
    if (symbol == null || depth > 32) return false
    val id = symbol.classId ?: return false
    if (id == Truffle.THROWABLE || id == Truffle.KOTLIN_THROWABLE) return true
    if (exempt.contains(id.asSingleFqName().asString()) || declaresFillIn(symbol)) return false
    val parent =
      symbol.superTypes.mapNotNull { (it as? KaClassType)?.symbol as? KaClassSymbol }.firstOrNull {
        it.classKind == org.jetbrains.kotlin.analysis.api.symbols.KaClassKind.CLASS
      }
    return stackful(parent, depth + 1)
  }

  private fun stacklessArguments(arguments: List<ValueArgument>, session: KaSession): Boolean =
    with(session) { arguments.size == 4 && arguments[3].getArgumentExpression()?.evaluate()?.value == false }

  /** Library exception constructors, and Kotlin stdlib throwers. */
  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    analyze(expression) {
      val symbol = call(expression)?.partiallyAppliedSymbol?.symbol ?: return
      if (symbol is KaConstructorSymbol) {
        // Through the expanded type: `RuntimeException(…)` resolves via the kotlin.RuntimeException typealias.
        val created = symbol.returnType.expandedSymbol ?: return
        val id = created.classId ?: return
        if (isLibrary(id) && stackful(created)) report(expression)
      } else if (symbol.callableId?.asSingleFqName()?.asString() in throwers) {
        report(expression, "'${expression.calleeExpression?.text}' throws a stackful exception.")
      }
    }
  }

  override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
    super.visitCallableReferenceExpression(expression)
    analyze(expression) {
      val symbol = call(expression)?.partiallyAppliedSymbol?.symbol as? KaConstructorSymbol ?: return
      val created = symbol.returnType.expandedSymbol ?: return
      val id = created.classId ?: return
      if (isLibrary(id) && stackful(created)) report(expression)
    }
  }

  /** Project types (and anonymous objects) extending a stackful library exception. */
  override fun visitClassOrObject(classOrObject: KtClassOrObject) {
    super.visitClassOrObject(classOrObject)
    val entry = classOrObject.superTypeListEntries.filterIsInstance<KtSuperTypeCallEntry>().firstOrNull()
    analyze(classOrObject) {
      val symbol = classOrObject.classSymbol ?: return
      val parent =
        symbol.superTypes.mapNotNull { (it as? KaClassType)?.symbol as? KaClassSymbol }.firstOrNull {
          it.classKind == org.jetbrains.kotlin.analysis.api.symbols.KaClassKind.CLASS
        } ?: return
      val parentId = parent.classId ?: return
      if (!isLibrary(parentId) || declaresFillIn(symbol) || !stackful(parent)) return
      val delegations =
        listOfNotNull(entry?.valueArguments) +
          classOrObject.secondaryConstructors.mapNotNull { constructor ->
            constructor.getDelegationCallOrNull()?.takeIf { it.isCallToThis.not() }?.valueArguments
          }
      if (delegations.isNotEmpty() && delegations.all { stacklessArguments(it, this) }) return
      if (classOrObject is KtObjectDeclaration && classOrObject.isObjectLiteral()) {
        report(entry ?: classOrObject)
      } else {
        report(classOrObject, "Exception type '${classOrObject.name}' captures a stack.")
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleForbiddenCollection

class TruffleForbiddenCollection(config: Config) : TruffleRule(config, "collections"), RequiresAnalysisApi {
  private val owners = policy.owners("collections")

  private fun KaSession.forbidden(element: KtElement): Boolean {
    val symbol = call(element)?.partiallyAppliedSymbol?.symbol as? KaConstructorSymbol ?: return false
    return symbol.containingClassId?.asSingleFqName()?.asString() in owners
  }

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    analyze(expression) { if (forbidden(expression)) report(expression) }
  }

  override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
    super.visitCallableReferenceExpression(expression)
    analyze(expression) { if (forbidden(expression)) report(expression) }
  }

  override fun visitSuperTypeCallEntry(call: KtSuperTypeCallEntry) {
    super.visitSuperTypeCallEntry(call)
    analyze(call) { if (forbidden(call)) report(call) }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleHostInterop

/**
 * Every reference to a banned embedding-API type in guest packages, by simple or qualified name, but not imports: a
 * reviewed crossing can then be suppressed (`@Suppress("TruffleHostInterop")`) where its reason lives.
 */
class TruffleHostInterop(config: Config) : TruffleRule(config, "hostinterop"), RequiresAnalysisApi {
  private val owners = policy.owners("hostinterop")
  private val scope = policy.scope("hostinterop")
  private val allow = policy.list("hostinterop.allow")

  override fun visitSimpleNameExpression(expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression) {
    super.visitSimpleNameExpression(expression)
    if (expression !is KtNameReferenceExpression) return
    if (!Policy.inPackages(expression.containingKtFile.packageFqName.asString(), scope)) return
    if (expression.getStrictParentOfType<KtImportDirective>() != null) return
    analyze(expression) {
      val symbol = expression.mainReference.resolveToSymbol() as? org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol ?: return
      val name = symbol.classId?.asSingleFqName()?.asString() ?: return
      if (!Policy.inPackages(name, owners) || name in allow) return
      report(expression, "'${symbol.classId?.shortClassName}' is part of the embedding API.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleBlockingInNode

class TruffleBlockingInNode(config: Config) : TruffleRule(config, "blocking"), RequiresAnalysisApi {
  private val calls = Calls(policy, "blocking")
  private val locks = setOf("kotlin.synchronized", "kotlin.concurrent.withLock")

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    analyze(expression) {
      val name = callableName(expression)
      if ((name in locks || calls.matches(this, expression)) && inPartialEvaluation(expression)) {
        report(expression)
      }
    }
  }

  override fun visitNamedFunction(function: KtNamedFunction) {
    super.visitNamedFunction(function)
    analyze(function) {
      if (function.symbol.annotations.contains(Truffle.SYNCHRONIZED) && peBody(function)) report(function)
    }
  }

  /** `@get:Synchronized` / `@set:Synchronized` properties of nodes. */
  override fun visitProperty(property: KtProperty) {
    super.visitProperty(property)
    if (property.isLocal) return
    analyze(property) {
      val symbol = property.symbol as? KaPropertySymbol ?: return
      val accessors = listOfNotNull(symbol.getter, symbol.setter)
      if (accessors.none { it.annotations.contains(Truffle.SYNCHRONIZED) }) return
      val owner = property.getStrictParentOfType<KtClassOrObject>() ?: return
      if (isNode(owner.classSymbol?.defaultType)) report(property)
    }
  }

  private fun KaSession.peBody(function: KtNamedFunction): Boolean {
    val body = function.bodyBlockExpression ?: function.bodyExpression ?: return false
    val first = (body as? org.jetbrains.kotlin.psi.KtBlockExpression)?.statements?.firstOrNull() ?: body
    return inPartialEvaluation(first)
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleHostValueInImageHeap

class TruffleHostValueInImageHeap(config: Config) : TruffleRule(config, "hostvalue"), RequiresAnalysisApi {
  private val calls = Calls(policy, "hostvalue")

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)
    val owner = staticOwner(expression) ?: return
    if (!policy.initializedAtBuildTime(owner)) return
    analyze(expression) {
      if (calls.matches(this, expression)) {
        report(expression)
        return
      }
      val reached = reachedHostValue(expression) ?: return
      report(expression, "Static initialization calls '${expression.calleeExpression?.text}', which reads '$reached'.")
    }
  }

  /** Enum entries without arguments still run the enum's constructor. */
  override fun visitEnumEntry(enumEntry: KtEnumEntry) {
    super.visitEnumEntry(enumEntry)
    if (enumEntry.initializerList != null) return
    val enum = enumEntry.getStrictParentOfType<KtClass>() ?: return
    val name = binaryName(enum) ?: return
    if (!policy.initializedAtBuildTime(name)) return
    analyze(enumEntry) {
      val reached = enum.primaryConstructor?.let { scan(it, enum) } ?: scan(null, enum)
      if (reached != null) report(enumEntry, "Enum constant initialization reads '$reached'.")
    }
  }

  /** One hop: a constructor or function declared in this file that static initialization runs. */
  private fun KaSession.reachedHostValue(expression: KtCallExpression): String? {
    val symbol = call(expression)?.partiallyAppliedSymbol?.symbol ?: return null
    val declaration = symbol.psi as? KtElement ?: return null
    if (declaration.containingKtFile != expression.containingKtFile) return null
    return when {
      symbol is KaConstructorSymbol -> {
        val type = (declaration as? KtClassOrObject) ?: declaration.getStrictParentOfType<KtClassOrObject>()
        scan(declaration as? KtFunction, type)
      }
      declaration is KtNamedFunction -> scan(declaration, null)
      else -> null
    }
  }

  /** The first host-value call in a function body, or in a class's initializers when [type] is given. */
  private fun KaSession.scan(function: KtFunction?, type: KtClassOrObject?): String? {
    var found: String? = null
    val visitor =
      object : KtTreeVisitorVoid() {
        override fun visitClassOrObject(classOrObject: KtClassOrObject) {}

        override fun visitNamedFunction(function: KtNamedFunction) {}

        override fun visitCallExpression(expression: KtCallExpression) {
          if (found == null && calls.matches(this@scan, expression)) found = expression.calleeExpression?.text
          super.visitCallExpression(expression)
        }
      }
    function?.bodyExpression?.accept(visitor)
    if (function is KtSecondaryConstructor || function == null || function.parent is KtClass) {
      type?.body?.anonymousInitializers?.forEach { it.accept(visitor) }
      type?.body?.properties?.forEach { it.initializer?.accept(visitor) }
      type?.primaryConstructorParameters?.forEach { it.defaultValue?.accept(visitor) }
    }
    return found
  }

  /** Binary name of the class whose static initializer evaluates [element], or null. */
  private fun staticOwner(element: KtElement): String? {
    var current = element.parent
    while (current != null) {
      when (current) {
        is KtFunction,
        is KtPropertyAccessor -> return null
        // Enum entries are KtClass too: their arguments run in the enum's static initializer.
        is KtEnumEntry -> return current.getStrictParentOfType<KtClass>()?.let(::binaryName)
        is KtClass -> return null
        is KtObjectLiteralExpression -> {
          current = current.parent
          continue
        }
        is KtAnonymousInitializer -> {
          val holder = current.getStrictParentOfType<KtClassOrObject>()
          return (holder as? KtObjectDeclaration)?.takeUnless { it.isObjectLiteral() }?.let(::binaryName)
        }
        is KtProperty -> {
          if (current.isLocal) {
            current = current.parent
            continue
          }
          if (current.isTopLevel) return facade(current.containingKtFile)
          val holder = current.getStrictParentOfType<KtClassOrObject>()
          if (holder is KtObjectDeclaration && holder.isObjectLiteral()) {
            current = holder.parent
            continue
          }
          return (holder as? KtObjectDeclaration)?.let(::binaryName)
        }
      }
      current = current.parent
    }
    return null
  }

  /** Top-level declarations live on the file facade: `@file:JvmName`, else `<File>Kt`. */
  private fun facade(file: KtFile): String {
    val named =
      file.fileAnnotationList?.annotationEntries?.firstOrNull { it.shortName?.asString() == "JvmName" }
        ?.valueArguments?.firstOrNull()?.getArgumentExpression()?.text?.trim('"')
    val pkg = file.packageFqName.asString().let { if (it.isEmpty()) "" else "$it." }
    return pkg + (named ?: (file.name.removeSuffix(".kt").replaceFirstChar { it.uppercaseChar() } + "Kt"))
  }

  /** Companion statics live on the outer class; named objects and classes are their own. */
  private fun binaryName(declaration: KtClassOrObject): String? {
    val target =
      if (declaration is KtObjectDeclaration && declaration.isCompanion()) {
        declaration.getStrictParentOfType<KtClassOrObject>()
      } else declaration
    val chain = generateSequence(target) { it.getStrictParentOfType<KtClassOrObject>() }.toList()
    if (target == null || chain.any { it.name == null }) return null
    val pkg = target.containingKtFile.packageFqName.asString().let { if (it.isEmpty()) "" else "$it." }
    return pkg + chain.asReversed().joinToString("$") { it.name!! }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleNodeChildren

class TruffleNodeChildren(config: Config) : TruffleRule(config, "children"), RequiresAnalysisApi {
  private val iterable = ClassId.fromString("kotlin/collections/Iterable")
  private val map = ClassId.fromString("kotlin/collections/Map")

  /** A node that could be adopted: not a RootNode (roots are referenced, never children). */
  private fun KaSession.isChild(type: KaType?): Boolean = isNode(type) && !isSubtype(type, Truffle.ROOT_NODE)

  private fun KaSession.isNodeArray(type: KaType?): Boolean =
    type != null && type.isArrayOrPrimitiveArray && isChild(type.arrayElementType)

  private fun KaSession.isNestedNodeArray(type: KaType?): Boolean =
    type != null && type.isArrayOrPrimitiveArray &&
      (isNodeArray(type.arrayElementType) || isNestedNodeArray(type.arrayElementType))

  private fun KaSession.isNodeCollection(type: KaType?): Boolean {
    if (type !is KaClassType || !(type.isSubtypeOf(iterable) || type.isSubtypeOf(map))) return false
    return type.typeArguments.any { argument ->
      val bound = (argument as? KaTypeArgumentWithVariance)?.type
      isChild(bound) || isNodeArray(bound)
    }
  }

  override fun visitProperty(property: KtProperty) {
    super.visitProperty(property)
    if (property.isLocal || property.isTopLevel) return
    val owner = property.getStrictParentOfType<KtClassOrObject>() ?: return
    if (owner is KtObjectDeclaration && owner.isCompanion()) return
    analyze(property) {
      if (!extendsNode(owner)) return
      val symbol = property.symbol as? KaPropertySymbol ?: return
      val type = symbol.returnType
      if (isNestedNodeArray(type) || isNodeCollection(type)) {
        report(
          property,
          "Collections and nested arrays of nodes can't be adopted; hold children in one @Children array property.",
        )
        return
      }
      if (!property.isVar) return
      val annotations = symbol.annotations.classIds + (symbol.backingFieldSymbol?.annotations?.classIds ?: emptyList())
      if (isNodeArray(type) && Truffle.CHILDREN !in annotations) {
        report(property, "Node array properties must be @Children.")
      } else if (isChild(type) && !isNodeArray(type) && Truffle.CHILD !in annotations) {
        report(property, "Node properties must be @Child.")
      }
    }
  }

  /** Object expressions extending Node that capture an outer node local or parameter. */
  override fun visitSimpleNameExpression(expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression) {
    super.visitSimpleNameExpression(expression)
    if (expression !is KtNameReferenceExpression) return
    val literal = expression.getStrictParentOfType<KtObjectLiteralExpression>() ?: return
    analyze(expression) {
      val target = expression.mainReference.resolveToSymbol() as? KaVariableSymbol ?: return
      if (target !is KaLocalVariableSymbol && target !is KaValueParameterSymbol) return
      if (!(isChild(target.returnType) || isNodeArray(target.returnType))) return
      if (!extendsNode(literal.objectDeclaration)) return
      // Declared inside the object expression: not a capture.
      val declaration = target.psi ?: return
      if (declaration.getStrictParentOfType<KtObjectLiteralExpression>() == literal) return
      // Initializing an annotated child property is how the capture becomes owned.
      val property = expression.getStrictParentOfType<KtProperty>()
      if (property != null && property.getStrictParentOfType<KtObjectLiteralExpression>() == literal) {
        val symbol = property.symbol as? KaPropertySymbol
        val annotations =
          (symbol?.annotations?.classIds ?: emptyList()) + (symbol?.backingFieldSymbol?.annotations?.classIds ?: emptyList())
        if (Truffle.CHILD in annotations || Truffle.CHILDREN in annotations) return
      }
      report(expression, "Captured child nodes must be stored in a @Child/@Children property.")
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// TruffleCompilationFinalDimensions

class TruffleCompilationFinalDimensions(config: Config) :
  TruffleRule(config, "compilationfinal"), RequiresAnalysisApi {
  override fun visitProperty(property: KtProperty) {
    super.visitProperty(property)
    if (property.isLocal) return
    analyze(property) {
      val symbol = property.symbol as? KaPropertySymbol ?: return
      if (!symbol.returnType.isArrayOrPrimitiveArray) return
      val annotation =
        (symbol.annotations[Truffle.COMPILATION_FINAL] +
            (symbol.backingFieldSymbol?.annotations?.get(Truffle.COMPILATION_FINAL) ?: emptyList()))
          .firstOrNull() ?: return
      if (annotation.arguments.none { it.name.asString() == "dimensions" }) report(property)
    }
  }
}
