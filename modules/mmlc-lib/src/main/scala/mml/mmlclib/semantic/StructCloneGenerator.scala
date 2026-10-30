package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import BindingIds.Allocation

/** Registers eligible struct instances before any clone body selects nested members. */
object StructCloneGenerator:

  private val source = SourceOrigin.Synth

  private def instanceId(struct: TypeStruct): String = s"${struct.id.getOrElse(struct.name)}::Clone"

  /** Scalar fields copy their value; native aggregates require a declared Clone instance. */
  private def scalarField(tpe: Type, index: ResolvablesIndex): Boolean =
    !TypeUtils.requiresDestruction(tpe, index) && (
      TypeUtils.canonical(tpe, index).exists(_.isInstanceOf[TypeUnit]) ||
        TypeUtils.resolveNativeType(tpe, index).exists {
          case _: NativePrimitive | _: NativePointer => true
          case _ => false
        }
    )

  /** Derivation requires scalar fields or declared/derivable Clone support. Declaration identity
    * guards recursive aggregates; function-bearing structs remain unsupported.
    */
  private def eligible(module: Module): List[TypeStruct] =
    val index     = module.resolvables
    val protocols = module.members.collect { case protocol: Protocol => protocol }
    val declared = module.members
      .collect {
        case instance: ProtocolInstance
            if ProtocolRegistry
              .resolveDeclaration(instance.protocol, protocols)
              .flatMap(_.id)
              .contains(CloneCalls.protocolId) =>
          ProtocolRegistry.typeKey(instance.concreteType, index).toOption
      }
      .flatten
      .toSet

    def supported(tpe: Type, seen: Set[String]): Boolean =
      if TypeUtils.containsFunction(tpe, index) then false
      else if ProtocolRegistry.typeKey(tpe, index).toOption.exists(declared.contains) then true
      else if scalarField(tpe, index) then true
      else
        TypeUtils.declaration(tpe, index) match
          case Some(struct: TypeStruct) if !struct.id.exists(seen.contains) =>
            struct.fields.forall(field => supported(field.typeSpec, seen ++ struct.id))
          case Some(_: TypeStruct) => false
          case _ => false

    module.members.collect {
      case struct: TypeStruct
          if struct.fields.forall(field => supported(field.typeSpec, struct.id.toSet)) =>
        struct
    }

  /** Reserve member and parameter identities before ProtocolChecker validates all instances. Empty
    * bodies are filled only after the registry and lexical references are available.
    */
  def register(state: CompilerState): CompilerState =
    val allocation = eligible(state.module).traverse { struct =>
      val id  = instanceId(struct)
      val tpe = TypeRef(source, struct.name, struct.id)
      LocalBindings
        .param(
          BindingOwner.binding(state.module.name, id),
          "value",
          typeAsc = tpe.some,
          purpose = "clone"
        )
        .map { parameter =>
          val member = Bnd(
            source   = source,
            nameNode = Name.synth("^"),
            value = Expr(
              source,
              List(
                Lambda(
                  source,
                  List(parameter),
                  Expr(source, Nil),
                  Nil
                )
              )
            ),
            id = s"$id::member".some
          )
          ProtocolInstance(
            source       = source,
            protocol     = ProtocolRef(source, Name.synth("Clone"), CloneCalls.protocolId.some),
            concreteType = tpe,
            members      = List(ProtocolInstanceMember(member, BindingOrigin.Operator)),
            id           = id.some
          )
        }
    }
    val reserved = allocation.flatTap { instances =>
      instances
        .flatMap(_.members)
        .traverse_(member => BindingIds.reserveDeclaration(member.binding))
    }
    val (supply, instances) = reserved.run(state.bindingIds.include(state.module)).value
    state
      .copy(bindingIds = supply)
      .withModule(
        ResolvablesIndexer.refresh(
          state.module.copy(members = state.module.members ++ instances)
        )
      )

  /** Fill only successfully registered generated members, retaining protocol binding identities.
    * Rejected declarations retain their diagnostics and are not offered as clone targets.
    */
  def generateBodies(state: CompilerState): CompilerState =
    val index = state.module.resolvables
    val allocation =
      state.module.members.collect { case struct: TypeStruct => struct }.flatTraverse { struct =>
        val registered = for
          selected <- state.protocols.instance(CloneCalls.protocolId, struct, index).toOption
          if selected == instanceId(struct)
          binding <- CloneCalls.target(struct, index, state.protocols)
        yield binding

        registered
          .traverse { binding =>
            cloneExpression(struct, binding, state.module.name, index, state.protocols)
              .map { (generated, errors) =>
                (binding.copy(value = generated), errors)
              }
          }
          .map(_.toList)
      }
    val (supply, generated) = allocation.run(state.bindingIds.include(state.module)).value
    val replacements        = generated.flatMap((binding, _) => binding.id.map(_ -> binding)).toMap
    val members = state.module.members.map {
      case binding: Bnd => binding.id.flatMap(replacements.get).getOrElse(binding)
      case other => other
    }
    state
      .copy(bindingIds = supply)
      .withModule(ResolvablesIndexer.refresh(state.module.copy(members = members)))
      .addErrors(generated.flatMap(_._2))

  /** Build a concrete member body from field projections and registered Clone calls. The input
    * borrows; the constructor consumes the independently cloned fields.
    */
  private def cloneExpression(
    struct:      TypeStruct,
    binding:     Bnd,
    moduleName:  String,
    resolvables: ResolvablesIndex,
    registry:    ProtocolRegistry
  ): Allocation[(Expr, List[SemanticError])] =
    val structName = struct.name
    val paramName  = "s"

    // Type refs
    val structTypeRef =
      TypeRef(source, structName, struct.id, Nil)

    // Parameter - not consuming since we're cloning (borrowing)
    val allocation = LocalBindings.local(
      BindingOwner.binding(moduleName, binding.name),
      paramName,
      typeAsc   = Some(structTypeRef),
      consuming = false,
      purpose   = "clone"
    )

    allocation
      .map { local =>
        val param = local.param
        // Build the constructor type as a curried function: T1 -> T2 -> ... -> StructType
        val fieldTypes = struct.fields.map(_.typeSpec).toList match
          case Nil => List(TypeRef(source, "Unit", stdlibTypeId("Unit")))
          case fields => fields
        val constructorType = fieldTypes.foldRight(structTypeRef: Type) { (fieldType, accType) =>
          TypeFn(source, cats.data.NonEmptyList.one(fieldType), accType)
        }

        // Constructor reference: __mk_StructName
        val constructorName = s"__mk_$structName"
        val constructorRef = Ref(
          SourceOrigin.Synth,
          constructorName,
          resolvedId = BindingIds.declaration(moduleName, constructorName).some,
          typeSpec   = Some(constructorType)
        )

        // Non-scalar fields use their registered Clone member.
        val fieldResults = struct.fields.toList.map { field =>
          val paramRef    = local.ref
          val fieldRef    = Ref(SourceOrigin.Synth, field.name, qualifier = Some(paramRef))
          val fieldAccess = Expr(source, List(fieldRef), typeSpec = Some(field.typeSpec))

          val cloned =
            if !scalarField(field.typeSpec, resolvables) then
              CloneCalls.build(
                fieldAccess,
                field.typeSpec,
                resolvables,
                "clone-bodies",
                registry
              )
            else fieldAccess.asRight[SemanticError]
          (cloned.getOrElse(fieldAccess), cloned.left.toOption.toList)
        }
        val argExprs = fieldResults.map(_._1) match
          case Nil =>
            List(
              Expr(
                source,
                List(LiteralUnit(source, TypeRef(source, "Unit", stdlibTypeId("Unit")).some))
              )
            )
          case args => args
        val errors = fieldResults.flatMap(_._2)

        // Build constructor call: __mk_StructName arg1 arg2 ...
        // Track the result type through each curried application
        val (constructorCall, _) =
          argExprs.foldLeft((constructorRef: Callee, constructorType)) {
            case ((fn, currType), arg) =>
              val resultType = currType match
                case TypeFn(_, _, ret) => ret
                case other => other
              val app = App(source, fn, arg, typeSpec = Some(resultType))
              (app, resultType)
          }

        val body = Expr(source, List(constructorCall), typeSpec = Some(structTypeRef))

        // Build the function type: StructName -> StructName
        val fnType =
          TypeFn(source, cats.data.NonEmptyList.one(structTypeRef), structTypeRef)

        // Build the lambda
        val lambda = Lambda(
          source,
          List(param),
          body,
          Nil,
          typeSpec = Some(fnType),
          typeAsc  = Some(structTypeRef)
        )

        (Expr(source, List(lambda)), errors)
      }
