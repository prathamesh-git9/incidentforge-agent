Domain: features rolled out behind flags.

- Mitigation (turning the flag off) is a separate, reversible decision made by a person; the code
  fix must make it safe to turn the flag back on.
- Keep the fix inside the flagged code path; do not change the flag-off path or remove the flag.
- The reproduction test runs the flag-on path for the failing input and also checks that inputs
  that already worked keep their new-path behaviour.
