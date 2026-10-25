# What to do if we get cybersecurity blocks

* As you work and get this blockers, collect them, so we can report per the site:

https://help.openai.com/en/articles/20001326-additional-safety-checks-for-biological-and-cybersecurity-requests-in-chatgpt-codex-and-the-api#what-can-i-do-if-content-cant-be-shown

* Create a separate report per task under the repository-root `./tmp/` directory, not `/tmp`.
* Keep reports and their supporting packets, diagnostics, and session metadata local. Do not
  stage or commit them; `./tmp/` is ignored by Git.
* Keep block-report content out of tracked task records. Record compiler findings and
  verification there without depending on the local reports.
* you need to collect what was the packet sent to the agent so we can report.
* include the session id if available

# What if I think the check or block is a mistake?

When available, use the thumbs-down control to share feedback about an individual response. This feedback helps improve our systems.

If a clearly benign or authorized request is repeatedly blocked, contact OpenAI Support and include:

* The exact safety message you received.
* The model and whether you were using the OpenAI API, Codex, or ChatGPT.
* The date, time, and time zone.
* The request ID, if available.
* A brief, redacted description of the task.
* Your workspace or organization name or ID, if applicable.

Do not include passwords, authentication codes, proprietary data, or other sensitive information.
