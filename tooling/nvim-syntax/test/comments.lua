-- Run from the repository root with the headless command in README.md.
vim.opt.runtimepath:prepend('tooling/nvim-syntax')
vim.opt.swapfile = false
vim.cmd('filetype plugin on')

local default_commentstring = vim.bo.commentstring
local default_comments = vim.bo.comments
vim.cmd('edit mml-comment-test.mml')
assert(vim.bo.filetype == 'mml')
assert(vim.bo.commentstring == '// %s')
assert(vim.bo.comments == 's1:/*,mb:*,ex:*/,://')

vim.bo.filetype = 'mml_test_unconfigured'
assert(vim.bo.commentstring == default_commentstring, 'Changing filetype must clear MML options')
assert(vim.bo.comments == default_comments)
vim.bo.filetype = 'mml'
assert(vim.bo.commentstring == '// %s', 'Re-entering MML must restore comment settings')

local comment_path = vim.env.COMMENT_NVIM
if comment_path then
  vim.opt.runtimepath:append(comment_path)
  require('Comment').setup()
  vim.cmd('enew')
  vim.bo.filetype = 'mml'

  local api = require('Comment.api')
  local source = '  let a = 1;'
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { source })
  api.toggle.linewise.current()
  assert(vim.api.nvim_get_current_line() == '  // let a = 1;')
  api.toggle.linewise.current()
  assert(vim.api.nvim_get_current_line() == source)
  api.toggle.blockwise.current()
  assert(vim.api.nvim_get_current_line() == '  /* let a = 1; */')
  api.toggle.blockwise.current()
  assert(vim.api.nvim_get_current_line() == source)

  local ft = require('Comment.ft')
  ft.set('mml', { '// %s', '/* %s */' })
  vim.cmd('enew!')
  vim.bo.filetype = 'mml'
  assert(vim.deep_equal(ft.get('mml'), { '// %s', '/* %s */' }), 'Preserve user registration')

  local config = require('Comment.config'):get()
  vim.bo.filetype = 'mml_test_unconfigured'
  assert(config.pre_hook({ ctype = 1 }) == nil, 'Other filetypes must use normal lookup')
  local custom_hook = function() return '// %s' end
  config.pre_hook = custom_hook
  vim.bo.filetype = 'mml'
  assert(config.pre_hook == custom_hook, 'Preserve user pre-hooks')
  print('Comment.nvim line and block toggles passed.')
end

print('MML comment settings and filetype cleanup passed.')
