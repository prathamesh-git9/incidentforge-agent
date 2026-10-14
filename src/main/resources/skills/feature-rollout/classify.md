Domain: features rolled out behind flags.

- Errors rising for one cohort (a region, a flag variant) while the running version is unchanged
  point at a flag change, not a deploy.
- Exceptions thrown from code that only runs when the flag is on are APPLICATION_CODE; the flag is
  the trigger, the code is the cause.
- Downstream-latency warnings that are just as frequent before the onset are background noise.
