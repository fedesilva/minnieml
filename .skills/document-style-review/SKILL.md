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

- Use familiar words and direct verbs. Keep necessary technical terms; explain
  unfamiliar ones when the audience needs that help. Do not replace a precise
  term with a vague everyday approximation.
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
