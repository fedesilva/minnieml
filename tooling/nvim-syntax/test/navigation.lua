-- Run from the repository root with the headless command in README.md.
local setup_config
local configs = {}
package.preload['lspconfig.configs'] = function() return configs end
package.preload['lspconfig'] = function()
  return {
    util = { find_git_ancestor = function() return '/project' end },
    mml = { setup = function(config) setup_config = config end },
  }
end

local calls = {}
local attached = true
local original_get_clients = vim.lsp.get_clients
vim.lsp.get_clients = function(filter)
  assert(filter.name == 'mml' and filter.bufnr == 0, 'Commands must use the current MML buffer')
  return attached and { { id = 1, name = 'mml' } } or {}
end
vim.lsp.get_client_by_id = function(id)
  return { id = id, name = id == 1 and 'mml' or 'other' }
end
for _, method in ipairs({ 'definition', 'implementation', 'references' }) do
  vim.lsp.buf[method] = function() table.insert(calls, method) end
end
local warnings = {}
vim.notify = function(message) table.insert(warnings, message) end

dofile('tooling/nvim-syntax/after/plugin/mml_lsp.lua')
assert(setup_config, 'The plugin must configure its language server')
assert(vim.deep_equal(configs.mml.default_config.cmd, { 'mmlc', 'lsp' }))

local buffer = vim.api.nvim_get_current_buf()
vim.bo[buffer].filetype = 'mml'
vim.api.nvim_exec_autocmds('LspAttach', { buffer = buffer, data = { client_id = 2 } })
assert(vim.fn.maparg('<leader>mgi', 'n') == '', 'Other language servers must not install MML maps')
vim.api.nvim_exec_autocmds('LspAttach', { buffer = buffer, data = { client_id = 1 } })

for _, navigation in ipairs({
  { command = 'MmlDefinition', key = '<leader>mgd', method = 'definition' },
  { command = 'MmlImplementation', key = '<leader>mgi', method = 'implementation' },
  { command = 'MmlReferences', key = '<leader>mgr', method = 'references' },
}) do
  vim.cmd(navigation.command)
  assert(calls[#calls] == navigation.method, navigation.command .. ' must invoke native navigation')
  local mapping = vim.fn.maparg(navigation.key, 'n', false, true)
  assert(mapping.buffer == 1, 'Navigation maps must be buffer-local')
  mapping.callback()
  assert(calls[#calls] == navigation.method, navigation.key .. ' must invoke native navigation')
end
assert(#calls == 6)

attached = false
vim.cmd('MmlImplementation')
assert(#calls == 6, 'An unattached buffer must not navigate through another buffer\'s server')
assert(#warnings == 1)

local other_buffer = vim.api.nvim_create_buf(true, false)
vim.api.nvim_set_current_buf(other_buffer)
vim.bo[other_buffer].filetype = 'lua'
vim.api.nvim_exec_autocmds('LspAttach', { buffer = other_buffer, data = { client_id = 1 } })
assert(vim.fn.maparg('<leader>mgi', 'n') == '', 'Non-MML buffers must not get MML maps')
vim.lsp.get_clients = original_get_clients
print('MML navigation commands, attachment checks, and buffer mappings passed.')
