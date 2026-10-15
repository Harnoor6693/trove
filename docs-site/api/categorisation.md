# Categorisation

Category and category-rule management, implemented in `finance.trove.categorisation`.
Matching is tiered: exact/fuzzy merchant match first (`MerchantCategoryMap`,
`CommunityMerchantRegistry`), then a rules tier, then — only if the household opted a
tier into AI — a call through `finance.trove.ai` to the member's own configured
endpoint. Nothing is called by default; see [AI Coach](./ai-coach).

| Method | Path | Purpose |
|---|---|---|
| `GET`/`POST` | `/v1/categories` | List / create categories |
| `GET`/`PATCH`/`DELETE` | `/v1/categories/{id}` | Read, update, remove a category |
| `GET`/`POST` | `/v1/categories/rules` | List / create category rules |
| `PATCH`/`DELETE` | `/v1/categories/rules/{id}` | Update / remove a rule |
| `POST` | `/v1/categories/rules/reorder` | Change rule evaluation order |
| `POST` | `/v1/categories/rules/dry-run` | Preview what a rule would match, without saving |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Categories`.
