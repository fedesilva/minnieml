---
name: document-style-review
description: Review documentation for plain language, readability, concision, and accurate claims. Use when asked to review document style, remove pompous or promotional rhetoric, or simplify technical prose without losing precision. Rewrite only when requested; this is not a code correctness or task-tracking audit.
---

# Document Style Review

Help the reader understand the subject with less effort. Keep the writing plain
spoken, concise, accurate, and unpretentious. Preserve technical depth where the
reader needs it.

## Scope and working mode

Read the requested document and enough surrounding context to understand its
audience, purpose, and status. Follow applicable repository instructions. Load
related sources only when needed to interpret or check a claim.

A review or request to identify problems is read-only. Report passages and useful
correction directions. When asked to edit or rewrite, make the authorized changes
and check the result. Do not turn an editorial request into a redesign, a code
review, or an audit of unrelated documents.

Work directly in the current agent; this editorial skill does not require an
independent reviewer or per-finding subagents.

## Lean and readable

Judge simplicity by the reader's effort, not by line count. An experienced, tired
reader should be able to follow the explanation without decoding it.

- Say things simply. No jargon: do not string together abstract or technical
  words that leave the reader guessing what happens or why.
- Prefer plain terms to technical terms whenever they express the same meaning.
  Use concrete nouns and direct verbs.
- If a technical term is necessary, define it in plain language when introducing
  it. Name the actual thing or action it refers to; do not explain jargon with
  more jargon. A short parenthetical definition is often enough.
- Give each paragraph one coherent thought. Use blank lines to separate thoughts,
  and place an explanation close to the claim or example it supports.
- State a rule once in the place where the reader needs it. Consolidate repeated
  rationales and summaries that add no information. Keep repetition when it helps
  the document serve as a reference.
- Keep useful examples, conditions, exceptions, and causal explanations. Removing
  them merely to shorten the text can make it harder to understand.
- Use tables or lists for actual comparisons or parallel rules. Avoid fragmenting
  connected reasoning into a succession of headings and slogans.

## Modest, precise claims

Do not assert a benefit or necessity and then substitute confident rhetoric for
an explanation. For claims about safety, simplicity, cost, or required design,
look for the condition, mechanism, example, or evidence that supports them.

- Distinguish implemented behavior, proposed behavior, goals, and open questions.
  Do not promote a proposal to a fact while smoothing its prose.
- Examine words such as "must", "only", "always", "nothing", and "no cost".
  Keep them when the scope and evidence justify them; otherwise narrow the claim
  or identify what needs checking.
- Replace praise with a supported explanation, or delete it if it adds no
  information. Calling a system "honest" does not explain what it does.
- Remove metaphors that hide the subject: "complexity tax", "phantom rung", or
  "load-bearing" when the text should name a cost, category, or requirement.
  A metaphor can stay when it actually makes the explanation easier to follow.
- Avoid rhetorical contrasts, declarations of superiority, and hypothetical
  arguments that establish no useful consequence. Do not belittle alternatives
  to make a preferred design sound inevitable.
- Be humble through accurate limits, not constant hedging or self-deprecation.
  State established facts directly. State uncertainty where it exists.

An unsupported claim is not automatically false. Check a directly relevant source
when practical, or report the gap. Do not invent evidence, a use case, a benchmark,
or a design decision to make a replacement sentence sound complete.

## Examples

**Obscure and pompous**

> Keeping them split was paying complexity tax for a phantom rung.

If "them" is clear from context and the intended claim is supported:

> We haven't identified a use case for them.

"Paying complexity tax" and "phantom rung" obscure a simple claim. State it
directly without adding technical detail that the surrounding text already gives.

**Promotional reassurance**

> The model stays small and the compiler stays honest.

Delete this sentence if it only praises the design. "Small" needs a defined scope;
"honest" attributes a virtue without explaining any behavior. There is no faithful
replacement until the surrounding text supplies an actual claim worth making.

**A broad claim contradicted by its own exceptions**

> Every setting can be changed without restarting the application.

If network settings are the documented exception:

> Most settings take effect immediately. Network changes require a restart.

State the exception instead of making a broader claim than the behavior supports.

**Unsupported necessity**

> A separate service is the only way to make this reliable.

When the implementation choice is unresolved:

> A separate service is one option. We still need to compare its failure handling
> with the alternatives.

Do not present an option as a necessity before establishing why it is required.

**Empty counterfactual**

> Removing this layer would mean dismantling the entire architecture.

Delete the passage if the document needs only to explain the layer's behavior.
If an actual dependency matters, name it and explain why it exists. Do not replace
filler with another concluding slogan.

## Code comments

Explain the decision or contract the reader cannot recover from the declaration
alone. Naming the fields in prose does not explain why the abstraction exists.

- Identify what the declaration actually represents. A record of analysis results
  is not the function being analyzed. State which decision consumes those results.
- Explain why a helper or result type is needed when its role is not obvious.
  Name the constraint that motivates it, rather than calling it a "helper".
- Apply the plain-language rules to comments too. Prefer "the LLVM function to
  call"; if the term "target" matters here, write "a known target (the LLVM
  function to call)" when introducing it.
- Attribute assumptions to the code that establishes them. If an analyzer only
  creates records for non-capturing lambdas, say so; the record's fields alone do
  not enforce that restriction.
- Include implementation details when they explain a contract or a consequence
  for callers. Avoid narrating statements, teaching concepts the reader already
  knows, or inventing a rationale that the surrounding code does not support.

For example, compiling an expression normally produces a runtime value. When a
local function has no captures and is only called directly, the compiler can
record which LLVM function the local name refers to. It does not need to build
a closure value. The result of compiling a binding must support both cases.

**Restates the declaration**

```scala
/** The result of compiling a local binding, including its state and scope entry. */
case class LocalBindingResult(
  state: CodeGenState,
  entry: ScopeEntry,
  exitBlock: Option[String]
)
```

The comment names the contents but leaves the reader asking why ordinary
expression results cannot serve the same purpose.

**Explains why the type exists**

```scala
/** Compiling a let binding does not always produce a runtime value.
  * For a non-capturing function used only in direct calls, the compiler records
  * a known target (the LLVM function to call) for the local name. This result
  * can carry that information without requiring an unused closure value.
  */
case class LocalBindingResult(
  state: CodeGenState,
  entry: ScopeEntry,
  exitBlock: Option[String]
)
```

The replacement explains what the compiler needs to remember and why it does
not need a closure value in this case. Apply that reasoning where needed; do
not expand every comment into a design essay.

## Review and delivery

For a review, identify the passage with a location and a short quotation, explain
the reader's difficulty or unsupported inference, and suggest a concise correction
or deletion. Group repeated instances when one explanation covers them. Separate
wording problems from technical questions that need the author's decision.

For a rewrite, preserve the author's intent and technical constraints. Retain
unresolved notes or turn them into clear open questions; do not silently settle
them. Read the result as a whole for flow, repeated explanations, contradictions,
and accidental changes in meaning. Check affected links and the diff for file
edits. Report meaningful changes and any remaining questions briefly.

Do not claim that this style review proves technical correctness. Do not force
changes where the original wording is already clear and supported.
