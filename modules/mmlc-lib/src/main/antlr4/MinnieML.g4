grammar MinnieML;

// Parser Rules ----------------------------------------------------------------------

// Script Rules ----------------------------------------------------------------------

//
// `Script` represents a scripting session's syntax. 
//  Not used by normal compiler, scripts are a bit more lenient.
// Syntactically you can write an expression that does not bind it's result, a statement.
// The effects system is far more lenient, too.
//
script: ( stat | member )* EOF;

// A statement ignores results if any and evaluates effects immediately
// Scripts dont enforce effectful segregation
stat: exp(End)?;

// ----------------------------------------------------------------------
// Full language rules follow
// ----------------------------------------------------------------------

// Modules --------------------------------------------------------------

modVisibility: pub | protd | lexical;

// Public modules are accessible from everywhere
// Default for top level modules unless specified otherwise
pub: Pub;

// Protected modules are only accesible from siblings and parent
// default if not specified otherwise
protd: Protd;

// Lexically visible modules are accesible by children of the declaring module.
lexical: Lexical;

module:
  (doc)? (modVisibility)? (Module moduleId Def)? (moduleExports)? (member)+ EOF ;

nestedModule:
  (doc)? (modVisibility)? Module moduleId Def (moduleExports)? (member)+(End)?;

exportSelection: 
  (id | tpId | moduleId | selection);

exportReSpec: 
  (idMWT | tpId | moduleId);

exportedMember: 
  (doc)? ( exportReSpec '=' )? (exportSelection);

moduleExports:
  (doc)? Exports Def ( exportedMember )+(End)?;

member: ( decl | comm );

decl:
  letBnd        |
  fn            |
  fnM           |
  op            |
  nestedModule  |
  variant       |
  tpDef         |
  protocol      |
  instance      ;

// ----------------------------------------------------------------------
// Expressions
//
// Application, Operators, precedence, associativity, type construction, etc,
// are all recognized post parsing
//

group: Lpar ( exp )+ Rpar;

exp:
  flatExp                       #flatExpL       |
  fnMatchLit                    #fnMatchLitL    |
  left = exp Match matchBody    #matchExpL      ;

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
// Protocols ----------------------------------------------------
//

protocol: Protocol tpId typeArgs LCurly protocolBody RCurly;
protocolBody: ( id '=' fnSig )+;


// When an instance is defined as canonical,
// it's the only instance possible within the module
// it is defined on and it's children.
canon: Canon;

instance:  (canon)? Instance tpId tpSpec LCurly instanceBody RCurly;
instanceBody: ( id '=' fnSig Def fnExp )+;

//
// Pattern matching ----------------------------------------------------
//

matchBody: matchCase ( matchCase )*;
matchCase: '|' matchBnd? patt ( If exp )? Def fnExp ;
matchBnd: (id '@');


patt: lit           |
      idOrMeh       |
      tpSpec        |
      tupleDecon    |
      nominalDecon  |
      tpId id       |
      structDecon   ;

// ----------------------------------------------------------------------
// Function and/or binding modifiers

rec:  Rec;
lazy: Lazy;
cnst: Const;

// ----------------------------------------------------------------------
// Bindings

bnd:  idMWT         Def exp  |
      tupleDecon    Def exp  |
      nominalDecon  Def exp  |
      structDecon   Def exp  ;

letBnd: (doc)? Let (lazy|cnst)? (rec)? (doc)? bnd (',' (doc)? bnd)* (End)? ;

// ----------------------------------------------------------------------
// Functions

fnLet:  (doc)? Let bnd (',' bnd)* In fnExp (Where fnLetWhereFn (',' fnLetWhereFn)*  )? ;
fnLetWhereFn: (rec)? id fnSig Def fnExp;

fnExp: exp | fnLet;
 
fnSig: (typeArgs)? formalArgs (returnTp)?;

formalArgs: idMWT* | '(' idMWT* ')';

returnTp: TpAsc tpSpec;

fn:    (doc)? Fn (rec)? id fnSig Def fnExp (End)?;
fnM:   (doc)? Fn (rec)? id ( (typeArgs)? | Lpar (typeArgs)? Rpar )  (returnTp)? Match matchBody End ;

fnLit:      fnSig TArrow fnExp;
fnMatchLit: Meh Match matchBody;

// Operators ----------------------------------------------------------------------

op: binOp | prefixOp | postfixOp;

binOp:       (doc)? Op opId (opPrecedence)? (typeArgs)? idMWT idMWT (returnTp)? Def fnExp(End)?;
prefixOp:    (doc)? Op opId Dot (opPrecedence)? (typeArgs)? idMWT (returnTp)?   Def fnExp(End)?;
postfixOp:   (doc)? Op Dot opId (opPrecedence)? (typeArgs)? idMWT (returnTp)?   Def fnExp(End)?;

opPrecedence:  LitPrec;

// Conditional expression ----------------------------------------------------------------------

cond: If cndThenExp (Else If cndThenExp)? cndElse;

cndThenExp: exp Then fnExp;
cndElse: Else fnExp;

// TYPES ---------------------------------------------------------------------------------------

// Type def
tpDef: (doc)? Type tpId (typeArgs)? Def tpSpec(End)?;

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

dtField: (doc)? idWT;



dtNamedAssign: Id Def exp;

dtCons: tpId ( dtNamedAssign | exp )+;

nominalDecon: tpId LCurly (idMWT)+ RCurly;

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

unionV: (doc)? Union tpId (typeArgs)? Def unionMbr ( unionMbr )+ (End)?;

unionMbr:  (doc)?  '|'  tpId ( TpAsc tpSpec)?;


// Enum -------------------------------------------------------------------

enumV:  (doc)? Enum tpId Def enumMbr ( enumMbr)+ (End)?;

enumMbr:  (doc)? '|' tpId;


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
idWT:     id TpAsc tpSpec;
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
Protocol:   'protocol';
Instance:   'instance';
Canon:      'canonical';

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
// Doc comment
Doc     : '(**' (Doc|MLComm|.)*? '*)';
// Multiline comment
MLComm  : '(*' (MLComm|Doc|.)*? '*)'        -> channel(2);
// Line comment
HComm   : '#' .*? ('\r\n' | '\n' | EOF)     -> channel(2);

