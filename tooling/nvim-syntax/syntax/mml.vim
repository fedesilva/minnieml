" Vim syntax file
" Language: MinnieML (MML)
" Maintainer: MML Project
" Latest Revision: 2025-10-30

if exists("b:current_syntax")
  finish
endif

" Case sensitive
syn case match

" ============================================================================
" Comments
" ============================================================================

" Line comments: // comment (but not /* which starts doc comments)
syn match mmlComment "//.*$" contains=mmlTodo

" Documentation comments: /* ... */ (can be nested)
syn region mmlDocComment start="/\*" end="\*/" contains=mmlDocComment,mmlTodo

" TODO/FIXME/NOTE highlighting within comments
syn keyword mmlTodo contained TODO FIXME NOTE XXX

" ============================================================================
" Keywords
" ============================================================================

" Control flow keywords
syn keyword mmlConditional if then elif else

" Declaration keywords
syn keyword mmlKeyword module let type end inline instance

" Visibility keywords
syn keyword mmlKeyword pub protected lexical

" Associativity keywords
syn keyword mmlKeyword left right

" Boolean constants
syn keyword mmlBoolean true false

" Special constants
syn match mmlConstant "\<???\>"
syn match mmlConstant "\<_\>"

" Unit literal
syn match mmlConstant "()"

" ============================================================================
" Operators
" ============================================================================

" Assignment operator
syn match mmlOperator "="

" Type annotation
syn match mmlOperator ":"

" Terminator
syn match mmlOperator ";"

" Comma separator
syn match mmlOperator ","

" Symbolic operators (but not single = or : which are handled above)
" Avoid matching // and /* so comments stay in comment groups.
syn match mmlOperator "\%(//\|/\*\)\@![!$%^&*+<>?/\\|~-]\+"

" Parentheses and brackets
syn match mmlDelimiter "[(){}\[\]]"

" ============================================================================
" Literals
" ============================================================================

" String literals (double-quoted)
syn region mmlString start=+"+ skip=+\\.+ end=+"+ contains=mmlEscape

" Escape sequences in strings
syn match mmlEscape contained "\\."

" Numeric literals
" Float: digits with decimal point
syn match mmlFloat "\<[0-9]\+\.[0-9]\+\>"
syn match mmlFloat "\<\.[0-9]\+\>"

" Integer: just digits (must come after float to avoid conflicts)
syn match mmlNumber "\<[0-9]\+\>"

" ============================================================================
" Types
" ============================================================================

" Uppercase type names and quoted type variables share the Type highlight.
syn match mmlType "\%('\|\<\)[A-Z][a-zA-Z0-9]*\>"

" ============================================================================
" Native Annotations
" ============================================================================

" Metal annotations
syn match mmlNative "@metal:\%(repr\|ffi\|tpl\)\>"

" ============================================================================
" Identifiers
" ============================================================================

" Regular identifiers (variables, functions, parameters)
" Pattern: [a-z][a-zA-Z0-9_]*
" We define this with lower priority so keywords take precedence
syn match mmlIdentifier "\<[a-z][a-zA-Z0-9_]*\>"

" A requirement clause starts with a type variable; ordinary where identifiers keep their role.
let s:constraint_trivia = '\%(\_s\|/\*\_.\{-}\*/\|//[^\n]*\n\)*'
execute 'syn match mmlWhere contained "\<where\>\ze' . s:constraint_trivia
      \ . "'[A-Z][A-Za-z0-9]*" . s:constraint_trivia . ':"'
unlet s:constraint_trivia
hi def link mmlWhere Keyword

" ============================================================================
" Special Regions
" ============================================================================

" Function definitions - highlight function names specially
" fn name(...) = ...
syn match mmlFunction "\<fn\s\+\zs[a-z][a-zA-Z0-9_]*\>" contained
syn region mmlFunctionDef matchgroup=mmlKeyword start="\<fn\>" matchgroup=mmlOperator end="[=;]" contains=mmlKeyword,mmlWhere,mmlFunction,mmlType,mmlOperator,mmlIdentifier,mmlComment,mmlDocComment transparent

" Protocol headers share constraint highlighting with function headers.
syn region mmlProtocolDef matchgroup=mmlKeyword start="\<protocol\>" matchgroup=mmlOperator end="[=;]" contains=mmlKeyword,mmlWhere,mmlType,mmlOperator,mmlIdentifier,mmlComment,mmlDocComment transparent

" Type definitions - highlight type names specially
" type Name = ...
syn match mmlTypeDecl "\<type\s\+\zs[A-Z][a-zA-Z0-9]*\>" contained
syn region mmlTypeDef start="\<type\>" end="=" contains=mmlKeyword,mmlTypeDecl,mmlNative,mmlType,mmlOperator,mmlComment,mmlDocComment transparent keepend

" Operator definitions - highlight operator names
" op + (a: T, b: T): T = ...
syn match mmlOperatorDecl "\<op\s\+\zs[=!#$%^&*+<>?/\\|~-]\+" contained
syn match mmlOperatorDecl "\<op\s\+\zs[a-z][a-zA-Z0-9_]*\>" contained
" A symbolic name can contain equals signs. Only a standalone equals starts the body.
syn region mmlOperatorDef matchgroup=mmlKeyword start="\<op\>" matchgroup=mmlOperator end=";\|[=!#$%^&*+<>?/\\|.~-]\@<!=[=!#$%^&*+<>?/\\|.~-]\@!" contains=mmlKeyword,mmlWhere,mmlOperatorDecl,mmlType,mmlOperator,mmlNumber,mmlIdentifier,mmlComment,mmlDocComment transparent

" ============================================================================
" Highlight Linking
" ============================================================================

" Link MML syntax groups to standard Vim highlight groups

" Comments
hi def link mmlComment Comment
hi def link mmlDocComment SpecialComment
hi def link mmlTodo Todo

" Keywords
hi def link mmlKeyword Keyword
hi def link mmlConditional Conditional

" Constants
hi def link mmlBoolean Boolean
hi def link mmlConstant Constant

" Operators and delimiters
hi def link mmlOperator Operator
hi def link mmlDelimiter Delimiter

" Literals
hi def link mmlString String
hi def link mmlEscape SpecialChar
hi def link mmlNumber Number
hi def link mmlFloat Float

" Types
hi def link mmlType Type
hi def link mmlTypeDecl Type

" Native annotations
hi def link mmlNative Special

" Identifiers
hi def link mmlIdentifier Identifier
hi def link mmlFunction Function
hi def link mmlOperatorDecl Function

" ============================================================================
" Finalize
" ============================================================================

let b:current_syntax = "mml"
