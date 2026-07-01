FROM node:22-bookworm-slim

RUN apt-get update && apt-get install -y \
    openjdk-17-jdk-headless \
    curl \
    bash \
    git \
    python3 \
    build-essential \
    && curl -L https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh | bash \
    && rm -rf /var/lib/apt/lists/*

RUN corepack enable

WORKDIR /app

COPY . .

RUN npm install
RUN mkdir -p src-gen
RUN npm run release

FROM nginx:stable-alpine
COPY --from=0 /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
