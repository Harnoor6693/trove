# Categorisation

Category and category-rule management, implemented in `finance.trove.categorisation`.
Matching is tiered and stops at the first hit: the household's exact-match mappings
(rules with match type `EXACT`, and mappings learned from AI), then its prefix-match
mappings (rules with match type `PREFIX`), then built-in
community defaults (`CommunityMerchantRegistry`), then — only if the transaction owner has
enabled their own AI and their privacy tier allows it — a call through `finance.trove.ai`
to that member's own configured endpoint, and finally the `Uncategorised` system category.
Confident AI answers are learned back as mappings. Nothing is called by default; see
[AI Coach](./ai-coach).

| Method | Path | Purpose |
|---|---|---|
| `GET`/`POST` | `/v1/categories` | List / create categories |
| `PUT`/`DELETE` | `/v1/categories/{id}` | Replace / remove a household category (system categories are read-only) |
| `GET`/`POST` | `/v1/categories/rules` | List / create category rules |
| `PUT`/`DELETE` | `/v1/categories/rules/{id}` | Replace / remove a rule |
| `POST` | `/v1/categories/rules/reorder` | Set rule priority (breaks ties between prefix rules of equal specificity) |
| `POST` | `/v1/categories/rules/dry-run` | Show what the rule tiers (this household's mappings, then the community defaults, then Uncategorised) would categorise a merchant as, without saving; the AI tier is not consulted |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Categories`.
