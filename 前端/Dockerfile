# ── 阶段1: Node 构建 ──
FROM node:20-alpine AS builder
WORKDIR /build
COPY package.json package-lock.json ./
# 使用锁文件安装：npm install 会解析到 rollup 4.68，对 @element-plus/icons-vue 报
# "Code is nested too deeply"，锁定的 rollup 4.60.1 可正常构建。
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# ── 阶段2: Nginx 托管 ──
FROM nginx:alpine
COPY --from=builder /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8002
