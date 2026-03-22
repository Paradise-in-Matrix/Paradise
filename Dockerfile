FROM node:20-bookworm-slim

# Note currently this is not generating the WASM but is just copying my pre-compiled. The WASM needs are very large
# and for simplicity sake I wanted to have a build quick. We will write a full Dockerfile later for it.
# You can generate the WASM yourself with the build-all.sh (though you may wish to read what each is doing
# as you might not... need to install Rust that way.

RUN apt-get update && apt-get install -y \
    openjdk-17-jdk-headless \
    curl \
    bash \
    && curl -L https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh | bash \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN npm i
COPY . .
RUN npx shadow-cljs release app
RUN npx vite build


FROM nginx:stable-alpine
COPY --from=0 /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
