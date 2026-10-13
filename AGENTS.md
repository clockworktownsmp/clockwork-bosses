# Clockwork Bosses working instructions

## Maintainability

- The owner must be able to understand and maintain the project. Explain each
  system's purpose, ownership, execution flow, interruption cleanup and limits
  in plain English. Compilation alone is not evidence of runtime correctness.
- The addon API is experimental revision 1. Do not describe it as stable or
  promise compatibility until a policy and runtime evidence support that claim.
- During an API walkthrough, explain existing code without adding new mechanics.

## Website ownership

- The live workshop is `https://workshop.clockworkboss.com`, hosted by a separate
  Cloudflare Worker/relay project. The companion no longer embeds a website or
  listens as a local web server. Keep frontend files out of the core mod jar.
- The core owns shared setting definitions/schema and native editor/rendering.
  Website changes also require updating the separate authoring UI and preparing
  its allowlisted public assets through the Cloudflare project's workflow.
  Editing the localhost preview alone does not confirm a published update.
- Read the current **Clockwork Bosses web companion** chat and project README
  before changing hosting/relay behavior. Preserve its authentication, routes and
  server-owned files. Keep credentials out of output, source and documentation.

## Wiki upkeep

- Update the separate `clockwork-bosses.wiki` checkout as part of relevant future
  project changes. Resolve the owner's current checkout location before writing;
  do not assume that a Downloads ZIP or an old draft is the current wiki.
- The ignored `.codex/wiki-location.local.json`, when present, records the local
  checkout path. Verify its directory and Git remote before using it. Keep this
  machine-specific locator untracked; never copy it into public wiki pages.
- Keep operator guides, configuration examples, API explanations, addon examples,
  troubleshooting, navigation and compatibility notes aligned with actual source.
- Explain what changed, why it works that way, and who owns cleanup. Addon guides
  should be understandable to someone who has not written an API before.
- Label experimental/source-only behavior separately from released features.
  Historical release test results do not verify later source changes. State
  compilation, packaging, runtime testing and deployment status accurately.
- Preserve existing owner edits and unrelated pages. Use GitHub Wiki page links
  and update Home and `_Sidebar.md` when introducing a new page.
- Keep private machine paths, credentials, server data and internal review notes
  out of public wiki pages. Use repository-relative source paths in explanations.
- Review Markdown links, examples and wording after documentation edits. Do not
  commit or push the source repository or wiki unless the user authorizes it.
- If the wiki is unavailable, prepare the documentation changes locally and
  report the outstanding synchronization instead of claiming the wiki was updated.
