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
  (doc)? (visibility)? (Module moduleId Eq)? (moduleExports)? (member)+ EOF ;

nestedModule:
  (doc)? (visibility)? Module moduleId Eq (moduleExports)? (member)+ End;

exportSelection: (id | tpId | moduleId | selection);
exportReSpec: (idMWT | tpId | moduleId);

moduleExports:
  (doc)? Exports Eq ( (doc)? (exportSelection)+ ( '=' exportReSpec  )? )+ End;

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
  flatExp                     #flatExpL    |
  left = exp Match matchBody #matchExpL  ;

flatExp:
    (
      lit       |
      id        |
      opId      |
      tpId      |
      moduleId  |
      fnLit     |
      tuple     |
      group     |
      selection |
      cond      |
      dtCons    |
      hole
    )+
;

hole: Hole;

selection: ( id | moduleId ) Dot (id | moduleId) (Dot (id | moduleId) )*;

//
// Pattern matching ----------------------------------------------------
//

matchBody: matchCase ( '|' matchCase )*;
matchCase: (id '@')? patt ( If exp )? Eq fnExp ;

patt: lit         |
      idMWT       |
      tpId        |
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

bnd:  idMWT         Eq exp  |
      tupleDecon    Eq exp  |
      dtDecon       Eq exp  |
      structDecon   Eq exp  ;

letBnd: (doc)? Let (lazy|cnst)? (rec)? (doc)? bnd (',' (doc)? bnd)* End ;

// ----------------------------------------------------------------------
// Functions

fnLet:  (doc)? Let bnd (',' bnd)* In fnExp;

fnExp: exp | fnLet;

fnSig: (typeArgs)? formalArgs (returnTp)?;

formalArgs: idMWT* | '(' idMWT* ')';

returnTp: TpAsc tpSpec;

fn:    (doc)? Fn (rec)? id fnSig Eq fnExp End ;
fnM:   (doc)? Fn (rec)? id (returnTp)? Match matchBody End ;

fnLit: fnSig TArrow fnExp;

// Operators ----------------------------------------------------------------------

op: binOp | prefixOp | postfixOp;

binOp:        (doc)? Op (opPrecedence)? opId (typeArgs)? idMWT idMWT (returnTp)?    Eq fnExp End;
prefixOp:     (doc)? Op (opPrecedence)? opId Dot (typeArgs)? idMWT (returnTp)?      Eq fnExp End;
postfixOp:    (doc)? Op (opPrecedence)?  Dot opId (typeArgs)? idMWT (returnTp)?      Eq fnExp End;

opPrecedence: LitPrec;

// Conditional expression ----------------------------------------------------------------------

cond: If cndThenExp (Else If cndThenExp)? cndElse;

cndThenExp: exp Then fnExp;
cndElse: Else fnExp;

// TYPES ---------------------------------------------------------------------------------------

// Type alias

tpAlias: Type tpId (typeArgs)? Eq tpSpec End;

// General type declaration related rules

typeArgs: tpArgId typeArgs | tpArgId;

tpArgId: TpArgId | tpArgId TpAsc tpSpec;

tpSpec:
    Lpar (tpSpec)+ Rpar                           #grpSpec              |
    tpId                                          #nameSpec             |
    tpArgId                                       #argSpec              |
    left = tpSpec ( '&' tpSpec )+                 #intersectSpec        |
    left = tpSpec ( '|' tpSpec )+                 #unionSpec            |
    left = tpSpec (TArrow left = tpSpec)+         #fnSpec               |
    LCurly (dtField)+ RCurly                      #structSpec           |
    Lpar tpSpec (',' tpSpec)+  Rpar               #tupleSpec            |
    left = tpSpec ( tpSpec )+                     #tpAppSpec            ;


// -----------------------------------------------------------------------------
// Product types ---------------------------------------------------------------
// -----------------------------------------------------------------------------

// Data types ----------------------------------------------------------------------

dtField: (doc)? idMWT;

dt: (doc)? Data tpId (typeArgs)? LCurly (dtField)+ RCurly (End)?;

dtNamedAssign: Id Eq exp;

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

unionMbr: tpId (tpSpec)?;

unionV: (doc)? Union tpId (typeArgs)? Eq unionMbr  ( '|' unionMbr )+ End;

// Enum -------------------------------------------------------------------

enumV:  (doc)? Enum tpId Eq tpId ( '|' tpId)+ End;


//
// Literals ----------------------------------------------------------------------
//

lit: litStr | litInt | litLong | litFloat | litDouble | litUnit | litBoolean;

litStr: LitStr;

litInt:  LitPrec | LitInt;
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

id:       Meh | Id;
idMWT:    id (TpAsc tpSpec)?;
idOrMeh:  idMWT | Meh;
moduleId: FirstUpId;
tpId:     FirstUpId ;
opId:     OpId;

// Lexer Rules ----------------------------------------------------------------------

// Literals
LitStr:   '"' .*? '"';
LitPrec: [0-5];
LitInt:   [0-9]+;
LitLong:  [0-9]+'L';
LitFloat: [0-9]*'.'[0-9]+;
LitDouble: [0-9]*'.'[0-9]+'D';
LitTrue:  'true';
LitFalse: 'false';
LitUnit : '()';

// Keywords
Type :      'type';
TpAsc:      ':';
Let :       'let';
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
TArrow:     '->';
Op:         'op';
End :       ';';
Eq :        '=';
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
UnsafeP:    '!>';
UnsafePaT:  '<!>';
Rescue:     '!~>';
Compose:    '<|';
AndThen:    '|>';
OrElse:     '~>';


// Identifiers

Id : FirstLowId;

// Singleton type parameter (a value lifted to the type system)
TpValArgId : [']FirstLowId;
// Literal Singleton type
// A type parameter
TpArgId : [']FirstUpId;

FirstLowId: [a-z][A-Za-z0-9_]*;
FirstUpId : [A-Z][A-Za-z0-9]* ;

OpId : ( '/' | '*' | '+' | '-' | '>' | '<' | '=' | ':' | '|' | '%' | '\\' | '^' | '!' | '~' | '?'  )+;

// Whitespace
Newline : ('\r\n' | '\n')   -> channel(1);
WS      : [\t ]+            -> channel(1);

// Comments
Doc     : '(**' (Doc|MLComm|.)*? '*)';
MLComm  : '(*' (MLComm|Doc|.)*? '*)'        -> channel(2);
HComm   : '#' .*? ('\r\n' | '\n' | EOF)     -> channel(2);
