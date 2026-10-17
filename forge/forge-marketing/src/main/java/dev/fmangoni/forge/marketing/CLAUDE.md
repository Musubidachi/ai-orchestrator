# marketing

Makes a project's marketing kit: one agent turn (`MarketingKit` with `MarketingBrief`) in
`runs/NNN/marketing`, then deterministic checks (`KitCheck`, `CampaignCheck`) whose problems are
shown on the report.

## Must not be traded away

- **Drafts only.** Forge never publishes, posts, uploads or spends. A person does, by hand.
- **Only on an explicit click.**
- **No claim without a feature.** Every claim cites an approved `feature.Feature`. No invented
  testimonials, numbers, awards or press.
- **Links go only to the deployed URL** from `deploy.Deployment`.
- **Campaigns stay within platform limits** and state a budget and a `stop_when`.

## Tests

`KitCheckTest` (hand-written kits, one per rule), `MarketingKitTest` (stub agent).
