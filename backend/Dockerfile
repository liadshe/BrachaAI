# Stage 1: Build
FROM node:20-alpine AS builder
WORKDIR /app

# Copy package files and install all dependencies
COPY package*.json ./
RUN npm ci

# Copy source code and build (compiles TypeScript to JS)
COPY . .
RUN npm run build

# Stage 2: Production Runner
FROM node:20-alpine AS runner
WORKDIR /app

# Install only production dependencies for a smaller image footprint
COPY --from=builder /app/package*.json ./
RUN npm ci --omit=dev

# Copy the compiled output from the builder stage
COPY --from=builder /app/dist ./dist

# Expose the port your Express server listens on
EXPOSE 3000

# Start the application
CMD ["node", "dist/index.js"]