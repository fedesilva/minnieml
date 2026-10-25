// Expressions retain term order: MML resolves user-defined operators in semantic analysis.
module.exports = grammar({
  name: 'mml',

  extras: $ => [/\s/, $.line_comment, $.doc_comment],
  externals: $ => [$.doc_comment],
  word: $ => $.identifier,

  conflicts: $ => [[$.parameter, $._atom], [$.body], [$._type, $.function_type]],

  rules: {
    source_file: $ => seq(optional($.link_directive), repeat($._declaration)),

    _declaration: $ => choice(
      $.let_declaration, $.function_declaration, $.operator_declaration,
      $.struct_declaration, $.type_declaration,
    ),

    visibility: _ => choice('pub', 'prot', 'priv'),
    let_declaration: $ => seq(
      optional($.visibility), 'let', field('name', $.identifier),
      optional($.type_ascription), '=', field('value', $.expression), ';',
    ),
    function_declaration: $ => seq(
      optional($.visibility), optional('inline'), 'fn', field('name', $.identifier),
      $.parameters, optional($.type_ascription), '=', field('body', $.body), ';',
    ),
    local_function: $ => seq(
      'fn', optional('~'), field('name', $.identifier), $.parameters,
      optional($.type_ascription), '=', field('body', $.body), ';',
    ),
    operator_declaration: $ => seq(
      optional($.visibility), optional('inline'), 'op',
      field('name', choice($.operator, $.identifier)), $.parameters,
      optional($.type_ascription), optional($.integer), optional($.associativity),
      '=', field('body', $.body), ';',
    ),
    associativity: _ => choice('left', 'right'),
    parameters: $ => seq('(', optional(commaSep1($.parameter)), ')'),
    parameter: $ => seq(optional('~'), field('name', $.identifier), optional($.type_ascription)),

    struct_declaration: $ => seq(
      optional($.visibility), 'struct', field('name', $.type_identifier), $.fields, ';',
    ),
    fields: $ => seq('{', optional(commaSep1($.field)), '}'),
    field: $ => seq(field('name', $.identifier), $.type_ascription),
    type_declaration: $ => seq(
      optional($.visibility), 'type', field('name', $.type_identifier), '=',
      choice($._type, $.native_type), ';',
    ),
    type_ascription: $ => seq(':', $._type),
    _type: $ => choice($.type_identifier, $.grouped_type, $.function_type),
    grouped_type: $ => seq('(', $._type, ')'),
    function_type: $ => prec.right(seq(
      choice($.type_identifier, $.grouped_type), '->', $._type,
    )),

    link_directive: $ => seq('@link', '[', commaSep1($.string), ']', ';'),
    native_type: $ => seq('@metal:repr', choice(
      seq($.native_attributes, optional($.fields)), $.fields,
    )),
    native_expression: $ => choice(
      seq('@metal:ffi', optional($.native_attributes)),
      seq('@metal:tpl', $.native_template_attributes),
    ),
    native_template_attributes: $ => seq('[', $.string, repeat(seq(',', $.native_attribute)), ']'),
    native_attributes: $ => seq('[', commaSep1($.native_attribute), ']'),
    native_attribute: $ => seq(
      field('name', $.identifier), '=',
      field('value', choice($.string, $.identifier, $.native_pointer)),
    ),
    native_pointer: $ => seq('*', $.identifier),

    body: $ => repeat1(choice($.local_binding, $.local_function, $.expression_statement)),
    local_binding: $ => seq(
      'let', field('name', $.identifier), optional($.type_ascription), '=', $.expression, ';',
    ),
    expression_statement: $ => seq($.expression, ';'),
    expression: $ => repeat1($._term),
    _term: $ => seq($._atom, optional($.type_ascription)),
    _atom: $ => choice(
      $.identifier, $.type_identifier, $.operator, $.integer, $.float, $.string,
      $.boolean, $.unit, $.hole, $.placeholder, $.group, $.tuple,
      $.lambda, $.conditional, $.selection, $.native_expression,
    ),
    group: $ => seq('(', optional($.body), $.expression, ')'),
    tuple: $ => seq('(', $.expression, repeat1(seq(',', $.expression)), ')'),
    selection: $ => prec.left(2, seq(
      field('value', choice($.identifier, $.type_identifier, $.group, $.selection)),
      token.immediate('.'), field('field', alias(token.immediate(/[a-z][a-zA-Z0-9_]*/), $.identifier)),
    )),
    lambda: $ => seq(
      optional('~'), '{', optional(seq(commaSep1($.parameter), '->')),
      choice($.body, seq(optional($.body), $.expression)), '}',
    ),
    conditional: $ => prec.right(seq(
      'if', field('condition', $.expression), 'then', field('consequence', $.body),
      repeat($.elif_clause), optional($.else_clause),
    )),
    elif_clause: $ => seq('elif', $.expression, 'then', $.body),
    else_clause: $ => seq('else', $.body),

    identifier: _ => /[a-z][a-zA-Z0-9_]*/,
    type_identifier: _ => /[A-Z][a-zA-Z0-9]*/,
    operator: _ => token(prec(-1, /[=!#$%^&*+<>?\/\\|.\-]+/)),
    integer: _ => /[0-9]+/,
    float: _ => /([0-9]+)?\.[0-9]+/,
    string: _ => seq('"', optional(/[^\"]+/), '"'),
    boolean: _ => choice('true', 'false'),
    unit: _ => '()',
    hole: _ => '???',
    placeholder: _ => '_',
    line_comment: _ => token(prec(2, seq('//', /[^\n]*/))),
  },
});

function commaSep1(rule) {
  return seq(rule, repeat(seq(',', rule)));
}
