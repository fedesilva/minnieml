package mml.mmlclib.codegen.emitter.alias

import mml.mmlclib.ast.Type
import mml.mmlclib.codegen.emitter.{CodeGenState, TypeNameResolver}

object AliasScopeEmitter:

  /** Returns alias scope/noalias tags for a given type specification. */
  def getAliasScopeTags(
    typeSpec: Type,
    state:    CodeGenState
  ): (CodeGenState, Option[String], Option[String]) =
    if !state.emitAliasScopes then (state, None, None)
    else
      TypeNameResolver.getMmlTypeName(typeSpec, state.resolvables) match
        case Right(typeName) =>
          val (nextState, aliasTag, noaliasTag) = getAliasScopeTagsByNameImpl(typeName, state)
          (nextState, Some(aliasTag), noaliasTag)
        case Left(_) =>
          (state, None, None)

  /** Returns alias scope/noalias tags for the provided type name. */
  def getAliasScopeTagsByName(
    typeName: String,
    state:    CodeGenState
  ): (CodeGenState, Option[String], Option[String]) =
    if !state.emitAliasScopes then (state, None, None)
    else
      val (nextState, aliasTag, noaliasTag) = getAliasScopeTagsByNameImpl(typeName, state)
      (nextState, Some(aliasTag), noaliasTag)

  private def getAliasScopeTagsByNameImpl(
    typeName: String,
    state:    CodeGenState
  ): (CodeGenState, String, Option[String]) =
    val (stateWithScope, scopeId) = state.ensureAliasScopeNode(typeName)
    val scopeTag                  = s"!{!$scopeId}"
    val noaliasIds = stateWithScope.aliasScopeIds.values
      .filter(_ != scopeId)
      .toList
      .sorted
      .map(id => s"!$id")
    val noaliasTag =
      if noaliasIds.isEmpty then None
      else Some(s"!{${noaliasIds.mkString(", ")}}")
    (stateWithScope, scopeTag, noaliasTag)
