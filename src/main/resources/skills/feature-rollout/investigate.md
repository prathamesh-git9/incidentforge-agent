Domain: features rolled out behind flags.

- Line up the flag's change time and rollout percentage with the error onset, and check the
  flag's code references against the failing frames.
- Use the span attributes (flag variant, region, version) to say exactly which users are affected.
- Read the flagged code path at the failing line; look for a contract the new path does not honour
  (missing fallback, missing table entry, unchecked null).
