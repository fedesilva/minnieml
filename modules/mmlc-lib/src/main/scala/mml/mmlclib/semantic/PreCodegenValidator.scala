package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.CompilationMode

object PreCodegenValidator:

  private type Check = (CompilationMode, SemanticPhaseState) => SemanticPhaseState

  private val allowedReturnNames       = Set("Unit", "Int64", "Int")
  private val allowedNativeReturnLlvmT = Set("i64")

  private val checks: List[Check] = List(
    validateEntryPoint
  )

  def validate(mode: CompilationMode)(state: SemanticPhaseState): SemanticPhaseState =
    checks.foldLeft(state)((currentState, check) => check(mode, currentState))

  private def validateEntryPoint(
    mode:  CompilationMode,
    state: SemanticPhaseState
  ): SemanticPhaseState =
    mode match
      case CompilationMode.Binary =>
        findMainFn(state.module) match
          case None =>
            state.addError(
              SemanticError.InvalidEntryPoint(
                "No entry point 'main' found for binary compilation",
                state.module.span
              )
            )
          case Some((bnd, lambda)) =>
            if lambda.params.nonEmpty then
              state.addError(
                SemanticError
                  .InvalidEntryPoint("Entry point 'main' must have no parameters", bnd.span)
              )
            else
              bnd.typeSpec match
                case Some(fnType) =>
                  extractReturnType(fnType, state.module) match
                    case Some(retType) if isValidReturnType(retType, state.module) => state
                    case _ =>
                      state.addError(
                        SemanticError.InvalidEntryPoint(
                          "Entry point 'main' must have a return type of 'Unit' or 'Int64'",
                          bnd.span
                        )
                      )
                case None =>
                  state.addError(
                    SemanticError.InvalidEntryPoint(
                      "Entry point 'main' must have a return type of 'Unit' or 'Int64'",
                      bnd.span
                    )
                  )
      case _ => state

  private def findMainFn(module: Module): Option[(Bnd, Lambda)] =
    module.members.collectFirst {
      case bnd: Bnd
          if bnd.meta.exists(m => m.origin == BindingOrigin.Function && m.originalName == "main") =>
        bnd.value.terms.headOption.collect { case lambda: Lambda => (bnd, lambda) }
    }.flatten

  private def extractReturnType(typeSpec: TypeSpec, module: Module): Option[TypeSpec] =
    resolveAliasChain(typeSpec, module) match
      case TypeScheme(_, _, bodyType) => extractReturnType(bodyType, module)
      case fnType: TypeFn => Some(resolveAliasChain(fnType.returnType, module))
      case _ => None

  private def isValidReturnType(typeSpec: TypeSpec, module: Module): Boolean =
    val canonical = resolveAliasChain(typeSpec, module)
    canonical match
      case TypeUnit(_) => true
      case NativePrimitive(_, llvmType) => allowedNativeReturnLlvmT.contains(llvmType)
      case TypeRef(_, name, _) => allowedReturnNames.contains(name)
      case _ => false

  private def resolveAliasChain(typeSpec: TypeSpec, module: Module): TypeSpec = typeSpec match
    case TypeGroup(_, types) if types.size == 1 =>
      resolveAliasChain(types.head, module)
    case tr @ TypeRef(_, name, Some(ta: TypeAlias)) =>
      ta.typeSpec match
        case Some(resolvedSpec: TypeSpec) => resolvedSpec
        case None => resolveAliasChain(ta.typeRef, module)
    case tr @ TypeRef(_, name, None) =>
      // If TypeRef doesn't have resolvedAs, look it up in the module
      module.members
        .collectFirst {
          case ta: TypeAlias if ta.name == name =>
            ta.typeSpec match
              case Some(resolvedSpec: TypeSpec) => resolvedSpec
              case None => resolveAliasChain(ta.typeRef, module)
          case td: TypeDef if td.name == name =>
            // Return TypeRef to the TypeDef, not its native typeSpec
            // This ensures type aliases resolve to MML types rather than native representations
            TypeRef(tr.span, td.name, Some(td))
        }
        .getOrElse(tr)
    case other => other
