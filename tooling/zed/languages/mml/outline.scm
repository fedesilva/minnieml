(function_declaration "fn" @context name: (identifier) @name) @item
(function_signature "fn" @context name: (identifier) @name) @item
(local_function "fn" @context name: (identifier) @name) @item
(operator_declaration "op" @context name: (_) @name) @item
(operator_signature "op" @context name: (_) @name) @item
(source_file (let_declaration "let" @context name: (identifier) @name) @item)
(struct_declaration "struct" @context name: (type_identifier) @name) @item
(type_declaration "type" @context name: (type_identifier) @name) @item
(protocol_declaration
  "protocol" @context name: (type_identifier) @name parameter: (type_variable) @context) @item
(instance_declaration
  "instance" @context protocol: (type_identifier) @name type: (_) @name) @item
