grammar MinnieML;

// Parser Rules ----------------------------------------------------------------------

// Script ----------------------------------------------------------------------

//
// `Script` represents a scripting session's syntax. Not used by normal compiler.
// Scripts are a bit more lenient.
//
script: ( stat | member )* EOF;

stat: exp End;

// ----------------------------------------------------------------------
// Normal compiler rules follow
// ----------------------------------------------------------------------

// Modules --------------------------------------------------------------

visibility: pub | protd | lexical;

pub:     Pub;
protd:   Protd;
lexical: Lexical;

module:
  (doc)? (visibility)? (Module moduleId Def)? (moduleExports)? (member)+ EOF ;

nestedModule:
  (doc)? (visibility)? Module moduleId Def (moduleExports)? (member)+ End;

exportSelection: 
  (id | tpId | moduleId | selection);

exportReSpec: 
  (idMWT | tpId | moduleId);

exportedMember: 
  (doc)? (exportSelection) ( '=' exportReSpec  )?;

moduleExports:
  (doc)? Exports Def ( exportedMember )+ End;

member: ( decl | comm );

decl:
  letBnd        |
  fn            |
  fnM           |
  op            |
  nestedModule  |
  dt            |
  variant       |
  tpAlias       ;

// ----------------------------------------------------------------------
// Expressions
//
// Application, Operators, precedence, associativity, type construction, etc,
// are all recognized post parsing
//

group: Lpar ( exp )+ Rpar;

exp:
  fnMatchLit                    #fnMatchLitL  |
  flatExp                       #flatExpL     |
  left = exp Match matchBody    #matchExpL    ;

flatExp:
    (
      group     |
      fnLit     |
      lit       |
      id        |
      tpId      |
      opId      |
      moduleId  |
      tuple     |
      selection |
      cond      |
      dtCons    |
      hole
    )+
;

selection: ( id | moduleId ) Dot (id | moduleId) (Dot (id | moduleId) )*;

hole: Hole;

//
// Pattern matching ----------------------------------------------------
//

matchBody: matchCase ( '|' matchCase )*;
matchCase: matchBnd? patt ( If exp )? Def fnExp ;
matchBnd: (id '@');


patt: lit         |
      idOrMeh     |
      tpSpec      |
      tupleDecon  |
      dtDecon     |
      structDecon ;

// ----------------------------------------------------------------------
// Function and/or binding modifiers

rec:  Rec;
lazy: Lazy;
cnst: Const;

// ----------------------------------------------------------------------
// Bindings

bnd:  idMWT         Def exp  |
      tupleDecon    Def exp  |
      dtDecon       Def exp  |
      structDecon   Def exp  ;

letBnd: (doc)? Let (lazy|cnst)? (rec)? (doc)? bnd (',' (doc)? bnd)* End ;

// ----------------------------------------------------------------------
// Functions

fnLet:  (doc)? Let bnd (',' bnd)* In fnExp (Where fnLetWhereFn (',' fnLetWhereFn)*  )? ;
fnLetWhereFn: (rec)? id fnSig Def fnExp;

fnExp: exp | fnLet;
 
fnSig: (typeArgs)? formalArgs (returnTp)?;

formalArgs: idMWT* | '(' idMWT* ')';

returnTp: TpAsc tpSpec;

fn:    (doc)? Fn (rec)? id fnSig Def fnExp End ;
fnM:   (doc)? Fn (rec)? id (returnTp)? Match matchBody End ;

fnLit:      fnSig TArrow fnExp;
fnMatchLit: Meh Match matchBody;

// Operators ----------------------------------------------------------------------

op: binOp | prefixOp | postfixOp;

binOp:       (doc)? Op opId (opPrecedence)? (typeArgs)? idMWT idMWT (returnTp)? Def fnExp End;
prefixOp:    (doc)? Op opId Dot (opPrecedence)? (typeArgs)? idMWT (returnTp)?   Def fnExp End;
postfixOp:   (doc)? Op Dot opId (opPrecedence)? (typeArgs)? idMWT (returnTp)?   Def fnExp End;

opPrecedence:  LitPrec;

// Conditional expression ----------------------------------------------------------------------

cond: If cndThenExp (Else If cndThenExp)? cndElse;

cndThenExp: exp Then fnExp;
cndElse: Else fnExp;

// TYPES ---------------------------------------------------------------------------------------

// Type alias
tpAlias: Type tpId (typeArgs)? Def tpSpec End;

// General type declaration related rules

typeArgs: tpArgId typeArgs | tpArgId;

tpArgId: TpArgId | tpArgId TpAsc tpSpec;

tpSpec:
    Lpar (tpSpec)+ Rpar                           #groupSpec            |
    tpId                                          #nameSpec             |
    tpArgId                                       #argSpec              |
    left = tpSpec ( '&' tpSpec )+                 #intersectSpec        |
    left = tpSpec ( '|' tpSpec )+                 #unionSpec            |
    left = tpSpec (TArrow left = tpSpec)+         #fnSpec               |
    LCurly dtField (dtField)* RCurly              #structSpec           |
    tpSpec (',' tpSpec)+                          #tupleSpec            |
    left = tpSpec ( tpSpec )+                     #tpAppSpec            ;


// -----------------------------------------------------------------------------
// Product types ---------------------------------------------------------------
// -----------------------------------------------------------------------------

// Data types ----------------------------------------------------------------------

dtField: (doc)? idMWT;

dt: (doc)? Data tpId (typeArgs)? LCurly dtField (dtField)* RCurly (End)?;

dtNamedAssign: Id Def exp;

dtCons: tpId ( dtNamedAssign | exp )+;

dtDecon: tpId LCurly (idMWT)+ RCurly;

structDecon: LCurly idMWT RCurly;

// Tuples ----------------------------------------------------------------------

tuple: Lpar exp (',' exp)+  Rpar;

tupleDecon: Lpar idOrMeh ',' idOrMeh ( ',' idOrMeh )*  Rpar;

// -----------------------------------------------------------------------------
// Sum types -------------------------------------------------------------------
// -----------------------------------------------------------------------------


// Variants  -------------------------------------------------------------------

variant: enumV | unionV;

// Union  -------------------------------------------------------------------

unionV: (doc)? Union tpId (typeArgs)? Def unionMbr ( '|'  unionMbr )+ End;

unionMbr:  (doc)? tpId ( TpAsc tpSpec)?;


// Enum -------------------------------------------------------------------

enumV:  (doc)? Enum tpId Def enumMbr ( '|' enumMbr)+ End;

enumMbr:  (doc)? tpId;


//
// Literals ----------------------------------------------------------------------
//

lit: litStr | litInt | litLong | litFloat | litDouble | litUnit | litBoolean;

litStr: LitStr;
litInt:  LitInt;
litLong: LitLong;

litFloat:  LitFloat;
litDouble: LitDouble;

litBoolean: (litTrue | litFalse);
litTrue: LitTrue;
litFalse: LitFalse;

litUnit: LitUnit;

// Comments  ----------------------------------------------------------------------

doc: Doc;

comm: (hComm | mlComm) ;

hComm: HComm;

mlComm: MLComm;

// Ids ----------------------------------------------------------------------

id:       Id;
idMWT:    id (TpAsc tpSpec)?;
idOrMeh:  Meh | idMWT;
moduleId: FirstUpId;
tpId:     FirstUpId;
opId:     SymId | Id;

// Lexer Rules ----------------------------------------------------------------------

// Literals
LitStr:   '"' .*? '"';
LitInt:   [0-9]+;
LitFloat: [0-9]*'.'[0-9]+;
LitLong:  [0-9]+'L';
LitDouble: [0-9]*'.'[0-9]+'D';
LitTrue:  'true';
LitFalse: 'false';
LitUnit : '()';
LitPrec: '[' [1-10] ']';

// Keywords
Type :      'type';
TpAsc:      ':';
Let :       'let';
Where:      'where';
Rec:        'rec';
Const:      'const';
Fn :        'fn';
In:         'in';
If:         'if';
Then:       'then';
Else:       'else';
Module:     'module';
Pub:        'pub';
Protd:      'protected';
Lexical:    'lexical';
Exports:    'exports';
Use:        'use';
Enum:       'enum';
Union:      'union';
Data:       'data';
Match:      'match';
Lazy:       'lazy';
Or:         'or';
And:        'and';
TArrow:     '->';
Op:         'op';
End :       ';';
Def :       '=';
Dot:        '.';
Lpar :      '(';
Rpar :      ')';
LCurly:     '{';
RCurly:     '}';
LBrac:      '[';
RBrac:      ']';
Derives:    '<:';
Hole:       '???';
Meh:        '_';
Compose:    '<|';
AndThen:    '|>';

// Identifiers

// a value binding id starts with a lowercase letter
Id : FirstLowId;

// A type parameter starts with a ' and an upper case letter
TpArgId : [']FirstUpId;

FirstLowId: [a-z][A-Za-z0-9_]*;
FirstUpId : [A-Z][A-Za-z0-9]* ;

SymId : ( '/' | '*' | '+' | '-' | '>' | '<' | '=' | ':' | '|' | '%' | '\\' | '^' | '!' | '~' | '?'  )+;

// Whitespace
Newline : ('\r\n' | '\n')   -> channel(1);
WS      : [\t ]+            -> channel(1);

// Comments
Doc     : '(**' (Doc|MLComm|.)*? '*)';
MLComm  : '(*' (MLComm|Doc|.)*? '*)'        -> channel(2);
HComm   : '#' .*? ('\r\n' | '\n' | EOF)     -> channel(2);
