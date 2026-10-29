package mml.mmlclib.ast

import cats.syntax.all.*

/** A protocol over one type parameter, with signatures for its required functions and operators. */
case class Protocol(
  source:        SourceOrigin,
  nameNode:      Name,
  typeParameter: TypeVariable,
  signatures:    List[ProtocolSignature],
  visibility:    Visibility         = Visibility.Protected,
  docComment:    Option[DocComment] = None,
  id:            Option[String]     = None
) extends Decl,
      Resolvable

/** A function or operator signature. Parameters retain type annotations and consuming ownership
  * markers. Required signatures have no default body; metadata describes function or operator
  * syntax.
  */
case class ProtocolSignature(
  source:      SourceOrigin,
  nameNode:    Name,
  params:      List[FnParam],
  returnType:  Type,
  meta:        BindingMeta,
  defaultBody: Option[Lambda]     = None,
  docComment:  Option[DocComment] = None,
  id:          Option[String]     = None
) extends Decl,
      Resolvable

/** A protocol name retained alongside its resolved protocol identity. */
case class ProtocolRef(
  source:     SourceOrigin,
  nameNode:   Name,
  resolvedId: Option[String] = None
) extends FromSource

/** Written instance syntax keeps optional operator attributes beside its ordinary binding body. The
  * binding receives concrete callable metadata when its required signature is checked.
  */
case class ProtocolInstanceMember(
  binding:              Bnd,
  origin:               BindingOrigin,
  writtenPrecedence:    Option[Int]           = None,
  writtenAssociativity: Option[Associativity] = None
):

  /** Operators are identified by source name and parameter count, including before lowering. */
  def operatorParameterCount: Option[Int] = binding.value.terms match
    case (lambda: Lambda) :: Nil if origin == BindingOrigin.Operator => lambda.params.size.some
    case _ => none

/** Implements a protocol for a concrete type. Parsed members contain ordinary Lambda values. After
  * lowering, members is empty and memberIds refers to those bindings in the module index. The
  * executable bodies have one owner: the module's ordinary binding list.
  */
case class ProtocolInstance(
  source:       SourceOrigin,
  protocol:     ProtocolRef,
  concreteType: Type,
  members:      List[ProtocolInstanceMember],
  docComment:   Option[DocComment] = None,
  id:           Option[String]     = None,
  memberIds:    List[String]       = Nil
) extends Decl
