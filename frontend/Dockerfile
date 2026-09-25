# ---- build ----
FROM node:20-alpine AS build
WORKDIR /build

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- serve ----
# The built app is static files; nginx serves them far more efficiently than a
# Node process, and the image is an order of magnitude smaller.
FROM nginx:1.27-alpine AS runtime
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
