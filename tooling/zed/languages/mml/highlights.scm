(identifier) @variable
(type_identifier) @type
(type_variable) @type
(parameter name: (identifier) @variable.parameter)
(field name: (identifier) @property)
(selection field: (identifier) @property)
(function_declaration name: (identifier) @function)
(function_signature name: (identifier) @function)
(local_function name: (identifier) @function)
(operator_declaration name: (identifier) @operator)
(operator_signature name: (identifier) @operator)
(operator) @operator
(native_attribute name: (identifier) @attribute)
(integer) @number
(float) @number
(string) @string
(boolean) @boolean
(unit) @constant.builtin
(hole) @constant
(placeholder) @variable.special
(line_comment) @comment
(doc_comment) @comment.doc
(visibility) @keyword
(associativity) @keyword
["let" "fn" "op" "type" "struct" "protocol" "instance" "inline" "if" "then" "elif" "else"] @keyword
["@metal:repr" "@metal:ffi" "@metal:tpl" "@link"] @attribute
["=" "->" "~"] @operator
["(" ")" "[" "]" "{" "}"] @punctuation.bracket
[":" ";" "," "."] @punctuation.delimiter
