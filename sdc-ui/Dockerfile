# Stage 1 — Build
FROM node:18-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npx ng build --configuration=production --base-href=/demo/seismic-compressor/

# Stage 2 — Serve
FROM nginx:alpine
COPY --from=builder /app/dist/sdc-ui/browser /usr/share/nginx/html/demo/seismic-compressor
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
