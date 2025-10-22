# embulk-input-file_glob

Based on standard embulk-input-file, but this one globs.

Usage
-----

`embulk-input-file_glob` provides the Embulk `file_glob` input plugin, which reads local files specified by the `path_glob` option. The value can be an exact path or any [Java glob](https://docs.oracle.com/javase/8/docs/api/java/nio/file/FileSystem.html#getPathMatcher-java.lang.String-) pattern.

```yaml
in:
  type: file_glob
  path_glob: /var/log/app/**/*.log
```

When the pattern does not contain any glob meta characters the path is treated as a plain prefix, keeping the legacy behaviour. The legacy `path_prefix` option is still accepted as an alias.

