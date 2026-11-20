-- Exercise the installed Vim syntax engine without an LSP overlay.
vim.opt.runtimepath:prepend(vim.env.MML_NVIM_PLUGIN or 'tooling/nvim-syntax')
vim.opt.swapfile = false
vim.cmd('syntax on')
local fixtures = vim.json.decode(table.concat(vim.fn.readfile('tooling/test/constraint-highlighting.json'), '\n'))
local lines = {}
for _, fixture in ipairs(fixtures) do table.insert(lines, fixture.text) end
vim.api.nvim_buf_set_lines(0, 0, -1, false, lines)
vim.bo.filetype = 'mml'
vim.cmd('syntax sync fromstart')
for line, fixture in ipairs(fixtures) do
  for word, expected in pairs(fixture.words) do
    local offset = 1
    for _, keyword in ipairs(expected) do
      local start = assert(fixture.text:find(word, offset, true))
      local group = vim.fn.synIDattr(vim.fn.synID(line, start, 1), 'name')
      local is_keyword = group == 'mmlKeyword' or group == 'mmlWhere'
      assert(is_keyword == keyword, fixture.text .. ' at ' .. start .. ': ' .. group)
      offset = start + #word
    end
    assert(not fixture.text:find(word, offset, true), 'Unasserted occurrence: ' .. fixture.text)
  end
end
print('MML contextual constraint syntax passed.')
