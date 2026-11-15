-- Comment.nvim requires a filetype entry for block comments.
local ok, ft = pcall(require, 'Comment.ft')
if ok and not ft.get('mml') then
  ft.set('mml', { '//%s', '/*%s*/' })
end

if ok then
  local config = require('Comment.config'):get()
  -- Comment.nvim's parser lookup can fail on Neovim 0.12 without a parser.
  if not config.pre_hook then
    config.pre_hook = function(ctx)
      if vim.bo.filetype == 'mml' then
        return ft.get('mml', ctx.ctype)
      end
    end
  end
end
