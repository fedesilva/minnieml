package mml.mmlclib.ast

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

/** Implements a protocol for a concrete type. Member bindings contain ordinary Lambda values. */
case class ProtocolInstance(
  source:       SourceOrigin,
  protocol:     ProtocolRef,
  concreteType: Type,
  members:      List[Bnd],
  docComment:   Option[DocComment] = None,
  id:           Option[String]     = None
) extends Decl
