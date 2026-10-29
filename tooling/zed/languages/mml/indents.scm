(function_declaration "=" @start ";" @end) @indent
(local_function "=" @start ";" @end) @indent
(operator_declaration "=" @start ";" @end) @indent
(protocol_declaration "=" @start ";" @end) @indent
(instance_declaration "=" @start ";" @end) @indent
(let_declaration "=" @start ";" @end) @indent
(local_binding "=" @start ";" @end) @indent
(conditional consequence: (body) @indent)
(elif_clause (body) @indent)
(else_clause (body) @indent)
(fields "{" @start "}" @end) @indent
(lambda "{" @start "}" @end) @indent
(parameters "(" @start ")" @end) @indent
(group "(" @start ")" @end) @indent
