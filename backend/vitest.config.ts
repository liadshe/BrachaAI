import { defineConfig } from 'vitest/config';

export default defineConfig({
    test: {
        environment: 'node',
        include: ['src/**/*.test.ts'],
        // src/config/jwt.ts calls process.exit(1) when this is missing, which
        // would kill the test worker on any machine without a backend/.env.
        env: {
            JWT_SECRET: 'test-secret',
        },
    },
});
