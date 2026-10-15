# Changelog

## 1.1.0

* New, opt-in `--object-args`: builds object-typed arguments (arrays, collections with the
  generic element type, and objects through public constructors, up to two levels of nesting),
  and allows access programs with up to five parameters instead of three. Arguments are
  re-created for every call and written inline in the generated tests (`Recipe`, `ObjectBuilder`).
* Without `--object-args`, behaviour is unchanged: for the same seed and budget, 1.1.0 writes
  the same tests as 1.0.0 (checked on the bundled TreeMap and BinTree subjects in all three modes).
* New unit tests: `ObjectArgsTest`.

## 1.0.0

* First release.
