Domain: synchronous HTTP read paths.

- Fix the query shape: aggregate in the database (COUNT/AVG projections) and fetch only the rows
  the response needs (an indexed top-N). Prefer an existing repository method over a new one.
- Do not add or warm a cache as the fix: it hides the cold path (first view, evictions, long tail
  of products) instead of removing it.
- Prove it with a deterministic test that asserts on rows read, not on wall-clock time, and that
  checks the page content is unchanged.
