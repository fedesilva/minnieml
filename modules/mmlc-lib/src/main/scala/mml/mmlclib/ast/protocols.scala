package mml.mmlclib.ast

/** A named contract over one type parameter. Members describe callable requirements. */
case class Protocol(
  source:        SourceOrigin,
  nameNode:      Name,
  typeParameter: TypeVariable,
  members:       List[ProtocolMember],
  visibility:    Visibility         = Visibility.Protected,
  docComment:    Option[DocComment] = None,
  id:            Option[String]     = None
) extends Decl,
      Resolvable

/** A callable contract. Parameters retain type annotations and consuming ownership markers.
  * Required members have no default body; metadata describes function or operator syntax.
  */
case class ProtocolMember(
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

/** A protocol name retained alongside its resolved contract identity. */
case class ProtocolRef(
  source:     SourceOrigin,
  nameNode:   Name,
  resolvedId: Option[String] = None
) extends FromSource

/** Implements a protocol for a concrete type. Member bindings contain ordinary Lambda values. */
case class ProtocolInstance(
  source:       SourceOrigin,
  protocol:     ProtocolRef,
  concreteType: Type,
  members:      List[Bnd],
  docComment:   Option[DocComment] = None,
  id:           Option[String]     = None
) extends Decl
