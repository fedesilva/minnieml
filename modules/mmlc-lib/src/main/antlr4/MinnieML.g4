grammar MinnieML;

// Parser Rules ----------------------------------------------------------------------

// Script Rules ----------------------------------------------------------------------

//
// `Script` represents a scripting session's syntax.
//  Not used by normal compiler, scripts are a bit more lenient.
// Syntactically you can write an expression that does not bind it's result, a statement.
// The effects system is far more lenient, too.
//
script: (open)* (use)* ( stat | member )* EOF;

// A statement ignores results if any and evaluates effects immediately
// Scripts dont enforce effectful segregation
stat: exp;

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
  (doc)? (modVisibility)? (Module moduleId Def)? (open)* (moduleExports)? (member)+ (EOF);

nestedModule:
  (doc)? (modVisibility)? Module moduleId Def (moduleExports)? (member)+ End;

exportSelection:
  (id | tpId | moduleId | selection);

exportReSpec:
  (idMWT | tpId | moduleId);

exportedMember:
  (doc)? ( exportReSpec '=' )? (exportSelection);

moduleExports:
  (doc)? Exports Def ( exportedMember )+ End;

member: ( decl | comm |  use  );

open: Open moduleId (openUses)? ;
openUses: (use)+ End;

use: Use (selection) (',' selection)*;


decl:
  letBnd        |
  fn            |
  fnM           |
  op            |
  nestedModule  |
  enumDef       |
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
      tpCons    |
      tpId      |
      opId      |
      moduleId  |
      tuple     |
      selection |
      cond      |
      dtCons    |
      tpCons    |
      hole
    )+
;

selection: ( id | moduleId )  (Dot (id | moduleId) )* (Dot '_')? ;

hole: Hole;

//
// Protocols ----------------------------------------------------
//

protocol: Protocol (canon)? tpId typeArgs ( Implies tpSpec (',' tpSpec)* )? Def (protocolMember)+ End;
protocolMember:  (id | binOpId | prefixOpId | postfixOpId) (typeArgs)* TpAsc ( tpSpec );

// When an instance is defined as canonical,
// it's the only instance possible within the module
// it is defined on and it's children.
canon: Canon;

instance: (canon)? Instance tpId tpSpec Def (instanceMember)+ End;
instanceMember:  ( letBnd | fn | fnM | op | nestedModule | enumDef | tpDef )+;
//
// Pattern matching ----------------------------------------------------
//

matchBody: matchCase ( matchCase )*;
matchCase: '|' matchBnd? patt ( If exp )? TArrow fnExp ;
matchBnd: (id '@');


patt: lit           |
      idOrMeh       |
      tpSpec        |
      tupleDecon    |
      nominalDecon  |
      tpDecon       |
      structDecon   |
      seqDeconT     |
      seqDeconLit   ;

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

letBnd: (doc)? Let (lazy|cnst)? (rec)? (doc)? bnd (',' (doc)? bnd)*  ;

// ----------------------------------------------------------------------
// Functions

fnLet:  (doc)? Let bnd (',' bnd)* In fnExp (Where fnLetWhereFn (',' fnLetWhereFn)*  )? ;
fnLetWhereFn: (rec)? id fnSig Def fnExp;

fnExp: exp | fnLet;

fnSig: (typeArgs)? formalArgs (returnTp)?;

formalArgs: idMWT* | '(' idMWT* ')';

returnTp: TpAsc tpSpec;

fn:    (doc)? Fn (rec)? id fnSig Def fnExp ;
fnM:   (doc)? Fn (rec)? id ( (typeArgs)? | Lpar (typeArgs)? Rpar )  (returnTp)? Match matchBody ;

fnLit:      fnSig TArrow fnExp;
fnMatchLit: Meh Match matchBody;

// Operators ----------------------------------------------------------------------

op: binOp | prefixOp | postfixOp;

binOpId: opId (opPrecedence)?;
prefixOpId: opId Dot (opPrecedence)?;
postfixOpId: Dot opId (opPrecedence)?;

binOp:       (doc)? Op binOpId      (typeArgs)? idMWT idMWT (returnTp)? Def fnExp;
prefixOp:    (doc)? Op prefixOpId   (typeArgs)? idMWT (returnTp)?   Def fnExp;
postfixOp:   (doc)? Op postfixOpId  (typeArgs)? idMWT (returnTp)?   Def fnExp;

opPrecedence:  LitPrec;

// Conditional expression ----------------------------------------------------------------------

cond: If cndThenExp (Else If cndThenExp)? cndElse;

cndThenExp: exp Then fnExp;
cndElse: Else fnExp;

// TYPES ---------------------------------------------------------------------------------------

// Type def
tpDef: (doc)? Type tpId (typeArgs)? Def tpSpec (tpRefinement)?  ;

// General type declaration related rules

typeArgs: tpArgId typeArgs | tpArgId | LPar typeArgs Rpar;

tpArgId: TpArgId | tpArgId TpAsc tpSpec;

tpRefinement:  LCurly  (id) ? '|' exp RCurly;

tpSpec:
    Lpar (tpSpec)+ Rpar                           #groupSpec            |
    left = tpSpec ( tpSpec )+                     #tpAppSpec            |
    tpId                                          #nameSpec             |
    tpArgId                                       #argSpec              |
    left = tpSpec ( '&' tpSpec )+                 #intersectSpec        | // Intersection types
    left = tpSpec ( '|' tpSpec )+                 #unionSpec            | // Union types
    left = tpSpec (TArrow left = tpSpec)+         #fnSpec               |
    LCurly dtField (dtField)* RCurly              #structSpec           |
    tpSpec ( ',' tpSpec )+                        #tupleSpec            |
    tpRefinement                                  #refinementSpec       |
    unit                                          #tpUnit               |
    litEmptySeq                                   #tpSeq                ;


unit: LitUnit;

expSeq: (exp)+;
//tpCons: tpId expSeq | tpId | tpSpec;
tpCons: tpId expSeq ;
tpDecon: tpId (idOrMeh)+;

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


// Variants (discriminated unions)  -------------------------------------------------------------------
// enum  -------------------------------------------------------------------

enumDef: (doc)? Enum tpId (typeArgs)? Def enumMbr ( enumMbr )+ ;

enumMbr:  (doc)?  '|'  tpId ( TpAsc tpSpec)?;



//
// Literals ----------------------------------------------------------------------
//

lit: litStr | litInt | litLong | litFloat | litDouble | litUnit | litBoolean |  litEmptySeq | litSeq ;

litStr: LitStr;
litInt:  LitInt;
litLong: LitLong;

litFloat:  LitFloat;
litDouble: LitDouble;

litBoolean: (litTrue | litFalse);
litTrue: LitTrue;
litFalse: LitFalse;

litUnit: LitUnit;

litSeq: LBrac (exp)* RBrac;
litEmptySeq: LBrac RBrac;
seqDeconLit: LBrac (id)* RBrac;
seqDeconT: LBrac (id '::' id) RBrac;

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
Open:       'open';
Use:        'use';
Enum:       'enum';
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
Implies:    '<:';
Hole:       '???';
Meh:        '_';
Compose:    '<|';
AndThen:    '|>';
Protocol:   'protocol';
Instance:   'instance';
Canon:      'canon';
//SeqCons:    '::';


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

