package mml.mmlclib.compiler

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.CompilationMode
import mml.mmlclib.semantic.SemanticError

object PreCodegenValidator:

  private type Check = (CompilationMode, CompilerState) => CompilerState

  private val allowedReturnNames       = Set("Unit", "Int64", "Int")
  private val allowedNativeReturnLlvmT = Set("i64")

  private val checks: List[Check] = List(
    validateEntryPoint
  )

  def validate(mode: CompilationMode)(state: CompilerState): CompilerState =
    checks.foldLeft(state)((currentState, check) => check(mode, currentState))

  private def validateEntryPoint(
    mode:  CompilationMode,
    state: CompilerState
  ): CompilerState =
    mode match
      case CompilationMode.Exe =>
        findMainFn(state.module) match
          case None =>
            state.addError(
              SemanticError.InvalidEntryPoint(
                "No entry point 'main' found for binary compilation",
                state.module.span
              )
            )
          case Some((bnd, _)) =>
            bnd.typeSpec match
              case Some(fnType) =>
                extractReturnType(fnType, state.module) match
                  case Some(retType) if isValidReturnType(retType, state.module) =>
                    val mangledMain = s"${state.module.name.toLowerCase}_main"
                    state.withEntryPoint(mangledMain)
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
        bnd.value.terms.headOption.collect {
          case lambda: Lambda if isValidMainParams(lambda, module) => (bnd, lambda)
        }
    }.flatten

  private def isValidMainParams(lambda: Lambda, module: Module): Boolean =
    lambda.params match
      case Nil => true
      case param :: Nil =>
        !param.consuming && paramType(param).exists(isStringArrayType(_, module))
      case _ => false

  private def paramType(param: FnParam): Option[Type] =
    param.typeSpec.orElse(param.typeAsc)

  private def isStringArrayType(typeSpec: Type, module: Module): Boolean =
    resolveAliasChain(typeSpec, module) match
      case TypeRef(_, name, _, _) => name == "StringArray"
      case _ => false

  private def extractReturnType(typeSpec: Type, module: Module): Option[Type] =
    resolveAliasChain(typeSpec, module) match
      case TypeScheme(_, _, bodyType) => extractReturnType(bodyType, module)
      case fnType: TypeFn => Some(resolveAliasChain(fnType.returnType, module))
      case _ => None

  private def isValidReturnType(typeSpec: Type, module: Module): Boolean =
    val canonical = resolveAliasChain(typeSpec, module)
    canonical match
      case TypeUnit(_) => true
      case NativePrimitive(_, llvmType, _, _) => allowedNativeReturnLlvmT.contains(llvmType)
      case TypeRef(_, name, _, _) => allowedReturnNames.contains(name)
      case _ => false

  private def resolveAliasChain(typeSpec: Type, module: Module): Type = typeSpec match
    case TypeGroup(_, types) if types.size == 1 =>
      resolveAliasChain(types.head, module)
    case tr: TypeRef =>
      // Look up resolved type by ID, or fall back to name lookup
      val resolved = tr.resolvedId
        .flatMap(module.resolvables.lookupType)
        .orElse(module.members.collectFirst {
          case ta: TypeAlias if ta.name == tr.name => ta
          case td: TypeDef if td.name == tr.name => td
        })
      resolved match
        case Some(ta: TypeAlias) =>
          ta.typeSpec match
            case Some(resolvedSpec: Type) => resolvedSpec
            case None => resolveAliasChain(ta.typeRef, module)
        case Some(td: TypeDef) =>
          // Return TypeRef to the TypeDef, not its native typeSpec
          TypeRef(tr.span, td.name, td.id)
        case _ => tr
    case other => other
