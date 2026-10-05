package mml.mmlclib.codegen

enum TargetAbi derives CanEqual:
  case X86_64
  case AArch64
  case AppleAArch64
  case Default

object TargetAbi:

  def fromHint(hint: Option[String]): TargetAbi =
    val parts = hint.getOrElse("").toLowerCase.split("-").toList
    val linux =
      parts.contains("linux") && !parts.exists(p => p.contains("ilp32") || p.contains("x32"))
    val apple =
      parts.contains("apple") && parts.exists(p => p.startsWith("darwin") || p.startsWith("macos"))
    parts.headOption match
      case Some("x86_64" | "amd64") if linux || apple => TargetAbi.X86_64
      case Some("aarch64" | "arm64") if apple => TargetAbi.AppleAArch64
      case Some("aarch64" | "arm64") if linux => TargetAbi.AArch64
      case _ => TargetAbi.Default
