Domain: synchronous HTTP read paths.

- Read the code at the span's code.filepath/code.lineno before naming a suspect; cite the trace
  evidence for every code claim.
- Compare rows returned with what the response actually shows. Loading every child row to
  compute a count, an average or a top-N is a query-shape problem whose cost grows with data
  size, which is why only "big" entities are slow.
- Check whether slow requests were cache hits or misses before blaming or proposing a cache.
