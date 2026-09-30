es-module-lexer 3.0.2 (MIT), minimal pure-JavaScript build, copied without changes.
Upstream: https://github.com/guybedford/es-module-lexer
Package: https://registry.npmjs.org/es-module-lexer/-/es-module-lexer-3.0.2.tgz
Package SHA-512 (base64): BuIB67FngDSyQ/dpQNOZybwdEBDUGJQvOqwWr4ha/ufYiqzuEwPkKO2zLhRAgay28tStRIHUeWmszZAJo3GCOg==
Resource SHA-256: c1f3e9c6ab07531ad311a3a03d4a61a5e4d148482a64109e47e735c39f58ef46

The minimal build exposes decoded import specifiers and source offsets. The asm.js
variant runs in the GraalJS runtime already used for asset compilation; it needs
neither Node.js nor WebAssembly. Only the lexer executes; application code is data.

To update, verify the npm archive's integrity, copy dist/lexer.minimal.asm.js and
LICENSE unchanged, update this record, and run JsModuleImportsSpec and
JsModuleImportProcessorSpec, including concurrent compilation and cycle cases.
