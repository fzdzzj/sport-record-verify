## Spec Delta: add-web-test-baseline (TASK-215)

- Test-only + test infrastructure: adds vitest/happy-dom devDeps, `test` script, vitest config in vite.config.ts, and anchor tests for token.ts and client.ts interceptors (TASK-205 credential-posture locks). CI web job gains a test step. No behavioral surface change.
