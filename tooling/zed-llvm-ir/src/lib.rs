use std::collections::HashMap;
use zed_extension_api::{self as zed, settings::CommandSettings, settings::LspSettings};

struct LlvmIr;

impl zed::Extension for LlvmIr {
    fn new() -> Self {
        Self
    }

    fn language_server_command(
        &mut self,
        language_server_id: &zed::LanguageServerId,
        worktree: &zed::Worktree,
    ) -> zed::Result<zed::Command> {
        let binary = LspSettings::for_worktree(language_server_id.as_ref(), worktree)?
            .binary
            .unwrap_or(CommandSettings {
                path: None,
                arguments: None,
                env: None,
            });
        let command = binary.path.or_else(|| worktree.which("mml-llvm-ir-lsp")).ok_or(
            "mml-llvm-ir-lsp was not found. Run tooling/install-zed-llvm-ir-extension.sh or set lsp.llvm-ir.binary.path.",
        )?;
        let env = worktree
            .shell_env()
            .into_iter()
            .chain(binary.env.unwrap_or_default())
            .collect::<HashMap<_, _>>();

        Ok(zed::Command {
            command,
            args: binary.arguments.unwrap_or_else(|| vec!["--stdio".into()]),
            env: env.into_iter().collect(),
        })
    }
}

zed::register_extension!(LlvmIr);
