-- Run with the published compiler using the command in README.md.
local source = [[protocol Inspect 'T =
  fn inspect(value: 'T): Int;
;
instance Inspect Int =
  fn inspect(value) = value;;
;
instance Inspect Bool =
  fn inspect(value) = 0;;
;
let integer = inspect 1;
let boolean = inspect true;]]

local configs = {}
local server_config
package.preload['lspconfig.configs'] = function() return configs end
package.preload['lspconfig'] = function()
  return {
    util = { find_git_ancestor = function() return vim.fn.getcwd() end },
    mml = {
      setup = function(options)
        server_config = vim.tbl_deep_extend('force', configs.mml.default_config, options)
      end,
    },
  }
end

dofile('tooling/nvim-syntax/after/plugin/mml_lsp.lua')
assert(vim.deep_equal(server_config.cmd, { 'mmlc', 'lsp' }))

local buffer = vim.api.nvim_get_current_buf()
local window = vim.api.nvim_get_current_win()
vim.api.nvim_buf_set_name(buffer, vim.fn.getcwd() .. '/build/navigation-neovim.mml')
vim.api.nvim_buf_set_lines(buffer, 0, -1, false, vim.split(source, '\n'))
vim.bo[buffer].filetype = 'mml'
server_config.name = 'mml'
server_config.root_dir = server_config.root_dir()
server_config.cmd[1] = vim.env.MMLC or server_config.cmd[1]
local exit_status
server_config.on_exit = function(code, signal) exit_status = { code = code, signal = signal } end
local client_id = assert(vim.lsp.start(server_config), 'MML language server did not start')
local client = assert(vim.lsp.get_client_by_id(client_id))

local function await_result(predicate, label)
  assert(vim.wait(30000, predicate, 10), 'Timed out: ' .. label)
end

local function select_position(line, column)
  vim.cmd('cclose')
  vim.api.nvim_set_current_win(window)
  vim.api.nvim_win_set_buf(window, buffer)
  vim.api.nvim_win_set_cursor(window, { line, column - 1 })
end

local function check_definition(line, column, target_line, target_column)
  select_position(line, column)
  vim.cmd('MmlDefinition')
  await_result(function()
    return vim.api.nvim_win_get_buf(window) == buffer
      and vim.deep_equal(vim.api.nvim_win_get_cursor(window), { target_line, target_column - 1 })
  end, 'definition cursor destination')
end

local function check_list(action, line, column, expected)
  select_position(line, column)
  vim.fn.setqflist({}, 'r')
  action()
  await_result(function() return #vim.fn.getqflist() > 0 end, 'navigation list')
  local actual = vim.tbl_map(function(item)
    assert(item.bufnr == buffer, 'Navigation must target the source buffer')
    return { item.lnum, item.col, item.end_lnum, item.end_col }
  end, vim.fn.getqflist())
  assert(vim.deep_equal(actual, expected), vim.inspect({ actual = actual, expected = expected }))
end

local function run()
  await_result(function()
    return client.initialized and vim.lsp.buf_is_attached(buffer, client_id)
  end, 'MML attachment')
  assert(client.server_capabilities.definitionProvider)
  assert(client.server_capabilities.implementationProvider)
  assert(client.server_capabilities.referencesProvider)

  check_definition(10, 15, 2, 6)
  check_list(function() vim.cmd('MmlImplementation') end, 1, 10, {
    { 4, 10, 4, 17 }, { 7, 10, 7, 17 },
  })
  check_list(function() vim.cmd('MmlImplementation') end, 2, 6, {
    { 5, 6, 5, 13 }, { 8, 6, 8, 13 },
  })
  check_list(function() vim.cmd('MmlReferences') end, 5, 6, {
    { 5, 6, 5, 13 }, { 10, 15, 10, 22 },
  })
  check_list(function() vim.cmd('MmlReferences') end, 4, 10, {
    { 4, 10, 4, 17 }, { 10, 15, 10, 22 },
  })
  check_list(function() vim.cmd('MmlReferences') end, 2, 6, {
    { 2, 6, 2, 13 }, { 10, 15, 10, 22 }, { 11, 15, 11, 22 },
  })

  select_position(10, 15)
  vim.api.nvim_buf_set_lines(buffer, 0, 0, false, { '// Unsaved source edit shifts every destination.' })
  check_definition(11, 15, 3, 6)
  check_list(function()
    vim.fn.maparg('<leader>mgi', 'n', false, true).callback()
  end, 3, 6, {
    { 6, 6, 6, 13 }, { 9, 6, 9, 13 },
  })
  check_list(function()
    vim.fn.maparg('<leader>mgr', 'n', false, true).callback()
  end, 9, 6, {
    { 9, 6, 9, 13 }, { 12, 15, 12, 22 },
  })
end

local ok, error_message = pcall(run)
client:stop()
if not vim.wait(10000, function() return exit_status ~= nil end, 10) then
  client:stop(true)
  assert(vim.wait(5000, function() return exit_status ~= nil end, 10), 'MML server did not exit')
end
assert(ok, error_message)
assert(exit_status.code == 0 and exit_status.signal == 0, vim.inspect(exit_status))
print('Neovim native navigation passed: commands, mappings, exact destinations, and unsaved edits.')
