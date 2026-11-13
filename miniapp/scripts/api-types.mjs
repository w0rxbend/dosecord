// Generates src/api/schema.d.ts from the committed OpenAPI document of the backend
// (MINI_APP 13.1 step 4, ARCHITECTURE DEP-09). `--check` writes nothing and exits non-zero when
// the committed types differ from the generated ones, so the client cannot drift from the server.
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';

const SOURCE = new URL('../../api/openapi.json', import.meta.url);
const TARGET = fileURLToPath(new URL('../src/api/schema.d.ts', import.meta.url));
const HEADER =
  '// GENERATED FILE: do not edit by hand. Source: api/openapi.json.\n' +
  '// Regenerate with: pnpm --dir miniapp run api:generate\n\n';

const generated = HEADER + astToString(await openapiTS(SOURCE));

if (process.argv.includes('--check')) {
  let committed = '';
  try {
    committed = readFileSync(TARGET, 'utf8');
  } catch {
    // A missing file is a difference.
  }
  if (committed !== generated) {
    console.error('api:check: src/api/schema.d.ts differs from api/openapi.json');
    console.error('  regenerate with: pnpm --dir miniapp run api:generate');
    process.exit(1);
  }
  console.log('api:check: src/api/schema.d.ts matches api/openapi.json');
} else {
  writeFileSync(TARGET, generated);
  console.log('api:generate: wrote src/api/schema.d.ts');
}
