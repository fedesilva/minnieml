package mml.mmlclib.codegen

enum TargetAbi derives CanEqual:
  case X86_64
  case AArch64
  case Default

object TargetAbi:
  def fromHint(hint: Option[String]): TargetAbi =
    hint match
      case Some(value) =>
        val lower = value.toLowerCase
        if lower.contains("x86_64") || lower.contains("amd64") then TargetAbi.X86_64
        else if lower.contains("aarch64") || lower.contains("arm64") then TargetAbi.AArch64
        else TargetAbi.Default
      case None => TargetAbi.Default

  def archFromHint(hint: Option[String]): Option[String] =
    hint.flatMap { value =>
      val lower = value.toLowerCase
      if lower.contains("x86_64") || lower.contains("amd64") then Some("x86_64")
      else if lower.contains("aarch64") || lower.contains("arm64") then Some("aarch64")
      else None
    }
