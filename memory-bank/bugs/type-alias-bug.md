 some TypeAlias nodes ARE resolved and some are NOT resolved:

  From the Type Checker Phase (Pretty Print):

  TypeAlias prot Int -> Int64
    typeSpec: TypeRef Int64 => TypeDef(Int64)  ← RESOLVED
    typeAsc: None
    typeRef: TypeRef Int64 => TypeDef(Int64)   ← RESOLVED

  TypeAlias prot XInt -> Int64
    typeSpec: TypeRef Int64 => TypeDef(Int64)  ← RESOLVED
    typeAsc: None
    typeRef: TypeRef Int64 => TypeDef(Int64)   ← RESOLVED

  From the Raw AST:

  The Int TypeAlias (injected, BROKEN):

  TypeAlias(Protected,SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),Int,
    TypeRef(SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),Int64,
      Some(TypeDef(Protected,SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),Int64,...))), ← typeRef.resolvedAs = Some(TypeDef)
    Some(TypeRef(SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),Int64,
      Some(TypeDef(Protected,SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),Int64,...)))), ← typeSpec.resolvedAs = Some(TypeDef)
    None,None)

  The XInt TypeAlias (user-defined, WORKING):

  TypeAlias(Protected,SrcSpan(SrcPoint(0,2,1),SrcPoint(2,19,20)),XInt,
    TypeRef(SrcSpan(SrcPoint(2,13,13),SrcPoint(2,18,18)),Int64,
      Some(TypeDef(Protected,SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),Int64,...))), ← typeRef.resolvedAs = Some(TypeDef)
    Some(TypeRef(SrcSpan(SrcPoint(2,13,13),SrcPoint(2,18,18)),Int64,
      Some(TypeDef(Protected,SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),Int64,...)))), ← typeSpec.resolvedAs = Some(TypeDef)
    None,None)

  The Key Difference:

  Both TypeAlias nodes have their typeRef and typeSpec fields properly resolved! This contradicts what I saw in the failing test_print_add.mml case.

  But looking more carefully at the operator parameters in this example, I see the same pattern:

  BinOpDef + parameter (STILL BROKEN):

  TypeAlias(Protected,SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),Int,
    TypeRef(SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),Int64,None), ← typeRef.resolvedAs = None ❌
    None,None,None)

  The Real Pattern:

  - User-defined TypeAlias: Fully resolved (XInt → Int64)
  - Injected TypeAlias in module context: Fully resolved (Int → Int64)
  - Injected TypeAlias embedded in operator parameters: NOT resolved (typeRef.resolvedAs = None)

  The issue is that there are multiple instances of the same TypeAlias floating around, and the TypeResolver is only resolving some instances, not all instances.

  The TypeResolver is resolving the top-level TypeAlias definitions in the module, but it's not recursively updating embedded references to those same TypeAlias nodes inside operator parameter types.


  --- 

see type-alias-bug-dump.txt